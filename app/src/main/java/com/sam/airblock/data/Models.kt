package com.sam.airblock.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

// ---------- adsb.lol /v2/closest ----------

@Serializable
data class ClosestResponse(
    val ac: List<Aircraft> = emptyList(),
    val msg: String? = null,
    val now: Long? = null,
)

@Serializable
data class Aircraft(
    val hex: String,
    /** Callsign, padded with trailing spaces by the API. */
    val flight: String? = null,
    /** Registration, e.g. "N832DN". */
    val r: String? = null,
    /** ICAO type code, e.g. "B739". */
    val t: String? = null,
    /** Human type name, e.g. "BOEING 737-900". Not always present. */
    val desc: String? = null,
    /** Barometric altitude in ft — the string "ground" when on the ground. */
    @SerialName("alt_baro") val altBaro: JsonPrimitive? = null,
    /** Ground speed in knots. */
    val gs: Double? = null,
    /** Mach number, e.g. 0.82. */
    val mach: Double? = null,
    /** Emitter category, e.g. "A3" (large), "A7" (rotorcraft), "B6" (drone). */
    val category: String? = null,
    /** adsb.lol DB flags bitmask: 1=military, 2=interesting, 4=PIA, 8=LADD. */
    val dbFlags: Long? = null,
    val squawk: String? = null,
    /** Distance from query point in nautical miles (closest endpoint only). */
    val dst: Double? = null,
    val lat: Double? = null,
    val lon: Double? = null,
) {
    val callsign: String? get() = flight?.trim()?.takeIf { it.isNotEmpty() }
    val altitudeFt: Int? get() = altBaro?.intOrNull // null when "ground" or absent
    val onGround: Boolean get() = altBaro?.content == "ground"
}

// ---------- adsb.lol /api/0/route/{callsign} ----------
// (GET, 302-redirects to a static JSON file on vrs-standing-data.adsb.lol)

@Serializable
data class RouteResult(
    val callsign: String? = null,
    @SerialName("airport_codes") val airportCodes: String? = null,
    @SerialName("_airports") val airports: List<RouteAirport> = emptyList(),
)

@Serializable
data class RouteAirport(
    val iata: String? = null,
    val icao: String? = null,
    val name: String? = null,
    /** City, e.g. "New York". */
    val location: String? = null,
    @SerialName("countryiso2") val countryIso2: String? = null,
    val lat: Double? = null,
    val lon: Double? = null,
)

// ---------- planespotters.net /pub/photos/hex/{hex} ----------

@Serializable
data class PhotoResponse(val photos: List<Photo> = emptyList())

@Serializable
data class Photo(
    val id: String? = null,
    @SerialName("thumbnail_large") val thumbnailLarge: PhotoVariant? = null,
    val thumbnail: PhotoVariant? = null,
    val link: String? = null,
    val photographer: String? = null,
)

@Serializable
data class PhotoVariant(val src: String)
