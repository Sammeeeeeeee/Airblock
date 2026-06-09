package com.sam.airblock.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
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
import com.sam.airblock.data.WidgetState
import com.sam.airblock.data.WidgetStateStore
import com.sam.airblock.util.Units

class AirblockWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val state = WidgetStateStore.read(context)
        val photo = state.photoPath?.let { decodePhoto(it) }
        provideContent {
            GlanceTheme {
                WidgetContent(state, photo)
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

@Composable
private fun WidgetContent(state: WidgetState, photo: Bitmap?) {
    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(GlanceTheme.colors.surface)
            .cornerRadius(24.dp)
            .padding(12.dp)
            .clickable(actionRunCallback<RefreshAction>()),
    ) {
        when (state.status) {
            WidgetState.Status.OK -> AircraftCard(state, photo)
            WidgetState.Status.NO_AIRCRAFT -> EmptyMessage("No aircraft nearby")
            WidgetState.Status.NO_LOCATION -> EmptyMessage("Location unavailable — tap to retry")
            else -> EmptyMessage("Airblock — tap to refresh")
        }
    }
}

@Composable
private fun AircraftCard(state: WidgetState, photo: Bitmap?) {
    Row(modifier = GlanceModifier.fillMaxSize()) {
        // Photo
        Box(
            modifier = GlanceModifier.fillMaxHeight().width(110.dp).cornerRadius(16.dp)
                .background(GlanceTheme.colors.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            if (photo != null) {
                Image(
                    provider = ImageProvider(photo),
                    contentDescription = state.typeName,
                    contentScale = ContentScale.Crop,
                    modifier = GlanceModifier.fillMaxSize().cornerRadius(16.dp),
                )
            } else {
                Image(
                    provider = ImageProvider(R.drawable.ic_plane_placeholder),
                    contentDescription = null,
                    modifier = GlanceModifier.size(56.dp),
                )
            }
        }
        Spacer(GlanceModifier.width(12.dp))

        Column(modifier = GlanceModifier.fillMaxSize()) {
            // Callsign + stale hint
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = state.callsign ?: "—",
                    style = TextStyle(
                        fontSize = 26.sp,
                        fontWeight = FontWeight.Bold,
                        color = GlanceTheme.colors.onSurface,
                    ),
                )
                if (isStale(state)) {
                    Spacer(GlanceModifier.width(6.dp))
                    Text(
                        text = "old",
                        style = TextStyle(fontSize = 10.sp, color = GlanceTheme.colors.outline),
                    )
                }
            }
            state.typeName?.let {
                Text(
                    text = it,
                    style = TextStyle(fontSize = 13.sp, color = GlanceTheme.colors.onSurfaceVariant),
                    maxLines = 1,
                )
            }
            Spacer(GlanceModifier.height(4.dp))
            RouteRow(state)
            Spacer(GlanceModifier.defaultWeight())
            ChipsRow(state)
        }
    }
}

@Composable
private fun RouteRow(state: WidgetState) {
    if (state.originIata == null && state.destIata == null) return
    Row(verticalAlignment = Alignment.CenterVertically) {
        Endpoint(state.originIata, state.originFlag, state.originCity)
        Spacer(GlanceModifier.width(8.dp))
        Image(
            provider = ImageProvider(R.drawable.ic_flight),
            contentDescription = null,
            colorFilter = ColorFilter.tint(GlanceTheme.colors.primary),
            modifier = GlanceModifier.size(14.dp),
        )
        Spacer(GlanceModifier.width(8.dp))
        Endpoint(state.destIata, state.destFlag, state.destCity)
    }
}

@Composable
private fun Endpoint(iata: String?, flag: String?, city: String?) {
    Column {
        Text(
            text = listOfNotNull(iata ?: "?", flag?.takeIf { it.isNotEmpty() })
                .joinToString(" "),
            style = TextStyle(
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = GlanceTheme.colors.onSurface,
            ),
        )
        city?.let {
            Text(
                text = it,
                style = TextStyle(fontSize = 10.sp, color = GlanceTheme.colors.onSurfaceVariant),
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun ChipsRow(state: WidgetState) {
    Row {
        // Emergency squawk replaces nothing — it leads the row in error colors
        state.squawkAlert?.let {
            Chip(
                icon = R.drawable.ic_warning,
                label = it,
                bg = GlanceTheme.colors.errorContainer,
                fg = GlanceTheme.colors.onErrorContainer,
            )
            Spacer(GlanceModifier.width(6.dp))
        }
        Chip(R.drawable.ic_altitude,
            if (state.onGround) "ground" else Units.formatAltitude(state.altitudeFt))
        Spacer(GlanceModifier.width(6.dp))
        Chip(R.drawable.ic_speed, state.speedMph?.let { Units.formatSpeed(it) } ?: "—")
        Spacer(GlanceModifier.width(6.dp))
        Chip(R.drawable.ic_distance, state.distanceKm?.let { Units.formatKm(it) } ?: "—")
    }
}

@Composable
private fun Chip(
    icon: Int,
    label: String,
    bg: ColorProvider = GlanceTheme.colors.secondaryContainer,
    fg: ColorProvider = GlanceTheme.colors.onSecondaryContainer,
) {
    Row(
        modifier = GlanceModifier.background(bg).cornerRadius(12.dp)
            .padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            provider = ImageProvider(icon),
            contentDescription = null,
            colorFilter = ColorFilter.tint(fg),
            modifier = GlanceModifier.size(12.dp),
        )
        Spacer(GlanceModifier.width(4.dp))
        Text(text = label, style = TextStyle(fontSize = 11.sp, color = fg), maxLines = 1)
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

private fun isStale(state: WidgetState): Boolean =
    System.currentTimeMillis() - state.updatedAt > 2 * 60_000
