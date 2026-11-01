package com.jsolaresc.launcher

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Destinos rápidos guardados en el estéreo (hasta 4, como en el diseño 2x2). */
data class Place(val name: String, val lat: Double, val lon: Double) {
    val iconRes: Int
        get() {
            val n = name.lowercase()
            return when {
                "casa" in n || "home" in n -> R.drawable.ic_home
                "trabajo" in n || "oficina" in n || "laburo" in n -> R.drawable.ic_work
                else -> R.drawable.ic_pin
            }
        }
}

object Places {
    const val MAX = 4
    private const val PREFS = "places"
    private const val KEY = "list"

    fun load(ctx: Context): MutableList<Place> {
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return mutableListOf()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                Place(o.getString("name"), o.getDouble("lat"), o.getDouble("lon"))
            }.toMutableList()
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    fun save(ctx: Context, list: List<Place>) {
        val arr = JSONArray()
        list.take(MAX).forEach {
            arr.put(JSONObject().put("name", it.name).put("lat", it.lat).put("lon", it.lon))
        }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, arr.toString()).apply()
    }
}
