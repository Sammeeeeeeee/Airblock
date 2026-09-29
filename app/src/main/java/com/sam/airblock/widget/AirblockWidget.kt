package com.sam.airblock.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.util.SizeF
import android.util.TypedValue
import android.widget.RemoteViews
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.clickable
import androidx.glance.appwidget.AndroidRemoteViews
import androidx.glance.appwidget.CircularProgressIndicator
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.LocalAppWidgetOptions
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.RowScope
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextDecoration
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.sam.airblock.R
import com.sam.airblock.data.ManufacturerLogoRepo
import com.sam.airblock.data.WidgetState
import com.sam.airblock.data.WidgetStateStore
import com.sam.airblock.util.AlertLabels
import com.sam.airblock.util.Units
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.stateIn
import kotlin.math.roundToInt

class AirblockWidget : GlanceAppWidget() {

    // Height TIERS, and the LAUNCHER picks one. SizeMode.Exact laid the card
    // out for the size the launcher last reported — and after a screen
    // resolution switch (FHD+ → WQHD+) Niagara gave the widget ~25% fewer dp,
    // crushing the full layout into it: a thumbnail-sized photo stranded in a
    // wide empty box, header lines clipped. With Responsive sizes an Android
    // 12+ host re-picks, on every layout pass, the tallest tier that fits the
    // space it ACTUALLY gave the widget — no report, re-render or restart
    // involved. The tier width is nominal (every real widget is wider);
    // width-driven sizing reads the reported width, which launchers get right.
    override val sizeMode: SizeMode = SizeMode.Responsive(
        CardFit.TIERS_DP.map { DpSize(TIER_WIDTH_DP.dp, it.dp) }.toSet())

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        // provideGlance runs ONCE per widget session; updateAll() only
        // recomposes. State must therefore be observed INSIDE the composition,
        // otherwise the widget keeps rendering a stale snapshot while the
        // process is alive (the bug: app showed fresh data, widget didn't).
        coroutineScope {
            val initial = WidgetStateStore.read(context)
            // ONE collection shared by every tier's composition, deduplicated
            // on what the widget draws: the refresh checklist alone changes
            // several times per tick, and each change re-sent every tier
            val states = WidgetStateStore.flow(context)
                .distinctUntilChangedBy { it.drawnFields() }
                .stateIn(this, SharingStarted.Eagerly, initial)
            provideContent {
                val state by states.collectAsState()
                val airlineLogo = state.airlineLogoPath?.let { decodeShared(it) }
                val manufacturerLogo = state.manufacturerLogoPath?.let { decodeShared(it) }
                val modelLogo = state.modelLogoPath?.let { decodeShared(it) }
                GlanceTheme {
                    WidgetContent(state, airlineLogo, manufacturerLogo, modelLogo)
                }
            }
        }
    }

    companion object {
        /**
         * Drop every cached render bitmap (callsign, photo, logos…) so the
         * next composition redraws them for the current display — part of
         * the Force full restart.
         */
        fun resetRenderCaches() = SharedBitmaps.clear()
    }
}

/** Nominal width of every height tier: narrower than any real placement. */
private const val TIER_WIDTH_DP = 200f

/** The photo never takes more than this share of the widget's width. */
private const val PHOTO_WIDTH_FRACTION = 0.42f

/** The state minus what the widget never draws (see provideGlance). */
private fun WidgetState.drawnFields() = copy(
    hex = null, photoCredit = null, refreshStage = null, stages = emptyList(),
    lastError = null, modeLabel = null, errorCount = errorCount.coerceAtMost(1),
)

/**
 * Bitmaps shared by all the height tiers. Each tier is its own composition, so
 * a `remember`ed bitmap existed once PER TIER — and RemoteViews dedupes bitmaps
 * by identity, so every copy would ride along on every update. Keyed by all
 * that the pixels depend on; the least recently used fall out.
 */
private object SharedBitmaps {
    private const val MAX_ENTRIES = 16
    private val cache = object : LinkedHashMap<String, Bitmap>(MAX_ENTRIES, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?) =
            size > MAX_ENTRIES
    }

    @Synchronized
    fun get(key: String, make: () -> Bitmap?): Bitmap? =
        cache[key] ?: make()?.also { cache[key] = it }

    @Synchronized
    fun clear() = cache.clear()
}

private fun decodeShared(path: String): Bitmap? =
    SharedBitmaps.get("file|$path") { BitmapFactory.decodeFile(path) }

/** Decode bounded — thumbnails are ~280 px already, just guard against surprises. */
private fun decodePhoto(path: String): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    if (bounds.outWidth <= 0) return null
    val sample = (bounds.outWidth / 400).coerceAtLeast(1)
    return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply {
        inSampleSize = sample
    })
}

/**
 * The widget's width as the launcher reports it (the smallest, if it reports
 * several). Launchers get width right — it's height they misreport.
 */
@Composable
private fun reportedWidthDp(): Float {
    val options = LocalAppWidgetOptions.current
    @Suppress("DEPRECATION") // typed overload is API 33; minSdk is 31
    val sizes = options.getParcelableArrayList<SizeF>(AppWidgetManager.OPTION_APPWIDGET_SIZES)
    val width = sizes?.minOfOrNull { it.width }
        ?: options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH).toFloat()
    return if (width > 0f) width else 250f // the declared minWidth
}

