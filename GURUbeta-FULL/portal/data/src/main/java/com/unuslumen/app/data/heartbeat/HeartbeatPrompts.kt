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
    private val IDLE_TOKEN = "{{idle_beats}}"
    private val SILENT_TOKEN = "{{silent_for}}"
    private val LAST_NOTE_TOKEN = "{{last_beat_note}}"

    /**
     * The facts about this beat that the prompt is allowed to reason from.
     *
     * WHY THIS EXISTS AT ALL (issue 327). A heartbeat prompt is the only instruction an owner can
     * give a scheduled beat, and until this existed it was written blind. The substitution knew
     * who it was talking to and what the time was, and nothing else, so every question worth
     * asking in a prompt was unanswerable from inside one: how long since they spoke, whether the
     * last beat already notified them, whether anything is booked for this half hour.
     *
     * The result was prompts written as vibes — "be productive", "be curious" — which a model
     * cannot fail, and therefore cannot follow either. It improvises, and improvisation under
     * uncertainty has two failure modes: silence forever, or busywork that looks like initiative.
     *
     * WHY A COUNT AND NOT A TIMESTAMP COMPARISON. A beat cannot trust its own reading of when the
     * human last spoke, because that knowledge lives in conversation history and history is
     * compacted away. An integer computed by the host is a fact; a comparison the model performs
     * against its own memory is an inference, and inferences about elapsed time are the first
     * thing compaction destroys.
     *
     * THE DEFAULTS MATTER AS MUCH AS THE FIELDS. `substitute` keeps a default parameter so every
     * existing caller compiles and behaves identically, because `HeartbeatViewModel.previewFor()`
     * is on the keystroke path and must not start doing database reads to keep up.
     */
    data class BeatContext(
        /** Consecutive beats fired since the human last sent a message. Zero means they just spoke. */
        val idleBeats: Int = 0,
        /** The gap since the last human message in plain English, or empty when unknown. */
        val silentFor: String = "",
        /** What the previous beat recorded it did, or empty when there was no previous beat. */
        val lastBeatNote: String = "",
    )

    /**
     * Replace every token in a prompt.
     *
     * Exposed because the Heartbeat room previews exactly what a beat will inject. A
     * preview that substituted tokens its own way would be a second contract, and the
     * two would drift the first time a token was added.
     *
     * The context defaults to a zeroed [BeatContext] so a preview substitutes a real `0` and a
     * real empty string rather than leaving the literal token visible. That is deliberate: a
     * preview showing `{{idle_beats}}` would read as a broken token, and the preview's whole job
     * is to show a person what a beat will actually receive.
     */
    fun substitute(
        raw: String,
        humanName: String,
        context: BeatContext = BeatContext(),
    ): String {
        val effectiveName = humanName.ifBlank { "your human" }
        var out = raw
        for (token in HUMAN_TOKENS) out = out.replace(token, effectiveName)
        val now = formatCurrentTime()
        for (token in TIME_TOKENS) out = out.replace(token, now)
        out = out.replace(IDLE_TOKEN, context.idleBeats.toString())
        out = out.replace(SILENT_TOKEN, context.silentFor)
        out = out.replace(LAST_NOTE_TOKEN, context.lastBeatNote)
        return out
    }

    /**
     * Every token this contract answers, and what a prompt can expect from it.
     *
     * Used by the room so the token list a person reads is the same list the substitution
     * implements, rather than a help panel that drifts from the code the first time one changes.
     */
    fun tokenHelp(context: BeatContext = BeatContext()): List<Pair<String, String>> = listOf(
        "{{human}}" to "the name the owner gave themselves",
        "{{human_name}}" to "the same name",
        "{{time}}" to "the current time, substituted at beat time",
        "{{idle_beats}}" to "how many beats have fired since they last spoke now reads as " +
            "${context.idleBeats}",
        "{{silent_for}}" to "how long since they last spoke now reads as " +
            (context.silentFor.ifEmpty { "nothing, because no gap is known yet" }),
        "{{last_beat_note}}" to "what the previous beat recorded it did now reads as " +
            (context.lastBeatNote.ifEmpty { "nothing, because no previous beat recorded a note" }),
    )

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
