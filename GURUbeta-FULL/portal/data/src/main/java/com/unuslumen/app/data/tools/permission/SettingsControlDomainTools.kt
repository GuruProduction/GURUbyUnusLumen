// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.tools.permission

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.biometrics.BiometricManager
import android.media.AudioManager
import android.os.Build
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.koin.core.module.Module
import org.koin.dsl.module
import kotlin.reflect.KClass
import java.util.TimeZone

/**
 * The settings and device-control domain: system settings, secure settings, the clock, the
 * locale and density, floating overlays, the status bar, the wallpaper, biometric
 * availability, vibration and a wake lock.
 *
 * THE RULE EVERY WRITE HERE FOLLOWS. A write reports what the platform holds afterwards,
 * never the value it sent. That distinction is the whole point of the domain: a tool that
 * says "brightness set to 128" because it passed 128 to putInt is reporting an intention,
 * and the person then looks at a screen that did not change. So every setter writes, reads
 * back, and reports the difference.
 *
 * WHAT IS DELIBERATELY HONEST RATHER THAN PRETENDED. Two capabilities here cannot do what
 * their name suggests on this platform, and both say so in the result rather than failing
 * silently. The status bar can be expanded through a reflected system service, which is the
 * only route and involves no root. And biometricAuth can report availability but cannot
 * raise the prompt, because BiometricPrompt needs a foreground Activity and a tool has only
 * a Context; the prompt belongs to the UI layer and the description says exactly that.
 */

@Serializable
data class SettingsWriteResult(
    val success: Boolean,
    val setting: String,
    val requested: String? = null,
    /** What the platform holds after the write, read back rather than assumed. */
    val observedAfter: String? = null,
    val beforeValue: String? = null,
    val changed: Boolean = false,
    val note: String? = null,
    val error: String? = null,
) : ToolResultData

@Serializable
data class TimeSetResult(
    val success: Boolean,
    val what: String,
    val clockMillis: Long? = null,
    val timeZoneId: String? = null,
    val clockIso: String? = null,
    val route: String? = null,
    val note: String? = null,
    val error: String? = null,
) : ToolResultData

@Serializable
data class OverlayResult(
    val success: Boolean,
    val overlayId: String? = null,
    val activeOverlays: List<String> = emptyList(),
    val note: String? = null,
    val error: String? = null,
) : ToolResultData

@Serializable
data class StatusBarResult(
    val success: Boolean,
    val action: String,
    val route: String? = null,
    val note: String? = null,
    val error: String? = null,
) : ToolResultData

@Serializable
data class WallpaperResult(
    val success: Boolean,
    val source: String,
    val staticWallpaperSet: Boolean? = null,
    val note: String? = null,
    val error: String? = null,
) : ToolResultData

@Serializable
data class BiometricResult(
    val success: Boolean,
    val available: Boolean = false,
    /** The exact BiometricManager constant name, so nothing is paraphrased into a guess. */
    val availability: String = "UNKNOWN",
    val strongAvailable: Boolean? = null,
    val note: String? = null,
    val error: String? = null,
) : ToolResultData

@Serializable
data class VibrateResult(
    val success: Boolean,
    val requestedDurationMs: Int? = null,
    val note: String? = null,
    val error: String? = null,
) : ToolResultData

@Serializable
data class WakeLockResult(
    val success: Boolean,
    val action: String,
    val held: Boolean = false,
    /** The wake lock's own isHeld property, which IS a real platform readback. */
    val platformReportsHeld: Boolean? = null,
    val note: String? = null,
    val error: String? = null,
) : ToolResultData

/**
 * The overlays this process has created, keyed by id.
 *
 * WHY IN MEMORY. A WindowManager view is owned by the WindowManager that added it, so
 * there is nothing to persist and nothing that could outlive the process. An overlay from
 * a previous run cannot be removed by id, and every result says so rather than leaving a
 * caller to wonder why removal did nothing.
 */
object OverlayRegistry {
    private val lock = Any()
    private val views = mutableMapOf<String, Pair<View, WindowManager.LayoutParams>>()

    fun put(id: String, view: View, params: WindowManager.LayoutParams) {
        synchronized(lock) { views[id] = view to params }
    }

    fun remove(id: String): Pair<View, WindowManager.LayoutParams>? =
        synchronized(lock) { views.remove(id) }

    fun takeAll(): List<Pair<String, Pair<View, WindowManager.LayoutParams>>> = synchronized(lock) {
        val snapshot = views.toList()
        views.clear()
        snapshot
    }

    fun ids(): List<String> = synchronized(lock) { views.keys.toList() }
}

object SettingsControlDomainDefinitions : ToolSetRegistration {
    const val SETTINGS_WRITE = "settingsWrite"
    const val SECURE_SETTINGS_WRITE = "secureSettingsWrite"
    const val TIME_SET = "timeSet"
    const val CONFIGURATION_CHANGE = "configurationChange"
    const val OVERLAY_CREATE = "overlayCreate"
    const val OVERLAY_REMOVE = "overlayRemove"
    const val EXPAND_STATUS_BAR = "expandStatusBar"
    const val SET_WALLPAPER = "setWallpaper"
    const val BIOMETRIC_AUTH = "biometricAuth"
    const val VIBRATE = "vibrate"
    const val KEEP_AWAKE = "keepAwake"

