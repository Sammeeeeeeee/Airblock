package com.sam.airblock

import com.sam.airblock.data.WidgetState
import com.sam.airblock.widget.StatPage
import com.sam.airblock.widget.StripContent
import com.sam.airblock.widget.StripContents
import com.sam.airblock.widget.StripPalette
import com.sam.airblock.widget.StripStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Flight strip's wording, logos, stats pages and colours. */
class StripFacesTest {

    private val now = 1_762_425_540_000L

    private val ba = WidgetState(
        status = WidgetState.Status.OK, callsign = "BAW8KC", typeName = "Airbus A320neo",
        typeCode = "A20N", registration = "G-TTNV", hex = "406b2a", altitudeFt = 8000,
        speedMph = 297, mach = 0.38, distanceKm = 15.2, bearingDeg = 312.0, elevationDeg = 34.4,
        verticalRateFpm = 1800, routeProgress = 0.2f, etaEpochMs = now + 34 * 60_000 + 5_000,
        flightNumber = "BA8", category = "A3", originIata = "LHR", destIata = "GLA",
        destCity = "Glasgow", airlineName = "British Airways",
        modelLogoPath = "/cache/manufacturers/model_logo_airbus_a320neo.png",
        airlineLogoPath = "/cache/airlines/BA.png", photoPath = "/cache/photos/406b2a.jpg",
        updatedAt = now, staleAfterMs = now + 120_000,
    )

    private fun text(runs: List<StripContent.Run>) = runs.joinToString("") {
        when (it) { is StripContent.Text -> it.text; is StripContent.Route -> "~" }
    }

    private fun words(p: StatPage) = p.parts.joinToString(" ") {
        when (it) {
            is StatPage.Value -> it.text
            is StatPage.Unit -> it.text
            StatPage.Gap -> "|"
            is StatPage.Arrow -> if (it.climb) "↗" else "↘"
            is StatPage.Compass -> "(${it.bearingDeg.toInt()}°)"
        }
    }

    @Test fun `callsign with the model and airline logos, route underneath`() {
        val c = StripContents.of(ba, now)
        assertEquals("BAW8KC", c.title)
        val mark = c.mark as StripContent.Mark.Logo
        assertTrue("Airbus logos lose the AIRBUS wordmark", mark.dropMaker)
        assertEquals("/cache/airlines/BA.png", c.airlineLogo)
        assertEquals("LHR~GLA · 34 min left", text(c.line2.first()))
        assertEquals("LHR~GLA", text(c.line2.last()))
    }

    @Test fun `the photo is passed through whole`() {
        assertEquals("/cache/photos/406b2a.jpg", StripContents.of(ba, now).photoPath)
    }

    @Test fun `only maker-led logos lose their first word`() {
        assertTrue(StripContents.dropsMaker("/c/model_logo_airbus_a350.png"))
        assertTrue(StripContents.dropsMaker("/c/model_b717.png"))
        assertTrue(StripContents.dropsMaker("/c/model_boeing_777_logo.png"))
        assertFalse(StripContents.dropsMaker("/c/model_737_max_logo.png"))
        assertFalse(StripContents.dropsMaker("/c/model_boeing_787_dreamliner_logo.png"))
    }

    @Test fun `no model logo falls back to the model name`() {
        val mark = StripContents.of(ba.copy(modelLogoPath = null), now).mark
        assertEquals(StripContent.Mark.Name("A320neo"), mark)
    }

    @Test fun `without a route the second line says whose aircraft it is`() {
        val c = StripContents.of(ba.copy(originIata = null, destIata = null), now)
        assertEquals("British Airways · G-TTNV", text(c.line2.first()))
        val raf = StripContents.of(ba.copy(originIata = null, destIata = null,
            alertCategory = "Royal Air Force"), now)
        assertEquals("Royal Air Force · G-TTNV", text(raf.line2.first()))
    }

