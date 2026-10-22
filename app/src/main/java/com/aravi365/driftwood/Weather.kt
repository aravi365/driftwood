package com.aravi365.driftwood

import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.roundToInt

/**
 * Current conditions from Open-Meteo (free, no key). Location is the city set in Driftwood,
 * or otherwise an approximate one from the connection's public IP (ipwho.is).
 */
object Weather {
    private const val FRESH_MS = 30 * 60 * 1000L
    private val main = Handler(Looper.getMainLooper())

    fun refresh(prefs: Prefs, force: Boolean = false, done: (String?) -> Unit) {
        val city = prefs.city
        if (!force && prefs.weatherAge < FRESH_MS && prefs.weather != null) return done(prefs.weather)
        Thread {
            val line = try { fetch(city) } catch (e: Exception) { null }
            main.post {
                if (line != null) prefs.weather = line
                done(line ?: prefs.weather)
            }
        }.start()
    }

    private fun fetch(city: String?): String {
        val lat: Double
        val lon: Double
        val name: String
        if (city != null) {
            val q = URLEncoder.encode(city, "UTF-8")
            val place = get("https://geocoding-api.open-meteo.com/v1/search?count=1&name=$q")
                .getJSONArray("results").getJSONObject(0)
            lat = place.getDouble("latitude"); lon = place.getDouble("longitude"); name = place.getString("name")
        } else {
            val ip = get("https://ipwho.is/")
            lat = ip.getDouble("latitude"); lon = ip.getDouble("longitude"); name = ip.getString("city")
        }
        val now = get("https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&current=temperature_2m,weather_code&timezone=auto")
            .getJSONObject("current")
        val temp = now.getDouble("temperature_2m").roundToInt()
        val (symbol, words) = describe(now.getInt("weather_code"))
        return "$symbol  $temp°  $words · $name"
    }

    private fun get(url: String): JSONObject {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 8000
        return try { JSONObject(c.inputStream.bufferedReader().readText()) } finally { c.disconnect() }
    }

    private fun describe(code: Int) = when (code) {
        0 -> "☀" to "Clear"
        1, 2 -> "⛅" to "Partly cloudy"
        3 -> "☁" to "Cloudy"
        45, 48 -> "🌫" to "Fog"
        in 51..57 -> "🌦" to "Drizzle"
        in 61..67, in 80..82 -> "🌧" to "Rain"
        in 71..77, 85, 86 -> "❄" to "Snow"
        in 95..99 -> "⛈" to "Thunderstorm"
        else -> "☁" to "Cloudy"
    }
}
