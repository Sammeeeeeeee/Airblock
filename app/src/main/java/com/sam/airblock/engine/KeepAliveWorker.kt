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
import com.sam.airblock.widget.AirblockWidgetReceiver
import java.util.concurrent.TimeUnit

/**
 * Safety net: every 15 min (WorkManager minimum) make sure the update service
 * is alive while a widget is placed. Costs nothing when the service is already
 * running; resurrects it if the OS killed our process.
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
        if (!serviceRunning(ctx)) UpdateService.start(ctx)
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
