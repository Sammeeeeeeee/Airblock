package com.sam.airblock.widget

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.updateAll
import com.sam.airblock.data.WidgetStateStore
import com.sam.airblock.engine.UpdateService

/**
 * Tap anywhere on the widget → show the refreshing icon immediately, then
 * kick the engine for a real tick (widget taps carry a temporary exemption
 * that lets us revive the foreground service even from the background).
 */
class RefreshAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        WidgetStateStore.update(context) { it.copy(refreshing = true) }
        AirblockWidget().updateAll(context)
        if (!UpdateService.start(context, tickNow = true)) {
            // FGS start denied — clear the spinner instead of leaving it stuck
            WidgetStateStore.update(context) { it.copy(refreshing = false) }
            AirblockWidget().updateAll(context)
        }
    }
}
