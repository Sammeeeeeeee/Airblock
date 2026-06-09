package com.sam.airblock.widget

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import com.sam.airblock.engine.UpdateService

/** Tap anywhere on the widget → immediate refresh tick. */
class RefreshAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        UpdateService.start(context, tickNow = true)
    }
}
