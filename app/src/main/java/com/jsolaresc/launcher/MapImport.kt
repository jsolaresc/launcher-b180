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

    private val main = Handler(Looper.getMainLooper())

    fun start(ctx: Context, file: File, temporary: Boolean, area: OfflineAreas.Area) {
        if (running) return
        running = true
        percent = 0
        val app = ctx.applicationContext
        Thread {
            val count = try {
                TileCache(app).importArchive(file.path) { done, total ->
                    val p = if (total > 0) done * 100 / total else 0
                    if (p != percent) {
                        percent = p
                        main.post { listener?.invoke(p) }
                    }
                }
            } catch (e: Exception) {
                -1
            }
            if (temporary) file.delete()
            running = false
            main.post {
                if (count > 0) {
                    OfflineAreas.save(app, OfflineAreas.Area(area.id, area.name, area.boxes, System.currentTimeMillis()))
                    Toast.makeText(app, app.getString(R.string.import_done, count), Toast.LENGTH_LONG).show()
                    listener?.invoke(-1)
                } else {
                    Toast.makeText(app, R.string.import_failed, Toast.LENGTH_LONG).show()
                    listener?.invoke(-2)
                }
            }
        }.start()
    }
}
