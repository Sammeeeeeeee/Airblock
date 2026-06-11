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

    /** Display name of the airline flying [callsign], or null when unknown. */
    fun nameForCallsign(callsign: String?): String? =
        iataForCallsign(callsign)?.let { IATA_TO_NAME[it] }

    private val IATA_TO_NAME: Map<String, String> = mapOf(
        "BA" to "British Airways", "CJ" to "BA CityFlyer", "VS" to "Virgin Atlantic",
        "U2" to "easyJet", "LS" to "Jet2", "BY" to "TUI Airways",
        "EI" to "Aer Lingus", "FR" to "Ryanair", "LM" to "Loganair",
        "LH" to "Lufthansa", "CL" to "Lufthansa CityLine", "EW" to "Eurowings",
        "LX" to "Swiss", "WK" to "Edelweiss", "OS" to "Austrian",
        "SN" to "Brussels Airlines", "KL" to "KLM", "HV" to "Transavia",
        "OR" to "TUI fly", "MP" to "Martinair", "AF" to "Air France",
        "A5" to "HOP!", "TO" to "Transavia France", "DE" to "Condor",
        "X3" to "TUIfly", "TB" to "TUI fly Belgium", "IB" to "Iberia",
        "I2" to "Iberia Express", "VY" to "Vueling", "UX" to "Air Europa",
        "TP" to "TAP Air Portugal", "AZ" to "ITA Airways", "V7" to "Volotea",
        "LG" to "Luxair", "KM" to "Air Malta",
        "SK" to "SAS", "DY" to "Norwegian", "AY" to "Finnair",
        "FI" to "Icelandair", "OG" to "Play", "WF" to "Widerøe", "BT" to "airBaltic",
        "LO" to "LOT", "W6" to "Wizz Air", "OK" to "Czech Airlines",
        "QS" to "Smartwings", "A3" to "Aegean", "OA" to "Olympic Air",
        "OU" to "Croatia Airlines", "JU" to "Air Serbia", "TK" to "Turkish Airlines",
        "PC" to "Pegasus", "XQ" to "SunExpress", "SU" to "Aeroflot",
        "S7" to "S7 Airlines", "U6" to "Ural Airlines",
        "EK" to "Emirates", "EY" to "Etihad", "QR" to "Qatar Airways",
        "GF" to "Gulf Air", "KU" to "Kuwait Airways", "SV" to "Saudia",
        "FZ" to "flydubai", "G9" to "Air Arabia", "RJ" to "Royal Jordanian",
        "ME" to "MEA", "WY" to "Oman Air", "LY" to "El Al",
        "MS" to "EgyptAir", "AT" to "Royal Air Maroc", "AH" to "Air Algérie",
        "TU" to "Tunisair", "ET" to "Ethiopian", "KQ" to "Kenya Airways",
        "SA" to "South African", "WB" to "RwandAir",
        "AA" to "American", "DL" to "Delta", "UA" to "United",
        "WN" to "Southwest", "B6" to "JetBlue", "AS" to "Alaska",
        "NK" to "Spirit", "F9" to "Frontier", "G4" to "Allegiant",
        "HA" to "Hawaiian", "SY" to "Sun Country", "OO" to "SkyWest",
        "YX" to "Republic", "9E" to "Endeavor", "MQ" to "Envoy",
        "YV" to "Mesa", "QX" to "Horizon", "FX" to "FedEx", "5X" to "UPS",
        "5Y" to "Atlas Air", "AC" to "Air Canada", "WS" to "WestJet",
        "TS" to "Air Transat", "PD" to "Porter", "F8" to "Flair",
        "AM" to "Aeroméxico", "Y4" to "Volaris", "VB" to "Viva Aerobus",
        "SQ" to "Singapore Airlines", "TR" to "Scoot", "MH" to "Malaysia Airlines",
        "AK" to "AirAsia", "D7" to "AirAsia X", "TG" to "Thai Airways",
        "GA" to "Garuda", "CX" to "Cathay Pacific", "MU" to "China Eastern",
        "CA" to "Air China", "CZ" to "China Southern", "HU" to "Hainan",
        "MF" to "XiamenAir", "ZH" to "Shenzhen Airlines", "9C" to "Spring Airlines",
        "HO" to "Juneyao Air", "SC" to "Shandong Airlines", "BR" to "EVA Air",
        "CI" to "China Airlines", "JL" to "JAL", "NH" to "ANA",
        "MM" to "Peach", "GK" to "Jetstar Japan", "KE" to "Korean Air",
        "OZ" to "Asiana", "LJ" to "Jin Air", "7C" to "Jeju Air",
        "TW" to "T'way", "VN" to "Vietnam Airlines", "VJ" to "VietJet",
        "PR" to "Philippine Airlines", "5J" to "Cebu Pacific",
        "AI" to "Air India", "6E" to "IndiGo", "SG" to "SpiceJet",
        "UK" to "Vistara", "IX" to "Air India Express", "PK" to "PIA",
        "BG" to "Biman", "UL" to "SriLankan", "QF" to "Qantas",
        "JQ" to "Jetstar", "VA" to "Virgin Australia", "NZ" to "Air New Zealand",
        "ZL" to "Rex", "LA" to "LATAM", "G3" to "GOL", "AD" to "Azul",
        "AV" to "Avianca", "CM" to "Copa", "AR" to "Aerolíneas Argentinas",
        "CV" to "Cargolux", "3S" to "AeroLogic", "RU" to "AirBridgeCargo",
    )

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
