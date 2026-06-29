package com.sam.airblock.data

import android.content.Context
import okhttp3.Request
import java.io.File
import java.io.IOException

/**
 * Interesting-aircraft tags from sdr-enthusiasts/plane-alert-db (military,
 * government, celebs, special liveries…), keyed by ICAO hex.
 *
 * The CSV (~40k rows) is re-pulled WEEKLY and only on Wi-Fi, by
 * [com.sam.airblock.engine.KeepAliveWorker] — never on the tick path. Lookups
 * hit a lazily-parsed in-memory map; no network is ever touched per tick.
 */
class PlaneAlertRepo(private val context: Context) {

    data class Alert(
        val operator: String?,
        val tags: List<String>,
        val category: String?,
    )

    private val file = File(context.filesDir, "plane-alert-db.csv")

    /** Tags for an airframe, or null. Local only — parses the CSV on first use. */
    fun lookup(hex: String?): Alert? {
        val h = hex?.trim()?.uppercase() ?: return null
        val m = cache ?: synchronized(LOCK) {
            cache ?: load().also { cache = it }
        }
        return m[h]
    }

    fun isStale(): Boolean =
        !file.exists() || System.currentTimeMillis() - file.lastModified() > MAX_AGE_MS

    /** Download a fresh DB (gzip over the wire). Call from Wi-Fi-gated work only. */
    @Throws(IOException::class)
    fun refresh() {
        val req = Request.Builder().url(DB_URL).build()
        Http.client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("plane-alert-db HTTP ${resp.code}")
            val body = resp.body?.bytes() ?: throw IOException("empty body")
            // Sanity: must look like the expected CSV, not an error page
            if (!body.decodeToString(0, minOf(64, body.size)).startsWith("\$ICAO")) {
                throw IOException("unexpected plane-alert-db content")
            }
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeBytes(body)
            if (!tmp.renameTo(file)) throw IOException("rename failed")
        }
        synchronized(LOCK) { cache = null } // re-parse lazily on next lookup
    }

    private fun load(): Map<String, Alert> {
        if (!file.exists()) return emptyMap()
        return runCatching {
            file.bufferedReader().use { reader ->
                val headerLine = reader.readLine() ?: return@use emptyMap<String, Alert>()
                // Resolve columns BY NAME, not fixed position: the plane-alert-db
                // layout changes over time (it has gained columns like "$ICAO
                // Type"), and a hardcoded index then lands on the wrong tag after
                // a weekly refresh — which is how Tag 1 became Tag 3.
                val header = splitCsv(headerLine).map { it.trim().lowercase() }
                fun colOf(vararg needles: String) =
                    header.indexOfFirst { h -> needles.any { h.contains(it) } }
                val iHex = header.indexOf("\$icao").let { if (it >= 0) it else 0 }
                val iOperator = colOf("operator")
                val iTag1 = colOf("tag 1", "tag1")
                val iTag2 = colOf("tag 2", "tag2")
                val iTag3 = colOf("tag 3", "tag3")
                val iCategory = header.indexOf("category")
                buildMap {
                    reader.forEachLine { line ->
                        val c = splitCsv(line)
                        val hex = c.getOrNull(iHex)?.trim()?.uppercase().orEmpty()
                        if (hex.isNotEmpty()) {
                            val tags = listOf(iTag1, iTag2, iTag3)
                                .filter { it >= 0 }
                                .mapNotNull { c.getOrNull(it)?.trim()?.ifEmpty { null } }
                            put(hex, Alert(
                                operator = c.getOrNull(iOperator)?.trim()?.ifEmpty { null },
                                tags = tags,
                                category = c.getOrNull(iCategory)?.trim()?.ifEmpty { null },
                            ))
                        }
                    }
                }
            }
        }.getOrDefault(emptyMap())
    }

    companion object {
        private const val DB_URL =
            "https://raw.githubusercontent.com/sdr-enthusiasts/plane-alert-db/main/plane-alert-db.csv"
        private const val MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000
        private val LOCK = Any()

        // One process-wide parsed map (~40k small entries); the service,
        // worker and any future caller share it
        @Volatile
        private var cache: Map<String, Alert>? = null

        /** Minimal quote-aware CSV splitter (fields may contain commas). */
        fun splitCsv(line: String): List<String> {
            val out = ArrayList<String>(12)
            val sb = StringBuilder()
            var inQuotes = false
            var i = 0
            while (i < line.length) {
                val ch = line[i]
                when {
                    ch == '"' && inQuotes && i + 1 < line.length && line[i + 1] == '"' -> {
                        sb.append('"'); i++
                    }
                    ch == '"' -> inQuotes = !inQuotes
                    ch == ',' && !inQuotes -> { out.add(sb.toString()); sb.setLength(0) }
                    else -> sb.append(ch)
                }
                i++
            }
            out.add(sb.toString())
            return out
        }
    }
}
