// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue 345's robots rules, against the file shapes real sites use.
 */
class RobotsTxtTest {

    @Test
    fun theMostSpecificGroupWinsOverTheWildcard() {
        // RFC 9309: a block naming us applies instead of the wildcard, even when it is more
        // permissive. Obeying the wildcard because it happens to disallow more would be following
        // a rule addressed to somebody else.
        val policy = RobotsTxt.parse(
            """
            User-agent: *
            Disallow: /

            User-agent: guru
            Disallow: /private
            """.trimIndent(),
        )
        assertTrue("the wildcard's Disallow / must not apply to us", RobotsTxt.permits(policy, "/anything"))
        assertFalse("our own rule does", RobotsTxt.permits(policy, "/private/page"))
    }

    @Test
    fun severalAgentLinesShareOneRuleSet() {
        val policy = RobotsTxt.parse(
            """
            User-agent: otherbot
            User-agent: guru
            Disallow: /nope
            """.trimIndent(),
        )
        assertFalse(RobotsTxt.permits(policy, "/nope"))
    }

    @Test
    fun aVersionedAgentTokenNamesTheSameAgent() {
        assertTrue(RobotsTxt.agentNames("guru"))
        assertTrue(RobotsTxt.agentNames("GURU"))
        assertTrue(RobotsTxt.agentNames("guru/1.0"))
        assertFalse("guru-other is a different agent", RobotsTxt.agentNames("guru-other"))
    }

    @Test
    fun aDisallowedPrefixBlocksEverythingBeneathIt() {
        val policy = RobotsTxt.parse("User-agent: guru\nDisallow: /admin")
        assertTrue(RobotsTxt.permits(policy, "/public"))
        assertFalse("a prefix blocks the path itself", RobotsTxt.permits(policy, "/admin"))
        assertFalse("and everything under it", RobotsTxt.permits(policy, "/admin/users"))
        /*
         * AND IT BLOCKS A PATH THAT MERELY SHARES THE LETTERS, which surprises people and is
         * nevertheless the specified behaviour: a robots pattern is a LITERAL prefix, not a path
         * segment. So `Disallow: /admin` really does stop `/administrator-guide`.
         *
         * This was written as an assertion the other way and the test caught it. Erring towards
         * obeying a site's stated wish is the safe direction, and a site that means one directory
         * writes `Disallow: /admin/`.
         */
        assertFalse(
            "a robots prefix is literal characters, not a path segment",
            RobotsTxt.permits(policy, "/administrator-guide"),
        )
        assertTrue(
            "which is why a site wanting one directory writes the trailing slash",
            RobotsTxt.permits(RobotsTxt.parse("User-agent: *\nDisallow: /admin/"), "/administrator-guide"),
        )
    }

    @Test
    fun anEmptyDisallowAllowsEverything() {
        // `Disallow:` with no value is the standard way of saying "crawl freely", and treating it
        // as a block would refuse the whole site.
        val policy = RobotsTxt.parse("User-agent: *\nDisallow:")
        assertTrue(RobotsTxt.permits(policy, "/anything"))
    }

    @Test
    fun theLongerAllowRuleWins() {
        // The shape a site uses to keep an archive public while closing the rest.
        val policy = RobotsTxt.parse(
            """
            User-agent: *
            Disallow: /
            Allow: /pub/
            """.trimIndent(),
        )
        assertFalse(RobotsTxt.permits(policy, "/private"))
        assertTrue(
            "the allow rule is longer, so it wins for paths under it",
            RobotsTxt.permits(policy, "/pub/report.pdf"),
        )
    }

    @Test
    fun theWildcardMatchesAnyRun() {
        val policy = RobotsTxt.parse("User-agent: *\nDisallow: /*.pdf$")
        assertFalse("a pdf is blocked", RobotsTxt.permits(policy, "/docs/report.pdf"))
        assertTrue("a page is not", RobotsTxt.permits(policy, "/docs/report.html"))
        assertTrue(
            "and a pdf not at the end of the path is a different path",
            RobotsTxt.permits(policy, "/docs/report.pdf.html"),
        )
    }

    @Test
    fun aDollarAnchorsTheEndOfThePath() {
        assertTrue(RobotsTxt.matches("/exact$", "/exact"))
        assertFalse("an anchored pattern does not match a longer path", RobotsTxt.matches("/exact$", "/exact/more"))
        assertTrue(
            "without the anchor it is a prefix",
            RobotsTxt.matches("/exact", "/exact/more"),
        )
    }

