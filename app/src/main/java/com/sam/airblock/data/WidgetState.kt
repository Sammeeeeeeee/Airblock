package com.sam.airblock.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable

val Context.airblockStore by preferencesDataStore(name = "airblock")

/** Everything the widget renders, persisted as one JSON blob in DataStore. */
@Serializable
data class WidgetState(
    val status: Status = Status.NO_DATA,
    val callsign: String? = null,
    val typeName: String? = null,      // "BOEING 737-900"
    val typeCode: String? = null,      // "B739"
    val registration: String? = null,
    val hex: String? = null,
    val altitudeFt: Int? = null,
    val onGround: Boolean = false,
    val speedMph: Int? = null,
    val mach: Double? = null,
    val distanceKm: Double? = null,
    /** 0..1 fraction of the current leg already flown, when route is known. */
    val routeProgress: Float? = null,
    /** Estimated arrival at destination (epoch ms), from distance/speed. */
    val etaEpochMs: Long? = null,
    /** Non-standard aircraft type ("Military", "Helicopter", …) or null. */
    val specialType: String? = null,
    val squawkAlert: String? = null,   // "7700 EMERGENCY" or null
    val originIata: String? = null,
    val originCity: String? = null,
    val originFlag: String? = null,
    val destIata: String? = null,
    val destCity: String? = null,
    val destFlag: String? = null,
    val photoPath: String? = null,
    val photoCredit: String? = null,
    val updatedAt: Long = 0L,
    // Non-standard conditions surfaced as a top-right status icon on the widget
    val refreshing: Boolean = false,
    val pausedReason: String? = null,  // e.g. "battery saver"
    val errorCount: Int = 0,           // consecutive failed refreshes
    val lastError: String? = null,     // human-readable cause of the last failure
) {
    enum class Status { OK, NO_AIRCRAFT, NO_LOCATION, NO_DATA, ERROR }

    /** Render-relevant identity — used to skip widget redraws when nothing changed. */
    fun renderKey(): String = listOf(
        status, callsign, typeName, altitudeFt, speedMph,
        mach?.let { "%.2f".format(it) },
        distanceKm?.let { "%.1f".format(it) }, squawkAlert,
        originIata, destIata, photoPath,
        routeProgress?.let { (it * 20).toInt() }, etaEpochMs?.let { it / 60_000 },
        specialType, registration,
        refreshing, pausedReason, errorCount > 0,
    ).joinToString("|")
}

object WidgetStateStore {
    private val KEY = stringPreferencesKey("widget_state")

    suspend fun read(context: Context): WidgetState {
        val raw = context.airblockStore.data.first()[KEY] ?: return WidgetState()
        return runCatching { Http.json.decodeFromString<WidgetState>(raw) }
            .getOrDefault(WidgetState())
    }

    /** Live state stream — used by the in-app status card. */
    fun flow(context: Context): Flow<WidgetState> =
        context.airblockStore.data.map { p ->
            p[KEY]?.let {
                runCatching { Http.json.decodeFromString<WidgetState>(it) }.getOrNull()
            } ?: WidgetState()
        }

    suspend fun write(context: Context, state: WidgetState) {
        val raw = Http.json.encodeToString(WidgetState.serializer(), state)
        context.airblockStore.edit { it[KEY] = raw }
    }
}
