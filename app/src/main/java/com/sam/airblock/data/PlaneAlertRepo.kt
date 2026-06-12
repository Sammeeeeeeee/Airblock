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
            buildMap {
                file.bufferedReader().useLines { lines ->
                    lines.drop(1).forEach { line ->
                        val c = splitCsv(line)
                        val hex = c.getOrNull(0)?.trim()?.uppercase().orEmpty()
                        if (hex.isNotEmpty()) {
                            put(hex, Alert(
                                operator = c.getOrNull(2)?.trim()?.ifEmpty { null },
                                tags = listOfNotNull(c.getOrNull(6), c.getOrNull(7),
                                    c.getOrNull(8)).map { it.trim() }.filter { it.isNotEmpty() },
                                category = c.getOrNull(9)?.trim()?.ifEmpty { null },
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
