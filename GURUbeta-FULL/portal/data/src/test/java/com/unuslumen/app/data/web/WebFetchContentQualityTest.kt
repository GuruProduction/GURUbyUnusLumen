// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #5: webFetch reported success for hollow responses.
 *
 * The cases here are the real ones from the report, not invented inputs.
 */
class WebFetchContentQualityTest {

    @Test
    fun `empty body is hollow`() {
        assertNotNull(WebFetchContentQuality.hollownessOf(""))
        assertFalse(WebFetchContentQuality.isUsable(""))
    }

    @Test
    fun `whitespace only is hollow`() {
        assertNotNull(WebFetchContentQuality.hollownessOf("   \n\t  \n "))
    }

    /**
     * The exact Google redirect shell named in the issue. It arrived with
     * success = true and was treated as a real page.
     */
    @Test
    fun `google redirect shell is hollow`() {
        val shell = "Please click here if you are not redirected. If you're having trouble accessing Google Search, please click here."
        val reason = WebFetchContentQuality.hollownessOf(shell)
        assertNotNull("the google redirect shell must not count as content", reason)
        assertTrue("reason should name the gate, was: $reason", reason!!.contains("gate"))
    }

    @Test
    fun `consent wall is hollow`() {
        val wall = "We value your privacy. Before you continue, accept all cookies to proceed to the site."
        assertNotNull(WebFetchContentQuality.hollownessOf(wall))
    }

    @Test
    fun `javascript notice is hollow`() {
        val notice = "You need to enable JavaScript to run this app."
        assertNotNull(WebFetchContentQuality.hollownessOf(notice))
    }

    @Test
    fun `bot challenge is hollow`() {
        val challenge = "Checking your browser before accessing the site."
        assertNotNull(WebFetchContentQuality.hollownessOf(challenge))
    }

    /**
     * The other real case: lumentus.com returned success with content "".
     */
    @Test
    fun `empty success from the report is hollow`() {
        assertNotNull(WebFetchContentQuality.hollownessOf(""))
    }

    @Test
    fun `real page content is usable`() {
        val real = "Example Domain. This domain is for use in illustrative examples in documents. " +
            "You may use this domain in literature without prior coordination or asking for permission."
        assertNull("a real page must pass", WebFetchContentQuality.hollownessOf(real))
        assertTrue(WebFetchContentQuality.isUsable(real))
    }

    @Test
    fun `short but genuine page is usable`() {
        val terse = "Not found. The page you requested does not exist on this server, sorry about that."
        assertNull(WebFetchContentQuality.hollownessOf(terse))
    }

    /**
     * The guard that keeps the marker list from being a blunt instrument: a
     * long article about cookies or JavaScript is content, not a wall.
     */
    @Test
    fun `long article that mentions cookies is still usable`() {
        // A real article is well past a thousand characters. The first version
        // of this fixture was 359 and rightly failed: at that length the text is
        // honestly indistinguishable from a consent banner, which is the exact
        // ambiguity the threshold exists for.
        val article = "A history of the cookie. " +
            "The cookie was invented in 1994 by Lou Montulli at Netscape, who needed a way for a " +
            "shopping basket to survive between page loads. Accept all cookies became the industry's " +
            "standard phrasing decades later, long after the mechanism itself had settled into the " +
            "browser. This article traces that history through the standards process, from the first " +
            "Set-Cookie header through RFC 6265 and on to the privacy regulations that turned a " +
            "shopping cart convenience into a legal obligation. Along the way it covers the third-party " +
            "cookie, the rise of tracking, the collapse of the advertising model that depended on it, " +
            "and why every website in Europe now opens with a banner asking the same question in a " +
            "slightly different font. The people who built the original mechanism have said in " +
            "interviews that none of this was anticipated, and that the banner is a symptom of a " +
            "model that never expected to be used the way it was used. The article closes by asking " +
            "whether consent, as currently implemented, informs anyone at all."
        assertNull("a long body mentioning the marker words must not be flagged", WebFetchContentQuality.hollownessOf(article))
        assertTrue(
            "the fixture must be long enough to exercise the guard",
            WebFetchContentQuality.visibleText(article).length > WebFetchContentQuality.MARKER_SCAN_LIMIT
        )
    }

    @Test
    fun `empty markup with no text is hollow`() {
        val markupOnly = "<div class=\"wrapper\"><span></span><main></main></div>"
        assertNotNull(WebFetchContentQuality.hollownessOf(markupOnly))
    }

    @Test
    fun `html wrapped real content is usable`() {
        val html = "<div><p>GURU runs a language model on an Android phone with real access to the " +
            "device, which is a genuinely different proposition from a chat window in a browser.</p></div>"
        assertNull(WebFetchContentQuality.hollownessOf(html))
    }

    @Test
    fun `visible text strips tags and collapses whitespace`() {
        val html = "<p>Hello</p>\n\n   <p>World</p>"
        assertEquals("Hello World", WebFetchContentQuality.visibleText(html))
    }

    @Test
    fun `nbsp is treated as a space not as text`() {
        val padded = "&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;"
        assertNotNull("a body of only non-breaking spaces is empty, not content", WebFetchContentQuality.hollownessOf(padded))
    }
}
