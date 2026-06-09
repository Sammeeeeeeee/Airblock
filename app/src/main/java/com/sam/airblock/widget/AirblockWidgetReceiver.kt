package com.sam.airblock.widget

import android.content.Context
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import com.sam.airblock.engine.KeepAliveWorker
import com.sam.airblock.engine.UpdateService

class AirblockWidgetReceiver : GlanceAppWidgetReceiver() {

    override val glanceAppWidget: GlanceAppWidget = AirblockWidget()

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        UpdateService.start(context, tickNow = true)
        KeepAliveWorker.schedule(context)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        // The service notices the last widget is gone and stops itself;
        // also drop the keep-alive job so nothing runs at all.
        KeepAliveWorker.unschedule(context)
    }
}
