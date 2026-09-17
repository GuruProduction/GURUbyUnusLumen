package com.unuslumen.app.data.tools

import com.unuslumen.app.data.tools.registry.ToolResultData
import kotlinx.serialization.Serializable

@Serializable data class WeatherResult(val success: Boolean, val location: String, val temperature: Double? = null, val feelsLike: Double? = null, val condition: String? = null, val humidity: Int? = null, val wind: Double? = null, val precipitation: Double? = null, val error: String? = null) : ToolResultData
@Serializable data class ForecastDay(val date: String, val maxTemp: Double? = null, val minTemp: Double? = null, val condition: String) : ToolResultData
@Serializable data class WeatherForecastResult(val success: Boolean, val location: String, val forecast: List<ForecastDay>, val error: String? = null) : ToolResultData
@Serializable data class WeatherAlert(val headline: String, val severity: String) : ToolResultData
@Serializable data class WeatherAlertResult(val success: Boolean, val location: String, val alerts: List<WeatherAlert>, val error: String? = null) : ToolResultData
@Serializable data class PlaceInfo(val id: String, val name: String, val address: String, val rating: Double? = null, val types: List<String>) : ToolResultData
@Serializable data class PlacesResult(val success: Boolean, val places: List<PlaceInfo>, val error: String? = null) : ToolResultData
@Serializable data class PlaceDetails(val id: String, val name: String, val address: String, val phoneNumber: String? = null, val rating: Double? = null, val isOpen: Boolean? = null, val reviews: List<String>) : ToolResultData
@Serializable data class PlaceDetailsResult(val success: Boolean, val details: PlaceDetails? = null, val error: String? = null) : ToolResultData
@Serializable data class RssEntry(val id: String, val title: String, val link: String, val summary: String? = null, val published: String? = null) : ToolResultData
@Serializable data class RssFeed(val title: String, val entries: List<RssEntry>) : ToolResultData
@Serializable data class RssResult(val success: Boolean, val feed: RssFeed? = null, val error: String? = null) : ToolResultData
@Serializable data class BlogWatchResult(val success: Boolean, val newEntries: List<RssEntry>, val lastChecked: String? = null, val error: String? = null) : ToolResultData
@Serializable data class UrlResult(val success: Boolean, val original: String, val shortened: String? = null, val error: String? = null) : ToolResultData
@Serializable data class QrResult(val success: Boolean, val path: String? = null, val content: String? = null, val error: String? = null) : ToolResultData