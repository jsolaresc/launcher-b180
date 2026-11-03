package com.jsolaresc.launcher

import android.content.Context

/**
 * Navegación sin conexión con OsmAnd (Organic Maps no anda en este estéreo:
 * necesita OpenGL ES 3 y la GPU Mali-450 tiene 2). Un destino rápido le pide a OsmAnd
 * que navegue directo y el launcher vuelve al inicio cuando arranca.
 */
object NavLaunch {
    /** OsmAnd~ de F-Droid (mapas ilimitados) y OsmAnd de Play. */
    val OSMAND = listOf("net.osmand.plus", "net.osmand")

    @Volatile
    var returnHome = false

    fun osmand(ctx: Context): String? = OSMAND.firstOrNull { ctx.packageManager.getLaunchIntentForPackage(it) != null }

    fun isOsmand(pkg: String) = pkg in OSMAND
}
