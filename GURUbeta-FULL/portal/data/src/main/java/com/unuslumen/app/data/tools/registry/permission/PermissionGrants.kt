// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.tools.registry.permission

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.unuslumen.app.data.tools.registry.ToolExecutionResult
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * PermissionGrants — the one grant helper every device-senses tool uses.
 *
 * WHY THERE IS EXACTLY ONE. Three agents building forty-four tools will each be
 * tempted to write their own "is it granted?" check, and they will disagree in
 * small ways that matter: one will read a denial as an empty list, one will
 * forget that an install-tier permission is never a user problem, one will
 * suggest a settings screen that does not exist. Written once, every tool gives
 * the same answer to the same question, and a correction lands everywhere at once.
 *
 * WHAT IT DOES NOT DO. It never requests anything. Requesting is the
 * PermissionGateway broadcast that MainActivity answers, and a tool that fires a
 * dialog in the middle of a conversation is a tool that interrupts. The job here
 * is to find out the truth and explain it; the grant itself is the person's
 * decision, taken through the UI or the settings screen the sentence names.
 *
 * WHEN #172 LANDS. [check] becomes the only call site that changes: it delegates
 * to PermissionRegistry and this object keeps its shape, so no tool is touched.
 */
object PermissionGrants {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /**
     * The current state of one permission.
     *
     * The platform check is the same call for every tier, because the platform is
     * what decides. What differs is how an absence is *reported*, and that is
     * [GrantCheck.of]'s job: a privileged permission that is missing is not a
     * person's oversight, and an install-tier permission that is missing is a
     * build fault. Reporting either as "not granted yet, go to Settings" is the
     * defect this whole batch exists to remove.
     */
    fun check(context: Context, spec: PermissionSpec): GrantCheck {
        val held = ContextCompat.checkSelfPermission(context, spec.permission) ==
            PackageManager.PERMISSION_GRANTED
        return GrantCheck.of(spec, held)
    }

    /**
     * Check a permission by its platform string against the catalogue.
     *
     * A string the catalogue does not know is resolved against the platform: a real
     * permission becomes an ordinary denial with a followable route, and only a
     * name Android has never heard of is reported as a tool defect.
     *
     * The platform fallback matters because ToolDispatcher consults this for every
     * tool in the app, and the rest of the app declares permissions outside this
     * batch's forty-three. Calling those a tool defect would be a plain lie about
     * a permission the person really can grant.
     */
    fun check(context: Context, permission: String): GrantCheck {
        val spec = TeamThreePermissions.forPermission(permission)
            ?: return resolveOutOfCatalogue(context, permission, requireDenied = false)
        return check(context, spec)
    }

    /**
     * Resolve a permission this batch's catalogue does not list.
     *
     * Only forty-three permissions have a written entry, but the rest of the app
     * legitimately declares real ones such as POST_NOTIFICATIONS. Calling those a
     * tool defect would tell a person the app is broken when a single grant would
     * fix it, so the platform is asked whether the name genuinely exists.
     *
     * A name Android has never heard of is the only case reported as a fault,
     * because no grant could ever satisfy it.
     *
     * [requireDenied] is set by the dispatcher's pre-flight, which reaches this
     * only after its own check already failed. In that case the state is forced to
     * denied so the sentence never says "granted" about something that is not.
     */
    private fun resolveOutOfCatalogue(
        context: Context,
        permission: String,
        requireDenied: Boolean,
    ): GrantCheck {
        val declared = runCatching {
            context.packageManager.getPermissionInfo(permission, 0)
            true
        }.getOrDefault(false)

        if (!declared) return GrantCheck.unknown(permission)

        val held = !requireDenied && ContextCompat.checkSelfPermission(context, permission) ==
            PackageManager.PERMISSION_GRANTED

        // The spec lives on GrantCheck.uncatalogued so the wording of an
        // out-of-catalogue refusal is written once, next to every other refusal
        // wording, rather than rebuilt here in a second place that would drift.
        return GrantCheck.uncatalogued(permission, appLabel(context), granted = held)
    }

    /** Convenience for a list: the first refusal, or null when every one is allowed. */
    fun firstRefusal(context: Context, specs: List<PermissionSpec>): GrantCheck? =
        specs.asSequence().map { check(context, it) }.firstOrNull { it.denied }

