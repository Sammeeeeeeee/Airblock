package com.sam.airblock.util

import kotlinx.serialization.Serializable

/**
 * User-facing alert groups for aircraft notifications. Each group is one
 * settings toggle and one notification channel; declaration order IS the
 * priority order — when an aircraft matches several groups, the first one
 * wins and the notification goes out on that group's channel.
 */
enum class AlertGroup(
    val id: String,
    val channelId: String,
    val label: String,
    /** Notification title, e.g. "Military aircraft overhead". */
    val title: String,
    /** One-line description under the settings toggle. */
    val desc: String,
    /** Included in the default set when alerts are first enabled. */
    val defaultOn: Boolean,
) {
    EMERGENCY("emergency", "alert2_emergency", "Emergency squawk", "Emergency squawk nearby",
        "Squawk 7500 · 7600 · 7700", true),
    WATCHLIST("watchlist", "alert2_watchlist", "Watched aircraft", "Watched aircraft overhead",
        "Specific aircraft you added below", true),
    GOV("gov", "alert2_gov", "Government & VIP", "Government aircraft overhead",
        "Governments, dictator alert, royals, famous people", true),
    MILITARY("military", "alert2_military", "Military", "Military aircraft overhead",
        "Air forces and armed forces worldwide", true),
    SERVICES("services", "alert2_services", "Police, medical & coastguard",
        "Emergency-services aircraft overhead",
        "Police, air ambulance, coastguard, firefighting", true),
    DRONES("drones", "alert2_drones", "Drones & UAV", "Drone overhead",
        "UAVs and drones", true),
    HISTORIC("historic", "alert2_historic", "Historic & interesting",
        "Interesting aircraft overhead",
        "Vintage, distinctive and notable airframes", true),
    OTHER("other", "alert2_other", "Other special", "Special aircraft overhead",
        "Anything else tagged in plane-alert-db", false),
}

/**
 * One user-added aircraft to alert on. Any single field is enough to add an
 * entry; when several are filled, ALL filled fields must match (they describe
 * the same aircraft). Comparison is case-insensitive; registrations also
 * ignore hyphens ("G-ABCD" == "GABCD").
 */
@Serializable
data class WatchEntry(
    val callsign: String? = null,
    val registration: String? = null,
    val hex: String? = null,
) {
    fun label(): String =
        listOfNotNull(callsign, registration, hex)
            .map { it.trim() }.filter { it.isNotEmpty() }
            .joinToString(" · ")
            .ifEmpty { "(empty)" }

    fun isBlank(): Boolean =
        callsign.isNullOrBlank() && registration.isNullOrBlank() && hex.isNullOrBlank()

    fun matches(callsign: String?, registration: String?, hex: String?): Boolean {
        fun norm(s: String?) = s?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }
        fun normReg(s: String?) = norm(s)?.replace("-", "")
        val wc = norm(this.callsign)
        val wr = normReg(this.registration)
        val wh = norm(this.hex)
        if (wc == null && wr == null && wh == null) return false
        if (wc != null && wc != norm(callsign)) return false
        if (wr != null && wr != normReg(registration)) return false
        if (wh != null && wh != norm(hex)) return false
        return true
    }
}

/**
 * Maps an aircraft onto [AlertGroup]s from BOTH data sources: live adsb.lol
 * fields (dbFlags, squawk, emitter category) and the plane-alert-db CSV
 * `Category`. Pure JVM logic — unit-testable, no Android deps.
 *
 * CSV categories resolve exact-name seeds first, then a keyword fallback, and
 * anything still unmatched lands in [AlertGroup.OTHER] — the plane-alert-db
 * category list changes over time, so unknown new categories must never be
 * silently dropped.
 */
object AlertGroups {

    val defaultGroupIds: Set<String> =
        AlertGroup.entries.filter { it.defaultOn }.map { it.id }.toSet()

    // Seed names are the categories plane-alert-db actually uses today (see
    // its README table); the keyword fallback catches renames and additions.
    private val SEEDS: Map<String, AlertGroup> = buildMap {
        fun all(g: AlertGroup, vararg names: String) =
            names.forEach { put(it.lowercase(), g) }
        all(AlertGroup.GOV, "Governments", "Dictator Alert", "Royal Aircraft", "Oligarch",
            "Radiohead", "Don't you know who I am?", "Quango")
        all(AlertGroup.MILITARY, "Military", "RAF", "USAF", "GAF", "Military Police",
            "Special Forces", "Toy Soldiers", "Ukraine", "Da Comrade", "Other Air Forces",
            "Other Navies", "United States Navy", "United States Marine Corps",
            "Royal Navy Fleet Air Arm", "Army Air Corps", "Gunship", "Zoomies", "Oxcart",
            "Hired Gun")
        all(AlertGroup.SERVICES, "Police Forces", "UK National Police Air Service",
            "Flying Doctors", "Coastguard", "Aerial Firefighter", "CAP", "Nuclear")
        all(AlertGroup.DRONES, "UAV")
        all(AlertGroup.HISTORIC, "Historic", "Distinctive", "Joe Cool", "Vanity Plate",
            "Aerobatic Teams", "Dogs with Jobs", "Jump Johnny Jump",
            "Ptolemy would be proud", "Gas Bags")
    }

