package com.jsolaresc.launcher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Point
import android.location.Location
import android.media.SoundPool
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * Fotomultas (radares de velocidad y cámaras de semáforo) de OpenStreetMap.
 * Se descargan con señal para la zona (~45 km alrededor) y quedan guardadas.
 * Avisa la que tenés adelante, a menos de 400 m, una sola vez por cámara.
 */
class Cameras(
    private val ctx: Context,
    private val map: MapView,
    /** Velocidad máxima de la calle actual (OpenStreetMap), si se conoce. */
    private val roadLimit: () -> Int?,
    private val onAlert: (Camera?, Int) -> Unit
) {

    data class Camera(val id: String, val lat: Double, val lon: Double, val kind: Int, val limit: Int?) {
        companion object {
            const val SPEED = 0
            const val RED_LIGHT = 1
        }
    }

    private val file = File(ctx.filesDir, "cameras.json")
    private val prefs = ctx.getSharedPreferences("alerts", Context.MODE_PRIVATE)
    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val overlay = CamerasOverlay()

    @Volatile
    private var cameras: List<Camera> = emptyList()
    private var areaLat = 0.0
    private var areaLon = 0.0
    private var fetchedAt = 0L

    @Volatile
    private var inFlight = false
    /** Cámaras que ya sonaron: cada una suena una sola vez. */
    private val sounded = LinkedHashSet<String>()
    private var current: Camera? = null
    private val soundPool = SoundPool.Builder().setMaxStreams(1).build()
    private val alertSound = soundPool.load(ctx, R.raw.alerta, 1)

    var soundOn: Boolean
        get() = prefs.getBoolean("sound", true)
        set(value) = prefs.edit().putBoolean("sound", value).apply()

    init {
        load()
        map.overlays.add(overlay)
    }

    /** Radares del archivo sin conexión (calles-amba): todo el AMBA, sin depender de internet. */
    private var offlineData = false

    private fun loadOffline(): Boolean {
        val list = Roads.cameras(ctx) ?: return false
        cameras = list.map {
            val id = String.format(Locale.US, "%.4f,%.4f", it.lat, it.lon)
            Camera(id, it.lat, it.lon, if (it.kind == 1) Camera.RED_LIGHT else Camera.SPEED, it.limit)
        }
        offlineData = true
        map.invalidate()
        return true
    }

    private fun load() {
        if (loadOffline()) return
        try {
            if (!file.exists()) return
            val o = JSONObject(file.readText())
            areaLat = o.getDouble("lat")
            areaLon = o.getDouble("lon")
            fetchedAt = o.getLong("at")
            val arr = o.getJSONArray("items")
            cameras = (0 until arr.length()).map {
                val c = arr.getJSONObject(it)
                Camera(
                    c.getString("id"), c.getDouble("lat"), c.getDouble("lon"), c.getInt("kind"),
                    if (c.has("limit")) c.getInt("limit") else null
                )
            }
        } catch (e: Exception) {
        }
    }

    /** Si en Ajustes se pidió actualizar los radares, se descarta lo cargado y se baja de nuevo con señal. */
    fun reloadIfCleared() {
        if (!offlineData && loadOffline()) return
        if (!file.exists() && fetchedAt != 0L) {
            cameras = emptyList()
            fetchedAt = 0L
            sounded.clear()
            map.invalidate()
        }
    }

    fun update(loc: Location, online: Boolean) {
        if (online && !offlineData) maybeFetch(loc)
        checkAlert(loc)
    }

    private fun maybeFetch(loc: Location) {
        if (inFlight) return
        val result = FloatArray(1)
        Location.distanceBetween(loc.latitude, loc.longitude, areaLat, areaLon, result)
        val stale = System.currentTimeMillis() - fetchedAt > 7L * 24 * 3600 * 1000
        if (fetchedAt != 0L && result[0] < 12_000 && !stale) return
        inFlight = true
        val lat = loc.latitude
        val lon = loc.longitude
        executor.execute {
            try {
                val list = fetch(lat, lon)
                if (list != null) {
                    cameras = list
                    areaLat = lat
                    areaLon = lon
                    fetchedAt = System.currentTimeMillis()
                    save()
                    main.post { map.invalidate() }
                }
            } catch (e: Exception) {
            }
            inFlight = false
        }
    }

    private fun fetch(lat: Double, lon: Double): List<Camera>? {
        val d = 0.2
        val bbox = String.format(Locale.US, "%.4f,%.4f,%.4f,%.4f", lat - d, lon - d, lat + d, lon + d)
        val q = "[out:json][timeout:25];(" +
            "node[\"highway\"=\"speed_camera\"]($bbox);" +
            "relation[\"type\"=\"enforcement\"]($bbox););" +
            "out body;>;out skel qt;"
        val conn = URL("https://overpass-api.de/api/interpreter").openConnection() as HttpURLConnection
        conn.connectTimeout = 8000
        conn.readTimeout = 30000
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("User-Agent", ctx.packageName)
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        conn.outputStream.use { it.write(("data=" + URLEncoder.encode(q, "UTF-8")).toByteArray()) }
        if (conn.responseCode != 200) return null
        val elements = JSONObject(conn.inputStream.bufferedReader().use { it.readText() }).getJSONArray("elements")

        val coords = HashMap<Long, Pair<Double, Double>>()
        for (i in 0 until elements.length()) {
            val e = elements.getJSONObject(i)
            if (e.optString("type") == "node" && e.has("lat")) {
                coords[e.getLong("id")] = Pair(e.getDouble("lat"), e.getDouble("lon"))
            }
        }

        val out = LinkedHashMap<String, Camera>()
        fun add(lat: Double, lon: Double, kind: Int, limit: Int?) {
            val key = String.format(Locale.US, "%.4f,%.4f", lat, lon)
            val prev = out[key]
            if (prev == null || (prev.limit == null && limit != null)) out[key] = Camera(key, lat, lon, kind, limit)
        }

        for (i in 0 until elements.length()) {
            val e = elements.getJSONObject(i)
            val tags = e.optJSONObject("tags") ?: continue
            when (e.optString("type")) {
                "node" -> if (tags.optString("highway") == "speed_camera" && e.has("lat")) {
                    val kind = if (tags.optString("enforcement") == "traffic_signals") Camera.RED_LIGHT else Camera.SPEED
                    add(e.getDouble("lat"), e.getDouble("lon"), kind, parseLimit(tags.optString("maxspeed")))
                }
                "relation" -> {
                    val enforcement = tags.optString("enforcement")
                    val kind = when (enforcement) {
                        "maxspeed", "average_speed", "mindistance" -> Camera.SPEED
                        "traffic_signals" -> Camera.RED_LIGHT
                        else -> continue
                    }
                    val limit = parseLimit(tags.optString("maxspeed"))
                    val members = e.optJSONArray("members") ?: continue
                    for (m in 0 until members.length()) {
                        val member = members.getJSONObject(m)
                        if (member.optString("type") != "node") continue
                        val role = member.optString("role")
                        if (role != "device" && role != "from") continue
                        val c = coords[member.getLong("ref")] ?: continue
                        add(c.first, c.second, kind, limit)
                        break
                    }
                }
            }
        }
        return out.values.toList()
    }

    private fun parseLimit(v: String?): Int? =
        v?.let { Regex("^(\\d+)").find(it.trim())?.groupValues?.get(1)?.toIntOrNull() }

    private fun save() {
        try {
            val arr = JSONArray()
            cameras.forEach { c ->
                val o = JSONObject().put("id", c.id).put("lat", c.lat).put("lon", c.lon).put("kind", c.kind)
                if (c.limit != null) o.put("limit", c.limit)
                arr.put(o)
            }
            file.writeText(
                JSONObject().put("lat", areaLat).put("lon", areaLon).put("at", fetchedAt).put("items", arr).toString()
            )
        } catch (e: Exception) {
        }
    }

    /** La cámara más cercana que está adelante (±35° del rumbo) y a menos de 400 m. */
    private fun checkAlert(loc: Location) {
        val moving = loc.hasBearing() && loc.hasSpeed() && loc.speed > 4f
        var best: Camera? = null
        var bestDist = Float.MAX_VALUE
        if (moving) {
            val result = FloatArray(2)
            for (c in cameras) {
                Location.distanceBetween(loc.latitude, loc.longitude, c.lat, c.lon, result)
                val dist = result[0]
                if (dist > 400 || dist >= bestDist) continue
                var diff = abs(result[1] - loc.bearing) % 360
                if (diff > 180) diff = 360 - diff
                if (diff > 35) continue
                best = c
                bestDist = dist
            }
        }
        // Suena solo si vas por encima de la máxima (la de la cámara o la de la calle),
        // o si no se conoce ninguna. Una sola vez por cámara.
        if (best != null && best.id !in sounded) {
            val limit = best.limit ?: roadLimit()
            val kmh = loc.speed * 3.6f
            if (limit == null || kmh > limit + 2) {
                sounded.add(best.id)
                if (soundOn) beep()
            }
        }
        if (best?.id != current?.id || best != null) {
            current = best
            onAlert(best, if (best != null) bestDist.toInt() else 0)
        }
        // Memoria acotada: se olvidan las cámaras más viejas.
        while (sounded.size > 200) sounded.remove(sounded.first())
    }

    private fun beep() {
        val v = Prefs.alertVolume(ctx) / 100f
        soundPool.play(alertSound, v, v, 1, 0, 1f)
    }

    fun release() {
        soundPool.release()
    }

    /** Íconos dibujados a mano, solo para lo que está en pantalla y con zoom de calle. */
    private inner class CamerasOverlay : Overlay() {
        private val speedFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#FDD663") }
        private val redFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#F28B82") }
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#0E1116")
            style = Paint.Style.STROKE
        }
        private val glyph = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#0E1116") }
        private val lens = Path()
        private val p = Point()

        override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
            if (shadow || mapView.zoomLevelDouble < 12.5) return
            val box = mapView.boundingBox
            val d = mapView.resources.displayMetrics.density
            ring.strokeWidth = 1.5f * d
            for (c in cameras) {
                if (!box.contains(c.lat, c.lon)) continue
                mapView.projection.toPixels(GeoPoint(c.lat, c.lon), p)
                val x = p.x.toFloat()
                val y = p.y.toFloat()
                canvas.drawCircle(x, y, 10f * d, if (c.kind == Camera.RED_LIGHT) redFill else speedFill)
                canvas.drawCircle(x, y, 10f * d, ring)
                // Cámara: cuerpo + lente.
                canvas.drawRoundRect(x - 6f * d, y - 3.5f * d, x + 2f * d, y + 3.5f * d, 1.5f * d, 1.5f * d, glyph)
                lens.reset()
                lens.moveTo(x + 2f * d, y - 1.5f * d)
                lens.lineTo(x + 6f * d, y - 3.5f * d)
                lens.lineTo(x + 6f * d, y + 3.5f * d)
                lens.lineTo(x + 2f * d, y + 1.5f * d)
                lens.close()
                canvas.drawPath(lens, glyph)
            }
        }
    }
}
