package com.sam.airblock.engine

import android.content.Context
import android.util.Log
import androidx.glance.appwidget.updateAll
import com.sam.airblock.data.AdsbApi
import com.sam.airblock.data.EventLog
import com.sam.airblock.data.NetMode
import com.sam.airblock.data.PhotoRepo
import com.sam.airblock.data.RouteResult
import com.sam.airblock.data.SettingsStore
import com.sam.airblock.data.WidgetState
import com.sam.airblock.data.WidgetStateStore
import com.sam.airblock.util.SpecialType
import com.sam.airblock.util.Squawk
import com.sam.airblock.util.TypeNames
import com.sam.airblock.util.Units
import com.sam.airblock.widget.AirblockWidget
import java.io.IOException

/**
 * One refresh cycle: location → nearest aircraft → (cached) route + photo →
 * widget state. Shared by [UpdateService] (15 s loop) and [KeepAliveWorker]
 * (fallback refresh when the service has been killed).
 */
class Ticker(private val context: Context) {

    private val location = LocationProvider(context)
    private val photos = PhotoRepo(context)
    private val gates = Gates(context)
    private val api = AdsbApi()

    // Per-flight caches: one route lookup per callsign, one photo per hex
    private var cachedRoute: Pair<String, RouteResult?>? = null

    var consecutiveErrors = 0
        private set

    suspend fun tick() {
        // The badge spinner must reflect EVERY refresh (automatic ones too),
        // not just manual taps — flip it on now; every exit path below
        // publishes a state with refreshing=false, so it can't get stuck.
        val (p0, n0) = WidgetStateStore.update(context) { it.copy(refreshing = true) }
        if (p0.renderKey() != n0.renderKey()) AirblockWidget().updateAll(context)

        val settings = SettingsStore.read(context)
        val log = settings.logEnabled

        // Schedule-aware freshness: data on the 10-min plan isn't "stale"
        // after 2 minutes — stamp every state with its real deadline + label.
        val (netMode, netWhy) = gates.effectiveMode(settings)
        val effIntervalMs = when (netMode) {
            NetMode.SLOW -> 10L * 60 * 1000
            else -> settings.intervalSec * 1000L
        }
        val staleAfter = System.currentTimeMillis() + maxOf(effIntervalMs * 2, 120_000L)
        val modeLabel = when (netMode) {
            NetMode.SLOW -> "$netWhy · 10 min"
            NetMode.OFF -> "$netWhy · off"
            else -> "$netWhy · ${settings.intervalSec}s"
        }

        val fix = location.currentFix()
        if (fix == null) {
            Log.d(TAG, "tick: no location fix")
            if (log) EventLog.append(context, "update skipped — no location fix")
            publish(WidgetState(status = WidgetState.Status.NO_LOCATION,
                updatedAt = System.currentTimeMillis(),
                staleAfterMs = staleAfter, modeLabel = modeLabel))
            return
        }
        try {
            // Ground traffic is excluded: if the nearest transponder is a
            // parked/taxiing plane, fall back to the nearest airborne one
            val ac = api.closest(fix.lat, fix.lon, settings.radiusNm)
                ?.let { if (it.onGround) api.nearestAirborne(fix.lat, fix.lon, settings.radiusNm) else it }
            consecutiveErrors = 0
            if (ac == null) {
                Log.d(TAG, "tick: no aircraft within ${settings.radiusNm} nm")
                if (log) EventLog.append(context, "updated — no aircraft within ${settings.radiusNm} nm")
                publish(WidgetState(status = WidgetState.Status.NO_AIRCRAFT,
                    updatedAt = System.currentTimeMillis(),
                    staleAfterMs = staleAfter, modeLabel = modeLabel))
                return
            }

            val callsign = ac.callsign
            val route: RouteResult? = when {
                callsign == null -> null
                cachedRoute?.first == callsign -> cachedRoute?.second
                else -> try {
                    // Cache only definitive answers (incl. a genuine 404 -> null);
                    // transient failures must retry on the next tick
                    api.route(callsign).also {
                        cachedRoute = callsign to it
                        if (it == null) Log.d(TAG, "route: none recorded for $callsign")
                    }
                } catch (e: IOException) {
                    Log.w(TAG, "route lookup failed for $callsign: $e")
                    null
                }
            }
            // Multi-leg routes (e.g. SDF-DUB-STN-CGN): show the leg the plane
            // is actually flying, not the overall first/last airports.
            val planeLat = ac.lat ?: fix.lat
            val planeLon = ac.lon ?: fix.lon
            val leg = pickLeg(route?.airports.orEmpty(), planeLat, planeLon)
            val origin = leg?.first
            val dest = leg?.second

            // Journey progress + ETA from great-circle geometry and ground speed
            var progress: Float? = null
            var etaEpochMs: Long? = null
            if (origin?.lat != null && origin.lon != null &&
                dest?.lat != null && dest.lon != null
            ) {
                val fromOrigin = Units.haversineKm(origin.lat, origin.lon, planeLat, planeLon)
                val toDest = Units.haversineKm(planeLat, planeLon, dest.lat, dest.lon)
                if (fromOrigin + toDest > 1.0) {
                    progress = (fromOrigin / (fromOrigin + toDest)).toFloat()
                }
                val gsKmh = (ac.gs ?: 0.0) * Units.NM_TO_KM
                if (gsKmh > 150 && toDest > 2.0) {
                    etaEpochMs = System.currentTimeMillis() +
                        (toDest / gsKmh * 3_600_000).toLong()
                }
            }

            val photo = photos.photoFor(ac.hex)

            Log.d(TAG, "tick @%.2f,%.2f: %s dst=%snm route=%s photo=%s".format(
                fix.lat, fix.lon, callsign ?: ac.hex, ac.dst,
                route?.airportCodes ?: "?", photo != null))
            if (log) EventLog.append(context,
                "updated — ${callsign ?: ac.hex}" +
                    (ac.dst?.let { " · %.1f km".format(Units.nmToKm(it)) } ?: ""))

            publish(WidgetState(
                status = WidgetState.Status.OK,
                callsign = callsign ?: ac.r ?: ac.hex.uppercase(),
                // desc is often absent from /v2/closest — resolve the ICAO code
                // to a full name offline, falling back to the raw code
                typeName = ac.desc?.let { prettyType(it) }
                    ?: TypeNames.name(ac.t) ?: ac.t,
                typeCode = ac.t,
                registration = ac.r,
                hex = ac.hex,
                altitudeFt = ac.altitudeFt,
                onGround = ac.onGround,
                speedMph = ac.gs?.let { Units.ktsToMph(it) },
                mach = ac.mach,
                distanceKm = ac.dst?.let { Units.nmToKm(it) },
                routeProgress = progress,
                etaEpochMs = etaEpochMs,
                specialType = SpecialType.classify(ac.category, ac.dbFlags),
                squawkAlert = Squawk.emergencyLabel(ac.squawk),
                originIata = origin?.iata,
                originCity = origin?.location,
                originFlag = Units.flagEmoji(origin?.countryIso2),
                destIata = dest?.iata,
                destCity = dest?.location,
                destFlag = Units.flagEmoji(dest?.countryIso2),
                photoPath = photo?.file?.absolutePath,
                photoCredit = photo?.photographer,
                updatedAt = System.currentTimeMillis(),
                staleAfterMs = staleAfter,
                modeLabel = modeLabel,
            ))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // Not just IOException: a captive portal or CDN error page makes
            // the JSON decode throw SerializationException — one bad body must
            // never kill the engine permanently.
            consecutiveErrors++
            Log.w(TAG, "tick failed (${consecutiveErrors}x): ${e.message}")
            if (log) EventLog.append(context,
                "update FAILED (×$consecutiveErrors) — ${e.message ?: e.javaClass.simpleName}")
            // Keep showing last good data, but surface the failure icon
            val (prev, next) = WidgetStateStore.update(context) {
                it.copy(errorCount = consecutiveErrors, refreshing = false,
                    pausedReason = null,
                    lastError = e.message ?: e.javaClass.simpleName)
            }
            if (prev.renderKey() != next.renderKey()) AirblockWidget().updateAll(context)
        }
    }

