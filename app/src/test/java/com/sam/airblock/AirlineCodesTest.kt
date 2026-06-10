package com.sam.airblock

import com.sam.airblock.util.AirlineCodes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AirlineCodesTest {

    @Test
    fun `extracts icao prefix from airline callsigns`() {
        assertEquals("RYR", AirlineCodes.icaoPrefix("RYR4421"))
        assertEquals("BAW", AirlineCodes.icaoPrefix("BAW123A"))
        assertEquals("EZY", AirlineCodes.icaoPrefix("  ezy45wz "))
    }

    @Test
    fun `rejects registrations and junk`() {
        assertNull(AirlineCodes.icaoPrefix("N832DN"))   // US reg: digit in prefix
        assertNull(AirlineCodes.icaoPrefix("4XABC"))    // Israeli reg
        assertNull(AirlineCodes.icaoPrefix("RYR"))      // too short to be a flight
        assertNull(AirlineCodes.icaoPrefix(""))
        assertNull(AirlineCodes.icaoPrefix(null))
    }

    @Test
    fun `maps known airlines to iata`() {
        assertEquals("FR", AirlineCodes.iataForCallsign("RYR4421"))
        assertEquals("BA", AirlineCodes.iataForCallsign("BAW123"))
        assertEquals("BA", AirlineCodes.iataForCallsign("SHT4M"))   // BA Shuttle → BA brand
        assertEquals("AA", AirlineCodes.iataForCallsign("AAL100"))
        assertEquals("EK", AirlineCodes.iataForCallsign("UAE29K"))
    }

    @Test
    fun `unknown airline is null, not an error`() {
        assertNull(AirlineCodes.iataForCallsign("ZZZ123"))
    }
}
