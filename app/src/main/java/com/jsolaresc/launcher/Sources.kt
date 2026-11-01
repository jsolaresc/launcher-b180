package com.jsolaresc.launcher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable

/**
 * Fuentes de música que se eligen desde el desplegable de la tarjeta.
 * - Bluetooth: la música del celular (sesión de com.android.bluetooth).
 * - Apps del estéreo (YouTube Music u otras agregadas): su propia sesión.
 * - USB y Radio: pantallas del estéreo, sin sesión de música que leer.
 */
data class Source(
    val id: String,
    val name: String,
    val detail: String,
    /** Ícono propio (fuentes fijas) o null para usar el de la app. */
    val iconRes: Int?,
    val color: Int,
    /** Paquete cuya sesión de música controla la tarjeta; null = sin sesión. */
    val sessionPackage: String?,
    val launch: ComponentName?,
    val appPackage: String?
) {
    val isBluetooth get() = id == Sources.BT

    /** Fuente propia del estéreo (se maneja con su servicio, no con una sesión). */
    val tsMode: Int?
        get() = when (id) {
            Sources.BT -> TsCommon.MODE_BT
            Sources.USB -> TsCommon.MODE_USB
            Sources.RADIO -> TsCommon.MODE_RADIO
            else -> null
        }
}

object Sources {
    const val BT = "bt"
    const val USB = "usb"
    const val RADIO = "radio"
    const val YT_MUSIC = "com.google.android.apps.youtube.music"

    private const val PREFS = "music"
    private const val KEY_SELECTED = "selected"
    private const val KEY_APPS = "apps"

    private val USB_SCREEN = ComponentName("com.ts.MainUI", "com.ts.main.Media.USBMainActivity")
    private val RADIO_SCREEN = ComponentName("com.ts.MainUI", "com.ts.main.radio.RadioMainActivity")

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun installed(ctx: Context, pkg: String) = try {
        ctx.packageManager.getApplicationInfo(pkg, 0)
        true
    } catch (e: Exception) {
        false
    }

    /** Apps agregadas por el usuario; YouTube Music viene de entrada si está instalada. */
    private fun appPackages(ctx: Context): List<String> {
        val stored = prefs(ctx).getString(KEY_APPS, null)
        val list = stored?.split(",")?.filter { it.isNotBlank() } ?: listOf(YT_MUSIC)
        return list.filter { installed(ctx, it) }
    }

    fun all(ctx: Context): List<Source> {
        val pm = ctx.packageManager
        val apps = appPackages(ctx).map { pkg ->
            val label = try {
                pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
            } catch (e: Exception) {
                pkg
            }
            Source(
                id = pkg,
                name = label,
                detail = "",
                iconRes = null,
                color = 0,
                sessionPackage = pkg,
                launch = null,
                appPackage = pkg
            )
        }
        return listOf(
            Source(BT, ctx.getString(R.string.source_bt), ctx.getString(R.string.source_bt_detail),
                R.drawable.ic_bt_white, 0xFF1A73E8.toInt(), Music.BT_PACKAGE, Dock.BT_MUSIC, null)
        ) + apps + listOf(
            Source(USB, ctx.getString(R.string.source_usb), ctx.getString(R.string.source_usb_detail),
                R.drawable.ic_usb_white, 0xFF3A4352.toInt(), null, USB_SCREEN, null),
            Source(RADIO, ctx.getString(R.string.source_radio), "",
                R.drawable.ic_radio_white, 0xFFE37400.toInt(), null, RADIO_SCREEN, null)
        )
    }

    fun selected(ctx: Context): Source {
        val id = prefs(ctx).getString(KEY_SELECTED, BT)
        val list = all(ctx)
        return list.firstOrNull { it.id == id } ?: list.first()
    }

    fun select(ctx: Context, id: String) {
        prefs(ctx).edit().putString(KEY_SELECTED, id).apply()
    }

    fun addApp(ctx: Context, pkg: String) {
        val list = appPackagesRaw(ctx)
        if (pkg !in list) save(ctx, list + pkg)
    }

    fun removeApp(ctx: Context, pkg: String) {
        save(ctx, appPackagesRaw(ctx) - pkg)
        if (prefs(ctx).getString(KEY_SELECTED, BT) == pkg) select(ctx, BT)
    }

    private fun appPackagesRaw(ctx: Context): List<String> =
        prefs(ctx).getString(KEY_APPS, null)?.split(",")?.filter { it.isNotBlank() } ?: listOf(YT_MUSIC)

    private fun save(ctx: Context, list: List<String>) {
        prefs(ctx).edit().putString(KEY_APPS, list.joinToString(",")).apply()
    }

    fun appIcon(ctx: Context, source: Source): Drawable? = source.appPackage?.let {
        try {
            ctx.packageManager.getApplicationIcon(it)
        } catch (e: Exception) {
            null
        }
    }

    fun openIntent(ctx: Context, source: Source): Intent? {
        val intent = when {
            source.launch != null -> Intent(Intent.ACTION_MAIN).setComponent(source.launch)
            source.appPackage != null -> ctx.packageManager.getLaunchIntentForPackage(source.appPackage)
            else -> null
        }
        return intent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
    }
}
