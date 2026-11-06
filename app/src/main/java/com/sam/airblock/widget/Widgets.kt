package com.sam.airblock.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import androidx.glance.appwidget.updateAll

/**
 * Both home-screen widgets — the Airblock card and the Flight strip — as one
 * thing for the engine: what is placed, and redraw everything placed. The
 * engine never needs to know which kinds the user chose.
 */
object Widgets {

    /** Ids of the placed Airblock cards. */
    fun cardIds(context: Context): IntArray = ids(context, AirblockWidgetReceiver::class.java)

    /** Ids of the placed Flight strips. */
    fun stripIds(context: Context): IntArray = ids(context, FlightStripReceiver::class.java)

    /** True while at least one widget of either kind is on a home screen. */
    fun anyPlaced(context: Context): Boolean =
        cardIds(context).isNotEmpty() || stripIds(context).isNotEmpty()

    /** Redraw every placed widget from the stored state. */
    suspend fun updateAll(context: Context) {
        if (cardIds(context).isNotEmpty()) AirblockWidget().updateAll(context)
        FlightStrip.updateAll(context)
    }

    /** Drop every cached render bitmap of both widgets (Force full restart). */
    fun resetRenderCaches() {
        AirblockWidget.resetRenderCaches()
        FlightStrip.resetRenderCaches()
    }

    private fun ids(context: Context, receiver: Class<*>): IntArray =
        AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context, receiver))
}
