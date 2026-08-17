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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonPrimitive
import java.io.File

/**
 * Aircraft-alert notifications. [evaluate] is called from the tick path with
 * the aircraft that was ALREADY fetched — deciding whether to alert costs no
 * network at all.
 *
 * The photo arrives in two phases so a rare overhead aircraft is never delayed
 * behind an image download: the alert posts immediately with whatever is on
 * disk, and if this airframe has no cached photo the Planespotters lookup runs
 * off the tick path and re-posts the SAME notification (same tag + id) with the
 * picture attached. `setOnlyAlertOnce` means that update is silent, and the
 * re-post is skipped if the alert has already been dismissed.
 *
 * Anti-spam: an airframe never re-fires while it remains the closest aircraft,
 * and once it leaves, [NotifyStore.COOLDOWN_MS] must pass before it can alert
 * again. Emergency squawks are exempt from the cooldown (and from per-aircraft
 * mutes) — a 7700 overhead is safety-relevant.
 */
class AlertNotifier(private val context: Context) {

    /**
     * Photo fetches outlive the tick that started them (they must not hold it
     * up), but never the process — a lost download just means no picture.
     */
    private val photoScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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

        // Phase 1: post NOW with the disk-cached photo, if this airframe has one
        val cached = if (photos.isCached(ac.hex)) photos.photoFor(ac.hex) else null
        post(group, ac, alert, typeName, distanceKm, fixLat, fixLon, cached)
        NotifyStore.recordNotified(context, hex, group.id)
        // Phase 2: first sighting of this airframe — fetch the photo off the
        // tick path and fill it into the notification that is already up.
        if (cached == null) photoScope.launch {
            val fetched = withTimeoutOrNull(PHOTO_FETCH_TIMEOUT_MS) {
                runCatching { photos.photoFor(ac.hex) }.getOrNull()
            } ?: return@launch
            // Don't resurrect an alert the user has already swiped away
            if (nm.activeNotifications.none { it.tag == hex && it.id == NOTIF_ID_ALERT }) return@launch
            post(group, ac, alert, typeName, distanceKm, fixLat, fixLon, fetched)
        }
        if (SettingsStore.read(context).logEnabled) {
            EventLog.append(context, "ALERT — ${group.label}: ${ac.callsign ?: ac.r ?: hex}")
        }
    }

    /** Debug-only end-to-end test: fabricated military jet, real posting path. */
    suspend fun postTest() {
        ensureChannels(context)
        // Borrow the widget's current photo so the BigPicture path is exercised
        val state = WidgetStateStore.read(context)
        val photo = state.photoPath?.let { File(it) }?.takeIf { it.exists() }
            ?.let { PhotoRepo.CachedPhoto(it, state.photoCredit) }
        val ac = Aircraft(
            hex = "TEST01", flight = "AIRBLK1", r = "ZK349", t = "EUFI",
            desc = "EUROFIGHTER Typhoon", altBaro = JsonPrimitive(2400),
            gs = 415.0, category = "A2", dbFlags = 1L, dst = 3.5,
        )
        post(AlertGroup.MILITARY, ac, alert = null, typeName = "Eurofighter Typhoon",
            distanceKm = 6.5, fixLat = 0.0, fixLon = 0.0, photo = photo)
    }

    private fun post(
        group: AlertGroup,
        ac: Aircraft,
        alert: PlaneAlertRepo.Alert?,
        typeName: String?,
        distanceKm: Double?,
        fixLat: Double,
        fixLon: Double,
        photo: PhotoRepo.CachedPhoto?,
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
            // The photo arrives as a second post of the same notification —
            // update it in place, don't buzz the user twice for one aircraft
            .setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(
                Icon.createWithResource(context, R.drawable.ic_mute),
                "Mute this aircraft", mute).build())
        photo?.let { p ->
            BitmapFactory.decodeFile(p.file.absolutePath)?.let { bmp ->
                b.setLargeIcon(bmp)
                b.setStyle(Notification.BigPictureStyle()
                    .bigPicture(bmp)
                    // Collapsed shows the thumbnail, expanded the full photo —
                    // keeping both would show the same image twice.
                    // No summary text either: BigPictureStyle falls back to the
                    // content text, so anything set here just repeats a line
                    // the notification is already showing.
                    .bigLargeIcon(null as Icon?))
            }
        }
        nm.notify(hex, NOTIF_ID_ALERT, b.build())
    }

    companion object {
        /** Tagged per airframe hex; the FGS notification owns id 1. */
        const val NOTIF_ID_ALERT = 2

        /**
         * Give up on the photo rather than leave a request hanging: the alert
         * itself is already on screen, the picture is a bonus.
         */
        private const val PHOTO_FETCH_TIMEOUT_MS = 20_000L

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
