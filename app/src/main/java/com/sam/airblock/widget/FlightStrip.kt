package com.sam.airblock.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.RectF
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.util.SizeF
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import com.sam.airblock.R
import com.sam.airblock.data.WidgetState
import com.sam.airblock.data.WidgetStateStore
import com.sam.airblock.ui.MainActivity
import com.sam.airblock.util.AircraftIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The Flight strip: the nearest aircraft as the WHOLE photo, a card beside it
 * (callsign with the model's and airline's logos, the route under it) and a
 * stats pill coming off the photo that rolls through more stats, on a tap and
 * on its own. Only those shapes cover the wallpaper; the rest is see-through.
 *
 * Taps: the photo opens Airblock, the card refreshes (a spinner shows until
 * it is done), the pill rolls to its next page.
 *
 * Plain RemoteViews rather than Glance: the pill is a ViewFlipper and the
 * card's roll-in a layout animation, both in hand-written layouts.
 */
object FlightStrip {

    private const val TAG = "FlightStrip"
    const val ACTION_PAGE = "com.sam.airblock.strip.PAGE"
    const val ACTION_REFRESH = "com.sam.airblock.strip.REFRESH"
    const val EXTRA_PAGE = "com.sam.airblock.strip.PAGE_INDEX"

    private val lock = Mutex()
    /** What each widget was last sent, so an unchanged frame isn't sent again. */
    private val lastSent = HashMap<Int, String>()
    /** A tapped stats page to move on from, applied once by the next frame. */
    private val pageJump = HashMap<Int, Int>()
    /** The aircraft each widget last showed: a new one starts on the first page. */
    private val lastHex = HashMap<Int, String?>()
    /**
     * The card was tapped and that refresh is still running. Only then does a
     * spinner show on good data: the engine also refreshes every few seconds
     * by itself, and those should not blink.
     */
    @Volatile private var refreshTapped = false

    fun onRefreshTapped() { refreshTapped = true }

    /** Redraw every placed strip from the stored state. */
    suspend fun updateAll(context: Context) = withContext(Dispatchers.Default) {
        val ids = Widgets.stripIds(context)
        if (ids.isEmpty()) return@withContext
        val state = WidgetStateStore.read(context)
        lock.withLock { ids.forEach { push(context, it, state, force = false) } }
    }

    /** Redraw one strip even if nothing changed (resize, tap, system update). */
    suspend fun update(context: Context, id: Int) = withContext(Dispatchers.Default) {
        val state = WidgetStateStore.read(context)
        lock.withLock { push(context, id, state, force = true) }
    }

    /** A stats page was tapped: show the one after it. */
    suspend fun onPageTapped(context: Context, id: Int, index: Int) {
        synchronized(pageJump) { pageJump[id] = index + 1 }
        update(context, id)
    }

    fun resetRenderCaches() {
        Cache.clear()
        synchronized(seeds) { seeds.clear() }
        synchronized(lastSent) { lastSent.clear() }
    }

    fun forget(ids: IntArray) {
        synchronized(lastSent) { ids.forEach { lastSent.remove(it) } }
        synchronized(pageJump) { ids.forEach { pageJump.remove(it) } }
        synchronized(lastHex) { ids.forEach { lastHex.remove(it) } }
    }

    /**
     * The strip for [state] at a given size with no placed widget behind it:
     * the widget picker's preview, and off-device render checks.
     */
    fun preview(
        context: Context,
        state: WidgetState,
        widthDp: Float,
        heightsDp: List<Float>,
        nowMs: Long = System.currentTimeMillis(),
        jump: Int? = null,
    ): RemoteViews = render(context, Frame(
        id = AppWidgetManager.INVALID_APPWIDGET_ID,
        state = state,
        content = StripContents.of(state, nowMs),
        tiers = StripTier.forHeights(heightsDp),
        widthDp = widthDp,
        fontScale = context.resources.configuration.fontScale.coerceIn(0.9f, 1.15f),
        status = StripContents.status(state, nowMs),
        jump = jump,
    ))