/** Content colors paired with the widget background (varies for special aircraft). */
private data class WidgetPalette(
    val bg: ColorProvider,
    val onBg: ColorProvider,
    val onBgVariant: ColorProvider,
)

@Composable
private fun widgetPalette(state: WidgetState): WidgetPalette {
    val military = state.specialType == "Military" ||
        state.alertCategory.equals("Military", ignoreCase = true)
    return when {
        military -> WidgetPalette(
            GlanceTheme.colors.errorContainer,
            GlanceTheme.colors.onErrorContainer,
            GlanceTheme.colors.onErrorContainer,
        )
        state.specialType != null -> WidgetPalette(
            GlanceTheme.colors.tertiaryContainer,
            GlanceTheme.colors.onTertiaryContainer,
            GlanceTheme.colors.onTertiaryContainer,
        )
        else -> WidgetPalette(
            GlanceTheme.colors.widgetBackground,
            GlanceTheme.colors.onSurface,
            GlanceTheme.colors.onSurfaceVariant,
        )
    }
}

@Composable
private fun WidgetContent(
    state: WidgetState,
    airlineLogo: Bitmap?,
    manufacturerLogo: Bitmap?,
    modelLogo: Bitmap?,
) {
    // Non-standard aircraft (military, police helicopters…) get an
    // attention-grabbing tonal background — content colors must follow the
    // container role or dark-theme contrast breaks.
    val palette = widgetPalette(state)
    val bg = palette.bg
    val fit = cardFit(LocalContext.current, LocalSize.current.height.value, state,
        hasModelLogo = modelLogo != null, hasAirlineLogo = airlineLogo != null)
    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(bg)
            // Match the corner radius the launcher clips widgets to
            .cornerRadius(android.R.dimen.system_app_widget_background_radius)
            .padding(fit.pad.dp)
            // Blank space taps still mean "refresh now"; the info elements
            // (photo, route, chips) carry their own open-the-app actions
            .clickable(actionRunCallback<RefreshAction>()),
    ) {
        when (state.status) {
            WidgetState.Status.OK ->
                AircraftCard(state, airlineLogo, manufacturerLogo, modelLogo, palette, fit)
            WidgetState.Status.NO_AIRCRAFT -> EmptyMessage("No aircraft nearby")
            WidgetState.Status.NO_LOCATION -> EmptyMessage("Location unavailable — tap to retry")
            else -> EmptyMessage("Airblock — tap to refresh")
        }
        // Non-standard status — single small icon, top-right
        StatusBadge(state)
    }
}

/**
 * Top-right indicator for non-standard conditions only, priority:
 * refreshing (live spinner) > battery saver > failed refreshes > stale data.
 * Nothing is shown when all is well — a permanent icon is just clutter.
 */
@Composable
private fun StatusBadge(state: WidgetState) {
    // When the aircraft is already on the glass and only the route is still
    // loading, its skeleton pill is indicator enough — two spinners is noise
    val routeSkeletonVisible = state.refreshing &&
        state.status == WidgetState.Status.OK &&
        state.originIata == null && state.destIata == null
    val showSpinner = state.refreshing && !routeSkeletonVisible
    val icon: Int
    val tint: ColorProvider
    when {
        showSpinner -> { icon = 0; tint = GlanceTheme.colors.primary }
        state.pausedReason != null -> {
            icon = R.drawable.ic_battery_saver; tint = GlanceTheme.colors.tertiary
        }
        state.errorCount > 0 -> {
            icon = R.drawable.ic_warning; tint = GlanceTheme.colors.error
        }
        isStale(state) -> {
            icon = R.drawable.ic_clock; tint = GlanceTheme.colors.outline
        }
        else -> return
    }
    Box(modifier = GlanceModifier.fillMaxSize(), contentAlignment = Alignment.TopEnd) {
        Box(
            modifier = GlanceModifier
                .background(GlanceTheme.colors.surfaceVariant)
                .cornerRadius(12.dp)
                .padding(4.dp),
        ) {
            if (showSpinner) {
                CircularProgressIndicator(
                    modifier = GlanceModifier.size(14.dp),
                    color = tint,
                )
            } else {
                Image(
                    provider = ImageProvider(icon),
                    contentDescription = state.pausedReason ?: "status",
                    colorFilter = ColorFilter.tint(tint),
                    modifier = GlanceModifier.size(12.dp),
                )
            }
        }
    }
}

