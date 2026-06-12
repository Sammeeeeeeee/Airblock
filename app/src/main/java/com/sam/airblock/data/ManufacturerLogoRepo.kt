package com.sam.airblock.data

import android.content.Context
import android.graphics.BitmapFactory
import okhttp3.Request
import java.io.File
import java.io.IOException

/**
 * Aircraft-manufacturer wordmarks (BOEING, AIRBUS…) rendered as PNGs by
 * Wikimedia Commons from the official SVG logos, disk-cached per manufacturer.
 * The widget tints them to the theme's content colour, so they read as crisp
 * monochrome wordmarks in light AND dark themes.
 *
 * Filenames were verified live against commons.wikimedia.org; manufacturers
 * not in the table (ATR, Cirrus, Bell, Leonardo…) simply fall back to text.
 */
class ManufacturerLogoRepo(context: Context) {

    private val dir = File(context.cacheDir, "manufacturers").apply { mkdirs() }

    /** Cached wordmark for [typeName]'s manufacturer, or null. */
    fun logoFor(typeName: String?): File? {
        val mfr = manufacturerOf(typeName) ?: return null
        return cachedOrFetch(
            key = mfr.lowercase().replace(" ", "_"),
            fileName = FILES.getValue(mfr),
        )
    }

    /**
     * The plane's OWN logo (the stylized A380 / 787 Dreamliner / 737 MAX
     * wordmarks) for an ICAO type code, or null when the model has none.
     */
    fun logoForModel(typeCode: String?): File? {
        val fileName = MODEL_FILES[typeCode?.trim()?.uppercase()] ?: return null
        return cachedOrFetch(
            key = "model_" + fileName.substringBeforeLast('.')
                .lowercase().replace(Regex("\\W"), "_"),
            fileName = fileName,
        )
    }

    /** True when [logoFor] for [typeName] needs no network (incl. unknown brands). */
    fun isCached(typeName: String?): Boolean {
        val mfr = manufacturerOf(typeName) ?: return true
        return hasLocal(mfr.lowercase().replace(" ", "_"))
    }

    /** True when [logoForModel] for [typeCode] needs no network. */
    fun isCachedModel(typeCode: String?): Boolean {
        val fileName = MODEL_FILES[typeCode?.trim()?.uppercase()] ?: return true
        return hasLocal("model_" + fileName.substringBeforeLast('.')
            .lowercase().replace(Regex("\\W"), "_"))
    }

    private fun hasLocal(key: String): Boolean {
        val miss = File(dir, "$key.none")
        return File(dir, "$key.png").exists() ||
            (miss.exists() && System.currentTimeMillis() - miss.lastModified() < NEG_TTL_MS)
    }

    private fun cachedOrFetch(key: String, fileName: String): File? {
        val img = File(dir, "$key.png")
        val miss = File(dir, "$key.none")
        if (img.exists()) return img
        if (miss.exists() && System.currentTimeMillis() - miss.lastModified() < NEG_TTL_MS) return null
        return try {
            fetch(fileName, img, miss)
        } catch (_: IOException) {
            null // network blip — retried next time this aircraft flies over
        }
    }

    private fun fetch(fileName: String, img: File, miss: File): File? {
        val req = Request.Builder()
            .url("https://commons.wikimedia.org/w/thumb.php?f=$fileName&w=$RENDER_WIDTH")
            .build()
        Http.client.newCall(req).execute().use { resp ->
            if (resp.code == 404) {
                miss.writeText("")
                return null
            }
            if (!resp.isSuccessful) throw IOException("manufacturer logo HTTP ${resp.code}")
            val bytes = resp.body?.bytes() ?: throw IOException("empty logo")
            // Wikimedia rate-limits return HTML with code 200/429 — never cache those
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            if (opts.outWidth <= 0) return null // transient — don't negative-cache
            img.writeBytes(bytes)
        }
        return img
    }

    companion object {
        private const val RENDER_WIDTH = 400
        private const val NEG_TTL_MS = 30L * 24 * 60 * 60 * 1000

        /** "Airbus A321neo" → "Airbus"; null when the brand has no known logo. */
        fun manufacturerOf(typeName: String?): String? =
            typeName?.let { t -> FILES.keys.firstOrNull { t.startsWith(it, ignoreCase = true) } }

        /** "Airbus A321neo" → "A321neo" (the part the wordmark doesn't say). */
        fun modelOf(typeName: String, manufacturer: String): String =
            typeName.substring(manufacturer.length).trim()

        /**
         * ICAO type code → the model's own logo on Wikimedia Commons
         * (verified against Category:Logos_of_Airbus_aircraft and
         * Category:Logos_of_Boeing; all have transparent backgrounds).
         */
        private val MODEL_FILES: Map<String, String> = buildMap {
            put("A388", "Logo_Airbus_A380.svg")
            put("BCS1", "Logo_Airbus_A220.svg"); put("BCS3", "Logo_Airbus_A220.svg")
            put("A318", "Logo_Airbus_A318.svg")
            put("A320", "Logo_Airbus_A320.svg")
            put("A20N", "Logo_Airbus_A320neo.svg"); put("A19N", "Logo_Airbus_A320neo.svg")
            put("A321", "Logo_Airbus_A321.svg"); put("A21N", "Logo_Airbus_A321.svg")
            put("A332", "Logo_Airbus_A330.svg"); put("A333", "Logo_Airbus_A330.svg")
            put("A338", "Logo_Airbus_A330neo.svg"); put("A339", "Logo_Airbus_A330neo.svg")
            listOf("A342", "A343", "A345", "A346").forEach { put(it, "Logo_Airbus_A340.svg") }
            put("A359", "Logo_Airbus_A350.svg"); put("A35K", "Logo_Airbus_A350.svg")
            listOf("B772", "B773", "B77L", "B77W", "B778", "B779")
                .forEach { put(it, "Boeing_777_logo.svg") }
            listOf("B788", "B789", "B78X")
                .forEach { put(it, "Boeing_787_Dreamliner_logo.png") }
            listOf("B37M", "B38M", "B39M", "B3XM")
                .forEach { put(it, "737_MAX_logo.png") }
            put("B712", "B717.svg")
        }

        /** Manufacturer display prefix → verified Wikimedia Commons filename. */
        private val FILES = mapOf(
            "Boeing" to "Boeing_full_logo.svg",
            "Airbus" to "Airbus_Logo_2017.svg",
            "Embraer" to "Embraer_logo.svg",
            "Bombardier" to "Bombardier.svg",
            "Cessna" to "Cessna_Logo.svg",
            "Piper" to "Piper_Aircraft_logo.svg",
            "Beechcraft" to "Beechcraft_logo.svg",
            "Gulfstream" to "Gulfstream_Aerospace_logo.svg",
            "Dassault" to "Dassault_Aviation_logo.svg",
            "Pilatus" to "Pilatus_Aircraft_logo.svg",
            "Robinson" to "Robinson_Helicopter_Company_logo.svg",
            "Sikorsky" to "Sikorsky_Aircraft_Logo.svg",
            "McDonnell Douglas" to "McDonnell_Douglas_logo.svg",
        )
    }
}
