package com.unuslumen.app.data.tools

import com.unuslumen.app.data.tools.registry.ToolDefinition
import com.unuslumen.app.data.tools.registry.ToolExecutor
import com.unuslumen.app.data.tools.registry.ToolParameter
import com.unuslumen.app.data.tools.registry.ToolParameterType
import com.unuslumen.app.data.tools.registry.ToolResultExtractor
import com.unuslumen.app.data.tools.registry.ToolSetRegistration
import kotlin.reflect.KClass

object WebServicesToolDefinitions : ToolSetRegistration {
    const val WEATHER_CURRENT = "weatherCurrent"
    const val WEATHER_FORECAST = "weatherForecast"
    const val WEATHER_ALERT = "weatherAlert"
    const val PLACES_SEARCH = "placesSearch"
    const val PLACES_DETAILS = "placesDetails"
    const val RSS_FETCH = "rssFetch"
    const val BLOG_WATCH = "blogWatch"
    const val URL_SHORTEN = "urlShorten"
    const val URL_EXPAND = "urlExpand"
    const val QR_GENERATE = "qrGenerate"
    const val QR_SCAN = "qrScan"

    override val definitions = listOf(
        ToolDefinition(name = WEATHER_CURRENT, description = "Get current weather for a location through Tor. Returns temperature, conditions, humidity, wind, and precipitation.", category = "web_services", parameters = listOf(ToolParameter("location", ToolParameterType.String, true, "Location - city name, airport code, or coordinates (e.g., 'London', 'JFK', '51.5,-0.1')")), permissions = emptyList()),
        ToolDefinition(name = WEATHER_FORECAST, description = "Get weather forecast for a location through Tor. Returns multi-day forecast with temperatures and conditions.", category = "web_services", parameters = listOf(ToolParameter("location", ToolParameterType.String, true, "Location - city name or coordinates"), ToolParameter("days", ToolParameterType.Integer, false, "Number of days to forecast (1-5). Default 3.")), permissions = emptyList()),
        ToolDefinition(name = WEATHER_ALERT, description = "Check for weather alerts/warnings at a location through Tor.", category = "web_services", parameters = listOf(ToolParameter("location", ToolParameterType.String, true, "Location - city name or coordinates")), permissions = emptyList()),
        ToolDefinition(name = PLACES_SEARCH, description = "Search for places nearby. Returns businesses, landmarks, and points of interest with names, addresses, and ratings.", category = "web_services", parameters = listOf(ToolParameter("query", ToolParameterType.String, true, "Search query - what to find (e.g., 'coffee', 'pizza', 'gas station')"), ToolParameter("location", ToolParameterType.String, false, "Location bias - city name or coordinates"), ToolParameter("limit", ToolParameterType.Integer, false, "Maximum results. Default 10.")), permissions = emptyList()),
        ToolDefinition(name = PLACES_DETAILS, description = "Get detailed information about a specific place including reviews, hours, and contact info.", category = "web_services", parameters = listOf(ToolParameter("placeId", ToolParameterType.String, true, "Place ID from a previous search")), permissions = emptyList()),
        ToolDefinition(name = RSS_FETCH, description = "Fetch and parse an RSS/Atom feed through Tor. Returns feed title and recent entries.", category = "web_services", parameters = listOf(ToolParameter("url", ToolParameterType.String, true, "RSS/Atom feed URL"), ToolParameter("limit", ToolParameterType.Integer, false, "Maximum entries to return. Default 10.")), permissions = emptyList()),
        ToolDefinition(name = BLOG_WATCH, description = "Check a blog or website for new posts since last check through Tor. Returns new entries.", category = "web_services", parameters = listOf(ToolParameter("url", ToolParameterType.String, true, "RSS/Atom feed URL to watch"), ToolParameter("lastChecked", ToolParameterType.String, false, "Last checked entry ID or timestamp")), permissions = emptyList()),
        ToolDefinition(name = URL_SHORTEN, description = "Shorten a URL through Tor using a URL shortening service.", category = "web_services", parameters = listOf(ToolParameter("url", ToolParameterType.String, true, "URL to shorten")), permissions = emptyList()),
        ToolDefinition(name = URL_EXPAND, description = "Expand a shortened URL through Tor to reveal the original URL.", category = "web_services", parameters = listOf(ToolParameter("url", ToolParameterType.String, true, "Shortened URL to expand")), permissions = emptyList()),
        ToolDefinition(name = QR_GENERATE, description = "Generate a QR code image from text or URL. Returns the path to the generated image. Works fully offline using on-device QR generation.", category = "web_services", parameters = listOf(ToolParameter("content", ToolParameterType.String, true, "Text or URL to encode"), ToolParameter("filename", ToolParameterType.String, false, "Filename without extension. Default 'qr'."), ToolParameter("size", ToolParameterType.Integer, false, "Image size in pixels. Default 300.")), permissions = emptyList()),
        ToolDefinition(name = QR_SCAN, description = "Scan a QR code image and decode its content. Works fully offline using on-device decoding.", category = "web_services", parameters = listOf(ToolParameter("imagePath", ToolParameterType.String, true, "Path to the QR code image file")), permissions = emptyList())
    )
    override fun executorClass(): KClass<out ToolExecutor> = WebServicesToolExecutor::class
    override fun extractorClass(): KClass<out ToolResultExtractor>? = null
}