package com.sam.airblock.data

import kotlinx.serialization.json.Json
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.Inet6Address
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
        // Prefer IPv4: on networks with broken IPv6 the v6 attempt eats the
        // whole connect timeout before falling back (OkHttp 4 has no happy
        // eyeballs), which showed up as recurring tick timeouts on-device.
        .dns(object : Dns {
            override fun lookup(hostname: String) =
                Dns.SYSTEM.lookup(hostname).sortedBy { it is Inet6Address }
        })
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
     * Nearest AIRBORNE aircraft from an area query — used only when the
     * single-aircraft `closest` answer is sitting on the ground (common next
     * to an airport: parked jets transmit too). Radius is capped to keep the
     * payload bounded; bodies arrive gzip'd.
     */
    @Throws(IOException::class)
    fun nearestAirborne(lat: Double, lon: Double, radiusNm: Int): Aircraft? {
        val r = radiusNm.coerceAtMost(MAX_AREA_RADIUS_NM)
        val req = Request.Builder()
            .url("https://api.adsb.lol/v2/lat/$lat/lon/$lon/dist/$r")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("area HTTP ${resp.code}")
            val body = resp.body?.string() ?: throw IOException("empty body")
            return Http.json.decodeFromString<ClosestResponse>(body).ac
                .filter { !it.onGround }
                // No altitude + crawling speed = almost certainly on a taxiway
                .filterNot { it.altitudeFt == null && (it.gs ?: 0.0) < 50.0 }
                .minByOrNull { it.dst ?: Double.MAX_VALUE }
        }
    }

    private companion object {
        const val MAX_AREA_RADIUS_NM = 25
    }

    // Route lookups run once per flight and the CDN can take ~7 s on a cold
    // callsign — give them more headroom than the per-tick closest call.
    private val routeClient by lazy {
        client.newBuilder().readTimeout(15, TimeUnit.SECONDS).build()
    }

    /**
     * Route for a callsign. Primary path is `api.adsb.lol/api/0/route/{cs}`,
     * which merely 302-redirects to a static JSON file on adsb.lol's CDN.
     * That redirector is a separate (occasionally 503-ing) service from the
     * CDN itself, so if the primary request fails we retry against the CDN
     * file directly — same JSON, one hop, no dependency on the redirector.
     *
     * Null when the route is unknown (404). A 404 is a definitive answer, not
     * a failure, so it does not trigger the fallback.
     */
    @Throws(IOException::class)
    fun route(callsign: String): RouteResult? {
        val cs = callsign.trim().uppercase()
        return try {
            fetchRoute("https://api.adsb.lol/api/0/route/$cs")
        } catch (primary: IOException) {
            // Redirector unreachable/erroring — go straight to the CDN file it
            // would have pointed us at ({first two chars}/{callsign}.json).
            fetchRoute("https://vrs-standing-data.adsb.lol/routes/${cs.take(2)}/$cs.json")
        }
    }

    /** Fetch and parse a route JSON document; null on 404 or <2 airports. */
    @Throws(IOException::class)
    private fun fetchRoute(url: String): RouteResult? {
        val req = Request.Builder().url(url).build()
        routeClient.newCall(req).execute().use { resp ->
            if (resp.code == 404) return null
            if (!resp.isSuccessful) throw IOException("route HTTP ${resp.code}")
            val body = resp.body?.string() ?: return null
            return Http.json.decodeFromString<RouteResult>(body)
                .takeIf { it.airports.size >= 2 }
        }
    }
}
