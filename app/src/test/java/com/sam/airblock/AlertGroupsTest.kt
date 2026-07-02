package com.sam.airblock

import com.sam.airblock.util.AlertGroup
import com.sam.airblock.util.AlertGroups
import com.sam.airblock.util.Units
import com.sam.airblock.util.WatchEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertGroupsTest {

    private val allOn = AlertGroup.entries.map { it.id }.toSet()
    private val defaults = AlertGroups.defaultGroupIds

    // ---------------------------------------------------- category mapping

    @Test fun exactSeeds() {
        assertEquals(AlertGroup.GOV, AlertGroups.groupForCategory("Dictator Alert"))
        assertEquals(AlertGroup.GOV, AlertGroups.groupForCategory("Radiohead"))
        assertEquals(AlertGroup.MILITARY, AlertGroups.groupForCategory("RAF"))
        assertEquals(AlertGroup.MILITARY, AlertGroups.groupForCategory("ukraine")) // case-insensitive
        assertEquals(AlertGroup.MILITARY, AlertGroups.groupForCategory("Da Comrade"))
        assertEquals(AlertGroup.MILITARY, AlertGroups.groupForCategory("Zoomies"))
        assertEquals(AlertGroup.MILITARY, AlertGroups.groupForCategory("Oxcart"))
        assertEquals(AlertGroup.SERVICES, AlertGroups.groupForCategory("Flying Doctors"))
        assertEquals(AlertGroup.DRONES, AlertGroups.groupForCategory("UAV"))
        assertEquals(AlertGroup.HISTORIC, AlertGroups.groupForCategory("Vanity Plate"))
    }

    @Test fun displayNameTranslatesInJokes() {
        assertEquals("Russian & Soviet aircraft", AlertGroups.displayName("Da Comrade"))
        assertEquals("Russian & Soviet aircraft", AlertGroups.displayName("da comrade"))
        assertEquals("Fast jets", AlertGroups.displayName("Zoomies"))
        assertEquals("Companies & brands", AlertGroups.displayName("As Seen on TV"))
        // Already-descriptive names pass through untouched
        assertEquals("Aerial Firefighter", AlertGroups.displayName("Aerial Firefighter"))
        assertEquals("Coastguard", AlertGroups.displayName("Coastguard"))
        assertEquals("Some Future Category", AlertGroups.displayName("Some Future Category"))
    }

    @Test fun seedBeatsKeyword() {
        // "Military Police" keyword-matches SERVICES ("police") but the exact
        // seed pins it to MILITARY
        assertEquals(AlertGroup.MILITARY, AlertGroups.groupForCategory("Military Police"))
    }

    @Test fun keywordFallback() {
        assertEquals(AlertGroup.GOV, AlertGroups.groupForCategory("Royal Flights"))
        assertEquals(AlertGroup.MILITARY, AlertGroups.groupForCategory("French Air Force"))
        assertEquals(AlertGroup.SERVICES, AlertGroups.groupForCategory("Air Ambulance"))
        assertEquals(AlertGroup.HISTORIC, AlertGroups.groupForCategory("Warbird Heritage"))
    }

    @Test fun unknownCategoryFallsToOther() {
        assertEquals(AlertGroup.OTHER, AlertGroups.groupForCategory("Celebrity Jets"))
        assertEquals(AlertGroup.OTHER, AlertGroups.groupForCategory(""))
    }

    // ------------------------------------------------------------ matching

    @Test fun emergencySquawk() {
        val m = AlertGroups.match(null, "7700", null, null, allOn)
        assertEquals(listOf(AlertGroup.EMERGENCY), m)
        // normal squawk — nothing
        assertTrue(AlertGroups.match(null, "1200", null, null, allOn).isEmpty())
    }

    @Test fun militaryDbFlagWithoutCsv() {
        assertEquals(listOf(AlertGroup.MILITARY),
            AlertGroups.match(1L, null, null, null, allOn))
        // "interesting" flag
        assertEquals(listOf(AlertGroup.HISTORIC),
            AlertGroups.match(2L, null, null, null, allOn))
    }

    @Test fun emitterCategories() {
        assertEquals(listOf(AlertGroup.DRONES),
            AlertGroups.match(null, null, "B6", null, allOn))
        assertEquals(listOf(AlertGroup.OTHER),
            AlertGroups.match(null, null, "B7", null, allOn))
        // OTHER is default-off, so a B7 with default groups matches nothing
        assertTrue(AlertGroups.match(null, null, "B7", null, defaults).isEmpty())
    }

    @Test fun disabledGroupDoesNotMatch() {
        val noMil = allOn - AlertGroup.MILITARY.id
        assertTrue(AlertGroups.match(1L, null, null, "RAF", noMil).isEmpty())
    }

    @Test fun priorityOrdering() {
        // dbFlags military + Governments CSV hit + 7700 → emergency first,
        // then gov, then military
        val m = AlertGroups.match(1L, "7700", null, "Governments", allOn)
        assertEquals(listOf(AlertGroup.EMERGENCY, AlertGroup.GOV, AlertGroup.MILITARY), m)
    }

    @Test fun excludeOverridesGroupToggle() {
        // Category excluded → CSV rule suppressed even though MILITARY is on…
        assertTrue(AlertGroups.match(null, null, null, "RAF", allOn,
            excludeCategories = setOf("raf")).isEmpty())
        // …but the dbFlags rule is unaffected by the category picker
        assertEquals(listOf(AlertGroup.MILITARY),
            AlertGroups.match(1L, null, null, "RAF", allOn,
                excludeCategories = setOf("RAF")))
    }

    @Test fun includeOverridesDisabledGroup() {
        val none = emptySet<String>()
        assertEquals(listOf(AlertGroup.HISTORIC),
            AlertGroups.match(null, null, null, "Historic", none,
                includeCategories = setOf("historic")))
    }

    @Test fun defaultsExcludeOther() {
        assertTrue(AlertGroup.OTHER.id !in defaults)
        assertTrue(AlertGroup.MILITARY.id in defaults)
        assertTrue(AlertGroups.match(null, null, null, "Celebrity Jets", defaults).isEmpty())
    }

    // ----------------------------------------------------------- watchlist

    @Test fun watchMatchesAnySingleField() {
        val byCallsign = WatchEntry(callsign = "baw117")
        assertTrue(byCallsign.matches("BAW117", null, "406A9C"))
        assertTrue(!byCallsign.matches("BAW118", null, "406A9C"))

        val byReg = WatchEntry(registration = "G-XLEA")
        assertTrue(byReg.matches(null, "GXLEA", null)) // hyphen-insensitive
        assertTrue(byReg.matches(null, "g-xlea", null))
        assertTrue(!byReg.matches(null, "G-XLEB", null))

        val byHex = WatchEntry(hex = "406a9c")
        assertTrue(byHex.matches(null, null, "406A9C"))
    }

    @Test fun watchWithSeveralFieldsNeedsAllToMatch() {
        val e = WatchEntry(callsign = "BAW117", registration = "G-XLEA")
        assertTrue(e.matches("BAW117", "G-XLEA", null))
        assertTrue(!e.matches("BAW117", "G-XLEB", null)) // reg mismatch
        assertTrue(!WatchEntry().matches("BAW117", "G-XLEA", "406A9C")) // empty entry
    }

    @Test fun watchGroupPriority() {
        // Watchlist outranks everything except an emergency squawk
        assertEquals(listOf(AlertGroup.WATCHLIST, AlertGroup.MILITARY),
            AlertGroups.match(1L, null, null, null, allOn, watch = true))
        assertEquals(listOf(AlertGroup.EMERGENCY, AlertGroup.WATCHLIST),
            AlertGroups.match(null, "7700", null, null, allOn, watch = true))
        // Group toggle off → watch matches nothing
        assertTrue(AlertGroups.match(null, null, null, null,
            allOn - AlertGroup.WATCHLIST.id, watch = true).isEmpty())
    }

    // ------------------------------------------------------------- bearing

    @Test fun bearing() {
        assertEquals(0.0, Units.bearingDeg(51.0, 0.0, 52.0, 0.0), 0.5)   // due north
        assertEquals(90.0, Units.bearingDeg(0.0, 0.0, 0.0, 1.0), 0.5)    // due east
        assertEquals(180.0, Units.bearingDeg(52.0, 0.0, 51.0, 0.0), 0.5) // due south
        assertEquals(270.0, Units.bearingDeg(0.0, 1.0, 0.0, 0.0), 0.5)   // due west
    }

    @Test fun compass8() {
        assertEquals("N", Units.compass8(0.0))
        assertEquals("N", Units.compass8(359.0))
        assertEquals("NE", Units.compass8(45.0))
        assertEquals("E", Units.compass8(100.0))
        assertEquals("SW", Units.compass8(225.0))
        assertEquals("NW", Units.compass8(315.0))
    }
}
