package com.sam.airblock.engine

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Location strategy (in cost order, cheapest first):
 *  1. The system's cached fused fix — free to read, produced by whatever app
 *     last asked for location. Used as-is while fresher than [STALE_MS].
 *  2. One balanced-power fix (Wi-Fi/cell preferred, GPS last resort) at most
 *     once per [STALE_MS] window.
 *  3. The last fix this app ever saw, kept in memory.
 */
class LocationProvider(private val context: Context) {

    private val fused = LocationServices.getFusedLocationProviderClient(context)
    private var lastActiveRequest = 0L
    private var lastGood: Pair<Double, Double>? = null

    data class Fix(val lat: Double, val lon: Double)

    suspend fun currentFix(): Fix? {
        if (hasPermission()) {
            // 1. Free cached fix
            val cached = lastKnown()
            val now = System.currentTimeMillis()
            if (cached != null && now - cached.time < STALE_MS) {
                lastGood = cached.latitude to cached.longitude
                return Fix(cached.latitude, cached.longitude)
            }
            // 2. One cheap active fix, rate-limited
            if (now - lastActiveRequest > STALE_MS) {
                lastActiveRequest = now
                val fresh = withTimeoutOrNull(10_000) { balancedFix() }
                if (fresh != null) {
                    lastGood = fresh.latitude to fresh.longitude
                    return Fix(fresh.latitude, fresh.longitude)
                }
            }
            // Stale cache still beats nothing for a 50 nm search radius
            cached?.let {
                lastGood = it.latitude to it.longitude
                return Fix(it.latitude, it.longitude)
            }
        }
        // 3. Whatever this app last saw
        lastGood?.let { return Fix(it.first, it.second) }
        return null
    }

    fun hasPermission(): Boolean = ContextCompat.checkSelfPermission(
        context, Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED

    @Suppress("MissingPermission")
    private suspend fun lastKnown(): Location? = suspendCancellableCoroutine { cont ->
        fused.lastLocation
            .addOnSuccessListener { cont.resume(it) }
            .addOnFailureListener { cont.resume(null) }
    }

    @Suppress("MissingPermission")
    private suspend fun balancedFix(): Location? = suspendCancellableCoroutine { cont ->
        // Cancellable token: when our 10s timeout fires, the platform request
        // must stop too instead of running the radio for up to 30 more seconds
        val cts = com.google.android.gms.tasks.CancellationTokenSource()
        fused.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, cts.token)
            .addOnSuccessListener { cont.resume(it) }
            .addOnFailureListener { cont.resume(null) }
        cont.invokeOnCancellation { cts.cancel() }
    }

    companion object {
        private const val STALE_MS = 10L * 60 * 1000
    }
}
