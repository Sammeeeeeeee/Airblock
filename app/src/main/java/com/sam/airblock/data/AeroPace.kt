package com.sam.airblock.data

import java.util.Calendar
import java.util.TimeZone
import kotlin.math.max
import kotlin.math.min

/**
 * Spend pacing for the billable AeroAPI `/flights` calls.
 *
 * The hard cap in [AeroStore] only stops an overrun — on its own it lets a busy
 * afternoon under a flight path burn a whole month's free allowance in a day,
 * after which the widget has no real times for four weeks. This spreads what is
 * left over the time that is left, and re-derives that rate on every single
 * query so it adapts on its own:
 *
 *  - **Rate** = requests still affordable ÷ milliseconds still left in the
 *    billing month. Quiet days push the rate up; a heavy day pushes it back
 *    down. No fixed daily quota to tune.
 *  - **Burst** = a bucket of [BURST] tokens, so several new flights in quick
 *    succession all resolve; only a sustained run gets throttled.
 *  - **Use it or lose it** — as the month ends the divisor shrinks, so anything
 *    unspent is released rather than wasted (the allowance does not roll over).
 *  - **Authoritative** — "affordable" is the stricter of our own request count
 *    against [AeroStore.HARD_LIMIT] and FlightAware's own reported spend against
 *    [AeroStore.BUDGET_USD], so a lagging usage figure can't cause an overrun.
 *
 * Pure functions only (no Android, no I/O) — the arithmetic is unit-tested.
 */
object AeroPace {

    /** Back-to-back queries allowed before the drip rate starts to bite. */
    const val BURST = 12.0

    /** Requests still affordable this month, by BOTH limits — the stricter wins. */
    fun remainingRequests(count: Int, costUsd: Double?): Int {
        val byCount = AeroStore.HARD_LIMIT - count
        val byCost = costUsd?.let {
            ((AeroStore.BUDGET_USD - it) / AeroStore.PER_QUERY_USD).toInt()
        } ?: Int.MAX_VALUE
        return max(0, min(byCount, byCost))
    }

    /** Epoch ms of the first instant of next month (UTC) — the allowance reset. */
    fun monthEndMs(nowMs: Long): Long {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            timeInMillis = nowMs
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            add(Calendar.MONTH, 1)
        }
        return cal.timeInMillis
    }

    /** Tokens earned per millisecond: everything left, spread over what's left. */
    fun refillPerMs(remaining: Int, fromMs: Long, monthEndMs: Long): Double {
        val window = max(1L, monthEndMs - fromMs)
        return remaining.toDouble() / window
    }

    /**
     * The bucket level at [nowMs], given the level last written at [atMs].
     * A never-written bucket ([atMs] == 0) starts full — the first flight seen
     * after enabling the feature should resolve immediately.
     */
    fun tokensAt(
        stored: Double?,
        atMs: Long,
        nowMs: Long,
        remaining: Int,
        monthEndMs: Long = monthEndMs(nowMs),
    ): Double {
        val cap = min(BURST, remaining.toDouble())
        if (stored == null || atMs <= 0L) return cap
        if (nowMs <= atMs) return min(stored, cap)
        val earned = (nowMs - atMs) * refillPerMs(remaining, atMs, monthEndMs)
        return min(cap, stored + earned)
    }

    /** Pace expressed for humans: requests per day at the current rate. */
    fun requestsPerDay(remaining: Int, nowMs: Long, monthEndMs: Long = monthEndMs(nowMs)): Double =
        refillPerMs(remaining, nowMs, monthEndMs) * DAY_MS

    /** Wait until the next whole token, in ms; 0 when one is ready now. */
    fun nextTokenInMs(
        tokens: Double,
        remaining: Int,
        nowMs: Long,
        monthEndMs: Long = monthEndMs(nowMs),
    ): Long {
        if (tokens >= 1.0) return 0L
        val rate = refillPerMs(remaining, nowMs, monthEndMs)
        if (rate <= 0.0) return Long.MAX_VALUE
        return ((1.0 - tokens) / rate).toLong()
    }

    private const val DAY_MS = 24L * 60 * 60 * 1000
}
