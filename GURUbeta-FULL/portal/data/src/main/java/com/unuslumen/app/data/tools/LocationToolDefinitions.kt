package com.unuslumen.app.data.tools

import com.unuslumen.app.data.tools.registry.ToolDefinition
import com.unuslumen.app.data.tools.registry.ToolExecutor
import com.unuslumen.app.data.tools.registry.ToolParameter
import com.unuslumen.app.data.tools.registry.ToolParameterType
import com.unuslumen.app.data.tools.registry.ToolResultExtractor
import com.unuslumen.app.data.tools.registry.ToolSetRegistration
import kotlin.reflect.KClass

object LocationToolDefinitions : ToolSetRegistration {
    const val GET_CURRENT_LOCATION = "getCurrentLocation"
    const val GEOCODE = "geocode"
    const val REVERSE_GEOCODE = "reverseGeocode"

    override val definitions = listOf(
        ToolDefinition(name = GET_CURRENT_LOCATION, description = "Get the device's current GPS location. Returns latitude, longitude, accuracy, altitude, speed, and bearing. Use for location-based queries, weather that works without API keys, navigation, or knowing where your human is.", category = "location", parameters = emptyList(), permissions = emptyList()),
        ToolDefinition(name = GEOCODE, description = "Convert an address to GPS coordinates. Use when your human says 'where is X' or you need coordinates for a place name.", category = "location", parameters = listOf(ToolParameter("address", ToolParameterType.String, true, "Address to look up, e.g. '1600 Amphitheatre Parkway, Mountain View, CA'")), permissions = emptyList()),
        ToolDefinition(name = REVERSE_GEOCODE, description = "Convert GPS coordinates to an address. Use when you have coordinates and need to know what's there.", category = "location", parameters = listOf(ToolParameter("latitude", ToolParameterType.Float, true, "Latitude"), ToolParameter("longitude", ToolParameterType.Float, true, "Longitude")), permissions = emptyList())
    )
    override fun executorClass(): KClass<out ToolExecutor> = LocationToolExecutor::class
    override fun extractorClass(): KClass<out ToolResultExtractor>? = null
}