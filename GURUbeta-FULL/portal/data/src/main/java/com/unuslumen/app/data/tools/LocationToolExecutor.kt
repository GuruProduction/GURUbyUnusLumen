// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.tools

import android.content.Context
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import com.unuslumen.app.data.tools.registry.ToolExecutionResult
import com.unuslumen.app.data.tools.registry.ToolExecutor
import com.unuslumen.app.data.tools.registry.permission.PermissionGrants
import com.unuslumen.app.data.tools.registry.permission.TeamThreePermissions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.util.Locale
import kotlin.coroutines.resume

/**
 * Location, where "granted" is not one boolean.
 *
 * THE DEFECT THIS FIXES. getCurrentLocation caught SecurityException and returned
 * `ToolExecutionResult.success(...)` carrying an error string, which is the forbidden
 * pattern for this batch: a caller cannot tell a permission refusal from a real fix
 * with no data, and the success flag says the call worked when it did not. Worse, the
 * catch hid the approximate-only grant: an approximate fix arrived through a stale
 * last-known location and was reported as if it were precise.
 *
 * WHAT IT DOES NOW. The grant is checked first and the refusal is PermissionGrants'
 * own sentence. The precision actually in force is reported on every fix, because a
 * coarse grant must never read as a street-level fact. A grant with Location switched
 * off is its own state, named as such, and a real fix is separated from a null one.
 */
class LocationToolExecutor(private val context: Context) : ToolExecutor {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val locationManager: LocationManager
        get() = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    override suspend fun execute(toolName: String, args: Map<String, Any?>): ToolExecutionResult = when (toolName) {
        LocationToolDefinitions.GET_CURRENT_LOCATION -> getCurrentLocation()
        LocationToolDefinitions.GEOCODE -> geocode(args)
        LocationToolDefinitions.REVERSE_GEOCODE -> reverseGeocode(args)
        else -> ToolExecutionResult.error("Unknown tool: $toolName")
    }

    /**
     * One fix, reported with the precision actually in force.
     *
     * The order is the point. The grant is answered first, from the catalogue, because
     * a refusal is about access and must never look like a fix with no data. Only then
     * is the switch state consulted, then the provider chosen from the grant, because
     * an approximate-only grant may not use GPS and asking anyway throws.
     */
    private suspend fun getCurrentLocation(): ToolExecutionResult = withContext(Dispatchers.Main) {
        val precision = PermissionGrants.locationPrecision(context)
        if (precision == PermissionGrants.LocationPrecision.NONE) {
            return@withContext PermissionGrants.refusal(
                PermissionGrants.check(context, TeamThreePermissions.ACCESS_COARSE_LOCATION)
            )
        }

        if (!PermissionGrants.isLocationEnabled(context)) {
            val r = LocationResult(
                success = false,
                precision = precision.name,
                error = "Location is switched off in Settings, so GURU holds the permission but no " +
                    "provider can answer until Location is turned on.",
            )
            return@withContext ToolExecutionResult.success(r, json.encodeToString(LocationResult.serializer(), r))
        }

        val enabledProviders = runCatching { locationManager.getProviders(true) }.getOrNull().orEmpty()
        // A coarse grant may not use GPS: the platform throws SecurityException for it,
        // so the provider is chosen from the grant rather than discovered and retried.
        val preferred = if (precision == PermissionGrants.LocationPrecision.PRECISE) {
            listOf(LocationManager.GPS_PROVIDER, LocationManager.FUSED_PROVIDER, LocationManager.NETWORK_PROVIDER)
        } else {
            listOf(LocationManager.NETWORK_PROVIDER, LocationManager.FUSED_PROVIDER)
        }
        val provider = preferred.firstOrNull { it in enabledProviders }

        if (provider == null) {
            val r = LocationResult(
                success = false,
                precision = precision.name,
                error = "No provider that the ${precision.name} grant permits is enabled on this " +
                    "device, so no fix can be requested; this is the device's state rather than a " +
                    "missing permission.",
            )
            return@withContext ToolExecutionResult.success(r, json.encodeToString(LocationResult.serializer(), r))
        }

        val location: Location? = try {
            suspendCancellableCoroutine { cont ->
                val lastKnown = runCatching { locationManager.getLastKnownLocation(provider) }.getOrNull()
                if (lastKnown != null && System.currentTimeMillis() - lastKnown.time < 60_000L) {
                    cont.resume(lastKnown)
                    return@suspendCancellableCoroutine
                }

                val listener = object : LocationListener {
                    override fun onLocationChanged(loc: Location) {
                        runCatching { locationManager.removeUpdates(this) }
                        cont.resume(loc)
                    }

                    override fun onProviderDisabled(p: String) {}
                    override fun onProviderEnabled(p: String) {}
                }

                // requestLocationUpdates throws for a grant that does not permit the
                // provider; letting it propagate means the refusal below is built from
                // the grant state at the moment it failed, not guessed at beforehand.
                locationManager.requestLocationUpdates(provider, 0L, 0F, listener, Looper.getMainLooper())
                cont.invokeOnCancellation { runCatching { locationManager.removeUpdates(listener) } }
            }
        } catch (e: SecurityException) {
            // The grant changed between the check and the call. Re-check so the
            // sentence describes the state now rather than the state a moment ago.
            return@withContext PermissionGrants.refusal(
                PermissionGrants.check(context, TeamThreePermissions.ACCESS_COARSE_LOCATION)
            )
        } catch (e: Exception) {
            val r = LocationResult(
                success = false,
                precision = precision.name,
                error = "The location provider failed: ${e.message}",
            )
            return@withContext ToolExecutionResult.success(r, json.encodeToString(LocationResult.serializer(), r))
        }

        if (location == null) {
            val r = LocationResult(
                success = false,
                precision = precision.name,
                error = "No fix arrived from $provider. A grant and a switched-on Location do not " +
                    "guarantee a fix, especially indoors; this is no data, not no permission.",
            )
            return@withContext ToolExecutionResult.success(r, json.encodeToString(LocationResult.serializer(), r))
        }

        // Prime the metadata chain's place cache directly from raw GPS when superadmin
        // allows tool-driven priming (cache_prime_on_tool_success) and the place group
        // is on. The worker owns the steady cadence.
        val primeConfig = com.unuslumen.app.data.metadata.MetadataConfigFetcher.getCachedConfig(context)
        if (primeConfig?.cachePrimeOnToolSuccess == true && primeConfig.groups.place) {
            com.unuslumen.app.data.metadata.CacheStore.place =
                com.unuslumen.app.data.metadata.CacheStore.PlaceCache(
                    town = "%.4f, %.4f".format(Locale.US, location.latitude, location.longitude),
                    timestampMillis = System.currentTimeMillis()
                )
        }

        // The trace is fed from this path too, so locationHistory has something real to
        // list even when only a one-shot fix was ever asked for.
        com.unuslumen.app.data.tools.senses.LocationTrace.record(
            com.unuslumen.app.data.tools.senses.LocationFix(
                latitude = location.latitude,
                longitude = location.longitude,
                accuracyMetres = location.accuracy.toDouble(),
                precision = precision.name,
                provider = location.provider ?: "unknown",
                timeMillis = location.time,
                altitudeMetres = if (location.hasAltitude()) location.altitude else null,
                speedMetresPerSecond = if (location.hasSpeed()) location.speed.toDouble() else null,
                bearingDegrees = if (location.hasBearing()) location.bearing.toDouble() else null,
            )
        )

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
            precision = precision.name,
            error = null
        )
        ToolExecutionResult.success(r, json.encodeToString(LocationResult.serializer(), r))
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