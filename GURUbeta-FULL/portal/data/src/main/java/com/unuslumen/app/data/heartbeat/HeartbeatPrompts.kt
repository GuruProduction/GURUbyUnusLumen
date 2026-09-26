package com.unuslumen.app.data.heartbeat

import android.content.Context
import android.util.Log

/**
 * Loader for the bundled heartbeat prompt.
 *
 * The prompt lives at `assets/heartbeat/HEARTBEAT_PROMPT.md` and ships compiled
 * inside the APK (the Android build packs everything under `portal/data/src/main/assets/`
 * verbatim). It is authored and maintained by the founder directly; the app never
 * writes to it and sync never overwrites it.
 *
 * At beat time the framework substitutes the token contract before injecting:
 *  - {{time}}        — the device's current date, time and timezone
 *  - {{human_name}}  — the user's chosen name ("your human" when blank)
 *
 * If the asset is missing or blank the heartbeat is skipped: a beat is never
 * injected with an empty or broken prompt. Failures are logged, never thrown.
 */
object HeartbeatPrompts {

    private const val TAG = "guru_heartbeat"
    private const val ASSET_PATH = "heartbeat/HEARTBEAT_PROMPT.md"
    private const val TIME_TOKEN = "{{time}}"
    private const val HUMAN_NAME_TOKEN = "{{human_name}}"

    /**
     * Load, substitute and return the prompt, or null when it must not be injected
     * (missing asset, blank asset, or substitution failure).
     */
    fun load(context: Context, humanName: String): String? {
        return try {
            val raw = context.assets.open(ASSET_PATH).bufferedReader().use { it.readText() }
            if (raw.isBlank()) {
                Log.w(TAG, "Heartbeat prompt asset is blank — skipping beat")
                return null
            }
            val effectiveName = humanName.ifBlank { "your human" }
            raw
                .replace(HUMAN_NAME_TOKEN, effectiveName)
                .replace(TIME_TOKEN, formatCurrentTime())
        } catch (e: Exception) {
            Log.w(TAG, "Heartbeat prompt load failed — skipping beat: ${e.message}")
            null
        }
    }

    private fun formatCurrentTime(): String {
        val now = java.time.ZonedDateTime.now()
        val zone = java.time.format.TextStyle.FULL
        return java.time.format.DateTimeFormatter
            .ofPattern("EEEE, d MMMM yyyy 'at' HH:mm")
            .format(now) + " (${now.zone.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.getDefault())})"
    }
}