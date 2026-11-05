// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.heartbeat

/**
 * The token contract a heartbeat prompt is written against.
 *
 * THE PROMPT ITSELF IS NOT HERE, AND IS NOT ANYWHERE IN THE SOURCE TREE. It used to
 * be: it shipped as `assets/heartbeat/HEARTBEAT_PROMPT.md`, and this object loaded
 * it, and `HeartbeatPromptRepositoryImpl` fell back to it whenever nothing had been
 * saved. The asset, its loader and the fallback are all deleted. The heartbeat
 * prompt is the owner's, written in the Heartbeat room and stored in the database,
 * and nothing else answers for it.
 *
 * WHAT SURVIVES IS THE SUBSTITUTION RULE, because it is a shared contract rather than
 * a default. The room previews exactly what a beat will inject and the beat injects
 * it, and both go through this one function, so the preview cannot stop being true.
 *
 * TWO SPELLINGS, AND THE ONE THAT WAS SILENTLY BROKEN. The old loader knew
 * `{{human_name}}` and `{{time}}` while the shipped asset was written with
 * `${human}`. Nothing matched it, so every beat in every released build injected the
 * literal text `${human}` where a name belonged. Nobody saw it, because a prompt is
 * read by a model rather than by a person, and the model simply did what the literal
 * text said. Both spellings are accepted here, so a token a person might reasonably
 * type cannot fail to substitute.
 */
object HeartbeatPrompts {

    private val HUMAN_TOKENS = listOf("{{human_name}}", "{{human}}", "\${human}", "\${human_name}")
    private val TIME_TOKENS = listOf("{{time}}", "\${time}")

    /**
     * Replace every token in a prompt.
     *
     * Exposed because the Heartbeat room previews exactly what a beat will inject. A
     * preview that substituted tokens its own way would be a second contract, and the
     * two would drift the first time a token was added.
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
