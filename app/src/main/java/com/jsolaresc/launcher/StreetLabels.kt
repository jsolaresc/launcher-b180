package com.jsolaresc.launcher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Point
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Nombres de las calles dibujados por el launcher (con calles-amba cargado):
 * siempre derechos aunque gire el mapa, más grandes y visibles en el zoom normal.
 * Las avenidas tienen prioridad; los que se pisan no se dibujan.
 */
class StreetLabels(private val ctx: Context, night: Boolean) : Overlay() {

    private val d = ctx.resources.displayMetrics.density
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = if (night) 0xFFE8EAED.toInt() else 0xFF2B2B2B.toInt()
        textSize = 12f * d
        isFakeBoldText = true
        textAlign = Paint.Align.CENTER
    }
    private val halo = Paint(text).apply {
        color = if (night) 0xFF0C0C0C.toInt() else 0xFFFFFFFF.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 3.5f * d
    }

    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var ways: List<Roads.Way> = emptyList()
    private var loadedBox: DoubleArray? = null
    private var loading = false
    private var mapView: MapView? = null

    private val p1 = Point()
    private val p2 = Point()
    private val g1 = GeoPoint(0.0, 0.0)
    private val g2 = GeoPoint(0.0, 0.0)

    override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow || mapView.zoomLevelDouble < 15.0) return
        this.mapView = mapView
        val box = mapView.boundingBox
        ensureLoaded(box.latSouth, box.lonWest, box.latNorth, box.lonEast)

        val pj = mapView.projection
        val orientation = mapView.mapOrientation
        val placed = ArrayList<RectF>()
        val w = canvas.width
        val h = canvas.height
        var drawn = 0

        // Avenidas primero.
        for (way in ways) {
            val name = way.name ?: continue
            if (drawn >= 28) break
            val labelW = text.measureText(name)
            // Tramo más largo visible, para que el nombre entre.
            var bestLen = 0.0
            var bx = 0f
            var by = 0f
            var bAngle = 0f
            val p = way.pts
            var i = 0
            while (i + 3 < p.size) {
                g1.setCoords(p[i], p[i + 1])
                g2.setCoords(p[i + 2], p[i + 3])
                pj.toPixels(g1, p1)
                pj.toPixels(g2, p2)
                i += 2
                val mx = (p1.x + p2.x) / 2f
                val my = (p1.y + p2.y) / 2f
                if (mx < 0 || my < 0 || mx > w || my > h) continue
                val len = hypot((p2.x - p1.x).toDouble(), (p2.y - p1.y).toDouble())
                if (len > bestLen) {
                    bestLen = len
                    bx = mx
                    by = my
                    bAngle = Math.toDegrees(atan2((p2.y - p1.y).toDouble(), (p2.x - p1.x).toDouble())).toFloat()
                }
            }
            if (bestLen < labelW + 12 * d) continue

            // Derecho en pantalla: el ángulo final (contando el giro del mapa) queda entre -90° y 90°.
            var screen = bAngle + orientation
            while (screen > 90f) screen -= 180f
            while (screen < -90f) screen += 180f
            val angle = screen - orientation

            // Caja aproximada para no pisar otros nombres.
            val rad = Math.toRadians(screen.toDouble())
            val hw = (abs(cos(rad)) * labelW / 2 + abs(sin(rad)) * text.textSize / 2).toFloat() + 4 * d
            val hh = (abs(sin(rad)) * labelW / 2 + abs(cos(rad)) * text.textSize / 2).toFloat() + 4 * d
            val rect = RectF(bx - hw, by - hh, bx + hw, by + hh)
            if (placed.any { RectF.intersects(it, rect) }) continue
            placed.add(rect)

            canvas.save()
            canvas.rotate(angle, bx, by)
            val baseline = by + text.textSize * 0.35f
            canvas.drawText(name, bx, baseline, halo)
            canvas.drawText(name, bx, baseline, text)
            canvas.restore()
            drawn++
        }
    }

    /** Pide a la base las calles de la zona visible (con margen) cuando el mapa se mueve fuera de lo cargado. */
    private fun ensureLoaded(s: Double, w: Double, n: Double, e: Double) {
        val box = loadedBox
        if (box != null && s >= box[0] && w >= box[1] && n <= box[2] && e <= box[3]) return
        if (loading) return
        loading = true
        val mlat = (n - s) * 0.5
        val mlon = (e - w) * 0.5
        val want = doubleArrayOf(s - mlat, w - mlon, n + mlat, e + mlon)
        executor.execute {
            val list = try {
                Roads.waysIn(ctx, want[0], want[1], want[2], want[3])
            } catch (ex: Exception) {
                emptyList()
            }
            main.post {
                ways = list.filter { it.name != null }.sortedBy { it.kind }
                loadedBox = want
                loading = false
                mapView?.invalidate()
            }
        }
    }
}
