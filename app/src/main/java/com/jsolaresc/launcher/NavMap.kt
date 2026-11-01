package com.jsolaresc.launcher

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Point
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.MapTileProviderBasic
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.CopyrightOverlay
import org.osmdroid.views.overlay.Overlay
import java.io.File

/**
 * Mapa propio: posición y rumbo por GPS, velocidad y seguimiento.
 * Usa teselas oscuras de CARTO (datos de OpenStreetMap). Lo que ya se vio queda
 * guardado en el estéreo, así que esas zonas se ven también sin señal.
 */
class NavMap(private val activity: Activity, root: View) : LocationListener {

    companion object {
        private val BUENOS_AIRES = GeoPoint(-34.6037, -58.3816)
        private const val FOLLOW_ZOOM = 17.0

        val DARK_TILES = CartoTiles("CartoDark2", "dark_all")
        val LIGHT_TILES = CartoTiles("CartoVoyager2", "rastertiles/voyager")

        /** Debe llamarse antes de inflar el layout que contiene el MapView. */
        fun configure(ctx: Context) {
            val base = File(ctx.filesDir, "osmdroid")
            Configuration.getInstance().apply {
                userAgentValue = ctx.packageName
                osmdroidBasePath = base
                osmdroidTileCache = File(base, "tiles")
                // Hasta ~1,5 GB de mapa guardado (AMBA en los dos temas entra);
                // al pasarse, borra lo más viejo. El estéreo tiene ~8 GB libres.
                tileFileSystemCacheMaxBytes = 1536L * 1024 * 1024
                tileFileSystemCacheTrimBytes = 1300L * 1024 * 1024
                cacheMapTileCount = 12
                tileDownloadThreads = 2
            }
        }
    }

    private val map: MapView = root.findViewById(R.id.map)
    private val statusText: TextView = root.findViewById(R.id.map_status)
    private val recenter: View = root.findViewById(R.id.btn_recenter)
    private val locationManager = activity.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val me = MeOverlay(activity)

    /** Calle por la que vas: se muestra debajo de la flecha. */
    var street: String?
        get() = me.street
        set(value) {
            if (value != me.street) {
                me.street = value
                map.invalidate()
            }
        }

    private var follow = true
    var lastFix: Location? = null
        private set
    private var running = false
    private var gpsStatus: String? = null

    /** Cada ubicación nueva (para velocidad, máxima, distancias y clima). */
    var onLocation: ((Location) -> Unit)? = null

    /** Sin internet: el mapa usa lo guardado y se avisa. */
    var offline = false
        set(value) {
            field = value
            renderStatus()
        }

    /** Centro del mapa, para guardar un lugar. */
    val center: GeoPoint get() = map.mapCenter as GeoPoint

    /** Mientras se elige un lugar, el mapa no sigue al auto. */
    fun setPicking(picking: Boolean) {
        if (picking) {
            follow = false
            recenter.visibility = View.GONE
        } else {
            follow = true
            lastFix?.let { map.controller.setCenter(GeoPoint(it.latitude, it.longitude)) }
        }
    }

