// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.tools.senses

import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.os.Looper
import android.provider.MediaStore
import com.unuslumen.app.data.hooks.HookEventBus
import com.unuslumen.app.data.tools.registry.ToolDefinition
import com.unuslumen.app.data.tools.registry.ToolExecutionResult
import com.unuslumen.app.data.tools.registry.ToolExecutor
import com.unuslumen.app.data.tools.registry.ToolParameter
import com.unuslumen.app.data.tools.registry.ToolParameterType
import com.unuslumen.app.data.tools.registry.ToolResultData
import com.unuslumen.app.data.tools.registry.ToolResultExtractor
import com.unuslumen.app.data.tools.registry.ToolSetRegistration
import com.unuslumen.app.data.tools.registry.permission.DomainToolModule
import com.unuslumen.app.data.tools.registry.permission.PermissionGrants
import com.unuslumen.app.data.tools.registry.permission.TeamThreePermissions
import com.unuslumen.app.domain.model.HookEventType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.koin.core.module.Module
import org.koin.dsl.module
import kotlin.reflect.KClass

/**
 * The location domain: a bounded watch, the trace of fixes this process has seen,
 * and the location a photo actually carries.
 *
 * WHAT IS NOT HERE. getCurrentLocation already exists and is registered, so it is
 * not duplicated. It was given the precision, the switched-off state and the
 * refusal sentence in place, in LocationToolExecutor, because a second tool that
 * returns a fix would be a second place for "approximate" to be forgotten.
 *
 * WHY THE THREE TOOLS ARE SEPARATE. getCurrentLocation answers "where are you
 * now". locationWatch answers "where did you go while I was not looking", which
 * needs a different grant, a different Android flow and a real battery cost.
 * locationHistory answers "where have we been in this session" and is honest that
 * it is not a stored history. photoLocation answers "where was this taken", which
 * is EXIF metadata rather than the device's position, and Android redacts it
 * without its own permission.
 */

@Serializable
data class LocationFix(
    val latitude: Double,
    val longitude: Double,
    /** Horizontal accuracy in metres as Android reported it. Never invented. */
    val accuracyMetres: Double,
    /** PRECISE or APPROXIMATE — the grant in force when this fix arrived. */
    val precision: String,
    val provider: String,
    val timeMillis: Long,
    val altitudeMetres: Double? = null,
    val speedMetresPerSecond: Double? = null,
    val bearingDegrees: Double? = null,
) : ToolResultData

@Serializable
data class LocationWatchResult(
    val success: Boolean,
    val fixes: List<LocationFix>,
    val fixCount: Int,
    val windowSeconds: Int,
    val precision: String,
    /** True when the watch ran on the background grant the issue asks about. */
    val backgroundGranted: Boolean,
    /** The provider the watch used, or null when no watch ran. */
    val provider: String? = null,
    /** One sentence a model must not paraphrase into a stronger claim. */
    val note: String? = null,
    val error: String? = null,
) : ToolResultData

@Serializable
data class LocationHistoryResult(
    val success: Boolean,
    val fixes: List<LocationFix>,
    val count: Int,
    /** What the list actually covers, stated so nobody reads it as a long history. */
    val scope: String,
    val error: String? = null,
) : ToolResultData

@Serializable
data class PhotoLocationResult(
    val success: Boolean,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val altitudeMetres: Double? = null,
    val capturedAt: String? = null,
    /** True only when GPS tags were present and readable in the image itself. */
    val metadataPresent: Boolean = false,
    val source: String,
    val note: String? = null,
    val error: String? = null,
) : ToolResultData

/**
 * The fixes this process has seen, in arrival order.
 *
 * WHY IN MEMORY. The project has no location store, and inventing one to satisfy a
 * tool name would be exactly the decorative layer this batch exists to remove. The
 * ring is small, bounded, and honest about dying with the process: every
 * [LocationHistoryResult] says so in its scope line.
 */
object LocationTrace {
    private const val CAPACITY = 200
    private val lock = Any()
    private val fixes = ArrayDeque<LocationFix>()

    fun record(fix: LocationFix) {
        synchronized(lock) {
            fixes.addLast(fix)
            while (fixes.size > CAPACITY) fixes.removeFirst()
        }
    }

    fun recent(limit: Int): List<LocationFix> = synchronized(lock) {
        fixes.toList().takeLast(limit.coerceIn(1, CAPACITY))
    }

    fun size(): Int = synchronized(lock) { fixes.size }
}

