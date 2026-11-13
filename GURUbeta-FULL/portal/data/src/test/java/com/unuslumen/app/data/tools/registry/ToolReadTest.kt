// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.tools.registry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Issue 337's rule, held as tests rather than as a preference.
 *
 * The issue's own words: "a tool returns a value that a genuine result could also produce". Each
 * test below takes one way a tool can lie and asserts the type will not let it.
 */
class ToolReadTest {

    @Test
    fun aRefusalIsNeverAnEmptyList() {
        val refused = ToolReads.fromShell(
            succeeded = false,
            items = emptyList<String>(),
            tier = "APP_UID",
            refusalReason = "The ARP table was refused.",
            remedy = "Grant Shizuku, or run the read from an ADB shell.",
        )

        assertTrue(
            "a failed call must be a refusal, whatever the item count. Issue 221 is exactly this " +
                "case: a denied read with an empty list reported as a measurement.",
            refused.refused,
        )
        assertNull(
            "there must be NO list to read on a refusal, so a caller cannot reach for one and " +
                "accidentally report an absence",
            refused.values,
        )
    }

    @Test
    fun aRefusalWithPartialOutputIsStillARefusal() {
        // A command that failed can still print a banner before the denial. Counting those
        // entries is how issue 44's pmDump reported success while every line was a denial.
        val partial = ToolReads.fromShell(
            succeeded = false,
            items = listOf("WARNING: partial banner"),
            tier = "APP_UID",
            refusalReason = "pm dump was refused.",
            remedy = "Grant the permission the dump needs.",
        )
        assertTrue("a failure is a refusal even when it printed something", partial.refused)
    }

    @Test
    fun aSuccessfulReadThatFoundNothingIsAnAnswer() {
        val empty = ToolReads.fromShell(
            succeeded = true,
            items = emptyList<String>(),
            tier = "APP_UID",
            refusalReason = "unused",
            remedy = "unused",
        )
        assertEquals("looked and found nothing is its own outcome", ToolRead.Empty, empty)
        assertTrue("and it IS an answer about the world", empty.answered)
        assertFalse("but it found nothing", empty.found)
        assertFalse("and it is not a refusal", empty.refused)
    }

    @Test
    fun aFoundReadCannotHoldAnEmptyList() {
        // The type refuses to represent "found, but zero things", because that IS Empty and
        // allowing both spellings is how the two outcomes drift apart again.
        try {
            ToolRead.Found(emptyList<String>())
            fail("Found must reject an empty list: an empty found result is Empty")
        } catch (expected: IllegalArgumentException) {
            assertTrue(
                "the message must name the rule: ${expected.message}",
                expected.message?.contains("Empty") == true,
            )
        }
    }

    @Test
    fun aMissingGrantIsARefusalThatNamesWhatWouldUnlockIt() {
        val blocked = ToolReads.missingGrant<String>(
            permission = "android.permission.READ_CALL_LOG",
            tier = "APP_UID",
            remedy = "Grant READ_CALL_LOG in the permission gate.",
        )
        val refusal = blocked as ToolRead.Refused
        assertTrue("nothing was attempted, so this is a refusal", blocked.refused)
        assertTrue(
            "the refusal must name the grant, not just say it could not read",
            refusal.reason.contains("READ_CALL_LOG"),
        )
        assertTrue(
            "and it must name the route, because a refusal with no route is a dead end",
            refusal.remedy.contains("READ_CALL_LOG"),
        )
    }

    @Test
    fun aToolThatCannotTellSaysSoRatherThanGuessing() {
        val unsure = ToolReads.cannotTell<String>(
            what = "listener state",
            tier = "APP_UID",
            remedy = "Fix the probe to report what the platform did.",
        )
        assertTrue("a probe that cannot tell must refuse rather than default", unsure.refused)
        assertTrue(
            "and it must say that is what happened: ${(unsure as ToolRead.Refused).reason}",
            unsure.reason.contains("could not tell"),
        )
    }

    @Test
    fun theSentenceForARefusalForbidsReadingItAsAFact() {
        val refused = ToolRead.Refused(
            tier = "APP_UID",
            reason = "The ARP table was refused.",
            remedy = "Grant Shizuku.",
        )
        val sentence = ToolReads.sentence(refused, plural = "devices")

        assertTrue(
            "the sentence must say this is not a result about the world: $sentence",
            sentence.contains("not a result about the world"),
        )
        assertTrue("and must name the route: $sentence", sentence.contains("Grant Shizuku"))
        assertFalse(
            "and must never present a count, which is the value a genuine result would also " +
                "produce: $sentence",
            sentence.contains("0 devices"),
        )
    }

    @Test
    fun theSentenceForAnEmptyResultSaysItIsAnAnswer() {
        val sentence = ToolReads.sentence(ToolRead.Empty, plural = "devices")
        assertTrue(
            "an empty result must be marked as an answer, so a model does not hedge on it: " +
                sentence,
            sentence.contains("answer about the world"),
        )
    }

    @Test
    fun aFamilyOfRefusalsIsFlaggedAsStillCollapsed() {
        val allRefused = listOf<ToolRead<String>>(
            ToolRead.Refused("APP_UID", "a", "b"),
            ToolRead.Refused("APP_UID", "c", "d"),
        )
        assertTrue(
            "a family where nothing ever answers has not been fixed, whatever its enums say",
            ToolReads.collapsesRefusalIntoEmpty(allRefused),
        )

        val mixed = listOf<ToolRead<String>>(
            ToolRead.Refused("APP_UID", "a", "b"),
            ToolRead.Empty,
        )
        assertFalse(
            "one tool that can genuinely answer means the family is not collapsing",
            ToolReads.collapsesRefusalIntoEmpty(mixed),
        )
    }

    @Test
    fun aFoundReadCarriesItsItemsAndItsCount() {
        val found = ToolReads.fromShell(
            succeeded = true,
            items = listOf("192.0.2.10", "192.0.2.11"),
            tier = "SHIZUKU",
            refusalReason = "unused",
            remedy = "unused",
        )
        assertEquals(2, found.values?.size)
        assertTrue(found.found)
        assertEquals("2 devices.", ToolReads.sentence(found, plural = "devices"))
    }
}
