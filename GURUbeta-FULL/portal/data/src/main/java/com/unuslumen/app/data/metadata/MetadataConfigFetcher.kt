package com.unuslumen.app.data.metadata

import android.content.Context
import android.location.Geocoder
import android.location.LocationManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * Server-delivered metadata configuration. One row on the server
 * (master_metadata_config) governs the whole metadata system: which groups are
 * active, which directions carry the block, the block template, every word
 * of the self-compaction prompts, and every cadence, threshold and profile of
 * the background refresh worker. NOTHING about the block is hardcoded in the
 * app — this config is fetched and obeyed.
 */
@Serializable
data class MetadataConfig(
    val enabled: Boolean = true,
    @SerialName("inject_on_receive") val injectOnReceive: Boolean = true,
    @SerialName("inject_on_send") val injectOnSend: Boolean = false,
    val groups: MetadataGroups = MetadataGroups(),
    @SerialName("recent_web_searches_count") val recentWebSearchesCount: Int = 20,
    @SerialName("recent_actions_count") val recentActionsCount: Int = 20,
    // How many extra rows beyond recent_actions_count the self-state query
    // overscans before filtering webSearch rows out, so search bursts cannot
    // crowd real actions out of recent_actions. Superadmin tunable.
    @SerialName("recent_actions_overscan") val recentActionsOverscan: Int = 50,
    @SerialName("weather_refresh_minutes") val weatherRefreshMinutes: Int = 30,
    @SerialName("place_refresh_minutes") val placeRefreshMinutes: Int = 15,
    // Cadence of the background motion sampler, in seconds.
    @SerialName("motion_refresh_seconds") val motionRefreshSeconds: Int = 15,
    // Fall-back location the background weather refresher uses when no GPS fix
    // exists yet. Empty string disables the fallback entirely.
    @SerialName("weather_default_location") val weatherDefaultLocation: String = "Bristol",
    // Which Bluetooth profiles the metadata bluetooth group polls for
    // connected devices: any subset of "gatt", "a2dp", "headset".
    // Headphones connect over A2DP/HEADSET, not GATT.
    @SerialName("bluetooth_profiles") val bluetoothProfiles: List<String> = listOf("gatt", "a2dp", "headset"),
    // Accelerometer deviation bands for the background motion reader. Peak
    // deviation of acceleration magnitude from gravity: below the still band is
    // still, below the stationary band is stationary, else moving/shaking.
    @SerialName("motion_thresholds") val motionThresholds: MotionThresholds = MotionThresholds(),
    // Whether successful weather/location tool calls may also write the
    // corresponding CacheStore entry. Superadmin kill switch for tool-driven
    // cache priming; group toggles still apply on top of it.
    @SerialName("cache_prime_on_tool_success") val cachePrimeOnToolSuccess: Boolean = true,
    @SerialName("block_template") val blockTemplate: String = "[[MESSAGE METADATA]]\n{METADATA}\n[[END MESSAGE METADATA]]",
    val compaction: MetadataCompactionConfig = MetadataCompactionConfig()
)

@Serializable
data class MotionThresholds(
    @SerialName("still_deviation_below") val stillDeviationBelow: Float = 0.15f,
    @SerialName("stationary_deviation_below") val stationaryDeviationBelow: Float = 1.2f
)

@Serializable
data class MetadataGroups(
    val clock: Boolean = true,
    val gaps: Boolean = true,
    val battery: Boolean = true,
    val screen: Boolean = true,
    val network: Boolean = true,
    val audio: Boolean = true,
    @SerialName("self_state") val selfState: Boolean = true,
    val place: Boolean = true,
    val weather: Boolean = true,
    val motion: Boolean = true,
    val bluetooth: Boolean = true
)

@Serializable
data class MetadataCompactionConfig(
    val enabled: Boolean = true,
    @SerialName("threshold_percent") val thresholdPercent: Int = 80,
    @SerialName("keep_recent") val keepRecent: Int = 20,
    @SerialName("summary_prompt") val summaryPrompt: String = "",
    @SerialName("pointer_prompt") val pointerPrompt: String = ""
)

/**
 * One background worker owns the full slow-changing chain: GPS fix,
 * reverse-geocode to town, weather fetch, motion sampling. Cadences, bands and
 * default location all come from superadmin config, re-read fresh at the top of
 * every loop iteration so superadmin edits apply within one cycle without an
 * APK ship. Each loop also honours its group toggle from config. Runs while
 * the app process is alive; the send path never blocks on it, it only reads
 * CacheStore.
 */
