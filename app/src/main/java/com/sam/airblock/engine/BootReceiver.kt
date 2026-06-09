package com.sam.airblock.engine

import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.sam.airblock.widget.AirblockWidgetReceiver

/** Restarts the update engine after a reboot — but only if a widget is placed. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val ids = AppWidgetManager.getInstance(context)
            .getAppWidgetIds(ComponentName(context, AirblockWidgetReceiver::class.java))
        if (ids.isNotEmpty()) UpdateService.start(context)
    }
}
