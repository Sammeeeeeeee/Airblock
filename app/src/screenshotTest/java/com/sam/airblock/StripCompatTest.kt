package com.sam.airblock

import android.content.Context
import android.os.Parcel
import android.view.View
import android.widget.FrameLayout
import android.widget.ViewFlipper
import androidx.test.core.app.ApplicationProvider
import com.sam.airblock.data.WidgetState
import com.sam.airblock.widget.FlightStrip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Every RemoteViews call the strip makes must be allowed on every Android it
 * runs on — a method that isn't remotable on one version throws when the
 * launcher applies it, leaving a "Can't load widget" box. Applies a frame for
 * each state on the oldest and newest supported releases.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 35], qualifiers = "w411dp-h891dp-420dpi")
class StripCompatTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private val now = 1_762_425_540_000L

    /**
     * What one update weighs, bitmaps written inline (a device may move big
     * ones to shared memory, so this is the worst case). Updates near 1 MB
     * fail outright. Worst case here: a 560 dpi screen with both tiers sent;
     * the Airblock card's own updates run about as heavy.
     */
    @Test @Config(qualifiers = "w411dp-h891dp-560dpi")
    fun updatesStaySmall() {
        val photo = System.getProperty("strip.sample.photo")
        val s = WidgetState(status = WidgetState.Status.OK, callsign = "BAW8KC",
            typeName = "Airbus A320neo", hex = "406b2a", altitudeFt = 8000, speedMph = 297,
            distanceKm = 15.2, bearingDeg = 312.0, elevationDeg = 9.1, verticalRateFpm = 1800,
            originIata = "LHR", destIata = "GLA", destCity = "Glasgow", routeProgress = 0.4f,
            flightNumber = "BA8", airlineName = "British Airways", photoPath = photo,
            modelLogoPath = System.getProperty("strip.sample.model"),
            airlineLogoPath = System.getProperty("strip.sample.airline"),
            etaEpochMs = now + 30 * 60_000, category = "A3", updatedAt = now,
            staleAfterMs = now + 120_000)
        for (heights in listOf(listOf(64f), listOf(100f), listOf(64f, 120f))) {
            val p = Parcel.obtain()
            FlightStrip.preview(ctx, s, 313f, heights, now).writeToParcel(p, 0)
            println("strip update, heights $heights: ${p.dataSize() / 1024} KB")
            org.junit.Assert.assertTrue("update is ${p.dataSize() / 1024} KB", p.dataSize() < 600 * 1024)
            p.recycle()
        }
    }

    @Test fun appliesOnEverySupportedRelease() {
        val ok = WidgetState(status = WidgetState.Status.OK, callsign = "BAW8KC",
            typeName = "Airbus A320neo", hex = "406b2a", altitudeFt = 8000, speedMph = 297,
            distanceKm = 15.2, bearingDeg = 312.0, elevationDeg = 9.1, verticalRateFpm = 1800,
            originIata = "LHR", destIata = "GLA", destCity = "Glasgow", routeProgress = 0.4f,
            etaEpochMs = now + 30 * 60_000, category = "A3", updatedAt = now,
            staleAfterMs = now + 120_000)
        val states = listOf(
            ok,
            ok.copy(squawkAlert = "7700 EMERGENCY", errorCount = 2),
            ok.copy(specialType = "Military", refreshing = true, markedStale = true),
            ok.copy(pausedReason = WidgetState.PAUSE_BATTERY),
            WidgetState(status = WidgetState.Status.NO_AIRCRAFT, updatedAt = now),
        )
        for (s in states) for (h in listOf(64f, 100f)) {
            val rv = FlightStrip.preview(ctx, s, 313f, listOf(h), now)
            // apply() picks the smallest tier; the full one goes through the host in StripRenderTest
            val v = rv.apply(ctx, FrameLayout(ctx))
            assertNotNull(v.findViewById<View>(R.id.strip_photo))
            assertNotNull(v.findViewById<View>(R.id.strip_card))
            assertNotNull(v.findViewById<ViewFlipper>(R.id.strip_stats))
        }
        // The full tier on its own, so its flipper calls are applied too
        val full = FlightStrip.preview(ctx, ok, 313f, listOf(100f), now)
        val sized = full.javaClass.getDeclaredMethod("getRemoteViewsToApply", Context::class.java,
            android.util.SizeF::class.java).apply { isAccessible = true }
            .invoke(full, ctx, android.util.SizeF(313f, 100f)) as android.widget.RemoteViews
        val v = sized.apply(ctx, FrameLayout(ctx))
        assertEquals(View.VISIBLE, v.findViewById<ViewFlipper>(R.id.strip_stats).visibility)
    }
}
