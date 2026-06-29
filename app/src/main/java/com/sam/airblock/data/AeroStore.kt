package com.sam.airblock.data

import android.content.Context
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
 * per-month billable-request counter, and the last-known usage cost.
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
 * The counter resets at the start of each calendar month, matching how the
 * AeroAPI free allowance renews.
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
) {
    /** Can a billable flight query be made right now? */
    fun canQuery(): Boolean =
        enabled &&
            requestCount < AeroStore.HARD_LIMIT &&
            (lastCostUsd ?: 0.0) < AeroStore.BUDGET_USD

    /** True when the feature has been forced off by hitting a limit. */
    fun exhausted(): Boolean =
        requestCount >= AeroStore.HARD_LIMIT || (lastCostUsd ?: 0.0) >= AeroStore.BUDGET_USD
}

object AeroStore {
    /** Feeder free allowance: $10/month of AeroAPI usage. */
    const val BUDGET_USD = 10.0
    /** Per-query cost of GET /flights/{ident}. */
    const val PER_QUERY_USD = 0.005
    /** Local safety cap on billable queries/month (≈ the $10 ÷ $0.005 = 2000). */
    const val HARD_LIMIT = 1900

    private val ENABLED = booleanPreferencesKey("aero_enabled")
    private val COUNT = intPreferencesKey("aero_req_count")
    private val PERIOD = stringPreferencesKey("aero_period")
    private val COST = doublePreferencesKey("aero_cost_usd")
    private val CHECKED = longPreferencesKey("aero_checked_ms")
    private val STATUS = stringPreferencesKey("aero_status")

    /** Current "yyyy-MM" in UTC — the billing period the counter belongs to. */
    private fun currentPeriod(): String =
        SimpleDateFormat("yyyy-MM", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Calendar.getInstance(TimeZone.getTimeZone("UTC")).time)

    private fun decode(
        enabled: Boolean, count: Int, period: String,
        cost: Double?, checked: Long, status: String?,
    ): AeroPrefs {
        val now = currentPeriod()
        // A rolled-over month zeroes the counter and clears the stale cost.
        return if (period != now)
            AeroPrefs(enabled = enabled, requestCount = 0, period = now,
                lastCostUsd = null, lastCheckedMs = 0L, lastStatus = null)
        else
            AeroPrefs(enabled, count, period, cost, checked, status)
    }

    suspend fun read(context: Context): AeroPrefs {
        val p = context.airblockStore.data.first()
        return decode(
            p[ENABLED] ?: false, p[COUNT] ?: 0, p[PERIOD] ?: "",
            p[COST], p[CHECKED] ?: 0L, p[STATUS],
        )
    }

    fun flow(context: Context): Flow<AeroPrefs> =
        context.airblockStore.data.map { p ->
            decode(
                p[ENABLED] ?: false, p[COUNT] ?: 0, p[PERIOD] ?: "",
                p[COST], p[CHECKED] ?: 0L, p[STATUS],
            )
        }

    suspend fun setEnabled(context: Context, on: Boolean) {
        context.airblockStore.edit { p ->
            p[ENABLED] = on
            if (p[PERIOD].orEmpty() != currentPeriod()) {
                p[PERIOD] = currentPeriod(); p[COUNT] = 0; p.remove(COST)
            }
        }
    }

    /**
     * Record one billable flight query and return whether more are still
     * allowed. Trips the switch off the moment the hard cap is reached.
     */
    suspend fun recordRequest(context: Context): Boolean {
        var ok = true
        context.airblockStore.edit { p ->
            val now = currentPeriod()
            val count = (if (p[PERIOD].orEmpty() == now) (p[COUNT] ?: 0) else 0) + 1
            p[PERIOD] = now
            p[COUNT] = count
            if (count >= HARD_LIMIT) { p[ENABLED] = false; ok = false }
        }
        return ok
    }

    /**
     * Store the latest authoritative usage cost from /account/usage. Forces the
     * feature off when the free budget is spent. A null cost means the check
     * failed — only the status label is updated.
     */
    suspend fun recordUsage(context: Context, costUsd: Double?, status: String) {
        context.airblockStore.edit { p ->
            if (p[PERIOD].orEmpty() != currentPeriod()) {
                p[PERIOD] = currentPeriod(); p[COUNT] = 0
            }
            costUsd?.let {
                p[COST] = it
                if (it >= BUDGET_USD) p[ENABLED] = false
            }
            p[CHECKED] = System.currentTimeMillis()
            p[STATUS] = status
        }
    }
}
