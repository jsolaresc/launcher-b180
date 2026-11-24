package com.jsolaresc.launcher

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Memoria de lo que pasó, para el reporte (Ajustes → Generar reporte):
 * los últimos eventos importantes y los cierres inesperados.
 */
object Diag {
    private const val MAX = 300
    private val events = ArrayDeque<String>()
    private val fmt = SimpleDateFormat("dd/MM HH:mm:ss", Locale("es", "AR"))

    /** Último estado de los satélites (lo actualiza el mapa). */
    @Volatile
    var gnss: String = "sin datos"

    @Synchronized
    fun log(tag: String, msg: String) {
        events.addLast("${fmt.format(RealTime.date())}  [$tag] $msg")
        while (events.size > MAX) events.removeFirst()
    }

    @Synchronized
    fun events(): List<String> = events.toList()

    private fun crashFile(ctx: Context) = File(ctx.filesDir, "crash.txt")

    /** Guarda los cierres inesperados (se agregan al reporte) y deja seguir al manejo normal. */
    fun installCrashLog(ctx: Context) {
        val current = Thread.getDefaultUncaughtExceptionHandler()
        if (current is CrashHandler) return
        Thread.setDefaultUncaughtExceptionHandler(CrashHandler(ctx.applicationContext, current))
    }

    fun crashes(ctx: Context): String = try {
        crashFile(ctx).takeIf { it.exists() }?.readText() ?: ""
    } catch (e: Exception) {
        ""
    }

    private class CrashHandler(val ctx: Context, val next: Thread.UncaughtExceptionHandler?) : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(t: Thread, e: Throwable) {
            try {
                val sw = StringWriter()
                e.printStackTrace(PrintWriter(sw))
                val f = crashFile(ctx)
                val old = if (f.exists()) f.readText().takeLast(30_000) else ""
                f.writeText(old + "\n=== ${fmt.format(RealTime.date())} (${BuildConfig.VERSION_NAME}) ===\n" + sw.toString().take(8000))
            } catch (x: Exception) {
            }
            next?.uncaughtException(t, e)
        }
    }
}
