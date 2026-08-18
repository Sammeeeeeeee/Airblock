package com.sam.airblock.util

/**
 * The single place that decides WHICH plane-alert-db field names an aircraft.
 *
 * The database gives every airframe two overlapping labels — a `Category`
 * ("Special Forces") and up to three `Tag`s ("Surveillance") — and the widget
 * and the notification used to pick different ones, so the same aircraft was
 * SURVEILLANCE on the home screen and "Special Forces" in the shade.
 *
 * The rule now: **the category leads everywhere.** It is the label the settings
 * screen lists and the per-category Auto/On/Off switches control, so whatever a
 * user reads on a notification or the widget is a thing they can go and find.
 * The tag rides along as extra colour where there is room for it, never on its
 * own unless there is no category at all.
 */
object AlertLabels {

    /**
     * The name to lead with: the database category, else a tag, else what the
     * live ADS-B data alone can tell us ("Military" / "Drone"). All three
     * arguments are expected to be display-ready (see [AlertGroups.displayName]).
     */
    fun primary(category: String?, tag: String?, specialType: String?): String? =
        category?.takeIf { it.isNotBlank() }
            ?: tag?.takeIf { it.isNotBlank() }
            ?: specialType?.takeIf { it.isNotBlank() }

    /**
     * The supporting tag, or null when it would just repeat [primary] — several
     * rows carry the same word in both columns, and "SPECIAL FORCES · Special
     * Forces" helps nobody.
     */
    fun secondary(primary: String?, tag: String?): String? =
        tag?.trim()?.takeIf { it.isNotEmpty() && !it.equals(primary?.trim(), ignoreCase = true) }
}
