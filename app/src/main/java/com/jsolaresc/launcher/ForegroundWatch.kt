package com.jsolaresc.launcher

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

/**
 * Opcional (Accesibilidad): avisa qué app está adelante, para mostrar el panel del
 * launcher solo sobre OsmAnd y esconderlo al abrir otra app. No lee el contenido de nada.
 */
class ForegroundWatch : AccessibilityService() {

    override fun onServiceConnected() {
        Foreground.enabled = true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        Foreground.report(pkg, event.className?.toString())
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        Foreground.enabled = false
        super.onDestroy()
    }
}

object Foreground {
    @Volatile
    var enabled = false

    var listener: ((pkg: String, cls: String?) -> Unit)? = null

    fun report(pkg: String, cls: String?) {
        // La barra del sistema y el teclado no cuentan como "otra app".
        if (pkg == "com.android.systemui" || pkg.contains("inputmethod") || pkg.contains("keyboard")) return
        listener?.invoke(pkg, cls)
    }
}
