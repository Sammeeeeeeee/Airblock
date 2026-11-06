package com.sam.airblock.widget

import com.sam.airblock.data.ManufacturerLogoRepo
import com.sam.airblock.data.WidgetState
import com.sam.airblock.util.AlertLabels
import com.sam.airblock.util.Sky
import com.sam.airblock.util.Units
import kotlin.math.roundToInt

/**
 * What the Flight strip says, as plain data: no Android types, so every
 * wording rule is JVM-testable. [FlightStrip] turns it into pixels.
 *
 * Layout it feeds: the full photo on the left; beside it a card whose first
 * line is the callsign with the model's and the airline's logos, and whose
 * second line is the route ("LHR ∿✈ GLA · 34 min left"); and a stats pill
 * coming off the photo that rolls through [pages].
 */
data class StripContent(
    /** Callsign, or the headline of an empty state ("Clear skies"). */
    val title: String,
    /** The model's logo (or its name when there's no logo), beside the title. */
    val mark: Mark?,
    /** Cached airline-logo PNG, shown as a tile after the mark. */
    val airlineLogo: String?,
    /** Second line, as options longest first: the renderer takes the first that fits. */
    val line2: List<List<Run>>,
    /** The stats pages; empty for empty states. */
    val pages: List<StatPage>,
    val photoPath: String?,
    /** What TalkBack reads for the card. */
    val description: String,
) {
    sealed interface Mark {
        /** [dropMaker]: the logo starts with the maker's wordmark ("AIRBUS A320neo"). */
        data class Logo(val path: String, val dropMaker: Boolean) : Mark
        data class Name(val text: String) : Mark
    }

    sealed interface Run
    /** [code]: airport codes, drawn in the bold hero face. */
    data class Text(val text: String, val tone: Tone = Tone.VARIANT, val code: Boolean = false) : Run
    /** The wavy route line with the plane at [progress] (0..1, null = unknown). */
    data class Route(val progress: Float?) : Run

    enum class Tone { MAIN, VARIANT, ACCENT, POSITIVE, NEGATIVE }
}

/** One page of the stats pill. */
data class StatPage(val kind: Kind, val parts: List<Part>, val description: String) {
    enum class Kind { ALERT, LIVE, LOOK, TRIP, AIRFRAME }

    sealed interface Part
    /** A number or word in bold ("8,000"). */
    data class Value(val text: String) : Part
    /** Its unit, lighter ("ft"). */
    data class Unit(val text: String) : Part
    /** Space between two stats. */
    data object Gap : Part
    data class Arrow(val climb: Boolean) : Part
    /** A small compass dial, its needle on the aircraft's bearing from you. */
    data class Compass(val bearingDeg: Double) : Part
}

/** Container colours for the card. */
enum class StripPalette { NORMAL, TERTIARY, ERROR }

/** The small status dot; NONE when all is well. */
enum class StripStatus { NONE, SPINNER, PAUSED_BATTERY, PAUSED, ERROR, STALE }

object StripContents {

    private const val SEP = " · "

    fun of(s: WidgetState, nowMs: Long): StripContent =
        if (s.status != WidgetState.Status.OK) empty(s) else aircraft(s, nowMs)

    private fun empty(s: WidgetState): StripContent {
        val (title, sub) = when (s.status) {
            WidgetState.Status.NO_AIRCRAFT -> "Clear skies" to "No aircraft nearby"
            WidgetState.Status.NO_LOCATION -> "No location" to "Tap to try again"
            else -> "Airblock" to "Tap to refresh"
        }
        return StripContent(
            title = title, mark = null, airlineLogo = null,
            line2 = listOf(listOf(StripContent.Text(sub))),
            pages = emptyList(), photoPath = null, description = "$title. $sub",
        )
    }

    private fun aircraft(s: WidgetState, nowMs: Long): StripContent {
        val title = s.callsign ?: "—"
        val short = shortType(s.typeName) ?: s.typeCode
        val mark = s.modelLogoPath?.let { StripContent.Mark.Logo(it, dropMaker = dropsMaker(it)) }
            ?: short?.let { StripContent.Mark.Name(it) }
        val line2 = routeLine(s, nowMs) ?: aboutLine(s)
        val first = line2.firstOrNull()?.joinToString("") { run ->
            when (run) {
                is StripContent.Text -> run.text
                is StripContent.Route -> " to "
            }
        }
        return StripContent(
            title = title, mark = mark, airlineLogo = s.airlineLogoPath, line2 = line2,
            pages = pages(s, nowMs), photoPath = s.photoPath,
            description = listOfNotNull(title, s.typeName, s.airlineName, first)
                .joinToString(", "),
        )
    }