    @Test
    fun aRegexMetacharacterInAPathIsLiteral() {
        // A robots pattern is not a regular expression. A `+` in a query string must not become a
        // quantifier, and a `?` must not become "optional".
        assertTrue(RobotsTxt.matches("/a+b", "/a+b"))
        assertFalse("a literal plus is not a quantifier", RobotsTxt.matches("/a+b", "/aab"))
        assertTrue(RobotsTxt.matches("/p?x=1", "/p?x=1"))
        assertFalse("a literal question mark is not optional", RobotsTxt.matches("/p?x=1", "/px=1"))
    }

    @Test
    fun crawlDelayIsRead() {
        val policy = RobotsTxt.parse("User-agent: *\nCrawl-delay: 10\nDisallow: /x")
        assertEquals(10, policy.crawlDelaySeconds)
        assertEquals(
            "a site's own delay wins over the floor",
            10_000L,
            RobotsTxt.intervalMillis(policy),
        )
    }

    @Test
    fun aSiteThatSaysNothingStillGetsTheFloor() {
        val policy = RobotsTxt.parse("User-agent: *\nDisallow: /x")
        assertNull(policy.crawlDelaySeconds)
        assertEquals(
            "the floor exists for exactly this case",
            RobotsTxt.DEFAULT_FLOOR_MS,
            RobotsTxt.intervalMillis(policy),
        )
    }

    @Test
    fun aSiteAskingForAFastRateCannotLowerTheFloor() {
        val policy = RobotsTxt.parse("User-agent: *\nCrawl-delay: 0")
        assertEquals(
            "a site asking for no delay is not evidence that hammering is safe",
            RobotsTxt.DEFAULT_FLOOR_MS,
            RobotsTxt.intervalMillis(policy),
        )
    }

    @Test
    fun commentsAndBlankLinesAreIgnored() {
        val policy = RobotsTxt.parse(
            """
            # a comment
            User-agent: *    # trailing comment

            Disallow: /secret
            """.trimIndent(),
        )
        assertFalse(RobotsTxt.permits(policy, "/secret"))
        assertTrue(RobotsTxt.permits(policy, "/public"))
    }

    @Test
    fun anUnknownPolicyPermitsButIsNotTheSameAsAnAllowAll() {
        assertTrue(
            "an absent file is not a refusal, per RFC 9309",
            RobotsTxt.permits(RobotsTxt.Policy.UNKNOWN, "/anything"),
        )
        assertFalse(
            "but it must be distinguishable from a file that was read",
            RobotsTxt.Policy.UNKNOWN.known,
        )
        assertTrue(RobotsTxt.parse("").known)
    }

    @Test
    fun theUserAgentNamesTheProductAndCarriesNoPerInstallDetail() {
        // The privacy cost is a smaller crowd sharing one string, so the string must be the ONLY
        // thing that varies. Any per-install component would turn that into a fingerprint.
        assertTrue("it names GURU", RobotsTxt.USER_AGENT.contains("GURU"))
        assertTrue(
            "and carries a contact URL, so an operator can reach us",
            RobotsTxt.USER_AGENT.contains("unuslumen.com"),
        )
        assertFalse(
            "it must not claim to be Chrome",
            RobotsTxt.USER_AGENT.contains("Chrome"),
        )
        for (leak in listOf("Build", "Android 1", "Mozilla")) {
            assertFalse(
                "$leak in the user agent would add a distinguishing mark: ${RobotsTxt.USER_AGENT}",
                RobotsTxt.USER_AGENT.contains(leak),
            )
        }
    }

    @Test
    fun sitemapsAreCollected() {
        val policy = RobotsTxt.parse(
            """
            User-agent: *
            Disallow:

            Sitemap: https://example.com/sitemap.xml
            """.trimIndent(),
        )
        assertEquals(listOf("https://example.com/sitemap.xml"), policy.sitemapUrls)
    }

    @Test
    fun aPathWithoutALeadingSlashIsStillTreatedAsARootPath() {
        // A caller building a path from a URI may or may not include the slash, and the rule must
        // not depend on which.
        val policy = RobotsTxt.parse("User-agent: *\nDisallow: /private")
        assertFalse(RobotsTxt.permits(policy, "private/page"))
    }
}
