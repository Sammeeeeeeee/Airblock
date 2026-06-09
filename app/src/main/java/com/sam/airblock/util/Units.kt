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
 * Non-standard aircraft classification (military, police helicopters, drones…)
 * from the ADS-B emitter category + adsb.lol database flags.
 */
object SpecialType {
    fun classify(category: String?, dbFlags: Long?): String? {
        if (dbFlags != null && (dbFlags and 1L) != 0L) return "Military"
        return when (category?.trim()?.uppercase()) {
            "A7" -> "Helicopter"
            "B1" -> "Glider"
            "B2" -> "Airship"
            "B4" -> "Ultralight"
            "B6" -> "Drone"
            "B7" -> "Spacecraft"
            "C1" -> "Emergency vehicle"
            "C2" -> "Service vehicle"
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
