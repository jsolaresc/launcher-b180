package com.jsolaresc.launcher

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.osmdroid.util.BoundingBox

/** Zonas de mapa descargadas desde Ajustes (AMBA, alrededor tuyo). */
object OfflineAreas {
    const val ZOOM_MIN = 10
    const val ZOOM_MAX = 16

    class Area(val id: String, val name: String, val boxes: List<BoundingBox>, val date: Long) {
        /** Si la tesela (clave de osmdroid) cae dentro de la zona. */
        fun contains(key: Long): Boolean {
            val (z, x, y) = TileCache.decode(key) ?: return false
            if (z < ZOOM_MIN || z > ZOOM_MAX) return false
            return boxes.any { b ->
                x in tileX(b.lonWest, z)..tileX(b.lonEast, z) && y in tileY(b.latNorth, z)..tileY(b.latSouth, z)
            }
        }
    }

    fun tileX(lon: Double, z: Int) = ((lon + 180) / 360 * (1 shl z)).toInt()

    fun tileY(lat: Double, z: Int): Int {
        val r = Math.toRadians(lat)
        return ((1 - Math.log(Math.tan(r) + 1 / Math.cos(r)) / Math.PI) / 2 * (1 shl z)).toInt()
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("offline_areas", Context.MODE_PRIVATE)

    fun list(ctx: Context): List<Area> = try {
        val arr = JSONArray(prefs(ctx).getString("list", "[]"))
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val b = o.getJSONArray("boxes")
            Area(
                o.getString("id"), o.getString("name"),
                (0 until b.length()).map { j ->
                    val q = b.getJSONArray(j)
                    BoundingBox(q.getDouble(0), q.getDouble(1), q.getDouble(2), q.getDouble(3))
                },
                o.getLong("date")
            )
        }
    } catch (e: Exception) {
        emptyList()
    }

    private fun write(ctx: Context, areas: List<Area>) {
        val arr = JSONArray()
        for (a in areas) {
            val boxes = JSONArray()
            for (b in a.boxes) boxes.put(JSONArray(listOf(b.latNorth, b.lonEast, b.latSouth, b.lonWest)))
            arr.put(JSONObject().put("id", a.id).put("name", a.name).put("boxes", boxes).put("date", a.date))
        }
        prefs(ctx).edit().putString("list", arr.toString()).apply()
    }

    /** Agrega o reemplaza (mismo id). */
    fun save(ctx: Context, area: Area) = write(ctx, list(ctx).filter { it.id != area.id } + area)

    fun remove(ctx: Context, id: String) = write(ctx, list(ctx).filter { it.id != id })
}
