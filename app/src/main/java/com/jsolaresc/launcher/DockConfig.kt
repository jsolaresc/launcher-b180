package com.jsolaresc.launcher

import android.content.ComponentName
import android.content.Context
import android.graphics.drawable.Drawable
import org.json.JSONArray
import org.json.JSONObject

/**
 * Qué accesos muestra la barra inferior, en qué orden, con qué nombre e ícono.
 * Los fijos (Apps, Android Auto, Mapa, Ajustes, Música, Radio, Teléfono) se pueden
 * ocultar; las apps agregadas a mano también se pueden quitar.
 */
object DockConfig {
    const val APPS = "apps"
    const val AUTO = Dock.AUTO
    const val MAPS = Dock.MAPS
    const val SETTINGS = "settings"
    const val MUSIC = "music"
    const val RADIO = Dock.RADIO
    const val PHONE = Dock.PHONE

    /** Usar el ícono propio de la app (solo apps agregadas). */
    const val ICON_ORIGINAL = "original"

    private const val PREFS = "dock"
    private const val KEY = "items_v1"

    class Item(
        val id: String,
        var label: String,
        var icon: String,
        var visible: Boolean,
        /** Solo apps agregadas: la app que abre. */
        var component: String? = null
    ) {
        val builtin get() = component == null && id in BUILTINS
    }

    private val BUILTINS = listOf(APPS, AUTO, MAPS, SETTINGS, MUSIC, RADIO, PHONE)

    /** Íconos de línea del launcher, para que todo combine. */
    val ICONS: LinkedHashMap<String, Int> = linkedMapOf(
        "apps" to R.drawable.ic_apps,
        "car" to R.drawable.ic_car,
        "maps" to R.drawable.ic_maps_dock,
        "settings" to R.drawable.ic_settings,
        "music" to R.drawable.ic_music,
        "note" to R.drawable.ic_note,
        "headphones" to R.drawable.ic_headphones,
        "radio" to R.drawable.ic_radio,
        "phone" to R.drawable.ic_phone,
        "chat" to R.drawable.ic_chat,
        "video" to R.drawable.ic_video,
        "star" to R.drawable.ic_star,
        "home" to R.drawable.ic_home,
        "work" to R.drawable.ic_work,
        "pin" to R.drawable.ic_pin,
        "search" to R.drawable.ic_search,
        "bt" to R.drawable.ic_bt,
        "wifi" to R.drawable.ic_wifi,
        "cloud" to R.drawable.ic_cloud,
        "sun" to R.drawable.ic_sun,
        "moon" to R.drawable.ic_moon,
        "bell" to R.drawable.ic_bell,
        "file" to R.drawable.ic_file,
        "usb" to R.drawable.ic_usb_white
    )

    private fun defaults(): MutableList<Item> = mutableListOf(
        Item(APPS, "Apps", "apps", true),
        Item(AUTO, "Android Auto", "car", true),
        Item(MAPS, "Mapa", "maps", true),
        Item(SETTINGS, "Ajustes", "settings", true),
        Item(MUSIC, "Música", "music", false),
        Item(RADIO, "Radio", "radio", false),
        Item(PHONE, "Teléfono", "phone", false)
    )

    fun load(ctx: Context): MutableList<Item> {
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
            ?: return defaults()
        val list = try {
            val arr = JSONArray(raw)
            MutableList(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                Item(
                    o.getString("id"), o.getString("label"), o.getString("icon"), o.getBoolean("visible"),
                    o.optString("component").takeIf { it.isNotEmpty() }
                )
            }
        } catch (e: Exception) {
            return defaults()
        }
        // Fijos nuevos en versiones futuras: se agregan ocultos al final.
        for (d in defaults()) if (list.none { it.id == d.id }) list.add(d.also { it.visible = false })
        return list
    }

    fun save(ctx: Context, items: List<Item>) {
        val arr = JSONArray()
        for (it in items) {
            arr.put(JSONObject().apply {
                put("id", it.id)
                put("label", it.label)
                put("icon", it.icon)
                put("visible", it.visible)
                it.component?.let { c -> put("component", c) }
            })
        }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, arr.toString()).apply()
    }

    fun reset(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).apply()

    fun newApp(component: ComponentName, label: String) =
        Item("app:" + component.flattenToShortString(), label, guessIcon(component.packageName), true, component.flattenToString())

    /** Ícono de línea parecido según la app (después se puede cambiar). */
    fun guessIcon(pkg: String): String {
        val p = pkg.lowercase()
        return when {
            "youtube.music" in p || "spotify" in p || "deezer" in p || "music" in p || "tidal" in p -> "music"
            "podcast" in p || "audible" in p || "audiobook" in p -> "headphones"
            "youtube" in p || "video" in p || "netflix" in p || "player" in p -> "video"
            "waze" in p || "maps" in p || "osmand" in p || "navi" in p -> "maps"
            "whatsapp" in p || "telegram" in p || "messag" in p || "sms" in p -> "chat"
            "dialer" in p || "phone" in p || "contacts" in p -> "phone"
            "radio" in p -> "radio"
            "headunit" in p || "gb.xxy" in p || "auto" in p -> "car"
            "weather" in p || "clima" in p -> "cloud"
            "file" in p -> "file"
            else -> "star"
        }
    }

    /** Ícono a mostrar: el de línea elegido, o el de la app. */
    fun drawable(ctx: Context, item: Item): Drawable? {
        if (item.icon == ICON_ORIGINAL && item.component != null) {
            try {
                return ctx.packageManager.getActivityIcon(ComponentName.unflattenFromString(item.component!!)!!)
            } catch (e: Exception) {
            }
        }
        return ctx.getDrawable(ICONS[item.icon] ?: R.drawable.ic_star)
    }
}