    /**
     * The widget picker's preview, drawn by the strip itself so it shows the
     * real thing in the phone's own colours (Android 15+ pickers; older ones
     * use the static previewImage). Sent once per app version: the system
     * rate-limits these calls.
     */
    fun publishPickerPreview(context: Context) {
        if (android.os.Build.VERSION.SDK_INT < 35) return
        val prefs = context.getSharedPreferences(PREVIEW_PREFS, Context.MODE_PRIVATE)
        val version = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
        }.getOrDefault(0L)
        if (prefs.getLong("preview_version", -1L) == version) return
        val now = System.currentTimeMillis()
        val sent = runCatching {
            AppWidgetManager.getInstance(context).setWidgetPreview(
                android.content.ComponentName(context, FlightStripReceiver::class.java),
                android.appwidget.AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN,
                preview(context, sample(now), DEFAULT_WIDTH_DP, listOf(StripTier.FULL.idealHeightDp), now))
        }.getOrDefault(false)
        if (sent) prefs.edit().putLong("preview_version", version).apply()
    }

    /** A plausible aircraft for previews: no photo, so nothing is shipped in the APK. */
    fun sample(now: Long) = WidgetState(
        status = WidgetState.Status.OK, callsign = "BAW8KC", typeName = "Airbus A320neo",
        typeCode = "A20N", registration = "G-TTNV", altitudeFt = 8000, speedMph = 297,
        mach = 0.38, distanceKm = 15.0, bearingDeg = 315.0, elevationDeg = 9.0,
        verticalRateFpm = 1800, originIata = "LHR", destIata = "GLA", destCity = "Glasgow",
        routeProgress = 0.35f, etaEpochMs = now + 34 * 60_000 + 30_000, category = "A3",
        updatedAt = now, staleAfterMs = now + 3_600_000,
    )

    private const val PREVIEW_PREFS = "flight_strip_preview"

    // ---- rendering --------------------------------------------------------

    private fun push(context: Context, id: Int, state: WidgetState, force: Boolean) {
        try {
            val awm = AppWidgetManager.getInstance(context)
            val now = System.currentTimeMillis()
            if (!state.refreshing) refreshTapped = false
            val newAircraft = synchronized(lastHex) {
                val had = lastHex.containsKey(id)
                (lastHex.put(id, state.hex) != state.hex) && had && state.hex != null
            }
            val jump = synchronized(pageJump) { pageJump.remove(id) } ?: if (newAircraft) 0 else null
            val frame = Frame.of(context, id, state, now, awm.getAppWidgetOptions(id), jump, refreshTapped)
            val key = frame.key()
            if (!force && jump == null && synchronized(lastSent) { lastSent[id] } == key) return
            awm.updateAppWidget(id, render(context, frame))
            synchronized(lastSent) { lastSent[id] = key }
        } catch (e: Exception) {
            // A rendering bug must never take the refresh engine down with it
            Log.w(TAG, "strip $id not drawn: $e")
        }
    }

    /** Everything one render depends on — also its de-duplication key. */
    private data class Frame(
        val id: Int,
        val state: WidgetState,
        val content: StripContent,
        val tiers: List<StripTier>,
        val widthDp: Float,
        val fontScale: Float,
        val status: StripStatus,
        /** Stats page to show now (after a tap), or null to leave the stack where it is. */
        val jump: Int?,
    ) {
        fun key(): String = listOf(
            content, tiers, widthDp, fontScale, status, StripContents.palette(state),
            state.status, state.typeCode, state.category, state.hex,
        ).joinToString("|")

        companion object {
            fun of(
                context: Context, id: Int, s: WidgetState, now: Long, options: Bundle?, jump: Int?,
                tapped: Boolean,
            ): Frame {
                val (widthDp, heights) = reportedSize(options)
                return Frame(
                    id = id, state = s, content = StripContents.of(s, now),
                    tiers = StripTier.forHeights(heights), widthDp = widthDp,
                    fontScale = context.resources.configuration.fontScale.coerceIn(0.9f, 1.15f),
                    status = StripContents.status(s, now, tapped), jump = jump,
                )
            }

            /** Smallest reported width, and every reported height (dp). */
            private fun reportedSize(options: Bundle?): Pair<Float, List<Float>> {
                @Suppress("DEPRECATION") // typed overload is API 33; minSdk is 31
                val sizes = options?.getParcelableArrayList<SizeF>(AppWidgetManager.OPTION_APPWIDGET_SIZES)
                if (!sizes.isNullOrEmpty()) return sizes.minOf { it.width } to sizes.map { it.height }
                val minW = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH) ?: 0
                val minH = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT) ?: 0
                val maxH = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT) ?: 0
                val w = if (minW > 0) minW.toFloat() else DEFAULT_WIDTH_DP
                val hs = listOf(minH, maxH).filter { it > 0 }.map { it.toFloat() }
                return w to hs.ifEmpty { listOf(StripTier.FULL.idealHeightDp) }
            }
        }
    }

    private fun render(context: Context, frame: Frame): RemoteViews {
        val art = StripArt(context)
        val sized = frame.tiers.associate { tier ->
            SizeF(1f, tier.idealHeightDp) to renderTier(context, art, frame, tier)
        }
        return sized.values.singleOrNull() ?: RemoteViews(sized)
    }

    private fun renderTier(context: Context, art: StripArt, f: Frame, tier: StripTier): RemoteViews {
        val c = f.content
        val d = art.density
        fun px(dp: Float) = (dp * d).roundToInt()
        val colors = palette(art, f.state, c.airlineLogo)
        val rv = RemoteViews(context.packageName, R.layout.strip_root)
        val k = f.fontScale

        // ---- geometry, in whole pixels: the stats stack hides its other
        // pages behind the front one only if the two line up exactly.
        // Photo and card are one band, joined like an M3 Expressive connected
        // group; the stats pill docks along the band's bottom, from the photo
        // across the join into the card, so it costs no extra height.
        val photoH = px(tier.photoDp)
        val photoW = art.photoWidth(c.photoPath, photoH)
        val cardLeft = photoW + px(JOIN_GAP_DP)
        val cardMax = (f.widthDp * d).toInt() - cardLeft - px(EDGE_DP)
        val showStats = tier.stats && c.pages.isNotEmpty()
        val pillH = px(tier.pillDp)
        val pillLeft = photoW - px(tier.pillOverlapDp)
        val pillTop = photoH - px(tier.pillInsetDp) - pillH
        val pillEnd = px(PILL_END_DP)

        // ---- the card: its lines first, then whatever width the pill needs
        val pad = px(CARD_PAD_H_DP)
        val lines = cardLines(art, f, tier, k, max(0, cardMax - 2 * pad).toFloat(), colors)
        var cardW = ceil(lines.width).toInt() + 2 * pad
        var pages: StatsLayout? = null
        if (showStats) {
            pages = statsLayout(art, c.pages, tier, k, cardLeft + cardMax - pillEnd - pillLeft, pillH)
            cardW = max(cardW, pillLeft + pages.pillW + pillEnd - cardLeft)
        }
        cardW = cardW.coerceAtMost(cardMax)
        // The pill's hairline of wallpaper equals its inset, so the cut-out
        // meets the band's bottom edge and the photo is notched cleanly
        val cut = if (showStats) CutOut(
            RectF(pillLeft.toFloat(), pillTop.toFloat(), (photoW + pillH).toFloat(), (pillTop + pillH).toFloat()),
            radius = pillH / 2f, gap = px(tier.pillInsetDp).toFloat(),
        ) else null

        photo(context, art, rv, f, photoW, photoH, cut, colors)
        status(context, rv, f, colors)
        rv.removeAllViews(R.id.strip_card_slot)
        rv.addStableView(R.id.strip_card_slot,
            card(context, f, tier, lines, cardW, photoH, colors, centred = !showStats), 1)
        rv.marginPx(R.id.strip_card_slot, RemoteViews.MARGIN_START, cardLeft)

        if (pages != null) {
            stats(context, rv, f, pages, colors, pillLeft, pillTop, cardLeft + cardW - pillEnd - pillLeft, pillH)
        } else rv.setViewVisibility(R.id.strip_stats_frame, View.GONE)
        return rv
    }

    private fun photo(
        context: Context, art: StripArt, rv: RemoteViews, f: Frame,
        w: Int, h: Int, cut: CutOut?, colors: Palette,
    ) {
        val d = art.density
        val radii = Radii(OUTER_DP * d, INNER_DP * d)
        val ok = f.state.status == WidgetState.Status.OK
        rv.sizePx(R.id.strip_photo, w, h)
        val cutKey = cut?.rect?.toShortString()
        val picture = f.content.photoPath?.let { path ->
            Cache.get("photo|$path|$w|$h|$cutKey|$radii") { art.photo(path, w, h, radii, cut) }
        }
        if (picture != null) {
            rv.setImageViewBitmap(R.id.strip_photo, picture)
            rv.setViewVisibility(R.id.strip_photo_icon, View.GONE)
            rv.setContentDescription(R.id.strip_photo, "Photo of ${f.content.title}. Opens Airblock")
        } else {
            // No photo (yet): a tinted tile with the type's silhouette. The
            // tile is a separate view from the photo so a tint never lands on it.
            rv.setImageViewBitmap(R.id.strip_photo_tile,
                Cache.get("tile|$w|$h|$cutKey|$radii") { art.tile(w, h, radii, cut) })
            rv.tint(R.id.strip_photo_tile, "setImageTintList",
                if (ok) colors.badge else Tint.Res(R.color.strip_secondary_container))
            val iconRes = when {
                ok -> AircraftIcons.iconFor(f.state.typeCode, f.state.category)
                f.state.status == WidgetState.Status.NO_AIRCRAFT -> R.drawable.ic_flight
                f.state.status == WidgetState.Status.NO_LOCATION -> R.drawable.ic_distance
                else -> R.drawable.ic_sync
            }
            val iconPx = (h * if (ok) 0.62f else 0.42f).roundToInt()
            rv.setImageViewBitmap(R.id.strip_photo_icon,
                Cache.get("icon|$iconRes|$iconPx") { art.icon(iconRes, iconPx, if (ok) 0f else 45f) })
            rv.tint(R.id.strip_photo_icon, "setImageTintList",
                if (ok) colors.onBadge else Tint.Res(R.color.strip_on_secondary_container))
            rv.setViewVisibility(R.id.strip_photo_icon, View.VISIBLE)
            // Centre the icon on the tile (the group is a plain FrameLayout)
            rv.marginPx(R.id.strip_photo_icon, RemoteViews.MARGIN_START, (w - iconPx) / 2)
            rv.marginPx(R.id.strip_photo_icon, RemoteViews.MARGIN_TOP, (h - iconPx) / 2)
            rv.setContentDescription(R.id.strip_photo, "Open Airblock")
        }
        rv.setViewVisibility(R.id.strip_photo, if (picture != null) View.VISIBLE else View.GONE)
        rv.setViewVisibility(R.id.strip_photo_tile, if (picture != null) View.GONE else View.VISIBLE)
        rv.sizePx(R.id.strip_photo_tile, w, h)
        rv.setOnClickPendingIntent(R.id.strip_photo, openApp(context))
        rv.setOnClickPendingIntent(R.id.strip_photo_tile, openApp(context))
    }

    private fun status(context: Context, rv: RemoteViews, f: Frame, colors: Palette) {
        val st = f.status
        if (st == StripStatus.NONE) {
            rv.setViewVisibility(R.id.strip_status, View.GONE)
            return
        }
        rv.setViewVisibility(R.id.strip_status, View.VISIBLE)
        rv.tint(R.id.strip_status, "setBackgroundTintList", colors.container)
        val spinning = st == StripStatus.SPINNER
        rv.setViewVisibility(R.id.strip_status_spin, if (spinning) View.VISIBLE else View.GONE)
        rv.setViewVisibility(R.id.strip_status_icon, if (spinning) View.GONE else View.VISIBLE)
        if (spinning) {
            rv.tint(R.id.strip_status_spin, "setIndeterminateTintList", colors.accent)
            rv.setContentDescription(R.id.strip_status, "Refreshing")
        } else {
            val (icon, tint, what) = when (st) {
                StripStatus.PAUSED_BATTERY -> Triple(R.drawable.ic_battery_saver, R.color.strip_tertiary, "Paused for battery saver")
                StripStatus.PAUSED -> Triple(R.drawable.ic_pause, R.color.strip_tertiary, "Paused")
                StripStatus.ERROR -> Triple(R.drawable.ic_warning, R.color.strip_error, "Refresh failed")
                else -> Triple(R.drawable.ic_clock, R.color.strip_outline, "Out of date")
            }
            rv.setImageViewResource(R.id.strip_status_icon, icon)
            rv.setColorStateList(R.id.strip_status_icon, "setImageTintList", tint)
            rv.setContentDescription(R.id.strip_status, "$what. Tap to refresh")
        }
        rv.setOnClickPendingIntent(R.id.strip_status, broadcast(context, f.id, ACTION_REFRESH))
    }

    /** The card's two lines, drawn and measured before the card is sized. */
    private class CardLines(
        val title: Bitmap, val mark: Bitmap?, val tile: Bitmap?,
        val line2: List<Pair<Bitmap, Tint>>, val width: Float, val height: Float,
        val description: String,
    )

    /**
     * Callsign + model mark + airline tile, then the route. Pieces give way in
     * order when the width runs out — the mark shrinks, then goes, before the
     * callsign is ever cut.
     */
    private fun cardLines(
        art: StripArt, f: Frame, tier: StripTier, k: Float, budget: Float, colors: Palette,
    ): CardLines {
        val d = art.density
        val c = f.content
        val tilePx = (tier.tileDp * d).roundToInt()
        val tile = c.airlineLogo?.let { path ->
            Cache.get("airline|$path|$tilePx") { art.airlineTile(path, tilePx, tilePx * 0.3f) }
        }
        val tileW = tile?.let { it.width + MARK_GAP_DP * d } ?: 0f
        fun markAt(markDp: Float): Bitmap? = when (val m = c.mark) {
            is StripContent.Mark.Logo -> {
                val px = (markDp * k * d).roundToInt()
                Cache.get("mark|${m.path}|$px|${m.dropMaker}") { art.mark(m.path, px, m.dropMaker) }
            }
            is StripContent.Mark.Name -> {
                val px = markDp / tier.markDp * tier.line2Sp * k * d
                Cache.get("name|${m.text}|$px") { art.text(m.text, StripType.SUPPORT, px) }
            }
            null -> null
        }
        var titlePx = tier.titleSp * k * d
        var mark: Bitmap? = null
        for (step in FIT_STEPS) {
            val tPx = tier.titleSp * step * k * d
            val m = markAt(tier.markDp * step)
            val need = art.measure(c.title, StripType.HERO, tPx) + tileW +
                (m?.let { it.width + MARK_GAP_DP * d } ?: 0f)
            titlePx = tPx
            if (need <= budget) { mark = m; break }
        }
        val markW = mark?.let { it.width + MARK_GAP_DP * d } ?: 0f
        val titleMax = max(titlePx * 2, budget - tileW - markW)
        val title = Cache.get("title|${c.title}|$titlePx|${titleMax.roundToInt()}") {
            art.text(c.title, StripType.HERO, titlePx, maxWidthPx = titleMax)
        }

        // Line 2: the first option that fits
        val codePx = tier.codeSp * k * d
        val textPx = tier.line2Sp * k * d
        val minRoute = codePx * 1.9f
        val maxRoute = codePx * 3.4f
        val runGap = RUN_GAP_DP * d
        fun fixedW(runs: List<StripContent.Run>) = runs.sumOf { r ->
            when (r) {
                is StripContent.Text -> (if (r.code) art.measure(r.text, StripType.HERO, codePx)
                else art.measure(r.text, StripType.SUPPORT, textPx, numeric = true)).toDouble()
                is StripContent.Route -> 0.0
            }
        }.toFloat() + (runs.size - 1) * runGap
        val runs = c.line2.firstOrNull { r ->
            fixedW(r) + (if (r.any { it is StripContent.Route }) minRoute else 0f) <= budget
        } ?: c.line2.last()
        val line1W = title.width + markW + tileW
        // The route line stretches to the callsign line's width, within limits
        val routeW = (max(line1W, fixedW(runs) + minRoute) - fixedW(runs))
            .coerceIn(minRoute, maxRoute).coerceAtMost(max(minRoute, budget - fixedW(runs))).roundToInt()
        // Codes and the small text share one baseline: both drawn in the codes' line box
        val box = ceil(StripType.HERO.ascentEm * codePx) to ceil(StripType.HERO.descentEm * codePx)
        var spent = 0f
        val line2 = runs.take(4).map { r ->
            val room = (budget - spent).roundToInt()
            val bmp = when (r) {
                is StripContent.Text -> if (r.code) Cache.get("code|${r.text}|$codePx") {
                    art.text(r.text, StripType.HERO, codePx)
                } else Cache.get("l2|${r.text}|$textPx|$room") {
                    art.text(r.text, StripType.SUPPORT, textPx, numeric = true,
                        maxWidthPx = room.toFloat(), box = box)
                }
                is StripContent.Route -> Cache.get("route|${r.progress?.let { (it * 40).roundToInt() }}|$routeW|$codePx") {
                    art.route(r.progress, routeW, codePx)
                }
            }
            spent += bmp.width + runGap
            bmp to when (r) {
                is StripContent.Route -> colors.accent
                is StripContent.Text -> colors.tone(r.tone)
            }
        }
        val line2W = spent - runGap
        val h = max(title.height.toFloat(), tile?.height?.toFloat() ?: 0f) +
            LINE_GAP_DP * d + (line2.maxOfOrNull { it.first.height } ?: 0)
        return CardLines(title, mark, tile, line2, max(line1W, line2W), h, c.description)
    }

    /** The card around [lines]: [w]×[h] px, lines at the top (or centred). */
    private fun card(
        context: Context, f: Frame, tier: StripTier, lines: CardLines,
        w: Int, h: Int, colors: Palette, centred: Boolean,
    ): RemoteViews {
        val d = context.resources.displayMetrics.density
        // A different (identical) card layout per aircraft: the launcher then
        // builds it fresh and plays the roll-in exactly when the plane changes
        val variant = abs("${f.state.hex}|${f.state.status}".hashCode()) % CARDS.size
        val rv = RemoteViews(context.packageName, CARDS[variant])
        rv.tint(R.id.strip_card, "setBackgroundTintList", colors.container)
        rv.sizePx(R.id.strip_card, w, h)
        val padH = (CARD_PAD_H_DP * d).roundToInt()
        val top = if (centred) ((h - lines.height) / 2f).roundToInt().coerceAtLeast(0)
        else (tier.cardTopDp * d).roundToInt()
        rv.setViewPadding(R.id.strip_card, padH, top, padH, 0)

        rv.setImageViewBitmap(R.id.strip_title, lines.title)
        rv.tint(R.id.strip_title, "setImageTintList", colors.main)
        if (lines.mark != null) {
            rv.setImageViewBitmap(R.id.strip_mark, lines.mark)
            rv.tint(R.id.strip_mark, "setImageTintList", colors.variant)
            rv.setViewVisibility(R.id.strip_mark, View.VISIBLE)
        } else rv.setViewVisibility(R.id.strip_mark, View.GONE)
        if (lines.tile != null) {
            rv.setImageViewBitmap(R.id.strip_tile, lines.tile)
            rv.setViewVisibility(R.id.strip_tile, View.VISIBLE)
        } else rv.setViewVisibility(R.id.strip_tile, View.GONE)
        val slots = intArrayOf(R.id.strip_l2_a, R.id.strip_l2_b, R.id.strip_l2_c, R.id.strip_l2_d)
        slots.forEachIndexed { i, slot ->
            val entry = lines.line2.getOrNull(i)
            if (entry == null) { rv.setViewVisibility(slot, View.GONE); return@forEachIndexed }
            rv.setViewVisibility(slot, View.VISIBLE)
            rv.setImageViewBitmap(slot, entry.first)
            rv.tint(slot, "setImageTintList", entry.second)
        }
        rv.setContentDescription(R.id.strip_card, lines.description + ". Tap to refresh")
        rv.setOnClickPendingIntent(R.id.strip_card, broadcast(context, f.id, ACTION_REFRESH))
        return rv
    }

    /** The stats pages, drawn to fit the pill, and the pill's width (px). */
    private class StatsLayout(
        val pages: List<StatPage>, val bitmaps: List<Bitmap>, val dots: List<Bitmap>, val pillW: Int,
    )

    /**
     * Pages fitted to [room] px: a page too wide loses its last stat (never
     * its first). The pill takes the widest page's width.
     */
    private fun statsLayout(
        art: StripArt, all: List<StatPage>, tier: StripTier, k: Float, room: Int, pillH: Int,
    ): StatsLayout {
        val d = art.density
        val valuePx = tier.valueSp * k * d
        val unitPx = tier.unitSp * k * d
        val dots = art.pageDots(all.size, pillH * 0.62f)
        val chrome = pagePad(d).let { it.start + it.gap + it.end } + dots.first().width
        val pages = all.map { p ->
            var parts = p.parts
            while (art.statPageWidth(parts, valuePx, unitPx) + chrome > room && parts.contains(StatPage.Gap)) {
                parts = parts.subList(0, parts.lastIndexOf(StatPage.Gap))
            }
            if (parts === p.parts) p else p.copy(parts = parts)
        }
        val bitmaps = pages.map { p ->
            Cache.get("page|${p.parts}|$valuePx|$unitPx") { art.statPage(p.parts, valuePx, unitPx) }
        }
        return StatsLayout(pages, bitmaps, dots, bitmaps.maxOf { it.width } + chrome)
    }

    private class PagePad(val start: Int, val gap: Int, val end: Int)
    private fun pagePad(d: Float) = PagePad((PAGE_PAD_START_DP * d).roundToInt(),
        (DOTS_GAP_DP * d).roundToInt(), (DOTS_END_DP * d).roundToInt())

    /**
     * The stats pill: a ViewFlipper inside a frame clipped to the pill's
     * round shape. A tap rolls to the next page and the pill rolls on by
     * itself every [PAGE_FLIP_MS]; each page rolls up on the M3 Expressive
     * spring. Pages are stable views, so a data update refreshes them in
     * place and leaves the flipper on the page it is showing. All in whole
     * pixels ([left]…[h]).
     */
    private fun stats(
        context: Context, rv: RemoteViews, f: Frame, layout: StatsLayout, colors: Palette,
        left: Int, top: Int, w: Int, h: Int,
    ) {
        val d = context.resources.displayMetrics.density
        rv.setViewVisibility(R.id.strip_stats_frame, View.VISIBLE)
        rv.sizePx(R.id.strip_stats_frame, w, h)
        rv.marginPx(R.id.strip_stats_frame, RemoteViews.MARGIN_START, left)
        rv.marginPx(R.id.strip_stats_frame, RemoteViews.MARGIN_TOP, top)
        rv.setViewOutlinePreferredRadius(R.id.strip_stats_frame, h / 2f, TypedValue.COMPLEX_UNIT_PX)
        // One page has nothing to roll to: a flip would only replay it
        rv.setInt(R.id.strip_stats, "setFlipInterval",
            if (layout.pages.size > 1) PAGE_FLIP_MS else Int.MAX_VALUE)
        // Text at the pill's left; any width the card added goes before the dots
        val pp = pagePad(d)
        val textSlot = w - pp.start - pp.gap - pp.end - layout.dots.first().width
        rv.removeAllViews(R.id.strip_stats)
        layout.pages.forEachIndexed { i, p ->
            val (bg, fg) = colors.page(p.kind)
            val item = RemoteViews(context.packageName, R.layout.strip_stat_page)
            item.tint(R.id.strip_page, "setBackgroundTintList", bg)
            val text = layout.bitmaps[i]
            item.setImageViewBitmap(R.id.strip_page_text, text)
            item.tint(R.id.strip_page_text, "setImageTintList", fg)
            // The page is exactly w×h: the text's slot and margins add up to it
            item.setViewLayoutWidth(R.id.strip_page_text, textSlot.toFloat(), TypedValue.COMPLEX_UNIT_PX)
            item.marginPx(R.id.strip_page_text, RemoteViews.MARGIN_START, pp.start)
            item.marginPx(R.id.strip_page_text, RemoteViews.MARGIN_TOP, (h - text.height) / 2)
            item.marginPx(R.id.strip_page_text, RemoteViews.MARGIN_BOTTOM, h - text.height - (h - text.height) / 2)
            item.marginPx(R.id.strip_page_dots, RemoteViews.MARGIN_START, pp.gap)
            item.marginPx(R.id.strip_page_dots, RemoteViews.MARGIN_END, pp.end)
            item.setImageViewBitmap(R.id.strip_page_dots, layout.dots[i])
            item.tint(R.id.strip_page_dots, "setImageTintList", fg)
            item.setContentDescription(R.id.strip_page, p.description + ". Tap for more")
            item.setOnClickPendingIntent(R.id.strip_page, pageIntent(context, f.id, i))
            rv.addStableView(R.id.strip_stats, item, p.kind.ordinal)
        }
        // Only after a tap or for a new aircraft: an update must not move the pill
        f.jump?.let { rv.setDisplayedChild(R.id.strip_stats, it.mod(layout.pages.size)) }
    }

    // ---- intents ------------------------------------------------------------

    private fun broadcast(context: Context, id: Int, action: String): PendingIntent {
        val intent = Intent(context, FlightStripReceiver::class.java)
            .setAction(action)
            // Distinct data per widget, so their PendingIntents never merge
            .setData(Uri.parse("airblock-strip://$action/$id"))
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
        return PendingIntent.getBroadcast(context, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    /** Page [page] of the pill was tapped. Distinct data per page, so the intents never merge. */
    private fun pageIntent(context: Context, id: Int, page: Int): PendingIntent {
        val intent = Intent(context, FlightStripReceiver::class.java)
            .setAction(ACTION_PAGE)
            .setData(Uri.parse("airblock-strip://$ACTION_PAGE/$id/$page"))
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
            .putExtra(EXTRA_PAGE, page)
        return PendingIntent.getBroadcast(context, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 0, Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE)

    private const val DEFAULT_WIDTH_DP = 312f
    /**
     * The band's shape: outer corners (M3 "large"), the tighter corners where
     * photo and card join, and the hairline of wallpaper between them. The
     * pill sits one hairline in from the card's end and the band's bottom,
     * so its round end is concentric with the card's corner (16 = 13 + 3).
     * strip_card.xml repeats OUTER and INNER.
     */
    private const val OUTER_DP = 16f
    private const val INNER_DP = 6f
    private const val JOIN_GAP_DP = 3f
    private const val PILL_END_DP = 3f
    private const val LINE_GAP_DP = 2f
    /**
     * Kept clear at the widget's right edge. Niagara's A–Z scroller wins taps
     * where a wide widget overlaps it (help.niagaralauncher.app, "Widgets").
     */
    private const val EDGE_DP = 8f
    private const val CARD_PAD_H_DP = 12f
    private const val MARK_GAP_DP = 6f
    private const val RUN_GAP_DP = 4f
    private const val PAGE_PAD_START_DP = 13f
    private const val DOTS_GAP_DP = 8f
    private const val DOTS_END_DP = 11f
    /** The pill rolls to its next page this often (strip_root.xml repeats it). */
    private const val PAGE_FLIP_MS = 8000
    /** Line 1 sizes, tried in turn until callsign, mark and tile all fit. */
    private val FIT_STEPS = floatArrayOf(1f, 0.92f, 0.85f, 0.78f, 0.72f)

    /** Identical card layouts: switching between them rebuilds the card. */
    private val CARDS = intArrayOf(
        R.layout.strip_card_0, R.layout.strip_card_1, R.layout.strip_card_2, R.layout.strip_card_3,
    )

    /** Rendered bitmaps, least recently used dropped first. */
    private object Cache {
        private const val MAX = 48
        private val map = object : LinkedHashMap<String, Bitmap>(MAX, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?) =
                size > MAX
        }

        @Synchronized
        fun <T : Bitmap?> get(key: String, make: () -> T): T {
            @Suppress("UNCHECKED_CAST")
            map[key]?.let { return it as T }
            return make().also { if (it != null) map[key] = it }
        }

        @Synchronized
        fun clear() = map.clear()
    }
}

/**
 * The two heights the strip comes in. The launcher picks the taller one
 * that fits the space it gave the widget ([idealHeightDp]).
 */
internal enum class StripTier(
    val idealHeightDp: Float,
    /** The band's height: photo and card. */
    val photoDp: Float,
    /** Card padding above line 1 (lines are centred when there is no pill). */
    val cardTopDp: Float,
    val titleSp: Float,
    val codeSp: Float,
    val line2Sp: Float,
    val markDp: Float,
    val tileDp: Float,
    /** Whether the stats pill shows. */
    val stats: Boolean,
    val pillDp: Float,
    /** How far the pill reaches back over the photo. */
    val pillOverlapDp: Float,
    /** Pill bottom to band bottom: the cut-out's hairline, so the photo is notched cleanly. */
    val pillInsetDp: Float,
    val valueSp: Float,
    val unitSp: Float,
) {
    /** One row: photo + card. */
    COMPACT(58f, 56f, 0f, 18f, 12.5f, 11.5f, 10f, 17f, false, 0f, 0f, 0f, 0f, 0f),
    /** One taller band: the stats pill docks along its bottom, across photo and card. */
    FULL(80f, 78f, 7f, 19f, 13f, 11.5f, 11f, 18f, true, 26f, 30f, 3f, 13f, 10.5f);

    companion object {
        /**
         * Tiers to send: the best fit for the SMALLEST reported height, plus
         * COMPACT under FULL in case the real height is a little less than
         * reported (launchers misreport height).
         */
        fun forHeights(heightsDp: List<Float>): List<StripTier> {
            val h = heightsDp.minOrNull() ?: return listOf(COMPACT, FULL)
            return when {
                // Plenty of room even if the launcher over-reports by a quarter
                h >= FULL.idealHeightDp / 0.75f -> listOf(FULL)
                h + 1f >= FULL.idealHeightDp -> listOf(COMPACT, FULL)
                else -> listOf(COMPACT)
            }
        }
    }
}

/** A colour the launcher resolves: a theme resource, or a light/dark pair. */
internal sealed interface Tint {
    data class Res(val id: Int) : Tint
    data class Duo(val light: Int, val dark: Int) : Tint
}

private fun RemoteViews.tint(viewId: Int, method: String, t: Tint) = when (t) {
    is Tint.Res -> setColorStateList(viewId, method, t.id)
    is Tint.Duo -> setColorStateList(viewId, method,
        ColorStateList.valueOf(t.light), ColorStateList.valueOf(t.dark))
}

private fun RemoteViews.sizePx(viewId: Int, w: Int, h: Int) {
    setViewLayoutWidth(viewId, w.toFloat(), TypedValue.COMPLEX_UNIT_PX)
    setViewLayoutHeight(viewId, h.toFloat(), TypedValue.COMPLEX_UNIT_PX)
}

/** Whole pixels survive RemoteViews' dimension encoding exactly; fractional dp do not. */
private fun RemoteViews.marginPx(viewId: Int, type: Int, v: Int) =
    setViewLayoutMargin(viewId, type, v.toFloat(), TypedValue.COMPLEX_UNIT_PX)

/** Every colour role of one render. */
private class Palette(
    val container: Tint, val main: Tint, val variant: Tint, val accent: Tint,
    val positive: Tint, val negative: Tint, val badge: Tint, val onBadge: Tint,
    val page: (StatPage.Kind) -> Pair<Tint, Tint>,
) {
    fun tone(t: StripContent.Tone): Tint = when (t) {
        StripContent.Tone.MAIN -> main
        StripContent.Tone.VARIANT -> variant
        StripContent.Tone.ACCENT -> accent
        StripContent.Tone.POSITIVE -> positive
        StripContent.Tone.NEGATIVE -> negative
    }
}

private fun res(id: Int) = Tint.Res(id)
private fun StripColors.Duo.tint() = Tint.Duo(light, dark)

/** Pages in the system palette: each its own tonal container. */
private val SYSTEM_PAGES = mapOf(
    StatPage.Kind.ALERT to (res(R.color.strip_error_container) to res(R.color.strip_on_error_container)),
    StatPage.Kind.LIVE to (res(R.color.strip_secondary_container) to res(R.color.strip_on_secondary_container)),
    StatPage.Kind.LOOK to (res(R.color.strip_tertiary_container) to res(R.color.strip_on_tertiary_container)),
    StatPage.Kind.TRIP to (res(R.color.strip_primary_container) to res(R.color.strip_on_primary_container)),
    StatPage.Kind.AIRFRAME to (res(R.color.strip_surface_variant) to res(R.color.strip_on_surface_variant)),
)

/** Airline colours worked out once per logo (null: no real colour in it). */
private val seeds = object : LinkedHashMap<String, StripColors.Lch?>(8, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, StripColors.Lch?>?) = size > 16
}

