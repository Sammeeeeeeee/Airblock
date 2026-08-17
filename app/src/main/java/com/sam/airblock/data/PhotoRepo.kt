package com.sam.airblock.data

import android.content.Context
import android.graphics.BitmapFactory
import okhttp3.Request
import java.io.File
import java.io.IOException

/**
 * Aircraft photos from the Planespotters.net public API, disk-cached per hex.
 * Network is touched at most ONCE per aircraft (json lookup + thumbnail download);
 * after that everything is served from disk.
 */
class PhotoRepo(context: Context) {

    private val dir = File(context.cacheDir, "photos").apply { mkdirs() }

    data class CachedPhoto(val file: File, val photographer: String?)

    /** True when [photoFor] can answer from disk — positive or negative cache. */
    fun isCached(hex: String): Boolean {
        val safeHex = hex.lowercase().filter { it.isLetterOrDigit() }
        val miss = File(dir, "$safeHex.none")
        return File(dir, "$safeHex.jpg").exists() ||
            (miss.exists() && System.currentTimeMillis() - miss.lastModified() < NEG_TTL_MS)
    }

    /** Cached photo for [hex], fetching it if this aircraft is new. Null when none exists. */
    fun photoFor(hex: String): CachedPhoto? {
        val safeHex = hex.lowercase().filter { it.isLetterOrDigit() }
        val img = File(dir, "$safeHex.jpg")
        val credit = File(dir, "$safeHex.txt")
        val miss = File(dir, "$safeHex.none") // negative cache: no photo exists

        if (img.exists()) return CachedPhoto(img, credit.takeIf { it.exists() }?.readText())
        if (miss.exists() && System.currentTimeMillis() - miss.lastModified() < NEG_TTL_MS) return null

        // The tick and an alert notification can both want the same airframe at
        // the same moment — one fetches, the other waits and reads the cache.
        return synchronized(lockFor(safeHex)) {
            if (img.exists()) return@synchronized CachedPhoto(
                img, credit.takeIf { it.exists() }?.readText())
            if (miss.exists() &&
                System.currentTimeMillis() - miss.lastModified() < NEG_TTL_MS
            ) return@synchronized null
            try {
                fetch(safeHex, img, credit, miss)
            } catch (_: IOException) {
                null // network blip — try again next time this hex shows up
            }
        }
    }

    private fun fetch(hex: String, img: File, credit: File, miss: File): CachedPhoto? {
        val lookup = Request.Builder()
            .url("https://api.planespotters.net/pub/photos/hex/$hex")
            .build()
        val photo = Http.client.newCall(lookup).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("planespotters HTTP ${resp.code}")
            Http.json.decodeFromString<PhotoResponse>(resp.body?.string() ?: "{}")
                .photos.firstOrNull()
        }
        val src = photo?.thumbnailLarge?.src ?: photo?.thumbnail?.src
        if (photo == null || src == null) {
            miss.writeText("") // remember "no photo" so we don't re-ask every tick
            return null
        }

        Http.client.newCall(Request.Builder().url(src).build()).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("thumbnail HTTP ${resp.code}")
            val bytes = resp.body?.bytes() ?: throw IOException("empty thumbnail")
            // Sanity-decode bounds; thumbnails are already ~widget-sized (~280px)
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            if (opts.outWidth <= 0) throw IOException("bad image")
            // Write-then-rename: a reader (widget render, notification) can
            // never pick up a half-written file
            val tmp = File(img.parentFile, "${img.name}.part")
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(img)) { img.writeBytes(bytes); tmp.delete() }
        }
        photo.photographer?.let { credit.writeText(it) }
        prune()
        return CachedPhoto(img, photo.photographer)
    }

    /** Keep the cache bounded: newest [MAX_PHOTOS] aircraft only. */
    private fun prune() {
        // Expired negative-cache markers would otherwise accumulate forever
        val now = System.currentTimeMillis()
        dir.listFiles { f -> f.extension == "none" }
            ?.filter { now - it.lastModified() > NEG_TTL_MS }
            ?.forEach { it.delete() }
        // Half-written downloads from a killed process
        dir.listFiles { f -> f.extension == "part" }?.forEach { it.delete() }

        val images = dir.listFiles { f -> f.extension == "jpg" } ?: return
        if (images.size <= MAX_PHOTOS) return
        images.sortedBy { it.lastModified() }
            .take(images.size - MAX_PHOTOS)
            .forEach { stale ->
                val base = stale.nameWithoutExtension
                stale.delete()
                File(dir, "$base.txt").delete()
            }
    }

    companion object {
        /**
         * Fetch locks, shared process-wide (each tick path builds its own
         * [PhotoRepo], but they all write the same cache directory). Striped
         * rather than per-hex so the table can't grow with every airframe ever
         * seen; a hash collision just means two aircraft fetch in turn.
         */
        private val locks = Array(8) { Any() }

        private fun lockFor(hex: String): Any =
            locks[(hex.hashCode() ushr 1) % locks.size]

        private const val MAX_PHOTOS = 20
        private const val NEG_TTL_MS = 7L * 24 * 60 * 60 * 1000 // retry "no photo" weekly
    }
}