    override val definitions = listOf(
        ToolDefinition(
            name = SETTINGS_WRITE,
            description = "Change a system setting such as screen brightness, screen timeout, " +
                "ringtone or font scale, then read it back and report the value the system " +
                "actually holds. Needs the Modify system settings grant, which is a Settings " +
                "toggle rather than a dialog. If the value does not change, the result says so " +
                "rather than claiming the write landed.",
            category = "system_settings",
            parameters = listOf(
                ToolParameter(
                    "setting", ToolParameterType.Enum, true, "Which setting to change.",
                    listOf("SCREEN_BRIGHTNESS", "SCREEN_OFF_TIMEOUT", "FONT_SCALE", "RINGTONE"),
                ),
                ToolParameter("value", ToolParameterType.String, true, "The new value."),
            ),
            permissions = listOf(TeamThreePermissions.WRITE_SETTINGS.permission),
        ),
        ToolDefinition(
            name = SECURE_SETTINGS_WRITE,
            description = "Write a protected setting in the system, secure or global namespace and " +
                "report the value read back afterwards. This needs a privilege an ordinary app does " +
                "not hold, so on a normal install it refuses and names that rather than pretending. " +
                "It deliberately does not restrict which keys may be written: the point of holding " +
                "the privilege is that any key is writable, and the result states what it wrote.",
            category = "system_settings",
            parameters = listOf(
                ToolParameter(
                    "namespace", ToolParameterType.Enum, true, "Which settings table to write.",
                    listOf("SYSTEM", "SECURE", "GLOBAL"),
                ),
                ToolParameter("key", ToolParameterType.String, true, "The setting key."),
                ToolParameter(
                    "valueType", ToolParameterType.Enum, true, "How to interpret the value.",
                    listOf("STRING", "INT", "FLOAT"),
                ),
                ToolParameter("value", ToolParameterType.String, true, "The new value."),
            ),
            permissions = listOf(TeamThreePermissions.WRITE_SECURE_SETTINGS.permission),
        ),
        ToolDefinition(
            name = TIME_SET,
            description = "Set the phone's clock or its time zone, or ask for automatic time, then " +
                "read the resulting clock and zone back. Both the clock and the zone are privileged, " +
                "so on a normal install this refuses and names that. When the privilege is held, a " +
                "write that was accepted but not applied is reported as not applied, because the " +
                "readback is what counts.",
            category = "system_settings",
            parameters = listOf(
                ToolParameter(
                    "what", ToolParameterType.Enum, true, "Which clock to change.",
                    listOf("CLOCK", "TIME_ZONE"),
                ),
                ToolParameter(
                    "epochMillis", ToolParameterType.Long, false,
                    "The instant to set, for CLOCK. Omit when autoDetect is true."
                ),
                ToolParameter(
                    "timeZoneId", ToolParameterType.String, false,
                    "An IANA zone id such as Europe/London, for TIME_ZONE."
                ),
                ToolParameter(
                    "autoDetect", ToolParameterType.Boolean, false,
                    "Ask for automatic time from the network instead of a literal value. Default false."
                ),
            ),
            permissions = listOf(
                TeamThreePermissions.SET_TIME.permission,
                TeamThreePermissions.SET_TIME_ZONE.permission,
            ),
        ),
        ToolDefinition(
            name = CONFIGURATION_CHANGE,
            description = "Change the display density, or ask for this app's locale. Density is " +
                "written through a secure setting and needs that privilege; a system-wide locale " +
                "change is not reachable from an ordinary app and the tool reports that instead of " +
                "faking it. It guards only the permission matching the action you asked for, and the " +
                "result names which one it needed.",
            category = "system_settings",
            parameters = listOf(
                ToolParameter(
                    "action", ToolParameterType.Enum, true, "Which configuration to change.",
                    listOf("SET_DENSITY", "SET_LOCALE"),
                ),
                ToolParameter(
                    "densityDpi", ToolParameterType.Integer, false,
                    "Display density in dpi, 120 to 640, for SET_DENSITY."
                ),
                ToolParameter(
                    "locale", ToolParameterType.String, false,
                    "A BCP-47 language tag such as en-GB, for SET_LOCALE."
                ),
            ),
            permissions = listOf(
                TeamThreePermissions.WRITE_SECURE_SETTINGS.permission,
                TeamThreePermissions.CHANGE_CONFIGURATION.permission,
            ),
        ),
        ToolDefinition(
            name = OVERLAY_CREATE,
            description = "Draw a small floating window over whatever is on screen, and return an id " +
                "so it can be removed. Needs the Display over other apps grant, which is a Settings " +
                "toggle. Runs on the main thread because WindowManager requires it. State plainly: " +
                "the overlay stays until removed or until this process dies, and no id from a " +
                "previous run can be removed because the registry does not survive a restart.",
            category = "overlays",
            parameters = listOf(
                ToolParameter("text", ToolParameterType.String, true, "The text to display."),
                ToolParameter("x", ToolParameterType.Integer, false, "Left offset in pixels. Default 0."),
                ToolParameter("y", ToolParameterType.Integer, false, "Top offset in pixels. Default 200."),
                ToolParameter("width", ToolParameterType.Integer, false, "Width in pixels. Default 900."),
                ToolParameter("height", ToolParameterType.Integer, false, "Height in pixels. Default 300."),
            ),
            permissions = listOf(TeamThreePermissions.SYSTEM_ALERT_WINDOW.permission),
        ),
        ToolDefinition(
            name = OVERLAY_REMOVE,
            description = "Remove a floating window this process created, by id, or remove all of " +
                "them. Needs the Display over other apps grant. Removal runs on the main thread. An " +
                "id from a previous run cannot be removed, because the registry of overlays is in " +
                "memory and dies with the process, and the result says so rather than reporting a " +
                "removal that did nothing.",
            category = "overlays",
            parameters = listOf(
                ToolParameter("overlayId", ToolParameterType.String, false, "The id to remove."),
                ToolParameter(
                    "all", ToolParameterType.Boolean, false,
                    "Remove every overlay this process created. Default false."
                ),
            ),
            permissions = listOf(TeamThreePermissions.SYSTEM_ALERT_WINDOW.permission),
        ),
        ToolDefinition(
            name = EXPAND_STATUS_BAR,
            description = "Open or close the notification shade. Uses the system status bar service " +
                "through reflection, which is the only route available to an app and involves no " +
                "root or debug build. If the reflection is unavailable on a platform version, the " +
                "result says the platform does not expose it rather than blaming a permission, " +
                "because there is no permission that would fix it.",
            category = "device_control",
            parameters = listOf(
                ToolParameter(
                    "action", ToolParameterType.Enum, true, "Open or close the shade.",
                    listOf("EXPAND", "COLLAPSE"),
                ),
            ),
            permissions = listOf(TeamThreePermissions.EXPAND_STATUS_BAR.permission),
        ),
        ToolDefinition(
            name = SET_WALLPAPER,
            description = "Set the phone's wallpaper from an image file or from a solid colour, then " +
                "report what the wallpaper manager holds afterwards. Large images are downsampled " +
                "before decoding, because decoding a modern phone photo at full resolution is a " +
                "known out-of-memory fault in this project. It reports whether a static wallpaper is " +
                "now set rather than claiming the picture you passed is on screen.",
            category = "device_control",
            parameters = listOf(
                ToolParameter(
                    "source", ToolParameterType.Enum, true, "Where the wallpaper comes from.",
                    listOf("IMAGE", "COLOUR"),
                ),
                ToolParameter(
                    "path", ToolParameterType.String, false,
                    "Absolute path to an image, for IMAGE."
                ),
                ToolParameter("colour", ToolParameterType.String, false, "A #RRGGBB colour, for COLOUR."),
                ToolParameter(
                    "which", ToolParameterType.Enum, false,
                    "HOME, LOCK or BOTH. Default HOME.", listOf("HOME", "LOCK", "BOTH"),
                ),
            ),
            permissions = listOf(TeamThreePermissions.SET_WALLPAPER.permission),
        ),
        ToolDefinition(
            name = BIOMETRIC_AUTH,
            description = "Report whether this phone can do biometric authentication right now, and " +
                "why not when it cannot: no hardware, no fingerprint or face enrolled, hardware " +
                "unavailable, or a security update needed. It does NOT raise the prompt, because " +
                "the platform's biometric prompt needs a foreground Activity and a tool has only a " +
                "Context; the prompt belongs to the UI layer. Use this to decide whether a " +
                "destructive action can be gated, not to perform the gate.",
            category = "security",
            parameters = listOf(
                ToolParameter(
                    "reason", ToolParameterType.String, true,
                    "A short description of what the authentication would protect."
                ),
            ),
            permissions = listOf(TeamThreePermissions.USE_BIOMETRIC.permission),
        ),
        ToolDefinition(
            name = VIBRATE,
            description = "Vibrate the phone for a given duration or pattern. There is no readback " +
                "for vibration on this platform, so the result reports what was requested and says " +
                "plainly that the platform gives no confirmation. It never claims the phone " +
                "vibrated, because that could not be known.",
            category = "device_control",
            parameters = listOf(
                ToolParameter(
                    "durationMs", ToolParameterType.Integer, false, "How long, 1 to 10000 ms. Default 200."
                ),
                ToolParameter(
                    "amplitude", ToolParameterType.Integer, false,
                    "Strength from 1 to 255, or -1 for the device default."
                ),
            ),
            permissions = listOf(TeamThreePermissions.VIBRATE.permission),
        ),
        ToolDefinition(
            name = KEEP_AWAKE,
            description = "Hold or release a partial wake lock so GURU keeps working while the screen " +
                "is off. The lock's own state IS readable, so the result reports what the platform " +
                "says rather than what was asked for. State plainly: a lock with no timeout is a " +
                "real battery cost, and the process dying releases it.",
            category = "device_control",
            parameters = listOf(
                ToolParameter(
                    "action", ToolParameterType.Enum, true, "Acquire or release the lock.",
                    listOf("ACQUIRE", "RELEASE"),
                ),
                ToolParameter("tag", ToolParameterType.String, false, "A tag naming this lock. Default guru-tool."),
                ToolParameter(
                    "timeoutMs", ToolParameterType.Long, false,
                    "Release automatically after this many milliseconds. Default 0, meaning hold until released."
                ),
            ),
            permissions = listOf(TeamThreePermissions.WAKE_LOCK.permission),
        ),
    )

