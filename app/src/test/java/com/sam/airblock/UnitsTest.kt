package com.sam.airblock

import com.sam.airblock.util.Squawk
import com.sam.airblock.util.TypeNames
import com.sam.airblock.util.Units
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UnitsTest {

    @Test fun ktsToMph() {
        assertEquals(533, Units.ktsToMph(463.0)) // mockup plane: 463 kts ≈ 533 mph
        assertEquals(0, Units.ktsToMph(0.0))
    }

    @Test fun nmToKm() {
        assertEquals(1.852, Units.nmToKm(1.0), 1e-9)
        assertEquals(2.4, Units.nmToKm(1.2959), 1e-3) // mockup: 2.4 km away
    }

    @Test fun formatKm() {
        assertEquals("2.4 km", Units.formatKm(2.4))
        assertEquals("23 km", Units.formatKm(23.2))
        assertEquals("9.9 km", Units.formatKm(9.94))
    }

    @Test fun formatAltitude() {
        assertEquals("34,000 ft", Units.formatAltitude(34000))
        assertEquals("—", Units.formatAltitude(null))
    }

    @Test fun flagEmoji() {
        assertEquals("🇺🇸", Units.flagEmoji("US"))
        assertEquals("🇬🇧", Units.flagEmoji("gb"))
        assertEquals("", Units.flagEmoji(null))
        assertEquals("", Units.flagEmoji("USA"))
        assertEquals("", Units.flagEmoji("1!"))
    }

    @Test fun typeNames() {
        assertEquals("Boeing 737-800", TypeNames.name("B738"))
        assertEquals("Airbus A320neo", TypeNames.name("a20n")) // case-insensitive
        assertEquals("Embraer E175", TypeNames.name("E75L"))
        assertNull(TypeNames.name("ZZZZ"))
        assertNull(TypeNames.name(null))
    }

    @Test fun squawkClassifier() {
        assertEquals("7700 EMERGENCY", Squawk.emergencyLabel("7700"))
        assertEquals("7600 RADIO FAILURE", Squawk.emergencyLabel("7600"))
        assertEquals("7500 HIJACK", Squawk.emergencyLabel("7500"))
        assertNull(Squawk.emergencyLabel("1200")) // VFR — normal
        assertNull(Squawk.emergencyLabel("7000"))
        assertNull(Squawk.emergencyLabel(null))
    }
}
