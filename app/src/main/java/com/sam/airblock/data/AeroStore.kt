package com.sam.airblock.data

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * State for the optional AeroAPI flight-times feature: the on/off switch, the
 * per-month billable-request counter, the last-known usage cost, and the
 * pacing bucket that spreads that allowance across the month.
 *
 * This is the guardrail that keeps spend inside the free allowance. Two
 * independent limits both force the feature OFF automatically:
 *
 *  - a **hard request cap** ([HARD_LIMIT]) — a local count of billable
 *    `/flights` queries this calendar month, well under the ~2,000 the $10
 *    feeder allowance buys, so even if usage polling fails we can't overrun; and
 *  - the **authoritative cost** from `/account/usage` ([lastCostUsd]) — once it
 *    reaches [BUDGET_USD] the switch trips off.
 *
 * Between those two floors sits [AeroPace]: the allowance is drip-fed at
 * "everything left ÷ time left in the month" so a single busy day can't spend
 * four weeks of quota, while quiet days automatically raise the rate.
 *
 * The counter resets at the start of each calendar month, matching how the
 * AeroAPI free allowance renews — and a feature that switched ITSELF off for
 * the quota switches itself back on then (one the user switched off stays off).
 */
data class AeroPrefs(
    val enabled: Boolean = false,
    val requestCount: Int = 0,
    /** "yyyy-MM" (UTC) the counter belongs to; a new month resets the count. */
    val period: String = "",
    /** Last total_cost from /account/usage, or null if never checked. */
    val lastCostUsd: Double? = null,
    val lastCheckedMs: Long = 0L,
    /** Human label of the last usage check ("Checked 12:04" / "Check failed"). */
    val lastStatus: String? = null,
    /** Pacing bucket as last written, and when — see [AeroPace]. */
    val tokens: Double? = null,
    val tokensAtMs: Long = 0L,
    /** FlightAware refused the key (401/403); the feature is off until it's replaced. */
    val keyRejected: Boolean = false,
) {
    /** Can a billable flight query be made right now? */
    fun canQuery(): Boolean =
        enabled &&
            requestCount < AeroStore.HARD_LIMIT &&
            (lastCostUsd ?: 0.0) < AeroStore.BUDGET_USD

    /** True when the feature has been forced off by hitting a limit. */
    fun exhausted(): Boolean =
        requestCount >= AeroStore.HARD_LIMIT || (lastCostUsd ?: 0.0) >= AeroStore.BUDGET_USD

    /** Requests still affordable this month under BOTH limits. */
    fun remainingRequests(): Int = AeroPace.remainingRequests(requestCount, lastCostUsd)

    /** Queries available right now — how much of the burst is left. */
    fun tokensNow(nowMs: Long = System.currentTimeMillis()): Double =
        AeroPace.tokensAt(tokens, tokensAtMs, nowMs, remainingRequests())

    /** The current drip rate, for display: requests per day. */
    fun requestsPerDay(nowMs: Long = System.currentTimeMillis()): Double =
        AeroPace.requestsPerDay(remainingRequests(), nowMs)

    /** True when the next query would be held back purely by pacing. */
    fun paced(nowMs: Long = System.currentTimeMillis()): Boolean =
        !exhausted() && tokensNow(nowMs) < 1.0
}

object AeroStore {
    /** Feeder free allowance: $10/month of AeroAPI usage. */
    const val BUDGET_USD = 10.0
    /** Per-query cost of GET /flights/{ident}. */
    const val PER_QUERY_USD = 0.005
    /** Local safety cap on billable queries/month (≈ the $10 ÷ $0.005 = 2000). */
    const val HARD_LIMIT = 1900

    /** Outcome of asking for one billable query. */
    enum class Spend {
        /** Go ahead — a token was consumed and the request counted. */
        OK,

        /** Allowance intact but the drip rate says "not yet". */
        PACED,

        /** Monthly allowance gone; the feature has switched itself off. */
        EXHAUSTED,
    }

    private val ENABLED = booleanPreferencesKey("aero_enabled")
    private val COUNT = intPreferencesKey("aero_req_count")
    private val PERIOD = stringPreferencesKey("aero_period")
    private val COST = doublePreferencesKey("aero_cost_usd")
    private val CHECKED = longPreferencesKey("aero_checked_ms")
    private val STATUS = stringPreferencesKey("aero_status")
    private val TOKENS = doublePreferencesKey("aero_tokens")
    private val TOKENS_AT = longPreferencesKey("aero_tokens_at_ms")
    /** Switched off by a limit, not by the user — so it resumes next month. */
    private val AUTO_PAUSED = booleanPreferencesKey("aero_auto_paused")
    private val KEY_REJECTED = booleanPreferencesKey("aero_key_rejected")

    /** Current "yyyy-MM" in UTC — the billing period the counter belongs to. */
    private fun currentPeriod(): String =
        SimpleDateFormat("yyyy-MM", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Calendar.getInstance(TimeZone.getTimeZone("UTC")).time)

