package com.sam.airblock.engine

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.PowerManager

/**
 * The gates that decide whether a tick is allowed to do ANY work.
 * All checks are local and effectively free — no network, no wakelocks.
 */
class Gates(private val context: Context) {

    private val power = context.getSystemService(PowerManager::class.java)
    private val usage = context.getSystemService(UsageStatsManager::class.java)

    // Stateful foreground tracking: we only query usage events SINCE the last
    // check (a 15–30 s window) and carry the answer forward, so sitting inside
    // Netflix for an hour stays correctly detected with tiny incremental queries.
    private var lastQueryTime = 0L
    private var lastForegroundPkg: String? = null

    /** Default launcher package, resolved once (changes only if the user switches launchers). */
    val launcherPackage: String? by lazy {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        context.packageManager.resolveActivity(intent, 0)?.activityInfo?.packageName
    }

    fun screenOn(): Boolean = power.isInteractive

    fun powerSave(): Boolean = power.isPowerSaveMode

    /**
     * True when the foreground app is the launcher — i.e. the widget is actually
     * visible — or our own settings screen. Requires the one-time Usage Access
     * grant; without it this degrades to "true" so the widget still works
     * (gated by screen-on only) instead of silently never updating.
     */
    fun launcherForeground(): Boolean {
        val launcher = launcherPackage ?: return true
        val now = System.currentTimeMillis()
        // First call (or after long idle): look back far enough to find the
        // current foreground app's launch event.
        val from = if (lastQueryTime == 0L || now - lastQueryTime > MAX_LOOKBACK_MS)
            now - MAX_LOOKBACK_MS else lastQueryTime
        val events = usage.queryEvents(from, now)
        val e = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            if (e.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                lastForegroundPkg = e.packageName
            }
        }
        lastQueryTime = now
        val fg = lastForegroundPkg
            ?: return true // no usage access / no data — fail open to screen-on gating
        return fg == launcher || fg == context.packageName
    }

    /** Whether the user has granted Usage Access (Settings > Special app access). */
    fun hasUsageAccess(): Boolean {
        val now = System.currentTimeMillis()
        val events = usage.queryEvents(now - MAX_LOOKBACK_MS, now)
        return events.hasNextEvent()
    }

    companion object {
        private const val MAX_LOOKBACK_MS = 4L * 60 * 60 * 1000
    }
}