    /** "LHR ∿✈ GLA · 34 min left", shorter variants after it. Null without a route. */
    private fun routeLine(s: WidgetState, nowMs: Long): List<List<StripContent.Run>>? {
        if (s.originIata == null && s.destIata == null) return null
        val route = listOf(
            StripContent.Text(s.originIata ?: "?", StripContent.Tone.MAIN, code = true),
            StripContent.Route(s.routeProgress),
            StripContent.Text(s.destIata ?: "?", StripContent.Tone.MAIN, code = true),
        )
        val left = timeLeft(s, nowMs)
        return listOfNotNull(
            left?.let { route + StripContent.Text(SEP + it) },
            left?.let { route + StripContent.Text(SEP + it.removeSuffix(" left")) },
            route,
        ).distinct()
    }

    /** Without a route: what the aircraft is, for whom ("British Airways · G-TTNV"). */
    private fun aboutLine(s: WidgetState): List<List<StripContent.Run>> {
        val special = AlertLabels.primary(s.alertCategory, s.alertTag, s.specialType)
        val owner = special ?: s.airlineName
        return listOf(
            listOfNotNull(owner, s.registration),
            listOfNotNull(owner ?: s.registration),
        ).filter { it.isNotEmpty() }.distinct()
            .map { listOf(StripContent.Text(it.joinToString(SEP))) }
            .ifEmpty { listOf(listOf(StripContent.Text(shortType(s.typeName) ?: "Aircraft"))) }
    }

    // ---- stats pages ------------------------------------------------------

    /** The stats pill, in page order. An emergency squawk leads. */
    fun pages(s: WidgetState, nowMs: Long): List<StatPage> = listOfNotNull(
        alertPage(s), livePage(s), lookPage(s), tripPage(s, nowMs), airframePage(s),
    )

    private fun alertPage(s: WidgetState): StatPage? {
        val label = s.squawkAlert ?: return null
        val code = label.substringBefore(' ')
        val words = label.substringAfter(' ', "").lowercase().replaceFirstChar { it.uppercase() }
        return StatPage(StatPage.Kind.ALERT,
            listOf(StatPage.Value(code), StatPage.Gap, StatPage.Unit(words.ifEmpty { "Squawk" })),
            "Squawk $code, ${words.ifEmpty { "emergency" }}")
    }

