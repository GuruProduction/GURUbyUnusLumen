// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.tools.permission

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a caller is allowed to see of an auth token.
 *
 * WHY THIS IS THE MOST IMPORTANT TEST IN THE DOMAIN. An auth token is a bearer credential: it is
 * as good as the password for whatever it grants, and it stays valid until revoked. A tool result
 * is stored, shown in the conversation, written to the observation table and exported in a brain
 * snapshot, so a token in a result is a leak the person cannot undo.
 *
 * These tests pin the rule that the token itself never appears, that the fingerprint is the tail
 * rather than the head, and that a short token gets no fingerprint at all because a tail would be
 * too much of it.
 */
class AccountTokenPolicyTest {

    private val realisticToken = "ya29.a0AfH6SMBx7Kq2LmNoPqRsTuVwXyZ0123456789abcdef"

    // ------------------------------------------------------------------
    // The token never appears
    // ------------------------------------------------------------------

    /**
     * THE CENTRAL ASSERTION. The sentence a result uses must not contain the token.
     *
     * This is what stops a future edit from "helpfully" including the credential so a caller can
     * use it. If somebody needs that, it has to be a different function with a different name and
     * an explicit decision, not a change to this one.
     */
    @Test
    fun theSentenceNeverContainsTheToken() {
        val shape = AccountTokenPolicy.shapeOf(realisticToken)!!
        val sentence = AccountTokenPolicy.sentence(shape)

        assertFalse(
            "the full token must never appear in a sentence: $sentence",
            sentence.contains(realisticToken),
        )
        assertFalse(
            "not even a long prefix of it: $sentence",
            sentence.contains(realisticToken.take(20)),
        )
        assertTrue("it must still say a token was obtained: $sentence", sentence.contains("obtained"))
    }

    /**
     * The fingerprint is the TAIL, never the head.
     *
     * Provider token prefixes are often fixed strings, so the head identifies the provider rather
     * than the token and gives away part of the secret for nothing. The tail is the part that
     * distinguishes one token from another.
     */
    @Test
    fun theFingerprintIsTheTailNotTheHead() {
        val shape = AccountTokenPolicy.shapeOf(realisticToken)!!
        assertEquals(realisticToken.takeLast(4), shape.fingerprint)
        assertFalse(
            "the head must not be the fingerprint",
            shape.fingerprint == realisticToken.take(4),
        )
    }

    @Test
    fun theLengthIsReportedAsARealFactAboutTheCredential() {
        val shape = AccountTokenPolicy.shapeOf(realisticToken)!!
        assertEquals(realisticToken.length, shape.length)
        assertTrue(shape.obtained)
    }

    // ------------------------------------------------------------------
    // The short-token case
    // ------------------------------------------------------------------

    /**
     * A short token gets no fingerprint at all.
     *
     * Four characters of an eight-character token is half of it, which turns a useful shape into
     * a usable partial secret. Below the threshold there is only a length and a sentence that
     * says why.
     */
    @Test
    fun aShortTokenGetsNoFingerprint() {
        val short = "abc123"
        val shape = AccountTokenPolicy.shapeOf(short)!!
        assertNull("a short token must not be fingerprinted at all", shape.fingerprint)
        assertEquals(6, shape.length)

        val sentence = AccountTokenPolicy.sentence(shape)
        assertFalse("the short token must not appear: $sentence", sentence.contains(short))
        assertTrue(
            "it must say the token is not shown in any form: $sentence",
            sentence.contains("not shown here in any form"),
        )
    }

    @Test
    fun aTokenExactlyAtTheThresholdIsFingerprinted() {
        val atThreshold = "a".repeat(12)
        val shape = AccountTokenPolicy.shapeOf(atThreshold)!!
        assertNotNull("a token at the threshold has a safe tail", shape.fingerprint)
    }

    // ------------------------------------------------------------------
    // No token
    // ------------------------------------------------------------------

    @Test
    fun noTokenIsReportedAsAbsenceRatherThanFailure() {
        assertNull(AccountTokenPolicy.shapeOf(null))
        assertNull(AccountTokenPolicy.shapeOf(""))

        val sentence = AccountTokenPolicy.sentence(null)
        assertTrue(
            "it must say no token was obtained: $sentence",
            sentence.contains("No token was obtained"),
        )
        assertTrue(
            "it must distinguish that from a permission problem: $sentence",
            sentence.contains("permission problem"),
        )
    }

    // ------------------------------------------------------------------
    // Invalidation
    // ------------------------------------------------------------------

    @Test
    fun invalidationReportsTheStateChangeAndItsLimit() {
        val ok = AccountTokenPolicy.invalidationSentence(true)
        assertTrue("it must say it worked: $ok", ok.contains("invalidated"))
        assertTrue(
            "it must state the limit a cached copy has: $ok",
            ok.contains("cached it"),
        )

        val refused = AccountTokenPolicy.invalidationSentence(false)
        assertTrue(
            "it must not call an already-gone token a failure: $refused",
            refused.contains("already gone"),
        )
    }
}
