package com.sam.airblock.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.sam.airblock.util.AlertExpiry
import com.sam.airblock.util.AlertGroups
import com.sam.airblock.util.WatchEntry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable

/**
 * Preferences for aircraft-alert notifications. Which groups fire is decided
 * by [com.sam.airblock.util.AlertGroups.match]; this store only holds the
 * user's choices plus the anti-spam state.
 */
data class NotifyPrefs(
    val enabled: Boolean = false,
    /** Enabled [com.sam.airblock.util.AlertGroup] ids. */
    val groups: Set<String> = AlertGroups.defaultGroupIds,
    /** Raw plane-alert-db categories forced ON regardless of group toggles. */
    val includeCategories: Set<String> = emptySet(),
    /** Raw plane-alert-db categories forced OFF regardless of group toggles. */
    val excludeCategories: Set<String> = emptySet(),
    /** Muted airframes: ICAO hex (uppercase) → friendly label for settings. */
    val muted: Map<String, String> = emptyMap(),
    /** Specific aircraft to always alert on (the Watched aircraft group). */
    val watch: List<WatchEntry> = emptyList(),
    /**
     * Minutes an alert stays up after its aircraft was last seen, or
     * [AlertExpiry.NEVER] to keep it until swiped (see [AlertExpiry]).
     */
    val clearAfterMin: Int = AlertExpiry.DEFAULT_MIN,
    /**
     * Only alert for aircraft within this many km (ground distance), or
     * [NotifyStore.ANY_RANGE]. The nearest aircraft can be ~90 km away on the
     * default search radius — far too far to look up and see.
     */
    val alertRangeKm: Int = NotifyStore.DEFAULT_RANGE_KM,
)

/** Per-category tri-state shown in the advanced picker. */
enum class CategoryChoice { DEFAULT, ALWAYS, NEVER }

/**
 * Anti-spam state, persisted so process death can't cause a re-notify:
 * no re-fire while the same airframe stays the closest aircraft, and a
 * cooldown once it leaves and comes back.
 */
@Serializable
data class NotifyState(
    /** Hex of the last aircraft notified about. */
    val sessionHex: String? = null,
    /** Group ids already fired for [sessionHex] while it stays closest. */
    val sessionGroups: Set<String> = emptySet(),
    /** hex → epoch ms of its last notification (pruned on write). */
    val recent: Map<String, Long> = emptyMap(),
)

object NotifyStore {
    /** Re-notify the same airframe at most once per this window. */
    const val COOLDOWN_MS = 45L * 60 * 1000

    /** [NotifyPrefs.alertRangeKm] value meaning "no limit". */
    const val ANY_RANGE = 0

    const val DEFAULT_RANGE_KM = 25

    /** Don't re-stamp a one-off watch's "last seen" more often than this. */
    private const val SEEN_WRITE_MS = 60L * 1000

    private val ENABLED = booleanPreferencesKey("notify_enabled")
    private val GROUPS = stringSetPreferencesKey("notify_groups")
    private val CAT_INCLUDE = stringSetPreferencesKey("notify_cat_include")
    private val CAT_EXCLUDE = stringSetPreferencesKey("notify_cat_exclude")
    private val MUTED = stringPreferencesKey("notify_muted")
    private val WATCH = stringPreferencesKey("notify_watch")
    private val CLEAR_MIN = intPreferencesKey("notify_clear_min")
    private val RANGE_KM = intPreferencesKey("notify_range_km")
    private val STATE = stringPreferencesKey("notify_state")

    private fun decode(p: Preferences) = NotifyPrefs(
        enabled = p[ENABLED] ?: false,
        groups = p[GROUPS] ?: AlertGroups.defaultGroupIds,
        includeCategories = p[CAT_INCLUDE] ?: emptySet(),
        excludeCategories = p[CAT_EXCLUDE] ?: emptySet(),
        muted = p[MUTED]?.let {
            runCatching { Http.json.decodeFromString<Map<String, String>>(it) }.getOrNull()
        } ?: emptyMap(),
        watch = p[WATCH]?.let {
            runCatching { Http.json.decodeFromString<List<WatchEntry>>(it) }.getOrNull()
        } ?: emptyList(),
        clearAfterMin = p[CLEAR_MIN] ?: AlertExpiry.DEFAULT_MIN,
        alertRangeKm = p[RANGE_KM] ?: DEFAULT_RANGE_KM,
    )

    suspend fun read(context: Context): NotifyPrefs =
        decode(context.airblockStore.data.first())

    fun flow(context: Context): Flow<NotifyPrefs> =
        context.airblockStore.data.map { decode(it) }

    suspend fun setEnabled(context: Context, on: Boolean) {
        context.airblockStore.edit { it[ENABLED] = on }
    }

    suspend fun setClearAfter(context: Context, minutes: Int) {
        context.airblockStore.edit { it[CLEAR_MIN] = minutes }
    }

    suspend fun setAlertRange(context: Context, km: Int) {
        context.airblockStore.edit { it[RANGE_KM] = km }
    }

    suspend fun setGroup(context: Context, id: String, on: Boolean) {
        context.airblockStore.edit { p ->
            val cur = p[GROUPS] ?: AlertGroups.defaultGroupIds
            p[GROUPS] = if (on) cur + id else cur - id
        }
    }

