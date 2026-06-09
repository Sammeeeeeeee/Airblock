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
import com.sam.airblock.R
import com.sam.airblock.data.SettingsStore
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

    private lateinit var gates: Gates
    private lateinit var ticker: Ticker

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
            addAction("android.os.action.POWER_SAVE_MODE_CHANGED")
        })
        loop = scope.launch { runLoop() }
        Log.d(TAG, "service started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_TICK_NOW) wake.value = System.currentTimeMillis()
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
            when {
                !gates.screenOn() || gates.powerSave() -> {
                    // Fully idle: wait for SCREEN_ON / power-save broadcast, no polling at all
                    Log.d(TAG, "gated (screen=${gates.screenOn()} powerSave=${gates.powerSave()}) — idle")
                    val seen = wake.value
                    wake.first { it != seen }
                }
                !gates.launcherForeground() -> {
                    // Another app is open (Netflix etc.) — slow local re-check, no network
                    delay(HIDDEN_RECHECK_MS)
                }
                else -> {
                    val intervalMs = SettingsStore.read(this).intervalSec * 1000L
                    ticker.tick()
                    val backoff = if (ticker.consecutiveErrors > 0)
                        min(ticker.consecutiveErrors, 4).let { intervalMs * (1 shl it) }
                    else intervalMs
                    // delay, but cut short if a tap/screen event bumps `wake`
                    val seen = wake.value
                    withTimeoutOrNull(backoff) { wake.first { it != seen } }
                }
            }
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
