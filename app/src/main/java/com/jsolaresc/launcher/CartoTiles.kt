package com.jsolaresc.launcher

import android.content.Context
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.MapTileIndex
import java.util.concurrent.Executors

/**
 * Teselas de CARTO (datos de OpenStreetMap). Desde 2026 CARTO pide una clave
 * gratuita: sin ella las teselas vienen con la marca "API KEY REQUIRED".
 * La clave se toma al compilar, del secreto CARTO_KEY del repo en GitHub.
 */
class CartoTiles(name: String, private val style: String, private val retina: Boolean = false) : XYTileSource(
    name, 0, 19, 256, ".png",
    // Con clave, CARTO indica el dominio sin subdominios (a., b., …).
    arrayOf("https://basemaps.cartocdn.com/$style/"),
    "© OpenStreetMap · © CARTO"
) {
    override fun getTileURLString(index: Long): String {
        val url = baseUrl + MapTileIndex.getZoom(index) + "/" + MapTileIndex.getX(index) + "/" +
            MapTileIndex.getY(index) + (if (retina) "@2x" else "") + mImageFilenameEnding
        return if (BuildConfig.CARTO_KEY.isEmpty()) url else url + "?key=" + BuildConfig.CARTO_KEY
    }

    companion object {
        /** Nombres anteriores: lo guardado con ellos vino sin clave (con la marca) y se borra una vez. */
        private val OLD = listOf("CartoDarkMatter", "CartoVoyager", "CartoDark2")

        fun purgeOld(ctx: Context) {
            val prefs = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
            if (prefs.getBoolean("purged_old_tiles_2", false)) return
            val app = ctx.applicationContext
            Executors.newSingleThreadExecutor().execute {
                try {
                    TileCache(app).deleteProviders(OLD)
                    prefs.edit().putBoolean("purged_old_tiles_2", true).apply()
                } catch (e: Exception) {
                }
            }
        }
    }
}
