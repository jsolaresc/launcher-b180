package com.jsolaresc.launcher

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.usb.UsbManager
import android.location.LocationManager
import android.os.Build
import android.os.SystemClock
import java.io.File

/**
 * Reporte para diagnosticar a distancia: versión, permisos, GPS, pendrive, apps
 * relacionadas, eventos recientes y errores. No incluye direcciones ni datos personales.
 */
object Report {
    const val FILE = "reporte-launcher.txt"

    private val APPS = listOf(
        "net.osmand.plus" to "OsmAnd",
        "net.osmand" to "OsmAnd (Play)",
        "com.andrerinas.headunitrevived" to "Open Headunit",
        "gb.xxy.hr" to "Headunit Reloaded",
        "gb.xxy.trial" to "Headunit Reloaded (prueba)",
        "com.google.android.apps.youtube.music" to "YouTube Music",
        "com.google.android.apps.maps" to "Google Maps",
        "com.ts.MainUI" to "Sistema del estéreo (MainUI)",
        "com.jsolaresc.ohu" to "Inicio Android Auto"
    )

    fun build(ctx: Context, storageRoots: List<File>): String {
        val sb = StringBuilder()
        fun line(s: String = "") = sb.append(s).append('\n')
        fun section(t: String) {
            line()
            line("== $t ==")
        }

        line("Reporte Launcher B180")
        line("Generado: ${RealTime.date()}  (reloj del estéreo: ${java.util.Date()})")
        line("Versión: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        line("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.MANUFACTURER} ${Build.MODEL} · ${Build.HARDWARE}")
        val dm = ctx.resources.displayMetrics
        line("Pantalla: ${dm.widthPixels}×${dm.heightPixels} · ${dm.densityDpi} dpi")
        line("Encendido hace: ${SystemClock.elapsedRealtime() / 60_000} min")

        section("Permisos")
        for (p in listOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_SECURE_SETTINGS
        )) {
            val ok = ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
            line("${p.substringAfterLast('.')}: ${if (ok) "sí" else "no"}")
        }
        line("Instalar apps: ${if (ctx.packageManager.canRequestPackageInstalls()) "sí" else "no (se pide al actualizar)"}")

        section("Apps relacionadas")
        for ((pkg, name) in APPS) {
            val v = try {
                ctx.packageManager.getPackageInfo(pkg, 0).versionName ?: "?"
            } catch (e: Exception) {
                null
            }
            if (v != null) line("$name ($pkg): $v")
        }
        line("Android Auto del dock abre: ${Dock.autoIntent(ctx)?.let { it.component?.flattenToShortString() ?: it.`package` } ?: "ninguna app instalada"}")

        section("Ajustes del launcher")
        line("Tema: ${Prefs.theme(ctx)} · tarjeta inferior: ${Prefs.bottomCard(ctx)}")
        line("Abrir Android Auto al conectar USB: ${Prefs.autoOnUsb(ctx)}")
        line("Bajar música cuando habla OsmAnd: ${Prefs.pauseForVoice(ctx)} (a ${Prefs.duckVolume(ctx)})")
        line("Guardar zonas recorridas: ${Prefs.saveVisited(ctx)} · radio ${Prefs.offlineRadius(ctx)} km")
        line("Barra inferior: " + DockConfig.load(ctx).joinToString(", ") { (if (it.visible) "" else "(oculto) ") + it.label })
        line("Destinos rápidos: ${Places.load(ctx).size}")
        line("Calles sin conexión (calles-amba): ${if (Roads.available(ctx)) "cargadas" else "no"}")
        dirSize(File(ctx.filesDir, "osmdroid"))?.let { line("Mapa guardado: ${it / (1024 * 1024)} MB") }

        section("GPS")
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        try {
            line("Proveedores: ${lm.allProviders.joinToString()} · GPS activo: ${lm.isProviderEnabled(LocationManager.GPS_PROVIDER)}")
        } catch (e: Exception) {
            line("Proveedores: error ${e.message}")
        }
        line("Satélites (último dato): ${Diag.gnss}")
        try {
            @Suppress("MissingPermission")
            val last = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            if (last == null) line("Última posición del sistema: ninguna")
            else {
                val age = (SystemClock.elapsedRealtimeNanos() - last.elapsedRealtimeNanos) / 1_000_000_000L
                line("Última posición del sistema: hace ${age}s · precisión ${last.accuracy.toInt()} m")
            }
        } catch (e: Exception) {
            line("Última posición del sistema: sin permiso")
        }

        section("USB conectado")
        val usb = ctx.getSystemService(Context.USB_SERVICE) as UsbManager
        val devices = try {
            usb.deviceList.values.toList()
        } catch (e: Exception) {
            emptyList()
        }
        if (devices.isEmpty()) line("(nada)")
        for (d in devices) {
            val classes = (0 until d.interfaceCount).map { d.getInterface(it).interfaceClass }
            line("${d.manufacturerName ?: "?"} ${d.productName ?: ""} · %04x:%04x · clases $classes".format(d.vendorId, d.productId))
        }

        section("Almacenamiento")
        for (r in storageRoots) {
            val names = r.listFiles()?.map { it.name }?.sorted()
            line("${r.path}: " + (names?.take(30)?.joinToString(", ") ?: "no se puede leer"))
        }
        ctx.getExternalFilesDirs(null).filterNotNull().forEach { line("Carpeta de la app: ${it.path}") }

        section("Eventos recientes")
        Diag.events().forEach { line(it) }

        section("Cierres inesperados")
        line(Diag.crashes(ctx).ifBlank { "(ninguno)" })

        section("Registro del sistema (solo el launcher)")
        line(logcat())
        return sb.toString()
    }

    private fun dirSize(f: File): Long? = try {
        if (!f.exists()) null else f.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    } catch (e: Exception) {
        null
    }

    private fun logcat(): String = try {
        val p = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-t", "600", "-v", "time"))
        val text = p.inputStream.bufferedReader().readText()
        p.waitFor()
        text.takeLast(120_000)
    } catch (e: Exception) {
        "no disponible: ${e.message}"
    }

    /**
     * Guarda el reporte: en la raíz del pendrive si se puede y, si no, en la carpeta de la
     * app dentro del pendrive (Android/data/com.jsolaresc.launcher/files), que siempre se puede.
     */
    fun save(ctx: Context, text: String, usbRoots: List<File>): List<String> {
        val saved = ArrayList<String>()
        for (root in usbRoots) {
            try {
                val f = File(root, FILE)
                f.writeText(text)
                saved.add(f.path)
            } catch (e: Exception) {
            }
        }
        if (saved.isEmpty()) {
            for (dir in ctx.getExternalFilesDirs(null).filterNotNull()) {
                try {
                    dir.mkdirs()
                    val f = File(dir, FILE)
                    f.writeText(text)
                    saved.add(f.path)
                } catch (e: Exception) {
                }
            }
        }
        return saved
    }
}