    /** "↗ 8,000 ft  297 mph  15 km" — the numbers you glance at. */
    private fun livePage(s: WidgetState): StatPage {
        val parts = mutableListOf<StatPage.Part>()
        val trend = Sky.trend(s.verticalRateFpm)
        if (s.onGround) {
            parts += StatPage.Value("On the ground")
        } else {
            if (trend != 0) parts += StatPage.Arrow(climb = trend > 0)
            parts += StatPage.Value(s.altitudeFt?.let { "%,d".format(it) } ?: "—")
            parts += StatPage.Unit("ft")
        }
        s.speedMph?.let { parts += listOf(StatPage.Gap, StatPage.Value("$it"), StatPage.Unit("mph")) }
        s.distanceKm?.let { km ->
            val (n, u) = Units.formatKm(km).split(' ', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
            parts += listOf(StatPage.Gap, StatPage.Value(n), StatPage.Unit(u))
        }
        val words = listOfNotNull(
            if (s.onGround) "On the ground" else Units.formatAltitude(s.altitudeFt),
            when (trend) { 1 -> "climbing"; -1 -> "descending"; else -> null },
            s.speedMph?.let { Units.formatSpeed(it) },
            s.distanceKm?.let { Units.formatKm(it) + " away" },
        )
        return StatPage(StatPage.Kind.LIVE, parts, words.joinToString(", "))
    }

    /**
     * Where in the sky to find it: a compass needle on the real bearing, then
     * "NW  34° up". Null without a bearing.
     */
    private fun lookPage(s: WidgetState): StatPage? {
        val bearing = s.bearingDeg ?: return null
        val dir = Units.compass8(bearing)
        val deg = s.elevationDeg?.takeIf { !s.onGround }
        if (deg != null && deg >= OVERHEAD_DEG) {
            return StatPage(StatPage.Kind.LOOK,
                listOf(StatPage.Unit("Look"), StatPage.Value("straight up")), "Look straight up")
        }
        val parts = mutableListOf<StatPage.Part>(StatPage.Compass(bearing), StatPage.Value(dir))
        val up = deg?.let(::elevationWords)
        if (up != null) parts += listOf(StatPage.Gap, StatPage.Value(up.substringBefore(' ')), StatPage.Unit("up"))
        return StatPage(StatPage.Kind.LOOK, parts, "Look $dir" + (up?.let { ", $it" } ?: ""))
    }

    /** From here an aircraft is "straight up" rather than in a direction. */
    private const val OVERHEAD_DEG = 80.0

    /** Real flight times when known: "Lands 11:44 · 12 min late". */
    private fun tripPage(s: WidgetState, nowMs: Long): StatPage? {
        val timing = timing(s, nowMs) ?: return null
        val (lands, delay) = timing
        val parts = mutableListOf<StatPage.Part>()
        if (lands != null) parts += listOf(StatPage.Unit("Lands"), StatPage.Value(lands))
        delay?.let { (text, _) ->
            if (parts.isNotEmpty()) parts += StatPage.Gap
            parts += StatPage.Value(text)
        }
        if (parts.isEmpty()) return null
        return StatPage(StatPage.Kind.TRIP, parts,
            listOfNotNull(lands?.let { "Lands $it" }, delay?.first).joinToString(", "))
    }

    /** "G-TTNV  A20N  M0.38": the airframe, for spotters. */
    private fun airframePage(s: WidgetState): StatPage? {
        val parts = mutableListOf<StatPage.Part>()
        s.registration?.let { parts += StatPage.Value(it) }
        s.typeCode?.let { if (parts.isNotEmpty()) parts += StatPage.Gap; parts += StatPage.Unit(it) }
        s.mach?.takeIf { it >= 0.1 }?.let {
            if (parts.isNotEmpty()) parts += StatPage.Gap
            parts += listOf(StatPage.Unit("Mach"), StatPage.Value("%.2f".format(it)))
        }
        if (parts.isEmpty()) return null
        return StatPage(StatPage.Kind.AIRFRAME, parts,
            listOfNotNull(s.registration, s.typeCode).joinToString(", "))
    }

    // ---- wording ----------------------------------------------------------

    /** "34 min left" from real times or the geometry ETA; null when unknown. */
    internal fun timeLeft(s: WidgetState, nowMs: Long): String? {
        val eta = (if (s.timesAreReal) s.schedArrEpochMs?.let { sched ->
            sched + (s.arrDelayMin ?: 0) * 60_000L
        } else null) ?: s.etaEpochMs ?: return null
        val mins = ((eta - nowMs) / 60_000).toInt()
        if (mins !in 0..(24 * 60)) return null
        return minutesLeft(mins)
    }

    /**
     * Real AeroAPI landing time and delay: ("11:44", "12 min late" to tone).
     * Null without real times.
     */
    internal fun timing(s: WidgetState, nowMs: Long): Pair<String?, Pair<String, StripContent.Tone>?>? {
        if (!s.timesAreReal) return null
        val lands = s.actualArrLocal ?: s.schedArrLocal
        val delay = s.arrDelayMin?.let { d ->
            when {
                d > 2 -> "$d min late" to StripContent.Tone.NEGATIVE
                d < -2 -> "${-d} min early" to StripContent.Tone.POSITIVE
                else -> "On time" to StripContent.Tone.POSITIVE
            }
        }
        return if (lands == null && delay == null) null else lands to delay
    }

    internal fun minutesLeft(mins: Int): String = when {
        mins < 1 -> "landing"
        mins < 60 -> "$mins min left"
        mins % 60 == 0 -> "${mins / 60} h left"
        else -> "${mins / 60} h ${mins % 60} min left"
    }

    /** "34° up"; null on the horizon, where the number says nothing useful. */
    internal fun elevationWords(deg: Double): String? =
        if (deg < 1) null else "${deg.roundToInt()}° up"

    /** "Airbus A320neo" → "A320neo"; names without a known maker stay whole. */
    internal fun shortType(typeName: String?): String? {
        val t = typeName ?: return null
        val maker = ManufacturerLogoRepo.manufacturerOf(t) ?: return t
        return ManufacturerLogoRepo.modelOf(t, maker).ifEmpty { t }
    }

    /**
     * Whether a cached model logo leads with the maker's wordmark that the
     * strip drops for width ("AIRBUS A320neo" → "A320neo"). Only the logos
     * known to be laid out that way: "737 MAX" and "787 Dreamliner" have a
     * gap too, and splitting there would cut off the model number.
     */
    internal fun dropsMaker(path: String): Boolean {
        val name = path.substringAfterLast('/').lowercase()
        return "airbus" in name || "b717" in name || "boeing_777" in name
    }

    // ---- colours and status -----------------------------------------------

    /** Military and emergencies in error colours; other special aircraft in tertiary. */
    fun palette(s: WidgetState): StripPalette {
        if (s.status != WidgetState.Status.OK) return StripPalette.NORMAL
        val military = s.specialType == "Military" ||
            s.alertCategory.equals("Military", ignoreCase = true)
        return when {
            s.squawkAlert != null || military -> StripPalette.ERROR
            s.specialType != null || s.alertCategory != null || s.alertTag != null ->
                StripPalette.TERTIARY
            else -> StripPalette.NORMAL
        }
    }

    /**
     * The status dot. Routine refreshes of fresh data stay silent — a spinner
     * every 15 s is noise — it spins only when the refresh matters: no data
     * yet, stale data or after a failure.
     */
    fun status(s: WidgetState, nowMs: Long, tapped: Boolean = false): StripStatus {
        val stale = s.markedStale || s.isStaleAt(nowMs)
        return when {
            // The engine's own refreshes stay quiet on good data; a tapped one shows
            s.refreshing && (tapped || s.status != WidgetState.Status.OK || stale || s.errorCount > 0) ->
                StripStatus.SPINNER
            s.pausedReason != null ->
                if (s.pausedForBattery) StripStatus.PAUSED_BATTERY else StripStatus.PAUSED
            s.errorCount > 0 -> StripStatus.ERROR
            stale -> StripStatus.STALE
            else -> StripStatus.NONE
        }
    }
}
