package com.sam.airblock.engine

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.drawable.Icon
import com.sam.airblock.R
import com.sam.airblock.data.Aircraft
import com.sam.airblock.data.EventLog
import com.sam.airblock.data.NotifyStore
import com.sam.airblock.data.PhotoRepo
import com.sam.airblock.data.PlaneAlertRepo
import com.sam.airblock.data.SettingsStore
import com.sam.airblock.data.WidgetStateStore
import com.sam.airblock.util.AircraftIcons
import com.sam.airblock.util.AlertGroup
import com.sam.airblock.util.AlertGroups
import com.sam.airblock.util.SpecialType
import com.sam.airblock.util.Squawk
import com.sam.airblock.util.Units
import kotlinx.serialization.json.JsonPrimitive
import java.io.File

/**
 * Aircraft-alert notifications. [evaluate] is called from the tick path with
 * the aircraft that was ALREADY fetched — it never touches the network (the
 * Planespotters photo is attached only when it is on disk).
 *
 * Anti-spam: an airframe never re-fires while it remains the closest aircraft,
 * and once it leaves, [NotifyStore.COOLDOWN_MS] must pass before it can alert
 * again. Emergency squawks are exempt from the cooldown (and from per-aircraft
 * mutes) — a 7700 overhead is safety-relevant.
 */
class AlertNotifier(private val context: Context) {

    suspend fun evaluate(
        ac: Aircraft,
        alert: PlaneAlertRepo.Alert?,
        typeName: String?,
        distanceKm: Double?,
        fixLat: Double,
        fixLon: Double,
        prevClosestHex: String?,
        photos: PhotoRepo,
    ) {
        val prefs = NotifyStore.read(context)
        if (!prefs.enabled) return
        val nm = context.getSystemService(NotificationManager::class.java)
        if (!nm.areNotificationsEnabled()) return // permission revoked later

        val hex = ac.hex.uppercase()
        val matched = AlertGroups.match(
            ac.dbFlags, ac.squawk, ac.category, alert?.category,
            prefs.groups, prefs.includeCategories, prefs.excludeCategories,
            watch = prefs.watch.any { it.matches(ac.callsign, ac.r, ac.hex) },
        )
        if (matched.isEmpty()) return

        // One notification only, on the highest-priority matching channel.
        // Muted airframes stay silent for everything except an emergency squawk.
        val group =
            if (hex in prefs.muted) matched.firstOrNull { it == AlertGroup.EMERGENCY } ?: return
            else matched.first()

        val state = NotifyStore.readState(context)
        val stillClosest = prevClosestHex?.uppercase() == hex
        if (stillClosest && state.sessionHex == hex && group.id in state.sessionGroups) return
        if (group != AlertGroup.EMERGENCY &&
            System.currentTimeMillis() - (state.recent[hex] ?: 0L) < NotifyStore.COOLDOWN_MS
        ) return

        // Reuse only what the tick already cached — never fetch
        val photoFile =
            if (photos.isCached(ac.hex)) photos.photoFor(ac.hex)?.file else null
        post(group, ac, alert, typeName, distanceKm, fixLat, fixLon, photoFile)
        NotifyStore.recordNotified(context, hex, group.id)
        if (SettingsStore.read(context).logEnabled) {
            EventLog.append(context, "ALERT — ${group.label}: ${ac.callsign ?: ac.r ?: hex}")
        }
    }

    /** Debug-only end-to-end test: fabricated military jet, real posting path. */
    suspend fun postTest() {
        ensureChannels(context)
        // Borrow the widget's current photo so the BigPicture path is exercised
        val photoFile = WidgetStateStore.read(context).photoPath
            ?.let { File(it) }?.takeIf { it.exists() }
        val ac = Aircraft(
            hex = "TEST01", flight = "AIRBLK1", r = "ZK349", t = "EUFI",
            desc = "EUROFIGHTER Typhoon", altBaro = JsonPrimitive(2400),
            gs = 415.0, category = "A2", dbFlags = 1L, dst = 3.5,
        )
        post(AlertGroup.MILITARY, ac, alert = null, typeName = "Eurofighter Typhoon",
            distanceKm = 6.5, fixLat = 0.0, fixLon = 0.0, photoFile = photoFile)
    }

