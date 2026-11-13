// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.tor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue 336's honesty requirement, held as tests.
 *
 * The ticket's minimum to ship is that the protection stops being described as unconditional. A
 * test on that property is the only way it stays true as tools are added.
 */
class EgressBoundaryTest {

    @Test
    fun theFrameworksOwnToolsAreGuaranteed() {
        for (tool in listOf("webSearch", "webFetch", "httpGet", "httpPost", "httpPut")) {
            assertEquals(
                "$tool builds its own connection, so the route is the app's decision",
                EgressBoundary.Protection.GUARANTEED,
                EgressBoundary.protectionOf(tool),
            )
            assertTrue("and it may claim Tor", EgressBoundary.canClaimTor(tool))
        }
    }

    @Test
    fun theTwoMeasuredHolesAreConditionalNotGuaranteed() {
        // These are the issue's own two findings. Reporting either as guaranteed is the lie the
        // ticket exists to remove.
        assertEquals(
            EgressBoundary.Protection.CONDITIONAL,
            EgressBoundary.protectionOf("executePythonScript"),
        )
        assertEquals(
            EgressBoundary.Protection.CONDITIONAL,
            EgressBoundary.protectionOf("termuxExec"),
        )
        assertFalse(
            "a conditional tool must never claim its traffic went through Tor",
            EgressBoundary.canClaimTor("executePythonScript"),
        )
        assertFalse(EgressBoundary.canClaimTor("termuxExec"))
    }

    @Test
    fun anUnlistedToolIsConditionalRatherThanGuaranteed() {
        // The default is the whole safety property. A tool added tomorrow has made no claim, and
        // defaulting it to GUARANTEED would turn every future addition into a silent overstatement.
        assertEquals(
            EgressBoundary.Protection.CONDITIONAL,
            EgressBoundary.protectionOf("someToolAddedTomorrow"),
        )
    }

    @Test
    fun aConditionalSentenceSaysWhatActuallyHappens() {
        val sentence = EgressBoundary.sentenceFor("termuxExec")
        assertTrue(
            "it must say the settings are given: $sentence",
            sentence.contains("given Tor settings"),
        )
        assertTrue(
            "it must say a well-behaved program obeys: $sentence",
            sentence.contains("honours"),
        )
        assertTrue(
            "and that a program opening its own connection does not: $sentence",
            sentence.contains("opens its own network connection"),
        )
        assertTrue(
            "and that there would be no error, which is the silent part: $sentence",
            sentence.contains("without any error"),
        )
    }

    @Test
    fun aGuaranteedSentenceIsShortAndCertain() {
        assertEquals(
            "webFetch sends everything through Tor.",
            EgressBoundary.sentenceFor("webFetch"),
        )
    }

    @Test
    fun aLanToolIsNotDescribedAsProtected() {
        // "In circuit" would be a false statement about a router fetch, so the direct case has its
        // own words rather than borrowing the protected ones.
        assertEquals(EgressBoundary.Protection.DIRECT, EgressBoundary.protectionOf("networkScan"))
        val sentence = EgressBoundary.sentenceFor("networkScan")
        assertTrue(sentence.contains("local network"))
        assertFalse(
            "it must NOT claim its traffic went through Tor, and the direct sentence has to say " +
                "so in its own words rather than borrowing the protected ones: $sentence",
            sentence.startsWith("networkScan sends everything through Tor"),
        )
        assertTrue(
            "and it must say plainly that it is not sent through Tor: $sentence",
            sentence.contains("not sent through Tor"),
        )
    }

    @Test
    fun theSummaryStatesThatTheProtectionIsNotUnconditional() {
        // The ticket's own minimum: this sentence must exist, because the protection was being
        // presented as unconditional and it is not.
        val summary = EgressBoundary.summary
        assertTrue(
            "the summary must name what is not guaranteed: $summary",
            summary.contains("Python script") && summary.contains("Termux"),
        )
        assertTrue(
            "and must say the failure is silent: $summary",
            summary.contains("would not report an error"),
        )
        assertTrue(
            "and must name the real fix rather than implying configuration is enough: $summary",
            summary.contains("network layer"),
        )
    }

    @Test
    fun theProbeReadsATorAnswer() {
        // The address is a documentation range because the parser does not read it; only the
        // IsTor field decides. Putting a real exit address in a public repository would be the
        // kind of leak this whole file is about.
        assertEquals(
            EgressBoundary.ProbeVerdict.IN_CIRCUIT,
            EgressBoundary.readProbeResponse("""{"IsTor":true,"IP":"203.0.113.7"}"""),
        )
    }

    @Test
    fun theProbeReadsALeakAsALeak() {
        assertEquals(
            EgressBoundary.ProbeVerdict.LEAKED,
            EgressBoundary.readProbeResponse("""{"IsTor":false,"IP":"198.51.100.9"}"""),
        )
    }

    @Test
    fun aProbeWithNoReadableAnswerIsUnverifiableNotAPass() {
        // Two answers would repeat the defect this batch removes: a tool that returned nothing must
        // not be counted as protected.
        assertEquals(EgressBoundary.ProbeVerdict.UNVERIFIABLE, EgressBoundary.readProbeResponse(null))
        assertEquals(EgressBoundary.ProbeVerdict.UNVERIFIABLE, EgressBoundary.readProbeResponse(""))
        assertEquals(
            EgressBoundary.ProbeVerdict.UNVERIFIABLE,
            EgressBoundary.readProbeResponse("<html>captive portal</html>"),
        )
    }

    @Test
    fun theProbeIgnoresSpacingAndCase() {
        assertEquals(
            EgressBoundary.ProbeVerdict.IN_CIRCUIT,
            EgressBoundary.readProbeResponse("""{ "IsTor" : true }"""),
        )
    }

    @Test
    fun aLeakIsCalledALeakRatherThanAWarning() {
        val sentence = EgressBoundary.probeSentence("termuxExec", EgressBoundary.ProbeVerdict.LEAKED)
        assertTrue(
            "the sentence must not soften a leak into a caution: $sentence",
            sentence.contains("That is the hole"),
        )
        assertTrue(sentence.contains("own address"))
    }

    @Test
    fun anUnverifiableProbeSaysNothingIsKnown() {
        val sentence = EgressBoundary.probeSentence(
            "executePythonScript", EgressBoundary.ProbeVerdict.UNVERIFIABLE,
        )
        assertTrue(sentence.contains("could not be checked"))
        assertTrue(
            "and must not imply either answer: $sentence",
            sentence.contains("nothing about its route is known"),
        )
    }

    @Test
    fun theProbeEndpointIsTheOneTheTicketNames() {
        assertEquals("https://check.torproject.org/api/ip", EgressBoundary.TOR_CHECK_URL)
    }
}
