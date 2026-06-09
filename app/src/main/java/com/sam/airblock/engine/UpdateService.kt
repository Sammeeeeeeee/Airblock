package com.sam.airblock.engine

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
import com.sam.airblock.data.AdsbApi
import com.sam.airblock.data.PhotoRepo
import com.sam.airblock.data.RouteResult
import com.sam.airblock.data.SettingsStore
import com.sam.airblock.data.WidgetState
import com.sam.airblock.data.WidgetStateStore
import com.sam.airblock.util.Squawk
import com.sam.airblock.util.Units
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
import java.io.IOException
import kotlin.math.min

/**
 * The 15-second update engine. A silent, minimum-importance foreground service
 * whose loop does ZERO work unless every gate passes:
 *   screen on · launcher (= widget) in foreground · no battery saver · widget placed.
 * When gated it just suspends — no timers spinning, no wakelocks, no network.
 */
class UpdateService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loop: Job? = null
    private val wake = MutableStateFlow(0L) // bumped by receivers/taps to re-check gates now

    private lateinit var gates: Gates
    private lateinit var location: LocationProvider
    private lateinit var photos: PhotoRepo
    private val api = AdsbApi()

    // Per-flight caches: one routeset call per callsign, one photo per hex
    private var cachedRoute: Pair<String, RouteResult?>? = null
    private var consecutiveErrors = 0

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            wake.value = System.currentTimeMillis()
        }
    }

    override fun onCreate() {
        super.onCreate()
        gates = Gates(this)
        location = LocationProvider(this)
        photos = PhotoRepo(this)
        startForeground()
        registerReceiver(screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction("android.os.action.POWER_SAVE_MODE_CHANGED")
        })
        loop = scope.launch { runLoop() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_TICK_NOW) wake.value = System.currentTimeMillis()
        return START_STICKY
    }

    override fun onDestroy() {
        unregisterReceiver(screenReceiver)
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ------------------------------------------------------------------ loop

    private suspend fun runLoop() {
        while (true) {
            if (!widgetPlaced()) {
                stopSelf()
                return
            }
            when {
                !gates.screenOn() || gates.powerSave() -> {
                    // Fully idle: wait for SCREEN_ON / power-save broadcast, no polling at all
                    val seen = wake.value
                    wake.first { it != seen }
                }
                !gates.launcherForeground() -> {
                    // Another app is open (Netflix etc.) — slow local re-check, no network
                    delay(HIDDEN_RECHECK_MS)
                }
                else -> {
                    val intervalMs = SettingsStore.read(this).intervalSec * 1000L
                    tick()
                    val backoff = if (consecutiveErrors > 0)
                        min(consecutiveErrors, 4).let { intervalMs * (1 shl it) } else intervalMs
                    // delay, but cut short if a tap/screen event bumps `wake`
                    val seen = wake.value
                    kotlinx.coroutines.withTimeoutOrNull(backoff) { wake.first { it != seen } }
                }
            }
        }
    }

    private suspend fun tick() {
        val settings = SettingsStore.read(this)
        val fix = location.currentFix()
        if (fix == null) {
            publish(WidgetState(status = WidgetState.Status.NO_LOCATION,
                updatedAt = System.currentTimeMillis()))
            return
        }
        try {
            val ac = api.closest(fix.lat, fix.lon, settings.radiusNm)
            consecutiveErrors = 0
            if (ac == null) {
                publish(WidgetState(status = WidgetState.Status.NO_AIRCRAFT,
                    updatedAt = System.currentTimeMillis()))
                return
            }

            // Route: cached per callsign — at most one routeset call per flight
            val callsign = ac.callsign
            val route: RouteResult? = when {
                callsign == null -> null
                cachedRoute?.first == callsign -> cachedRoute?.second
                else -> runCatching { api.route(callsign, fix.lat, fix.lon) }
                    .getOrNull()
                    .also { cachedRoute = callsign to it }
            }
            val origin = route?.airports?.firstOrNull()
            val dest = route?.airports?.lastOrNull()?.takeIf { it !== origin }

            // Photo: disk-cached per hex — at most one fetch per aircraft
            val photo = photos.photoFor(ac.hex)

            publish(WidgetState(
                status = WidgetState.Status.OK,
                callsign = callsign ?: ac.r ?: ac.hex.uppercase(),
                typeName = ac.desc?.let { prettyType(it) },
                typeCode = ac.t,
                registration = ac.r,
                hex = ac.hex,
                altitudeFt = ac.altitudeFt,
                onGround = ac.onGround,
                speedMph = ac.gs?.let { Units.ktsToMph(it) },
                distanceKm = ac.dst?.let { Units.nmToKm(it) },
                squawkAlert = Squawk.emergencyLabel(ac.squawk),
                originIata = origin?.iata,
                originCity = origin?.location,
                originFlag = Units.flagEmoji(origin?.countryIso2),
                destIata = dest?.iata,
                destCity = dest?.location,
                destFlag = Units.flagEmoji(dest?.countryIso2),
                photoPath = photo?.file?.absolutePath,
                photoCredit = photo?.photographer,
                updatedAt = System.currentTimeMillis(),
            ))
        } catch (e: IOException) {
            consecutiveErrors++
            Log.w(TAG, "tick failed (${consecutiveErrors}x): ${e.message}")
            // Keep showing last good data; widget marks it stale by timestamp
        }
    }

    /** "BOEING 737-900" -> "Boeing 737-900" */
    private fun prettyType(desc: String): String =
        desc.split(" ").joinToString(" ") { w ->
            if (w.any { it.isDigit() }) w else w.lowercase().replaceFirstChar { it.uppercase() }
        }

    private suspend fun publish(state: WidgetState) {
        val previous = WidgetStateStore.read(this)
        WidgetStateStore.write(this, state)
        // Skip the RemoteViews churn when nothing the user can see has changed
        if (previous.renderKey() != state.renderKey()) {
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
        const val ACTION_TICK_NOW = "com.sam.airblock.TICK_NOW"

        fun start(context: Context, tickNow: Boolean = false) {
            val intent = Intent(context, UpdateService::class.java)
            if (tickNow) intent.action = ACTION_TICK_NOW
            context.startForegroundService(intent)
        }
    }
}
