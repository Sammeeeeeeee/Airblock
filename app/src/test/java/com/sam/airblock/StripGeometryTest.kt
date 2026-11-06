package com.sam.airblock

import com.sam.airblock.data.WidgetState
import com.sam.airblock.util.Sky
import com.sam.airblock.widget.StripTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Sky angles, tier choice and the redraw key. */
class StripGeometryTest {

    @Test fun `elevation above the horizon`() {
        assertEquals(45.0, Sky.elevationDeg(1.0, 3281)!!, 0.1) // 1 km up, 1 km away
        assertEquals(90.0, Sky.elevationDeg(0.0, 2000)!!, 1e-9)
        assertNull(Sky.elevationDeg(null, 2000))
        assertNull(Sky.elevationDeg(3.0, null))
    }

    @Test fun `climb and descent need more than a wobble`() {
        assertEquals(1, Sky.trend(301))
        assertEquals(0, Sky.trend(300))
        assertEquals(-1, Sky.trend(-301))
        assertEquals(0, Sky.trend(null))
    }

    @Test fun `tiers follow the height the launcher reports`() {
        assertEquals(listOf(StripTier.COMPACT), StripTier.forHeights(listOf(60f)))
        assertEquals(listOf(StripTier.COMPACT, StripTier.FULL), StripTier.forHeights(listOf(100f)))
        // Portrait and landscape differ: the smaller decides
        assertEquals(listOf(StripTier.COMPACT), StripTier.forHeights(listOf(70f, 120f)))
        assertEquals(listOf(StripTier.COMPACT, StripTier.FULL), StripTier.forHeights(emptyList()))
    }

    @Test fun `where-to-look words reach the redraw check, metres do not`() {
        val s = WidgetState(bearingDeg = 10.0, elevationDeg = 30.2, verticalRateFpm = 0)
        assertNotEquals(s.renderKey(), s.copy(bearingDeg = 50.0).renderKey()) // N → NE
        assertEquals(s.renderKey(), s.copy(bearingDeg = 12.0).renderKey())
        assertNotEquals(s.renderKey(), s.copy(verticalRateFpm = 1500).renderKey())
        assertEquals(s.renderKey(), s.copy(verticalRateFpm = 150).renderKey())
    }
}
