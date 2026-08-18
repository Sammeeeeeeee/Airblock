package com.sam.airblock

import com.sam.airblock.util.AlertLabels
import com.sam.airblock.util.WatchEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one rule every surface follows: the database CATEGORY names the
 * aircraft (it's what the settings switches are labelled with), the TAG is
 * supporting detail.
 */
class AlertLabelsTest {

    @Test
    fun `category leads even when a tag exists`() {
        // The real Shadow R1 row that started this: it read SURVEILLANCE on the
        // widget and "Special Forces" in the notification
        assertEquals(
            "Special Forces",
            AlertLabels.primary("Special Forces", "Surveillance", null),
        )
        assertEquals(
            "Surveillance",
            AlertLabels.secondary("Special Forces", "Surveillance"),
        )
    }

    @Test
    fun `tag stands in when the row has no category`() {
        assertEquals("Surveillance", AlertLabels.primary(null, "Surveillance", null))
        // and then it must not also be shown as the secondary
        assertNull(AlertLabels.secondary("Surveillance", "Surveillance"))
    }

    @Test
    fun `live ADS-B data is the last resort`() {
        assertEquals("Military", AlertLabels.primary(null, null, "Military"))
        assertNull(AlertLabels.primary(null, null, null))
    }

    @Test
    fun `blank fields are skipped, not shown`() {
        assertEquals("Military", AlertLabels.primary("", "  ", "Military"))
        assertNull(AlertLabels.secondary("Military", "   "))
    }

    @Test
    fun `a tag that repeats the category is dropped`() {
        assertNull(AlertLabels.secondary("Military", "military"))
        assertNull(AlertLabels.secondary("Military", "Military "))
    }
}

/** One-off watches: what keeps them alive and what removes them. */
class WatchEntryOnceTest {

    private val t0 = 1_700_000_000_000L
    private val entry = WatchEntry(registration = "G-XLEA", once = true)

    @Test
    fun `a permanent watch never expires`() {
        val permanent = WatchEntry(registration = "G-XLEA", lastSeenMs = t0)
        assertNull(permanent.expiresAtMs())
        assertFalse(permanent.isExpired(t0 + 10L * 365 * 24 * 60 * 60 * 1000))
    }

    @Test
    fun `an unseen one-off watch waits indefinitely`() {
        // Added the night before for tomorrow's flight — it must still be there
        assertNull(entry.expiresAtMs())
        assertFalse(entry.isExpired(t0 + 8 * 60 * 60 * 1000))
    }

    @Test
    fun `the countdown starts at the last sighting`() {
        val seen = entry.copy(lastSeenMs = t0)
        assertEquals(t0 + WatchEntry.ONCE_EXPIRY_MS, seen.expiresAtMs())
        assertFalse(seen.isExpired(t0 + 19 * 60 * 1000))
        assertTrue(seen.isExpired(t0 + 21 * 60 * 1000))
    }

    @Test
    fun `re-appearing resets the clock`() {
        // Dropped out of ADS-B coverage for 15 min, then came back
        val again = entry.copy(lastSeenMs = t0 + 15 * 60 * 1000)
        assertFalse(again.isExpired(t0 + 30 * 60 * 1000))
        assertTrue(again.isExpired(t0 + 36 * 60 * 1000))
    }

    @Test
    fun `identity ignores the note and the seen stamp`() {
        val stored = entry.copy(note = "Mum's flight home", lastSeenMs = t0)
        // Removing from the settings list must still match after a tick has
        // stamped the entry underneath the UI
        assertTrue(stored.sameAircraftAs(WatchEntry(registration = "g-xlea")))
        assertFalse(stored.sameAircraftAs(WatchEntry(registration = "G-XLEB")))
        assertFalse(stored.sameAircraftAs(WatchEntry(hex = "406A9C")))
    }
}
