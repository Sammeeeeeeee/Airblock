package com.sam.airblock.engine

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.sam.airblock.widget.FlightStrip
import com.sam.airblock.widget.Widgets

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
        if (Widgets.anyPlaced(context)) {
            UpdateService.start(context)
            KeepAliveWorker.schedule(context)
        }
        // A new version may draw the strip differently: refresh the picker's preview
        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            val pending = goAsync()
            Thread {
                try { FlightStrip.publishPickerPreview(context) } finally { pending.finish() }
            }.start()
        }
    }
}