    // ------------------------------------------------------------------
    // Hardware presence
    // ------------------------------------------------------------------

    /**
     * Whether the device carries a system feature at all.
     *
     * This is the difference between "you have not granted this" and "this phone
     * cannot do it". A tool that answers a missing sensor with a zero reading
     * teaches the model that the device is still, which is a fabricated fact.
     */
    fun hasFeature(context: Context, feature: String): Boolean =
        context.packageManager.hasSystemFeature(feature)

    /** UWB is the clearest case: absent on most phones and on the test emulator. */
    fun hasUwb(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && hasFeature(context, PackageManager.FEATURE_UWB)

    fun hasBluetooth(context: Context): Boolean = hasFeature(context, PackageManager.FEATURE_BLUETOOTH)

    fun hasCamera(context: Context): Boolean = hasFeature(context, PackageManager.FEATURE_CAMERA_ANY)

    fun hasMicrophone(context: Context): Boolean = hasFeature(context, PackageManager.FEATURE_MICROPHONE)

    fun hasGps(context: Context): Boolean = hasFeature(context, PackageManager.FEATURE_LOCATION_GPS)

    /**
     * Whether the device reports a body sensor of the given type.
     *
     * BODY_SENSORS is a runtime permission, but a phone with no heart-rate sensor
     * cannot answer a heart-rate question however it is granted. Both facts have
     * to be reported, in the right order: hardware first, because no grant will
     * ever fix it.
     */
    fun hasBodySensor(context: Context, sensorType: Int): Boolean {
        val manager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return false
        return manager.getDefaultSensor(sensorType) != null
    }

    fun hasHeartRateSensor(context: Context): Boolean =
        hasBodySensor(context, Sensor.TYPE_HEART_RATE)

    /**
     * Refuse a capability because the hardware is not there.
     *
     * The spec is built here rather than in the catalogue because a hardware
     * absence is per-device and per-sensor, not a fixed property of a permission.
     */
    fun absentHardware(label: String, why: String): GrantCheck =
        GrantCheck.absent(permission = "hardware:$label", label = label, why = why)

    // ------------------------------------------------------------------
    // Location, where "granted" is not one boolean
    // ------------------------------------------------------------------

    /**
     * What the fix will actually be, given the grants held.
     *
     * NEVER PRESENT AN APPROXIMATE FIX AS PRECISE. On Android 12+ a person can
     * grant "approximate" and Android reports fine as denied and coarse as
     * granted. A tool that says "location" without qualifying it lets the model
     * state a street-level fact it does not have, so the precision travels with
     * the result rather than being assumed.
     */
    enum class LocationPrecision { PRECISE, APPROXIMATE, NONE }

    fun locationPrecision(context: Context): LocationPrecision = when {
        ContextCompat.checkSelfPermission(context, TeamThreePermissions.ACCESS_FINE_LOCATION.permission) ==
            PackageManager.PERMISSION_GRANTED -> LocationPrecision.PRECISE
        ContextCompat.checkSelfPermission(context, TeamThreePermissions.ACCESS_COARSE_LOCATION.permission) ==
            PackageManager.PERMISSION_GRANTED -> LocationPrecision.APPROXIMATE
        else -> LocationPrecision.NONE
    }

    /**
     * Whether any location provider is switched on at all.
     *
     * A grant with location switched off in Settings is another "no access" case
     * that is easy to misreport as "no data".
     */
    fun isLocationEnabled(context: Context): Boolean {
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            manager.isLocationEnabled
        } else {
            runCatching {
                manager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                    manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
            }.getOrDefault(false)
        }
    }

    // ------------------------------------------------------------------
    // The refusal every tool returns
    // ------------------------------------------------------------------

    /**
     * The JSON body of a refusal.
     *
     * Carries the state as a stable token so a caller can branch on it without
     * parsing the sentence, and the sentence itself so the model has the exact
     * words to say. `denied = true` is the machine-readable form of "this is not
     * an empty result, it is a refusal".
     */
    @Serializable
    data class RefusalBody(
        val denied: Boolean = true,
        val permission: String,
        val state: String,
        val tier: String,
        /** The exact route to grant it, or null when no dialogs can grant it. */
        val grantRoute: String? = null,
        val sentence: String,
    )

