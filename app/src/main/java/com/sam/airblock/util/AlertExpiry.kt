package com.sam.airblock.util

/**
 * When an alert clears itself: a set time after its aircraft was last seen,
 * not after the alert was posted — so it stays up while the aircraft is still
 * the nearest one, and goes once it has left.
 *
 * Android does the clearing, not a tick. Each alert is posted with
 * `Notification.Builder.setTimeoutAfter`, which the system enforces with its
 * own exact alarm — so the alert still goes on time with the screen off,
 * another app in front or this process dead, which is exactly when no tick is
 * running to check. Each tick that sees the aircraft again re-posts the alert
 * unchanged, and a re-post restarts the system's countdown from that moment
 * (AOSP: NotificationManagerService.scheduleTimeoutLocked on Android 12–14,
 * TimeToLiveHelper on 15+).
 *
 * Pure functions only (no Android) — the timing rules are unit-tested.
 */
object AlertExpiry {

    /** "Never": no timeout, the alert stays until swiped away. */
    const val NEVER = 0

    const val DEFAULT_MIN = 5

    /**
     * Re-post a showing alert at most this often. Each re-post sends the whole
     * notification (photo included) to the system and the shade redraws it —
     * once a minute is plenty against a countdown measured in minutes, where
     * every 15 s tick would be all cost.
     */
    const val REARM_MS = 60_000L

    /**
     * The `setTimeoutAfter` for an alert whose aircraft was seen just now, or
     * 0 for no timeout. Two allowances on top of the user's [clearAfterMin]:
     *
     *  - Never shorter than [refreshMs], the wait until the next tick. On the
     *    10-minute data-saving plan a 5-minute timer would clear the alert of
     *    an aircraft still circling overhead before the app had looked again.
     *  - Plus [REARM_MS]: sightings inside that window don't re-post, so this
     *    slack is what stops the alert clearing sooner than promised after the
     *    LAST sighting.
     */
    fun timeoutMs(clearAfterMin: Int, refreshMs: Long): Long =
        if (clearAfterMin <= NEVER) 0L
        else maxOf(clearAfterMin * 60_000L, refreshMs) + REARM_MS

    /** True when an alert last posted at [postedAtMs] is due a re-post. */
    fun rearmDue(postedAtMs: Long, nowMs: Long): Boolean = nowMs - postedAtMs >= REARM_MS
}
