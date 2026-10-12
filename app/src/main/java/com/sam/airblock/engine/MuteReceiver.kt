package com.sam.airblock.engine

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.sam.airblock.data.EventLog
import com.sam.airblock.data.NotifyStore
import com.sam.airblock.data.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Handles taps on an alert. The "Mute this aircraft" action remembers the
 * airframe in [NotifyStore] (managed under Settings → Notifications) and
 * dismisses the notification that carried the button; a tap on the alert
 * itself just dismisses it.
 */
class MuteReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val hex = intent.getStringExtra(EXTRA_HEX) ?: return
        if (intent.action == ACTION_DISMISS) {
            // setAutoCancel already removes it on most shades; this makes
            // sure on the rest
            context.getSystemService(NotificationManager::class.java)
                .cancel(hex.uppercase(), AlertNotifier.NOTIF_ID_ALERT)
            return
        }
        if (intent.action != ACTION_MUTE) return
        val label = intent.getStringExtra(EXTRA_LABEL) ?: hex
        val result = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                NotifyStore.mute(context, hex, label)
                context.getSystemService(NotificationManager::class.java)
                    .cancel(hex.uppercase(), AlertNotifier.NOTIF_ID_ALERT)
                if (SettingsStore.read(context).logEnabled) {
                    EventLog.append(context, "muted aircraft $label")
                }
            } finally {
                result.finish()
            }
        }
    }

    companion object {
        const val ACTION_MUTE = "com.sam.airblock.MUTE_AIRCRAFT"
        const val ACTION_DISMISS = "com.sam.airblock.DISMISS_ALERT"
        const val EXTRA_HEX = "hex"
        const val EXTRA_LABEL = "label"
    }
}
