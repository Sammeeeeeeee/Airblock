package com.sam.airblock.engine

import android.content.Context
import android.util.Log
import androidx.glance.appwidget.updateAll
import com.sam.airblock.data.AdsbApi
import com.sam.airblock.data.PhotoRepo
import com.sam.airblock.data.RouteResult
import com.sam.airblock.data.SettingsStore
import com.sam.airblock.data.WidgetState
import com.sam.airblock.data.WidgetStateStore
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
    private val api = AdsbApi()

    // Per-flight caches: one route lookup per callsign, one photo per hex
    private var cachedRoute: Pair<String, RouteResult?>? = null

    var consecutiveErrors = 0
        private set

    suspend fun tick() {
        val settings = SettingsStore.read(context)
        val fix = location.currentFix()
        if (fix == null) {
            Log.d(TAG, "tick: no location fix")
            publish(WidgetState(status = WidgetState.Status.NO_LOCATION,
                updatedAt = System.currentTimeMillis()))
            return
        }
        try {
            val ac = api.closest(fix.lat, fix.lon, settings.radiusNm)
            consecutiveErrors = 0
            if (ac == null) {
                Log.d(TAG, "tick: no aircraft within ${settings.radiusNm} nm")
                publish(WidgetState(status = WidgetState.Status.NO_AIRCRAFT,
                    updatedAt = System.currentTimeMillis()))
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
            val origin = route?.airports?.firstOrNull()
            val dest = route?.airports?.lastOrNull()?.takeIf { it !== origin }

            val photo = photos.photoFor(ac.hex)

            Log.d(TAG, "tick @%.2f,%.2f: %s dst=%snm route=%s photo=%s".format(
                fix.lat, fix.lon, callsign ?: ac.hex, ac.dst,
                route?.airportCodes ?: "?", photo != null))

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
                distanceKm = ac.dst?.let { Units.nmToKm(it) },
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
            ))
        } catch (e: IOException) {
            consecutiveErrors++
            Log.w(TAG, "tick failed (${consecutiveErrors}x): ${e.message}")
            // Keep showing last good data, but surface the failure icon
            val cur = WidgetStateStore.read(context)
            publish(cur.copy(errorCount = consecutiveErrors, refreshing = false,
                pausedReason = null,
                lastError = e.message ?: e.javaClass.simpleName))
        }
    }

    /** "BOEING 737-900" -> "Boeing 737-900" */
    private fun prettyType(desc: String): String =
        desc.split(" ").joinToString(" ") { w ->
            if (w.any { it.isDigit() }) w else w.lowercase().replaceFirstChar { it.uppercase() }
        }

    private suspend fun publish(state: WidgetState) {
        val previous = WidgetStateStore.read(context)
        WidgetStateStore.write(context, state)
        // Skip the RemoteViews churn when nothing the user can see has changed
        if (previous.renderKey() != state.renderKey()) {
            AirblockWidget().updateAll(context)
        }
    }

    companion object {
        private const val TAG = "Airblock"
    }
}
