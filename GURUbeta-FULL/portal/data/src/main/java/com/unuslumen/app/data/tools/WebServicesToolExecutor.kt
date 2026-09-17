package com.unuslumen.app.data.tools

import android.content.Context
import com.unuslumen.app.data.tor.TorManager
import com.unuslumen.app.util.shell.ShellExecutor
import com.unuslumen.app.data.tools.registry.ToolExecutionResult
import com.unuslumen.app.data.tools.registry.ToolExecutor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

class WebServicesToolExecutor(
    private val context: Context,
    private val torManager: TorManager
) : ToolExecutor {
    private val json = Json { ignoreUnknownKeys = true }
    private val shellExecutor = ShellExecutor(context)

    override suspend fun execute(toolName: String, args: Map<String, Any?>): ToolExecutionResult = when (toolName) {
        WebServicesToolDefinitions.WEATHER_CURRENT -> weatherCurrent(args)
        WebServicesToolDefinitions.WEATHER_FORECAST -> weatherForecast(args)
        WebServicesToolDefinitions.WEATHER_ALERT -> weatherAlert(args)
        WebServicesToolDefinitions.PLACES_SEARCH -> placesSearch(args)
        WebServicesToolDefinitions.PLACES_DETAILS -> placesDetails(args)
        WebServicesToolDefinitions.RSS_FETCH -> rssFetch(args)
        WebServicesToolDefinitions.BLOG_WATCH -> blogWatch(args)
        WebServicesToolDefinitions.URL_SHORTEN -> urlShorten(args)
        WebServicesToolDefinitions.URL_EXPAND -> urlExpand(args)
        WebServicesToolDefinitions.QR_GENERATE -> qrGenerate(args)
        WebServicesToolDefinitions.QR_SCAN -> qrScan(args)
        else -> ToolExecutionResult.error("Unknown tool: $toolName")
    }

    private fun fetchThroughTor(url: String): Pair<String?, String?> {
        if (!torManager.isReady.value) {
            return Pair(null, "Tor is not running. Cannot fetch over clearnet.")
        }
        var conn: HttpURLConnection? = null
        return try {
            val proxy = torManager.getSocksProxy()
            conn = (URL(url).openConnection(proxy) as HttpURLConnection)
            conn.requestMethod = "GET"
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            Pair(body, null)
        } catch (e: Exception) {
            try {
                val errorBody = conn?.errorStream?.bufferedReader()?.use { it.readText() }
                Pair(null, "Fetch failed: ${e.message}" + (errorBody?.let { " — $it" } ?: ""))
            } catch (_: Exception) {
                Pair(null, "Fetch failed: ${e.message}")
            }
        } finally {
            conn?.disconnect()
        }
    }

    private suspend fun weatherCurrent(args: Map<String, Any?>): ToolExecutionResult = withContext(Dispatchers.IO) {
        val location = args["location"] as? String ?: return@withContext ToolExecutionResult.error("Missing 'location'")
        val encodedLocation = URLEncoder.encode(location, StandardCharsets.UTF_8.name())
        val (body, error) = fetchThroughTor("https://wttr.in/$encodedLocation?format=j1")
        if (body != null && body.isNotBlank()) {
            try {
                val weather = parseWeatherJson(body)
                // A parse with zero fields means the body was not real weather JSON —
                // wttr.in happily returns error text with HTTP 200. Report honestly
                // instead of declaring success with an empty payload.
                val realData = weather.location.isNotBlank() || weather.temperature != null || weather.condition != null
                if (!realData) {
                    val r = WeatherResult(success = false, location = location, error = "Weather service returned unparseable data for $location")
                    ToolExecutionResult.success(r, json.encodeToString(WeatherResult.serializer(), r))
                } else {
                    val r = WeatherResult(success = true, location = weather.location, temperature = weather.temperature, feelsLike = weather.feelsLike, condition = weather.condition, humidity = weather.humidity, wind = weather.wind, precipitation = weather.precipitation, error = null)
                    // Feed the metadata chain when superadmin allows tool-driven
                    // priming (cache_prime_on_tool_success) and the weather group
                    // is on. The worker remains the owner of the steady cadence.
                    val primeConfig = com.unuslumen.app.data.metadata.MetadataConfigFetcher.getCachedConfig(context)
                    if (primeConfig?.cachePrimeOnToolSuccess == true && primeConfig.groups.weather) {
                        com.unuslumen.app.data.metadata.CacheStore.weather = com.unuslumen.app.data.metadata.CacheStore.WeatherCache(
                            condition = r.condition,
                            temperatureC = r.temperature,
                            location = r.location,
                            timestampMillis = System.currentTimeMillis()
                        )
                    }
                    ToolExecutionResult.success(r, json.encodeToString(WeatherResult.serializer(), r))
                }
            } catch (e: Exception) {
                val r = WeatherResult(success = false, location = location, error = "Failed to parse weather data: ${e.message}")
                ToolExecutionResult.success(r, json.encodeToString(WeatherResult.serializer(), r))
            }
        } else {
            val (simpleBody, simpleError) = fetchThroughTor("https://wttr.in/$encodedLocation?format=3")
            if (simpleBody != null && simpleBody.isNotBlank()) {
                val r = WeatherResult(success = true, location = location, temperature = extractTemperature(simpleBody), condition = simpleBody.trim(), error = null)
                ToolExecutionResult.success(r, json.encodeToString(WeatherResult.serializer(), r))
            } else {
                val r = WeatherResult(success = false, location = location, error = "Weather service unavailable: ${error ?: simpleError}")
                ToolExecutionResult.success(r, json.encodeToString(WeatherResult.serializer(), r))
            }
        }
    }

    private suspend fun weatherForecast(args: Map<String, Any?>): ToolExecutionResult = withContext(Dispatchers.IO) {
        val location = args["location"] as? String ?: return@withContext ToolExecutionResult.error("Missing 'location'")
        val days = (args["days"] as? Number)?.toInt() ?: 3
        val encodedLocation = URLEncoder.encode(location, StandardCharsets.UTF_8.name())
        val (body, error) = fetchThroughTor("https://wttr.in/$encodedLocation?format=j1")
        if (body != null && body.isNotBlank()) {
            val forecast = parseForecastJson(body, days)
            val r = WeatherForecastResult(success = true, location = location, forecast = forecast, error = null)
            ToolExecutionResult.success(r, json.encodeToString(WeatherForecastResult.serializer(), r))
        } else {
            val r = WeatherForecastResult(success = false, location = location, forecast = emptyList(), error = "Forecast unavailable: ${error ?: "Unknown error"}")
            ToolExecutionResult.success(r, json.encodeToString(WeatherForecastResult.serializer(), r))
        }
    }

    private suspend fun weatherAlert(args: Map<String, Any?>): ToolExecutionResult = withContext(Dispatchers.IO) {
        val location = args["location"] as? String ?: return@withContext ToolExecutionResult.error("Missing 'location'")
        val encodedLocation = URLEncoder.encode(location, StandardCharsets.UTF_8.name())
        val (body, error) = fetchThroughTor("https://wttr.in/$encodedLocation?format=j1")
        if (body != null) {
            val alerts = parseWeatherAlerts(body)
            val r = WeatherAlertResult(success = true, location = location, alerts = alerts, error = null)
            ToolExecutionResult.success(r, json.encodeToString(WeatherAlertResult.serializer(), r))
        } else {
            val r = WeatherAlertResult(success = false, location = location, alerts = emptyList(), error = "Alert check failed: ${error ?: "Unknown error"}")
            ToolExecutionResult.success(r, json.encodeToString(WeatherAlertResult.serializer(), r))
        }
    }

    private suspend fun placesSearch(args: Map<String, Any?>): ToolExecutionResult = withContext(Dispatchers.IO) {
        val query = args["query"] as? String ?: return@withContext ToolExecutionResult.error("Missing 'query'")
        val location = args["location"] as? String
        val limit = (args["limit"] as? Number)?.toInt() ?: 10
        val locationArg = location?.let { "--lat-lng \"$it\"" } ?: ""
        val result = shellExecutor.execute("goplaces search \"$query\" --limit $limit $locationArg --json 2>/dev/null")
        if (result.success && result.stdout.isNotBlank()) {
            val places = parsePlacesJson(result.stdout)
            val r = PlacesResult(success = true, places = places, error = null)
            ToolExecutionResult.success(r, json.encodeToString(PlacesResult.serializer(), r))
        } else {
            val r = PlacesResult(success = false, places = emptyList(), error = "Places search requires goplaces CLI with GOOGLE_PLACES_API_KEY configured.")
            ToolExecutionResult.success(r, json.encodeToString(PlacesResult.serializer(), r))
        }
    }

    private suspend fun placesDetails(args: Map<String, Any?>): ToolExecutionResult = withContext(Dispatchers.IO) {
        val placeId = args["placeId"] as? String ?: return@withContext ToolExecutionResult.error("Missing 'placeId'")
        val result = shellExecutor.execute("goplaces details \"$placeId\" --json 2>/dev/null")
        if (result.success && result.stdout.isNotBlank()) {
            val details = parsePlaceDetails(result.stdout)
            val r = PlaceDetailsResult(success = true, details = details, error = null)
            ToolExecutionResult.success(r, json.encodeToString(PlaceDetailsResult.serializer(), r))
        } else {
            val r = PlaceDetailsResult(success = false, details = null, error = "Place details unavailable: ${result.stderr}")
            ToolExecutionResult.success(r, json.encodeToString(PlaceDetailsResult.serializer(), r))
        }
    }

    private suspend fun rssFetch(args: Map<String, Any?>): ToolExecutionResult = withContext(Dispatchers.IO) {
        val url = args["url"] as? String ?: return@withContext ToolExecutionResult.error("Missing 'url'")
        val limit = (args["limit"] as? Number)?.toInt() ?: 10
        val (body, error) = fetchThroughTor(url)
        if (body != null && body.isNotBlank()) {
            val feed = parseRssFeed(body.take(50000), limit)
            val r = RssResult(success = true, feed = feed, error = null)
            ToolExecutionResult.success(r, json.encodeToString(RssResult.serializer(), r))
        } else {
            val r = RssResult(success = false, feed = null, error = "Failed to fetch RSS feed: ${error ?: "Unknown error"}")
            ToolExecutionResult.success(r, json.encodeToString(RssResult.serializer(), r))
        }
    }

    private suspend fun blogWatch(args: Map<String, Any?>): ToolExecutionResult = withContext(Dispatchers.IO) {
        val url = args["url"] as? String ?: return@withContext ToolExecutionResult.error("Missing 'url'")
        val lastChecked = args["lastChecked"] as? String
        val (body, error) = fetchThroughTor(url)
        if (body != null && body.isNotBlank()) {
            val feed = parseRssFeed(body.take(50000), 50)
            val newEntries = if (lastChecked != null) {
                feed.entries.takeWhile { it.id != lastChecked }
            } else {
                feed.entries.take(5)
            }
            val r = BlogWatchResult(success = true, newEntries = newEntries, lastChecked = feed.entries.firstOrNull()?.id, error = null)
            ToolExecutionResult.success(r, json.encodeToString(BlogWatchResult.serializer(), r))
        } else {
            val r = BlogWatchResult(success = false, newEntries = emptyList(), lastChecked = null, error = "Blog watch failed: ${error ?: "Unknown error"}")
            ToolExecutionResult.success(r, json.encodeToString(BlogWatchResult.serializer(), r))
        }
    }

    private suspend fun urlShorten(args: Map<String, Any?>): ToolExecutionResult = withContext(Dispatchers.IO) {
        val url = args["url"] as? String ?: return@withContext ToolExecutionResult.error("Missing 'url'")
        val encodedUrl = URLEncoder.encode(url, StandardCharsets.UTF_8.name())
        val (body, error) = fetchThroughTor("https://is.gd/create.php?format=simple&url=$encodedUrl")
        if (body != null && body.startsWith("http")) {
            val r = UrlResult(success = true, original = url, shortened = body.trim(), error = null)
            ToolExecutionResult.success(r, json.encodeToString(UrlResult.serializer(), r))
        } else {
            val (fallbackBody, fallbackError) = fetchThroughTor("https://tinyurl.com/api-create.php?url=$encodedUrl")
            if (fallbackBody != null && fallbackBody.startsWith("http")) {
                val r = UrlResult(success = true, original = url, shortened = fallbackBody.trim(), error = null)
                ToolExecutionResult.success(r, json.encodeToString(UrlResult.serializer(), r))
            } else {
                val r = UrlResult(success = false, original = url, shortened = null, error = "URL shortening failed: ${error ?: fallbackError ?: "Unknown"}")
                ToolExecutionResult.success(r, json.encodeToString(UrlResult.serializer(), r))
            }
        }
    }

    private suspend fun urlExpand(args: Map<String, Any?>): ToolExecutionResult = withContext(Dispatchers.IO) {
        val url = args["url"] as? String ?: return@withContext ToolExecutionResult.error("Missing 'url'")
        if (!torManager.isReady.value) {
            val r = UrlResult(success = false, original = url, shortened = null, error = "Tor is not running.")
            return@withContext ToolExecutionResult.success(r, json.encodeToString(UrlResult.serializer(), r))
        }
        var conn: HttpURLConnection? = null
        try {
            val proxy = torManager.getSocksProxy()
            conn = (URL(url).openConnection(proxy) as HttpURLConnection)
            conn.requestMethod = "HEAD"
            conn.instanceFollowRedirects = true
            val finalUrl = conn.url.toString()
            val r = UrlResult(success = true, original = url, shortened = finalUrl, error = null)
            ToolExecutionResult.success(r, json.encodeToString(UrlResult.serializer(), r))
        } catch (e: Exception) {
            val r = UrlResult(success = false, original = url, shortened = null, error = "URL expansion failed: ${e.message}")
            ToolExecutionResult.success(r, json.encodeToString(UrlResult.serializer(), r))
        } finally {
            conn?.disconnect()
        }
    }

    private suspend fun qrGenerate(args: Map<String, Any?>): ToolExecutionResult = withContext(Dispatchers.IO) {
        val content = args["content"] as? String ?: return@withContext ToolExecutionResult.error("Missing 'content'")
        val filename = args["filename"] as? String ?: "qr"
        val size = (args["size"] as? Number)?.toInt() ?: 300
        val dir = java.io.File(context.filesDir, "qr")
        dir.mkdirs()
        val outputFile = java.io.File(dir, "${filename}_${System.currentTimeMillis()}.png")
        try {
            val hints = mapOf(
                com.google.zxing.EncodeHintType.MARGIN to 1,
                com.google.zxing.EncodeHintType.CHARACTER_SET to "UTF-8"
            )
            val bitMatrix = com.google.zxing.qrcode.QRCodeWriter().encode(
                content, com.google.zxing.BarcodeFormat.QR_CODE, size, size, hints
            )
            val bitmap = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
            for (x in 0 until size) {
                for (y in 0 until size) {
                    bitmap.setPixel(x, y, if (bitMatrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
                }
            }
            val outputStream = java.io.FileOutputStream(outputFile)
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, outputStream)
            outputStream.flush()
            outputStream.close()
            bitmap.recycle()
            val r = QrResult(success = true, path = outputFile.absolutePath, content = content, error = null)
            ToolExecutionResult.success(r, json.encodeToString(QrResult.serializer(), r))
        } catch (e: Exception) {
            val encodedContent = content.replace("\"", "\\\"")
            val result = shellExecutor.execute("qrencode -o \"${outputFile.absolutePath}\" \"$encodedContent\" 2>/dev/null")
            if (result.success && outputFile.exists() && outputFile.length() > 0) {
                val r = QrResult(success = true, path = outputFile.absolutePath, content = content, error = null)
                return@withContext ToolExecutionResult.success(r, json.encodeToString(QrResult.serializer(), r))
            }
            try {
                val encoded = java.net.URLEncoder.encode(content, "UTF-8")
                val (imageBytes, fetchError) = fetchThroughTor("https://api.qrserver.com/v1/create-qr-code/?size=${size}x${size}&data=$encoded")
                if (imageBytes != null && imageBytes.isNotEmpty()) {
                    outputFile.writeBytes(imageBytes.toByteArray(Charsets.ISO_8859_1))
                    if (outputFile.exists() && outputFile.length() > 100) {
                        val r = QrResult(success = true, path = outputFile.absolutePath, content = content, error = null)
                        return@withContext ToolExecutionResult.success(r, json.encodeToString(QrResult.serializer(), r))
                    }
                }
            } catch (_: Exception) {}
            val r = QrResult(success = false, path = null, content = content, error = "QR generation failed: ${e.message}. All methods exhausted.")
            ToolExecutionResult.success(r, json.encodeToString(QrResult.serializer(), r))
        }
    }

    private suspend fun qrScan(args: Map<String, Any?>): ToolExecutionResult = withContext(Dispatchers.IO) {
        val imagePath = args["imagePath"] as? String ?: return@withContext ToolExecutionResult.error("Missing 'imagePath'")
        val file = java.io.File(imagePath)
        if (!file.exists()) {
            val r = QrResult(success = false, path = imagePath, content = null, error = "File not found: $imagePath")
            return@withContext ToolExecutionResult.success(r, json.encodeToString(QrResult.serializer(), r))
        }
        try {
            val bitmap = android.graphics.BitmapFactory.decodeFile(imagePath)
            if (bitmap != null) {
                val width = bitmap.width
                val height = bitmap.height
                val pixels = IntArray(width * height)
                bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
                val source = com.google.zxing.RGBLuminanceSource(width, height, pixels)
                val binaryBitmap = com.google.zxing.BinaryBitmap(com.google.zxing.common.HybridBinarizer(source))
                val reader = com.google.zxing.qrcode.QRCodeReader()
                val result = reader.decode(binaryBitmap)
                bitmap.recycle()
                if (result != null) {
                    val r = QrResult(success = true, path = imagePath, content = result.text, error = null)
                    return@withContext ToolExecutionResult.success(r, json.encodeToString(QrResult.serializer(), r))
                }
                bitmap.recycle()
            }
        } catch (_: Exception) {}
        val result = shellExecutor.execute("zbarimg --raw \"$imagePath\" 2>/dev/null")
        if (result.success && result.stdout.isNotBlank()) {
            val content = result.stdout.trim()
            val r = QrResult(success = true, path = imagePath, content = content, error = null)
            ToolExecutionResult.success(r, json.encodeToString(QrResult.serializer(), r))
        } else {
            val r = QrResult(success = false, path = imagePath, content = null, error = "QR scan failed. No decoder available.")
            ToolExecutionResult.success(r, json.encodeToString(QrResult.serializer(), r))
        }
    }

    private data class WeatherData(val location: String, val temperature: Double?, val feelsLike: Double?, val condition: String?, val humidity: Int?, val wind: Double?, val precipitation: Double?)

    private fun parseWeatherJson(json: String): WeatherData {
        val tempMatch = Regex("\"temp_c\"\\s*:\\s*([\\d.]+)").find(json)
        val feelsMatch = Regex("\"feelslike_c\"\\s*:\\s*([\\d.]+)").find(json)
        val conditionMatch = Regex("\"text\"\\s*:\\s*\"([^\"]+)\"").find(json)
        val humidityMatch = Regex("\"humidity\"\\s*:\\s*(\\d+)").find(json)
        val windMatch = Regex("\"wind_kph\"\\s*:\\s*([\\d.]+)").find(json)
        val precipMatch = Regex("\"precip_mm\"\\s*:\\s*([\\d.]+)").find(json)
        val locationMatch = Regex("\"name\"\\s*:\\s*\"([^\"]+)\"").find(json)
        return WeatherData(
            location = locationMatch?.groupValues?.get(1) ?: "",
            temperature = tempMatch?.groupValues?.get(1)?.toDoubleOrNull(),
            feelsLike = feelsMatch?.groupValues?.get(1)?.toDoubleOrNull(),
            condition = conditionMatch?.groupValues?.get(1),
            humidity = humidityMatch?.groupValues?.get(1)?.toIntOrNull(),
            wind = windMatch?.groupValues?.get(1)?.toDoubleOrNull(),
            precipitation = precipMatch?.groupValues?.get(1)?.toDoubleOrNull()
        )
    }

    private fun parseForecastJson(json: String, days: Int): List<ForecastDay> {
        val dayMatches = Regex("\"date\"\\s*:\\s*\"([^\"]+)\"").findAll(json).take(days)
        val maxTempMatches = Regex("\"maxtemp_c\"\\s*:\\s*([\\d.]+)").findAll(json).take(days)
        val minTempMatches = Regex("\"mintemp_c\"\\s*:\\s*([\\d.]+)").findAll(json).take(days)
        val conditionMatches = Regex("\"text\"\\s*:\\s*\"([^\"]+)\"").findAll(json).take(days * 2)
        return dayMatches.mapIndexed { index, match ->
            ForecastDay(
                date = match.groupValues[1],
                maxTemp = maxTempMatches.elementAtOrNull(index)?.groupValues?.get(1)?.toDoubleOrNull(),
                minTemp = minTempMatches.elementAtOrNull(index)?.groupValues?.get(1)?.toDoubleOrNull(),
                condition = conditionMatches.elementAtOrNull(index * 2)?.groupValues?.get(1) ?: ""
            )
        }.toList()
    }

    private fun parseWeatherAlerts(json: String): List<WeatherAlert> {
        val alertMatches = Regex("\"headline\"\\s*:\\s*\"([^\"]+)\"").findAll(json)
        return alertMatches.map { match ->
            WeatherAlert(headline = match.groupValues[1], severity = "unknown")
        }.toList()
    }

    private fun extractTemperature(text: String): Double? {
        return Regex("(-?\\d+)").find(text)?.groupValues?.get(1)?.toDoubleOrNull()
    }

    private fun parsePlacesJson(json: String): List<PlaceInfo> {
        val nameMatches = Regex("\"displayName\"\\s*:\\s*\\{[^}]*\"text\"\\s*:\\s*\"([^\"]+)\"").findAll(json)
        val addressMatches = Regex("\"formattedAddress\"\\s*:\\s*\"([^\"]+)\"").findAll(json)
        val ratingMatches = Regex("\"rating\"\\s*:\\s*([\\d.]+)").findAll(json)
        return nameMatches.mapIndexed { index, match ->
            PlaceInfo(
                id = "place_$index",
                name = match.groupValues[1],
                address = addressMatches.elementAtOrNull(index)?.groupValues?.get(1) ?: "",
                rating = ratingMatches.elementAtOrNull(index)?.groupValues?.get(1)?.toDoubleOrNull(),
                types = emptyList()
            )
        }.take(10).toList()
    }

    private fun parsePlaceDetails(json: String): PlaceDetails? {
        val nameMatch = Regex("\"displayName\"\\s*:\\s*\\{[^}]*\"text\"\\s*:\\s*\"([^\"]+)\"").find(json)
        val addressMatch = Regex("\"formattedAddress\"\\s*:\\s*\"([^\"]+)\"").find(json)
        val phoneMatch = Regex("\"nationalPhoneNumber\"\\s*:\\s*\"([^\"]+)\"").find(json)
        val ratingMatch = Regex("\"rating\"\\s*:\\s*([\\d.]+)").find(json)
        val openMatch = Regex("\"openNow\"\\s*:\\s*(true|false)").find(json)
        return PlaceDetails(
            id = "",
            name = nameMatch?.groupValues?.get(1) ?: "",
            address = addressMatch?.groupValues?.get(1) ?: "",
            phoneNumber = phoneMatch?.groupValues?.get(1),
            rating = ratingMatch?.groupValues?.get(1)?.toDoubleOrNull(),
            isOpen = openMatch?.groupValues?.get(1)?.toBoolean(),
            reviews = emptyList()
        )
    }

    private fun parseRssFeed(xml: String, limit: Int): RssFeed {
        val titleMatch = Regex("<title><!\\[CDATA\\[([^]]+)\\]\\]></title>|<title>([^<]+)</title>").find(xml)

        // Capture the content inside each <item>...</item> or <entry>...</entry> block
        // DOT_MATCHES_ALL so newlines inside entries are handled
        val entryPattern = Regex("<item>(.*?)</item>|<entry>(.*?)</entry>", RegexOption.DOT_MATCHES_ALL)
        val entries = entryPattern.findAll(xml).take(limit).map { match ->
            val entryXml = match.groupValues[1].ifEmpty { match.groupValues[2] }

            val titleRegex = Regex("<title><!\\[CDATA\\[([^]]+)\\]\\]></title>|<title>([^<]+)</title>")
            val titleMatch = titleRegex.find(entryXml)
            val entryTitle = titleMatch?.let { it.groupValues[1].ifEmpty { it.groupValues[2] } } ?: ""

            val linkRegex = Regex("<link[^>]*href=\"([^\"]+)\"|<link>([^<]+)</link>")
            val linkMatch = linkRegex.find(entryXml)
            val entryLink = linkMatch?.let { it.groupValues[1].ifEmpty { it.groupValues[2] } } ?: ""

            val idRegex = Regex("<id>([^<]+)</id>|<guid[^>]*>([^<]+)</guid>")
            val idMatch = idRegex.find(entryXml)
            val entryId = idMatch?.let { it.groupValues[1].ifEmpty { it.groupValues[2] } } ?: entryLink

            RssEntry(id = entryId, title = entryTitle, link = entryLink, summary = null, published = null)
        }.toList()

        return RssFeed(
            title = titleMatch?.let { it.groupValues[1].ifEmpty { it.groupValues[2] } } ?: "",
            entries = entries
        )
    }
}