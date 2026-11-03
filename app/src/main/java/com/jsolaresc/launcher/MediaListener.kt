package com.jsolaresc.launcher

import android.app.Notification
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Su permiso habilita leer la sesión de música. Además escucha la notificación
 * de navegación de Google Maps para mostrar la próxima indicación en el mapa.
 */
class MediaListener : NotificationListenerService() {

    override fun onListenerConnected() {
        try {
            activeNotifications?.forEach { handle(it) }
        } catch (e: Exception) {
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) = handle(sbn)

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (sbn.packageName == Nav.MAPS_PACKAGE || NavLaunch.isOsmand(sbn.packageName)) Nav.remove(sbn.key)
    }

    private fun handle(sbn: StatusBarNotification) {
        val n = sbn.notification
        if (NavLaunch.isOsmand(sbn.packageName)) {
            // OsmAnd: título = "120 m • Girar a la derecha"; texto largo = calle y lo que falta.
            if (n.category != Notification.CATEGORY_NAVIGATION) return
            val extras = n.extras
            val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
            val big = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))
                ?.toString().orEmpty()
            val lines = big.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
            if (title.isNullOrBlank() && lines.isEmpty()) return
            val first = Nav.current == null
            Nav.update(
                Nav.Info(
                    sbn.key, title, lines.firstOrNull(), lines.drop(1).joinToString(" · ").ifBlank { null },
                    largeIcon(n), tintIcon = true
                )
            )
            // Arrancó la navegación pedida desde un destino rápido: volver al inicio.
            if (first && NavLaunch.returnHome) {
                NavLaunch.returnHome = false
                try {
                    startActivity(
                        android.content.Intent(this, MainActivity::class.java)
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                    )
                } catch (e: Exception) {
                }
            }
            return
        }
        if (sbn.packageName != Nav.MAPS_PACKAGE) return
        // La de navegación es la notificación fija ("en curso") de Maps.
        val ongoing = (n.flags and Notification.FLAG_ONGOING_EVENT) != 0
        if (!ongoing && n.category != Notification.CATEGORY_NAVIGATION) return

        val extras = n.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        val sub = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()
        if (title.isNullOrBlank() && text.isNullOrBlank()) return

        Nav.update(Nav.Info(sbn.key, title, text, sub, largeIcon(n)))
    }

    private fun largeIcon(n: Notification): Bitmap? = try {
        val drawable = n.getLargeIcon()?.loadDrawable(this)
        when {
            drawable == null -> null
            drawable is BitmapDrawable -> drawable.bitmap
            drawable.intrinsicWidth > 0 && drawable.intrinsicHeight > 0 -> {
                val bmp = Bitmap.createBitmap(drawable.intrinsicWidth, drawable.intrinsicHeight, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bmp)
                drawable.setBounds(0, 0, canvas.width, canvas.height)
                drawable.draw(canvas)
                bmp
            }
            else -> null
        }
    } catch (e: Exception) {
        null
    }
}

/** Última indicación de navegación conocida, compartida con la pantalla. */
object Nav {
    const val MAPS_PACKAGE = "com.google.android.apps.maps"

    data class Info(
        val key: String,
        val title: String?,
        val text: String?,
        val sub: String?,
        val icon: Bitmap?,
        /** El ícono de giro de Organic Maps viene de color: se pinta blanco para el cartel verde. */
        val tintIcon: Boolean = false
    )

    @Volatile
    var current: Info? = null
        private set

    var listener: (() -> Unit)? = null

    fun update(info: Info) {
        current = info
        listener?.invoke()
    }

    fun remove(key: String) {
        if (current?.key == key) {
            current = null
            listener?.invoke()
        }
    }
}