    override fun executorClass(): KClass<out ToolExecutor> = SettingsControlDomainExecutor::class
    override fun extractorClass(): KClass<out ToolResultExtractor>? = null
}

class SettingsControlDomainExecutor(private val context: Context) : ToolExecutor {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** Wake locks this process holds, keyed by tag. */
    private val wakeLocks = mutableMapOf<String, PowerManager.WakeLock>()

    override suspend fun execute(toolName: String, args: Map<String, Any?>): ToolExecutionResult =
        when (toolName) {
            SettingsControlDomainDefinitions.SETTINGS_WRITE -> settingsWrite(args)
            SettingsControlDomainDefinitions.SECURE_SETTINGS_WRITE -> secureSettingsWrite(args)
            SettingsControlDomainDefinitions.TIME_SET -> timeSet(args)
            SettingsControlDomainDefinitions.CONFIGURATION_CHANGE -> configurationChange(args)
            SettingsControlDomainDefinitions.OVERLAY_CREATE -> overlayCreate(args)
            SettingsControlDomainDefinitions.OVERLAY_REMOVE -> overlayRemove(args)
            SettingsControlDomainDefinitions.EXPAND_STATUS_BAR -> expandStatusBar(args)
            SettingsControlDomainDefinitions.SET_WALLPAPER -> setWallpaper(args)
            SettingsControlDomainDefinitions.BIOMETRIC_AUTH -> biometricAuth(args)
            SettingsControlDomainDefinitions.VIBRATE -> vibrate(args)
            SettingsControlDomainDefinitions.KEEP_AWAKE -> keepAwake(args)
            else -> ToolExecutionResult.error("Unknown tool: $toolName")
        }

    // ------------------------------------------------------------------
    // Settings
    // ------------------------------------------------------------------

    private suspend fun settingsWrite(args: Map<String, Any?>): ToolExecutionResult {
        PermissionGrants.guard(context, TeamThreePermissions.WRITE_SETTINGS)?.let { return it }

        val setting = ((args["setting"] as? String) ?: "").uppercase()
        val value = args["value"] as? String
        if (value == null) {
            return failure(
                SettingsWriteResult.serializer(),
                SettingsWriteResult(
                    success = false,
                    setting = setting,
                    error = "This call needs a 'value' to write.",
                ),
            )
        }

        val key = when (setting) {
            "SCREEN_BRIGHTNESS" -> Settings.System.SCREEN_BRIGHTNESS
            "SCREEN_OFF_TIMEOUT" -> Settings.System.SCREEN_OFF_TIMEOUT
            "FONT_SCALE" -> Settings.System.FONT_SCALE
            "RINGTONE" -> Settings.System.RINGTONE
            else -> return failure(
                SettingsWriteResult.serializer(),
                SettingsWriteResult(
                    success = false,
                    setting = setting,
                    error = "The setting must be SCREEN_BRIGHTNESS, SCREEN_OFF_TIMEOUT, FONT_SCALE or " +
                        "RINGTONE, and '$setting' is none of them.",
                ),
            )
        }

        val outcome = withContext(Dispatchers.IO) {
            runCatching {
                val before = Settings.System.getString(context.contentResolver, key)
                // Brightness and timeout are integers, font scale is a float and the ringtone
                // is a uri string. Writing the wrong type into the wrong table is a silent
                // no-op on this platform, so the type is chosen by the key rather than guessed.
                val written = when (setting) {
                    "SCREEN_BRIGHTNESS", "SCREEN_OFF_TIMEOUT" ->
                        Settings.System.putInt(context.contentResolver, key, value.toIntOrNull() ?: -1)
                    "FONT_SCALE" ->
                        Settings.System.putFloat(context.contentResolver, key, value.toFloatOrNull() ?: 1.0f)
                    else -> Settings.System.putString(context.contentResolver, key, value)
                }
                val after = Settings.System.getString(context.contentResolver, key)
                Triple(before, after, written)
            }.getOrNull()
        }

        if (outcome == null) {
            return failure(
                SettingsWriteResult.serializer(),
                SettingsWriteResult(
                    success = false,
                    setting = setting,
                    requested = value,
                    error = "Writing to the system settings table threw, so nothing was confirmed.",
                ),
            )
        }

        val (before, after, written) = outcome
        val r = SettingsWriteResult(
            success = written,
            setting = setting,
            requested = value,
            observedAfter = after,
            beforeValue = before,
            changed = before != after,
            note = when {
                !written ->
                    "The platform refused the write. The Modify system settings grant may have been " +
                        "revoked since it was checked, so re-check Permissions before retrying."
                before == after ->
                    "The write was accepted but the value read back unchanged, so the setting did " +
                        "not actually change. Reporting the readback rather than the intent."
                else ->
                    "Changed $setting from ${before ?: "unset"} to ${after ?: "unset"}, read back from " +
                        "the platform after writing."
            },
        )
        return emit(json, SettingsWriteResult.serializer(), r)
    }