@Composable
private fun AircraftCard(
    state: WidgetState,
    airlineLogo: Bitmap?,
    manufacturerLogo: Bitmap?,
    modelLogo: Bitmap?,
    palette: WidgetPalette,
    fit: CardFit,
) {
    val context = LocalContext.current
    val density = context.resources.displayMetrics.density
    // Only the photo's CAP comes from the (reliably reported) width; its
    // actual size is set by the launcher from the real row height — see
    // PhotoFrame. Launchers (Niagara et al.) misreport height, which is what
    // made the photo a narrow cropped strip (v2.2) or a tiny thumbnail later.
    val widthDp = reportedWidthDp()
    val photoMaxWidth = widthDp * PHOTO_WIDTH_FRACTION

    Column(modifier = GlanceModifier.fillMaxSize()) {
        // Top row: landscape photo + callsign/type side by side
        Row(
            modifier = GlanceModifier.fillMaxWidth().defaultWeight(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Corners are baked into the pixels at the size the photo shows
            // when it hits its width cap (then it is letterboxed inside its
            // view, and clipping the view can't reach the photo's corners);
            // below the cap the frame hugs the photo and its 16dp clip rounds.
            val photo = state.photoPath?.let { path ->
                SharedBitmaps.get("photo|$path|${photoMaxWidth.roundToInt()}") {
                    decodePhoto(path)?.let { roundCorners(it, it.width * 16f / photoMaxWidth) }
                }
            } ?: run {
                // No photo (yet): the type's silhouette on a tonal rounded
                // card, drawn as a BITMAP — RemoteViews mangled the tinted
                // vector (it rendered as a solid slab), and canvas drawing
                // is what the route path and callsign already use.
                val phBg = GlanceTheme.colors.surfaceVariant.getColor(context).toArgb()
                val phFg = GlanceTheme.colors.onSurfaceVariant.getColor(context).toArgb()
                val icon = com.sam.airblock.util.AircraftIcons.iconFor(
                    state.typeCode, state.category)
                SharedBitmaps.get("placeholder|$icon|$phBg|$phFg|${photoMaxWidth.roundToInt()}") {
                    photoPlaceholderBitmap(context, icon, bg = phBg, fg = phFg,
                        cornerPx = 480f * 16f / photoMaxWidth)
                }
            }
            photo?.let { PhotoFrame(it, state.typeName, photoMaxWidth) }
            Spacer(GlanceModifier.width(12.dp))
            Column(modifier = GlanceModifier.defaultWeight()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // The callsign in real M3 Expressive display type: drawn
                    // as a bitmap because RemoteViews text can't reach the
                    // heavy, tight expressive weights
                    val callsignText = state.callsign ?: "—"
                    val callsignColor = palette.onBg.getColor(context).toArgb()
                    // The narrowest the title column gets: photo at its cap
                    val maxCallsignWidthPx =
                        ((widthDp - 2 * fit.pad - photoMaxWidth - 16f) * density).toInt()
                    val callsignHeightPx = (fit.callsignDp * density).toInt()
                    val callsignBmp = SharedBitmaps.get(
                        "callsign|$callsignText|$callsignColor|$callsignHeightPx|$maxCallsignWidthPx",
                    ) {
                        expressiveText(callsignText, callsignColor,
                            heightPx = callsignHeightPx, maxWidthPx = maxCallsignWidthPx)
                    }
                    // (FR24 deep-linking removed: the app intercepts the URL
                    // but just opens its website view — no public deep-link
                    // API exists, so the tap falls through to refresh)
                    callsignBmp?.let { bmp ->
                        Image(
                            provider = ImageProvider(bmp),
                            contentDescription = callsignText,
                            modifier = GlanceModifier
                                .width((bmp.width / density).dp)
                                .height((bmp.height / density).dp),
                        )
                    }
                }
                // Type line: manufacturer WORDMARK (tinted to theme) + model,
                // falling back to the full text when no logo is cached
                state.typeName?.takeIf { fit.typeLine }?.let { typeName ->
                    val mfr = manufacturerLogo?.let {
                        ManufacturerLogoRepo.manufacturerOf(typeName)?.let { name ->
                            name to ManufacturerLogoRepo.modelOf(typeName, name)
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        when {
                            // The plane's OWN logo says it all (787 Dreamliner,
                            // A380…) — the company wordmark would be redundant
                            modelLogo != null -> {
                                val mLogoH = 13f
                                val mLogoW = (mLogoH * modelLogo.width /
                                    modelLogo.height).coerceAtMost(84f)
                                Image(
                                    provider = ImageProvider(modelLogo),
                                    contentDescription = typeName,
                                    colorFilter = ColorFilter.tint(palette.onBgVariant),
                                    modifier = GlanceModifier.width(mLogoW.dp).height(mLogoH.dp),
                                )
                            }
                            manufacturerLogo != null && mfr != null -> {
                                val (mfrName, model) = mfr
                                val logoH = 11f
                                val logoW = (logoH * manufacturerLogo.width /
                                    manufacturerLogo.height).coerceAtMost(64f)
                                Image(
                                    provider = ImageProvider(manufacturerLogo),
                                    contentDescription = mfrName,
                                    colorFilter = ColorFilter.tint(palette.onBgVariant),
                                    modifier = GlanceModifier.width(logoW.dp).height(logoH.dp),
                                )
                                if (model.isNotEmpty()) {
                                    Spacer(GlanceModifier.width(5.dp))
                                    Text(
                                        text = model,
                                        style = TextStyle(fontSize = 12.sp,
                                            color = palette.onBgVariant),
                                        maxLines = 1,
                                    )
                                }
                            }
                            else -> Text(
                                text = typeName,
                                style = TextStyle(fontSize = 12.sp,
                                    color = palette.onBgVariant),
                                maxLines = 1,
                            )
                        }
                    }
                }
                // The operating airline (medium weight) with the flight number
                // beside it lighter — told apart by weight/colour, no chrome.
                if (fit.airline) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        state.airlineName?.let { airline ->
                            Text(
                                text = airline,
                                style = TextStyle(fontSize = 10.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = palette.onBg),
                                maxLines = 1,
                            )
                        }
                        state.flightNumber?.let { fn ->
                            if (state.airlineName != null) Spacer(GlanceModifier.width(4.dp))
                            Text(
                                text = fn,
                                style = TextStyle(fontSize = 10.sp,
                                    color = palette.onBgVariant),
                                maxLines = 1,
                            )
                        }
                    }
                }
                // Scheduled dep–arr times, faded italic. Where the actual differs
                // the scheduled is struck through and the actual shown next to it,
                // green when on time/early, red when late.
                if (fit.times) ScheduleTimesRow(state, palette)
                // Special-aircraft badge (military, police…): left-aligned, flush
                // with the title column's left edge, directly under the type —
                // it used to float at the far right, reading as detached.
                // It names the DATABASE CATEGORY, which is what the settings
                // screen's per-category switches are labelled with — see
                // AlertLabels.
                val badge = AlertLabels.primary(
                    state.alertCategory, state.alertTag, state.specialType)
                badge?.takeIf { fit.badge }?.let { tag ->
                    Spacer(GlanceModifier.height(3.dp))
                    Row(
                        modifier = GlanceModifier
                            .background(GlanceTheme.colors.error)
                            .cornerRadius(12.dp)
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Image(
                            provider = ImageProvider(R.drawable.ic_shield),
                            contentDescription = tag,
                            colorFilter = ColorFilter.tint(GlanceTheme.colors.onError),
                            modifier = GlanceModifier.size(9.dp),
                        )
                        Spacer(GlanceModifier.width(3.dp))
                        Text(
                            text = tag.uppercase(),
                            style = TextStyle(fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                color = GlanceTheme.colors.onError),
                            // Categories run longer than the tags this used to
                            // show ("ROYAL NAVY FLEET AIR ARM"); wrap rather
                            // than clip a name mid-word
                            maxLines = 2,
                        )
                    }
                }
            }
        }
        Spacer(GlanceModifier.height(fit.gap.dp))
        RouteRow(state, airlineLogo, fit.compact)
        Spacer(GlanceModifier.height(fit.gap.dp))
        ChipsRow(state)
    }
}