    init {
        val tiles = if (Theme.isNight(activity)) DARK_TILES else LIGHT_TILES
        // Proveedor propio para poder no guardar lo recorrido (opción en Ajustes).
        map.tileProvider = MapTileProviderBasic(activity.applicationContext, tiles, TileCache(activity.applicationContext))
        map.setTileSource(tiles)
        map.setMultiTouchControls(true)
        map.zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
        map.isTilesScaledToDpi = true
        map.minZoomLevel = 4.0
        map.maxZoomLevel = 19.0
        map.controller.setZoom(FOLLOW_ZOOM)
        map.controller.setCenter(BUENOS_AIRES)

        val copyright = CopyrightOverlay(activity)
        copyright.setTextColor(Color.parseColor("#8A93A0"))
        copyright.setTextSize(9)
        map.overlays.add(copyright)
        map.overlays.add(me)

        // Si movés el mapa con el dedo, deja de seguirte hasta que toques "centrar".
        map.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_MOVE && follow) {
                follow = false
                recenter.visibility = View.VISIBLE
            }
            false
        }
        recenter.setOnClickListener {
            follow = true
            recenter.visibility = View.GONE
            map.controller.setZoom(FOLLOW_ZOOM)
            lastFix?.let { map.controller.animateTo(GeoPoint(it.latitude, it.longitude)) }
        }
    }

    fun hasPermission() =
        activity.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun start() {
        map.onResume()
        if (!hasPermission()) {
            showStatus(activity.getString(R.string.gps_no_permission))
            return
        }
        if (running) return
        running = true
        val gpsOn = try {
            locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
        } catch (e: Exception) {
            false
        }
        try {
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this)
        } catch (e: Exception) {
        }
        val last = try {
            locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
        } catch (e: Exception) {
            null
        }
        if (last != null) onLocationChanged(last)
        if (!gpsOn) showStatus(activity.getString(R.string.gps_off))
        else if (last == null) showStatus(activity.getString(R.string.gps_searching))
    }

    fun stop() {
        map.onPause()
        if (running) {
            try {
                locationManager.removeUpdates(this)
            } catch (e: Exception) {
            }
        }
        running = false
    }

    private fun showStatus(text: String?) {
        gpsStatus = text
        renderStatus()
    }

    private fun renderStatus() {
        // Sin conexión no se avisa en el mapa: ya lo muestra el ícono de WiFi del dock.
        val text = gpsStatus
        statusText.text = text
        statusText.visibility = if (text == null) View.GONE else View.VISIBLE
    }

    override fun onLocationChanged(location: Location) {
        lastFix = location
        me.point = GeoPoint(location.latitude, location.longitude)
        // El rumbo del GPS solo es confiable en movimiento.
        if (location.hasBearing() && location.hasSpeed() && location.speed > 1.5f) me.bearing = location.bearing

        if (location.provider == LocationManager.GPS_PROVIDER) showStatus(null)

        if (follow) map.controller.setCenter(me.point)
        map.invalidate()
        onLocation?.invoke(location)
    }

    override fun onProviderEnabled(provider: String) {
        if (provider == LocationManager.GPS_PROVIDER) showStatus(activity.getString(R.string.gps_searching))
    }

    override fun onProviderDisabled(provider: String) {
        if (provider == LocationManager.GPS_PROVIDER) showStatus(activity.getString(R.string.gps_off))
    }

    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {
    }

    /** Punto azul con flecha de rumbo, dibujado a mano (más liviano que un ícono). */
    private class MeOverlay(ctx: Context) : Overlay() {
        var point: GeoPoint? = null
        var bearing: Float? = null
        var street: String? = null

        private val pill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ctx.getColor(R.color.floating) }
        private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ctx.getColor(R.color.text)
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
        }

        private val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#408AB4F8") }
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#1A73E8") }
        private val arrow = Path()
        private val screen = Point()

        override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
            if (shadow) return
            val p = point ?: return
            mapView.projection.toPixels(p, screen)
            val d = mapView.resources.displayMetrics.density
            val x = screen.x.toFloat()
            val y = screen.y.toFloat()

            canvas.drawCircle(x, y, 22f * d, halo)

            // Nombre de la calle, en una píldora debajo de la flecha.
            street?.let { name ->
                label.textSize = 11f * d
                val text = if (name.length > 32) name.take(31) + "…" else name
                val w = label.measureText(text) / 2 + 9f * d
                val top = y + 25f * d
                canvas.drawRoundRect(x - w, top, x + w, top + 20f * d, 10f * d, 10f * d, pill)
                canvas.drawText(text, x, top + 14f * d, label)
            }
            canvas.drawCircle(x, y, 11f * d, ring)

            val b = bearing
            if (b == null) {
                canvas.drawCircle(x, y, 8f * d, dot)
                return
            }
            canvas.save()
            canvas.rotate(b, x, y)
            arrow.reset()
            arrow.moveTo(x, y - 9f * d)
            arrow.lineTo(x + 7f * d, y + 7f * d)
            arrow.lineTo(x, y + 3.5f * d)
            arrow.lineTo(x - 7f * d, y + 7f * d)
            arrow.close()
            canvas.drawPath(arrow, dot)
            canvas.restore()
        }
    }
}
