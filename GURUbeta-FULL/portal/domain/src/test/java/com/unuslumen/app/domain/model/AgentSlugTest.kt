// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the slug rule in issue #233.
 *
 * THE DEFECT was that a saved agent's slug was the empty string, because the create path
 * hardcoded it. That was survivable only because spawn falls back to matching the name,
 * so a created agent still ran — which is exactly why it went unnoticed. It stops being
 * survivable the moment anything keys on the slug, and the server's `slug NOT NULL UNIQUE`
 * means an empty one can never publish.
 *
 * THE SHAPE TESTED HERE IS THE SERVER'S. The submit route refuses anything that is not
 * lowercase ascii words joined by single dashes, so a slug this class produces must be
 * acceptable there or a person is told their agent is invalid for a field they never saw.
 */
class AgentSlugTest {

    /** The reported case: a person types a plain name and gets a real identity. */
    @Test
    fun aPlainNameBecomesADashedSlug() {
        assertEquals("coding-agent", AgentSlug.from("coding agent"))
    }

    /** The name from the issue, exactly. */
    @Test
    fun theReportedAgentNameSlugifies() {
        assertEquals("coding-agent", AgentSlug.from("coding agent"))
        assertNotEquals(
            "the defect was an empty slug; deriving one must never produce that",
            "",
            AgentSlug.from("coding agent"),
        )
    }

    /** A slug is never empty, whatever the name was. */
    @Test
    fun aNameWithNoUsableCharacterFallsBack() {
        assertEquals(AgentSlug.FALLBACK, AgentSlug.from(""))
        assertEquals(AgentSlug.FALLBACK, AgentSlug.from("   "))
        assertEquals(AgentSlug.FALLBACK, AgentSlug.from("日本語"))
        assertEquals(AgentSlug.FALLBACK, AgentSlug.from("!!!"))
    }

    /** The shape the catalogue accepts: lowercase, dashes, no doubled or edge dashes. */
    @Test
    fun theSlugIsTheShapeTheCatalogueAccepts() {
        val cases = listOf(
            "Coding Agent" to "coding-agent",
            "  my   agent  " to "my-agent",
            "Agent!!!" to "agent",
            "agent--two" to "agent-two",
            "Ünïcödé Agent" to "n-c-d-agent",
            "-leading and trailing-" to "leading-and-trailing",
        )
        for ((name, expected) in cases) {
            assertEquals("'$name' did not slugify as expected", expected, AgentSlug.from(name))
        }
    }

    /** Every produced slug fits the catalogue's character set and has no edge dashes. */
    @Test
    fun everySlugIsValidForTheCatalogue() {
        val names = listOf(
            "coding agent", "  my   agent  ", "Agent!!!", "agent--two",
            "Ünïcödé Agent", "-leading-", "a", "A B C", "test 123", "日本語 agent",
        )
        val pattern = Regex("^[a-z0-9]+(-[a-z0-9]+)*$")
        for (name in names) {
            val slug = AgentSlug.from(name)
            assertTrue(
                "'$name' produced '$slug', which the catalogue would refuse: it must be " +
                    "lowercase ascii words joined by single dashes",
                pattern.matches(slug),
            )
            assertTrue("'$slug' must not be empty", slug.isNotEmpty())
            assertTrue("'$slug' exceeds the length limit", slug.length <= AgentSlug.MAX_LENGTH)
        }
    }

    /** A long name is cut to the limit without leaving a trailing dash. */
    @Test
    fun aLongNameIsCutWithoutATrailingDash() {
        val slug = AgentSlug.from("a".repeat(50) + " " + "b".repeat(50))
        assertTrue("the slug must fit the catalogue limit", slug.length <= AgentSlug.MAX_LENGTH)
        assertTrue("a cut must never leave a trailing dash", !slug.endsWith("-"))
        assertTrue("a cut must never leave a leading dash", !slug.startsWith("-"))
    }

    // ------------------------------------------------------------------
    // Uniqueness
    // ------------------------------------------------------------------

    /**
     * Two agents with the same name must not share a slug.
     *
     * The server enforces `slug UNIQUE`, so a duplicate is refused at publish time with a
     * message about a field the person never saw. Resolving it at save time means both
     * exist and both can publish.
     */
    @Test
    fun theSameNameTwiceGetsDistinctSlugs() {
        val first = AgentSlug.unique("coding agent", emptyList())
        val second = AgentSlug.unique("coding agent", listOf(first))

        assertEquals("coding-agent", first)
        assertEquals("coding-agent-2", second)
        assertNotEquals("a shared slug is a collision the server refuses", first, second)
    }

    /** A third agent with the same name continues the sequence rather than repeating. */
    @Test
    fun aThirdAgentContinuesTheSequence() {
        val taken = mutableListOf("coding-agent", "coding-agent-2")
        val third = AgentSlug.unique("coding agent", taken)
        assertEquals("coding-agent-3", third)
        assertTrue("the new slug must not already be in use", third !in taken)
    }

    /** Uniqueness is case-insensitive, because the catalogue's slugs are lowercase. */
    @Test
    fun uniquenessIgnoresCaseInWhatIsAlreadyTaken() {
        assertEquals(
            "a stored 'Coding-Agent' occupies the same identity as 'coding-agent'",
            "coding-agent-2",
            AgentSlug.unique("coding agent", listOf("Coding-Agent")),
        )
    }

    /** A free name is used exactly, with no gratuitous suffix. */
    @Test
    fun aFreeNameIsUsedExactly() {
        assertEquals("coding-agent", AgentSlug.unique("coding agent", listOf("other-agent", "third")))
    }

    /** Blank entries in the taken set are ignored rather than treated as a name. */
    @Test
    fun blankSlugsInTheTakenSetAreIgnored() {
        assertEquals("coding-agent", AgentSlug.unique("coding agent", listOf("", "   ")))
    }

    /** A suffixed slug still fits the catalogue limit, even from a name at the limit. */
    @Test
    fun aSuffixedSlugStillFitsTheLimit() {
        val longName = "a".repeat(AgentSlug.MAX_LENGTH)
        val base = AgentSlug.from(longName)
        val second = AgentSlug.unique(longName, listOf(base))
        assertTrue(
            "'$second' is ${second.length} characters and would be refused by the catalogue",
            second.length <= AgentSlug.MAX_LENGTH,
        )
        assertTrue("the suffix must still be there", second.endsWith("-2"))
        assertTrue("a cut must never leave a trailing dash before the suffix", !second.contains("--"))
    }
}