/**
 * The photo (or placeholder) as a plain ImageView with adjustViewBounds, so
 * the LAUNCHER sizes it: full top-row height, width from the bitmap's own
 * aspect, capped at [maxWidthDp]. The frame hugs the picture at whatever
 * height the widget really has — a Glance Image needs a fixed width up front,
 * and a fixed width sized for the wrong height left a thumbnail stranded in a
 * wide empty box.
 */
@Composable
private fun PhotoFrame(bitmap: Bitmap, description: String?, maxWidthDp: Float) {
    val context = LocalContext.current
    val views = RemoteViews(context.packageName, R.layout.widget_photo).apply {
        setImageViewBitmap(R.id.widget_photo, bitmap)
        setIntDimen(R.id.widget_photo, "setMaxWidth", maxWidthDp, TypedValue.COMPLEX_UNIT_DIP)
        setContentDescription(R.id.widget_photo, description)
    }
    AndroidRemoteViews(
        remoteViews = views,
        modifier = GlanceModifier.fillMaxHeight().cornerRadius(16.dp)
            .clickable(
                androidx.glance.action.actionStartActivity<com.sam.airblock.ui.MainActivity>()),
    )
}

/**
 * What the aircraft card shows at one height tier. The old fixed layout needed
 * ~170dp and simply overflowed anything shorter; now whole lines are taken in
 * priority order only while they fit, and short tiers drop the pill's city
 * names so the photo row keeps its height. Text heights are measured from the
 * live font, so a bigger font size or another system font still fits.
 */
private class CardFit(
    /** Route pill without city names, tighter spacing. */
    val compact: Boolean,
    val pad: Float,
    val gap: Float,
    /** Box height the callsign bitmap is drawn for. */
    val callsignDp: Float,
    val typeLine: Boolean,
    val badge: Boolean,
    val airline: Boolean,
    val times: Boolean,
) {
    companion object {
        /**
         * Tier heights (dp), each where one more thing starts to fit: callsign
         * only · + type · + airline · city names back · + times. 170 is the
         * full original layout.
         */
        val TIERS_DP = listOf(100f, 124f, 140f, 156f, 170f)
        /** From here the route pill has room for the city names again. */
        const val FULL_FROM_DP = 156f
        /** Margin for rounding and emoji-height lines. */
        const val SLACK_DP = 2f
    }
}

