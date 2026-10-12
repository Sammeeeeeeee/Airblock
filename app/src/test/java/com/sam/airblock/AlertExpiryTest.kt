package com.sam.airblock

import com.sam.airblock.data.NotifyPrefs
import com.sam.airblock.util.AlertExpiry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Alerts clear a set time after their aircraft was LAST seen. */
class AlertExpiryTest {

    private val min = 60_000L
    private val t0 = 1_700_000_000_000L

    /**
     * Play out one pass: the aircraft is seen on [ticksSeen] ticks in a row,
     * [refreshMs] apart, then never again. Returns the last sighting and when
     * Android would clear the alert.
     */
    private fun pass(choice: Int, refreshMs: Long, ticksSeen: Int): Pair<Long, Long> {
        val timeout = AlertExpiry.timeoutMs(choice, refreshMs)
        var postedAt = t0 // the alert goes up on the first sighting
        var lastSeen = t0
        for (i in 1 until ticksSeen) {
            val seen = t0 + i * refreshMs
            // It must still be up each time the aircraft is seen again
            assertTrue("cleared while still overhead", seen < postedAt + timeout)
            if (AlertExpiry.rearmDue(postedAt, seen)) postedAt = seen
            lastSeen = seen
        }
        return lastSeen to postedAt + timeout
    }

    @Test
    fun `the default is five minutes`() {
        assertEquals(5, NotifyPrefs().clearAfterMin)
    }

    @Test
    fun `never means no timeout at all`() {
        assertEquals(0L, AlertExpiry.timeoutMs(AlertExpiry.NEVER, 15_000L))
    }

    @Test
    fun `the chosen time plus the re-post slack`() {
        assertEquals(5 * min + AlertExpiry.REARM_MS, AlertExpiry.timeoutMs(5, 15_000L))
        assertEquals(2 * min + AlertExpiry.REARM_MS, AlertExpiry.timeoutMs(2, 60_000L))
    }

    @Test
    fun `re-posts at most once a minute`() {
        assertFalse(AlertExpiry.rearmDue(t0, t0 + AlertExpiry.REARM_MS - 1))
        assertTrue(AlertExpiry.rearmDue(t0, t0 + AlertExpiry.REARM_MS))
    }

    @Test
    fun `clears on time after the last sighting, whatever the tick rate`() {
        for (refreshMs in listOf(15_000L, 30_000L, 60_000L)) {
            for (choice in listOf(2, 5, 15)) {
                // Overhead for one tick, or circling for half an hour
                for (ticksSeen in 1..(30 * min / refreshMs).toInt()) {
                    val (lastSeen, clearsAt) = pass(choice, refreshMs, ticksSeen)
                    val promised = lastSeen + choice * min
                    // Never sooner than promised, and at most a minute late
                    assertTrue("early: $refreshMs/$choice/$ticksSeen", clearsAt >= promised)
                    assertTrue("late: $refreshMs/$choice/$ticksSeen",
                        clearsAt <= promised + AlertExpiry.REARM_MS)
                }
            }
        }
    }

    @Test
    fun `the slow plan waits for the next look`() {
        // 10-min data-saving plan: a 5-min timer would clear the alert of a
        // helicopter still circling overhead before the app looked again
        val refreshMs = 10 * min
        assertEquals(refreshMs + AlertExpiry.REARM_MS, AlertExpiry.timeoutMs(5, refreshMs))
        for (ticksSeen in 1..6) {
            val (lastSeen, clearsAt) = pass(5, refreshMs, ticksSeen)
            // Up until the first tick that misses the aircraft, then soon gone
            assertTrue(clearsAt > lastSeen + refreshMs)
            assertTrue(clearsAt <= lastSeen + refreshMs + AlertExpiry.REARM_MS)
        }
        // A longer choice still wins over the refresh gap
        assertEquals(15 * min + AlertExpiry.REARM_MS, AlertExpiry.timeoutMs(15, refreshMs))
    }
}
