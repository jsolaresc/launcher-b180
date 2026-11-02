package com.jsolaresc.launcher

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Opcional (se activa en Accesibilidad): cuando un destino rápido abre la ruta en
 * Organic Maps, toca "Iniciar" por vos. Solo actúa en Organic Maps y solo justo
 * después de tocar un destino en el launcher.
 */
class NavStarter : AccessibilityService() {

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!NavLaunch.pending()) return
        val root = rootInActiveWindow ?: return
        try {
            val start = root.findAccessibilityNodeInfosByViewId("${NavLaunch.ORGANIC}:id/start")
                .firstOrNull { it.isVisibleToUser && it.isEnabled }
                ?: return
            if (start.performAction(AccessibilityNodeInfo.ACTION_CLICK)) NavLaunch.started()
        } catch (e: Exception) {
        }
    }

    override fun onInterrupt() {}
}

/** Estado de "destino rápido → Organic Maps → volver al inicio". */
object NavLaunch {
    const val ORGANIC = "app.organicmaps"

    @Volatile
    private var pendingUntil = 0L

    @Volatile
    var returnHome = false

    /** Se tocó un destino: durante 90 s se espera que arranque la navegación. */
    fun begin() {
        pendingUntil = System.currentTimeMillis() + 90_000
        returnHome = true
    }

    fun pending() = System.currentTimeMillis() < pendingUntil

    fun started() {
        pendingUntil = 0
    }
}
