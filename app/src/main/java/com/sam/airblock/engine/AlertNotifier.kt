package com.sam.airblock.engine

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
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
import com.sam.airblock.util.AlertLabels
import com.sam.airblock.util.SpecialType
import com.sam.airblock.util.Squawk
import com.sam.airblock.util.Units
import com.sam.airblock.util.WatchEntry
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
        // One-off watches that have outlived their window go first, so an
        // expired entry can't fire on this tick.
        NotifyStore.pruneExpiredWatches(context)
        val prefs = NotifyStore.read(context)
        if (!prefs.enabled) return
        val nm = context.getSystemService(NotificationManager::class.java)
        if (!nm.areNotificationsEnabled()) return // permission revoked later

        val hex = ac.hex.uppercase()
        // The user's own entry for this aircraft, if any — it carries the note
        // shown on the alert, and its "last seen" stamp is what keeps a
        // one-off watch alive while the aircraft is still around.
        val watch = prefs.watch.firstOrNull { it.matches(ac.callsign, ac.r, ac.hex) }
        if (watch != null) NotifyStore.markWatchSeen(context, watch)
        val matched = AlertGroups.match(
            ac.dbFlags, ac.squawk, ac.category, alert?.category,
            prefs.groups, prefs.includeCategories, prefs.excludeCategories,
            watch = watch != null,
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
        post(group, ac, alert, typeName, distanceKm, fixLat, fixLon, cached, watch)
        NotifyStore.recordNotified(context, hex, group.id)
        // Phase 2: first sighting of this airframe — fetch the photo off the
        // tick path and fill it into the notification that is already up.
        if (cached == null) photoScope.launch {
            val fetched = withTimeoutOrNull(PHOTO_FETCH_TIMEOUT_MS) {
                runCatching { photos.photoFor(ac.hex) }.getOrNull()
            } ?: return@launch
            // Don't resurrect an alert the user has already swiped away
            if (nm.activeNotifications.none { it.tag == hex && it.id == NOTIF_ID_ALERT }) return@launch
            post(group, ac, alert, typeName, distanceKm, fixLat, fixLon, fetched, watch)
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
        watch: WatchEntry? = null,
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
        // The database's own names for this airframe, resolved the same way the
        // widget badge resolves them (AlertLabels): the CATEGORY leads, because
        // that is what the settings screen's switches are labelled with; the
        // TAG is the extra detail, and rides on the photo as a pill below.
        val category = alert?.category?.let(AlertGroups::displayName)
        val tag = alert?.tags?.firstOrNull()?.let(AlertGroups::displayName)
        val special = SpecialType.classify(ac.category, ac.dbFlags)
        val primary = AlertLabels.primary(category, tag, special)
        val pill = AlertLabels.secondary(primary, tag)
        // Why this alert fired: the emergency squawk, the watchlist (where the
        // user's own note is the most useful thing we can say), or the
        // plane-alert-db category + operator
        val reason = when (group) {
            AlertGroup.EMERGENCY -> Squawk.emergencyLabel(ac.squawk)
            AlertGroup.WATCHLIST -> watch?.note?.takeIf { it.isNotBlank() }
                ?: "On your watchlist"
            else -> primary?.let { name ->
                alert?.operator?.let { "$name · $it" } ?: name
            }
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
        val bitmap = photo?.let { BitmapFactory.decodeFile(it.file.absolutePath) }
        if (bitmap != null) {
            b.setLargeIcon(bitmap)
            b.setStyle(Notification.BigPictureStyle()
                // The tag is burned into the picture as a pill: Android's
                // notification templates give us no way to style a run of text,
                // and a custom RemoteViews layout would mean re-implementing
                // (and re-theming) the whole notification. Drawing it matches
                // the widget's badge exactly and can't be restyled away.
                .bigPicture(pill?.let { drawPill(bitmap, it) } ?: bitmap)
                // Collapsed shows the thumbnail, expanded the full photo —
                // keeping both would show the same image twice.
                // No summary text either: BigPictureStyle falls back to the
                // content text, so anything set here just repeats a line
                // the notification is already showing.
                .bigLargeIcon(null as Icon?))
        } else if (pill != null) {
            // No photo to draw on — the tag still gets its own line in the
            // expanded view rather than being lost
            b.setStyle(Notification.BigTextStyle().bigText(body).setSummaryText(pill))
        }
        nm.notify(hex, NOTIF_ID_ALERT, b.build())
    }

    /**
     * A copy of [src] with the database tag drawn into the bottom-left as a
     * rounded pill — the same shield-and-caps badge the widget renders, so one
     * aircraft reads the same in the shade and on the home screen.
     *
     * Everything is sized off the bitmap so it lands right on any thumbnail,
     * and a failure here (an odd config, no memory) simply returns the original
     * picture: the badge is decoration, never a reason to lose the photo.
     */
    private fun drawPill(src: Bitmap, text: String): Bitmap = runCatching {
        val out = src.copy(Bitmap.Config.ARGB_8888, true) ?: return src
        val canvas = Canvas(out)
        val h = out.height.toFloat()
        val textSize = (h * 0.055f).coerceIn(13f, 34f)
        val padH = textSize * 0.6f
        val padV = textSize * 0.38f
        val margin = h * 0.045f
        val label = text.uppercase()

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            this.textSize = textSize
            typeface = android.graphics.Typeface.create(
                android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
        }
        val iconSize = textSize * 0.95f
        val iconGap = textSize * 0.35f
        val textWidth = textPaint.measureText(label)
        val pillWidth = padH * 2 + iconSize + iconGap + textWidth
        val pillHeight = padV * 2 + textSize
        val left = margin
        val top = h - margin - pillHeight
        val rect = RectF(left, top, left + pillWidth, top + pillHeight)

        // Material error red, matching the widget badge's GlanceTheme error
        canvas.drawRoundRect(rect, pillHeight / 2, pillHeight / 2,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = PILL_COLOR })
        context.getDrawable(R.drawable.ic_shield)?.let { shield ->
            shield.setTint(Color.WHITE)
            val iconTop = (top + pillHeight / 2 - iconSize / 2).toInt()
            val iconLeft = (left + padH).toInt()
            shield.setBounds(iconLeft, iconTop,
                (iconLeft + iconSize).toInt(), (iconTop + iconSize).toInt())
            shield.draw(canvas)
        }
        canvas.drawText(label, left + padH + iconSize + iconGap,
            top + padV + textSize * 0.82f, textPaint)
        out
    }.getOrDefault(src)

    companion object {
        /** Tagged per airframe hex; the FGS notification owns id 1. */
        const val NOTIF_ID_ALERT = 2

        /**
         * Give up on the photo rather than leave a request hanging: the alert
         * itself is already on screen, the picture is a bonus.
         */
        private const val PHOTO_FETCH_TIMEOUT_MS = 20_000L

        /** Material 3 error red — the widget badge's colour, hard-coded here
         *  because a notification bitmap has no theme to read it from. */
        private const val PILL_COLOR = 0xFFB3261E.toInt()

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
