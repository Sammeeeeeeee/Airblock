package com.sam.airblock.engine

import android.content.Context
import android.util.Log
import androidx.glance.appwidget.updateAll
import com.sam.airblock.data.AdsbApi
import com.sam.airblock.data.AeroApi
import com.sam.airblock.data.AeroStore
import com.sam.airblock.data.AeroTimes
import com.sam.airblock.data.AirlineLogoRepo
import com.sam.airblock.data.EventLog
import com.sam.airblock.data.ManufacturerLogoRepo
import com.sam.airblock.data.NetMode
import com.sam.airblock.data.PhotoRepo
import com.sam.airblock.data.PlaneAlertRepo
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
import kotlinx.coroutines.launch
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
    private val planeAlerts = PlaneAlertRepo(context)
    private val gates = Gates(context)
    private val api = AdsbApi()
    private val aeroApi = AeroApi(context)

    // Per-flight caches: one route lookup per callsign, one photo per hex
    private var cachedRoute: Pair<String, RouteResult?>? = null
    // One AeroAPI times lookup per callsign — the billable call is made at most
    // once per distinct flight (see also the timesAreReal guard below)
    private var cachedAero: Pair<String, AeroTimes?>? = null

    var consecutiveErrors = 0
        private set

    /** Mutable per-tick checklist, persisted on every transition. */
    private val stageOrder =
        listOf("location", "aircraft", "route", "schedule", "photo", "logos")
    private var stages = linkedMapOf<String, WidgetState.Stage>()

    /** The "schedule" (AeroAPI) step is only listed when the feature is on. */
    private fun resetStages(includeSchedule: Boolean) {
        stages = linkedMapOf(
            "location" to WidgetState.Stage("location", "Location", WidgetState.Stage.PENDING),
            "aircraft" to WidgetState.Stage("aircraft", "Nearest aircraft", WidgetState.Stage.PENDING),
            "route" to WidgetState.Stage("route", "Route", WidgetState.Stage.PENDING),
        )
        if (includeSchedule) stages["schedule"] =
            WidgetState.Stage("schedule", "Schedule (AeroAPI)", WidgetState.Stage.PENDING)
        stages["photo"] = WidgetState.Stage("photo", "Photo", WidgetState.Stage.PENDING)
        stages["logos"] = WidgetState.Stage("logos", "Logos", WidgetState.Stage.PENDING)
    }

    private fun stageList() = stageOrder.mapNotNull { stages[it] }

    /**
     * Record a stage transition and persist it. The widget's renderKey ignores
     * stage/checklist fields, so these writes reach the in-app status card via
     * the DataStore flow with zero RemoteViews churn.
     */
    private suspend fun setStage(key: String, state: String, label: String? = null,
        stageText: String? = null, cached: Boolean = false) {
        stages[key]?.let {
            stages[key] = it.copy(state = state, label = label ?: it.label, cached = cached)
        }
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
        val aeroOn = AeroStore.read(context).enabled
        resetStages(includeSchedule = aeroOn)
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
            setStage("location", WidgetState.Stage.DONE, cached = fix.cached)
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
            // Local hex lookup against the cached plane-alert-db — no network
            val alert = planeAlerts.lookup(ac.hex)

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
                // GROUND distance (great-circle over coordinates): a plane
                // 0.5 km directly overhead is 0 km away, not 0.5 — the API's
                // dst is slant range and only serves as fallback
                distanceKm = if (ac.lat != null && ac.lon != null)
                    Units.haversineKm(fix.lat, fix.lon, ac.lat, ac.lon)
                else ac.dst?.let { Units.nmToKm(it) },
                routeProgress = geo.progress
                    ?: prev.routeProgress.takeIf { sameFlight && leg == null },
                etaEpochMs = geo.etaEpochMs
                    ?: prev.etaEpochMs.takeIf { sameFlight && leg == null },
                // Real AeroAPI times persist across ticks of the same flight;
                // phase 2 fills them in when enabled. The flight number falls
                // back to the callsign's numeric part so it shows even with
                // AeroAPI off (AeroAPI then upgrades it to the IATA number).
                schedDepEpochMs = prev.schedDepEpochMs.takeIf { sameFlight },
                schedArrEpochMs = prev.schedArrEpochMs.takeIf { sameFlight },
                actualDepEpochMs = prev.actualDepEpochMs.takeIf { sameFlight },
                arrDelayMin = prev.arrDelayMin.takeIf { sameFlight },
                flightNumber = flightNumberFromCallsign(callsign)
                    ?: prev.flightNumber.takeIf { sameFlight },
                schedDepLocal = prev.schedDepLocal.takeIf { sameFlight },
                schedArrLocal = prev.schedArrLocal.takeIf { sameFlight },
                timesAreReal = prev.timesAreReal && sameFlight,
                specialType = SpecialType.classify(ac.category, ac.dbFlags),
                category = ac.category,
                alertTag = alert?.tags?.firstOrNull(),
                alertCategory = alert?.category,
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
            if (sameType && prev.modelLogoPath != null) {
                updateAndRender { it.copy(modelLogoPath = prev.modelLogoPath) }
            }

            // ---- Phase 2: route, photo and logos IN PARALLEL ---------------
            // Each result lands on the widget the moment it arrives — no
            // result waits behind a slower sibling.
            kotlinx.coroutines.coroutineScope {
                launch {
                    // Route from adsb.lol first; whether it yielded a usable
                    // origin/destination decides if we then call AeroAPI at all.
                    val routeKnown = when {
                        callsign == null -> {
                            setStage("route", WidgetState.Stage.DONE,
                                label = "Route (no callsign)")
                            false
                        }
                        routeCached -> {
                            setStage("route", WidgetState.Stage.DONE, cached = true)
                            (cachedRoute?.second?.airports?.size ?: 0) >= 2
                        }
                        else -> {
                            setStage("route", WidgetState.Stage.RUNNING)
                            try {
                                // Cache only definitive answers (incl. a genuine
                                // 404 -> null); transient failures retry next tick
                                route = api.route(callsign).also {
                                    cachedRoute = callsign to it
                                    if (it == null) Log.d(TAG, "route: none recorded for $callsign")
                                }
                                leg = pickLeg(route?.airports.orEmpty(), planeLat, planeLon)
                                geo = legGeometry(leg, planeLat, planeLon, ac.gs)
                                val l = leg
                                val g = geo
                                updateAndRender { st ->
                                    st.copy(
                                        originIata = l?.first?.iata ?: st.originIata,
                                        originCity = l?.first?.location ?: st.originCity,
                                        originFlag = l?.first?.let { Units.flagEmoji(it.countryIso2) }
                                            ?: st.originFlag,
                                        destIata = l?.second?.iata ?: st.destIata,
                                        destCity = l?.second?.location ?: st.destCity,
                                        destFlag = l?.second?.let { Units.flagEmoji(it.countryIso2) }
                                            ?: st.destFlag,
                                        routeProgress = g.progress ?: st.routeProgress,
                                        etaEpochMs = g.etaEpochMs ?: st.etaEpochMs,
                                    )
                                }
                                setStage("route", WidgetState.Stage.DONE)
                                (route?.airports?.size ?: 0) >= 2
                            } catch (e: IOException) {
                                Log.w(TAG, "route lookup failed for $callsign: $e")
                                setStage("route", WidgetState.Stage.FAILED)
                                false
                            }
                        }
                    }
                    // AeroAPI runs ONLY after a successful adsb.lol route — no
                    // route means we don't bother (and don't spend a request)
                    if (aeroOn) enrichWithRealTimes(
                        callsign, prev.timesAreReal && sameFlight, log, routeKnown)
                }
                launch {
                    setStage("photo", WidgetState.Stage.RUNNING)
                    val photoWasCached = photos.isCached(ac.hex)
                    val photo = photos.photoFor(ac.hex)
                    if (photo != null) {
                        updateAndRender {
                            it.copy(photoPath = photo.file.absolutePath,
                                photoCredit = photo.photographer)
                        }
                    }
                    setStage("photo", WidgetState.Stage.DONE, cached = photoWasCached)
                }
                launch {
                    setStage("logos", WidgetState.Stage.RUNNING)
                    val logosWereCached = airlineLogos.isCached(callsign) &&
                        manufacturerLogos.isCached(resolvedTypeName) &&
                        manufacturerLogos.isCachedModel(ac.t)
                    val logoPath = airlineLogos.logoFor(callsign)?.absolutePath
                    val mfrLogoPath = manufacturerLogos.logoFor(resolvedTypeName)?.absolutePath
                    val modelLogoPath = manufacturerLogos.logoForModel(ac.t)?.absolutePath
                    updateAndRender {
                        it.copy(
                            airlineLogoPath = logoPath ?: it.airlineLogoPath,
                            manufacturerLogoPath = mfrLogoPath ?: it.manufacturerLogoPath,
                            modelLogoPath = modelLogoPath ?: it.modelLogoPath,
                        )
                    }
                    setStage("logos", WidgetState.Stage.DONE, cached = logosWereCached)
                }
            }

            Log.d(TAG, "tick @%.2f,%.2f: %s dst=%snm route=%s".format(
                fix.lat, fix.lon, callsign ?: ac.hex, ac.dst,
                route?.airportCodes ?: "?"))
            val groundKm = if (ac.lat != null && ac.lon != null)
                Units.haversineKm(fix.lat, fix.lon, ac.lat, ac.lon)
            else ac.dst?.let { Units.nmToKm(it) }
            if (log) EventLog.append(context,
                "updated — ${callsign ?: ac.hex}" +
                    (groundKm?.let { " · %.1f km".format(it) } ?: ""))

            updateAndRender {
                it.copy(refreshing = false, refreshStage = null, stages = stageList())
            }
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

    /**
     * Real times from AeroAPI — the opt-in upgrade over the geometry ETA. Called
     * only after the adsb.lol route resolves: if there is no route ([routeKnown]
     * false) we don't bother and spend nothing. Otherwise strictly bounded — the
     * billable `/flights` call runs at most once per distinct flight (cached by
     * callsign, short-circuited when this tick already carries real times). The
     * free `/account/usage` endpoint is polled at most every [USAGE_REFRESH_MS]
     * to keep the spend authoritative and trip the switch off when it's gone.
     * Drives the "schedule" checklist stage throughout.
     */
    private suspend fun enrichWithRealTimes(
        callsign: String?, alreadyReal: Boolean, log: Boolean, routeKnown: Boolean,
    ) {
        if (!routeKnown || callsign == null) {
            setStage("schedule", WidgetState.Stage.DONE, label = "Schedule — no route")
            return
        }
        if (alreadyReal) {
            setStage("schedule", WidgetState.Stage.DONE, cached = true)
            return
        }
        var aero = AeroStore.read(context)
        if (!aero.canQuery()) {
            setStage("schedule", WidgetState.Stage.DONE,
                label = if (aero.exhausted()) "Schedule — quota used" else "Schedule — off")
            return
        }
        setStage("schedule", WidgetState.Stage.RUNNING)
        try {
            if (System.currentTimeMillis() - aero.lastCheckedMs > USAGE_REFRESH_MS) {
                runCatching { aeroApi.usageCostUsd() }
                    .onSuccess { AeroStore.recordUsage(context, it, "Auto-checked") }
                aero = AeroStore.read(context)
                if (!aero.canQuery()) {
                    setStage("schedule", WidgetState.Stage.DONE, label = "Schedule — quota used")
                    return
                }
            }
            val cachedHit = cachedAero?.first == callsign
            val times: AeroTimes? = if (cachedHit) {
                cachedAero?.second
            } else {
                // Count the billable query BEFORE the call so a crash mid-flight
                // can never let us slip past the cap; a hit returns false → stop
                if (!AeroStore.recordRequest(context)) {
                    setStage("schedule", WidgetState.Stage.DONE, label = "Schedule — quota used")
                    return
                }
                aeroApi.times(callsign).also { cachedAero = callsign to it }
            }
            if (times != null) {
                val depLocal = formatLocal(times.schedDepMs, times.originTimeZone)
                val arrLocal = formatLocal(times.schedArrMs, times.destTimeZone)
                val actDepLocal = formatLocal(times.actualDepMs, times.originTimeZone)
                val actArrLocal = formatLocal(times.estArrMs, times.destTimeZone)
                updateAndRender { st ->
                    st.copy(
                        schedDepEpochMs = times.schedDepMs ?: st.schedDepEpochMs,
                        schedArrEpochMs = times.schedArrMs ?: st.schedArrEpochMs,
                        actualDepEpochMs = times.actualDepMs ?: st.actualDepEpochMs,
                        depDelayMin = times.departureDelayMin ?: st.depDelayMin,
                        arrDelayMin = times.arrivalDelayMin ?: st.arrDelayMin,
                        // Prefer the callsign-derived IATA number (always
                        // present); AeroAPI only fills it in when we couldn't
                        flightNumber = st.flightNumber ?: times.flightNumber,
                        schedDepLocal = depLocal ?: st.schedDepLocal,
                        schedArrLocal = arrLocal ?: st.schedArrLocal,
                        actualDepLocal = actDepLocal ?: st.actualDepLocal,
                        actualArrLocal = actArrLocal ?: st.actualArrLocal,
                        timesAreReal = true,
                    )
                }
            }
            // Activity-log line for the AeroAPI step (only on a billable call)
            if (log && !cachedHit) {
                val n = AeroStore.read(context).requestCount
                val delayLabel = times?.arrivalDelayMin?.let {
                    when { it > 2 -> "late ${it}m"; it < -2 -> "early ${-it}m"; else -> "on time" }
                } ?: "no schedule"
                EventLog.append(context,
                    "AeroAPI — ${times?.flightNumber ?: callsign}: $delayLabel " +
                        "(request $n/${AeroStore.HARD_LIMIT})")
            }
            setStage("schedule", WidgetState.Stage.DONE, cached = cachedHit)
        } catch (e: IOException) {
            Log.w(TAG, "aero times failed for $callsign: $e")
            setStage("schedule", WidgetState.Stage.FAILED)
            if (log) EventLog.append(context, "AeroAPI FAILED — $callsign: ${e.message}")
        }
    }

    /**
     * Callsign → IATA flight number from the local airline table: "BAW813C" →
     * "BA813" (IATA designator + the numeric core, dropping any ATC suffix).
     * Falls back to the bare digits when the airline isn't known.
     */
    private fun flightNumberFromCallsign(cs: String?): String? {
        if (cs == null) return null
        val digits = cs.dropWhile { !it.isDigit() }.takeWhile { it.isDigit() }
        if (digits.isEmpty()) return null
        return (AirlineCodes.iataForCallsign(cs) ?: "") + digits
    }

    /** Format an epoch in a given IANA zone as "HH:mm" (UTC if zone unknown). */
    private fun formatLocal(ms: Long?, tz: String?): String? {
        if (ms == null) return null
        return runCatching {
            java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).apply {
                timeZone = tz?.let { java.util.TimeZone.getTimeZone(it) }
                    ?: java.util.TimeZone.getTimeZone("UTC")
            }.format(java.util.Date(ms))
        }.getOrNull()
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

    /** Incremental enrichment: merge into current state, render if visible. */
    private suspend fun updateAndRender(transform: (WidgetState) -> WidgetState) {
        val (previous, next) = WidgetStateStore.update(context, transform)
        if (previous.renderKey() != next.renderKey()) {
            AirblockWidget().updateAll(context)
        }
    }

    companion object {
        private const val TAG = "Airblock"
        /** Re-poll the free /account/usage endpoint at most this often. */
        private const val USAGE_REFRESH_MS = 30L * 60 * 1000
    }
}
