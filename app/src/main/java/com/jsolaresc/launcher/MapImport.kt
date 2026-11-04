package com.jsolaresc.launcher

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import java.io.File

/**
 * Importación del mapa desde el pendrive. Corre aparte de la pantalla de Ajustes:
 * se puede salir, ir al inicio o volver, y sigue. Al terminar avisa con un mensaje.
 */
object MapImport {
    @Volatile
    var running = false
        private set

    /** 0 a 100. */
    @Volatile
    var percent = 0
        private set

    /** Para mostrar el avance en Ajustes si está abierta (porcentaje; -1 = terminó, -2 = falló). */
    var listener: ((Int) -> Unit)? = null

    /** Qué falló, para mostrarlo (ayuda a saber si es el pendrive, el espacio o el archivo). */
    @Volatile
    var lastError: String? = null
        private set

    /** Etapa: copiando del pendrive (0-50 %) o guardando en el mapa (50-100 %). */
    @Volatile
    var copying = false
        private set

    private val main = Handler(Looper.getMainLooper())

    fun start(ctx: Context, file: File, temporary: Boolean, area: OfflineAreas.Area) {
        if (running) return
        running = true
        percent = 0
        val app = ctx.applicationContext
        lastError = null
        Thread {
            fun report(p: Int) {
                if (p != percent) {
                    percent = p
                    main.post { listener?.invoke(p) }
                }
            }
            // SQLite no lee bien desde el pendrive (el sistema no deja bloquear el archivo):
            // primero se copia a la memoria del estéreo y de ahí se importa.
            var source = file
            var copied = false
            val count = try {
                if (!file.path.startsWith(app.filesDir.path)) {
                    copying = true
                    val local = File(app.filesDir, "import.sqlite")
                    val total = file.length().coerceAtLeast(1)
                    file.inputStream().use { input ->
                        local.outputStream().use { out ->
                            val buf = ByteArray(1 shl 16)
                            var done = 0L
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                done += n
                                report((done * 50 / total).toInt())
                            }
                        }
                    }
                    copying = false
                    source = local
                    copied = true
                }
                TileCache(app).importArchive(source.path) { done, total ->
                    report(50 + if (total > 0) done * 50 / total else 0)
                }
            } catch (e: Exception) {
                lastError = e.message ?: e.javaClass.simpleName
                -1
            }
            copying = false
            if (copied) source.delete()
            if (temporary) file.delete()
            running = false
            main.post {
                if (count > 0) {
                    OfflineAreas.save(app, OfflineAreas.Area(area.id, area.name, area.boxes, System.currentTimeMillis()))
                    Toast.makeText(app, app.getString(R.string.import_done, count), Toast.LENGTH_LONG).show()
                    listener?.invoke(-1)
                } else {
                    Toast.makeText(app, app.getString(R.string.import_failed) + (lastError?.let { ": $it" } ?: ""), Toast.LENGTH_LONG).show()
                    listener?.invoke(-2)
                }
            }
        }.start()
    }
}