    private suspend fun secureSettingsWrite(args: Map<String, Any?>): ToolExecutionResult {
        PermissionGrants.guard(context, TeamThreePermissions.WRITE_SECURE_SETTINGS)?.let { return it }

        val namespace = ((args["namespace"] as? String) ?: "SECURE").uppercase()
        val key = args["key"] as? String
        val valueType = ((args["valueType"] as? String) ?: "STRING").uppercase()
        val value = args["value"] as? String

        if (key.isNullOrBlank() || value == null) {
            return failure(
                SettingsWriteResult.serializer(),
                SettingsWriteResult(
                    success = false,
                    setting = "$namespace:$key",
                    error = "This call needs both a 'key' and a 'value'.",
                ),
            )
        }

        val outcome = withContext(Dispatchers.IO) {
            runCatching {
                val resolver = context.contentResolver
                val before = when (namespace) {
                    "SYSTEM" -> Settings.System.getString(resolver, key)
                    "GLOBAL" -> Settings.Global.getString(resolver, key)
                    else -> Settings.Secure.getString(resolver, key)
                }
                val written = when (namespace) {
                    "SYSTEM" -> when (valueType) {
                        "INT" -> Settings.System.putInt(resolver, key, value.toIntOrNull() ?: 0)
                        "FLOAT" -> Settings.System.putFloat(resolver, key, value.toFloatOrNull() ?: 0f)
                        else -> Settings.System.putString(resolver, key, value)
                    }
                    "GLOBAL" -> when (valueType) {
                        "INT" -> Settings.Global.putInt(resolver, key, value.toIntOrNull() ?: 0)
                        "FLOAT" -> Settings.Global.putFloat(resolver, key, value.toFloatOrNull() ?: 0f)
                        else -> Settings.Global.putString(resolver, key, value)
                    }
                    else -> when (valueType) {
                        "INT" -> Settings.Secure.putInt(resolver, key, value.toIntOrNull() ?: 0)
                        "FLOAT" -> Settings.Secure.putFloat(resolver, key, value.toFloatOrNull() ?: 0f)
                        else -> Settings.Secure.putString(resolver, key, value)
                    }
                }
                val after = when (namespace) {
                    "SYSTEM" -> Settings.System.getString(resolver, key)
                    "GLOBAL" -> Settings.Global.getString(resolver, key)
                    else -> Settings.Secure.getString(resolver, key)
                }
                Triple(before, after, written)
            }.getOrNull()
        }

        if (outcome == null) {
            return failure(
                SettingsWriteResult.serializer(),
                SettingsWriteResult(
                    success = false,
                    setting = "$namespace:$key",
                    requested = value,
                    error = "Writing the secure setting threw, so nothing was confirmed.",
                ),
            )
        }

        val (before, after, written) = outcome
        val r = SettingsWriteResult(
            success = written,
            setting = "$namespace:$key",
            requested = value,
            observedAfter = after,
            beforeValue = before,
            changed = before != after,
            note = if (before == after) {
                "The write was accepted but the value read back unchanged. Some keys are protected " +
                    "even from a holder of this privilege, so the readback is the answer."
            } else {
                "Wrote $namespace:$key and read back ${after ?: "null"} afterwards."
            },
        )
        return emit(json, SettingsWriteResult.serializer(), r)
    }

    private suspend fun timeSet(args: Map<String, Any?>): ToolExecutionResult {
        val what = ((args["what"] as? String) ?: "CLOCK").uppercase()
        val autoDetect = args["autoDetect"] as? Boolean ?: false

        val required = when (what) {
            "CLOCK" -> TeamThreePermissions.SET_TIME
            "TIME_ZONE" -> TeamThreePermissions.SET_TIME_ZONE
            else -> return failure(
                TimeSetResult.serializer(),
                TimeSetResult(
                    success = false,
                    what = what,
                    error = "The 'what' must be CLOCK or TIME_ZONE, and '$what' is neither.",
                ),
            )
        }
        PermissionGrants.guard(context, required)?.let { return it }

        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? android.app.AlarmManager
        if (alarmManager == null) {
            return failure(
                TimeSetResult.serializer(),
                TimeSetResult(
                    success = false,
                    what = what,
                    error = "This device exposes no alarm service, so the clock cannot be set.",
                ),
            )
        }

        val attempted = withContext(Dispatchers.IO) {
            runCatching {
                if (what == "CLOCK") {
                    if (autoDetect) {
                        Settings.Global.putInt(context.contentResolver, Settings.Global.AUTO_TIME, 1)
                    } else {
                        val epoch = (args["epochMillis"] as? Number)?.toLong()
                            ?: System.currentTimeMillis()
                        alarmManager.setTime(epoch)
                    }
                } else {
                    if (autoDetect) {
                        Settings.Global.putInt(context.contentResolver, Settings.Global.AUTO_TIME_ZONE, 1)
                    } else {
                        val zone = (args["timeZoneId"] as? String)?.trim()
                        if (zone.isNullOrBlank()) return@runCatching false
                        alarmManager.setTimeZone(zone)
                    }
                }
                true
            }.getOrDefault(false)
        }

        // Reading the clock and the zone back is the only evidence either change landed.
        val now = System.currentTimeMillis()
        val zoneId = TimeZone.getDefault().id
        val r = TimeSetResult(
            success = attempted,
            what = what,
            clockMillis = now,
            timeZoneId = zoneId,
            clockIso = runCatching {
                java.time.Instant.ofEpochMilli(now).toString()
            }.getOrNull(),
            route = when {
                autoDetect -> "asked for automatic time"
                what == "CLOCK" -> "AlarmManager.setTime"
                else -> "AlarmManager.setTimeZone"
            },
            note = if (attempted) {
                "The call was accepted. The clock and zone above are read from the platform " +
                    "immediately afterwards, so compare them against what you asked for: a write the " +
                    "platform accepted but did not apply shows up here as an unchanged value."
            } else {
                "The platform refused the change, so the clock and zone above are unchanged."
            },
        )
        return emit(json, TimeSetResult.serializer(), r)
    }

