package com.sam.airblock.engine

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.sam.airblock.data.SettingsStore
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Location strategy (in cost order, cheapest first):
 *  1. The system's cached fused fix — free to read, produced by whatever app
 *     last asked for location. Used as-is while fresher than [STALE_MS].
 *  2. One balanced-power fix (Wi-Fi/cell preferred, GPS last resort) at most
 *     once per [STALE_MS] window.
 *  3. The user's saved home coordinates as the final fallback.
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
            (cached ?: null)?.let {
                lastGood = it.latitude to it.longitude
                return Fix(it.latitude, it.longitude)
            }
            lastGood?.let { return Fix(it.first, it.second) }
        }
        // 3. Saved home coordinates
        val s = SettingsStore.read(context)
        if (s.homeLat != null && s.homeLon != null) return Fix(s.homeLat, s.homeLon)
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
        fused.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, null)
            .addOnSuccessListener { cont.resume(it) }
            .addOnFailureListener { cont.resume(null) }
    }

    companion object {
        private const val STALE_MS = 10L * 60 * 1000
    }
}