class MetadataRefreshWorker(
    private val context: Context,
    private val configProvider: suspend () -> MetadataConfig?,
    private val torManager: com.unuslumen.app.data.tor.TorManager? = null
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val locationManager: LocationManager
        get() = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    fun start() {
        scope.launch { locationLoop() }
        scope.launch { weatherLoop() }
        scope.launch { motionLoop() }
        Log.i(TAG, "MetadataRefreshWorker started")
    }

    /** Stops all loops. Called when the process is being torn down. */
    fun stop() {
        scope.cancel()
    }

    private suspend fun locationLoop() {
        while (true) {
            val cfg = configProvider()
            if (cfg?.groups?.place != false) {
                val minutes = (cfg?.placeRefreshMinutes ?: 15).coerceAtLeast(1)
                refreshPlaceOnce()
                delay(minutes * 60_000L)
            } else {
                // Group disabled — sleep short so a superadmin re-enable lands quickly.
                delay(60_000L)
            }
        }
    }

    private suspend fun weatherLoop() {
        while (true) {
            val cfg = configProvider()
            if (cfg?.groups?.weather != false) {
                val minutes = (cfg?.weatherRefreshMinutes ?: 30).coerceAtLeast(1)
                refreshWeatherOnce(cfg)
                delay(minutes * 60_000L)
            } else {
                delay(60_000L)
            }
        }
    }

    private suspend fun motionLoop() {
        while (true) {
            val cfg = configProvider()
            if (cfg?.groups?.motion != false) {
                val seconds = (cfg?.motionRefreshSeconds ?: 15).coerceAtLeast(1)
                readMotion(cfg?.motionThresholds ?: MotionThresholds())?.let { state ->
                    CacheStore.motionState = state
                }
                delay(seconds * 1000L)
            } else {
                CacheStore.motionState = null
                delay(60_000L)
            }
        }
    }

    /**
     * Read one motion sample window. Uses accelerometer peak deviation from
     * gravity across the window to classify device movement.
     */
    private fun readMotion(thresholds: MotionThresholds): String? {
        return try {
            val sm = context.getSystemService(Context.SENSOR_SERVICE) as? android.hardware.SensorManager
                ?: return null
            val accel = sm.getDefaultSensor(android.hardware.Sensor.TYPE_ACCELEROMETER) ?: return null
            var peakDeviation = 0f
            val listener = object : android.hardware.SensorEventListener {
                override fun onSensorChanged(event: android.hardware.SensorEvent) {
                    val magnitude = Math.sqrt(
                        (event.values[0] * event.values[0] +
                         event.values[1] * event.values[1] +
                         event.values[2] * event.values[2]).toDouble()
                    ).toFloat()
                    val deviation = Math.abs(magnitude - GRAVITY)
                    if (deviation > peakDeviation) peakDeviation = deviation
                }
                override fun onAccuracyChanged(sensor: android.hardware.Sensor?, accuracy: Int) {}
            }
            sm.registerListener(listener, accel, android.hardware.SensorManager.SENSOR_DELAY_UI)
            try { Thread.sleep(SAMPLE_WINDOW_MILLIS) } catch (_: InterruptedException) {}
            sm.unregisterListener(listener)
            when {
                peakDeviation < thresholds.stillDeviationBelow -> "still"
                peakDeviation < thresholds.stationaryDeviationBelow -> "stationary"
                else -> "moving/shaking"
            }
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun refreshPlaceOnce() {
        try {
            val fix = latestFix() ?: return
            val town = resolveTown(fix.latitude, fix.longitude)
            val display = town ?: "%.4f, %.4f".format(Locale.US, fix.latitude, fix.longitude)
            CacheStore.place = CacheStore.PlaceCache(
                town = display,
                timestampMillis = System.currentTimeMillis()
            )
        } catch (_: SecurityException) {
            // Location permission not granted — the human has not allowed it. Absent
            // data, never fabricated. It appears the moment the permission lands.
        } catch (_: Exception) {
        }
    }

    private fun latestFix(): android.location.Location? {
        return try {
            @Suppress("MissingPermission")
            locationManager.getProviders(true)
                .mapNotNull { runCatching { locationManager.getLastKnownLocation(it) }.getOrNull() }
                .maxByOrNull { it.time }
        } catch (_: SecurityException) {
            null
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun resolveTown(latitude: Double, longitude: Double): String? {
        return try {
            @Suppress("DEPRECATION")
            val addresses = Geocoder(context, Locale.getDefault())
                .getFromLocation(latitude, longitude, 1) ?: return null
            addresses.firstOrNull()?.let {
                it.locality ?: it.subLocality ?: it.getAddressLine(0)
            }
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun refreshWeatherOnce(cfg: MetadataConfig?) {
        try {
            // Weather for where the human actually is: the latest GPS fix. Without a
            // fix, the superadmin default location. With neither, skip honestly.
            val location = latestFix()
                ?.let { "%.4f, %.4f".format(Locale.US, it.latitude, it.longitude) }
                ?: cfg?.weatherDefaultLocation?.takeIf { it.isNotBlank() }
                ?: return
            val encodedLocation = java.net.URLEncoder.encode(location, "UTF-8")
            val body = fetchThroughTor("https://wttr.in/$encodedLocation?format=j1") ?: return
            val data = parseWeather(body) ?: return
            CacheStore.weather = CacheStore.WeatherCache(
                condition = data.condition,
                temperatureC = data.temperature,
                location = location,
                timestampMillis = System.currentTimeMillis()
            )
        } catch (_: Exception) {
        }
    }

    private data class WeatherNow(
        val condition: String?,
        val temperature: Double?
    )

    private fun parseWeather(jsonBody: String): WeatherNow? {
        return try {
            val obj = Json.parseToJsonElement(jsonBody).jsonObject
            val current = obj["current_condition"]?.jsonArray?.firstOrNull()?.jsonObject ?: return null
            val temp = current["temp_C"]?.jsonPrimitive?.content?.toDoubleOrNull()
            val cond = current["weatherDesc"]?.jsonArray?.firstOrNull()
                ?.jsonObject?.get("value")?.jsonPrimitive?.contentOrNull
            if (temp == null && cond == null) null
            else WeatherNow(condition = cond, temperature = temp)
        } catch (_: Exception) {
            null
        }
    }

    private fun fetchThroughTor(url: String): String? {
        return try {
            // Tor or nothing for web fetches, per the app's own security doctrine.
            val proxy: java.net.Proxy = torManager?.getSocksProxy() ?: return null
            var connection: HttpURLConnection? = null
            try {
                connection = (URL(url).openConnection(proxy)) as HttpURLConnection
                connection.connectTimeout = 10000
                connection.readTimeout = 20000
                connection.requestMethod = "GET"
                connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
                if (connection.responseCode in 200..299) {
                    connection.inputStream.bufferedReader().use { it.readText() }
                } else null
            } finally {
                connection?.disconnect()
            }
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        private const val TAG = "guru"
        private const val GRAVITY = 9.81f
        private const val SAMPLE_WINDOW_MILLIS = 800L
    }
}

/**
 * In-memory last-known store for slow-changing values (place, weather, motion).
 * Fed exclusively by MetadataRefreshWorker, which the app starts at boot. The
 * send path only ever reads, never blocks on a live fetch. Absent data stays
 * null — absent data, never fabricated.
 */
object CacheStore {
    var place: PlaceCache? = null
    var weather: WeatherCache? = null
    var motionState: String? = null

    data class PlaceCache(val town: String?, val timestampMillis: Long)
    data class WeatherCache(
        val condition: String?,
        val temperatureC: Double?,
        val location: String?,
        val timestampMillis: Long
    )
}

/**
 * Fetch-and-cache client for /api/metadata-config. Mirrors LlmConfigFetcher:
 * SharedPreferences cache, fetchAndCache per send so superadmin edits go live
 * without an APK ship, cached values used when the server is unreachable.
 */
object MetadataConfigFetcher {
    private const val TAG = "MetadataConfigFetcher"
    private const val PREFS_NAME = "guru_metadata_config"
    private const val KEY_CONFIG = "metadata_config_json"
    private const val KEY_LAST_FETCH = "metadata_config_last_fetch"
    private const val BASE_URL = "https://api.unuslumen.com"
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun fetchAndCache(context: Context) {
        withContext(Dispatchers.IO) {
            try {
                val url = URL("$BASE_URL/api/metadata-config")
                val connection = url.openConnection() as HttpURLConnection
                connection.connectTimeout = 5000
                connection.readTimeout = 10000
                connection.requestMethod = "GET"

                val responseCode = connection.responseCode
                if (responseCode != 200) {
                    Log.w(TAG, "Server returned $responseCode, keeping cached config")
                    return@withContext
                }

                val body = connection.inputStream.bufferedReader().readText()
                connection.disconnect()

                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                prefs.edit()
                    .putString(KEY_CONFIG, body)
                    .putLong(KEY_LAST_FETCH, System.currentTimeMillis())
                    .apply()

                Log.i(TAG, "Fetched and cached metadata config (${body.length} chars)")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to fetch metadata config: ${e.message}")
            }
        }
    }

    fun getCachedConfig(context: Context): MetadataConfig? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_CONFIG, null) ?: return null
        return try {
            json.decodeFromString<MetadataConfig>(raw)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse cached metadata config: ${e.message}")
            null
        }
    }
}