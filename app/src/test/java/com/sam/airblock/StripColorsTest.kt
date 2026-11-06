package com.sam.airblock

import com.sam.airblock.widget.StripColors
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StripColorsTest {

    private fun logo(vararg counts: Pair<Int, Int>) = IntArray(48 * 48).also { px ->
        var i = 0
        for ((color, n) in counts) repeat(n) { px[i++] = color }
        while (i < px.size) px[i++] = 0 // transparent
    }

    @Test fun `the airline colour is the logo's main hue, not its white or black`() {
        // A navy logo on white with a little red
        val seed = StripColors.seed(logo(0xFF1B2A6B.toInt() to 900, 0xFFFFFFFF.toInt() to 900,
            0xFFD0202E.toInt() to 120))
        assertNotNull(seed)
        assertTrue("hue ${seed!!.h}", seed.h in 250.0..290.0)
    }

    @Test fun `a logo with no real colour gives no seed`() {
        assertNull(StripColors.seed(logo(0xFFFFFFFF.toInt() to 1200, 0xFF202020.toInt() to 600,
            0xFF808080.toInt() to 400)))
        assertNull(StripColors.seed(IntArray(48 * 48)))
    }

    @Test fun `every livery reads, in light and dark`() {
        for (hue in 0 until 360 step 15) for (chroma in listOf(0.06, 0.2, 0.35)) {
            val s = StripColors.scheme(StripColors.Lch(0.6, chroma, hue.toDouble()))
            fun check(what: String, a: StripColors.Duo, b: StripColors.Duo, min: Double) {
                val light = StripColors.contrast(a.light, b.light)
                val dark = StripColors.contrast(a.dark, b.dark)
                assertTrue("$what light $light at h$hue c$chroma", light >= min)
                assertTrue("$what dark $dark at h$hue c$chroma", dark >= min)
            }
            check("title", s.main, s.container, 7.0)
            check("support", s.variant, s.container, 4.5)
            check("accent", s.accent, s.container, 4.5)
            check("badge", s.onBadge, s.badge, 4.5)
            s.pages.forEach { (kind, p) -> check("page $kind", p.second, p.first, 4.5) }
        }
    }
}
