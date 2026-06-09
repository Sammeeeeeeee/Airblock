package com.sam.airblock

import com.sam.airblock.data.ClosestResponse
import com.sam.airblock.data.Http
import com.sam.airblock.data.PhotoResponse
import com.sam.airblock.data.RouteResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parsing tests against representative API payloads. Shapes verified against
 * live responses captured in /samples — keep these in sync if the APIs change.
 */
class ParsingTest {

    @Test fun parsesClosestAircraft() {
        val json = """
        {
          "ac": [{
            "hex": "a8c759",
            "type": "adsb_icao",
            "flight": "DAL1234 ",
            "r": "N832DN",
            "t": "B739",
            "desc": "BOEING 737-900",
            "alt_baro": 34000,
            "gs": 463.0,
            "track": 270.1,
            "squawk": "1200",
            "lat": 40.65,
            "lon": -73.9,
            "dst": 1.3,
            "unknown_future_field": {"nested": true}
          }],
          "msg": "No error",
          "now": 1759999999999,
          "total": 1
        }
        """.trimIndent()
        val ac = Http.json.decodeFromString<ClosestResponse>(json).ac.single()
        assertEquals("a8c759", ac.hex)
        assertEquals("DAL1234", ac.callsign) // trailing space trimmed
        assertEquals("B739", ac.t)
        assertEquals(34000, ac.altitudeFt)
        assertFalse(ac.onGround)
        assertEquals(463.0, ac.gs!!, 1e-9)
        assertEquals(1.3, ac.dst!!, 1e-9)
    }

    @Test fun parsesGroundAltitude() {
        val json = """{"ac":[{"hex":"abc123","alt_baro":"ground","gs":12.5}]}"""
        val ac = Http.json.decodeFromString<ClosestResponse>(json).ac.single()
        assertNull(ac.altitudeFt)
        assertTrue(ac.onGround)
    }

    @Test fun parsesEmptyResponse() {
        val json = """{"ac":[],"msg":"No error","total":0}"""
        assertTrue(Http.json.decodeFromString<ClosestResponse>(json).ac.isEmpty())
    }

    @Test fun parsesRoute() {
        // Captured live from api.adsb.lol/api/0/route/DAL420 (2026-06-09)
        val json = """
        {
          "callsign": "DAL420",
          "number": "420",
          "airline_code": "DAL",
          "airport_codes": "KLAX-KDFW",
          "_airport_codes_iata": "LAX-DFW",
          "_airports": [
            {"name":"Los Angeles International Airport","icao":"KLAX","iata":"LAX",
             "location":"Los Angeles","countryiso2":"US",
             "lat":33.942501,"lon":-118.407997,"alt_feet":125.0,"alt_meters":38.1},
            {"name":"Dallas Fort Worth International Airport","icao":"KDFW","iata":"DFW",
             "location":"Dallas-Fort Worth","countryiso2":"US",
             "lat":32.896801,"lon":-97.038002,"alt_feet":607.0,"alt_meters":185.01}
          ]
        }
        """.trimIndent()
        val route = Http.json.decodeFromString<RouteResult>(json)
        assertEquals("LAX", route.airports.first().iata)
        assertEquals("DFW", route.airports.last().iata)
        assertEquals("US", route.airports.first().countryIso2)
        assertEquals("Los Angeles", route.airports.first().location)
    }

    @Test fun parsesPlanespotters() {
        val json = """
        {
          "photos": [{
            "id": "1455282",
            "thumbnail": {"src": "https://t.plnspttrs.net/abc_280.jpg",
                          "size": {"width": 200, "height": 133}},
            "thumbnail_large": {"src": "https://t.plnspttrs.net/abc_400.jpg",
                                "size": {"width": 400, "height": 267}},
            "link": "https://www.planespotters.net/photo/1455282",
            "photographer": "Jane Doe"
          }]
        }
        """.trimIndent()
        val photo = Http.json.decodeFromString<PhotoResponse>(json).photos.single()
        assertEquals("https://t.plnspttrs.net/abc_400.jpg", photo.thumbnailLarge?.src)
        assertEquals("Jane Doe", photo.photographer)
    }

    @Test fun parsesPlanespottersNoPhoto() {
        val json = """{"photos": []}"""
        assertTrue(Http.json.decodeFromString<PhotoResponse>(json).photos.isEmpty())
    }
}