object LocationDomainDefinitions : ToolSetRegistration {
    const val WATCH = "locationWatch"
    const val HISTORY = "locationHistory"
    const val PHOTO_LOCATION = "photoLocation"

    /**
     * Every tool declares the permission it actually needs.
     *
     * THIS WAS EMPTY AND THE REASONING WAS WRONG. The field was omitted because
     * the dispatcher's pre-flight used to return the bare string
     * "Permission denied: android.permission.X", so leaving it empty let the
     * executor's own guard produce the good sentence. That pre-flight has since
     * been fixed to build its refusal from the same catalogue, so it now returns
     * the identical sentence a guard does. Declaring the permission is therefore
     * strictly better: a tool that cannot run is refused before it starts any
     * work, the sentence is the same, and the declaration also documents what the
     * tool needs for anyone reading the schema.
     *
     * The one thing that must stay true: the permission named here must be the
     * real one the tool needs, or the refusal names the wrong thing.
     */
    override val definitions = listOf(
        ToolDefinition(
            name = WATCH,
            description = "Watch location for a bounded window and return every fix that arrived, " +
                "each labelled PRECISE or APPROXIMATE. Needs the background location grant " +
                "(\"Allow all the time\"), which Android grants only as a second step after precise " +
                "location, because it will not deliver updates to a backgrounded app without it. " +
                "Use for \"follow my drive\", \"tell me when I move\", or anything needing a trail " +
                "rather than one point. For a single fix use getCurrentLocation.",
            category = "location",
            parameters = listOf(
                ToolParameter(
                    "windowSeconds", ToolParameterType.Integer, false,
                    "How long to watch, 5 to 300 seconds. Default 30."
                ),
                ToolParameter(
                    "minIntervalMs", ToolParameterType.Integer, false,
                    "Minimum spacing between fixes in milliseconds, 0 to 60000. Default 1000. " +
                        "Larger values save battery; Android may still deliver faster."
                ),
                ToolParameter(
                    "maxFixes", ToolParameterType.Integer, false,
                    "Stop after this many fixes, 1 to 200. Default 50."
                ),
            ),
            // COARSE, not FINE. The dispatcher refuses when any declared permission is
            // missing, so declaring FINE would refuse a person who granted approximate
            // only, and background location itself is what this tool needs on top of
            // foreground location. Which precision is in force travels on the result.
            permissions = listOf(
                TeamThreePermissions.ACCESS_COARSE_LOCATION.permission,
                TeamThreePermissions.ACCESS_BACKGROUND_LOCATION.permission,
            ),
        ),
        ToolDefinition(
            name = HISTORY,
            description = "List the location fixes GURU has actually seen during this app run, newest " +
                "last. This is an in-memory trace, not a stored history: it is empty after a restart, " +
                "and every result says so. Use it for \"where were we a minute ago\" inside one " +
                "session, never to claim a long-term location record.",
            category = "location",
            parameters = listOf(
                ToolParameter(
                    "limit", ToolParameterType.Integer, false,
                    "How many of the most recent fixes to return, 1 to 200. Default 20."
                ),
            ),
            // locationHistory reads this process's own trace and touches no platform
            // API, so it needs no permission and must never claim one.
            permissions = emptyList(),
        ),
        ToolDefinition(
            name = PHOTO_LOCATION,
            description = "Read the GPS coordinates embedded in a photo's EXIF metadata — where the " +
                "picture was taken, not where the phone is now. Accepts a file path or a content:// " +
                "MediaStore URI. Requires the Photos and videos grant on every path, because Android " +
                "redacts a MediaStore image's location without it and a refusal naming that grant is " +
                "truer than a silent 'no location'. A refusal here means the location was withheld, " +
                "not that the photo never had one.",
            category = "location",
            parameters = listOf(
                ToolParameter(
                    "path", ToolParameterType.String, true,
                    "Absolute file path or content:// URI of the photo."
                ),
            ),
            permissions = listOf(TeamThreePermissions.ACCESS_MEDIA_LOCATION.permission),
        ),
    )

    override fun executorClass(): KClass<out ToolExecutor> = LocationDomainExecutor::class
    override fun extractorClass(): KClass<out ToolResultExtractor>? = null
}

/**
 * The executor behind locationWatch, locationHistory and photoLocation.
 *
 * Every refusal comes from PermissionGrants and is returned verbatim, first, before
 * any argument is read. Nothing here catches SecurityException into a success
 * payload: a grant that disappeared is reported as the grant, because that is what
 * it is.
 */
