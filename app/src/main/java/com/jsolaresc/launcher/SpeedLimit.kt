package com.jsolaresc.launcher

import android.content.Context
import android.location.Location
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Velocidad máxima de la calle por la que vas, según OpenStreetMap.
 * Google Maps no comparte este dato con otras apps, así que se consulta OSM
 * (Overpass) cuando hay señal y se guarda por zona: las calles ya recorridas
 * muestran su máxima también sin señal.
 */
class SpeedLimit(ctx: Context, private val onChange: (Int?) -> Unit) {

    private val prefs = ctx.getSharedPreferences("maxspeed", Context.MODE_PRIVATE)
    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val userAgent = ctx.packageName

    @Volatile
    private var inFlight = false
    private var lastQueryAt = 0L
    private var lastKey: String? = null

    /** Zona de ~100 m y rumbo en 8 sectores: distingue la avenida de la calle que la cruza. */
    private fun keyFor(loc: Location): String {
        val lat = (loc.latitude * 1000).roundToInt()
        val lon = (loc.longitude * 1000).roundToInt()
        val sector = if (loc.hasBearing()) ((loc.bearing + 22.5f) / 45f).toInt() % 8 else 9
        return "$lat,$lon,$sector"
    }

    fun update(loc: Location, online: Boolean) {
        // Parado o casi, el rumbo no sirve: se mantiene lo último.
        if (!loc.hasSpeed() || loc.speed < 2f) return
        val key = keyFor(loc)
        if (prefs.contains(key)) {
            if (key != lastKey) {
                lastKey = key
                val v = prefs.getInt(key, -1)
                onChange(v.takeIf { it > 0 })
            }
            return
        }
        if (!online || inFlight) return
        val now = System.currentTimeMillis()
        if (now - lastQueryAt < 4000) return
        lastQueryAt = now
        inFlight = true
        val lat = loc.latitude
        val lon = loc.longitude
        val bearing = if (loc.hasBearing()) loc.bearing.toDouble() else null
        executor.execute {
            val result = try {
                query(lat, lon, bearing)
            } catch (e: Exception) {
                null
            }
            inFlight = false
            if (result != null) {
                prefs.edit().putInt(key, result.first ?: -1).apply()
                main.post {
                    lastKey = key
                    onChange(result.first)
                }
            }
        }
    }

    /** Devuelve (máxima o null si la calle no la tiene cargada), o null si falló la consulta. */
    private fun query(lat: Double, lon: Double, bearing: Double?): Pair<Int?, Unit>? {
        val q = "[out:json][timeout:8];" +
            "way(around:25,$lat,$lon)[highway~\"^(motorway|trunk|primary|secondary|tertiary|unclassified|residential|living_street|service|motorway_link|trunk_link|primary_link|secondary_link|tertiary_link)$\"];" +
            "out tags geom;"
        val conn = URL("https://overpass-api.de/api/interpreter").openConnection() as HttpURLConnection
        conn.connectTimeout = 6000
        conn.readTimeout = 10000
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("User-Agent", userAgent)
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        conn.outputStream.use { it.write(("data=" + URLEncoder.encode(q, "UTF-8")).toByteArray()) }
        if (conn.responseCode != 200) return null
        val body = conn.inputStream.bufferedReader().use { it.readText() }
        val elements = JSONObject(body).optJSONArray("elements") ?: return null

        var bestScore = Double.MAX_VALUE
        var bestLimit: Int? = null
        var found = false
        val cosLat = cos(Math.toRadians(lat))
        for (i in 0 until elements.length()) {
            val way = elements.getJSONObject(i)
            val geom = way.optJSONArray("geometry") ?: continue
            val tags = way.optJSONObject("tags")
            for (j in 0 until geom.length() - 1) {
                val a = geom.getJSONObject(j)
                val b = geom.getJSONObject(j + 1)
                // Coordenadas locales en metros (aproximación plana, alcanza para 25 m).
                val ax = (a.getDouble("lon") - lon) * 111320 * cosLat
                val ay = (a.getDouble("lat") - lat) * 110540
                val bx = (b.getDouble("lon") - lon) * 111320 * cosLat
                val by = (b.getDouble("lat") - lat) * 110540
                val dist = pointToSegment(ax, ay, bx, by)
                var penalty = 0.0
                if (bearing != null) {
                    val segBearing = (Math.toDegrees(atan2(bx - ax, by - ay)) + 360) % 360
                    var diff = abs(segBearing - bearing) % 180
                    if (diff > 90) diff = 180 - diff
                    penalty = diff * 0.5 // 90° de diferencia pesa como 45 m
                }
                val score = dist + penalty
                if (score < bestScore) {
                    bestScore = score
                    bestLimit = parse(tags?.optString("maxspeed"))
                    found = true
                }
            }
        }
        return if (found) Pair(bestLimit, Unit) else Pair(null, Unit)
    }

    private fun pointToSegment(ax: Double, ay: Double, bx: Double, by: Double): Double {
        val dx = bx - ax
        val dy = by - ay
        val len2 = dx * dx + dy * dy
        val t = if (len2 == 0.0) 0.0 else (-(ax * dx + ay * dy) / len2).coerceIn(0.0, 1.0)
        val px = ax + t * dx
        val py = ay + t * dy
        return sqrt(px * px + py * py)
    }

    private fun parse(value: String?): Int? {
        if (value.isNullOrBlank()) return null
        val number = Regex("^(\\d+)").find(value.trim())?.groupValues?.get(1)?.toIntOrNull() ?: return null
        return if (value.contains("mph")) (number * 1.609).roundToInt() else number
    }
}
