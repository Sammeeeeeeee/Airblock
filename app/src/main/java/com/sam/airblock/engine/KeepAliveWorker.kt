package com.sam.airblock.engine

import android.app.ActivityManager
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.sam.airblock.data.NetMode
import com.sam.airblock.data.SettingsStore
import com.sam.airblock.widget.AirblockWidgetReceiver
import java.util.concurrent.TimeUnit

/**
 * Safety net: every 15 min (WorkManager minimum) while a widget is placed.
 * Android 12+ usually DENIES starting an FGS from a worker (background), so
 * when the service is dead this does the next best thing: one inline refresh
 * tick right here (workers may do network), keeping the widget at worst
 * 15 min stale until a widget tap revives the 15 s service.
 */
class KeepAliveWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val widgetPlaced = AppWidgetManager.getInstance(ctx)
            .getAppWidgetIds(ComponentName(ctx, AirblockWidgetReceiver::class.java))
            .isNotEmpty()
        if (!widgetPlaced) {
            unschedule(ctx)
            return Result.success()
        }
        if (!serviceRunning(ctx)) {
            val revived = UpdateService.start(ctx)
            if (!revived) {
                // Service stays dead until the user taps the widget; at least
                // refresh the data once so what's shown isn't ancient. Must
                // respect ALL the same gates the service does, incl. the
                // user's per-network modes and system Data Saver.
                val gates = Gates(ctx)
                val s = SettingsStore.read(ctx)
                val mode = when {
                    gates.dataSaverOn() -> NetMode.OFF
                    gates.networkTransport() == "wifi" -> s.wifiMode
                    gates.networkTransport() == "cell" -> s.dataMode
                    else -> NetMode.NORMAL
                }
                if (gates.screenOn() && gates.unlocked() && !gates.powerSave() &&
                    gates.launcherForeground() && mode != NetMode.OFF
                ) {
                    Ticker(ctx).tick()
                }
            }
        }
        return Result.success()
    }

    @Suppress("DEPRECATION") // getRunningServices still works for our OWN service
    private fun serviceRunning(ctx: Context): Boolean {
        val am = ctx.getSystemService(ActivityManager::class.java)
        return am.getRunningServices(Int.MAX_VALUE)
            .any { it.service.className == UpdateService::class.java.name }
    }

    companion object {
        private const val NAME = "airblock_keepalive"

        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<KeepAliveWorker>(15, TimeUnit.MINUTES).build()
            )
        }

        fun unschedule(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(NAME)
        }
    }
}
