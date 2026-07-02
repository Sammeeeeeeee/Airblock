package com.sam.airblock.util

import kotlin.math.roundToInt

/** Pure conversion/formatting helpers — JVM unit-testable, no Android deps. */
object Units {
    const val KTS_TO_MPH = 1.15078
    const val NM_TO_KM = 1.852

    fun ktsToMph(kts: Double): Int = (kts * KTS_TO_MPH).roundToInt()

    fun nmToKm(nm: Double): Double = nm * NM_TO_KM

    /** "2.4 km" under 10 km, "23 km" above. */
    fun formatKm(km: Double): String =
        if (km < 10) "%.1f km".format(km) else "${km.roundToInt()} km"

    fun formatAltitude(altFt: Int?): String =
        if (altFt == null) "—" else "%,d ft".format(altFt)

    fun formatSpeed(mph: Int): String = "$mph mph"

    /** Great-circle distance in km. */
    fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = kotlin.math.sin(dLat / 2) * kotlin.math.sin(dLat / 2) +
            kotlin.math.cos(Math.toRadians(lat1)) * kotlin.math.cos(Math.toRadians(lat2)) *
            kotlin.math.sin(dLon / 2) * kotlin.math.sin(dLon / 2)
        return 2 * r * kotlin.math.atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1 - a))
    }

    /** Initial great-circle bearing from point 1 to point 2, degrees 0..360. */
    fun bearingDeg(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dLon = Math.toRadians(lon2 - lon1)
        val y = kotlin.math.sin(dLon) * kotlin.math.cos(p2)
        val x = kotlin.math.cos(p1) * kotlin.math.sin(p2) -
            kotlin.math.sin(p1) * kotlin.math.cos(p2) * kotlin.math.cos(dLon)
        return (Math.toDegrees(kotlin.math.atan2(y, x)) + 360.0) % 360.0
    }

    /** 8-wind compass point for a bearing: 45° -> "NE". */
    fun compass8(deg: Double): String {
        val dirs = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
        return dirs[(((deg % 360 + 360) % 360 + 22.5) / 45.0).toInt() % 8]
    }

    /** "US" -> 🇺🇸 via regional indicator symbols. Empty for invalid input. */
    fun flagEmoji(countryIso2: String?): String {
        val cc = countryIso2?.trim()?.uppercase() ?: return ""
        if (cc.length != 2 || !cc.all { it in 'A'..'Z' }) return ""
        val first = Character.toChars(0x1F1E6 + (cc[0] - 'A'))
        val second = Character.toChars(0x1F1E6 + (cc[1] - 'A'))
        return String(first) + String(second)
    }
}

/**
 * Genuinely special aircraft only — military (adsb.lol DB flag), drones and
 * spacecraft. Ordinary civilian traffic like helicopters, gliders and
 * airships must NOT get an alert badge: over a city they are everyday sights.
 */
object SpecialType {
    fun classify(category: String?, dbFlags: Long?): String? {
        if (dbFlags != null && (dbFlags and 1L) != 0L) return "Military"
        return when (category?.trim()?.uppercase()) {
            "B6" -> "Drone"
            "B7" -> "Spacecraft"
            else -> null
        }
    }
}

/** Emergency squawk classification — only these are "not normal" per spec. */
object Squawk {
    private val EMERGENCIES = mapOf(
        "7500" to "HIJACK",
        "7600" to "RADIO FAILURE",
        "7700" to "EMERGENCY",
    )

    /** Returns a label like "7700 EMERGENCY" or null when the squawk is normal. */
    fun emergencyLabel(squawk: String?): String? {
        val code = squawk?.trim() ?: return null
        val label = EMERGENCIES[code] ?: return null
        return "$code $label"
    }
}
