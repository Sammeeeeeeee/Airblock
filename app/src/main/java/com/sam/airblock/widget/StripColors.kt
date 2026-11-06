package com.sam.airblock.widget

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The strip dresses in the colours of the aircraft overhead: Material's
 * "colour from content" (as media players take album art), seeded from the
 * airline's logo. Tones are set in OkLCH (Björn Ottosson's perceptual space,
 * as used by CSS Color 4), so every livery gives the same contrast: a pale
 * container, dark text, a saturated accent — and the reverse in dark theme.
 *
 * Pure (ARGB ints in and out), so it is unit-testable off-device.
 */
object StripColors {

    /** A colour for each theme. */
    data class Duo(val light: Int, val dark: Int)

    /** Every colour role the strip paints with. */
    data class Scheme(
        val container: Duo, val main: Duo, val variant: Duo, val accent: Duo,
        val badge: Duo, val onBadge: Duo,
        /** Stats pages: container and content, one hue each. */
        val pages: Map<StatPage.Kind, Pair<Duo, Duo>>,
    )

    /** OkLCH: lightness 0..1, chroma ≥ 0, hue in degrees. */
    data class Lch(val l: Double, val c: Double, val h: Double)

    /**
     * The airline's colour: the most prominent saturated hue in its logo,
     * ignoring white, black and greys. Null for a logo with no real colour.
     */
    fun seed(argb: IntArray): Lch? {
        val buckets = 24
        val weight = DoubleArray(buckets)
        val sumA = DoubleArray(buckets)
        val sumB = DoubleArray(buckets)
        val sumL = DoubleArray(buckets)
        for (px in argb) {
            if ((px ushr 24) < 160) continue
            val lab = toOkLab(px)
            val c = hypot(lab[1], lab[2])
            if (c < 0.045 || lab[0] < 0.16 || lab[0] > 0.97) continue
            val h = (Math.toDegrees(atan2(lab[2], lab[1])) + 360.0) % 360.0
            val b = (h / (360.0 / buckets)).toInt().coerceIn(0, buckets - 1)
            weight[b] += c
            sumL[b] += lab[0] * c; sumA[b] += lab[1] * c; sumB[b] += lab[2] * c
        }
        val best = weight.indices.maxByOrNull { weight[it] } ?: return null
        // Too little colour to call it a livery (a mostly white or grey logo)
        if (weight[best] < argb.size * 0.004) return null
        val w = weight[best]
        val a = sumA[best] / w
        val b = sumB[best] / w
        return Lch(sumL[best] / w, hypot(a, b), (Math.toDegrees(atan2(b, a)) + 360.0) % 360.0)
    }

    /**
     * Tones for [seed], after M3's tonal-spot recipe: pale containers, a
     * second hue 60° on for the "where to look" page, a near-neutral page
     * for the airframe. Chroma is capped per role so loud liveries stay calm.
     */
    fun scheme(seed: Lch): Scheme {
        val h = seed.h
        val c = seed.c
        fun duo(lL: Double, lD: Double, cap: Double, hue: Double = h) =
            Duo(toArgb(Lch(lL, min(c, cap), hue)), toArgb(Lch(lD, min(c, cap * 0.85), hue)))
        fun page(hue: Double, cap: Double) =
            duo(0.885, 0.36, cap, hue) to duo(0.24, 0.94, 0.06, hue)
        return Scheme(
            container = duo(0.955, 0.27, 0.032),
            main = duo(0.24, 0.94, 0.05),
            variant = duo(0.45, 0.80, 0.05),
            accent = duo(0.52, 0.80, 0.15),
            badge = duo(0.50, 0.78, 0.14),
            onBadge = Duo(toArgb(Lch(0.985, min(c, 0.02), h)), toArgb(Lch(0.22, min(c, 0.05), h))),
            pages = mapOf(
                StatPage.Kind.LIVE to page(h, 0.075),
                StatPage.Kind.LOOK to page((h + 60) % 360, 0.075),
                StatPage.Kind.TRIP to page((h + 315) % 360, 0.075),
                StatPage.Kind.AIRFRAME to page(h, 0.014),
            ),
        )
    }

    // ---- OkLab / OkLCH ------------------------------------------------------

    fun toOkLab(argb: Int): DoubleArray {
        val r = lin((argb shr 16 and 0xFF) / 255.0)
        val g = lin((argb shr 8 and 0xFF) / 255.0)
        val b = lin((argb and 0xFF) / 255.0)
        val l = cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b)
        val m = cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b)
        val s = cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b)
        return doubleArrayOf(
            0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s,
            1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s,
            0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s,
        )
    }

    fun toLch(argb: Int): Lch {
        val lab = toOkLab(argb)
        return Lch(lab[0], hypot(lab[1], lab[2]),
            (Math.toDegrees(atan2(lab[2], lab[1])) + 360.0) % 360.0)
    }

    /** OkLCH to opaque sRGB, lowering chroma until the colour is displayable. */
    fun toArgb(lch: Lch): Int {
        var lo = 0.0
        var hi = lch.c
        var rgb = rgbOf(lch.l, 0.0, lch.h)!!
        if (rgbOf(lch.l, hi, lch.h)?.also { rgb = it } == null) {
            repeat(20) {
                val mid = (lo + hi) / 2
                val got = rgbOf(lch.l, mid, lch.h)
                if (got != null) { rgb = got; lo = mid } else hi = mid
            }
        }
        val (r, g, b) = rgb
        return (0xFF shl 24) or (enc(r) shl 16) or (enc(g) shl 8) or enc(b)
    }

    /** Linear sRGB for an OkLCH colour, or null outside the sRGB gamut. */
    private fun rgbOf(l: Double, c: Double, hDeg: Double): Triple<Double, Double, Double>? {
        val h = hDeg * PI / 180.0
        val a = c * cos(h)
        val b = c * sin(h)
        val l3 = (l + 0.3963377774 * a + 0.2158037573 * b).pow(3)
        val m3 = (l - 0.1055613458 * a - 0.0638541728 * b).pow(3)
        val s3 = (l - 0.0894841775 * a - 1.2914855480 * b).pow(3)
        val r = 4.0767416621 * l3 - 3.3077115913 * m3 + 0.2309699292 * s3
        val g = -1.2684380046 * l3 + 2.6097574011 * m3 - 0.3413193965 * s3
        val bl = -0.0041960863 * l3 - 0.7034186147 * m3 + 1.7076147010 * s3
        val e = 1e-4
        return if (r in -e..1 + e && g in -e..1 + e && bl in -e..1 + e)
            Triple(r.coerceIn(0.0, 1.0), g.coerceIn(0.0, 1.0), bl.coerceIn(0.0, 1.0)) else null
    }

    private fun lin(v: Double) = if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)

    private fun enc(v: Double): Int {
        val s = if (v <= 0.0031308) v * 12.92 else 1.055 * v.pow(1 / 2.4) - 0.055
        return (s * 255).roundToInt().coerceIn(0, 255)
    }

    /** WCAG contrast ratio of two opaque colours (for tests and sanity checks). */
    fun contrast(a: Int, b: Int): Double {
        fun lum(c: Int): Double {
            val r = lin((c shr 16 and 0xFF) / 255.0)
            val g = lin((c shr 8 and 0xFF) / 255.0)
            val bl = lin((c and 0xFF) / 255.0)
            return 0.2126 * r + 0.7152 * g + 0.0722 * bl
        }
        val x = lum(a)
        val y = lum(b)
        return (max(x, y) + 0.05) / (min(x, y) + 0.05)
    }
}
