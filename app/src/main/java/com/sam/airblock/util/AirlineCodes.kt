package com.sam.airblock.util

/**
 * Airline identification from a flight callsign. ICAO callsigns are the
 * 3-letter airline designator + flight number ("RYR4421" → Ryanair); logo CDNs
 * are keyed by the 2-letter IATA code, so a mapping table is needed.
 *
 * The table is a curated set of the world's major passenger/cargo airlines —
 * unknown prefixes simply mean "no logo", never an error. Subsidiaries that
 * fly the parent's brand map to the parent's IATA code.
 */
object AirlineCodes {

    /** "RYR4421" → "RYR"; null for registrations ("N832DN") and short/odd callsigns. */
    fun icaoPrefix(callsign: String?): String? {
        val cs = callsign?.trim()?.uppercase() ?: return null
        if (cs.length < 4) return null // bare 3 letters is more likely a reg fragment
        val prefix = cs.substring(0, 3)
        if (!prefix.all { it in 'A'..'Z' }) return null
        return prefix
    }

    /** IATA code for an ICAO airline designator, or null when unknown. */
    fun iataFor(icao: String): String? = ICAO_TO_IATA[icao.uppercase()]

    /** Convenience: callsign straight to IATA code. */
    fun iataForCallsign(callsign: String?): String? =
        icaoPrefix(callsign)?.let { iataFor(it) }

    private val ICAO_TO_IATA: Map<String, String> = mapOf(
        // --- UK & Ireland ---
        "BAW" to "BA", "SHT" to "BA", "CFE" to "CJ", "VIR" to "VS",
        "EZY" to "U2", "EJU" to "U2", "EZS" to "U2", "EXS" to "LS",
        "TOM" to "BY", "EIN" to "EI", "RYR" to "FR", "RUK" to "FR",
        "LOG" to "LM",
        // --- Western Europe ---
        "DLH" to "LH", "CLH" to "CL", "GEC" to "LH", "EWG" to "EW",
        "SWR" to "LX", "EDW" to "WK", "AUA" to "OS", "BEL" to "SN",
        "KLM" to "KL", "TRA" to "HV", "TFL" to "OR", "MPH" to "MP",
        "AFR" to "AF", "HOP" to "A5", "TVF" to "TO", "CFG" to "DE",
        "TUI" to "X3", "JAF" to "TB", "IBE" to "IB", "IBS" to "I2",
        "VLG" to "VY", "AEA" to "UX", "TAP" to "TP", "ITY" to "AZ",
        "AZA" to "AZ", "VOE" to "V7", "LGL" to "LG", "AMC" to "KM",
        // --- Northern Europe ---
        "SAS" to "SK", "NAX" to "DY", "NOZ" to "DY", "NSZ" to "DY",
        "FIN" to "AY", "ICE" to "FI", "FPY" to "OG", "WIF" to "WF",
        "BTI" to "BT",
        // --- Central/Eastern Europe & Turkey ---
        "LOT" to "LO", "WZZ" to "W6", "WUK" to "W6", "CSA" to "OK",
        "TVS" to "QS", "AEE" to "A3", "OAL" to "OA", "CTN" to "OU",
        "ASL" to "JU", "THY" to "TK", "PGT" to "PC", "SXS" to "XQ",
        "AFL" to "SU", "SBI" to "S7", "SVR" to "U6",
        // --- Middle East & Africa ---
        "UAE" to "EK", "ETD" to "EY", "QTR" to "QR", "GFA" to "GF",
        "KAC" to "KU", "SVA" to "SV", "FDB" to "FZ", "ABY" to "G9",
        "RJA" to "RJ", "MEA" to "ME", "OMA" to "WY", "ELY" to "LY",
        "MSR" to "MS", "RAM" to "AT", "DAH" to "AH", "TAR" to "TU",
        "ETH" to "ET", "KQA" to "KQ", "SAA" to "SA", "RWD" to "WB",
        // --- North America ---
        "AAL" to "AA", "DAL" to "DL", "UAL" to "UA", "SWA" to "WN",
        "JBU" to "B6", "ASA" to "AS", "NKS" to "NK", "FFT" to "F9",
        "AAY" to "G4", "HAL" to "HA", "SCX" to "SY", "SKW" to "OO",
        "RPA" to "YX", "EDV" to "9E", "ENY" to "MQ", "ASH" to "YV",
        "QXE" to "QX", "FDX" to "FX", "UPS" to "5X", "GTI" to "5Y",
        "ACA" to "AC", "ROU" to "AC", "JZA" to "AC", "WJA" to "WS",
        "WEN" to "WS", "TSC" to "TS", "POE" to "PD", "FLE" to "F8",
        "AMX" to "AM", "VOI" to "Y4", "VIV" to "VB",
        // --- Asia-Pacific ---
        "SIA" to "SQ", "SCO" to "TR", "MAS" to "MH", "AXM" to "AK",
        "XAX" to "D7", "THA" to "TG", "GIA" to "GA", "CPA" to "CX",
        "CES" to "MU", "CCA" to "CA", "CSN" to "CZ", "CHH" to "HU",
        "CXA" to "MF", "CSZ" to "ZH", "CQH" to "9C", "DKH" to "HO",
        "CDG" to "SC", "EVA" to "BR", "CAL" to "CI", "JAL" to "JL",
        "ANA" to "NH", "APJ" to "MM", "JJP" to "GK", "KAL" to "KE",
        "AAR" to "OZ", "JNA" to "LJ", "JJA" to "7C", "TWB" to "TW",
        "HVN" to "VN", "VJC" to "VJ", "PAL" to "PR", "CEB" to "5J",
        "AIC" to "AI", "IGO" to "6E", "SEJ" to "SG", "VTI" to "UK",
        "AXB" to "IX", "PIA" to "PK", "BBC" to "BG", "ALK" to "UL",
        "QFA" to "QF", "JST" to "JQ", "VOZ" to "VA", "ANZ" to "NZ",
        "RXA" to "ZL",
        // --- Latin America ---
        "LAN" to "LA", "TAM" to "LA", "GLO" to "G3", "AZU" to "AD",
        "AVA" to "AV", "CMP" to "CM", "ARG" to "AR",
        // --- Cargo / other ---
        "CLX" to "CV", "BOX" to "3S", "ABW" to "RU",
    )
}