    private fun post(
        group: AlertGroup,
        ac: Aircraft,
        alert: PlaneAlertRepo.Alert?,
        typeName: String?,
        distanceKm: Double?,
        fixLat: Double,
        fixLon: Double,
        photoFile: File?,
    ) {
        val nm = context.getSystemService(NotificationManager::class.java)
        ensureChannels(context)

        val hex = ac.hex.uppercase()
        val ident = ac.r ?: ac.callsign ?: hex
        val bearing = if (ac.lat != null && ac.lon != null)
            Units.compass8(Units.bearingDeg(fixLat, fixLon, ac.lat, ac.lon)) else null
        val body = listOfNotNull(
            listOfNotNull(typeName, ident).joinToString(" ").ifEmpty { null },
            distanceKm?.let { Units.formatKm(it) + (bearing?.let { b -> " $b" } ?: "") },
            ac.altitudeFt?.let { Units.formatAltitude(it) },
        ).joinToString(" · ")
        // Why this alert fired: the emergency squawk, the watchlist, or the
        // plane-alert-db category (+ operator), or the live-data special type
        val reason = when (group) {
            AlertGroup.EMERGENCY -> Squawk.emergencyLabel(ac.squawk)
            AlertGroup.WATCHLIST -> "On your watchlist"
            else -> alert?.category?.let { c ->
                val name = AlertGroups.displayName(c)
                alert.operator?.let { "$name · $it" } ?: name
            } ?: SpecialType.classify(ac.category, ac.dbFlags)
        }

        val muteIntent = Intent(context, MuteReceiver::class.java)
            .setAction(MuteReceiver.ACTION_MUTE)
            .putExtra(MuteReceiver.EXTRA_HEX, hex)
            .putExtra(MuteReceiver.EXTRA_LABEL,
                listOfNotNull(typeName, ident).joinToString(" ").ifEmpty { hex })
        val mute = PendingIntent.getBroadcast(
            context, hex.hashCode(), muteIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val b = Notification.Builder(context, group.channelId)
            .setSmallIcon(AircraftIcons.iconFor(ac.t, ac.category))
            .setContentTitle(group.title)
            .setContentText(body)
            .setSubText(reason)
            .addAction(Notification.Action.Builder(
                Icon.createWithResource(context, R.drawable.ic_mute),
                "Mute this aircraft", mute).build())
        photoFile?.let { f ->
            BitmapFactory.decodeFile(f.absolutePath)?.let { bmp ->
                b.setLargeIcon(bmp)
                b.setStyle(Notification.BigPictureStyle()
                    .bigPicture(bmp)
                    .bigLargeIcon(null as Icon?))
            }
        }
        nm.notify(hex, NOTIF_ID_ALERT, b.build())
    }

    companion object {
        /** Tagged per airframe hex; the FGS notification owns id 1. */
        const val NOTIF_ID_ALERT = 2

        /**
         * First-generation channels were IMPORTANCE_DEFAULT, which posts
         * silently into the shade with no heads-up. Android restores a deleted
         * channel's old settings when the same id is recreated, so raising the
         * importance required NEW ids — the v1 ids are deleted here.
         */
        private val LEGACY_CHANNEL_IDS = listOf(
            "alert_emergency", "alert_gov", "alert_military", "alert_services",
            "alert_drones", "alert_historic", "alert_other",
        )

        /** Idempotent — safe to call on every post and from settings. */
        fun ensureChannels(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java)
            LEGACY_CHANNEL_IDS.forEach { nm.deleteNotificationChannel(it) }
            AlertGroup.entries.forEach { g ->
                // HIGH = heads-up banner + sound: these are rare events and the
                // whole point is being told NOW; each channel can still be
                // demoted per-category in system settings.
                val ch = NotificationChannel(
                    g.channelId, g.label, NotificationManager.IMPORTANCE_HIGH,
                )
                if (g == AlertGroup.EMERGENCY) {
                    ch.description = "Aircraft squawking 7500 / 7600 / 7700. " +
                        "Tip: this channel can be allowed to override Do Not Disturb " +
                        "in system settings."
                }
                nm.createNotificationChannel(ch)
            }
        }
    }
}
