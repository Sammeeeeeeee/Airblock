package com.sam.airblock.engine

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Location strategy (in cost order, cheapest first):
 *  1. The system's cached fused fix — free to read, produced by whatever app
 *     last asked for location. Used as-is while fresher than [STALE_MS].
 *  2. One balanced-power fix (Wi-Fi/cell preferred, GPS last resort) at most
 *     once per [STALE_MS] window — requested in the BACKGROUND. A tick must
 *     never stall the visible refresh for up to [FIX_TIMEOUT_MS] when a
 *     slightly stale fix is fine for a 50 nm search radius; the fresh fix
 *     simply serves the next tick.
 *  3. The last fix this app ever saw, kept in memory.
 *
 * Only the very first fix ever (nothing cached anywhere) blocks the tick —
 * there is genuinely nothing to show until it arrives.
 */
class LocationProvider(private val context: Context) {

    private val fused = LocationServices.getFusedLocationProviderClient(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
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
            val mayRequest = now - lastActiveRequest > STALE_MS
            if (cached != null || lastGood != null) {
                // 2a. Stale-but-usable: hand it back immediately and refresh
                // behind the tick's back, rate-limited as before.
                if (mayRequest) {
                    lastActiveRequest = now
                    scope.launch {
                        withTimeoutOrNull(FIX_TIMEOUT_MS) { balancedFix() }?.let {
                            lastGood = it.latitude to it.longitude
                        }
                    }
                }
                cached?.let {
                    lastGood = it.latitude to it.longitude
                    return Fix(it.latitude, it.longitude)
                }
            } else if (mayRequest) {
                // 2b. First fix ever — nothing stale to fall back on, block once
                lastActiveRequest = now
                val fresh = withTimeoutOrNull(FIX_TIMEOUT_MS) { balancedFix() }
                if (fresh != null) {
                    lastGood = fresh.latitude to fresh.longitude
                    return Fix(fresh.latitude, fresh.longitude)
                }
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
        // Cancellable token: when our timeout fires, the platform request
        // must stop too instead of running the radio for up to 30 more seconds
        val cts = com.google.android.gms.tasks.CancellationTokenSource()
        fused.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, cts.token)
            .addOnSuccessListener { cont.resume(it) }
            .addOnFailureListener { cont.resume(null) }
        cont.invokeOnCancellation { cts.cancel() }
    }

    companion object {
        private const val STALE_MS = 10L * 60 * 1000
        private const val FIX_TIMEOUT_MS = 10_000L
    }
}
