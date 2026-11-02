// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.tools.github

/**
 * Building a GitHub search URL, and reading the answer. Issue #235.
 *
 * WHY THIS IS ITS OWN OBJECT. Both faults in #235 were in the plumbing between a
 * caller's query string and GitHub's answer, and neither could be seen from a
 * device report alone: one produced a 422 and the other an empty success. Keeping
 * the rules here, pure and without a socket, means a JVM test pins the bytes that
 * go on the wire rather than a second copy written inside the test.
 *
 * THE ENCODING DEFECT, EXACTLY. The tool used to run `query.replace(" ", "+")`
 * and then URL-encode the result. The encoder is right to treat `+` as data, so it
 * emitted `%2B`, and GitHub read `repo:owner/repo%2Bbug` as a single search term
 * it could not resolve. The whole query was rejected as malformed. The reported
 * pattern fits that precisely: a `repo:` qualifier alone contains no space and
 * worked, a bare term has no colon and worked, and a qualifier followed by any
 * second term failed every time.
 *
 * The fix is ordering, not escaping: encode first, then turn the encoded spaces
 * into `+`. A `+` in the caller's own text survives as `%2B` and means a literal
 * plus, which is correct, and the separators become real separators.
 */
object GitHubSearchQuery {

    /** The GitHub endpoint name for a tool-level search type. */
    fun endpointFor(type: String): String = when (type) {
        "repos" -> "repositories"
        else -> type
    }

    /**
     * The query parameter value for a search string.
     *
     * `encodeURIComponent` semantics: everything except the unreserved set is
     * percent-encoded, then the encoded space (`%20`) becomes `+` because that is
     * what the search endpoint expects between terms.
     */
    fun encode(query: String): String =
        percentEncode(query.trim()).replace("%20", "+")

    /** The path plus query for a search, ready to hand to the client. */
    fun pathFor(type: String, query: String, limit: Int): String =
        "/search/${endpointFor(type)}?q=${encode(query)}&per_page=$limit"

    /**
     * What a search response means, in three distinguishable outcomes.
     *
     * A response with neither `total_count` nor `items` is a FAILURE, never an
     * empty success. That is the second half of #235: several queries answered
     * `{"success":true}` with no total and no items at all, which a caller reads
     * as "nothing matched". `GURUbyUnusLumen tvDiscover` must match at least four
     * known issues and `is:issue bug` must match tens of millions, so reporting
     * either as an empty result is a lie about what happened.
     */
    sealed interface Response {
        /**
         * A real search answer, with the body to parse.
         *
         * The total is NOT reported here, because this object does no JSON parsing:
         * the executor already has a parser and a second one here would be the kind
         * of duplicate that drifts. What this class decides is whether the body is a
         * search response at all, which is the distinction that was missing.
         */
        data class Results(val body: String) : Response

        /** The body could not be understood as a search response at all. */
        data class Malformed(val reason: String) : Response
    }

    /**
     * Classify a response body.
     *
     * The rule is deliberately about PRESENCE rather than value. `total_count: 0`
     * with an `items` array is a genuine "nothing found" and must stay a success.
     * A body missing both keys is the fault this exists to catch.
     */
    fun interpret(body: String?): Response {
        val trimmed = body?.trim().orEmpty()
        if (trimmed.isEmpty()) {
            return Response.Malformed("GitHub answered with an empty body, so the search never produced a result.")
        }
        if (!trimmed.startsWith("{")) {
            return Response.Malformed(
                "GitHub answered with something that is not a search object: ${trimmed.take(200)}",
            )
        }
        val hasTotal = Regex("\"total_count\"\\s*:").containsMatchIn(trimmed)
        val hasItems = Regex("\"items\"\\s*:").containsMatchIn(trimmed)
        if (!hasTotal && !hasItems) {
            return Response.Malformed(
                "GitHub answered without a total_count and without an items list, so this is not a " +
                    "search result and cannot be reported as 'nothing found'. Body began: " +
                    trimmed.take(200),
            )
        }
        return Response.Results(trimmed)
    }

    /**
     * Percent-encode to RFC 3986, which is what JavaScript's encodeURIComponent
     * does: the unreserved characters stay literal and everything else is escaped.
     *
     * Written out rather than delegated to URLEncoder because URLEncoder targets
     * form encoding, where a space becomes `+` and a literal `+` becomes `%2B`.
     * That is exactly the confusion this class exists to remove, so the two steps
     * are kept separate and visible.
     */
    private fun percentEncode(value: String): String {
        val unreserved = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_.~"
        val out = StringBuilder(value.length * 2)
        for (byte in value.toByteArray(Charsets.UTF_8)) {
            val char = byte.toInt() and 0xFF
            if (char < 0x80 && unreserved.indexOf(char.toChar()) >= 0) {
                out.append(char.toChar())
            } else {
                out.append('%')
                out.append(HEX[(char shr 4) and 0xF])
                out.append(HEX[char and 0xF])
            }
        }
        return out.toString()
    }

    private const val HEX = "0123456789ABCDEF"
}
