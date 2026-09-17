package com.unuslumen.app.data.tools

import android.content.Context
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import com.unuslumen.app.data.tools.registry.ToolExecutionResult
import com.unuslumen.app.data.tools.registry.ToolExecutor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.util.Locale
import kotlin.coroutines.resume

class LocationToolExecutor(private val context: Context) : ToolExecutor {
    private val json = Json { ignoreUnknownKeys = true }
    private val locationManager: LocationManager
        get() = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    override suspend fun execute(toolName: String, args: Map<String, Any?>): ToolExecutionResult = when (toolName) {
        LocationToolDefinitions.GET_CURRENT_LOCATION -> getCurrentLocation()
        LocationToolDefinitions.GEOCODE -> geocode(args)
        LocationToolDefinitions.REVERSE_GEOCODE -> reverseGeocode(args)
        else -> ToolExecutionResult.error("Unknown tool: $toolName")
    }

    private suspend fun getCurrentLocation(): ToolExecutionResult = withContext(Dispatchers.Main) {
        try {
            val location = suspendCancellableCoroutine<Location?> { cont ->
                val providers = locationManager.getProviders(true)
                if (providers.isNullOrEmpty()) {
                    cont.resume(null)
                    return@suspendCancellableCoroutine
                }
                val provider = if (providers.contains(LocationManager.GPS_PROVIDER)) LocationManager.GPS_PROVIDER
                    else providers.first()

                val lastKnown = locationManager.getLastKnownLocation(provider)
                if (lastKnown != null && System.currentTimeMillis() - lastKnown.time < 60000) {
                    cont.resume(lastKnown)
                    return@suspendCancellableCoroutine
                }

                val listener = object : LocationListener {
                    override fun onLocationChanged(loc: Location) {
                        locationManager.removeUpdates(this)
                        cont.resume(loc)
                    }
                    override fun onProviderDisabled(p: String) {}
                    override fun onProviderEnabled(p: String) {}
                }

                try {
                    locationManager.requestLocationUpdates(provider, 0L, 0F, listener, Looper.getMainLooper())
                } catch (e: SecurityException) {
                    cont.resume(lastKnown)
                }
            }
            if (location == null) {
                val r = LocationResult(success = false, error = "Location unavailable. Check GPS is enabled and location permission is granted.")
                ToolExecutionResult.success(r, json.encodeToString(LocationResult.serializer(), r))
            } else {
                // Prime the metadata chain's place cache directly from raw GPS when
                // superadmin allows tool-driven priming (cache_prime_on_tool_success)
                // and the place group is on. The worker owns the steady cadence.
                val primeConfig = com.unuslumen.app.data.metadata.MetadataConfigFetcher.getCachedConfig(context)
                if (primeConfig?.cachePrimeOnToolSuccess == true && primeConfig.groups.place) {
                    com.unuslumen.app.data.metadata.CacheStore.place =
                        com.unuslumen.app.data.metadata.CacheStore.PlaceCache(
                            town = "%.4f, %.4f".format(Locale.US, location.latitude, location.longitude),
                            timestampMillis = System.currentTimeMillis()
                        )
                }
                val r = LocationResult(
                    success = true,
                    latitude = location.latitude,
                    longitude = location.longitude,
                    accuracy = location.accuracy.toDouble(),
                    altitude = location.altitude,
                    speed = location.speed.toDouble(),
                    bearing = location.bearing.toDouble(),
                    provider = location.provider ?: "unknown",
                    time = location.time,
                    error = null
                )
                ToolExecutionResult.success(r, json.encodeToString(LocationResult.serializer(), r))
            }
        } catch (e: SecurityException) {
            val r = LocationResult(success = false, error = "Location permission not granted. Enable in Settings > Apps > guru > Permissions > Location.")
            ToolExecutionResult.success(r, json.encodeToString(LocationResult.serializer(), r))
        } catch (e: Exception) {
            val r = LocationResult(success = false, error = "Location error: ${e.message}")
            ToolExecutionResult.success(r, json.encodeToString(LocationResult.serializer(), r))
        }
    }

