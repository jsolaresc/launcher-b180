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

    /** Borra todo lo guardado de esos estilos (nombres de proveedor). */
    fun deleteProviders(names: List<String>) {
        val db = getDb() ?: return
        for (n in names) db.delete(DatabaseFileArchive.TABLE, "${DatabaseFileArchive.COLUMN_PROVIDER}=?", arrayOf(n))
        try {
            db.execSQL("VACUUM")
        } catch (e: Exception) {
        }
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

    /** Suma las teselas de un archivo con el mismo formato (mapa-amba.sqlite) a lo guardado. */
    /**
     * Se copia de a tandas chicas: entre tanda y tanda el mapa puede seguir leyendo
     * (una sola copia gigante lo dejaba en blanco hasta terminar). Avisa el avance.
     */
    fun importArchive(path: String, onProgress: (done: Int, total: Int) -> Unit): Int {
        val db = getDb() ?: return 0
        db.execSQL("ATTACH DATABASE ? AS imp", arrayOf(path))
        try {
            var total = 0
            var minId = 0L
            var maxId = -1L
            db.rawQuery("SELECT count(*), min(rowid), max(rowid) FROM imp.tiles", null).use { c ->
                if (c.moveToFirst()) {
                    total = c.getInt(0)
                    minId = c.getLong(1)
                    maxId = c.getLong(2)
                }
            }
            // Vencen en un año: hasta entonces, con señal no se vuelven a bajar.
            val expires = RealTime.now() + 365L * 24 * 3600 * 1000
            val step = 1000L
            var from = minId
            var done = 0
            while (from <= maxId) {
                val to = from + step
                db.beginTransaction()
                try {
                    db.execSQL(
                        "INSERT OR REPLACE INTO ${DatabaseFileArchive.TABLE} " +
                            "(${DatabaseFileArchive.COLUMN_KEY}, ${DatabaseFileArchive.COLUMN_PROVIDER}, ${DatabaseFileArchive.COLUMN_TILE}, $COLUMN_EXPIRES) " +
                            "SELECT key, provider, tile, $expires FROM imp.tiles WHERE rowid >= $from AND rowid < $to"
                    )
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
                done = minOf(total, done + step.toInt())
                onProgress(done, total)
                from = to
            }
            return total
        } finally {
            db.execSQL("DETACH DATABASE imp")
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
