// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.memory

/**
 * RecallSignals — the decision half of the recall feedback loop.
 *
 * WHY THIS FILE EXISTS. `InjectionLedger` shipped with a complete set of write
 * methods and no caller. The ledger's three signals were therefore constants:
 * `confirmed_used_at` was NULL on every row because nothing ever called
 * `confirmUsed`, and `restated_without_injection` was 0 everywhere because
 * `recordRecallFailure` had no caller and no table to write to. Every claim the
 * ledger made about memory quality was a claim about a table nobody wrote.
 *
 * That is not a wiring accident to be patched with one call site. The missing
 * piece is a decision: given what was injected and what came back, did a reply
 * actually USE a memory, and did a user actually RESTATE one? Putting that
 * decision in a coroutine is how it becomes untestable, so it lives here as a
 * pure function over plain data. No Android, no I/O, no clock. The ledger then
 * becomes a writer of decisions rather than a home for them.
 *
 * WHAT "USED" MEANS, AND WHY IT IS A TOKEN OVERLAP. The honest available
 * evidence for one turn is that the assistant's reply and the memory share
 * vocabulary the query did not supply. A memory whose words appear in the reply
 * but not in the question is the reply leaning on that memory. The reverse test
 * also matters: a memory sharing only words that the USER already typed proves
 * nothing, because the model was echoing its input rather than recalling.
 *
 * This is deliberately conservative. It will under-confirm, and under-counting
 * precision is the safe direction: it makes recall look worse than it is, never
 * better, so a lane cannot flatter itself by being verbose.
 */
object RecallSignals {

    /**
     * Words that carry no evidence either way.
     *
     * A reply and a memory both contain "the" and "and", so overlap on function
     * words is guaranteed and means nothing. Matching on them would confirm
     * every injected memory on every turn, which is the same as recording
     * nothing while looking like it works.
     */
    private val STOP_WORDS: Set<String> = setOf(
        "the", "and", "for", "are", "but", "not", "you", "your", "yours", "with",
        "that", "this", "these", "those", "from", "have", "has", "had", "was",
        "were", "will", "would", "can", "could", "should", "its", "it's", "they",
        "them", "their", "there", "here", "what", "when", "where", "which", "who",
        "how", "why", "all", "any", "some", "one", "two", "out", "off", "about",
        "into", "than", "then", "also", "just", "like", "more", "most", "very",
        "only", "over", "such", "been", "being", "does", "did", "doing", "done",
        "get", "got", "gets", "getting", "make", "made", "makes", "use", "used",
        "uses", "using", "way", "ways", "thing", "things", "something", "anything",
        "everything", "nothing", "now", "new", "old", "see", "saw", "seen", "say",
        "said", "says", "tell", "told", "tells", "know", "knew", "knows", "think",
        "thought", "want", "wanted", "wants", "need", "needed", "needs", "let",
        "lets", "help", "helps", "helped", "okay", "ok", "yes", "yeah", "sure",
        "well", "still", "even", "back", "down", "much", "many", "own", "same",
        "other", "another", "each", "every", "both", "few", "less", "least",
    )

    /**
     * A memory must share at least this many distinct non-trivial words with a
     * reply before it counts as used.
     *
     * WHY TWO AND NOT ONE. A single shared word is indistinguishable from
     * coincidence on a reply of any length, and confirming on it would make
     * precision a measure of how many nouns the reply happens to contain. Two
     * distinct content words appearing in both a specific memory and a specific
     * reply is a real signal; one is noise.
     */
    private const val MIN_SHARED_DISTINCT_WORDS = 2

    /**
     * Words shorter than this are dropped before comparison.
     *
     * Two-letter tokens are overwhelmingly grammar ("is", "to", "of", "in"),
     * and a two-letter technical token is short enough that incidental overlap
     * is likely. Four is the shortest length at which a shared token usually
     * names the same thing in both places.
     */
    private const val MIN_TOKEN_LENGTH = 4

