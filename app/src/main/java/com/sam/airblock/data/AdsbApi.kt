package com.sam.airblock.data

import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Thin client for api.adsb.lol. One shared OkHttp client app-wide:
 * connection keep-alive matters when polling every 15 s.
 */
object Http {
    /**
     * Descriptive UA on every request — Planespotters rejects generic library
     * UAs outright, and it's polite to the free adsb.lol service too.
     */
    const val USER_AGENT = "Airblock/1.0 (+https://github.com/Sammeeeeeeee/Airblock)"

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        // Keep the connection to api.adsb.lol warm across ticks.
        .addInterceptor { chain ->
            chain.proceed(
                chain.request().newBuilder().header("User-Agent", USER_AGENT).build()
            )
        }
        .build()

    val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
}

class AdsbApi(private val client: OkHttpClient = Http.client) {

    /** Nearest aircraft within [radiusNm] of the point, or null when none. */
    @Throws(IOException::class)
    fun closest(lat: Double, lon: Double, radiusNm: Int): Aircraft? {
        val req = Request.Builder()
            .url("https://api.adsb.lol/v2/closest/$lat/$lon/$radiusNm")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("closest HTTP ${resp.code}")
            val body = resp.body?.string() ?: throw IOException("empty body")
            return Http.json.decodeFromString<ClosestResponse>(body).ac.firstOrNull()
        }
    }

    /**
     * Route for a callsign — a 302 to a static JSON file on adsb.lol's CDN,
     * so this is one cheap GET per flight. Null when the route is unknown (404).
     */
    @Throws(IOException::class)
    fun route(callsign: String): RouteResult? {
        val req = Request.Builder()
            .url("https://api.adsb.lol/api/0/route/${callsign.trim().uppercase()}")
            .build()
        client.newCall(req).execute().use { resp ->
            if (resp.code == 404) return null
            if (!resp.isSuccessful) throw IOException("route HTTP ${resp.code}")
            val body = resp.body?.string() ?: return null
            return Http.json.decodeFromString<RouteResult>(body)
                .takeIf { it.airports.size >= 2 }
        }
    }
}
