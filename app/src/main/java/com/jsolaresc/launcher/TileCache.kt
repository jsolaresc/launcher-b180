package com.jsolaresc.launcher

import android.content.Context
import org.osmdroid.tileprovider.modules.DatabaseFileArchive
import org.osmdroid.tileprovider.modules.SqlTileWriter
import org.osmdroid.tileprovider.tilesource.ITileSource
import java.io.InputStream

/**
 * Base de datos de mapa guardado (la misma que usa osmdroid).
 * Sabe cuánto ocupa cada zona descargada, borrar zonas y, si en Ajustes se
 * apaga "Guardar lo que recorro", no guarda lo que el mapa baja al andar.
 */
class TileCache(private val ctx: Context) : SqlTileWriter() {

    override fun saveFile(source: ITileSource, index: Long, stream: InputStream, expires: Long?): Boolean =
        if (Prefs.saveVisited(ctx)) super.saveFile(source, index, stream, expires) else false

    /** Bytes por zona (id) y los de "lo recorrido" (lo que no cae en ninguna zona) bajo la clave "". */
    fun usage(areas: List<OfflineAreas.Area>): Map<String, Long> {
        val out = HashMap<String, Long>()
        val db = getDb() ?: return out
        db.rawQuery(
            "SELECT ${DatabaseFileArchive.COLUMN_KEY}, length(${DatabaseFileArchive.COLUMN_TILE}) FROM ${DatabaseFileArchive.TABLE}",
            null
        ).use { c ->
            while (c.moveToNext()) {
                val owner = areas.firstOrNull { it.contains(c.getLong(0)) }?.id ?: ""
                out[owner] = (out[owner] ?: 0L) + c.getLong(1)
            }
        }
        return out
    }

    /** Borra las teselas que cumplan la condición (según la clave) y achica el archivo. */
    fun deleteWhere(match: (Long) -> Boolean) {
        val db = getDb() ?: return
        val doomed = ArrayList<Pair<Long, String>>()
        db.rawQuery(
            "SELECT ${DatabaseFileArchive.COLUMN_KEY}, ${DatabaseFileArchive.COLUMN_PROVIDER} FROM ${DatabaseFileArchive.TABLE}",
            null
        ).use { c ->
            while (c.moveToNext()) if (match(c.getLong(0))) doomed.add(c.getLong(0) to c.getString(1))
        }
        if (doomed.isEmpty()) return
        db.beginTransaction()
        try {
            for ((key, provider) in doomed) {
                db.delete(
                    DatabaseFileArchive.TABLE,
                    "${DatabaseFileArchive.COLUMN_KEY}=? AND ${DatabaseFileArchive.COLUMN_PROVIDER}=?",
                    arrayOf(key.toString(), provider)
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        // Sin esto el archivo no se achica y el espacio no vuelve al estéreo.
        try {
            db.execSQL("VACUUM")
        } catch (e: Exception) {
        }
    }

    companion object {
        /** La clave de osmdroid es ((z << z) + x << z) + y: devuelve (z, x, y). */
        fun decode(key: Long): Triple<Int, Int, Int>? {
            for (z in 0..22) {
                if (key shr (2 * z) == z.toLong()) {
                    val mask = (1L shl z) - 1
                    return Triple(z, ((key shr z) and mask).toInt(), (key and mask).toInt())
                }
            }
            return null
        }
    }
}