    /**
     * The memory ids a reply demonstrably used.
     *
     * [injected] is every memory that reached the preamble this turn, with its
     * body. [assistantReply] is the text the model produced. [userQuery] is what
     * the human typed, used to discount vocabulary the model was simply handed
     * back to itself.
     *
     * Returns ids, not counts: the caller records one confirmation per id, and a
     * set keeps a memory that appears twice in a preamble from being confirmed
     * twice.
     */
    fun confirmationsFor(
        injected: List<InjectedMemory>,
        assistantReply: String,
        userQuery: String,
    ): Set<String> {
        if (injected.isEmpty() || assistantReply.isBlank()) return emptySet()

        val replyTokens = contentTokens(assistantReply)
        if (replyTokens.size < MIN_SHARED_DISTINCT_WORDS) return emptySet()

        // Vocabulary the query already supplied. A reply repeating a query word
        // is echoing the question, so that word is no evidence of recall.
        val queryTokens = contentTokens(userQuery)

        val confirmed = LinkedHashSet<String>()
        for (memory in injected) {
            val memoryTokens = contentTokens(memory.content)
            if (memoryTokens.size < MIN_SHARED_DISTINCT_WORDS) continue

            val shared = memoryTokens.intersect(replyTokens) - queryTokens
            if (shared.size >= MIN_SHARED_DISTINCT_WORDS) {
                confirmed.add(memory.memoryId)
            }
        }
        return confirmed
    }

    /**
     * The stored memories a user's new message RESTATES.
     *
     * This is the recall-failure signal: a fact exists, the preamble did not put
     * it in front of the model, and the human then says the thing anyway. The
     * human produced ground truth by accident, which is why this is the most
     * valuable row the ledger can hold.
     *
     * Deliberately stricter than [confirmationsFor] on one point: a restatement
     * must share at least [MIN_SHARED_DISTINCT_WORDS] words with the CANDIDATE
     * MEMORY, and it is measured against the user's words directly rather than
     * against a generated reply. Nothing about it depends on what the model
     * said, because the point of the signal is that the model did not say it.
     *
     * [alreadyInjectedIds] are excluded: a memory the preamble did put in front
     * of the model was not missed, and recording it as a miss would be a false
     * failure that damages the measurement.
     */
    fun restatementsFor(
        userMessage: String,
        candidates: List<InjectedMemory>,
        alreadyInjectedIds: Set<String>,
    ): List<InjectedMemory> {
        if (userMessage.isBlank() || candidates.isEmpty()) return emptyList()

        val messageTokens = contentTokens(userMessage)
        if (messageTokens.size < MIN_SHARED_DISTINCT_WORDS) return emptyList()

        return candidates.filter { candidate ->
            if (candidate.memoryId in alreadyInjectedIds) return@filter false
            val memoryTokens = contentTokens(candidate.content)
            if (memoryTokens.isEmpty()) return@filter false
            memoryTokens.intersect(messageTokens).size >= MIN_SHARED_DISTINCT_WORDS
        }
    }

    /**
     * The words in [text] that carry evidence: lowercased, stripped of
     * punctuation, longer than [MIN_TOKEN_LENGTH] and not a stop word.
     *
     * A set, because repetition inside one memory is not extra evidence and
     * would otherwise let a wordy memory confirm itself.
     */
    fun contentTokens(text: String): Set<String> {
        if (text.isBlank()) return emptySet()
        val tokens = LinkedHashSet<String>()
        var index = 0
        val length = text.length
        val current = StringBuilder()
        while (index <= length) {
            val atEnd = index == length
            val char = if (atEnd) ' ' else text[index]
            val isWordChar = !atEnd && (char.isLetterOrDigit() || char == '_' || char == '\'')
            if (isWordChar) {
                current.append(char.lowercaseChar())
            } else if (current.isNotEmpty()) {
                val token = current.toString().trim('\'')
                if (token.length >= MIN_TOKEN_LENGTH && token !in STOP_WORDS) {
                    tokens.add(token)
                }
                current.setLength(0)
            }
            index++
        }
        return tokens
    }
}
