package com.sam.airblock.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.toArgb
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
import androidx.glance.appwidget.CircularProgressIndicator
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.sam.airblock.R
import com.sam.airblock.data.WidgetState
import com.sam.airblock.data.WidgetStateStore
import com.sam.airblock.util.Units

class AirblockWidget : GlanceAppWidget() {

    // Exact size so the layout can compute a true 3:2 photo box
    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        // provideGlance runs ONCE per widget session; updateAll() only
        // recomposes. State must therefore be observed INSIDE the composition,
        // otherwise the widget keeps rendering a stale snapshot while the
        // process is alive (the bug: app showed fresh data, widget didn't).
        val initial = WidgetStateStore.read(context)
        provideContent {
            val state by WidgetStateStore.flow(context).collectAsState(initial)
            val photo = remember(state.photoPath) {
                state.photoPath?.let { decodePhoto(it) }
            }
            val airlineLogo = remember(state.airlineLogoPath) {
                state.airlineLogoPath?.let { BitmapFactory.decodeFile(it) }
            }
            GlanceTheme {
                WidgetContent(state, photo, airlineLogo)
            }
        }
    }

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
}

/** Content colors paired with the widget background (varies for special aircraft). */
private data class WidgetPalette(
    val bg: ColorProvider,
    val onBg: ColorProvider,
    val onBgVariant: ColorProvider,
)

@Composable
private fun widgetPalette(specialType: String?): WidgetPalette = when (specialType) {
    null -> WidgetPalette(
        GlanceTheme.colors.widgetBackground,
        GlanceTheme.colors.onSurface,
        GlanceTheme.colors.onSurfaceVariant,
    )
    "Military" -> WidgetPalette(
        GlanceTheme.colors.errorContainer,
        GlanceTheme.colors.onErrorContainer,
        GlanceTheme.colors.onErrorContainer,
    )
    else -> WidgetPalette(
        GlanceTheme.colors.tertiaryContainer,
        GlanceTheme.colors.onTertiaryContainer,
        GlanceTheme.colors.onTertiaryContainer,
    )
}

