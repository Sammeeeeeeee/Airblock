package com.sam.airblock.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
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
 * stats pill coming off the photo that swipes up/down through more stats.
 * Only those shapes cover the wallpaper; the rest of the widget is see-through.
 *
 * Plain RemoteViews rather than Glance: the stats pill is Android's StackView
 * (the one widget view that swipes) and the card's roll-in is a layout
 * animation — both need hand-written layouts.
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
        synchronized(lastSent) { lastSent.clear() }
    }

    fun forget(ids: IntArray) {
        synchronized(lastSent) { ids.forEach { lastSent.remove(it) } }
        synchronized(pageJump) { ids.forEach { pageJump.remove(it) } }
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
    ): RemoteViews = render(context, Frame(
        id = AppWidgetManager.INVALID_APPWIDGET_ID,
        state = state,
        content = StripContents.of(state, nowMs),
        tiers = StripTier.forHeights(heightsDp),
        widthDp = widthDp,
        fontScale = context.resources.configuration.fontScale.coerceIn(0.9f, 1.15f),
        status = StripContents.status(state, nowMs),
        jump = null,
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
            val jump = synchronized(pageJump) { pageJump.remove(id) }
            val frame = Frame.of(context, id, state, now, awm.getAppWidgetOptions(id), jump)
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
            fun of(context: Context, id: Int, s: WidgetState, now: Long, options: Bundle?, jump: Int?): Frame {
                val (widthDp, heights) = reportedSize(options)
                return Frame(
                    id = id, state = s, content = StripContents.of(s, now),
                    tiers = StripTier.forHeights(heights), widthDp = widthDp,
                    fontScale = context.resources.configuration.fontScale.coerceIn(0.9f, 1.15f),
                    status = StripContents.status(s, now), jump = jump,
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
        val colors = Colors.of(StripContents.palette(f.state))
        val rv = RemoteViews(context.packageName, R.layout.strip_root)
        val k = f.fontScale

        // ---- geometry: the photo sets the scale; the pill hangs off its corner
        val photoH = (tier.photoDp * d).roundToInt()
        val photoW = art.photoWidth(c.photoPath, photoH)
        val photoWdp = photoW / d
        val showStats = tier.stats && c.pages.isNotEmpty()
        val pillH = tier.pillDp * d
        val pillLeft = photoW - tier.pillOverlapDp * d
        val pillTop = photoH - tier.pillRiseDp * d
        val cut = if (showStats) CutOut(
            RectF(pillLeft, pillTop, photoW + pillH, pillTop + pillH),
            radius = pillH / 2f, gap = CUT_GAP_DP * d,
        ) else null

        photo(context, art, rv, f, tier, photoW, photoH, cut, colors)
        status(context, rv, f, colors)

        // ---- the card beside the photo
        val budget = (f.widthDp - photoWdp - CARD_GAP_DP - 2 * CARD_PAD_H_DP - EDGE_DP) * d
        val card = card(context, art, f, tier, k, max(0f, budget), colors)
        rv.removeAllViews(R.id.strip_card_slot)
        rv.addStableView(R.id.strip_card_slot, card, 1)
        rv.setViewLayoutMargin(R.id.strip_card_slot, RemoteViews.MARGIN_START,
            photoWdp + CARD_GAP_DP, TypedValue.COMPLEX_UNIT_DIP)

        // ---- the stats pill (FULL only)
        if (showStats) stats(context, art, rv, f, tier, k, pillLeft, pillTop, pillH)
        else rv.setViewVisibility(R.id.strip_stats_frame, View.GONE)
        return rv
    }

    private fun photo(
        context: Context, art: StripArt, rv: RemoteViews, f: Frame, tier: StripTier,
        w: Int, h: Int, cut: CutOut?, colors: Colors,
    ) {
        val d = art.density
        val radius = tier.photoRadiusDp * d
        val ok = f.state.status == WidgetState.Status.OK
        rv.setViewLayoutWidth(R.id.strip_photo, w / d, TypedValue.COMPLEX_UNIT_DIP)
        rv.setViewLayoutHeight(R.id.strip_photo, h / d, TypedValue.COMPLEX_UNIT_DIP)
        val cutKey = cut?.rect?.toShortString()
        val picture = f.content.photoPath?.let { path ->
            Cache.get("photo|$path|$w|$h|$cutKey") { art.photo(path, w, h, radius, cut) }
        }
        if (picture != null) {
            rv.setImageViewBitmap(R.id.strip_photo, picture)
            rv.setViewVisibility(R.id.strip_photo_icon, View.GONE)
            rv.setContentDescription(R.id.strip_photo, "Photo of ${f.content.title}. Opens Airblock")
        } else {
            // No photo (yet): a tinted tile with the type's silhouette. The
            // tile is a separate view from the photo so a tint never lands on it.
            rv.setImageViewBitmap(R.id.strip_photo_tile,
                Cache.get("tile|$w|$h|$cutKey") { art.tile(w, h, radius, cut) })
            rv.setColorStateList(R.id.strip_photo_tile, "setImageTintList",
                if (ok) colors.badge else R.color.strip_secondary_container)
            val iconRes = when {
                ok -> AircraftIcons.iconFor(f.state.typeCode, f.state.category)
                f.state.status == WidgetState.Status.NO_AIRCRAFT -> R.drawable.ic_flight
                f.state.status == WidgetState.Status.NO_LOCATION -> R.drawable.ic_distance
                else -> R.drawable.ic_sync
            }
            val iconPx = (h * if (ok) 0.62f else 0.42f).roundToInt()
            rv.setImageViewBitmap(R.id.strip_photo_icon,
                Cache.get("icon|$iconRes|$iconPx") { art.icon(iconRes, iconPx, if (ok) 0f else 45f) })
            rv.setColorStateList(R.id.strip_photo_icon, "setImageTintList",
                if (ok) colors.onBadge else R.color.strip_on_secondary_container)
            rv.setViewVisibility(R.id.strip_photo_icon, View.VISIBLE)
            // Centre the icon on the tile (the group is a plain FrameLayout)
            rv.setViewLayoutMargin(R.id.strip_photo_icon, RemoteViews.MARGIN_START,
                (w - iconPx) / 2f / d, TypedValue.COMPLEX_UNIT_DIP)
            rv.setViewLayoutMargin(R.id.strip_photo_icon, RemoteViews.MARGIN_TOP,
                (h - iconPx) / 2f / d, TypedValue.COMPLEX_UNIT_DIP)
            rv.setContentDescription(R.id.strip_photo, "Open Airblock")
        }
        rv.setViewVisibility(R.id.strip_photo, if (picture != null) View.VISIBLE else View.GONE)
        rv.setViewVisibility(R.id.strip_photo_tile, if (picture != null) View.GONE else View.VISIBLE)
        rv.setViewLayoutWidth(R.id.strip_photo_tile, w / d, TypedValue.COMPLEX_UNIT_DIP)
        rv.setViewLayoutHeight(R.id.strip_photo_tile, h / d, TypedValue.COMPLEX_UNIT_DIP)
        rv.setOnClickPendingIntent(R.id.strip_photo, openApp(context))
        rv.setOnClickPendingIntent(R.id.strip_photo_tile, openApp(context))
    }

    private fun status(context: Context, rv: RemoteViews, f: Frame, colors: Colors) {
        val st = f.status
        if (st == StripStatus.NONE) {
            rv.setViewVisibility(R.id.strip_status, View.GONE)
            return
        }
        rv.setViewVisibility(R.id.strip_status, View.VISIBLE)
        rv.setColorStateList(R.id.strip_status, "setBackgroundTintList", colors.container)
        val spinning = st == StripStatus.SPINNER
        rv.setViewVisibility(R.id.strip_status_spin, if (spinning) View.VISIBLE else View.GONE)
        rv.setViewVisibility(R.id.strip_status_icon, if (spinning) View.GONE else View.VISIBLE)
        if (spinning) {
            rv.setColorStateList(R.id.strip_status_spin, "setIndeterminateTintList", colors.accent)
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

    /**
     * The card: callsign + model mark + airline tile, then the route. Pieces
     * give way in order when the width runs out — the mark shrinks, then
     * goes, before the callsign is ever cut.
     */
    private fun card(
        context: Context, art: StripArt, f: Frame, tier: StripTier, k: Float,
        budget: Float, colors: Colors,
    ): RemoteViews {
        val d = art.density
        val c = f.content
        // A different (identical) card layout per aircraft: the launcher then
        // builds it fresh and plays the roll-in exactly when the plane changes
        val variant = abs("${f.state.hex}|${f.state.status}".hashCode()) % CARDS.size
        val rv = RemoteViews(context.packageName, CARDS[variant])
        rv.setColorStateList(R.id.strip_card, "setBackgroundTintList", colors.container)

        // ---- line 1: callsign, model mark, airline tile. They step down in
        // size together until all three fit; only then does the mark go.
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
            if (need <= budget) { titlePx = tPx; mark = m; break }
            titlePx = tPx
        }
        val markW = mark?.let { it.width + MARK_GAP_DP * d } ?: 0f
        val titleMax = max(titlePx * 2, budget - tileW - markW)
        val title = Cache.get("title|${c.title}|$titlePx|${titleMax.roundToInt()}") {
            art.text(c.title, StripType.HERO, titlePx, maxWidthPx = titleMax)
        }
        rv.setImageViewBitmap(R.id.strip_title, title)
        rv.setColorStateList(R.id.strip_title, "setImageTintList", colors.main)
        if (mark != null) {
            rv.setImageViewBitmap(R.id.strip_mark, mark)
            rv.setColorStateList(R.id.strip_mark, "setImageTintList", colors.variant)
            rv.setViewVisibility(R.id.strip_mark, View.VISIBLE)
        } else rv.setViewVisibility(R.id.strip_mark, View.GONE)
        if (tile != null) {
            rv.setImageViewBitmap(R.id.strip_tile, tile)
            rv.setViewVisibility(R.id.strip_tile, View.VISIBLE)
        } else rv.setViewVisibility(R.id.strip_tile, View.GONE)

        // ---- line 2: the first option that fits
        val codePx = tier.codeSp * k * d
        val textPx = tier.line2Sp * k * d
        val minRoute = codePx * 1.9f
        val maxRoute = codePx * 3.2f
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
        val routeW = (budget - fixedW(runs)).coerceIn(minRoute, maxRoute).roundToInt()
        // Codes and the small text share one baseline: both drawn in the codes' line box
        val box = ceil(StripType.HERO.ascentEm * codePx) to ceil(StripType.HERO.descentEm * codePx)
        val slots = intArrayOf(R.id.strip_l2_a, R.id.strip_l2_b, R.id.strip_l2_c, R.id.strip_l2_d)
        var spent = 0f
        slots.forEachIndexed { i, slot ->
            val r = runs.getOrNull(i)
            if (r == null) { rv.setViewVisibility(slot, View.GONE); return@forEachIndexed }
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
            rv.setViewVisibility(slot, View.VISIBLE)
            rv.setImageViewBitmap(slot, bmp)
            rv.setColorStateList(slot, "setImageTintList", when (r) {
                is StripContent.Route -> colors.accent
                is StripContent.Text -> colors.tone(r.tone)
            })
        }
        rv.setContentDescription(R.id.strip_card, c.description + ". Tap to refresh")
        rv.setOnClickPendingIntent(R.id.strip_card, broadcast(context, f.id, ACTION_REFRESH))
        return rv
    }

    /**
     * The stats pill: a StackView whose front page sits in the photo's
     * cut-out. Every page is drawn the same width and carries page dots
     * (stacked, like the pages) so it reads as something that pages up/down.
     */
    private fun stats(
        context: Context, art: StripArt, rv: RemoteViews, f: Frame, tier: StripTier,
        k: Float, pillLeft: Float, pillTop: Float, pillH: Float,
    ) {
        val d = art.density
        val valuePx = tier.valueSp * k * d
        val unitPx = tier.unitSp * k * d
        val pages = f.content.pages
        val textH = art.statLineHeight(valuePx)
        // Every page the same width, so the pages behind hide under the front one
        val textW = pages.maxOf { art.statPageWidth(it.parts, valuePx, unitPx) }
        val bitmaps = pages.map { p ->
            Cache.get("page|${p.parts}|$valuePx|$unitPx") { art.statPage(p.parts, valuePx, unitPx) }
        }
        val padV = (pillH - textH) / 2f / d
        val dots = art.pageDots(pages.size, pillH * 0.62f)
        val pillW = textW + (PAGE_PAD_START_DP + DOTS_GAP_DP + DOTS_END_DP) * d + dots.first().width
        // StackView puts each page in a frame padded by FRAME_PAD, sizes the
        // frames to at most 90% of itself and fans the pages behind up and to
        // the right. The clip frame is exactly the front page's rounded pill,
        // so none of that fan shows — a swipe rolls the pages through the pill.
        val pad = FRAME_PAD_DP * d
        val frameW = pillW + 2 * pad
        val frameH = pillH + 2 * pad
        val stackW = ceil(frameW / STACK_FILL) + 1f
        val stackH = ceil(frameH / STACK_FILL) + 1f
        rv.setViewVisibility(R.id.strip_stats_frame, View.VISIBLE)
        rv.setViewLayoutWidth(R.id.strip_stats_frame, pillW / d, TypedValue.COMPLEX_UNIT_DIP)
        rv.setViewLayoutHeight(R.id.strip_stats_frame, pillH / d, TypedValue.COMPLEX_UNIT_DIP)
        rv.setViewLayoutMargin(R.id.strip_stats_frame, RemoteViews.MARGIN_START, pillLeft / d,
            TypedValue.COMPLEX_UNIT_DIP)
        rv.setViewLayoutMargin(R.id.strip_stats_frame, RemoteViews.MARGIN_TOP, pillTop / d,
            TypedValue.COMPLEX_UNIT_DIP)
        rv.setViewOutlinePreferredRadius(R.id.strip_stats_frame, pillH / 2f / d,
            TypedValue.COMPLEX_UNIT_DIP)
        // The front page sits at the stack's bottom-left, inside its frame padding
        rv.setViewLayoutWidth(R.id.strip_stats, stackW / d, TypedValue.COMPLEX_UNIT_DIP)
        rv.setViewLayoutHeight(R.id.strip_stats, stackH / d, TypedValue.COMPLEX_UNIT_DIP)
        rv.setViewLayoutMargin(R.id.strip_stats, RemoteViews.MARGIN_START, -pad / d,
            TypedValue.COMPLEX_UNIT_DIP)
        rv.setViewLayoutMargin(R.id.strip_stats, RemoteViews.MARGIN_TOP, -(stackH - pad - pillH) / d,
            TypedValue.COMPLEX_UNIT_DIP)
        val items = RemoteViews.RemoteCollectionItems.Builder()
            .setHasStableIds(true)
            .setViewTypeCount(1)
        pages.forEachIndexed { i, p ->
            val (bg, fg) = PAGE_COLORS.getValue(p.kind)
            val item = RemoteViews(context.packageName, R.layout.strip_stat_page)
            item.setColorStateList(R.id.strip_page, "setBackgroundTintList", bg)
            item.setImageViewBitmap(R.id.strip_page_text, bitmaps[i])
            item.setColorStateList(R.id.strip_page_text, "setImageTintList", fg)
            item.setViewLayoutWidth(R.id.strip_page_text, textW / d, TypedValue.COMPLEX_UNIT_DIP)
            item.setViewLayoutMargin(R.id.strip_page_text, RemoteViews.MARGIN_TOP, padV,
                TypedValue.COMPLEX_UNIT_DIP)
            item.setViewLayoutMargin(R.id.strip_page_text, RemoteViews.MARGIN_BOTTOM, padV,
                TypedValue.COMPLEX_UNIT_DIP)
            item.setImageViewBitmap(R.id.strip_page_dots, dots[i])
            item.setColorStateList(R.id.strip_page_dots, "setImageTintList", fg)
            item.setContentDescription(R.id.strip_page, p.description + ". Swipe or tap for more")
            item.setOnClickFillInIntent(R.id.strip_page, Intent().putExtra(EXTRA_PAGE, i))
            items.addItem(p.kind.ordinal.toLong(), item)
        }
        rv.setRemoteAdapter(R.id.strip_stats, items.build())
        rv.setPendingIntentTemplate(R.id.strip_stats, pageTemplate(context, f.id))
        // Only right after a tap: a later update must not undo a swipe
        f.jump?.let { rv.setDisplayedChild(R.id.strip_stats, it.mod(pages.size)) }
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

    /** Collection items fill in the page index, so this one must be mutable. */
    private fun pageTemplate(context: Context, id: Int): PendingIntent {
        val intent = Intent(context, FlightStripReceiver::class.java)
            .setAction(ACTION_PAGE)
            .setData(Uri.parse("airblock-strip://$ACTION_PAGE/$id"))
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
        return PendingIntent.getBroadcast(context, 0, intent,
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 0, Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE)

    private const val DEFAULT_WIDTH_DP = 312f
    /**
     * Kept clear at the widget's right edge. Niagara's A–Z scroller wins taps
     * where a wide widget overlaps it (help.niagaralauncher.app, "Widgets").
     */
    private const val EDGE_DP = 8f
    private const val CARD_GAP_DP = 6f
    private const val CARD_PAD_H_DP = 13f
    private const val MARK_GAP_DP = 6f
    private const val RUN_GAP_DP = 4f
    /** Wallpaper showing between the photo and the pill that comes off it. */
    private const val CUT_GAP_DP = 3f
    private const val PAGE_PAD_START_DP = 13f
    private const val DOTS_GAP_DP = 8f
    private const val DOTS_END_DP = 11f
    /** StackView sizes its pages to this share of itself (its perspective shift). */
    private const val STACK_FILL = 0.9f
    /** StackView's padding around each page (its FRAME_PADDING). */
    private const val FRAME_PAD_DP = 4f
    /** Line 1 sizes, tried in turn until callsign, mark and tile all fit. */
    private val FIT_STEPS = floatArrayOf(1f, 0.92f, 0.85f, 0.78f, 0.72f)

    /** Identical card layouts: switching between them rebuilds the card. */
    private val CARDS = intArrayOf(
        R.layout.strip_card_0, R.layout.strip_card_1, R.layout.strip_card_2, R.layout.strip_card_3,
    )

    /** Each stats page has its own tonal container, so the peeking edges differ. */
    private val PAGE_COLORS = mapOf(
        StatPage.Kind.ALERT to (R.color.strip_error_container to R.color.strip_on_error_container),
        StatPage.Kind.LIVE to (R.color.strip_secondary_container to R.color.strip_on_secondary_container),
        StatPage.Kind.LOOK to (R.color.strip_tertiary_container to R.color.strip_on_tertiary_container),
        StatPage.Kind.TRIP to (R.color.strip_primary_container to R.color.strip_on_primary_container),
        StatPage.Kind.AIRFRAME to (R.color.strip_surface_variant to R.color.strip_on_surface_variant),
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
    val photoDp: Float,
    val photoRadiusDp: Float,
    val titleSp: Float,
    val codeSp: Float,
    val line2Sp: Float,
    val markDp: Float,
    val tileDp: Float,
    /** Whether the stats pill shows. */
    val stats: Boolean,
    val pillDp: Float,
    /** How far the pill reaches back over the photo, and up into it. */
    val pillOverlapDp: Float,
    val pillRiseDp: Float,
    val valueSp: Float,
    val unitSp: Float,
) {
    /** One row: photo + card. */
    COMPACT(58f, 56f, 14f, 18f, 12.5f, 11.5f, 10f, 17f, false, 0f, 0f, 0f, 0f, 0f),
    /** Photo + card + the stats pill coming off the photo. */
    FULL(96f, 76f, 18f, 20f, 13.5f, 12f, 11f, 19f, true, 30f, 36f, 13f, 14f, 11f);

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

/** Colour resources for one palette (all follow light/dark and the wallpaper). */
private data class Colors(
    val container: Int, val main: Int, val variant: Int, val accent: Int,
    val positive: Int, val negative: Int, val badge: Int, val onBadge: Int,
) {
    fun tone(t: StripContent.Tone): Int = when (t) {
        StripContent.Tone.MAIN -> main
        StripContent.Tone.VARIANT -> variant
        StripContent.Tone.ACCENT -> accent
        StripContent.Tone.POSITIVE -> positive
        StripContent.Tone.NEGATIVE -> negative
    }

    companion object {
        fun of(p: StripPalette): Colors = when (p) {
            StripPalette.NORMAL -> Colors(
                R.color.strip_surface, R.color.strip_on_surface, R.color.strip_on_surface_variant,
                R.color.strip_primary, R.color.strip_positive, R.color.strip_error,
                R.color.strip_primary, R.color.strip_on_primary)
            StripPalette.TERTIARY -> Colors(
                R.color.strip_tertiary_container, R.color.strip_on_tertiary_container,
                R.color.strip_on_tertiary_container_variant, R.color.strip_tertiary,
                R.color.strip_positive, R.color.strip_error,
                R.color.strip_tertiary, R.color.strip_on_tertiary)
            StripPalette.ERROR -> Colors(
                R.color.strip_error_container, R.color.strip_on_error_container,
                R.color.strip_on_error_container_variant, R.color.strip_error,
                R.color.strip_on_error_container, R.color.strip_error,
                R.color.strip_error, R.color.strip_on_error)
        }
    }
}