    fun categoryChoice(prefs: NotifyPrefs, category: String): CategoryChoice = when {
        prefs.excludeCategories.any { it.equals(category, ignoreCase = true) } ->
            CategoryChoice.NEVER
        prefs.includeCategories.any { it.equals(category, ignoreCase = true) } ->
            CategoryChoice.ALWAYS
        else -> CategoryChoice.DEFAULT
    }

    suspend fun setCategoryChoice(context: Context, category: String, choice: CategoryChoice) {
        context.airblockStore.edit { p ->
            fun without(s: Set<String>) =
                s.filterNot { it.equals(category, ignoreCase = true) }.toSet()
            val inc = without(p[CAT_INCLUDE] ?: emptySet())
            val exc = without(p[CAT_EXCLUDE] ?: emptySet())
            p[CAT_INCLUDE] = if (choice == CategoryChoice.ALWAYS) inc + category else inc
            p[CAT_EXCLUDE] = if (choice == CategoryChoice.NEVER) exc + category else exc
        }
    }

    suspend fun mute(context: Context, hex: String, label: String) {
        editMuted(context) { it + (hex.uppercase() to label) }
    }

    suspend fun unmute(context: Context, hex: String) {
        editMuted(context) { it - hex.uppercase() }
    }

    private suspend fun editMuted(
        context: Context,
        transform: (Map<String, String>) -> Map<String, String>,
    ) {
        context.airblockStore.edit { p ->
            val cur = p[MUTED]?.let {
                runCatching { Http.json.decodeFromString<Map<String, String>>(it) }.getOrNull()
            } ?: emptyMap()
            p[MUTED] = Http.json.encodeToString(
                kotlinx.serialization.serializer<Map<String, String>>(), transform(cur))
        }
    }

    suspend fun addWatch(context: Context, entry: WatchEntry) {
        if (entry.isBlank()) return
        editWatch(context) { it + entry }
    }

    /** Removed by the aircraft it names — its note/seen metadata may have moved on. */
    suspend fun removeWatch(context: Context, entry: WatchEntry) {
        editWatch(context) { list -> list.filterNot { it.sameAircraftAs(entry) } }
    }

    /**
     * Stamp a watched aircraft as seen right now, which is what holds a
     * one-off watch open ([WatchEntry.ONCE_EXPIRY_MS] after the LAST sighting).
     *
     * Throttled to [SEEN_WRITE_MS]: the tick runs every 15 s while an aircraft
     * stays overhead, and the expiry window is measured in tens of minutes, so
     * writing a fresh stamp on every tick would be all cost and no accuracy.
     */
    suspend fun markWatchSeen(context: Context, entry: WatchEntry) {
        if (!entry.once) return // permanent watches don't need the bookkeeping
        val now = System.currentTimeMillis()
        if (entry.lastSeenMs != null && now - entry.lastSeenMs < SEEN_WRITE_MS) return
        editWatch(context) { list ->
            list.map { if (it.sameAircraftAs(entry)) it.copy(lastSeenMs = now) else it }
        }
    }

    /** Drop one-off watches whose aircraft hasn't been seen for the window. */
    suspend fun pruneExpiredWatches(
        context: Context,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        val current = read(context).watch
        if (current.none { it.isExpired(nowMs) }) return // no write in the common case
        editWatch(context) { list -> list.filterNot { it.isExpired(nowMs) } }
    }

    private suspend fun editWatch(
        context: Context,
        transform: (List<WatchEntry>) -> List<WatchEntry>,
    ) {
        context.airblockStore.edit { p ->
            val cur = p[WATCH]?.let {
                runCatching { Http.json.decodeFromString<List<WatchEntry>>(it) }.getOrNull()
            } ?: emptyList()
            p[WATCH] = Http.json.encodeToString(
                kotlinx.serialization.serializer<List<WatchEntry>>(), transform(cur))
        }
    }

    // ------------------------------------------------------ anti-spam state

    suspend fun readState(context: Context): NotifyState =
        context.airblockStore.data.first()[STATE]?.let {
            runCatching { Http.json.decodeFromString<NotifyState>(it) }.getOrNull()
        } ?: NotifyState()

    /**
     * Record that [hex] was just notified on group [groupId]. Keeps the
     * session's fired-group set when the same airframe notifies again
     * (e.g. an emergency squawk appearing on an already-alerted military
     * plane); a different airframe starts a fresh session.
     */
    suspend fun recordNotified(context: Context, hex: String, groupId: String) {
        val now = System.currentTimeMillis()
        context.airblockStore.edit { p ->
            val cur = p[STATE]?.let {
                runCatching { Http.json.decodeFromString<NotifyState>(it) }.getOrNull()
            } ?: NotifyState()
            val h = hex.uppercase()
            val groups = if (cur.sessionHex == h) cur.sessionGroups + groupId else setOf(groupId)
            val recent = cur.recent.filterValues { now - it < 2 * COOLDOWN_MS } + (h to now)
            p[STATE] = Http.json.encodeToString(
                NotifyState.serializer(),
                NotifyState(sessionHex = h, sessionGroups = groups, recent = recent))
        }
    }
}