class LocationDomainExecutor(private val context: Context) : ToolExecutor {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val locationManager: LocationManager?
        get() = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    override suspend fun execute(toolName: String, args: Map<String, Any?>): ToolExecutionResult = when (toolName) {
        LocationDomainDefinitions.WATCH -> watch(args)
        LocationDomainDefinitions.HISTORY -> history(args)
        LocationDomainDefinitions.PHOTO_LOCATION -> photoLocation(args)
        else -> ToolExecutionResult.error("Unknown tool: $toolName")
    }

    private suspend fun watch(args: Map<String, Any?>): ToolExecutionResult {
        // Foreground precision first: background location is impossible without it,
        // and naming the background grant when no location grant is held at all
        // would send the person to a screen that cannot fix the problem. The
        // approximate-only grant is a true answer here, not a refusal, because
        // background location itself requires precise and the guard below names it.
        val precisionNow = PermissionGrants.locationPrecision(context)
        if (precisionNow == PermissionGrants.LocationPrecision.NONE) {
            return PermissionGrants.refusal(
                PermissionGrants.check(context, TeamThreePermissions.ACCESS_COARSE_LOCATION)
            )
        }
        PermissionGrants.guard(context, TeamThreePermissions.ACCESS_BACKGROUND_LOCATION)?.let { return it }

        val windowSeconds = ((args["windowSeconds"] as? Number)?.toInt() ?: 30).coerceIn(5, 300)
        val minIntervalMs = ((args["minIntervalMs"] as? Number)?.toLong() ?: 1000L).coerceIn(0L, 60_000L)
        val maxFixes = ((args["maxFixes"] as? Number)?.toInt() ?: 50).coerceIn(1, 200)

        if (!PermissionGrants.isLocationEnabled(context)) {
            val r = LocationWatchResult(
                success = false,
                fixes = emptyList(),
                fixCount = 0,
                windowSeconds = windowSeconds,
                precision = precisionNow.name,
                backgroundGranted = true,
                error = "Location is switched off in Settings, so GURU holds the permission but no " +
                    "provider can answer until Location is turned on.",
            )
            return emitPayload(json, json.encodeToString(LocationWatchResult.serializer(), r), r, r.success, r.error)
        }

        val manager = locationManager ?: run {
            val r = LocationWatchResult(
                success = false, fixes = emptyList(), fixCount = 0, windowSeconds = windowSeconds,
                precision = precisionNow.name, backgroundGranted = true,
                error = "This device has no LocationManager service, so there is nothing to watch.",
            )
            return emitPayload(json, json.encodeToString(LocationWatchResult.serializer(), r), r, r.success, r.error)
        }

        val provider = preferredProvider(manager, precisionNow) ?: run {
            val r = LocationWatchResult(
                success = false, fixes = emptyList(), fixCount = 0, windowSeconds = windowSeconds,
                precision = precisionNow.name, backgroundGranted = true,
                error = "No location provider is enabled on this device, so no watch can start; " +
                    "this is the device's state, not a missing permission.",
            )
            return emitPayload(json, json.encodeToString(LocationWatchResult.serializer(), r), r, r.success, r.error)
        }

        val collected = mutableListOf<LocationFix>()
        val finished = CompletableDeferred<Unit>()

        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                collected.add(location.toFix(precisionNow))
                if (collected.size >= maxFixes) finished.complete(Unit)
            }

