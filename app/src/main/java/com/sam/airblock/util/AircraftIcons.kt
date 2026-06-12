package com.sam.airblock.util

import com.sam.airblock.R

/**
 * Top-view aircraft silhouettes by ADS-B Radar (adsb-radar.com), bundled as
 * tinted vector drawables. Resolution order: exact ICAO type code → the
 * transponder's emitter category → generic airliner.
 */
object AircraftIcons {

    /** Drawable for an aircraft, never null — falls back to a generic shape. */
    fun iconFor(typeCode: String?, category: String?): Int {
        TYPE_TO_ICON[typeCode?.trim()?.uppercase()]?.let { return it }
        CATEGORY_TO_ICON[category?.trim()?.uppercase()]?.let { return it }
        return R.drawable.ac_a3
    }

    /** ADS-B emitter category → silhouette (per the icon set's own grouping). */
    private val CATEGORY_TO_ICON = mapOf(
        "A0" to R.drawable.ac_a0, "A1" to R.drawable.ac_a1, "A2" to R.drawable.ac_a2,
        "A3" to R.drawable.ac_a3, "A4" to R.drawable.ac_a4, "A5" to R.drawable.ac_a5,
        "A6" to R.drawable.ac_a6, "A7" to R.drawable.ac_a7,
        "B0" to R.drawable.ac_b0, "B5" to R.drawable.ac_b0, "B6" to R.drawable.ac_b0,
        "B7" to R.drawable.ac_b0,
        "B1" to R.drawable.ac_b1, "B2" to R.drawable.ac_b2, "B3" to R.drawable.ac_b3,
        "B4" to R.drawable.ac_b4, "C0" to R.drawable.ac_c0,
    )

    private val TYPE_TO_ICON: Map<String, Int> = buildMap {
        fun all(icon: Int, vararg codes: String) = codes.forEach { put(it, icon) }
        all(R.drawable.ac_a320, "A318", "A319", "A320", "A321",
            "A19N", "A20N", "A21N", "BCS1", "BCS3", "C919")
        all(R.drawable.ac_a330, "A300", "A306", "A310", "A332", "A333", "A338", "A339")
        all(R.drawable.ac_a340, "A342", "A343", "A345", "A346", "A359", "A35K")
        all(R.drawable.ac_a380, "A388")
        all(R.drawable.ac_b737, "B731", "B732", "B733", "B734", "B735", "B736",
            "B737", "B738", "B739", "B37M", "B38M", "B39M", "B3XM", "B721", "B722")
        all(R.drawable.ac_b747, "B741", "B742", "B743", "B744", "B748", "BLCF")
        all(R.drawable.ac_b767, "B752", "B753", "B762", "B763", "B764", "B703")
        all(R.drawable.ac_b777, "B772", "B77L", "B773", "B77W", "B778", "B779")
        all(R.drawable.ac_b787, "B788", "B789", "B78X")
        all(R.drawable.ac_c130, "C130", "C30J", "C27J", "C160", "C17", "A400", "K35R")
        all(R.drawable.ac_cessna, "C150", "C152", "C162", "C172", "C177", "C182",
            "C206", "C208", "C210", "P28A", "P28B", "PA31", "PA34", "PA46", "P46T",
            "SR20", "SR22", "S22T", "DA20", "DA40", "BE33", "BE36", "BE58", "BE76",
            "BE20", "B350", "PC12", "M20P", "M20T", "AA5", "BN2P", "TBM7", "TBM8",
            "TBM9", "B190", "TWEN", "G115", "EVOT")
        all(R.drawable.ac_crjx, "CRJ1", "CRJ2", "CRJ7", "CRJ9", "CRJX")
        all(R.drawable.ac_dh8a, "DH8A", "DH8B", "DH8C", "DH8D",
            "AT43", "AT45", "AT46", "AT72", "AT75", "AT76", "DA42", "DA62",
            "SF34", "SB20")
        all(R.drawable.ac_e195, "E170", "E175", "E75S", "E75L", "E190", "E195",
            "E290", "E295")
        all(R.drawable.ac_erj, "E135", "E145", "E45X")
        all(R.drawable.ac_f100, "F100", "F70", "MD81", "MD82", "MD83", "MD87",
            "MD88", "MD90", "B712", "RJ85", "RJ1H", "B461", "B462", "B463")
        all(R.drawable.ac_fa7x, "FA7X", "FA8X", "FA50", "F900", "F2TH", "FA10", "FA20")
        all(R.drawable.ac_glf5, "GLF3", "GLF4", "GLF5", "GLF6", "GL5T", "GLEX",
            "GL7T", "G280", "GA5C", "GA6C", "CL60", "E50P", "E55P", "E545", "E550",
            "BE40")
        all(R.drawable.ac_learjet, "C25A", "C25B", "C25C", "C25M", "C500", "C510",
            "C525", "C550", "C560", "C56X", "C650", "C680", "C68A", "C700", "C750",
            "LJ35", "LJ45", "LJ60", "LJ75", "CL30", "CL35", "HDJT", "SF50", "PC24",
            "EA50", "H25B")
        all(R.drawable.ac_md11, "MD11", "DC10")
        // Helicopters by type code (category A7 catches the rest)
        all(R.drawable.ac_a7, "R22", "R44", "R66", "B06", "B407", "B412", "B429",
            "B505", "EC20", "EC30", "EC35", "EC45", "EC55", "EC75", "AS50", "AS55",
            "A109", "A119", "A139", "A169", "A189", "S76", "S92", "H160", "HLE")
        // ac_f15's traced path is unsalvageable — the A6 delta silhouette
        // (high-performance category) is the right shape for fast jets anyway
        all(R.drawable.ac_a6, "EUFI", "F15", "F16", "F35", "F18", "HAWK")
    }
}