/**
 * The palette for a render. Special aircraft keep their semantic colours
 * (error red, tertiary); an ordinary flight wears its airline's colours;
 * without a usable logo it falls back to the system's dynamic colours.
 */
private fun palette(art: StripArt, state: WidgetState, logo: String?): Palette {
    when (StripContents.palette(state)) {
        StripPalette.ERROR -> return Palette(
            res(R.color.strip_error_container), res(R.color.strip_on_error_container),
            res(R.color.strip_on_error_container_variant), res(R.color.strip_error),
            res(R.color.strip_on_error_container), res(R.color.strip_error),
            res(R.color.strip_error), res(R.color.strip_on_error)) { SYSTEM_PAGES.getValue(it) }
        StripPalette.TERTIARY -> return Palette(
            res(R.color.strip_tertiary_container), res(R.color.strip_on_tertiary_container),
            res(R.color.strip_on_tertiary_container_variant), res(R.color.strip_tertiary),
            res(R.color.strip_positive), res(R.color.strip_error),
            res(R.color.strip_tertiary), res(R.color.strip_on_tertiary)) { SYSTEM_PAGES.getValue(it) }
        StripPalette.NORMAL -> Unit
    }
    val seed = logo?.let { path ->
        synchronized(seeds) {
            if (seeds.containsKey(path)) seeds[path]
            else art.logoPixels(path)?.let(StripColors::seed).also { seeds[path] = it }
        }
    }
    if (seed == null) return Palette(
        res(R.color.strip_surface), res(R.color.strip_on_surface), res(R.color.strip_on_surface_variant),
        res(R.color.strip_primary), res(R.color.strip_positive), res(R.color.strip_error),
        res(R.color.strip_primary), res(R.color.strip_on_primary)) { SYSTEM_PAGES.getValue(it) }
    val sc = StripColors.scheme(seed)
    return Palette(
        sc.container.tint(), sc.main.tint(), sc.variant.tint(), sc.accent.tint(),
        res(R.color.strip_positive), res(R.color.strip_error), sc.badge.tint(), sc.onBadge.tint(),
    ) { kind ->
        sc.pages[kind]?.let { (bg, fg) -> bg.tint() to fg.tint() } ?: SYSTEM_PAGES.getValue(kind)
    }
}