private fun cardFit(
    context: Context,
    tierDp: Float,
    state: WidgetState,
    hasModelLogo: Boolean,
    hasAirlineLogo: Boolean,
): CardFit {
    val metrics = context.resources.displayMetrics
    val density = metrics.density
    // One line of text as a TextView lays it out (font padding included), dp
    fun line(sp: Float): Float {
        val paint = Paint().apply {
            textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, metrics)
        }
        val fm = paint.fontMetrics
        return (fm.bottom - fm.top) / density
    }
    fun callsignHeight(boxDp: Float): Float {
        val fm = expressivePaint(0, (boxDp * density).toInt()).fontMetrics
        return (fm.descent - fm.ascent) / density
    }

    val compact = tierDp < CardFit.FULL_FROM_DP
    val pad = if (compact) 8f else 10f
    val gap = if (compact) 4f else 6f
    // Mirrors RouteRow / RoutePill / AirlineLogoBadge / ChipsRow paddings
    val hasRoute = state.originIata != null || state.destIata != null
    val routeLoading = !hasRoute && state.refreshing
    val pill = when {
        hasRoute -> 2 * (if (compact) 3f else 5f) + maxOf(
            line(15f) + (if (compact) 0f else line(10f)), // codes (+ cities)
            14f + line(10f),                               // path + times
        )
        routeLoading -> 2 * (if (compact) 8f else 10f) + maxOf(12f, line(11f))
        else -> 0f
    }
    val logoBadge = if (hasAirlineLogo) (if (compact) 30f else 34f) else 0f
    val chips = 8f + maxOf(10f, line(10f))
    val budget = tierDp - 2 * pad - 2 * gap - maxOf(pill, logoBadge) - chips - CardFit.SLACK_DP

    val typeH = when {
        state.typeName == null -> 0f
        hasModelLogo -> 13f
        else -> maxOf(11f, line(12f))
    }
    val tag = AlertLabels.primary(state.alertCategory, state.alertTag, state.specialType)
    val badgeH = if (tag == null) 0f
    else 3f + 4f + maxOf(9f, line(9f) * (if (tag.length > 16) 2 else 1))
    val airlineH = if (state.airlineName != null || state.flightNumber != null) line(10f) else 0f
    val timesH = if (state.schedDepLocal != null) line(9f) else 0f

    // The callsign always shows; it shrinks before the type line is given up
    val sizes = listOf(28f, 24f, 20f)
    val withType = sizes.firstOrNull { callsignHeight(it) + typeH <= budget }
    val typeLine = withType != null && typeH > 0f
    val callsign = withType
        ?: sizes.firstOrNull { callsignHeight(it) <= budget }
        ?: sizes.last()
    var used = callsignHeight(callsign) + if (typeLine) typeH else 0f
    // Then by importance: special-aircraft badge, airline, scheduled times
    fun take(h: Float): Boolean = (h > 0f && used + h <= budget).also { if (it) used += h }
    val badge = take(badgeH)
    val airline = take(airlineH)
    val times = take(timesH)
    return CardFit(compact, pad, gap, callsign, typeLine, badge, airline, times)
}

/**
 * The middle row: the route pill takes all width up to the airline-logo
 * badge, which sits NEXT TO it on the right as its own element. [compact]
 * (short widgets) drops the city names and slims the pill and badge.
 */
