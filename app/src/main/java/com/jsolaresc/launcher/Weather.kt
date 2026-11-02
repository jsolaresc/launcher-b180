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

/** Clima actual con Open-Meteo (sin cuenta ni clave). Se actualiza cada 15 minutos con señal. */
class Weather(private val onChange: (temp: Int, iconRes: Int) -> Unit) {

    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var lastFetch = 0L

    @Volatile
    private var inFlight = false

    fun update(loc: Location?, online: Boolean) {
        if (loc == null || !online || inFlight) return
        if (System.currentTimeMillis() - lastFetch < 15 * 60 * 1000L) return
        inFlight = true
        val lat = String.format(Locale.US, "%.3f", loc.latitude)
        val lon = String.format(Locale.US, "%.3f", loc.longitude)
        executor.execute {
            try {
                val conn = URL(
                    "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&current=temperature_2m,weather_code,is_day,precipitation"
                ).openConnection() as HttpURLConnection
                conn.connectTimeout = 6000
                conn.readTimeout = 8000
                if (conn.responseCode == 200) {
                    val current = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                        .getJSONObject("current")
                    val temp = current.getDouble("temperature_2m").roundToInt()
                    val icon = iconFor(
                        current.optInt("weather_code", 3),
                        current.optInt("is_day", 1) == 1,
                        current.optDouble("precipitation", 0.0)
                    )
                    lastFetch = System.currentTimeMillis()
                    main.post { onChange(temp, icon) }
                }
            } catch (e: Exception) {
            }
            inFlight = false
        }
    }

    /** Lluvia si el código lo dice o si está cayendo agua; de noche, luna en lugar de sol. */
    private fun iconFor(code: Int, day: Boolean, precipitation: Double) = when {
        precipitation > 0.0 || code in 51..67 || code in 80..82 || code in 95..99 -> R.drawable.ic_rain
        code == 0 || code == 1 -> if (day) R.drawable.ic_sun else R.drawable.ic_moon
        else -> R.drawable.ic_cloud
    }
}
