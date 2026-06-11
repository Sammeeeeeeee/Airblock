package com.sam.airblock.engine

import android.content.Context
import android.util.Log
import androidx.glance.appwidget.updateAll
import com.sam.airblock.data.AdsbApi
import com.sam.airblock.data.AirlineLogoRepo
import com.sam.airblock.data.EventLog
import com.sam.airblock.data.ManufacturerLogoRepo
import com.sam.airblock.data.NetMode
import com.sam.airblock.data.PhotoRepo
import com.sam.airblock.data.RouteResult
import com.sam.airblock.data.SettingsStore
import com.sam.airblock.data.WidgetState
import com.sam.airblock.data.WidgetStateStore
import com.sam.airblock.util.AirlineCodes
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
 *
 * Publishes in TWO phases so the widget never sits on a spinner waiting for
 * slow media: the aircraft itself (callsign, altitude, speed…) goes out the
 * moment the closest-aircraft response lands, reusing the previous tick's
 * route/photo/logo when they still apply; the enriched state follows once the
 * route and media fetches finish.
 */
class Ticker(private val context: Context) {

    private val location = LocationProvider(context)
    private val photos = PhotoRepo(context)
    private val airlineLogos = AirlineLogoRepo(context)
    private val manufacturerLogos = ManufacturerLogoRepo(context)
    private val gates = Gates(context)
    private val api = AdsbApi()

    // Per-flight caches: one route lookup per callsign, one photo per hex
    private var cachedRoute: Pair<String, RouteResult?>? = null

    var consecutiveErrors = 0
        private set

    /** Mutable per-tick checklist, persisted on every transition. */
    private val stageOrder = listOf("location", "aircraft", "route", "media")
    private var stages = linkedMapOf<String, WidgetState.Stage>()

    private fun resetStages() {
        stages = linkedMapOf(
            "location" to WidgetState.Stage("location", "Location", WidgetState.Stage.PENDING),
            "aircraft" to WidgetState.Stage("aircraft", "Nearest aircraft", WidgetState.Stage.PENDING),
            "route" to WidgetState.Stage("route", "Route", WidgetState.Stage.PENDING),
            "media" to WidgetState.Stage("media", "Photo & airline logo", WidgetState.Stage.PENDING),
        )
    }

    private fun stageList() = stageOrder.mapNotNull { stages[it] }

    /**
     * Record a stage transition and persist it. The widget's renderKey ignores
     * stage/checklist fields, so these writes reach the in-app status card via
     * the DataStore flow with zero RemoteViews churn.
     */
    private suspend fun setStage(key: String, state: String, label: String? = null,
        stageText: String? = null) {
        stages[key]?.let { stages[key] = it.copy(state = state, label = label ?: it.label) }
        val (prev, next) = WidgetStateStore.update(context) {
            it.copy(refreshing = true, stages = stageList(),
                refreshStage = stageText ?: it.refreshStage)
        }
        if (prev.renderKey() != next.renderKey()) AirblockWidget().updateAll(context)
    }

    /** Mark whatever is still running/pending as failed — used on tick failure. */
    private fun failOpenStages() {
        stages.replaceAll { _, s ->
            if (s.state == WidgetState.Stage.RUNNING || s.state == WidgetState.Stage.PENDING)
                s.copy(state = WidgetState.Stage.FAILED) else s
        }
    }

