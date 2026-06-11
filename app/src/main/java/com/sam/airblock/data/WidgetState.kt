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
    /** ADS-B emitter category ("A3", "A7"…) — drives the silhouette fallback. */
    val category: String? = null,
    val squawkAlert: String? = null,   // "7700 EMERGENCY" or null
    val originIata: String? = null,
    val originCity: String? = null,
    val originFlag: String? = null,
    val destIata: String? = null,
    val destCity: String? = null,
    val destFlag: String? = null,
    val photoPath: String? = null,
    val photoCredit: String? = null,
    /** Cached airline-logo PNG for the operating airline, when known. */
    val airlineLogoPath: String? = null,
    /** Display name of the operating airline, e.g. "Wizz Air". */
    val airlineName: String? = null,
    /** Cached manufacturer-wordmark PNG (BOEING, AIRBUS…), when known. */
    val manufacturerLogoPath: String? = null,
    /** Cached model-specific logo (A380, 787 Dreamliner…), when the model has one. */
    val modelLogoPath: String? = null,
    val updatedAt: Long = 0L,
    // Non-standard conditions surfaced as a top-right status icon on the widget
    val refreshing: Boolean = false,
    /**
     * What the refresh is doing right now ("Getting location…"), shown only by
     * the in-app status card. NOT part of renderKey — the widget must not
     * redraw three times per tick for text it doesn't display.
     */
    val refreshStage: String? = null,
    /**
     * Per-step progress of the current refresh (location → aircraft → route →
     * media), driving the expanded checklist in the in-app status card.
     * Like [refreshStage], deliberately NOT part of renderKey.
     */
    val stages: List<Stage> = emptyList(),
    val pausedReason: String? = null,  // e.g. "battery saver"
    val errorCount: Int = 0,           // consecutive failed refreshes
    val lastError: String? = null,     // human-readable cause of the last failure
    /** Epoch ms after which this data counts as stale (schedule-aware). */
    val staleAfterMs: Long = 0L,
    /** Human label of the active refresh schedule, e.g. "wifi · 10 min". */
    val modeLabel: String? = null,
) {
    enum class Status { OK, NO_AIRCRAFT, NO_LOCATION, NO_DATA, ERROR }

    /** One step of a refresh, as shown in the status card's checklist. */
    @Serializable
    data class Stage(
        val key: String,   // "location" | "aircraft" | "route" | "media"
        val label: String, // "Location", "Nearest aircraft", …
        val state: String, // PENDING | RUNNING | DONE | FAILED
    ) {
        companion object {
            const val PENDING = "pending"
            const val RUNNING = "running"
            const val DONE = "done"
            const val FAILED = "failed"
        }
    }

    /** Render-relevant identity — used to skip widget redraws when nothing changed. */
    fun renderKey(): String = listOf(
        status, callsign, typeName, altitudeFt, onGround, speedMph,
        mach?.let { "%.2f".format(it) },
        distanceKm?.let { "%.1f".format(it) }, squawkAlert,
        originIata, destIata, photoPath, airlineLogoPath, airlineName,
        manufacturerLogoPath, modelLogoPath,
        routeProgress?.let { (it * 20).toInt() }, etaEpochMs?.let { it / 60_000 },
        specialType, category, registration, modeLabel,
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

    /**
     * Atomic read-transform-write — the service loop, KeepAliveWorker and
     * widget taps all mutate this state concurrently; doing the mutation
     * inside one edit prevents lost flags. Returns previous and new state.
     */
    suspend fun update(
        context: Context,
        transform: (WidgetState) -> WidgetState,
    ): Pair<WidgetState, WidgetState> {
        var prev = WidgetState()
        var next = WidgetState()
        context.airblockStore.edit { p ->
            prev = p[KEY]?.let {
                runCatching { Http.json.decodeFromString<WidgetState>(it) }.getOrNull()
            } ?: WidgetState()
            next = transform(prev)
            p[KEY] = Http.json.encodeToString(WidgetState.serializer(), next)
        }
        return prev to next
    }
}
