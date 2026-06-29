package com.sam.airblock.data

import android.content.Context
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

// ---------- AeroAPI /flights/{ident} ----------

@Serializable
data class AeroFlightsResponse(val flights: List<AeroFlight> = emptyList())

@Serializable
data class AeroFlight(
    val ident: String? = null,
    val cancelled: Boolean = false,
    @SerialName("scheduled_out") val scheduledOut: String? = null,
    @SerialName("estimated_out") val estimatedOut: String? = null,
    @SerialName("actual_out") val actualOut: String? = null,
    @SerialName("scheduled_in") val scheduledIn: String? = null,
    @SerialName("estimated_in") val estimatedIn: String? = null,
    @SerialName("actual_in") val actualIn: String? = null,
    /** Arrival delay vs schedule, in SECONDS (negative = early). */
    @SerialName("arrival_delay") val arrivalDelay: Int? = null,
    @SerialName("progress_percent") val progressPercent: Int? = null,
    val origin: AeroAirport? = null,
    val destination: AeroAirport? = null,
) {
    /** Airborne: pushed back but not yet arrived. Used to pick the live leg. */
    val inProgress: Boolean get() = actualOut != null && actualIn == null
}

@Serializable
data class AeroAirport(
    val code: String? = null,
    @SerialName("code_iata") val codeIata: String? = null,
    /** IANA zone, e.g. "America/New_York" — used to show arrival in local time. */
    val timezone: String? = null,
)

// ---------- AeroAPI /account/usage ----------

@Serializable
data class AeroUsageResponse(
    @SerialName("total_cost") val totalCost: Double? = null,
)

/**
 * Resolved arrival timing for a flight, in epoch-ms and minutes — everything the
 * widget needs to show a *real* arrival time and delay instead of the
 * distance/speed ETA guess.
 */
data class AeroTimes(
    val scheduledArrivalMs: Long?,
    val estimatedArrivalMs: Long?,
    val arrivalDelayMin: Int?,
    val destTimeZone: String?,
)

/**
 * Thin client for FlightAware AeroAPI. Authenticates with the `x-apikey` header
 * read from [SecureKeyStore]; the key is never logged or echoed.
 *
 * Only two endpoints are used: `/flights/{ident}` (one BILLABLE query per
 * flight — $0.005 each) and `/account/usage` (free; used to track how much of
 * the monthly free allowance has been spent). All budgeting and the hard
 * request cap live in [AeroStore]; this class only performs the calls.
 */
class AeroApi(
    private val context: Context,
    private val client: OkHttpClient = Http.client,
) {
    private fun key(): String? = SecureKeyStore.aeroKey(context)

    /** Real arrival timing for a callsign, or null when unknown/unauthorised. */
    @Throws(IOException::class)
    fun times(ident: String): AeroTimes? {
        val key = key() ?: return null
        val req = Request.Builder()
            .url("$BASE/flights/${ident.trim().uppercase()}?max_pages=1")
            .header("x-apikey", key)
            .build()
        client.newCall(req).execute().use { resp ->
            if (resp.code == 404) return null
            if (!resp.isSuccessful) throw IOException("aero flights HTTP ${resp.code}")
            val body = resp.body?.string() ?: return null
            val flight = pick(Http.json.decodeFromString<AeroFlightsResponse>(body).flights)
                ?: return null
            val estArr = parseIso(flight.actualIn ?: flight.estimatedIn)
            return AeroTimes(
                scheduledArrivalMs = parseIso(flight.scheduledIn),
                estimatedArrivalMs = estArr,
                arrivalDelayMin = flight.arrivalDelay?.let { it / 60 },
                destTimeZone = flight.destination?.timezone,
            ).takeIf { it.estimatedArrivalMs != null || it.arrivalDelayMin != null }
        }
    }

    /**
     * Dollars of AeroAPI usage accrued so far this calendar month, from the free
     * `/account/usage` endpoint. Null when no key is set.
     */
    @Throws(IOException::class)
    fun usageCostUsd(): Double? {
        val key = key() ?: return null
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        val end = fmt.format(cal.time)
        cal.set(Calendar.DAY_OF_MONTH, 1)
        val start = fmt.format(cal.time)
        val req = Request.Builder()
            .url("$BASE/account/usage?start=$start&end=$end")
            .header("x-apikey", key)
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("aero usage HTTP ${resp.code}")
            val body = resp.body?.string() ?: return null
            return Http.json.decodeFromString<AeroUsageResponse>(body).totalCost
        }
    }

    /** Prefer the airborne leg, then any non-cancelled flight, else the first. */
    private fun pick(flights: List<AeroFlight>): AeroFlight? =
        flights.firstOrNull { it.inProgress }
            ?: flights.firstOrNull { !it.cancelled }
            ?: flights.firstOrNull()

    private companion object {
        const val BASE = "https://aeroapi.flightaware.com/aeroapi"

        /** AeroAPI emits ISO-8601 UTC ("2026-06-29T14:32:00Z"). */
        fun parseIso(s: String?): Long? = s?.let { str ->
            runCatching { java.time.Instant.parse(str).toEpochMilli() }
                .recoverCatching {
                    // recoverCatching's `it` is the Throwable — use the named
                    // String so we don't accidentally parse the exception
                    java.time.OffsetDateTime.parse(str).toInstant().toEpochMilli()
                }
                .getOrNull()
        }
    }
}
