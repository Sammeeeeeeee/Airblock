package com.sam.airblock.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
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
    val distanceKm: Double? = null,
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
) {
    enum class Status { OK, NO_AIRCRAFT, NO_LOCATION, NO_DATA, ERROR }

    /** Render-relevant identity — used to skip widget redraws when nothing changed. */
    fun renderKey(): String = listOf(
        status, callsign, typeName, altitudeFt, speedMph,
        distanceKm?.let { "%.1f".format(it) }, squawkAlert,
        originIata, destIata, photoPath,
    ).joinToString("|")
}

object WidgetStateStore {
    private val KEY = stringPreferencesKey("widget_state")

    suspend fun read(context: Context): WidgetState {
        val raw = context.airblockStore.data.first()[KEY] ?: return WidgetState()
        return runCatching { Http.json.decodeFromString<WidgetState>(raw) }
            .getOrDefault(WidgetState())
    }

    suspend fun write(context: Context, state: WidgetState) {
        val raw = Http.json.encodeToString(WidgetState.serializer(), state)
        context.airblockStore.edit { it[KEY] = raw }
    }
}
