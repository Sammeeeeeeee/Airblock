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

    @Test fun parsesRouteset() {
        val json = """
        [{
          "callsign": "DAL1234",
          "number": "DL1234",
          "airline_code": "DAL",
          "airport_codes": "JFK-LAX",
          "_airport_codes_iata": "JFK-LAX",
          "plausible": true,
          "_airports": [
            {"alt":13,"countryiso2":"US","iata":"JFK","icao":"KJFK",
             "lat":40.639,"location":"New York","lon":-73.778,
             "name":"John F Kennedy International Airport"},
            {"alt":125,"countryiso2":"US","iata":"LAX","icao":"KLAX",
             "lat":33.942,"location":"Los Angeles","lon":-118.408,
             "name":"Los Angeles International Airport"}
          ]
        }]
        """.trimIndent()
        val route = Http.json.decodeFromString<List<RouteResult>>(json).single()
        assertEquals("JFK", route.airports.first().iata)
        assertEquals("LAX", route.airports.last().iata)
        assertEquals("US", route.airports.first().countryIso2)
        assertEquals("New York", route.airports.first().location)
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
