package com.sam.airblock

import com.sam.airblock.data.AeroPace
import com.sam.airblock.data.AeroStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * The budget pacer: what's left of the AeroAPI allowance, spread over what's
 * left of the billing month.
 */
class AeroPaceTest {

    private fun utcMs(
        year: Int, month: Int, day: Int, hour: Int = 0, minute: Int = 0,
    ): Long = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
        clear()
        set(year, month - 1, day, hour, minute, 0)
    }.timeInMillis

    private val hour = 60L * 60 * 1000
    private val day = 24 * hour

    // ---- remaining budget ------------------------------------------------

    @Test
    fun `remaining counts down from the hard limit`() {
        assertEquals(AeroStore.HARD_LIMIT, AeroPace.remainingRequests(0, null))
        assertEquals(AeroStore.HARD_LIMIT - 400, AeroPace.remainingRequests(400, null))
    }

    @Test
    fun `reported spend can be stricter than the local count`() {
        // $9 of the $10 gone = 200 requests left, even though we've only
        // counted 10 locally (someone else is using the same key)
        assertEquals(200, AeroPace.remainingRequests(10, 9.0))
    }

    @Test
    fun `local count can be stricter than reported spend`() {
        // FlightAware's usage figure lags — our own tally wins while it does
        assertEquals(AeroStore.HARD_LIMIT - 1800, AeroPace.remainingRequests(1800, 0.0))
    }

    @Test
    fun `nothing left is clamped at zero, never negative`() {
        assertEquals(0, AeroPace.remainingRequests(AeroStore.HARD_LIMIT + 5, null))
        assertEquals(0, AeroPace.remainingRequests(0, AeroStore.BUDGET_USD + 1))
    }

    // ---- month window ----------------------------------------------------

    @Test
    fun `month end is midnight on the first of next month`() {
        assertEquals(utcMs(2026, 9, 1), AeroPace.monthEndMs(utcMs(2026, 8, 17, 13, 42)))
        // December rolls the year over
        assertEquals(utcMs(2027, 1, 1), AeroPace.monthEndMs(utcMs(2026, 12, 31, 23, 59)))
    }

    // ---- the bucket ------------------------------------------------------

    @Test
    fun `a never-used bucket starts full`() {
        val now = utcMs(2026, 8, 17)
        assertEquals(AeroPace.BURST, AeroPace.tokensAt(null, 0L, now, 1000), 1e-9)
    }

    @Test
    fun `an empty bucket refills at the monthly rate`() {
        // 1st of a 31-day month, 1550 requests left = 50/day = ~1 per 29 min
        val now = utcMs(2026, 8, 1)
        val after90Min = AeroPace.tokensAt(0.0, now, now + 90 * 60 * 1000, 1550)
        assertTrue("expected ~3 tokens, got $after90Min", after90Min > 2.5 && after90Min < 3.5)
    }

    @Test
    fun `refill is capped at the burst allowance`() {
        val now = utcMs(2026, 8, 1)
        // A fortnight of not looking at the widget doesn't bank a fortnight of
        // requests — the burst is the ceiling
        assertEquals(
            AeroPace.BURST,
            AeroPace.tokensAt(0.0, now, now + 14 * day, 1550),
            1e-9,
        )
    }

    @Test
    fun `the cap drops with the budget when almost nothing is left`() {
        // Bucket banked while there was plenty of quota, then a usage check
        // reveals only 3 requests are affordable: the bucket can't hand out
        // more than the budget actually allows
        val now = utcMs(2026, 8, 20)
        assertEquals(3.0, AeroPace.tokensAt(5.0, now, now + hour, 3), 1e-9)
        // And accrual alone can never exceed what's left either
        assertTrue(AeroPace.tokensAt(0.0, now, now + 10 * day, 3) <= 3.0)
    }

    @Test
    fun `a clock jumping backwards cannot mint tokens`() {
        val now = utcMs(2026, 8, 17)
        assertEquals(2.0, AeroPace.tokensAt(2.0, now, now - day, 1000), 1e-9)
    }

    // ---- rate ------------------------------------------------------------

    @Test
    fun `an untouched month paces the whole allowance evenly`() {
        val perDay = AeroPace.requestsPerDay(1550, utcMs(2026, 8, 1))
        assertEquals(50.0, perDay, 0.5)
    }

    @Test
    fun `a quiet fortnight raises the daily rate`() {
        // Same 1550 left, but only 10 days of August remain: ~155/day
        val perDay = AeroPace.requestsPerDay(1550, utcMs(2026, 8, 22))
        assertEquals(155.0, perDay, 1.0)
    }

    @Test
    fun `a heavy start lowers the daily rate`() {
        // Burned all but 100 in the first two days: 100 over the last 29
        val perDay = AeroPace.requestsPerDay(100, utcMs(2026, 8, 3))
        assertEquals(3.4, perDay, 0.2)
    }

    @Test
    fun `unspent allowance is released as the month runs out`() {
        // Use it or lose it: 60 requests and six hours left is 10/hour, so an
        // empty bucket is back to full inside two hours instead of the ~29
        // minutes per request an even month-long spread would allow
        val sixHoursLeft = utcMs(2026, 8, 31, 18, 0)
        assertEquals(10.0, AeroPace.tokensAt(0.0, sixHoursLeft, sixHoursLeft + hour, 60), 1e-6)
        assertEquals(
            AeroPace.BURST,
            AeroPace.tokensAt(0.0, sixHoursLeft, sixHoursLeft + 2 * hour, 60),
            1e-9,
        )
    }

    // ---- waiting ---------------------------------------------------------

    @Test
    fun `a full bucket means no wait`() {
        assertEquals(0L, AeroPace.nextTokenInMs(1.0, 1000, utcMs(2026, 8, 17)))
    }

    @Test
    fun `an empty bucket waits one drip interval`() {
        // 1550 left over 31 days = 50/day -> ~29 min per request
        val wait = AeroPace.nextTokenInMs(0.0, 1550, utcMs(2026, 8, 1))
        assertEquals(29.0, wait.toDouble() / (60 * 1000), 1.0)
        // Half a token in the bucket = half the wait
        val half = AeroPace.nextTokenInMs(0.5, 1550, utcMs(2026, 8, 1))
        assertEquals(wait / 2.0, half.toDouble(), wait * 0.02)
    }

    @Test
    fun `an exhausted allowance never releases another token`() {
        assertEquals(
            Long.MAX_VALUE,
            AeroPace.nextTokenInMs(0.0, 0, utcMs(2026, 8, 17)),
        )
    }
}