@Composable
private fun RouteRow(state: WidgetState, airlineLogo: Bitmap?, compact: Boolean) {
    val hasRoute = state.originIata != null || state.destIata != null
    // Until the route fetch settles, hold the space with a loading pill —
    // confirmed-no-route (refresh done, still no airports) shows nothing
    val routeLoading = !hasRoute && state.refreshing
    if (!hasRoute && !routeLoading && airlineLogo == null) return
    Row(
        modifier = GlanceModifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Weight on a plain Box, with the pill filling it — more reliable
        // across launchers than weighting the complex pill row directly
        when {
            hasRoute -> Box(modifier = GlanceModifier.defaultWeight()) {
                RoutePill(state, compact)
            }
            routeLoading -> Box(modifier = GlanceModifier.defaultWeight()) {
                Row(
                    modifier = GlanceModifier
                        .fillMaxWidth()
                        .background(GlanceTheme.colors.surfaceVariant)
                        .cornerRadius(20.dp)
                        .padding(horizontal = 12.dp, vertical = if (compact) 8.dp else 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(
                        modifier = GlanceModifier.size(12.dp),
                        color = GlanceTheme.colors.onSurfaceVariant,
                    )
                    Spacer(GlanceModifier.width(8.dp))
                    Text(
                        text = "Looking up route…",
                        style = TextStyle(fontSize = 11.sp,
                            color = GlanceTheme.colors.onSurfaceVariant),
                        maxLines = 1,
                    )
                }
            }
            else -> Spacer(GlanceModifier.defaultWeight())
        }
        airlineLogo?.let { logo ->
            Spacer(GlanceModifier.width(6.dp))
            AirlineLogoBadge(logo, compact)
        }
    }
}

/**
 * Scheduled departure–arrival, faded italic. When a leg's actual/estimated time
 * differs from schedule, the scheduled value is struck through and the actual is
 * shown beside it — green when early/on time, red when late. Deliberately quiet:
 * it sits in the background, under the airline line.
 */
@Composable
private fun ScheduleTimesRow(state: WidgetState, palette: WidgetPalette) {
    val schedDep = state.schedDepLocal ?: return
    Row(verticalAlignment = Alignment.CenterVertically) {
        TimeChunk(schedDep, state.actualDepLocal, state.depDelayMin, palette)
        Text(
            text = " – ",
            style = TextStyle(fontSize = 9.sp, color = palette.onBgVariant),
            maxLines = 1,
        )
        state.schedArrLocal?.let {
            TimeChunk(it, state.actualArrLocal, state.arrDelayMin, palette)
        }
    }
}

@Composable
private fun TimeChunk(sched: String, actual: String?, delayMin: Int?, palette: WidgetPalette) {
    if (actual != null && actual != sched) {
        Text(
            text = sched,
            style = TextStyle(fontSize = 9.sp,
                textDecoration = TextDecoration.LineThrough, color = palette.onBgVariant),
            maxLines = 1,
        )
        Spacer(GlanceModifier.width(3.dp))
        Text(
            text = actual,
            style = TextStyle(
                fontSize = 9.sp,
                // Softer than the pill's delay chip — this line is background
                color = if ((delayMin ?: 0) > 2)
                    ColorProvider(androidx.compose.ui.graphics.Color(0xFFD0605C))
                else ColorProvider(androidx.compose.ui.graphics.Color(0xFF5C9E60)),
            ),
            maxLines = 1,
        )
    } else {
        Text(
            text = sched,
            style = TextStyle(fontSize = 9.sp, color = palette.onBgVariant),
            maxLines = 1,
        )
    }
}

/**
 * White rounded badge — airline logos are drawn for light backgrounds.
 * Compact: no taller than the slim route pill beside it.
 */
@Composable
private fun AirlineLogoBadge(logo: Bitmap, compact: Boolean) {
    Box(
        modifier = GlanceModifier
            .background(ColorProvider(androidx.compose.ui.graphics.Color.White))
            .cornerRadius(14.dp)
            .padding(5.dp),
    ) {
        Image(
            provider = ImageProvider(logo),
            contentDescription = "airline",
            modifier = GlanceModifier.size(if (compact) 20.dp else 24.dp),
        )
    }
}

/**
 * Expressive tonal pill spanning its slot: origin left, the plane positioned
 * along a dotted path at its real journey progress, time-to-arrival under it,
 * destination right. [compact] leaves out the city names under the codes.
 */
@Composable
private fun RoutePill(state: WidgetState, compact: Boolean) {
    if (state.originIata == null && state.destIata == null) return
    val context = LocalContext.current
    Row(
        modifier = GlanceModifier
            .fillMaxWidth()
            .background(GlanceTheme.colors.secondaryContainer)
            .cornerRadius(20.dp) // full pill: radius ≈ half the pill height
            .padding(horizontal = 12.dp, vertical = if (compact) 3.dp else 5.dp)
            .clickable(
                androidx.glance.action.actionStartActivity<com.sam.airblock.ui.MainActivity>()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Endpoint(state.originIata, state.originFlag, state.originCity.takeUnless { compact },
            horizontal = Alignment.Start)
        Column(
            modifier = GlanceModifier.defaultWeight().padding(horizontal = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val tint = GlanceTheme.colors.primary.getColor(context).toArgb()
            val pathBitmap = SharedBitmaps.get("route|${state.routeProgress}|$tint") {
                routeProgressBitmap(context, tint, state.routeProgress)
            }
            pathBitmap?.let {
                Image(
                    provider = ImageProvider(it),
                    contentDescription = "route progress",
                    modifier = GlanceModifier.fillMaxWidth().height(14.dp),
                )
            }
            // Real times from AeroAPI: elapsed-since-departure / total
            // scheduled duration ("00:25/03:12"), with how much LONGER (+) or
            // SHORTER (−) the journey is running than scheduled beside it — this
            // is the flight-time difference (arrival delay minus departure
            // delay), not the landing delay. Falls back to the geometry ETA.
            val flown = elapsedOverTotal(state)
            if (flown != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = flown,
                        style = TextStyle(fontSize = 10.sp,
                            color = GlanceTheme.colors.onSecondaryContainer),
                        maxLines = 1,
                    )
                    flightTimeDelta(state)?.let { d ->
                        if (d in -1..1) return@let
                        Spacer(GlanceModifier.width(4.dp))
                        Text(
                            text = if (d > 0) "+$d" else "$d",
                            style = TextStyle(
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (d > 0) GlanceTheme.colors.error
                                else ColorProvider(
                                    androidx.compose.ui.graphics.Color(0xFF2E7D32)),
                            ),
                            maxLines = 1,
                        )
                    }
                }
            } else state.etaEpochMs?.let { eta ->
                val mins = ((eta - System.currentTimeMillis()) / 60_000).toInt()
                if (mins in 0..(24 * 60)) {
                    Text(
                        text = "ETA " + if (mins < 60) "${mins} min"
                        else "${mins / 60}h ${mins % 60}m",
                        style = TextStyle(fontSize = 10.sp,
                            color = GlanceTheme.colors.onSecondaryContainer),
                        maxLines = 1,
                    )
                }
            }
        }
        Endpoint(state.destIata, state.destFlag, state.destCity.takeUnless { compact },
            horizontal = Alignment.End)
    }
}

/**
 * Always-an-airplane placeholder for the photo box: the aircraft type's
 * silhouette, tinted, centered on a rounded tonal card. 3:2 like the photos.
 */
private fun photoPlaceholderBitmap(
    context: Context,
    iconRes: Int,
    bg: Int,
    fg: Int,
    cornerPx: Float,
): Bitmap {
    val w = 480
    val h = 320
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    canvas.drawRoundRect(
        android.graphics.RectF(0f, 0f, w.toFloat(), h.toFloat()),
        cornerPx, cornerPx,
        Paint(Paint.ANTI_ALIAS_FLAG).apply { color = bg },
    )
    context.getDrawable(iconRes)?.mutate()?.let { d ->
        d.setTint(fg)
        val side = (h * 0.66f).toInt()
        d.setBounds(w / 2 - side / 2, h / 2 - side / 2, w / 2 + side / 2, h / 2 + side / 2)
        d.draw(canvas)
    }
    return bmp
}

/**
 * Rounds the bitmap's own corners. The radius is scaled so it visually
 * matches 16dp at the size the photo is displayed.
 */
private fun roundCorners(src: Bitmap, radius: Float): Bitmap {
    val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(out)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    canvas.drawRoundRect(
        android.graphics.RectF(0f, 0f, src.width.toFloat(), src.height.toFloat()),
        radius, radius, paint,
    )
    paint.xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC_IN)
    canvas.drawBitmap(src, 0f, 0f, paint)
    return out
}