    // Checked in group-priority order; seeds win over keywords so e.g.
    // "Military Police" stays MILITARY instead of keyword-matching SERVICES.
    private val KEYWORDS: List<Pair<AlertGroup, List<String>>> = listOf(
        AlertGroup.GOV to listOf("gov", "dictator", "royal", "head of state", "vip"),
        AlertGroup.MILITARY to listOf("military", "air force", "navy", "army", "nato", "marines"),
        AlertGroup.SERVICES to listOf("police", "ambulance", "doctor", "medic",
            "coastguard", "rescue", "firefight"),
        AlertGroup.DRONES to listOf("uav", "drone", "rpas"),
        AlertGroup.HISTORIC to listOf("historic", "vintage", "distinctive", "warbird"),
    )

    /**
     * Short plain-English replacements for plane-alert-db's in-joke category
     * names ("Da Comrade", "Radiohead"…). Self-descriptive names (Aerial
     * Firefighter, Coastguard…) are not listed — they pass through as-is.
     */
    private val DISPLAY_NAMES: Map<String, String> = mapOf(
        "as seen on tv" to "Companies & brands",
        "big hello" to "Large helicopters",
        "bizjets" to "Business jets",
        "cap" to "Civil Air Patrol",
        "climate crisis" to "Oil companies & large bizjets",
        "da comrade" to "Russian & Soviet aircraft",
        "distinctive" to "Special airframes",
        "dogs with jobs" to "Special-role aircraft",
        "don't you know who i am?" to "Famous people",
        "flying doctors" to "Air ambulance",
        "football" to "Sports teams",
        "gaf" to "German Air Force",
        "gas bags" to "Balloons & airships",
        "hired gun" to "Military contractors",
        "jesus he knows me" to "Religious organisations",
        "joe cool" to "Cool aircraft",
        "jump johnny jump" to "DH Chipmunk trainers",
        "nuclear" to "Nuclear response teams",
        "oligarch" to "Oligarchs",
        "oxcart" to "Intelligence aircraft",
        "perfectly serviceable aircraft" to "Skydiving planes",
        "pia" to "Private ICAO address",
        "police forces" to "Police",
        "ptolemy would be proud" to "Survey & mapping",
        "quango" to "NATO, UN & similar",
        "radiohead" to "Head-of-state transport",
        "raf" to "Royal Air Force",
        "royal aircraft" to "UK Royal Family",
        "toy soldiers" to "Armies worldwide",
        "uav" to "Drones",
        "uk national police air service" to "UK police air service",
        "ukraine" to "Ukrainian aircraft",
        "united states marine corps" to "US Marine Corps",
        "united states navy" to "US Navy",
        "usaf" to "US Air Force",
        "vanity plate" to "Distinctive registrations",
        "watch me fly" to "Flying schools",
        "you came here in that thing?" to "Microlights & tiny aircraft",
        "zoomies" to "Fast jets",
    )

    /**
     * What to SHOW for a category — the short plain-English name, or the raw
     * name when it's already descriptive. Used everywhere a category surfaces:
     * the settings picker, the notification reason line and the widget tag.
     */
    fun displayName(category: String): String =
        DISPLAY_NAMES[category.trim().lowercase()] ?: category.trim()

    /** plane-alert-db `Category` → group. Never null: unmatched → [AlertGroup.OTHER]. */
    fun groupForCategory(category: String): AlertGroup {
        val c = category.trim().lowercase()
        if (c.isEmpty()) return AlertGroup.OTHER
        SEEDS[c]?.let { return it }
        for ((g, words) in KEYWORDS) if (words.any { c.contains(it) }) return g
        return AlertGroup.OTHER
    }

    /**
     * All groups this aircraft matches, in priority order (first = the channel
     * to notify on). [watch] is whether a [WatchEntry] matched. [includeCategories]/
     * [excludeCategories] are the per-CSV-category tri-state overrides: Always
     * (include even when the mapped group is off) / Never (exclude even when
     * it's on). They apply ONLY to the CSV rule — live-data rules
     * (dbFlags/squawk/emitter) and the watchlist follow the group toggles.
     */
    fun match(
        dbFlags: Long?,
        squawk: String?,
        emitterCategory: String?,
        csvCategory: String?,
        enabledGroups: Set<String>,
        includeCategories: Set<String> = emptySet(),
        excludeCategories: Set<String> = emptySet(),
        watch: Boolean = false,
    ): List<AlertGroup> {
        val out = sortedSetOf<AlertGroup>()

        if (Squawk.emergencyLabel(squawk) != null &&
            AlertGroup.EMERGENCY.id in enabledGroups
        ) out.add(AlertGroup.EMERGENCY)

        if (watch && AlertGroup.WATCHLIST.id in enabledGroups) out.add(AlertGroup.WATCHLIST)

        if (dbFlags != null && (dbFlags and 1L) != 0L &&
            AlertGroup.MILITARY.id in enabledGroups
        ) out.add(AlertGroup.MILITARY)
        if (dbFlags != null && (dbFlags and 2L) != 0L &&
            AlertGroup.HISTORIC.id in enabledGroups
        ) out.add(AlertGroup.HISTORIC)

        when (emitterCategory?.trim()?.uppercase()) {
            "B6" -> if (AlertGroup.DRONES.id in enabledGroups) out.add(AlertGroup.DRONES)
            "B7" -> if (AlertGroup.OTHER.id in enabledGroups) out.add(AlertGroup.OTHER)
        }

        val cat = csvCategory?.trim()?.takeIf { it.isNotEmpty() }
        if (cat != null) {
            val g = groupForCategory(cat)
            val included = when {
                excludeCategories.any { it.equals(cat, ignoreCase = true) } -> false
                includeCategories.any { it.equals(cat, ignoreCase = true) } -> true
                else -> g.id in enabledGroups
            }
            if (included) out.add(g)
        }
        return out.toList()
    }
}
