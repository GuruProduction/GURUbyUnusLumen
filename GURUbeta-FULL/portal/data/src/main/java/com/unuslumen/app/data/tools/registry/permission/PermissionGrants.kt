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
     * A string the catalogue does not know is a tool defect, not a grant problem,
     * and is reported as one so nobody goes looking for a settings toggle that
     * would not help.
     */
    fun check(context: Context, permission: String): GrantCheck {
        val spec = TeamThreePermissions.forPermission(permission)
            ?: return GrantCheck.unknown(permission)
        return check(context, spec)
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
    fun refusal(check: GrantCheck): ToolExecutionResult {
        val body = RefusalBody(
            permission = check.spec.permission,
            state = check.state.name,
            tier = check.spec.tier.name,
            grantRoute = check.spec.grantRoute,
            sentence = check.sentence(),
        )
        return ToolExecutionResult(
            rawJson = json.encodeToString(RefusalBody.serializer(), body),
            resultData = null,
            resultObject = null,
            success = false,
            error = check.sentence(),
        )
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
}
