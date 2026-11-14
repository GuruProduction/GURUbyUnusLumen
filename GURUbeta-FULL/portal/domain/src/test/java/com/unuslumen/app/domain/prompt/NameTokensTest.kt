// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.domain.prompt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue 340's token rules.
 *
 * The failure this guards against is in the codebase's own history: a shipped asset used `${human}`
 * while the loader knew only `{{human}}`, so every release injected a literal token where a name
 * belonged and nobody noticed because a model read it rather than a person.
 */
class NameTokensTest {

    @Test
    fun bothTokensAreSubstituted() {
        val prompt = "You are \${guru}, and you are talking to \${human}."
        assertEquals(
            "You are GURU, and you are talking to Steven.",
            NameTokens.substitute(prompt, humanName = "Steven", guruName = null),
        )
    }

    @Test
    fun anUnsetGuruNameIsTheNameItAlwaysHad() {
        // An install that never opens the setting must read exactly as it did before the setting
        // existed. A different default would be a silent behaviour change dressed as a fallback.
        assertEquals(
            "You are GURU.",
            NameTokens.substitute("You are \${guru}.", humanName = null, guruName = null),
        )
        assertEquals(
            "You are GURU.",
            NameTokens.substitute("You are \${guru}.", humanName = null, guruName = "   "),
        )
    }

    @Test
    fun anUnsetHumanNameFallsBackToItsOwnWordsRatherThanGurus() {
        // "GURU" for the owner would be a lie about who is speaking, so the two fallbacks are not
        // shared.
        assertEquals(
            "You are talking to your human.",
            NameTokens.substitute("You are talking to \${human}.", humanName = null, guruName = "Ada"),
        )
    }

    @Test
    fun aNameTypedWithATrailingSpaceIsTrimmed() {
        // A phone keyboard very often adds one, and "Steve " in a sentence shows a visible gap.
        assertEquals(
            "Hello Steve.",
            NameTokens.substitute("Hello \${human}.", humanName = "Steve ", guruName = null),
        )
        assertEquals(
            "I am Ada.",
            NameTokens.substitute("I am \${guru}.", humanName = null, guruName = " Ada  "),
        )
    }

    @Test
    fun bothSpellingsAreAcceptedSoAPromptAuthorCannotPickTheWrongOne() {
        val prompt = "{{human}} and {{guru}} and \${human_name} and \${guru_name}"
        val out = NameTokens.substitute(prompt, humanName = "Steven", guruName = "Ada")
        assertEquals("Steven and Ada and Steven and Ada", out)
        assertFalse(
            "no token may survive substitution: $out",
            NameTokens.hasUnsubstitutedToken(out),
        )
    }

    @Test
    fun noTokenSurvivesEvenWhenBothNamesAreUnset() {
        // The exact defect the heartbeat asset shipped with: a literal token reaching the model.
        val out = NameTokens.substitute(
            "\${human} / \${guru} / {{human}} / {{guru}}",
            humanName = null,
            guruName = null,
        )
        assertFalse(
            "an unset name must still leave no token visible: $out",
            NameTokens.hasUnsubstitutedToken(out),
        )
        assertTrue(out.contains("your human"))
        assertTrue(out.contains("GURU"))
    }

    @Test
    fun aPromptWithNoTokensIsReturnedUnchanged() {
        val prompt = "Be useful and be brief."
        assertEquals(prompt, NameTokens.substitute(prompt, "Steven", "Ada"))
    }

    @Test
    fun anEmptyPromptIsReturnedRatherThanProcessed() {
        assertEquals("", NameTokens.substitute("", "Steven", "Ada"))
    }

    @Test
    fun theLabelNameFollowsTheSameFallbackAsTheToken() {
        // A label that fell back differently from a prompt would show one name on screen and put
        // another in the text the model reads.
        assertEquals("GURU", NameTokens.guruName(null))
        assertEquals("GURU", NameTokens.guruName("  "))
        assertEquals("Ada", NameTokens.guruName(" Ada "))
        assertEquals("your human", NameTokens.humanName(null))
        assertEquals("Steven", NameTokens.humanName("Steven"))
    }

    @Test
    fun theDetectorFindsEachTokenInIsolation() {
        for (token in listOf("\${human}", "\${guru}", "{{human}}", "{{guru}}", "\${human_name}", "\${guru_name}")) {
            assertTrue("$token must be recognised as a live token", NameTokens.hasUnsubstitutedToken(token))
        }
        assertFalse(NameTokens.hasUnsubstitutedToken("GURU talking to Steven"))
    }
}
