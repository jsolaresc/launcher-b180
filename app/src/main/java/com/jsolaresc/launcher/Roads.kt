package com.jsolaresc.launcher

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * Calles y radares del AMBA sin conexión (calles-amba.sqlite, se carga por USB).
 * Da el nombre y la velocidad máxima de la calle por la que vas, los radares y
 * las calles que se ven para dibujar sus nombres derechos sobre el mapa.
 */
object Roads {
    const val FILE = "calles.db"
    private const val CELL = 0.005

    class Way(
        val id: Long,
        val name: String?,
        val maxspeed: Int?,
        /** 1 autopista … 8 servicio: menor = más importante. */
        val kind: Int,
        /** lat, lon intercalados. */
        val pts: DoubleArray
    )

    @Volatile
    private var db: SQLiteDatabase? = null
    private var opened = false

    fun file(ctx: Context) = File(ctx.filesDir, FILE)

    fun available(ctx: Context): Boolean = open(ctx) != null

    @Synchronized
    private fun open(ctx: Context): SQLiteDatabase? {
        if (opened) return db
        opened = true
        val f = file(ctx)
        if (!f.exists()) return null
        db = try {
            SQLiteDatabase.openDatabase(f.path, null, SQLiteDatabase.OPEN_READONLY)
        } catch (e: Exception) {
            null
        }
        return db
    }

    /** Tras importar un archivo nuevo, se vuelve a abrir. */
    @Synchronized
    fun reset() {
        try {
            db?.close()
        } catch (e: Exception) {
        }
        db = null
        opened = false
    }

    private fun cell(lat: Double, lon: Double): Long =
        floor((lat + 90) / CELL).toLong() * 100000 + floor((lon + 180) / CELL).toLong()

    private val cache = object : LinkedHashMap<Long, Way>(512, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Way>?) = size > 3000
    }

    /** Calles que pasan por el rectángulo (en grados). */
    @Synchronized
    fun waysIn(ctx: Context, south: Double, west: Double, north: Double, east: Double, maxCells: Int = 400): List<Way> {
        val d = open(ctx) ?: return emptyList()
        val c0 = floor((south + 90) / CELL).toLong()
        val c1 = floor((north + 90) / CELL).toLong()
        val r0 = floor((west + 180) / CELL).toLong()
        val r1 = floor((east + 180) / CELL).toLong()
        if ((c1 - c0 + 1) * (r1 - r0 + 1) > maxCells) return emptyList()
        val ids = LinkedHashSet<Long>()
        for (a in c0..c1) {
            d.rawQuery(
                "SELECT way FROM cells WHERE cell BETWEEN ? AND ?",
                arrayOf((a * 100000 + r0).toString(), (a * 100000 + r1).toString())
            ).use { c -> while (c.moveToNext()) ids.add(c.getLong(0)) }
        }
        val out = ArrayList<Way>(ids.size)
        val missing = ids.filter { cache[it] == null }
        for (chunk in missing.chunked(400)) {
            d.rawQuery(
                "SELECT id, name, maxspeed, kind, pts FROM ways WHERE id IN (${chunk.joinToString(",")})",
                null
            ).use { c ->
                while (c.moveToNext()) {
                    val blob = c.getBlob(4)
                    val bb = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN)
                    val pts = DoubleArray(blob.size / 4)
                    for (i in pts.indices) pts[i] = bb.int / 1e6
                    val w = Way(
                        c.getLong(0),
                        if (c.isNull(1)) null else c.getString(1),
                        if (c.isNull(2)) null else c.getInt(2),
                        c.getInt(3),
                        pts
                    )
                    cache[w.id] = w
                }
            }
        }
        for (id in ids) cache[id]?.let { out.add(it) }
        return out
    }

    /**
     * La calle más probable para esa posición y rumbo (null si no hay ninguna a menos de 30 m).
     * Igual criterio que con internet: distancia + diferencia de rumbo.
     */
    fun best(ctx: Context, lat: Double, lon: Double, bearing: Double?): Way? {
        val d = 0.0004
        val ways = waysIn(ctx, lat - d, lon - d, lat + d, lon + d)
        val cosLat = cos(Math.toRadians(lat))
        var best: Way? = null
        var bestScore = Double.MAX_VALUE
        for (w in ways) {
            val p = w.pts
            var i = 0
            while (i + 3 < p.size) {
                val ax = (p[i + 1] - lon) * 111320 * cosLat
                val ay = (p[i] - lat) * 110540
                val bx = (p[i + 3] - lon) * 111320 * cosLat
                val by = (p[i + 2] - lat) * 110540
                val dist = pointToSegment(ax, ay, bx, by)
                if (dist < 30) {
                    var penalty = 0.0
                    if (bearing != null) {
                        val seg = (Math.toDegrees(atan2(bx - ax, by - ay)) + 360) % 360
                        var diff = abs(seg - bearing) % 180
                        if (diff > 90) diff = 180 - diff
                        penalty = diff * 0.5
                    }
                    // A igual distancia, mejor una calle con nombre que un pasaje sin nombre.
                    val score = dist + penalty + (if (w.name == null) 10 else 0)
                    if (score < bestScore) {
                        bestScore = score
                        best = w
                    }
                }
                i += 2
            }
        }
        return best
    }

    class Cam(val lat: Double, val lon: Double, val kind: Int, val limit: Int?)

    fun cameras(ctx: Context): List<Cam>? {
        val d = open(ctx) ?: return null
        return try {
            val out = ArrayList<Cam>()
            d.rawQuery("SELECT lat, lon, kind, maxspeed FROM cameras", null).use { c ->
                while (c.moveToNext()) {
                    out.add(Cam(c.getDouble(0), c.getDouble(1), c.getInt(2), if (c.isNull(3)) null else c.getInt(3)))
                }
            }
            out
        } catch (e: Exception) {
            null
        }
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
}
