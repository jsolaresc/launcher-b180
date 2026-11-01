package com.jsolaresc.launcher

import android.location.Location
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/** Clima actual con Open-Meteo (sin cuenta ni clave). Se actualiza cada 30 minutos con señal. */
class Weather(private val onChange: (temp: Int, iconRes: Int) -> Unit) {

    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var lastFetch = 0L

    @Volatile
    private var inFlight = false

    fun update(loc: Location?, online: Boolean) {
        if (loc == null || !online || inFlight) return
        if (System.currentTimeMillis() - lastFetch < 30 * 60 * 1000L) return
        inFlight = true
        val lat = String.format(Locale.US, "%.3f", loc.latitude)
        val lon = String.format(Locale.US, "%.3f", loc.longitude)
        executor.execute {
            try {
                val conn = URL(
                    "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&current=temperature_2m,weather_code"
                ).openConnection() as HttpURLConnection
                conn.connectTimeout = 6000
                conn.readTimeout = 8000
                if (conn.responseCode == 200) {
                    val current = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                        .getJSONObject("current")
                    val temp = current.getDouble("temperature_2m").roundToInt()
                    val icon = iconFor(current.optInt("weather_code", 3))
                    lastFetch = System.currentTimeMillis()
                    main.post { onChange(temp, icon) }
                }
            } catch (e: Exception) {
            }
            inFlight = false
        }
    }

    private fun iconFor(code: Int) = when (code) {
        0, 1 -> R.drawable.ic_sun
        in 51..67, in 80..82, in 95..99 -> R.drawable.ic_rain
        else -> R.drawable.ic_cloud
    }
}
