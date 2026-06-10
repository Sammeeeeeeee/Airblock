package com.sam.airblock.data

import android.content.Context
import android.graphics.BitmapFactory
import com.sam.airblock.util.AirlineCodes
import okhttp3.Request
import java.io.File
import java.io.IOException

/**
 * Airline logos from the Kiwi.com images CDN (keyed by IATA code), disk-cached
 * per airline. Like [PhotoRepo], the network is touched at most once per
 * airline; afterwards everything is served from disk. Airlines that aren't in
 * the [AirlineCodes] table or have no logo on the CDN are negative-cached.
 */
class AirlineLogoRepo(context: Context) {

    private val dir = File(context.cacheDir, "airlines").apply { mkdirs() }

    /** Cached logo for the airline flying [callsign], or null when unknown. */
    fun logoFor(callsign: String?): File? {
        val icao = AirlineCodes.icaoPrefix(callsign) ?: return null
        val iata = AirlineCodes.iataFor(icao) ?: return null
        val img = File(dir, "$icao.png")
        val miss = File(dir, "$icao.none")

        if (img.exists()) return img
        if (miss.exists() && System.currentTimeMillis() - miss.lastModified() < NEG_TTL_MS) return null

        return try {
            fetch(iata, img, miss)
        } catch (_: IOException) {
            null // network blip — try again next time this airline shows up
        }
    }

    private fun fetch(iata: String, img: File, miss: File): File? {
        val req = Request.Builder()
            .url("https://images.kiwi.com/airlines/64/$iata.png")
            .build()
        Http.client.newCall(req).execute().use { resp ->
            if (resp.code == 404) {
                miss.writeText("")
                return null
            }
            if (!resp.isSuccessful) throw IOException("airline logo HTTP ${resp.code}")
            val bytes = resp.body?.bytes() ?: throw IOException("empty logo")
            // Sanity-decode: an HTML error page must not be cached as a "logo"
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            if (opts.outWidth <= 0) {
                miss.writeText("")
                return null
            }
            img.writeBytes(bytes)
        }
        return img
    }

    companion object {
        // Logos are tiny and airlines are few — no pruning needed, but retry
        // missing ones occasionally in case the CDN adds them.
        private const val NEG_TTL_MS = 30L * 24 * 60 * 60 * 1000
    }
}
