package com.sam.airblock.engine

import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.glance.appwidget.updateAll
import com.sam.airblock.R
import com.sam.airblock.data.EventLog
import com.sam.airblock.data.SettingsStore
import com.sam.airblock.data.WidgetStateStore
import com.sam.airblock.widget.AirblockWidget
import com.sam.airblock.widget.AirblockWidgetReceiver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.min

/**
 * The 15-second update engine. A silent, minimum-importance foreground service
 * whose loop does ZERO work unless every gate passes:
 *   screen on · launcher (= widget) in foreground · no battery saver · widget placed.
 * When gated it just suspends — no timers spinning, no wakelocks, no network.
 *
 * Android 12+ forbids starting an FGS from the background, so [start] can be
 * denied (e.g. from the keep-alive worker). That's fine: [KeepAliveWorker]
 * refreshes the widget inline as a fallback, and any widget tap (temporary
 * background-start exemption) revives this service.
 */
class UpdateService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loop: Job? = null
    private val wake = MutableStateFlow(0L) // bumped by receivers/taps to re-check gates now
    private val tickNowRequested = java.util.concurrent.atomic.AtomicBoolean(false)

    private lateinit var gates: Gates
    private lateinit var ticker: Ticker
    private var lastPhase: String? = null

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            wake.value = System.currentTimeMillis()
        }
    }

    override fun onCreate() {
        super.onCreate()
        gates = Gates(this)
        ticker = Ticker(this)
        startForeground()
        registerReceiver(screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT) // unlock — leave idle without waiting
            addAction("android.os.action.POWER_SAVE_MODE_CHANGED")
        })
        loop = scope.launch { runLoop() }
        Log.d(TAG, "service started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_TICK_NOW) {
            tickNowRequested.set(true)
            wake.value = System.currentTimeMillis()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        unregisterReceiver(screenReceiver)
        scope.cancel()
        Log.d(TAG, "service destroyed")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ------------------------------------------------------------------ loop

    private suspend fun runLoop() {
        while (true) {
            if (!widgetPlaced()) {
                Log.d(TAG, "no widget placed — stopping")
                stopSelf()
                return
            }
            val forced = tickNowRequested.getAndSet(false)
            when {
                // A tap on the widget PROVES it is visible — refresh right now,
                // bypassing the launcher/battery-saver gates for this one tick.
                forced && gates.screenOn() -> {
                    Log.d(TAG, "tap — forced tick")
                    logPhase("active", "manual refresh (tap)")
                    tickAndWait()
                }
                !gates.screenOn() || !gates.unlocked() || gates.powerSave() -> {
                    // Fully idle: wait for SCREEN_ON / USER_PRESENT / power-save
                    // broadcast, no polling at all. Battery saver is the only
                    // gated state the user can actually see — flag it.
                    Log.d(TAG, "gated (screen=${gates.screenOn()} unlocked=${gates.unlocked()} " +
                        "powerSave=${gates.powerSave()}) — idle")
                    when {
                        gates.powerSave() && gates.screenOn() -> {
                            setPausedFlag("battery saver")
                            logPhase("powersave", "paused — battery saver")
                        }
                        !gates.screenOn() -> logPhase("screenoff", "paused — screen off")
                        else -> logPhase("locked", "paused — locked")
                    }
                    awaitWake(null)
                }
                !gates.launcherForeground() -> {
                    // Another app is open (Netflix etc.) — slow local re-check,
                    // no network. Interruptible so a tap reacts instantly.
                    Log.d(TAG, "hidden: fg=${gates.foregroundPackage()} — recheck in 30s")
                    logPhase("hidden", "paused — ${gates.foregroundPackage() ?: "another app"} in front")
                    awaitWake(HIDDEN_RECHECK_MS)
                }
                else -> {
                    Log.d(TAG, "visible (fg=${gates.foregroundPackage() ?: "?"})")
                    logPhase("active", "updates running")
                    tickAndWait()
                }
            }
        }
    }

    private suspend fun tickAndWait() {
        val intervalMs = SettingsStore.read(this).intervalSec * 1000L
        val started = System.currentTimeMillis()
        ticker.tick()
        // One flaky request shouldn't slow the widget down: keep the normal
        // interval on the first failure, back off only when errors repeat.
        val errs = ticker.consecutiveErrors
        val target = if (errs > 1)
            min(intervalMs * (1 shl min(errs - 1, 3)), MAX_BACKOFF_MS)
        else intervalMs
        // True cadence: the network time counts toward the interval
        val elapsed = System.currentTimeMillis() - started
        awaitWake((target - elapsed).coerceAtLeast(1_000L))
    }

    /** Activity-log a phase transition exactly once. */
    private suspend fun logPhase(phase: String, message: String) {
        if (phase == lastPhase) return
        lastPhase = phase
        if (SettingsStore.read(this).logEnabled) EventLog.append(this, message)
    }

    /** Suspend until `wake` is bumped (tap/screen event), at most [timeoutMs]. */
    private suspend fun awaitWake(timeoutMs: Long?) {
        val seen = wake.value
        if (timeoutMs == null) wake.first { it != seen }
        else withTimeoutOrNull(timeoutMs) { wake.first { it != seen } }
    }

    /** Surface/clear the "paused" status icon without touching the rest of the state. */
    private suspend fun setPausedFlag(reason: String?) {
        val cur = WidgetStateStore.read(this)
        if (cur.pausedReason != reason) {
            WidgetStateStore.write(this, cur.copy(pausedReason = reason))
            AirblockWidget().updateAll(this)
        }
    }

    private fun widgetPlaced(): Boolean =
        AppWidgetManager.getInstance(this)
            .getAppWidgetIds(ComponentName(this, AirblockWidgetReceiver::class.java))
            .isNotEmpty()

    // ---------------------------------------------------------- notification

    private fun startForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_MIN)
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_flight)
            .setContentTitle(getString(R.string.notif_text))
            .setOngoing(true)
            .build()
        startForeground(NOTIF_ID, notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
    }

    companion object {
        private const val TAG = "Airblock"
        private const val CHANNEL_ID = "airblock_updates"
        private const val NOTIF_ID = 1
        private const val HIDDEN_RECHECK_MS = 30_000L
        private const val MAX_BACKOFF_MS = 120_000L
        const val ACTION_TICK_NOW = "com.sam.airblock.TICK_NOW"

        /** Try to start the engine; false when Android denies a background FGS start. */
        fun start(context: Context, tickNow: Boolean = false): Boolean {
            val intent = Intent(context, UpdateService::class.java)
            if (tickNow) intent.action = ACTION_TICK_NOW
            return try {
                context.startForegroundService(intent)
                true
            } catch (e: ForegroundServiceStartNotAllowedException) {
                Log.w(TAG, "FGS start denied (background) — will retry on next user interaction")
                false
            }
        }
    }
}
