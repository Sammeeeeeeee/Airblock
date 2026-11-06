package com.sam.airblock.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import com.sam.airblock.data.WidgetStateStore
import com.sam.airblock.engine.KeepAliveWorker
import com.sam.airblock.engine.UpdateService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The Flight strip's provider. Taps arrive here as broadcasts; each one also
 * revives the refresh engine, since a widget tap is what lets Android start
 * its foreground service from the background.
 */
class FlightStripReceiver : AppWidgetProvider() {

    override fun onEnabled(context: Context) {
        UpdateService.start(context, tickNow = true)
        KeepAliveWorker.schedule(context)
    }

    override fun onDisabled(context: Context) {
        // Keep the keep-alive job while an Airblock card is still placed
        if (!Widgets.anyPlaced(context)) KeepAliveWorker.unschedule(context)
    }

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) =
        inBackground { ids.forEach { FlightStrip.update(context, it) } }

    override fun onAppWidgetOptionsChanged(
        context: Context, manager: AppWidgetManager, id: Int, newOptions: Bundle,
    ) = inBackground { FlightStrip.update(context, id) }

    override fun onDeleted(context: Context, ids: IntArray) = FlightStrip.forget(ids)

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID)
        when (intent.action) {
            FlightStrip.ACTION_PAGE -> {
                val page = intent.getIntExtra(FlightStrip.EXTRA_PAGE, -1)
                inBackground {
                    // Revive a dead engine; a tap on stale data also refreshes it
                    val state = WidgetStateStore.read(context)
                    UpdateService.start(context,
                        tickNow = state.markedStale || state.isStaleAt(System.currentTimeMillis()))
                    if (page >= 0 && id != AppWidgetManager.INVALID_APPWIDGET_ID) {
                        FlightStrip.onPageTapped(context, id, page)
                    }
                }
            }
            FlightStrip.ACTION_REFRESH -> inBackground {
                WidgetStateStore.update(context) { it.copy(refreshing = true) }
                Widgets.updateAll(context)
                if (!UpdateService.start(context, tickNow = true)) {
                    // FGS start denied — clear the spinner instead of leaving it stuck
                    WidgetStateStore.update(context) { it.copy(refreshing = false) }
                    Widgets.updateAll(context)
                }
            }
            else -> super.onReceive(context, intent)
        }
    }

    /** Run [block] off the main thread, keeping the broadcast alive until it ends. */
    private fun BroadcastReceiver.inBackground(block: suspend () -> Unit) {
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                block()
            } finally {
                pending.finish()
            }
        }
    }
}
