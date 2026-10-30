// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.web

/**
 * Decides whether a fetched body is a real page or a hollow response.
 *
 * THE DEFECT THIS EXISTS FOR (issue #5). webFetch reported success for every
 * response that was not a network exception. A redirect shell, a consent gate
 * or a JavaScript notice therefore came back as `success = true` with
 * `error = null`, indistinguishable from a real fetch, and the model built work
 * on nothing. The fix is not to make those pages load; it is to stop claiming
 * they did.
 *
 * Deliberately pure and free of Android types so it can be unit tested. The
 * previous version lived as a private method inside WebFetchService, which
 * needs a TorManager and a Context to construct, so the one piece of logic that
 * decides "is this page real" could only ever be checked by hand.
 */
object WebFetchContentQuality {

    /**
     * Below this many characters of visible text, whatever came back is a shell
     * rather than a page. Kept low on purpose: a genuinely terse page should
     * still pass, and the question here is "is there anything here", not "is
     * this page interesting".
     */
    const val MIN_USABLE_CONTENT = 40

    /**
     * A body longer than this is treated as real content even if it mentions a
     * gate phrase, because a genuine article may legitimately discuss cookies
     * or JavaScript while a wall is short.
     *
     * Six hundred is chosen against real examples rather than by taste. The
     * Google redirect shell from issue #5 is about 120 characters. Consent
     * banners and JavaScript notices are typically a sentence or two. A real
     * article is well past a thousand. Anything between is genuinely ambiguous,
     * and for ambiguous cases this errs toward "content" because throwing away
     * a real page is the worse failure.
     */
    const val MARKER_SCAN_LIMIT = 600

    /**
     * Phrases that mean the response is a gate. ONLY consulted for bodies
     * shorter than MARKER_SCAN_LIMIT, which is the guard that stops a long
     * article from being mistaken for a consent wall. Getting this wrong was
     * caught by the unit test before it shipped.
     */
    private val HOLLOW_MARKERS = listOf(
        "please click here if you are not redirected",
        "if you're having trouble accessing",
        "enable javascript",
        "javascript is required",
        "javascript must be enabled",
        "accept all cookies",
        "we value your privacy",
        "before you continue",
        "checking your browser",
        "verify you are human",
        "just a moment",
        "attention required",
        "access denied"
    )

    /**
     * Why a body is hollow, or null when there is usable content.
     *
     * The input may be cleaned HTML (as Jsoup returns) or plain text (as the
     * WebView returns), so tags are stripped before measuring.
     */
    fun hollownessOf(rawContent: String): String? {
        val text = visibleText(rawContent)

        if (text.isEmpty()) return "the body was empty"
        if (text.length < MIN_USABLE_CONTENT) {
            return "only ${text.length} characters of text came back, which is a shell rather than a page"
        }

        // Only short bodies are scanned for gate phrases. A long body is
        // content by definition here: an article about privacy law will contain
        // "accept all cookies" and must not be thrown away for it.
        if (text.length <= MARKER_SCAN_LIMIT) {
            val lower = text.lowercase()
            for (marker in HOLLOW_MARKERS) {
                if (lower.contains(marker)) {
                    return "the page is a gate, not content: it asks for \"$marker\""
                }
            }
        }
        return null
    }

    /** True when the body contains something worth handing to a caller. */
    fun isUsable(rawContent: String): Boolean = hollownessOf(rawContent) == null

    /**
     * The text a reader would see, with markup stripped and whitespace
     * collapsed, so measurement is not fooled by an empty page wrapped in a
     * large amount of markup.
     */
    fun visibleText(rawContent: String): String = rawContent
        .replace(Regex("<[^>]*>"), " ")
        .replace("&nbsp;", " ")
        .replace(Regex("\\s+"), " ")
        .trim()
}