/**
 * M3 Expressive-style wavy flight path, mirroring LinearWavyProgressIndicator:
 * the flown portion is a wavy stroke, the remaining track a thin flat line
 * with a gap around the plane glyph and a stop dot at the destination end.
 * The plane sits at the journey-progress fraction (centre when unknown).
 */
private fun routeProgressBitmap(context: Context, color: Int, progress: Float?): Bitmap {
    val w = 480
    val h = 64
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    val cy = h / 2f
    val planeSize = 52
    val gap = planeSize / 2f + 10f
    val px = (progress ?: 0.5f).coerceIn(0.07f, 0.93f) * w
    val trackColor = (color and 0x00FFFFFF) or (0x59 shl 24) // 35% alpha track

    val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.STROKE
        strokeWidth = 9f
        strokeCap = Paint.Cap.ROUND
    }
    // Flown portion: sine wave from the origin up to the plane
    val wave = android.graphics.Path()
    val amplitude = 9f
    val wavelength = 72f
    var x = 8f
    var started = false
    while (x <= px - gap) {
        val y = cy + amplitude *
            kotlin.math.sin(x / wavelength * 2f * Math.PI.toFloat())
        if (!started) { wave.moveTo(x, y); started = true } else wave.lineTo(x, y)
        x += 4f
    }
    if (started) canvas.drawPath(wave, wavePaint)

    // Remaining portion: thin flat track from the plane to the destination,
    // finished with the M3 stop indicator dot
    val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = trackColor
        style = Paint.Style.STROKE
        strokeWidth = 9f
        strokeCap = Paint.Cap.ROUND
    }
    if (px + gap < w - 8f) canvas.drawLine(px + gap, cy, w - 8f, cy, trackPaint)
    canvas.drawCircle(w - 10f, cy, 5f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
    })

    context.getDrawable(R.drawable.ic_flight)?.mutate()?.let { d ->
        d.setTint(color)
        canvas.save()
        canvas.rotate(90f, px, cy)
        d.setBounds(
            (px - planeSize / 2).toInt(), (cy - planeSize / 2).toInt(),
            (px + planeSize / 2).toInt(), (cy + planeSize / 2).toInt(),
        )
        d.draw(canvas)
        canvas.restore()
    }
    return bmp
}

@Composable
private fun Endpoint(
    iata: String?,
    flag: String?,
    city: String?,
    horizontal: Alignment.Horizontal,
) {
    Column(horizontalAlignment = horizontal) {
        Text(
            text = listOfNotNull(iata ?: "?", flag?.takeIf { it.isNotEmpty() })
                .joinToString(" "),
            style = TextStyle(
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = GlanceTheme.colors.onSecondaryContainer,
            ),
        )
        city?.let {
            // Cap the width so a long city name can't squeeze the progress bar
            // in the middle — clip the name instead of cropping the bar.
            Text(
                text = if (it.length > 12) it.take(11).trimEnd() + "…" else it,
                style = TextStyle(fontSize = 10.sp,
                    color = GlanceTheme.colors.onSecondaryContainer),
                maxLines = 1,
            )
        }
    }
}

/**
 * The stat pills at their natural size, with WEIGHTED spacers between them:
 * leftover width grows the gaps (space-between), so the row reaches the true
 * widget edges with no scaling, no stretching, and razor-sharp native text.
 */