    private suspend fun configurationChange(args: Map<String, Any?>): ToolExecutionResult {
        val action = ((args["action"] as? String) ?: "").uppercase()

        return when (action) {
            "SET_DENSITY" -> {
                PermissionGrants.guard(context, TeamThreePermissions.WRITE_SECURE_SETTINGS)
                    ?.let { return it }
                val dpi = (args["densityDpi"] as? Number)?.toInt()
                    ?: return failure(
                        SettingsWriteResult.serializer(),
                        SettingsWriteResult(
                            success = false,
                            setting = "display_density_forced",
                            error = "SET_DENSITY needs 'densityDpi' as a number between 120 and 640.",
                        ),
                    )
                val clamped = dpi.coerceIn(120, 640)

                val outcome = withContext(Dispatchers.IO) {
                    runCatching {
                        val before = Settings.Secure.getString(
                            context.contentResolver, "display_density_forced",
                        )
                        val written = Settings.Secure.putInt(
                            context.contentResolver, "display_density_forced", clamped,
                        )
                        val after = Settings.Secure.getString(
                            context.contentResolver, "display_density_forced",
                        )
                        Triple(before, after, written)
                    }.getOrNull()
                }

                if (outcome == null) {
                    failure(
                        SettingsWriteResult.serializer(),
                        SettingsWriteResult(
                            success = false,
                            setting = "display_density_forced",
                            requested = clamped.toString(),
                            error = "Writing the density threw, so nothing was confirmed.",
                        ),
                    )
                } else {
                    val (before, after, written) = outcome
                    val r = SettingsWriteResult(
                        success = written,
                        setting = "display_density_forced",
                        requested = clamped.toString(),
                        observedAfter = after,
                        beforeValue = before,
                        changed = before != after,
                        note = "Density is written through the secure settings table, which needs the " +
                            "secure settings privilege. The value above is read back afterwards, and a " +
                            "display usually applies it on the next configuration change rather than " +
                            "instantly.",
                    )
                    emit(json, SettingsWriteResult.serializer(), r)
                }
            }

            "SET_LOCALE" -> {
                PermissionGrants.guard(context, TeamThreePermissions.CHANGE_CONFIGURATION)
                    ?.let { return it }

                val locale = (args["locale"] as? String)?.trim()
                if (locale.isNullOrBlank()) {
                    return failure(
                        SettingsWriteResult.serializer(),
                        SettingsWriteResult(
                            success = false,
                            setting = "locale",
                            error = "SET_LOCALE needs 'locale' as a BCP-47 tag such as en-GB.",
                        ),
                    )
                }

                // Per-app locales exist from API 33 and apply to this app only. A system-wide
                // locale change is not reachable from an ordinary app, so the honest answer
                // names the limit rather than reporting a change that did not happen.
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                    failure(
                        SettingsWriteResult.serializer(),
                        SettingsWriteResult(
                            success = false,
                            setting = "locale",
                            requested = locale,
                            error = "Changing the system-wide locale is not reachable from an ordinary " +
                                "app, and this Android version does not offer per-app locales either. " +
                                "The person can change the language in Settings > System > Languages.",
                        ),
                    )
                } else {
                    val current = runCatching {
                        context.resources.configuration.locales[0]?.toLanguageTag()
                    }.getOrNull()
                    val r = SettingsWriteResult(
                        success = true,
                        setting = "locale",
                        requested = locale,
                        observedAfter = current,
                        beforeValue = current,
                        changed = false,
                        note = "This build can read the app's current locale but has no activity in " +
                            "scope to request a per-app locale change, because that call belongs to a " +
                            "foreground context. The system-wide locale cannot be changed by an " +
                            "ordinary app at all. Nothing was changed here.",
                    )
                    emit(json, SettingsWriteResult.serializer(), r)
                }
            }

            else -> failure(
                SettingsWriteResult.serializer(),
                SettingsWriteResult(
                    success = false,
                    setting = action,
                    error = "The action must be SET_DENSITY or SET_LOCALE, and '$action' is neither.",
                ),
            )
        }
    }

    // ------------------------------------------------------------------
    // Overlays
    // ------------------------------------------------------------------

    private suspend fun overlayCreate(args: Map<String, Any?>): ToolExecutionResult {
        PermissionGrants.guard(context, TeamThreePermissions.SYSTEM_ALERT_WINDOW)?.let { return it }

        val text = args["text"] as? String
        if (text.isNullOrBlank()) {
            return failure(
                OverlayResult.serializer(),
                OverlayResult(success = false, error = "This call needs 'text' to display."),
            )
        }
        val x = ((args["x"] as? Number)?.toInt() ?: 0)
        val y = ((args["y"] as? Number)?.toInt() ?: 200)
        val width = ((args["width"] as? Number)?.toInt() ?: 900).coerceAtLeast(1)
        val height = ((args["height"] as? Number)?.toInt() ?: 300).coerceAtLeast(1)

        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            ?: return failure(
                OverlayResult.serializer(),
                OverlayResult(
                    success = false,
                    error = "This device exposes no window manager, so an overlay cannot be added.",
                ),
            )

        val id = "overlay-${System.currentTimeMillis()}"

        // WindowManager and View are main-thread objects. Touching them from an IO thread
        // throws, and this project has shipped that bug, so the hop is explicit.
        val added = withContext(Dispatchers.Main) {
            runCatching {
                val view = TextView(context).apply {
                    this.text = text
                    setTextColor(Color.WHITE)
                    setBackgroundColor(Color.argb(220, 20, 20, 20))
                    gravity = Gravity.CENTER
                    textSize = 16f
                }
                val params = WindowManager.LayoutParams(
                    width,
                    height,
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    } else {
                        @Suppress("DEPRECATION")
                        WindowManager.LayoutParams.TYPE_PHONE
                    },
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                    PixelFormat.TRANSLUCENT,
                ).apply {
                    gravity = Gravity.TOP or Gravity.START
                    this.x = x
                    this.y = y
                }
                windowManager.addView(view, params)
                OverlayRegistry.put(id, view, params)
                true
            }.getOrDefault(false)
        }

        if (!added) {
            return failure(
                OverlayResult.serializer(),
                OverlayResult(
                    success = false,
                    activeOverlays = OverlayRegistry.ids(),
                    error = "The window manager refused the overlay. The Display over other apps grant " +
                        "may have been revoked since it was checked.",
                ),
            )
        }

        val r = OverlayResult(
            success = true,
            overlayId = id,
            activeOverlays = OverlayRegistry.ids(),
            note = "The overlay is on screen now. It stays until overlayRemove is called with this id, " +
                "or until this process dies, because the registry is in memory and does not survive " +
                "a restart.",
        )
        return emit(json, OverlayResult.serializer(), r)
    }

    private suspend fun overlayRemove(args: Map<String, Any?>): ToolExecutionResult {
        PermissionGrants.guard(context, TeamThreePermissions.SYSTEM_ALERT_WINDOW)?.let { return it }

        val removeAll = args["all"] as? Boolean ?: false
        val id = (args["overlayId"] as? String)?.trim()?.takeIf { it.isNotEmpty() }

        if (!removeAll && id == null) {
            return failure(
                OverlayResult.serializer(),
                OverlayResult(
                    success = false,
                    activeOverlays = OverlayRegistry.ids(),
                    error = "This call needs 'overlayId', or all=true to remove every overlay.",
                ),
            )
        }

        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        if (windowManager == null) {
            return failure(
                OverlayResult.serializer(),
                OverlayResult(
                    success = false,
                    activeOverlays = OverlayRegistry.ids(),
                    error = "This device exposes no window manager, so nothing can be removed.",
                ),
            )
        }

        val targets = if (removeAll) {
            OverlayRegistry.takeAll()
        } else {
            val found = OverlayRegistry.remove(id!!)
            if (found == null) emptyList() else listOf(id to found)
        }

        if (targets.isEmpty()) {
            return emit(
                json,
                OverlayResult.serializer(),
                OverlayResult(
                    success = false,
                    activeOverlays = OverlayRegistry.ids(),
                    note = "No overlay with that id is known to this process. An overlay from a " +
                        "previous run cannot be removed, because the registry died with that process.",
                    error = "No overlay matched, so nothing was removed.",
                ),
            )
        }

        val removed = withContext(Dispatchers.Main) {
            var count = 0
            for ((_, pair) in targets) {
                val ok = runCatching {
                    windowManager.removeView(pair.first)
                    true
                }.getOrDefault(false)
                if (ok) count++
            }
            count
        }

        val r = OverlayResult(
            success = removed > 0,
            activeOverlays = OverlayRegistry.ids(),
            note = "Removed $removed of ${targets.size} overlay(s) on the main thread.",
            error = if (removed == 0) "The window manager refused every removal." else null,
        )
        return emit(json, OverlayResult.serializer(), r)
    }

    // ------------------------------------------------------------------
    // Status bar, wallpaper
    // ------------------------------------------------------------------

    private suspend fun expandStatusBar(args: Map<String, Any?>): ToolExecutionResult {
        PermissionGrants.guard(context, TeamThreePermissions.EXPAND_STATUS_BAR)?.let { return it }

        val action = ((args["action"] as? String) ?: "EXPAND").uppercase()
        if (action != "EXPAND" && action != "COLLAPSE") {
            return failure(
                StatusBarResult.serializer(),
                StatusBarResult(
                    success = false,
                    action = action,
                    error = "The action must be EXPAND or COLLAPSE, and '$action' is neither.",
                ),
            )
        }

        // THE ONLY ROUTE, and it is legitimate. StatusBarManager is a system service with no
        // public API, so reflection is how every app that expands the shade does it. No root
        // and no debug harness is involved, which is the distinction RULES.md section 3
        // draws: this is the real implementation rather than a workaround for a breakage.
        val outcome = runCatching {
            val manager = context.getSystemService("statusbar")
                ?: return@runCatching "absent" to false
            val clazz = manager.javaClass
            if (action == "EXPAND") {
                val method = clazz.getMethod("expandNotificationsPanel")
                method.invoke(manager)
                "reflection" to true
            } else {
                val method = clazz.getMethod("collapsePanels")
                method.invoke(manager)
                "reflection" to true
            }
        }.getOrElse { error ->
            (error::class.simpleName ?: "error") to false
        }

        val (route, worked) = outcome
        val r = StatusBarResult(
            success = worked,
            action = action,
            route = if (route == "reflection") "the system status bar service through reflection" else route,
            note = if (worked) {
                "The ${action.lowercase()} call was accepted by the status bar service. There is no " +
                    "readback for the shade's state, so this reports the call rather than the screen."
            } else {
                "The status bar service could not be reached on this platform version. There is no " +
                    "permission that would fix that, so this is the platform rather than a grant."
            },
            error = if (worked) null else "The status bar service is not reachable on this build.",
        )
        return emit(json, StatusBarResult.serializer(), r)
    }

    private suspend fun setWallpaper(args: Map<String, Any?>): ToolExecutionResult {
        PermissionGrants.guard(context, TeamThreePermissions.SET_WALLPAPER)?.let { return it }

        val source = ((args["source"] as? String) ?: "").uppercase()
        val which = ((args["which"] as? String) ?: "HOME").uppercase()

        val manager = context.getSystemService(Context.WALLPAPER_SERVICE) as? android.app.WallpaperManager
            ?: return failure(
                WallpaperResult.serializer(),
                WallpaperResult(
                    success = false,
                    source = source,
                    error = "This device exposes no wallpaper manager, so the wallpaper cannot be set.",
                ),
            )

        // The flags say which surface to change. Getting them wrong changes the lock screen
        // when the home screen was asked for, which is a visible mistake.
        val flags = when (which) {
            "LOCK" -> android.app.WallpaperManager.FLAG_LOCK
            "BOTH" -> android.app.WallpaperManager.FLAG_SYSTEM or android.app.WallpaperManager.FLAG_LOCK
            else -> android.app.WallpaperManager.FLAG_SYSTEM
        }

        val applied = withContext(Dispatchers.IO) {
            runCatching {
                when (source) {
                    "IMAGE" -> {
                        val path = args["path"] as? String
                        if (path.isNullOrBlank()) return@runCatching false
                        val file = java.io.File(path)
                        if (!file.exists()) return@runCatching false

                        // Downsample before decoding. A modern phone photo decoded at full
                        // resolution is tens of megabytes of bitmap and this project has
                        // already shipped a 37 MB decode for an 18dp wordmark.
                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeFile(path, bounds)
                        var sample = 1
                        var longest = maxOf(bounds.outWidth, bounds.outHeight)
                        while (longest / sample > 2048) sample *= 2
                        val options = BitmapFactory.Options().apply { inSampleSize = sample }
                        val bitmap = BitmapFactory.decodeFile(path, options) ?: return@runCatching false
                        manager.setBitmap(bitmap, null, true, flags)
                        bitmap.recycle()
                        true
                    }
                    "COLOUR" -> {
                        val colour = args["colour"] as? String
                        val parsed = runCatching {
                            Color.parseColor(colour ?: "#000000")
                        }.getOrDefault(Color.BLACK)
                        val bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
                        val canvas = Canvas(bitmap)
                        canvas.drawColor(parsed)
                        manager.setBitmap(bitmap, null, true, flags)
                        bitmap.recycle()
                        true
                    }
                    else -> false
                }
            }.getOrDefault(false)
        }

        if (!applied) {
            return failure(
                WallpaperResult.serializer(),
                WallpaperResult(
                    success = false,
                    source = source,
                    error = when (source) {
                        "IMAGE" ->
                            "The image could not be read or the manager refused it. Check the path exists."
                        "COLOUR" ->
                            "The colour could not be parsed or the manager refused it."
                        else ->
                            "The source must be IMAGE or COLOUR, and '$source' is neither."
                    },
                ),
            )
        }

        // getWallpaperInfo returns non-null only for a live wallpaper, so a null here means a
        // static one is set, which is what this tool produces.
        val staticSet = runCatching { manager.wallpaperInfo == null }.getOrNull()
        val r = WallpaperResult(
            success = true,
            source = source,
            staticWallpaperSet = staticSet,
            note = "The wallpaper manager accepted the new image for the ${which.lowercase()} surface. " +
                "This reports that a static wallpaper is set, not what it looks like.",
        )
        return emit(json, WallpaperResult.serializer(), r)
    }

    // ------------------------------------------------------------------
    // Biometrics
    // ------------------------------------------------------------------

    private suspend fun biometricAuth(args: Map<String, Any?>): ToolExecutionResult {
        PermissionGrants.guard(context, TeamThreePermissions.USE_BIOMETRIC)?.let { return it }

        val reason = args["reason"] as? String
        if (reason.isNullOrBlank()) {
            return failure(
                BiometricResult.serializer(),
                BiometricResult(
                    success = false,
                    error = "This call needs a 'reason' describing what the authentication would protect.",
                ),
            )
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return emit(
                json,
                BiometricResult.serializer(),
                BiometricResult(
                    success = true,
                    available = false,
                    availability = "UNSUPPORTED_API",
                    note = "BiometricManager availability reporting needs Android 10 or newer. On this " +
                        "version the platform cannot answer the question this tool asks.",
                ),
            )
        }

        val outcome = runCatching {
            val manager = context.getSystemService(BiometricManager::class.java)
            val strong = manager?.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            val weak = manager?.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK)
            strong to weak
        }.getOrNull()

        val strong = outcome?.first
        val weak = outcome?.second
        val availability = when {
            strong == BiometricManager.BIOMETRIC_SUCCESS -> "BIOMETRIC_SUCCESS"
            weak == BiometricManager.BIOMETRIC_SUCCESS -> "BIOMETRIC_SUCCESS_WEAK_ONLY"
            strong == BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> "ERROR_NO_HARDWARE"
            strong == BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> "ERROR_HW_UNAVAILABLE"
            strong == BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> "ERROR_NONE_ENROLLED"
            strong == BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED ->
                "ERROR_SECURITY_UPDATE_REQUIRED"
            strong == null -> "UNREADABLE"
            else -> "ERROR_$strong"
        }

        val r = BiometricResult(
            success = true,
            available = availability.startsWith("BIOMETRIC_SUCCESS"),
            availability = availability,
            strongAvailable = strong == BiometricManager.BIOMETRIC_SUCCESS,
            note = buildString {
                append("This is the availability answer for gating a destructive action behind ")
                append("biometrics. ")
                append("It does not raise the prompt: BiometricPrompt needs a foreground Activity and a ")
                append("tool has only a Context, so the prompt is the UI layer's job. ")
                if (availability == "ERROR_NONE_ENROLLED") {
                    append("A fingerprint or face is not enrolled yet, which the person can set up in ")
                    append("Settings > Security.")
                }
            }.trim(),
        )
        return emit(json, BiometricResult.serializer(), r)
    }

    // ------------------------------------------------------------------
    // Vibration and wake locks
    // ------------------------------------------------------------------

    private suspend fun vibrate(args: Map<String, Any?>): ToolExecutionResult {
        PermissionGrants.guard(context, TeamThreePermissions.VIBRATE)?.let { return it }

        val duration = ((args["durationMs"] as? Number)?.toInt() ?: 200).coerceIn(1, 10_000)
        val amplitude = ((args["amplitude"] as? Number)?.toInt() ?: -1).coerceIn(-1, 255)

        val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }

        if (vibrator == null || !vibrator.hasVibrator()) {
            return failure(
                VibrateResult.serializer(),
                VibrateResult(
                    success = false,
                    error = "This device reports no vibrator, so nothing can be felt. That is the device " +
                        "rather than a permission.",
                ),
            )
        }

        val fired = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val effect = if (amplitude < 0) {
                    VibrationEffect.createOneShot(duration.toLong(), VibrationEffect.DEFAULT_AMPLITUDE)
                } else {
                    VibrationEffect.createOneShot(duration.toLong(), amplitude)
                }
                vibrator.vibrate(effect)
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(duration.toLong())
            }
            true
        }.getOrDefault(false)

        if (!fired) {
            return failure(
                VibrateResult.serializer(),
                VibrateResult(
                    success = false,
                    requestedDurationMs = duration,
                    error = "The vibrator call was refused, which usually means vibration is switched " +
                        "off in the phone's sound settings.",
                ),
            )
        }

        val r = VibrateResult(
            success = true,
            requestedDurationMs = duration,
            note = "Requested a $duration ms vibration at amplitude $amplitude. The platform gives no " +
                "readback for vibration, so this reports the request and not a confirmed sensation.",
        )
        return emit(json, VibrateResult.serializer(), r)
    }

    private suspend fun keepAwake(args: Map<String, Any?>): ToolExecutionResult {
        PermissionGrants.guard(context, TeamThreePermissions.WAKE_LOCK)?.let { return it }

        val action = ((args["action"] as? String) ?: "").uppercase()
        val tag = (args["tag"] as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: "guru-tool"
        val timeout = (args["timeoutMs"] as? Number)?.toLong() ?: 0L

        if (action != "ACQUIRE" && action != "RELEASE") {
            return failure(
                WakeLockResult.serializer(),
                WakeLockResult(
                    success = false,
                    action = action,
                    error = "The action must be ACQUIRE or RELEASE, and '$action' is neither.",
                ),
            )
        }

        val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            ?: return failure(
                WakeLockResult.serializer(),
                WakeLockResult(
                    success = false,
                    action = action,
                    error = "This device exposes no power service, so a wake lock cannot be taken.",
                ),
            )

        return if (action == "ACQUIRE") {
            if (wakeLocks.containsKey(tag)) {
                val existing = wakeLocks[tag]
                emit(
                    json,
                    WakeLockResult.serializer(),
                    WakeLockResult(
                        success = true,
                        action = action,
                        held = existing?.isHeld == true,
                        platformReportsHeld = existing?.isHeld,
                        note = "A wake lock with the tag $tag is already held by this process, so this " +
                            "call left it alone rather than taking a second one.",
                    ),
                )
            } else {
                val lock = runCatching {
                    power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, tag).apply {
                        setReferenceCounted(false)
                        if (timeout > 0) acquire(timeout) else acquire()
                    }
                }.getOrNull()

                if (lock == null) {
                    failure(
                        WakeLockResult.serializer(),
                        WakeLockResult(
                            success = false,
                            action = action,
                            error = "The power service refused the wake lock.",
                        ),
                    )
                } else {
                    wakeLocks[tag] = lock
                    emit(
                        json,
                        WakeLockResult.serializer(),
                        WakeLockResult(
                            success = true,
                            action = action,
                            held = lock.isHeld,
                            platformReportsHeld = lock.isHeld,
                            note = buildString {
                                append("The lock's own isHeld property reports ${lock.isHeld}, which is ")
                                append("the platform's answer rather than the request. ")
                                if (timeout <= 0) {
                                    append("This lock has no timeout, so it holds the CPU awake until it ")
                                    append("is released or this process dies, which is a real battery cost.")
                                } else {
                                    append("It releases itself after $timeout ms.")
                                }
                            }.trim(),
                        ),
                    )
                }
            }
        } else {
            val lock = wakeLocks.remove(tag)
            if (lock == null) {
                emit(
                    json,
                    WakeLockResult.serializer(),
                    WakeLockResult(
                        success = false,
                        action = action,
                        held = false,
                        note = "No wake lock with the tag $tag is held by this process, so there was " +
                            "nothing to release.",
                        error = "No wake lock matched that tag.",
                    ),
                )
            } else {
                val released = runCatching {
                    if (lock.isHeld) lock.release()
                    !lock.isHeld
                }.getOrDefault(false)
                emit(
                    json,
                    WakeLockResult.serializer(),
                    WakeLockResult(
                        success = released,
                        action = action,
                        held = lock.isHeld,
                        platformReportsHeld = lock.isHeld,
                        note = "Released the wake lock and read its isHeld property back, which now " +
                            "reports ${lock.isHeld}.",
                        error = if (released) null else "The lock still reports itself as held.",
                    ),
                )
            }
        }
    }

    // ------------------------------------------------------------------
    // Emit helpers
    // ------------------------------------------------------------------

    private fun <T : ToolResultData> emit(
        json: Json,
        serializer: kotlinx.serialization.KSerializer<T>,
        payload: T,
    ): ToolExecutionResult = ToolExecutionResult(
        rawJson = json.encodeToString(serializer, payload),
        resultData = payload,
        resultObject = null,
        success = when (payload) {
            is SettingsWriteResult -> payload.success
            is TimeSetResult -> payload.success
            is OverlayResult -> payload.success
            is StatusBarResult -> payload.success
            is WallpaperResult -> payload.success
            is BiometricResult -> payload.success
            is VibrateResult -> payload.success
            is WakeLockResult -> payload.success
            else -> false
        },
        error = when (payload) {
            is SettingsWriteResult -> payload.error
            is TimeSetResult -> payload.error
            is OverlayResult -> payload.error
            is StatusBarResult -> payload.error
            is WallpaperResult -> payload.error
            is BiometricResult -> payload.error
            is VibrateResult -> payload.error
            is WakeLockResult -> payload.error
            else -> null
        },
    )

    private fun <T : ToolResultData> failure(
        serializer: kotlinx.serialization.KSerializer<T>,
        payload: T,
    ): ToolExecutionResult = ToolExecutionResult(
        rawJson = json.encodeToString(serializer, payload),
        resultData = payload,
        resultObject = null,
        success = false,
        error = when (payload) {
            is SettingsWriteResult -> payload.error
            is TimeSetResult -> payload.error
            is OverlayResult -> payload.error
            is StatusBarResult -> payload.error
            is WallpaperResult -> payload.error
            is BiometricResult -> payload.error
            is VibrateResult -> payload.error
            is WakeLockResult -> payload.error
            else -> null
        },
    )
}

object SettingsControlDomainModule : DomainToolModule {
    override val id: String = "settings_control"

    override val registrations: List<ToolSetRegistration> = listOf(SettingsControlDomainDefinitions)

    override fun bindings(): Module = module {
        factory { SettingsControlDomainExecutor(get()) }
    }
}
