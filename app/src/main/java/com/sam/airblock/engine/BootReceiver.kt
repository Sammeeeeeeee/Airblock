package com.sam.airblock.engine

import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.sam.airblock.widget.AirblockWidgetReceiver

/**
 * Restarts the update engine after a reboot or an app update — but only if a
 * widget is placed. An update stops the running engine just as a reboot does,
 * and otherwise nothing restarts it until the widget is tapped. Both
 * broadcasts may start a foreground service from the background.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return
        val ids = AppWidgetManager.getInstance(context)
            .getAppWidgetIds(ComponentName(context, AirblockWidgetReceiver::class.java))
        if (ids.isNotEmpty()) {
            UpdateService.start(context)
            KeepAliveWorker.schedule(context)
        }
    }
}
