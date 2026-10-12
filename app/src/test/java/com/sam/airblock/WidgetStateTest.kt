package com.sam.airblock

import com.sam.airblock.data.WidgetState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the widget's status badge reads from the stored state. */
class WidgetStateTest {

    private val t0 = 1_700_000_000_000L

    @Test
    fun `never-updated data is not stale`() {
        assertFalse(WidgetState().isStaleAt(t0))
    }

    @Test
    fun `stale after its schedule-aware deadline`() {
        // 10-minute plan: fresh for 20 minutes, not the default 2
        val slow = WidgetState(updatedAt = t0, staleAfterMs = t0 + 20 * 60_000)
        assertFalse(slow.isStaleAt(t0 + 9 * 60_000))
        assertTrue(slow.isStaleAt(t0 + 21 * 60_000))
    }

    @Test
    fun `no deadline stored falls back to two minutes`() {
        val s = WidgetState(updatedAt = t0)
        assertFalse(s.isStaleAt(t0 + 119_000))
        assertTrue(s.isStaleAt(t0 + 121_000))
    }

    @Test
    fun `the stale flag reaches the redraw check`() {
        // The keep-alive worker sets it; the widget only redraws when the
        // render key changes, so the flag must be part of it
        val s = WidgetState(updatedAt = t0)
        assertTrue(s.renderKey() != s.copy(markedStale = true).renderKey())
        assertTrue(s.renderKey() != s.copy(routePending = true).renderKey())
    }

    @Test
    fun `only battery saver counts as a battery pause`() {
        assertTrue(WidgetState(pausedReason = WidgetState.PAUSE_BATTERY).pausedForBattery)
        assertFalse(WidgetState(pausedReason = "off (mobile data)").pausedForBattery)
        assertFalse(WidgetState().pausedForBattery)
    }
}
