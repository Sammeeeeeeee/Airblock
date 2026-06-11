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
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
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
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.sam.airblock.R
import com.sam.airblock.data.ManufacturerLogoRepo
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
            val manufacturerLogo = remember(state.manufacturerLogoPath) {
                state.manufacturerLogoPath?.let { BitmapFactory.decodeFile(it) }
            }
            val modelLogo = remember(state.modelLogoPath) {
                state.modelLogoPath?.let { BitmapFactory.decodeFile(it) }
            }
            GlanceTheme {
                WidgetContent(state, photo, airlineLogo, manufacturerLogo, modelLogo)
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
private fun WidgetContent(
    state: WidgetState,
    photo: Bitmap?,
    airlineLogo: Bitmap?,
    manufacturerLogo: Bitmap?,
    modelLogo: Bitmap?,
) {
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
            // Tapping the widget opens the app (which kicks a refresh on open)
            .clickable(
                androidx.glance.action.actionStartActivity<com.sam.airblock.ui.MainActivity>()),
    ) {
        when (state.status) {
            WidgetState.Status.OK ->
                AircraftCard(state, photo, airlineLogo, manufacturerLogo, modelLogo, palette)
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
    manufacturerLogo: Bitmap?,
    modelLogo: Bitmap?,
    palette: WidgetPalette,
) {
    val widgetSize = LocalSize.current
    val context = LocalContext.current
    val density = context.resources.displayMetrics.density
    // Photo sizing FROM WIDTH ONLY. Launchers (Niagara et al.) misreport the
    // widget's height bucket — deriving the photo from height is what made it
    // render as a narrow cropped strip (v2.2) or a tiny thumbnail (later).
    // Width is the one dimension launchers report reliably.
    val photoWidth = widgetSize.width * 0.42f

    Column(modifier = GlanceModifier.fillMaxSize()) {
        // Top row: landscape photo + callsign/type side by side
        Row(
            modifier = GlanceModifier.fillMaxWidth().defaultWeight(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // cornerRadius clips the VIEW — a Fit-letterboxed bitmap sits
            // inside the view, so its corners came out square. Bake the
            // rounding into the bitmap's pixels instead.
            val roundedPhoto = remember(state.photoPath, photoWidth.value) {
                photo?.let {
                    roundCorners(it, it.width * 16f / photoWidth.value.coerceAtLeast(1f))
                }
            }
            // Background only behind the placeholder: with Fit, any unused
            // sliver of the box must stay invisible, not show as a gray band
            val photoBox = GlanceModifier.fillMaxHeight().width(photoWidth).cornerRadius(16.dp)
            Box(
                modifier = if (roundedPhoto == null)
                    photoBox.background(GlanceTheme.colors.surfaceVariant) else photoBox,
                contentAlignment = Alignment.Center,
            ) {
                if (roundedPhoto != null) {
                    Image(
                        provider = ImageProvider(roundedPhoto),
                        contentDescription = state.typeName,
                        contentScale = ContentScale.Fit,
                        modifier = GlanceModifier.fillMaxSize(),
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
                    // The callsign in real M3 Expressive display type: drawn
                    // as a bitmap because RemoteViews text can't reach the
                    // heavy, tight expressive weights
                    val callsignText = state.callsign ?: "—"
                    val callsignColor = palette.onBg.getColor(context).toArgb()
                    val maxCallsignWidthPx =
                        ((widgetSize.width.value * 0.62f - 24f) * density).toInt()
                    val callsignBmp = remember(callsignText, callsignColor, maxCallsignWidthPx) {
                        expressiveText(callsignText, callsignColor,
                            heightPx = (28 * density).toInt(), maxWidthPx = maxCallsignWidthPx)
                    }
                    // Tapping the callsign opens this flight in Flightradar24
                    // (or the browser when the app isn't installed)
                    Image(
                        provider = ImageProvider(callsignBmp),
                        contentDescription = callsignText,
                        modifier = GlanceModifier
                            .width((callsignBmp.width / density).dp)
                            .height((callsignBmp.height / density).dp)
                            .clickable(
                                actionStartActivity(
                                    android.content.Intent(
                                        android.content.Intent.ACTION_VIEW,
                                        android.net.Uri.parse(
                                            "https://www.flightradar24.com/" +
                                                callsignText.replace(" ", "")),
                                    )
                                )
                            ),
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
                // Type line: manufacturer WORDMARK (tinted to theme) + model,
                // falling back to the full text when no logo is cached
                state.typeName?.let { typeName ->
                    val mfr = manufacturerLogo?.let {
                        ManufacturerLogoRepo.manufacturerOf(typeName)?.let { name ->
                            name to ManufacturerLogoRepo.modelOf(typeName, name)
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (manufacturerLogo != null && mfr != null) {
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
                            if (modelLogo != null) {
                                // The plane's OWN logo (A380, 787 Dreamliner…)
                                Spacer(GlanceModifier.width(6.dp))
                                val mLogoH = 13f
                                val mLogoW = (mLogoH * modelLogo.width /
                                    modelLogo.height).coerceAtMost(72f)
                                Image(
                                    provider = ImageProvider(modelLogo),
                                    contentDescription = model,
                                    colorFilter = ColorFilter.tint(palette.onBgVariant),
                                    modifier = GlanceModifier.width(mLogoW.dp).height(mLogoH.dp),
                                )
                            } else if (model.isNotEmpty()) {
                                Spacer(GlanceModifier.width(5.dp))
                                Text(
                                    text = model,
                                    style = TextStyle(fontSize = 12.sp,
                                        color = palette.onBgVariant),
                                    maxLines = 1,
                                )
                            }
                        } else {
                            Text(
                                text = typeName,
                                style = TextStyle(fontSize = 12.sp,
                                    color = palette.onBgVariant),
                                maxLines = 1,
                            )
                        }
                    }
                }
                // The operating airline, quietly under the type
                state.airlineName?.let { airline ->
                    Text(
                        text = airline,
                        style = TextStyle(fontSize = 10.sp, color = palette.onBgVariant),
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
            Text(
                text = it,
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
        modifier = GlanceModifier.fillMaxWidth(),
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
    val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        this.color = color
        typeface = android.graphics.Typeface.create(
            "sans-serif-black", android.graphics.Typeface.BOLD)
        textSize = heightPx * 0.82f
        letterSpacing = -0.02f // expressive headlines run tight
    }
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

/** Schedule-aware: data on the 10-min plan isn't stale after 2 minutes. */
private fun isStale(state: WidgetState): Boolean {
    if (state.updatedAt <= 0) return false
    val deadline = if (state.staleAfterMs > 0) state.staleAfterMs
    else state.updatedAt + 2 * 60_000
    return System.currentTimeMillis() > deadline
}
