// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.domain.model

/**
 * How the three beat facts are computed from what the host already holds.
 *
 * WHY THIS IS SEPARATE FROM THE SUBSTITUTION (issue 327). `HeartbeatPrompts` decides what a token
 * is replaced WITH; this decides what the values ARE. Keeping them apart means the counting rule
 * can be proved on the JVM with a list of timestamps rather than by scheduling half-hour alarms and
 * waiting, which is the only other way to observe it.
 *
 * THE HARD PART IS THE ANSWER TO "HOW LONG SINCE THEY SPOKE". A beat cannot trust its own reading of
 * that, because the knowledge lives in conversation history and history is compacted away. So the
 * gap is computed here, by the host, from timestamps it holds in the database, and handed to the
 * prompt as a finished phrase. An integer and a phrase are facts. A model comparing "now" against a
 * remembered time is an inference, and inferences about elapsed time are the first thing a
 * compaction destroys.
 */
object BeatContextDerivation {

    /**
     * The idle beat count.
     *
     * DEFINITION, chosen for what a prompt can act on: the number of half-hour slots that have
     * passed since the human last sent a message. So "idle_beats: 0" means they spoke in the slot
     * the beat woke in, and "idle_beats: 6" means three hours of GURU talking to itself.
     *
     * WHY SLOTS AND NOT FIRED BEATS READ FROM A COUNTER. A counter would have to be incremented in
     * the one place that fires, which means it survives a missed beat that never ran and reads
     * zero after a reinstall. Slots are arithmetic over two timestamps the database already holds,
     * so they cannot drift from reality and need no write at all.
     *
     * A last-spoken time in the future (a clock change, a bad row) is treated as the current slot
     * rather than a negative count, because a negative idle count would be a nonsense in a prompt.
     */
    fun idleBeats(
        nowMillis: Long,
        lastHumanMessageAt: Long?,
        zone: java.util.TimeZone = java.util.TimeZone.getDefault(),
    ): Int {
        if (lastHumanMessageAt == null || lastHumanMessageAt <= 0L) {
            // Nothing has ever been said. There is no gap to measure, so the count is zero rather
            // than an invented number: the prompt can still ask why nobody has spoken.
            return 0
        }
        val nowSlot = HeartbeatSlot.floor(nowMillis, zone)
        val spokenSlot = HeartbeatSlot.floor(lastHumanMessageAt, zone)
        val slots = ((nowSlot - spokenSlot) / HeartbeatSlot.SLOT_MILLIS).toInt()
        return slots.coerceAtLeast(0)
    }

    /**
     * "3 hours 20 minutes", or an empty string when no human message is known.
     *
     * WHY PLAIN ENGLISH AND NOT A NUMBER. The prompt is read by a model that will repeat it to a
     * person. "3 hours 20 minutes" survives that journey unchanged; "12000 seconds" becomes "a lot
     * of seconds" somewhere along the way.
     *
     * THE UNITS ARE CHOSEN FOR READABILITY, NOT PRECISION. Seconds are dropped once there are
     * minutes, and minutes are dropped once there are days, because a prompt asking "has it been a
     * while" does not need to know that it has been 3 hours 20 minutes and 4 seconds. A gap under
     * a minute reads "less than a minute" rather than "0 minutes", which would invite a model to
     * conclude nothing has happened.
     */
    fun silentFor(nowMillis: Long, lastHumanMessageAt: Long?): String {
        if (lastHumanMessageAt == null || lastHumanMessageAt <= 0L) return ""
        val gap = nowMillis - lastHumanMessageAt
        if (gap < 0L) return "less than a minute"
        val minutes = gap / 60_000L
        if (minutes < 1L) return "less than a minute"

        val days = minutes / (24 * 60)
        val hours = (minutes % (24 * 60)) / 60
        val mins = minutes % 60

        val parts = mutableListOf<String>()
        if (days > 0) parts.add(plural(days, "day"))
        if (hours > 0) parts.add(plural(hours, "hour"))
        // Minutes are dropped when there are days: "2 days 0 minutes" is noise.
        if (mins > 0 && days == 0L) parts.add(plural(mins, "minute"))
        return if (parts.isEmpty()) "about an hour" else parts.joinToString(" ")
    }

    private fun plural(value: Long, unit: String): String =
        if (value == 1L) "1 $unit" else "$value ${unit}s"

    /**
     * What the previous beat recorded it did, trimmed for a prompt.
     *
     * TRIMMED, because this text is concatenated into a prompt and an unbounded note would push
     * the instruction itself out of the model's attention. 400 characters is roughly two sentences,
     * which is enough for "sent a notification about the build failing" and not enough to become a
     * second prompt.
     *
     * An empty outcome returns an empty string rather than a placeholder, because the token
     * substituting to nothing is the honest answer to "what did the last beat do" when the last
     * beat did nothing worth recording. A prompt can check for emptiness; it cannot act on
     * "unknown".
     */
    fun lastBeatNote(previousOutcome: String?, limit: Int = 400): String {
        val trimmed = previousOutcome?.trim().orEmpty()
        if (trimmed.isEmpty()) return ""
        return if (trimmed.length <= limit) trimmed else trimmed.take(limit - 1) + "\u2026"
    }
}