    private suspend fun geocode(args: Map<String, Any?>): ToolExecutionResult = withContext(Dispatchers.IO) {
        val address = args["address"] as? String ?: return@withContext ToolExecutionResult.error("Missing 'address'")
        try {
            val geocoder = Geocoder(context, Locale.getDefault())
            @Suppress("DEPRECATION")
            val addresses = geocoder.getFromLocationName(address, 5) ?: emptyList()
            if (addresses.isNullOrEmpty()) {
                val r = GeocodeResult(success = false, results = emptyList(), error = "No results found for: $address")
                return@withContext ToolExecutionResult.success(r, json.encodeToString(GeocodeResult.serializer(), r))
            }
            val results = addresses.map { addr ->
                GeocodeEntry(
                    latitude = addr.latitude,
                    longitude = addr.longitude,
                    address = addr.getAddressLine(0) ?: "",
                    locality = addr.locality ?: "",
                    country = addr.countryName ?: ""
                )
            }
            val r = GeocodeResult(success = true, results = results, error = null)
            ToolExecutionResult.success(r, json.encodeToString(GeocodeResult.serializer(), r))
        } catch (e: Exception) {
            val r = GeocodeResult(success = false, results = emptyList(), error = "Geocoding failed: ${e.message}")
            ToolExecutionResult.success(r, json.encodeToString(GeocodeResult.serializer(), r))
        }
    }

    private suspend fun reverseGeocode(args: Map<String, Any?>): ToolExecutionResult = withContext(Dispatchers.IO) {
        val latitude = (args["latitude"] as? Number)?.toDouble() ?: return@withContext ToolExecutionResult.error("Missing 'latitude'")
        val longitude = (args["longitude"] as? Number)?.toDouble() ?: return@withContext ToolExecutionResult.error("Missing 'longitude'")
        try {
            val geocoder = Geocoder(context, Locale.getDefault())
            @Suppress("DEPRECATION")
            val addresses = geocoder.getFromLocation(latitude, longitude, 5) ?: emptyList()
            if (addresses.isNullOrEmpty()) {
                val r = GeocodeResult(success = false, results = emptyList(), error = "No address found for $latitude, $longitude")
                return@withContext ToolExecutionResult.success(r, json.encodeToString(GeocodeResult.serializer(), r))
            }
            val results = addresses.map { addr ->
                GeocodeEntry(
                    latitude = addr.latitude,
                    longitude = addr.longitude,
                    address = addr.getAddressLine(0) ?: "",
                    locality = addr.locality ?: "",
                    country = addr.countryName ?: ""
                )
            }
            // Feed the metadata chain's place cache with the resolved town when
            // superadmin allows tool-driven priming (cache_prime_on_tool_success)
            // and the place group is on.
            val primeConfig = com.unuslumen.app.data.metadata.MetadataConfigFetcher.getCachedConfig(context)
            val firstLocality = results.firstOrNull()?.locality
            if (primeConfig?.cachePrimeOnToolSuccess == true && primeConfig.groups.place && !firstLocality.isNullOrBlank()) {
                com.unuslumen.app.data.metadata.CacheStore.place =
                    com.unuslumen.app.data.metadata.CacheStore.PlaceCache(
                        town = firstLocality,
                        timestampMillis = System.currentTimeMillis()
                    )
            }
            val r = GeocodeResult(success = true, results = results, error = null)
            ToolExecutionResult.success(r, json.encodeToString(GeocodeResult.serializer(), r))
        } catch (e: Exception) {
            val r = GeocodeResult(success = false, results = emptyList(), error = "Reverse geocoding failed: ${e.message}")
            ToolExecutionResult.success(r, json.encodeToString(GeocodeResult.serializer(), r))
        }
    }
}