            // A provider toggling during a watch is not a failure of the watch: the
            // fixes already collected are real. Nothing is fabricated either way.
            override fun onProviderDisabled(provider: String) {}
            override fun onProviderEnabled(provider: String) {}
        }

        val watchError: String? = try {
            manager.requestLocationUpdates(provider, minIntervalMs, 0f, listener, Looper.getMainLooper())
            withTimeoutOrNull(windowSeconds * 1000L) { finished.await() }
            null
        } catch (e: SecurityException) {
            // Re-check rather than assume: the sentence must describe the state now.
            return PermissionGrants.refusal(
                PermissionGrants.check(context, TeamThreePermissions.ACCESS_BACKGROUND_LOCATION)
            )
        } catch (e: Exception) {
            "The watch failed: ${e.message}"
        } finally {
            runCatching { manager.removeUpdates(listener) }
        }

        collected.forEach(LocationTrace::record)

        if (collected.isNotEmpty()) {
            // A place change is a real event that automation and thought cycles may
            // care about, so the watch reports it through the one event bus. The bus
            // takes HookEventType and a flat map; CUSTOM is the entry point other
            // hooks and cycles already subscribe to for events with no dedicated
            // type, and the "event" field names this one.
            val last = collected.last()
            HookEventBus.fire(
                HookEventType.CUSTOM,
                mapOf<String, Any?>(
                    "event" to "locationWatch",
                    "latitude" to last.latitude,
                    "longitude" to last.longitude,
                    "precision" to last.precision,
                    "accuracyMetres" to last.accuracyMetres,
                    "fixCount" to collected.size,
                    "provider" to last.provider,
                ),
            )
        }

        val r = LocationWatchResult(
            success = collected.isNotEmpty(),
            fixes = collected,
            fixCount = collected.size,
            windowSeconds = windowSeconds,
            precision = precisionNow.name,
            backgroundGranted = true,
            provider = provider,
            note = if (collected.isEmpty()) {
                "No fix arrived in $windowSeconds seconds. A grant and a switched-on Location do not " +
                    "guarantee a satellite or network fix, especially indoors; this is no data, not no " +
                    "permission."
            } else {
                "Watched through $provider for up to $windowSeconds seconds on the ${precisionNow.name} " +
                    "grant. Android may deliver faster or slower than the requested interval."
            },
            error = watchError,
        )
        return emitPayload(json, json.encodeToString(LocationWatchResult.serializer(), r), r, r.success, r.error)
    }

    private fun history(args: Map<String, Any?>): ToolExecutionResult {
        val limit = ((args["limit"] as? Number)?.toInt() ?: 20).coerceIn(1, 200)
        val fixes = LocationTrace.recent(limit)
        val r = LocationHistoryResult(
            success = fixes.isNotEmpty(),
            fixes = fixes,
            count = fixes.size,
            scope = "In-memory trace of this app run only (${LocationTrace.size()} fixes kept, " +
                "newest last). It does not survive a restart and is not a stored location history.",
            error = if (fixes.isEmpty()) {
                "GURU has not recorded a location fix since this process started, so the trace is empty."
            } else null,
        )
        return emitPayload(json, json.encodeToString(LocationHistoryResult.serializer(), r), r, r.success, r.error)
    }

    /**
     * The GPS a photo carries.
     *
     * The grant is checked first on every path, which is the tool's declared
     * contract: Android redacts a MediaStore image's location without it, so the
     * honest answer to "where was this taken" is unreadable rather than absent. A
     * refusal therefore means the location was withheld, never that the photo had
     * none, and only a read made with the grant held may say "no GPS tags".
     */
    private fun photoLocation(args: Map<String, Any?>): ToolExecutionResult {
        PermissionGrants.guard(context, TeamThreePermissions.ACCESS_MEDIA_LOCATION)?.let { return it }

        val path = args["path"] as? String
        if (path.isNullOrBlank()) {
            val r = PhotoLocationResult(
                success = false,
                source = "none",
                error = "This call needs the 'path' of a photo: an absolute file path or a " +
                    "content:// MediaStore URI.",
            )
            return emitPayload(json, json.encodeToString(PhotoLocationResult.serializer(), r), r, r.success, r.error)
        }

        val isContentUri = path.startsWith("content://")
        val exif: ExifInterface? = if (isContentUri) {
            openExifFromUri(path)
        } else {
            val file = java.io.File(path)
            if (!file.exists()) {
                return photoFailure(path, "No file exists at $path.")
            }
            runCatching { ExifInterface(file.absolutePath) }.getOrNull()
        }

        if (exif == null) {
            return photoFailure(
                path,
                "This photo's metadata could not be read. Either the content resolver refused the " +
                    "URI, or the file is not an image with EXIF headers.",
            )
        }

        val latLong = FloatArray(2)
        val hasGps = runCatching { exif.getLatLong(latLong) }.getOrDefault(false)
        val capturedAt = runCatching { exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL) }.getOrNull()

        if (!hasGps) {
            val r = PhotoLocationResult(
                success = false,
                capturedAt = capturedAt,
                metadataPresent = false,
                source = if (isContentUri) "content-uri" else "file",
                note = if (isContentUri) {
                    "The Photos and videos grant is held, so Android did not redact this image; it " +
                        "carries no GPS tags, which means whatever took it did not record a location " +
                        "or stripped it before the file was saved."
                } else {
                    "Plain file access is never redacted, so the absence here is real: this image has " +
                        "no GPS tags."
                },
                error = "No GPS coordinates in this photo's EXIF metadata.",
            )
            return emitPayload(json, json.encodeToString(PhotoLocationResult.serializer(), r), r, r.success, r.error)
        }

        // getLatLong validates the reference tags; a value outside the legal range
        // means corrupt metadata, not a place near the poles.
        if (latLong[0] !in -90f..90f || latLong[1] !in -180f..180f) {
            return photoFailure(path, "The EXIF coordinates are outside the legal range, so the metadata is corrupt.")
        }

        val altitude = runCatching { exif.getAltitude(0.0) }.getOrNull()
        val r = PhotoLocationResult(
            success = true,
            latitude = latLong[0].toDouble(),
            longitude = latLong[1].toDouble(),
            altitudeMetres = altitude,
            capturedAt = capturedAt,
            metadataPresent = true,
            source = if (isContentUri) "content-uri" else "file",
            note = "These are the coordinates recorded when the photo was taken, not the phone's " +
                "current position.",
        )
        return emitPayload(json, json.encodeToString(PhotoLocationResult.serializer(), r), r, r.success, r.error)
    }

    /**
     * Open a MediaStore image's metadata with the original bytes requested.
     *
     * `setRequireOriginal` is what stops Android handing back a redacted copy and it
     * exists from API 29. A descriptor is tried first because it is the path the
     * platform documents for this call; the plain stream is the fallback so a cloud
     * provider that cannot share originals still yields the date fields and an
     * honest "no GPS" answer instead of a read failure.
     */
    private fun openExifFromUri(path: String): ExifInterface? {
        val uri = Uri.parse(path)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val original = runCatching { MediaStore.setRequireOriginal(uri) }.getOrNull()
            if (original != null) {
                val descriptor = runCatching { context.contentResolver.openFileDescriptor(original, "r") }.getOrNull()
                if (descriptor != null) {
                    // The tags are read during construction, so closing the descriptor
                    // afterwards loses nothing.
                    val opened = runCatching { ExifInterface(descriptor.fileDescriptor) }.getOrNull()
                    runCatching { descriptor.close() }
                    if (opened != null) return opened
                }
            }
        }

        return runCatching {
            context.contentResolver.openInputStream(uri)?.use { ExifInterface(it) }
        }.getOrNull()
    }

    private fun photoFailure(path: String, message: String): ToolExecutionResult {
        val r = PhotoLocationResult(
            success = false,
            source = if (path.startsWith("content://")) "content-uri" else "file",
            error = message,
        )
        return emitPayload(json, json.encodeToString(PhotoLocationResult.serializer(), r), r, r.success, r.error)
    }

    /**
     * The provider that can actually answer under the grant held.
     *
     * An approximate-only grant may not use GPS: the platform throws
     * SecurityException for it, which is how the old code silently produced a stale
     * last-known fix. Choosing the provider from the grant means the answer matches
     * the permission instead of failing after the fact.
     */
    private fun preferredProvider(
        manager: LocationManager,
        precision: PermissionGrants.LocationPrecision,
    ): String? {
        val enabled = runCatching { manager.getProviders(true) }.getOrNull().orEmpty()
        val preferred = if (precision == PermissionGrants.LocationPrecision.PRECISE) {
            listOf(LocationManager.GPS_PROVIDER, LocationManager.FUSED_PROVIDER, LocationManager.NETWORK_PROVIDER)
        } else {
            listOf(LocationManager.NETWORK_PROVIDER, LocationManager.FUSED_PROVIDER)
        }
        return preferred.firstOrNull { it in enabled }
    }

    private fun Location.toFix(precision: PermissionGrants.LocationPrecision): LocationFix = LocationFix(
        latitude = latitude,
        longitude = longitude,
        accuracyMetres = accuracy.toDouble(),
        precision = precision.name,
        provider = provider ?: "unknown",
        timeMillis = time,
        altitudeMetres = if (hasAltitude()) altitude else null,
        speedMetresPerSecond = if (hasSpeed()) speed.toDouble() else null,
        bearingDegrees = if (hasBearing()) bearing.toDouble() else null,
    )
}

/**
 * The location domain's wiring: its definitions and the executor that runs them, in
 * one file so the two cannot drift apart.
 */
object LocationDomainModule : DomainToolModule {
    override val id: String = "location"

    override val registrations: List<ToolSetRegistration> = listOf(LocationDomainDefinitions)

    override fun bindings(): Module = module {
        factory { LocationDomainExecutor(get()) }
    }
}
