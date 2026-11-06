package com.sam.airblock.util

import kotlin.math.atan2

/** Where an aircraft sits in your sky. Pure, JVM-testable. */
object Sky {
    /** A climb or descent slower than this (ft/min) counts as level flight. */
    const val LEVEL_FPM = 300

    /**
     * Degrees above the horizon for an aircraft [groundKm] away at [altitudeFt].
     * Flat earth: inside the alert ranges the curvature changes this by well
     * under a degree. Null when either input is unknown.
     */
    fun elevationDeg(groundKm: Double?, altitudeFt: Int?): Double? {
        if (groundKm == null || altitudeFt == null) return null
        val altKm = altitudeFt.coerceAtLeast(0) * 0.0003048
        return Math.toDegrees(atan2(altKm, groundKm.coerceAtLeast(0.0)))
    }

    /** +1 climbing, −1 descending, 0 level or unknown. */
    fun trend(fpm: Int?): Int = when {
        fpm == null -> 0
        fpm > LEVEL_FPM -> 1
        fpm < -LEVEL_FPM -> -1
        else -> 0
    }
}
