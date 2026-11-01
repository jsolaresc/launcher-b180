package com.jsolaresc.launcher

import android.content.Context
import android.content.res.Configuration
import android.location.Location
import java.util.Calendar
import java.util.TimeZone
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin

/** Ajustes guardados del launcher. */
object Prefs {
    private const val NAME = "settings"
    const val THEME_LIGHT = "light"
    const val THEME_DARK = "dark"
    const val THEME_AUTO = "auto"

    const val CARD_PLACES = "places"
    const val CARD_CLOCK = "clock"
    const val CARD_COMPASS = "compass"
    const val CARD_SPEEDO = "speedo"

    private fun p(ctx: Context) = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun theme(ctx: Context) = p(ctx).getString("theme", THEME_AUTO) ?: THEME_AUTO
    fun setTheme(ctx: Context, v: String) = p(ctx).edit().putString("theme", v).apply()

    /** Último resultado del modo automático (para arrancar ya con el tema correcto). */
    fun autoNight(ctx: Context) = p(ctx).getBoolean("auto_night", true)
    fun setAutoNight(ctx: Context, v: Boolean) = p(ctx).edit().putBoolean("auto_night", v).apply()

    fun bottomCard(ctx: Context) = p(ctx).getString("bottom_card", CARD_PLACES) ?: CARD_PLACES
    fun setBottomCard(ctx: Context, v: String) = p(ctx).edit().putString("bottom_card", v).apply()

    /** Radio de "Descargar alrededor tuyo", en km. */
    fun offlineRadius(ctx: Context) = p(ctx).getInt("offline_radius", 20)
    fun setOfflineRadius(ctx: Context, v: Int) = p(ctx).edit().putInt("offline_radius", v).apply()

    /** Guardar en el estéreo el mapa de las zonas por las que pasás (con señal). */
    fun saveVisited(ctx: Context) = p(ctx).getBoolean("save_visited", true)
    fun setSaveVisited(ctx: Context, v: Boolean) = p(ctx).edit().putBoolean("save_visited", v).apply()

    /** Volumen del aviso de radares, 0 a 100. */
    fun alertVolume(ctx: Context) = p(ctx).getInt("alert_volume", 80)
    fun setAlertVolume(ctx: Context, v: Int) = p(ctx).edit().putInt("alert_volume", v).apply()
}

object Theme {

    fun isNight(ctx: Context): Boolean = when (Prefs.theme(ctx)) {
        Prefs.THEME_LIGHT -> false
        Prefs.THEME_DARK -> true
        else -> Prefs.autoNight(ctx)
    }

    /** Contexto con el modo claro/oscuro elegido, para attachBaseContext. */
    fun wrap(base: Context): Context {
        val config = Configuration(base.resources.configuration)
        val night = if (isNight(base)) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        config.uiMode = (config.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or night
        return base.createConfigurationContext(config)
    }

    fun dialogTheme(ctx: Context) =
        if (isNight(ctx)) android.R.style.Theme_Material_Dialog_Alert
        else android.R.style.Theme_Material_Light_Dialog_Alert

    /**
     * Sin dato de las luces del auto: es de noche si el sol está bajo el horizonte.
     * Cálculo aproximado de la elevación solar (alcanza para decidir el tema).
     */
    fun sunIsDown(loc: Location?): Boolean {
        val lat = loc?.latitude ?: -34.6
        val lon = loc?.longitude ?: -58.4
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        val day = cal.get(Calendar.DAY_OF_YEAR)
        val hours = cal.get(Calendar.HOUR_OF_DAY) + cal.get(Calendar.MINUTE) / 60.0
        val gamma = 2 * Math.PI / 365 * (day - 1 + (hours - 12) / 24)
        val decl = 0.006918 - 0.399912 * cos(gamma) + 0.070257 * sin(gamma) -
            0.006758 * cos(2 * gamma) + 0.000907 * sin(2 * gamma) -
            0.002697 * cos(3 * gamma) + 0.00148 * sin(3 * gamma)
        val eqTime = 229.18 * (0.000075 + 0.001868 * cos(gamma) - 0.032077 * sin(gamma) -
            0.014615 * cos(2 * gamma) - 0.040849 * sin(2 * gamma))
        val solarMinutes = hours * 60 + eqTime + 4 * lon
        val hourAngle = Math.toRadians(solarMinutes / 4 - 180)
        val latR = Math.toRadians(lat)
        val elevation = Math.toDegrees(
            asin(sin(latR) * sin(decl) + cos(latR) * cos(decl) * cos(hourAngle))
        )
        return elevation < -0.833
    }
}
