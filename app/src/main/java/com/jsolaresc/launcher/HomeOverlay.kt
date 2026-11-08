package com.jsolaresc.launcher

import android.app.Activity
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageButton

/**
 * Inicio con el mapa de OsmAnd: OsmAnd queda de fondo en toda la pantalla y el launcher
 * pone encima su columna (música y destinos) y el dock, como ventanas flotantes.
 * OsmAnd sabe que ese lugar está ocupado y acomoda sus botones en el resto.
 */
class HomeOverlay(private val activity: Activity) {

    private val wm = activity.getSystemService(Activity.WINDOW_SERVICE) as WindowManager
    private val d = activity.resources.displayMetrics.density
    private val column: View = activity.findViewById(R.id.right_column)
    private val dock: View = activity.findViewById(R.id.dock_bar)
    private val toggle = ImageButton(activity)
    private var attached = false

    /** Mapa completo: el panel se esconde y queda solo el botón para volver. */
    var full = false
        private set
    private var shown = true

    val columnWidth: Int
    val dockHeight = (54 * d).toInt()

    init {
        val w = activity.resources.displayMetrics.widthPixels
        columnWidth = ((w - 24 * d) * 0.3f).toInt()
    }

    private fun params(width: Int, height: Int, gravity: Int, x: Int, y: Int) = WindowManager.LayoutParams(
        width, height,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT
    ).apply {
        this.gravity = gravity
        this.x = x
        this.y = y
    }

    fun attach() {
        if (attached) return
        attached = true
        val h = activity.resources.displayMetrics.heightPixels
        (column.parent as? ViewGroup)?.removeView(column)
        (dock.parent as? ViewGroup)?.removeView(dock)
        dock.setBackgroundColor(activity.getColor(R.color.ground))

        wm.addView(column, params(columnWidth, h - dockHeight - (8 * d).toInt(), Gravity.TOP or Gravity.END, (8 * d).toInt(), (8 * d).toInt()))
        wm.addView(dock, params(WindowManager.LayoutParams.MATCH_PARENT, dockHeight, Gravity.BOTTOM or Gravity.START, 0, 0))

        toggle.setBackgroundResource(R.drawable.bg_map_button)
        val pad = (10 * d).toInt()
        toggle.setPadding(pad, pad, pad, pad)
        toggle.scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
        toggle.setOnClickListener { setFull(!full) }
        wm.addView(toggle, toggleParams())
        render()
    }

    private fun toggleParams(): WindowManager.LayoutParams {
        val size = (44 * d).toInt()
        val x = if (full) (12 * d).toInt() else columnWidth + (8 * d + 8 * d + 12 * d).toInt()
        return params(size, size, Gravity.TOP or Gravity.END, x, (12 * d).toInt())
    }

    fun setFull(on: Boolean, relayout: Boolean = true) {
        full = on
        if (attached) wm.updateViewLayout(toggle, toggleParams())
        applyToOsmAnd(relayout)
        render()
    }

    fun show() {
        shown = true
        render()
    }

    fun hide() {
        shown = false
        render()
    }

    private fun render() {
        if (!attached) return
        column.visibility = if (shown && !full) View.VISIBLE else View.GONE
        dock.visibility = if (shown && !full) View.VISIBLE else View.GONE
        toggle.visibility = if (shown) View.VISIBLE else View.GONE
        toggle.setImageResource(if (full) R.drawable.ic_close_full else R.drawable.ic_open_full)
        toggle.contentDescription = activity.getString(if (full) R.string.map_exit_fullscreen else R.string.map_fullscreen)
    }

    /** Le dice a OsmAnd cuánto lugar ocupa el panel y si debe mostrar pocos botones. */
    /**
     * Le dice a OsmAnd dónde queda su mapa. En el inicio es una tarjeta: el hueco entre
     * el borde izquierdo, la columna del launcher y el dock (así queda centrado y el nombre
     * de la calle no queda tapado). OsmAnd lo aplica al volver a primer plano: con
     * [relayout] se fuerza eso enseguida.
     */
    fun applyToOsmAnd(relayout: Boolean = false) {
        val gap = (8 * d).toInt()
        if (full) {
            OsmAnd.setMargins(activity, 0, 0, 0, 0)
            OsmAnd.setMinimal(activity, false)
        } else {
            OsmAnd.setMargins(activity, gap, gap, columnWidth + 2 * gap, dockHeight)
            OsmAnd.setMinimal(activity, true)
        }
        if (relayout) handler.postDelayed({ relayoutOsmAnd() }, 250)
    }

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    private fun relayoutOsmAnd() {
        if (!attached || !shown) return
        try {
            activity.applicationContext.startActivity(
                android.content.Intent(activity, RelayoutActivity::class.java)
                    .addFlags(
                        android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_NO_ANIMATION or
                            android.content.Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                    )
            )
        } catch (e: Exception) {
        }
    }

    fun detach() {
        if (!attached) return
        attached = false
        for (v in listOf(column, dock, toggle)) {
            try {
                wm.removeView(v)
            } catch (e: Exception) {
            }
        }
    }
}