    /**
     * Build the failed tool result for a refusal.
     *
     * `success = false` deliberately, not `success = true` with an error string:
     * this call did not do the thing, and a success flag would let it read as a
     * completed action with no data. The dispatcher records the failure with the
     * sentence, which is exactly the trail a later attempt needs.
     */
    fun refusal(check: GrantCheck): ToolExecutionResult = ToolExecutionResult(
        rawJson = refusalBodyJson(check),
        resultData = null,
        resultObject = null,
        success = false,
        error = check.sentence(),
    )

    /**
     * The refusal body as JSON, shared by both refusal paths.
     *
     * A tool's own guard and the dispatcher's pre-flight both produce this, so a
     * caller sees one shape whichever refused it. Two shapes for one condition is
     * how a branch written against the tool path silently misses the dispatcher
     * path and reads a refusal as a malformed success.
     */
    fun refusalBodyJson(check: GrantCheck): String {
        val body = RefusalBody(
            permission = check.spec.permission,
            state = check.state.name,
            tier = check.spec.tier.name,
            grantRoute = check.spec.grantRoute,
            sentence = check.sentence(),
        )
        return json.encodeToString(RefusalBody.serializer(), body)
    }

    /**
     * Check and refuse in one call, for the common case.
     *
     * Returns null when the capability may run, so a tool reads as a guard clause
     * rather than a four-line ceremony. The executor then either produces real
     * data or, when the platform throws underneath, its own honest failure.
     */
    fun guard(context: Context, spec: PermissionSpec): ToolExecutionResult? {
        val check = check(context, spec)
        return if (check.denied) refusal(check) else null
    }

    /**
     * The same guard for hardware rather than a permission.
     */
    fun guardHardware(present: Boolean, label: String, why: String): ToolExecutionResult? =
        if (present) null else refusal(GrantCheck.absent(permission = "hardware:$label", label = label, why = why))

    // ------------------------------------------------------------------
    // The dispatcher's pre-flight refusal
    // ------------------------------------------------------------------

    /**
     * The sentence for a permission the dispatcher refused before the tool ran.
     *
     * WHY THIS EXISTS. ToolDispatcher checks a tool's declared permissions before
     * calling the executor, which is correct: a tool must not run at all when it
     * cannot. But its refusal was the bare string
     * "Permission denied: android.permission.READ_SMS", which names no route and
     * does not say whether the person can fix it. Because it runs first, it
     * short-circuits every tool's own guard and that raw string is what the model
     * actually reads.
     *
     * Routing the pre-flight through the same catalogue means one refusal shape
     * exists in the app rather than two, and a permission outside the catalogue
     * still produces something honest instead of a raw platform string. Every tool
     * set benefits, not only this batch's.
     *
     * ONLY 43 PERMISSIONS HAVE A WRITTEN ENTRY, and other tool sets legitimately
     * declare real ones the catalogue does not know, such as POST_NOTIFICATIONS.
     * Calling those a tool defect would tell a person the app is broken when one
     * grant would fix it, so the platform is asked whether the name genuinely
     * exists and only a string Android has never heard of is reported as a fault.
     */
    fun refusalForPermission(context: Context, permission: String): GrantCheck {
        TeamThreePermissions.forPermission(permission)?.let { spec ->
            // The dispatcher only reaches here when its check failed, so the state
            // is derived the same way a tool's own guard derives it. A privileged
            // or install-tier permission that lands here is reported as what it is
            // rather than as a dialog nobody can find.
            return GrantCheck.of(spec, granted = false)
        }
        return resolveOutOfCatalogue(context, permission, requireDenied = true)
    }

    /**
     * The app's own name, for a settings path a person can follow.
     *
     * Read from the application info rather than hardcoded, because the debug build
     * installs as a separate app and its label is what appears on the settings
     * screen the person is being told to open.
     */
    private fun appLabel(context: Context): String = runCatching {
        context.packageManager.getApplicationLabel(context.applicationInfo).toString()
    }.getOrDefault("GURU")
}
