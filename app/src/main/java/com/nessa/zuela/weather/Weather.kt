package com.nessa.zuela.weather

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Free weather via Open-Meteo (no API key, works worldwide). Resolves either a
 * spoken city ("weather in Nairobi") or the phone's last known location, then
 * fetches the current conditions. All network runs off the main thread.
 */
object Weather {

    private data class Place(val lat: Double, val lon: Double, val name: String)

    suspend fun spoken(context: Context, city: String?): String = withContext(Dispatchers.IO) {
        val place = when {
            !city.isNullOrBlank() -> geocode(city)
                ?: return@withContext "I couldn't find $city."
            else -> deviceLocation(context)
                ?: return@withContext "I couldn't get your location. Turn location on, " +
                    "or ask me the weather in a city by name."
        }

        val current = fetchCurrent(place.lat, place.lon)
            ?: return@withContext "I couldn't reach the weather service. Please try again."

        val (temp, code) = current
        "It's ${temp.roundToInt()} degrees and ${describe(code)} in ${place.name}."
    }

    // ---------- location ----------

    @Suppress("DEPRECATION")
    private fun deviceLocation(context: Context): Place? {
        val fine = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) return null

        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        val location = try {
            listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
                .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
                .maxByOrNull { it.time }
        } catch (e: SecurityException) {
            null
        } ?: return null

        val name = reverseGeocode(context, location.latitude, location.longitude) ?: "your area"
        return Place(location.latitude, location.longitude, name)
    }

    @Suppress("DEPRECATION")
    private fun reverseGeocode(context: Context, lat: Double, lon: Double): String? = try {
        // Synchronous getFromLocation is deprecated on API 33+, but still functions;
        // this runs off the main thread already.
        if (Build.VERSION.SDK_INT >= 33 || Geocoder.isPresent()) {
            Geocoder(context, Locale.getDefault())
                .getFromLocation(lat, lon, 1)
                ?.firstOrNull()
                ?.let { it.locality ?: it.subAdminArea ?: it.adminArea }
        } else null
    } catch (e: Exception) {
        null
    }

    // ---------- open-meteo ----------

    private fun geocode(city: String): Place? {
        val url = "https://geocoding-api.open-meteo.com/v1/search?name=" +
            URLEncoder.encode(city.trim(), "UTF-8") + "&count=1&language=en&format=json"
        val body = httpGet(url) ?: return null
        val results = JSONObject(body).optJSONArray("results") ?: return null
        if (results.length() == 0) return null
        val r = results.getJSONObject(0)
        val name = buildString {
            append(r.optString("name"))
            r.optString("country").takeIf { it.isNotBlank() }?.let { append(", ").append(it) }
        }
        return Place(r.getDouble("latitude"), r.getDouble("longitude"), name)
    }

    /** Returns temperature (°C) and WMO weather code. */
    private fun fetchCurrent(lat: Double, lon: Double): Pair<Double, Int>? {
        val url = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
            "&current=temperature_2m,weather_code"
        val body = httpGet(url) ?: return null
        val current = JSONObject(body).optJSONObject("current") ?: return null
        val temp = current.optDouble("temperature_2m", Double.NaN)
        if (temp.isNaN()) return null
        return temp to current.optInt("weather_code", 0)
    }

    private fun httpGet(urlString: String): String? = try {
        val connection = (URL(urlString).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 15_000
        }
        val code = connection.responseCode
        val text = if (code in 200..299) {
            connection.inputStream.bufferedReader().use(BufferedReader::readText)
        } else null
        connection.disconnect()
        text
    } catch (e: Exception) {
        null
    }

    /** Maps WMO weather codes to a short spoken description. */
    private fun describe(code: Int): String = when (code) {
        0 -> "clear"
        1, 2 -> "partly cloudy"
        3 -> "overcast"
        45, 48 -> "foggy"
        in 51..57 -> "drizzly"
        in 61..67 -> "rainy"
        in 71..77 -> "snowy"
        in 80..82 -> "showery"
        in 85..86 -> "snow showers"
        in 95..99 -> "stormy"
        else -> "mild"
    }
}
