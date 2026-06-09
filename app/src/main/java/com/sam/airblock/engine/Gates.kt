package com.sam.airblock.engine

import android.app.ActivityManager
import android.app.KeyguardManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.PowerManager

/**
 * The gates that decide whether a tick is allowed to do ANY work.
 * All checks are local and effectively free — no network, no wakelocks.
 */
class Gates(private val context: Context) {

    private val power = context.getSystemService(PowerManager::class.java)
    private val keyguard = context.getSystemService(KeyguardManager::class.java)
    private val usage = context.getSystemService(UsageStatsManager::class.java)
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)

    // Stateful foreground tracking with OVERLAPPING query windows: usage events
    // are often written to the stats DB seconds late, so a strictly incremental
    // window would miss them permanently and wedge the gate. Re-reading a bit
    // of already-seen history is idempotent (we just take the latest event).
    private var lastQueryTime = 0L
    private var lastForegroundPkg: String? = null

    /** Every installed launcher — the user may run a non-default one (Niagara etc.). */
    val launcherPackages: Set<String> by lazy {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        context.packageManager.queryIntentActivities(intent, 0)
            .mapNotNull { it.activityInfo?.packageName }
            .toSet()
    }

    /** Last known foreground package — for diagnostics. */
    fun foregroundPackage(): String? = lastForegroundPkg

    fun screenOn(): Boolean = power.isInteractive

    /** True when the device is unlocked — the lock screen covers the widget. */
    fun unlocked(): Boolean = !keyguard.isKeyguardLocked

    fun powerSave(): Boolean = power.isPowerSaveMode

    /**
     * True when the foreground app is the launcher — i.e. the widget is actually
     * visible — or our own settings screen. Requires the one-time Usage Access
     * grant; without it this degrades to "true" so the widget still works
     * (gated by screen-on only) instead of silently never updating.
     */
    fun launcherForeground(): Boolean {
        if (launcherPackages.isEmpty()) return true
        val now = System.currentTimeMillis()
        // Cover the gap since the previous query plus a generous overlap for
        // late-written events; first call looks back far enough to find the
        // current foreground app's launch event.
        val lookback = if (lastQueryTime == 0L) MAX_LOOKBACK_MS
        else (now - lastQueryTime + OVERLAP_MS).coerceIn(MIN_WINDOW_MS, MAX_LOOKBACK_MS)
        val events = usage.queryEvents(now - lookback, now)
        val e = UsageEvents.Event()
        var latest = 0L
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            if (e.eventType == UsageEvents.Event.ACTIVITY_RESUMED && e.timeStamp >= latest) {
                latest = e.timeStamp
                lastForegroundPkg = e.packageName
            }
        }
        lastQueryTime = now
        val fg = lastForegroundPkg
            ?: return true // no usage access / no data — fail open to screen-on gating
        // Our own package only counts when the UI is actually on screen — the
        // foreground SERVICE keeps the process alive, so without this check a
        // stale "Airblock was last resumed" reading keeps the gate wedged open
        // and the widget ticks while other apps are in front.
        return fg in launcherPackages ||
            (fg == context.packageName && selfUiVisible())
    }

    /** True when OUR activity is actually visible (not just the FGS running). */
    private fun selfUiVisible(): Boolean {
        val info = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(info)
        return info.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    }

    /**
     * "wifi", "cell", "other", or null when offline. VPNs (AdGuard, Tailscale,
     * NextDNS…) report TRANSPORT_VPN and would otherwise mask the real
     * transport — resolve the underlying network in that case.
     */
    fun networkTransport(): String? {
        val active = connectivity.activeNetwork ?: return null
        val caps = connectivity.getNetworkCapabilities(active) ?: return null
        transportOf(caps)?.let { return it }
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
            @Suppress("DEPRECATION")
            connectivity.allNetworks.forEach { n ->
                val c = connectivity.getNetworkCapabilities(n) ?: return@forEach
                if (!c.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                    transportOf(c)?.let { return it }
                }
            }
        }
        return "other"
    }

    private fun transportOf(caps: NetworkCapabilities): String? = when {
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "wifi"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cell"
        else -> null
    }

    /** System Data Saver active on a metered connection — do no network at all. */
    fun dataSaverOn(): Boolean =
        connectivity.isActiveNetworkMetered &&
            connectivity.restrictBackgroundStatus ==
            ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED

    /**
     * The single source of truth for "how should we refresh right now",
     * combining transport, the user's per-network modes and Data Saver.
     * Used identically by the service loop and the keep-alive worker.
     */
    fun effectiveMode(settings: com.sam.airblock.data.Settings): Pair<com.sam.airblock.data.NetMode, String> {
        val transport = networkTransport()
        return when {
            transport == null ->
                com.sam.airblock.data.NetMode.OFF to "offline"
            dataSaverOn() ->
                com.sam.airblock.data.NetMode.OFF to "data saver"
            transport == "wifi" -> settings.wifiMode to "wifi"
            transport == "cell" -> settings.dataMode to "mobile data"
            // Unknown transport: honour the MORE restrictive of the two rules
            else -> maxOf(settings.wifiMode, settings.dataMode) to "unknown network"
        }
    }

    /** Whether the user has granted Usage Access (Settings > Special app access). */
    fun hasUsageAccess(): Boolean {
        val appOps = context.getSystemService(android.app.AppOpsManager::class.java)
        return when (appOps.unsafeCheckOpNoThrow(
            android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
            android.os.Process.myUid(), context.packageName,
        )) {
            android.app.AppOpsManager.MODE_ALLOWED -> true
            // MODE_DEFAULT falls back to the event probe (rare)
            android.app.AppOpsManager.MODE_DEFAULT -> {
                val now = System.currentTimeMillis()
                usage.queryEvents(now - MAX_LOOKBACK_MS, now).hasNextEvent()
            }
            else -> false
        }
    }

    companion object {
        private const val MAX_LOOKBACK_MS = 4L * 60 * 60 * 1000
        private const val MIN_WINDOW_MS = 3L * 60 * 1000
        private const val OVERLAP_MS = 60L * 1000
    }
}