@Composable
private fun ChipsRow(state: WidgetState) {
    Row(
        modifier = GlanceModifier.fillMaxWidth()
            .clickable(
                androidx.glance.action.actionStartActivity<com.sam.airblock.ui.MainActivity>()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Emergency squawk leads the row in error colors
        state.squawkAlert?.let {
            Chip(
                icon = R.drawable.ic_warning,
                label = it,
                bg = GlanceTheme.colors.errorContainer,
                fg = GlanceTheme.colors.onErrorContainer,
            )
            ChipGap()
        }
        Chip(
            icon = R.drawable.ic_altitude,
            label = if (state.onGround) "ground" else Units.formatAltitude(state.altitudeFt),
            bg = GlanceTheme.colors.secondaryContainer,
            fg = GlanceTheme.colors.onSecondaryContainer,
        )
        ChipGap()
        // Speed and Mach combined in one pill (Glance can't do per-corner
        // radii, so a true split button isn't possible)
        Chip(
            icon = R.drawable.ic_speed,
            // Thin spaces around the separator: the row is width-critical
            label = (state.speedMph?.let { Units.formatSpeed(it) } ?: "—") +
                (state.mach?.let { " · M" + "%.2f".format(it).trimStart('0') } ?: ""),
            bg = GlanceTheme.colors.tertiaryContainer,
            fg = GlanceTheme.colors.onTertiaryContainer,
        )
        ChipGap()
        Chip(
            icon = R.drawable.ic_distance,
            label = state.distanceKm?.let { Units.formatKm(it) } ?: "—",
            bg = GlanceTheme.colors.primaryContainer,
            fg = GlanceTheme.colors.onPrimaryContainer,
        )
        state.registration?.let { reg ->
            ChipGap()
            // No icon: a tag glyph says nothing the registration text doesn't,
            // and this row hasn't a dp to spare — it's the chip that clips
            Chip(
                icon = null,
                label = reg,
                bg = GlanceTheme.colors.surfaceVariant,
                fg = GlanceTheme.colors.onSurfaceVariant,
            )
        }
    }
}

/** Minimum 4dp between pills; the weighted spacer absorbs all leftover width. */
@Composable
private fun RowScope.ChipGap() {
    Spacer(GlanceModifier.width(4.dp))
    Spacer(GlanceModifier.defaultWeight())
}

@Composable
private fun Chip(
    icon: Int?,
    label: String,
    bg: ColorProvider,
    fg: ColorProvider,
) {
    Row(
        modifier = GlanceModifier.background(bg).cornerRadius(12.dp)
            .padding(horizontal = 5.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        icon?.let {
            Image(
                provider = ImageProvider(it),
                contentDescription = null,
                colorFilter = ColorFilter.tint(fg),
                modifier = GlanceModifier.size(10.dp),
            )
            Spacer(GlanceModifier.width(2.dp))
        }
        Text(text = label, style = TextStyle(fontSize = 10.sp, color = fg), maxLines = 1)
    }
}

@Composable
private fun EmptyMessage(message: String) {
    Box(modifier = GlanceModifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Image(
                provider = ImageProvider(R.drawable.ic_plane_placeholder),
                contentDescription = null,
                modifier = GlanceModifier.size(40.dp),
            )
            Spacer(GlanceModifier.height(6.dp))
            Text(
                text = message,
                style = TextStyle(fontSize = 13.sp, color = GlanceTheme.colors.onSurfaceVariant),
            )
        }
    }
}

/**
 * Text in heavy expressive display type, rendered as a bitmap — RemoteViews
 * text can't reach the tight, black weights M3 Expressive headlines use.
 * Returns a bitmap trimmed to the text bounds; scales down to [maxWidthPx].
 */
private fun expressiveText(text: String, color: Int, heightPx: Int, maxWidthPx: Int): Bitmap {
    val paint = expressivePaint(color, heightPx)
    var w = paint.measureText(text)
    if (maxWidthPx > 0 && w > maxWidthPx) {
        paint.textSize *= maxWidthPx / w
        w = paint.measureText(text)
    }
    val fm = paint.fontMetrics
    val h = (fm.descent - fm.ascent).toInt().coerceAtLeast(1)
    val bmp = Bitmap.createBitmap(w.toInt().coerceAtLeast(1), h, Bitmap.Config.ARGB_8888)
    Canvas(bmp).drawText(text, 0f, -fm.ascent, paint)
    return bmp
}

/** The paint behind [expressiveText] — also used to measure its height. */
private fun expressivePaint(color: Int, heightPx: Int) =
    Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        this.color = color
        typeface = android.graphics.Typeface.create(
            "sans-serif-black", android.graphics.Typeface.BOLD)
        textSize = heightPx * 0.82f
        letterSpacing = -0.02f // expressive headlines run tight
    }

/**
 * Elapsed-since-departure / total-scheduled-duration for the route pill
 * ("00:25/03:12"), or null when AeroAPI times don't apply. Elapsed counts from
 * the actual departure (estimated, then scheduled, as fallbacks); total is the
 * scheduled gate-to-gate duration. Total is omitted if unknown.
 */
private fun elapsedOverTotal(state: WidgetState): String? {
    if (!state.timesAreReal) return null
    val dep = state.actualDepEpochMs ?: state.schedDepEpochMs ?: return null
    val elapsed = hhmm((System.currentTimeMillis() - dep).coerceAtLeast(0))
    val total = if (state.schedDepEpochMs != null && state.schedArrEpochMs != null)
        state.schedArrEpochMs - state.schedDepEpochMs else null
    return if (total != null && total > 0) "$elapsed/${hhmm(total)}" else elapsed
}

/** Milliseconds as zero-padded "HH:mm". */
private fun hhmm(ms: Long): String {
    val m = (ms / 60_000).toInt()
    return "%02d:%02d".format(m / 60, m % 60)
}

/**
 * How much longer (+) or shorter (−) the journey is running than scheduled, in
 * minutes — the flight-time difference, i.e. arrival delay minus departure
 * delay. (A flight that leaves 30 late and lands 30 late kept its booked
 * duration → 0, not +30.) Falls back to the arrival delay when the departure
 * delay is unknown; null when no real times apply.
 */
private fun flightTimeDelta(state: WidgetState): Int? {
    val arr = state.arrDelayMin ?: return null
    return state.depDelayMin?.let { arr - it } ?: arr
}

/** Schedule-aware: data on the 10-min plan isn't stale after 2 minutes. */
private fun isStale(state: WidgetState): Boolean {
    if (state.updatedAt <= 0) return false
    val deadline = if (state.staleAfterMs > 0) state.staleAfterMs
    else state.updatedAt + 2 * 60_000
    return System.currentTimeMillis() > deadline
}
