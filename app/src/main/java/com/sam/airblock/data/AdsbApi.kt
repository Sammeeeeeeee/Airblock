package com.sam.airblock.data

import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Thin client for api.adsb.lol. One shared OkHttp client app-wide:
 * connection keep-alive matters when polling every 15 s.
 */
object Http {
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        // Keep the connection to api.adsb.lol warm across ticks.
        .build()

    val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
}

class AdsbApi(private val client: OkHttpClient = Http.client) {

    /** Nearest aircraft within [radiusNm] of the point, or null when none. */
    @Throws(IOException::class)
    fun closest(lat: Double, lon: Double, radiusNm: Int): Aircraft? {
        val req = Request.Builder()
            .url("https://api.adsb.lol/v2/closest/$lat/$lon/$radiusNm")
            .header("User-Agent", UA)
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("closest HTTP ${resp.code}")
            val body = resp.body?.string() ?: throw IOException("empty body")
            return Http.json.decodeFromString<ClosestResponse>(body).ac.firstOrNull()
        }
    }

    /** Plausible route for a callsign. Null when unknown. Call once per callsign, then cache. */
    @Throws(IOException::class)
    fun route(callsign: String, lat: Double, lon: Double): RouteResult? {
        val payload = Http.json.encodeToString(
            RoutesetRequest.serializer(),
            RoutesetRequest(listOf(RoutePlane(callsign, lat, lon)))
        )
        val req = Request.Builder()
            .url("https://api.adsb.lol/api/0/routeset")
            .header("User-Agent", UA)
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("routeset HTTP ${resp.code}")
            val body = resp.body?.string() ?: return null
            val results = Http.json.decodeFromString<List<RouteResult>>(body)
            return results.firstOrNull()
                ?.takeIf { it.plausible != false && it.airports.size >= 2 }
        }
    }

    companion object {
        private const val UA = "Airblock-widget/1.0 (personal home-screen widget)"
    }
}