    suspend fun tick() {
        // The badge spinner must reflect EVERY refresh (automatic ones too),
        // not just manual taps — flip it on now; every exit path below
        // publishes a state with refreshing=false, so it can't get stuck.
        resetStages()
        setStage("location", WidgetState.Stage.RUNNING, stageText = "Getting location…")

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
            stages["location"] = stages["location"]!!.copy(state = WidgetState.Stage.FAILED)
            publish(WidgetState(status = WidgetState.Status.NO_LOCATION,
                updatedAt = System.currentTimeMillis(),
                staleAfterMs = staleAfter, modeLabel = modeLabel,
                stages = stageList()))
            return
        }
        try {
            setStage("location", WidgetState.Stage.DONE)
            setStage("aircraft", WidgetState.Stage.RUNNING,
                stageText = "Finding the nearest aircraft…")
            // Ground traffic is excluded: if the nearest transponder is a
            // parked/taxiing plane, fall back to the nearest airborne one
            val ac = api.closest(fix.lat, fix.lon, settings.radiusNm)
                ?.let { if (it.onGround) api.nearestAirborne(fix.lat, fix.lon, settings.radiusNm) else it }
            consecutiveErrors = 0
            if (ac == null) {
                Log.d(TAG, "tick: no aircraft within ${settings.radiusNm} nm")
                if (log) EventLog.append(context, "updated — no aircraft within ${settings.radiusNm} nm")
                setStage("aircraft", WidgetState.Stage.DONE)
                publish(WidgetState(status = WidgetState.Status.NO_AIRCRAFT,
                    updatedAt = System.currentTimeMillis(),
                    staleAfterMs = staleAfter, modeLabel = modeLabel,
                    stages = stageList()))
                return
            }
            setStage("aircraft", WidgetState.Stage.DONE)

            val callsign = ac.callsign
            val planeLat = ac.lat ?: fix.lat
            val planeLon = ac.lon ?: fix.lon

            // ---- Phase 1: publish the aircraft NOW ------------------------
            // The whole point of a tap is "what's overhead" — that answer is
            // already in hand. Route/photo/logo from the previous tick still
            // apply when the flight/airframe hasn't changed; anything else is
            // filled in by phase 2.
            val prev = WidgetStateStore.read(context)
            val sameAirframe = prev.hex != null && prev.hex == ac.hex
            val sameFlight = prev.callsign != null && callsign != null &&
                prev.callsign == callsign
            val sameAirline = callsign != null &&
                AirlineCodes.icaoPrefix(callsign) != null &&
                AirlineCodes.icaoPrefix(callsign) == AirlineCodes.icaoPrefix(prev.callsign)

            val routeCached = callsign != null && cachedRoute?.first == callsign
            var route: RouteResult? = if (routeCached) cachedRoute?.second else null
            var leg = pickLeg(route?.airports.orEmpty(), planeLat, planeLon)
            var geo = legGeometry(leg, planeLat, planeLon, ac.gs)

            val resolvedTypeName = ac.desc?.let { prettyType(it) }
                ?: TypeNames.name(ac.t) ?: ac.t
            val sameType = prev.typeName != null && prev.typeName == resolvedTypeName

            fun buildState(
                photoPath: String?, photoCredit: String?, logoPath: String?,
                mfrLogoPath: String?,
                refreshing: Boolean,
            ) = WidgetState(
                status = WidgetState.Status.OK,
                callsign = callsign ?: ac.r ?: ac.hex.uppercase(),
                // desc is often absent from /v2/closest — resolve the ICAO code
                // to a full name offline, falling back to the raw code
                typeName = resolvedTypeName,
                typeCode = ac.t,
                registration = ac.r,
                hex = ac.hex,
                altitudeFt = ac.altitudeFt,
                onGround = ac.onGround,
                speedMph = ac.gs?.let { Units.ktsToMph(it) },
                mach = ac.mach,
                distanceKm = ac.dst?.let { Units.nmToKm(it) },
                routeProgress = geo.progress
                    ?: prev.routeProgress.takeIf { sameFlight && leg == null },
                etaEpochMs = geo.etaEpochMs
                    ?: prev.etaEpochMs.takeIf { sameFlight && leg == null },
                specialType = SpecialType.classify(ac.category, ac.dbFlags),
                squawkAlert = Squawk.emergencyLabel(ac.squawk),
                originIata = leg?.first?.iata ?: prev.originIata.takeIf { sameFlight },
                originCity = leg?.first?.location ?: prev.originCity.takeIf { sameFlight },
                originFlag = leg?.first?.let { Units.flagEmoji(it.countryIso2) }
                    ?: prev.originFlag.takeIf { sameFlight },
                destIata = leg?.second?.iata ?: prev.destIata.takeIf { sameFlight },
                destCity = leg?.second?.location ?: prev.destCity.takeIf { sameFlight },
                destFlag = leg?.second?.let { Units.flagEmoji(it.countryIso2) }
                    ?: prev.destFlag.takeIf { sameFlight },
                photoPath = photoPath,
                photoCredit = photoCredit,
                airlineLogoPath = logoPath,
                airlineName = AirlineCodes.nameForCallsign(callsign),
                manufacturerLogoPath = mfrLogoPath,
                updatedAt = System.currentTimeMillis(),
                refreshing = refreshing,
                refreshStage = if (refreshing) "Loading route, photo & logo…" else null,
                stages = stageList(),
                staleAfterMs = staleAfter,
                modeLabel = modeLabel,
            )

            publish(buildState(
                photoPath = prev.photoPath.takeIf { sameAirframe },
                photoCredit = prev.photoCredit.takeIf { sameAirframe },
                logoPath = prev.airlineLogoPath.takeIf { sameAirline },
                mfrLogoPath = prev.manufacturerLogoPath.takeIf { sameType },
                refreshing = true,
            ))

            // ---- Phase 2: route + media enrichment ------------------------
            when {
                callsign == null ->
                    setStage("route", WidgetState.Stage.DONE, label = "Route (no callsign)")
                routeCached ->
                    setStage("route", WidgetState.Stage.DONE, label = "Route (cached)")
                else -> {
                    setStage("route", WidgetState.Stage.RUNNING)
                    try {
                        // Cache only definitive answers (incl. a genuine 404 -> null);
                        // transient failures must retry on the next tick
                        route = api.route(callsign).also {
                            cachedRoute = callsign to it
                            if (it == null) Log.d(TAG, "route: none recorded for $callsign")
                        }
                        leg = pickLeg(route?.airports.orEmpty(), planeLat, planeLon)
                        geo = legGeometry(leg, planeLat, planeLon, ac.gs)
                        setStage("route", WidgetState.Stage.DONE)
                    } catch (e: IOException) {
                        Log.w(TAG, "route lookup failed for $callsign: $e")
                        setStage("route", WidgetState.Stage.FAILED)
                    }
                }
            }

            setStage("media", WidgetState.Stage.RUNNING)
            // Disk-cached per hex/airline — instant for anything already seen;
            // the previous tick's media stays as fallback when a fetch fails
            val photo = photos.photoFor(ac.hex)
            val photoPath = photo?.file?.absolutePath ?: prev.photoPath.takeIf { sameAirframe }
            val photoCredit = photo?.photographer ?: prev.photoCredit.takeIf { sameAirframe }
            val logoPath = airlineLogos.logoFor(callsign)?.absolutePath
                ?: prev.airlineLogoPath.takeIf { sameAirline }
            val mfrLogoPath = manufacturerLogos.logoFor(resolvedTypeName)?.absolutePath
                ?: prev.manufacturerLogoPath.takeIf { sameType }
            setStage("media", WidgetState.Stage.DONE)

            Log.d(TAG, "tick @%.2f,%.2f: %s dst=%snm route=%s photo=%s".format(
                fix.lat, fix.lon, callsign ?: ac.hex, ac.dst,
                route?.airportCodes ?: "?", photoPath != null))
            if (log) EventLog.append(context,
                "updated — ${callsign ?: ac.hex}" +
                    (ac.dst?.let { " · %.1f km".format(Units.nmToKm(it)) } ?: ""))

            publish(buildState(photoPath, photoCredit, logoPath, mfrLogoPath,
                refreshing = false))
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
            failOpenStages()
            // Keep showing last good data, but surface the failure icon
            val (prev, next) = WidgetStateStore.update(context) {
                it.copy(errorCount = consecutiveErrors, refreshing = false,
                    refreshStage = null, stages = stageList(), pausedReason = null,
                    lastError = e.message ?: e.javaClass.simpleName)
            }
            if (prev.renderKey() != next.renderKey()) AirblockWidget().updateAll(context)
        }
    }

    private data class LegGeometry(val progress: Float? = null, val etaEpochMs: Long? = null)

    /** Journey progress + ETA from great-circle geometry and ground speed. */
    private fun legGeometry(
        leg: Pair<com.sam.airblock.data.RouteAirport, com.sam.airblock.data.RouteAirport>?,
        planeLat: Double,
        planeLon: Double,
        gsKts: Double?,
    ): LegGeometry {
        val origin = leg?.first
        val dest = leg?.second
        if (origin?.lat == null || origin.lon == null ||
            dest?.lat == null || dest.lon == null
        ) return LegGeometry()
        var progress: Float? = null
        var etaEpochMs: Long? = null
        val fromOrigin = Units.haversineKm(origin.lat, origin.lon, planeLat, planeLon)
        val toDest = Units.haversineKm(planeLat, planeLon, dest.lat, dest.lon)
        if (fromOrigin + toDest > 1.0) {
            progress = (fromOrigin / (fromOrigin + toDest)).toFloat()
        }
        val gsKmh = (gsKts ?: 0.0) * Units.NM_TO_KM
        if (gsKmh > 150 && toDest > 2.0) {
            etaEpochMs = System.currentTimeMillis() + (toDest / gsKmh * 3_600_000).toLong()
        }
        return LegGeometry(progress, etaEpochMs)
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
