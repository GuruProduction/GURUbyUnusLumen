package com.unuslumen.app.domain.slashcommands

/**
 * Rough context-window token estimation for slash command reporting. Matches
 * the data layer's ContextManager estimate exactly (chars / 4 padded 4/3) so
 * /usage and the auto-compact trigger never disagree.
 */
object TokenEstimator {

    /** Padded chars-per-token factor, matching ContextManager. */
    private const val CHARS_PER_TOKEN = 4

    /**
     * Estimate the total context tokens of a conversation from its parts.
     * [charCounter] lets the caller feed exactly the strings ContextManager
     * counts, so both estimators never drift.
     */
    fun estimate(countedChars: List<Int>): Int {
        val totalChars = countedChars.sum()
        // Pad by 4/3 to be conservative (matches ContextManager)
        return (totalChars / CHARS_PER_TOKEN) * 4 / 3
    }
}