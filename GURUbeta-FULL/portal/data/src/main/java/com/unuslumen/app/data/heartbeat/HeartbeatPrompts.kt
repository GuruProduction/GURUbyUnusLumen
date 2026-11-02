// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.heartbeat

import android.content.Context
import android.util.Log

/**
 * The bundled heartbeat prompt, and the token contract it is written against.
 *
 * THIS IS NOW THE FLOOR RATHER THAN THE WHOLE STORY. The prompt is edited in the
 * Heartbeat room and stored in the database, and [HeartbeatPromptRepositoryImpl]
 * falls back to what is here when nothing has been saved. Both paths substitute the
 * same tokens through [substitute], so a saved prompt and the bundled one behave
 * identically.
 *
 * THE ASSET PATH STAYS. The Android build packs everything under
 * `portal/data/src/main/assets/`, the file is authored by the founder, and the app
 * never writes to it.
 */
object HeartbeatPrompts {

    private const val TAG = "guru_heartbeat"
    private const val ASSET_PATH = "heartbeat/HEARTBEAT_PROMPT.md"

    /**
     * THE TOKENS, AND THE SECOND SPELLING THAT WAS SILENTLY BROKEN.
     *
     * The loader knew `{{human_name}}` and `{{time}}`, but the shipped asset is
     * written with `${human}`. Nothing matched it, so every beat in every released
     * build injected the literal text `${human}` where a name belonged. Both
     * spellings are accepted here: a token a person might reasonably type should not
     * fail to substitute just because the app happened to standardise on the other.
     */
    private val HUMAN_TOKENS = listOf("{{human_name}}", "{{human}}", "\${human}", "\${human_name}")
    private val TIME_TOKENS = listOf("{{time}}", "\${time}")

    /** The bundled prompt, unsubstituted. Null when the asset cannot be read. */
    fun bundledText(context: Context): String? = try {
        val raw = context.assets.open(ASSET_PATH).bufferedReader().use { it.readText() }
        raw.takeIf { it.isNotBlank() }
    } catch (e: Exception) {
        Log.w(TAG, "Bundled heartbeat prompt unavailable: ${e.message}")
        null
    }

    /**
     * Load the bundled prompt, substitute and return it, or null when it cannot be
     * injected.
     *
     * Kept because it is the honest name for "read the shipped file", and because a
     * beat must never be injected with an empty or broken prompt: every failure here
     * returns null rather than an empty string, and the caller ends the beat.
     */
    fun load(context: Context, humanName: String): String? {
        val raw = bundledText(context) ?: return null
        return substitute(raw, humanName).ifBlank { null }
    }

    /**
     * Replace every token in a prompt, bundled or stored.
     *
     * Exposed because the Heartbeat room previews exactly what a beat will inject,
     * and a preview that substituted tokens its own way would be a second contract
     * that could disagree with the real one.
     */
    fun substitute(raw: String, humanName: String): String {
        val effectiveName = humanName.ifBlank { "your human" }
        var out = raw
        for (token in HUMAN_TOKENS) out = out.replace(token, effectiveName)
        val now = formatCurrentTime()
        for (token in TIME_TOKENS) out = out.replace(token, now)
        return out
    }

    private fun formatCurrentTime(): String {
        val now = java.time.ZonedDateTime.now()
        val zoneShort = now.zone.getDisplayName(
            java.time.format.TextStyle.SHORT,
            java.util.Locale.getDefault()
        )
        return java.time.format.DateTimeFormatter
            .ofPattern("EEEE, d MMMM yyyy 'at' HH:mm")
            .format(now) + " ($zoneShort)"
    }
}