@Composable
private fun WidgetContent(state: WidgetState, photo: Bitmap?, airlineLogo: Bitmap?) {
    // Non-standard aircraft (military, police helicopters…) get an
    // attention-grabbing tonal background — content colors must follow the
    // container role or dark-theme contrast breaks.
    val palette = widgetPalette(state.specialType)
    val bg = palette.bg
    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(bg)
            // Match the corner radius the launcher clips widgets to
            .cornerRadius(android.R.dimen.system_app_widget_background_radius)
            .padding(10.dp)
            .clickable(actionRunCallback<RefreshAction>()),
    ) {
        when (state.status) {
            WidgetState.Status.OK -> AircraftCard(state, photo, airlineLogo, palette)
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
    val showSpinner = state.refreshing
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
    photo: Bitmap?,
    airlineLogo: Bitmap?,
    palette: WidgetPalette,
) {
    val widgetSize = LocalSize.current
    val hasRoute = state.originIata != null || state.destIata != null
    val hasRouteRow = hasRoute || airlineLogo != null
    // Slightly conservative middle-row budget: the photo box must never be
    // TALLER than the real top row or the launcher clips it back into a crop.
    val topRowHeight = widgetSize.height - 20.dp /* card padding */ -
        24.dp /* chips */ - (if (hasRouteRow) 58.dp else 12.dp) /* pill + spacers */
    // The photo dictates the box: BOTH dimensions come from the same number,
    // so box aspect == photo aspect exactly and the full picture shows.
    // (Previously height came from fillMaxHeight while width came from this
    // estimate — any mismatch between the two turned straight into cropping.)
    val aspect = if (photo != null && photo.height > 0)
        (photo.width.toFloat() / photo.height).coerceIn(1.0f, 2.3f) else 1.6f
    val photoWidth = minOf(topRowHeight * aspect, widgetSize.width * 0.6f)

    Column(modifier = GlanceModifier.fillMaxSize()) {
        // Top row: landscape photo + callsign/type side by side
        Row(
            modifier = GlanceModifier.fillMaxWidth().defaultWeight(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = GlanceModifier.height(topRowHeight).width(photoWidth)
                    .cornerRadius(16.dp)
                    .background(GlanceTheme.colors.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                if (photo != null) {
                    Image(
                        provider = ImageProvider(photo),
                        contentDescription = state.typeName,
                        // Fit, not Crop: the box already matches the photo's
                        // aspect, and on the rare clamp the photo letterboxes
                        // instead of losing the airframe's nose or tail
                        contentScale = ContentScale.Fit,
                        modifier = GlanceModifier.fillMaxSize().cornerRadius(16.dp),
                    )
                } else {
                    Image(
                        provider = ImageProvider(R.drawable.ic_plane_placeholder),
                        contentDescription = null,
                        modifier = GlanceModifier.size(36.dp),
                    )
                }
            }
            Spacer(GlanceModifier.width(12.dp))
            Column(modifier = GlanceModifier.defaultWeight()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = state.callsign ?: "—",
                        style = TextStyle(
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold,
                            color = palette.onBg,
                        ),
                        maxLines = 1,
                    )
                    // Non-standard aircraft badge next to the name
                    state.specialType?.let { special ->
                        Spacer(GlanceModifier.width(6.dp))
                        Row(
                            modifier = GlanceModifier
                                .background(GlanceTheme.colors.error)
                                .cornerRadius(12.dp)
                                .padding(horizontal = 6.dp, vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Image(
                                provider = ImageProvider(R.drawable.ic_shield),
                                contentDescription = special,
                                colorFilter = ColorFilter.tint(GlanceTheme.colors.onError),
                                modifier = GlanceModifier.size(10.dp),
                            )
                            Spacer(GlanceModifier.width(3.dp))
                            Text(
                                text = special.uppercase(),
                                style = TextStyle(fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = GlanceTheme.colors.onError),
                                maxLines = 1,
                            )
                        }
                    }
                }
                state.typeName?.let {
                    Text(
                        text = it,
                        style = TextStyle(fontSize = 12.sp,
                            color = palette.onBgVariant),
                        maxLines = 1,
                    )
                }
            }
        }
        Spacer(GlanceModifier.height(6.dp))
        RouteRow(state, airlineLogo)
        Spacer(GlanceModifier.height(6.dp))
        ChipsRow(state)
    }
}

/**
 * The middle row: the route pill takes all width up to the airline-logo
 * badge, which sits NEXT TO it on the right as its own element.
 */
@Composable
private fun RouteRow(state: WidgetState, airlineLogo: Bitmap?) {
    val hasRoute = state.originIata != null || state.destIata != null
    if (!hasRoute && airlineLogo == null) return
    Row(
        modifier = GlanceModifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Weight on a plain Box, with the pill filling it — more reliable
        // across launchers than weighting the complex pill row directly
        if (hasRoute) {
            Box(modifier = GlanceModifier.defaultWeight()) { RoutePill(state) }
        } else {
            Spacer(GlanceModifier.defaultWeight())
        }
        airlineLogo?.let { logo ->
            Spacer(GlanceModifier.width(6.dp))
            AirlineLogoBadge(logo)
        }
    }
}

/** White rounded badge — airline logos are drawn for light backgrounds. */
@Composable
private fun AirlineLogoBadge(logo: Bitmap) {
    Box(
        modifier = GlanceModifier
            .background(ColorProvider(androidx.compose.ui.graphics.Color.White))
            .cornerRadius(14.dp)
            .padding(5.dp),
    ) {
        Image(
            provider = ImageProvider(logo),
            contentDescription = "airline",
            modifier = GlanceModifier.size(24.dp),
        )
    }
}

/**
 * Expressive tonal pill spanning its slot: origin left, the plane positioned
 * along a dotted path at its real journey progress, time-to-arrival under it,
 * destination right.
 */
@Composable
private fun RoutePill(state: WidgetState) {
    if (state.originIata == null && state.destIata == null) return
    val context = LocalContext.current
    Row(
        modifier = GlanceModifier
            .fillMaxWidth()
            .background(GlanceTheme.colors.secondaryContainer)
            .cornerRadius(20.dp) // full pill: radius ≈ half the pill height
            .padding(horizontal = 12.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Endpoint(state.originIata, state.originFlag, state.originCity,
            horizontal = Alignment.Start)
        Column(
            modifier = GlanceModifier.defaultWeight().padding(horizontal = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val tint = GlanceTheme.colors.primary.getColor(context).toArgb()
            val pathBitmap = remember(state.routeProgress, tint) {
                routeProgressBitmap(context, tint, state.routeProgress)
            }
            Image(
                provider = ImageProvider(pathBitmap),
                contentDescription = "route progress",
                modifier = GlanceModifier.fillMaxWidth().height(14.dp),
            )
            // Remaining time, not a clock time: "ETA 14:05" reads as the
            // phone's timezone while trackers show the DESTINATION's local
            // arrival — a duration can't be misread either way.
            state.etaEpochMs?.let { eta ->
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
        Endpoint(state.destIata, state.destFlag, state.destCity,
            horizontal = Alignment.End)
    }
}

/**
 * Dotted flight path with the plane glyph drawn at the journey-progress
 * fraction (defaults to centre when unknown).
 */
private fun routeProgressBitmap(context: Context, color: Int, progress: Float?): Bitmap {
    val w = 480
    val h = 64
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
    val cy = h / 2f
    val planeSize = 52
    val px = (progress ?: 0.5f).coerceIn(0.07f, 0.93f) * w
    var x = 12f
    while (x < w - 10) {
        if (kotlin.math.abs(x - px) > planeSize / 2f + 12) canvas.drawCircle(x, cy, 5f, paint)
        x += 30f
    }
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
            Text(
                text = it,
                style = TextStyle(fontSize = 10.sp,
                    color = GlanceTheme.colors.onSecondaryContainer),
                maxLines = 1,
            )
        }
    }
}

/** Everything needed to draw one stat pill on the canvas. */
private data class ChipDraw(val icon: Int, val label: String, val bg: Int, val fg: Int)

/**
 * The stat pills, rendered as one measured bitmap. Glance rows cannot
 * shrink-to-fit (overflowing chips just get CUT OFF at the widget edge), so
 * the pills are drawn on canvas instead: they always span the full row —
 * leftover space goes into the gaps — and when space is short everything
 * scales down together by exactly the factor needed, never clipping.
 */
@Composable
private fun ChipsRow(state: WidgetState) {
    val context = LocalContext.current
    // Row width = widget width minus the card's 10dp padding per side
    val rowWidthDp = LocalSize.current.width.value - 20f
    fun argb(c: ColorProvider) = c.getColor(context).toArgb()
    val chips = buildList {
        // Emergency squawk leads the row in error colors
        state.squawkAlert?.let {
            add(ChipDraw(R.drawable.ic_warning, it,
                argb(GlanceTheme.colors.errorContainer),
                argb(GlanceTheme.colors.onErrorContainer)))
        }
        add(ChipDraw(R.drawable.ic_altitude,
            if (state.onGround) "ground" else Units.formatAltitude(state.altitudeFt),
            argb(GlanceTheme.colors.secondaryContainer),
            argb(GlanceTheme.colors.onSecondaryContainer)))
        // Speed and Mach combined in one pill
        add(ChipDraw(R.drawable.ic_speed,
            (state.speedMph?.let { Units.formatSpeed(it) } ?: "—") +
                (state.mach?.let { " · M" + "%.2f".format(it).trimStart('0') } ?: ""),
            argb(GlanceTheme.colors.tertiaryContainer),
            argb(GlanceTheme.colors.onTertiaryContainer)))
        add(ChipDraw(R.drawable.ic_distance,
            state.distanceKm?.let { Units.formatKm(it) } ?: "—",
            argb(GlanceTheme.colors.primaryContainer),
            argb(GlanceTheme.colors.onPrimaryContainer)))
        state.registration?.let {
            add(ChipDraw(R.drawable.ic_tag, it,
                argb(GlanceTheme.colors.surfaceVariant),
                argb(GlanceTheme.colors.onSurfaceVariant)))
        }
    }
    val bitmap = remember(rowWidthDp, chips) { chipsBitmap(context, chips, rowWidthDp) }
    Image(
        provider = ImageProvider(bitmap),
        contentDescription = chips.joinToString { it.label },
        modifier = GlanceModifier.fillMaxWidth().height(CHIPS_HEIGHT_DP.dp),
        // FillBounds, not Fit: LocalSize is the launcher's estimate and can be
        // a few dp under the real cell width — Fit then leaves dead bands at
        // the sides, FillBounds stretches the row to the true edges.
        contentScale = ContentScale.FillBounds,
    )
}

private const val CHIPS_HEIGHT_DP = 24f

private fun chipsBitmap(context: Context, chips: List<ChipDraw>, widthDp: Float): Bitmap {
    // 2x the native density: crisp text after the launcher scales the image
    val d = context.resources.displayMetrics.density * 2f
    val w = (widthDp * d).toInt().coerceAtLeast(1)
    val h = (CHIPS_HEIGHT_DP * d).toInt()
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    // Natural pill metrics (matching the old 10sp Glance chips). Gaps give
    // way FIRST (5dp → 3dp); the text only shrinks when even tight gaps
    // can't fit the labels — keeping it as close to full size as possible.
    fun pillWidth(c: ChipDraw, s: Float): Float {
        paint.textSize = 10f * d * s
        return (6f * d + 11f * d + 3f * d + 6f * d) * s + paint.measureText(c.label)
    }
    val minGap = 3f * d
    fun naturalWidth(s: Float) =
        chips.map { pillWidth(it, s) }.sum() + minGap * (chips.size - 1)
    val scale = (w / naturalWidth(1f)).coerceAtMost(1f)

    paint.textSize = 10f * d * scale
    val widths = chips.map { pillWidth(it, scale) }
    // Full-bleed row: all leftover width widens the gaps between pills
    val gap = if (chips.size > 1) (w - widths.sum()) / (chips.size - 1) else 0f
    val radius = h / 2f
    val fm = paint.fontMetrics
    val baseline = h / 2f - (fm.ascent + fm.descent) / 2f
    var x = 0f
    chips.forEachIndexed { i, chip ->
        paint.color = chip.bg
        canvas.drawRoundRect(x, 0f, x + widths[i], h.toFloat(), radius, radius, paint)
        var cx = x + 6f * d * scale
        val iconSize = 11f * d * scale
        context.getDrawable(chip.icon)?.mutate()?.let { ic ->
            ic.setTint(chip.fg)
            val top = (h - iconSize) / 2f
            ic.setBounds(cx.toInt(), top.toInt(),
                (cx + iconSize).toInt(), (top + iconSize).toInt())
            ic.draw(canvas)
        }
        cx += iconSize + 3f * d * scale
        paint.color = chip.fg
        canvas.drawText(chip.label, cx, baseline, paint)
        x += widths[i] + gap
    }
    return bmp
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

/** Schedule-aware: data on the 10-min plan isn't stale after 2 minutes. */
private fun isStale(state: WidgetState): Boolean {
    if (state.updatedAt <= 0) return false
    val deadline = if (state.staleAfterMs > 0) state.staleAfterMs
    else state.updatedAt + 2 * 60_000
    return System.currentTimeMillis() > deadline
}