    private fun decode(p: Preferences): AeroPrefs {
        val now = currentPeriod()
        // A rolled-over month zeroes the counter, clears the stale cost and
        // hands back a fresh (full) pacing bucket.
        // A quota pause from last month has ended, so it reads as on again
        // (and is written back on the first query of the month).
        return if (p[PERIOD].orEmpty() != now)
            AeroPrefs(enabled = (p[ENABLED] ?: false) || p[AUTO_PAUSED] == true,
                requestCount = 0, period = now,
                lastCostUsd = null, lastCheckedMs = 0L, lastStatus = null,
                tokens = null, tokensAtMs = 0L, keyRejected = p[KEY_REJECTED] ?: false)
        else
            AeroPrefs(
                enabled = p[ENABLED] ?: false,
                requestCount = p[COUNT] ?: 0,
                period = p[PERIOD].orEmpty(),
                lastCostUsd = p[COST],
                lastCheckedMs = p[CHECKED] ?: 0L,
                lastStatus = p[STATUS],
                tokens = p[TOKENS],
                tokensAtMs = p[TOKENS_AT] ?: 0L,
                keyRejected = p[KEY_REJECTED] ?: false,
            )
    }

    /** New billing month: zero the count and spend, resume a quota pause. */
    private fun rollOver(p: MutablePreferences) {
        p[PERIOD] = currentPeriod(); p[COUNT] = 0
        p.remove(COST); p.remove(TOKENS); p.remove(TOKENS_AT)
        if (p[AUTO_PAUSED] == true) { p[ENABLED] = true; p.remove(AUTO_PAUSED) }
    }

    /** A limit (not the user) switches the feature off — it resumes next month. */
    private fun pauseForQuota(p: MutablePreferences) {
        p[ENABLED] = false; p[AUTO_PAUSED] = true
    }

    suspend fun read(context: Context): AeroPrefs = decode(context.airblockStore.data.first())

    fun flow(context: Context): Flow<AeroPrefs> = context.airblockStore.data.map(::decode)

    /** The user's own choice — it overrides a quota pause and a rejected key. */
    suspend fun setEnabled(context: Context, on: Boolean) {
        context.airblockStore.edit { p ->
            if (p[PERIOD].orEmpty() != currentPeriod()) rollOver(p)
            p[ENABLED] = on
            p.remove(AUTO_PAUSED); p.remove(KEY_REJECTED)
        }
    }

    /**
     * Give back a request that ended in an error response: FlightAware didn't
     * bill it, so it mustn't count against the cap or drain the bucket.
     */
    suspend fun refund(context: Context) {
        context.airblockStore.edit { p ->
            if (p[PERIOD].orEmpty() != currentPeriod()) return@edit
            p[COUNT] = ((p[COUNT] ?: 0) - 1).coerceAtLeast(0)
            p[TOKENS]?.let { p[TOKENS] = it + 1.0 }
        }
    }

    /** FlightAware refused the key: switch off and say so until it's replaced. */
    suspend fun rejectKey(context: Context, code: Int) {
        context.airblockStore.edit { p ->
            p[ENABLED] = false
            p.remove(AUTO_PAUSED)
            p[KEY_REJECTED] = true
            p[STATUS] = "Key rejected — HTTP $code"
        }
    }

    /**
     * Ask to make one billable flight query.
     *
     * Counts the request BEFORE the call is made (a crash mid-request can then
     * never let us slip past the cap) and takes one token from the [AeroPace]
     * bucket, so callers are throttled to the rate that spreads what is left of
     * the allowance across what is left of the month. [Spend.PACED] is a "come
     * back later" — nothing is spent and the caller simply falls back to the
     * geometry ETA for this tick.
     */
    suspend fun trySpend(context: Context, nowMs: Long = System.currentTimeMillis()): Spend {
        var result = Spend.OK
        context.airblockStore.edit { p ->
            if (p[PERIOD].orEmpty() != currentPeriod()) rollOver(p)
            val count = p[COUNT] ?: 0
            val remaining = AeroPace.remainingRequests(count, p[COST])
            if (remaining <= 0) {
                pauseForQuota(p)
                result = Spend.EXHAUSTED
                return@edit
            }
            val tokens = AeroPace.tokensAt(p[TOKENS], p[TOKENS_AT] ?: 0L, nowMs, remaining)
            p[TOKENS_AT] = nowMs
            if (tokens < 1.0) {
                p[TOKENS] = tokens
                result = Spend.PACED
                return@edit
            }
            p[TOKENS] = tokens - 1.0
            p[COUNT] = count + 1
            if (count + 1 >= HARD_LIMIT) pauseForQuota(p)
        }
        return result
    }

    /**
     * Store the latest authoritative usage cost from /account/usage. Forces the
     * feature off when the free budget is spent. A null cost means the check
     * failed — only the status label is updated.
     */
    suspend fun recordUsage(context: Context, costUsd: Double?, status: String) {
        context.airblockStore.edit { p ->
            if (p[PERIOD].orEmpty() != currentPeriod()) rollOver(p)
            costUsd?.let {
                p[COST] = it
                if (it >= BUDGET_USD) pauseForQuota(p)
            }
            p[CHECKED] = System.currentTimeMillis()
            p[STATUS] = status
        }
    }
}