    @Test fun `stats pages in swipe order`() {
        val pages = StripContents.pages(ba, now)
        assertEquals(listOf(StatPage.Kind.LIVE, StatPage.Kind.LOOK, StatPage.Kind.AIRFRAME),
            pages.map { it.kind })
        assertEquals("↗ 8,000 ft | 297 mph | 15 km", words(pages[0]))
        assertEquals("(312°) NW | 34° up", words(pages[1]))
        assertEquals("G-TTNV | A20N | Mach 0.38", words(pages[2]))
    }

    @Test fun `an emergency squawk leads the stats`() {
        val pages = StripContents.pages(ba.copy(squawkAlert = "7700 EMERGENCY"), now)
        assertEquals(StatPage.Kind.ALERT, pages.first().kind)
        assertEquals("7700 | Emergency", words(pages.first()))
    }

    @Test fun `real flight times add a trip page`() {
        val late = ba.copy(timesAreReal = true, schedArrLocal = "11:30", actualArrLocal = "11:44",
            arrDelayMin = 14, schedArrEpochMs = now + 20 * 60_000)
        val trip = StripContents.pages(late, now).first { it.kind == StatPage.Kind.TRIP }
        assertEquals("Lands 11:44 | 14 min late", words(trip))
        // Time left follows the real arrival estimate, not the geometry guess
        assertEquals("34 min left", StripContents.timeLeft(late, now))
    }

    @Test fun `climbing and descending get an arrow, level flight none`() {
        fun arrow(fpm: Int?) = (StripContents.pages(ba.copy(verticalRateFpm = fpm), now)
            .first().parts.first() as? StatPage.Arrow)?.climb
        assertEquals(true, arrow(1800))
        assertEquals(false, arrow(-900))
        assertNull(arrow(120))
    }

    @Test fun `straight overhead and on the ground`() {
        val look = StripContents.pages(ba.copy(elevationDeg = 85.0), now)
            .first { it.kind == StatPage.Kind.LOOK }
        assertEquals("Look straight up", words(look))
        val ground = StripContents.pages(ba.copy(onGround = true), now).first()
        assertEquals("On the ground | 297 mph | 15 km", words(ground))
    }

    @Test fun `empty states say what is going on in plain words`() {
        val quiet = StripContents.of(WidgetState(status = WidgetState.Status.NO_AIRCRAFT), now)
        assertEquals("Clear skies", quiet.title)
        assertEquals("No aircraft nearby", text(quiet.line2.single()))
        assertTrue(quiet.pages.isEmpty())
        val lost = StripContents.of(WidgetState(status = WidgetState.Status.NO_LOCATION), now)
        assertEquals("Tap to try again", text(lost.line2.single()))
    }

    @Test fun `time left reads naturally`() {
        assertEquals("landing", StripContents.minutesLeft(0))
        assertEquals("34 min left", StripContents.minutesLeft(34))
        assertEquals("1 h left", StripContents.minutesLeft(60))
        assertEquals("1 h 5 min left", StripContents.minutesLeft(65))
    }

    @Test fun `special aircraft change the card colours`() {
        assertEquals(StripPalette.NORMAL, StripContents.palette(ba))
        assertEquals(StripPalette.ERROR, StripContents.palette(ba.copy(specialType = "Military")))
        assertEquals(StripPalette.ERROR, StripContents.palette(ba.copy(squawkAlert = "7700 EMERGENCY")))
        assertEquals(StripPalette.TERTIARY, StripContents.palette(ba.copy(alertCategory = "Police Forces")))
    }

    @Test fun `routine refreshes stay quiet`() {
        assertEquals(StripStatus.NONE, StripContents.status(ba.copy(refreshing = true), now))
        assertEquals(StripStatus.SPINNER,
            StripContents.status(ba.copy(refreshing = true, markedStale = true), now))
        assertEquals(StripStatus.ERROR, StripContents.status(ba.copy(errorCount = 1), now))
        assertEquals(StripStatus.PAUSED_BATTERY,
            StripContents.status(ba.copy(pausedReason = WidgetState.PAUSE_BATTERY), now))
        assertEquals(StripStatus.STALE, StripContents.status(ba, now + 10 * 60_000))
    }
}