    /**
     * The consecutive airport pair the plane is currently between — the leg
     * with the smallest detour (dist-to-A + dist-to-B − leg length).
     */
    private fun pickLeg(
        airports: List<com.sam.airblock.data.RouteAirport>,
        lat: Double,
        lon: Double,
    ): Pair<com.sam.airblock.data.RouteAirport, com.sam.airblock.data.RouteAirport>? {
        if (airports.size < 2) return null
        if (airports.size == 2) return airports[0] to airports[1]
        var best: Pair<com.sam.airblock.data.RouteAirport, com.sam.airblock.data.RouteAirport>? = null
        var bestScore = Double.MAX_VALUE
        for (i in 0 until airports.size - 1) {
            val a = airports[i]
            val b = airports[i + 1]
            if (a.lat == null || a.lon == null || b.lat == null || b.lon == null) continue
            val score = Units.haversineKm(lat, lon, a.lat, a.lon) +
                Units.haversineKm(lat, lon, b.lat, b.lon) -
                Units.haversineKm(a.lat, a.lon, b.lat, b.lon)
            if (score < bestScore) {
                bestScore = score
                best = a to b
            }
        }
        return best ?: (airports.first() to airports.last())
    }

    /** "BOEING 737-900" -> "Boeing 737-900" */
    private fun prettyType(desc: String): String =
        desc.split(" ").joinToString(" ") { w ->
            if (w.any { it.isDigit() }) w else w.lowercase().replaceFirstChar { it.uppercase() }
        }

    private suspend fun publish(state: WidgetState) {
        // Atomic swap — concurrent writers (worker, tap) can't interleave
        val (previous, next) = WidgetStateStore.update(context) { state }
        // Skip the RemoteViews churn when nothing the user can see has changed
        if (previous.renderKey() != next.renderKey()) {
            AirblockWidget().updateAll(context)
        }
    }

    companion object {
        private const val TAG = "Airblock"
    }
}
