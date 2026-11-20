// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.domain.agents

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which arguments are withheld, and which are not.
 *
 * THE RISK IS IN BOTH DIRECTIONS. Redacting too little stores a credential; redacting too much hides
 * the ordinary work the record exists to prove. Neither failure is visible from outside, because a
 * reader cannot tell a withheld field from one the call never carried. These tests therefore pin the
 * exact set rather than sampling it.
 */
class AgentArgumentRedactionTest {

    @Test
    fun anOrdinaryToolKeepsEveryArgument() {
        val verdict = AgentArgumentRedaction.evaluate(
            toolName = "captureScreen",
            argumentNames = listOf("quality", "format"),
        )
        assertFalse("nothing about a screenshot is secret", verdict.hasWithheld)
        assertEquals("", verdict.reason)
    }

    @Test
    fun aPasswordFieldIsWithheld() {
        val verdict = AgentArgumentRedaction.evaluate(
            toolName = "httpPost",
            argumentNames = listOf("url", "password"),
        )
        assertTrue(verdict.hasWithheld)
        assertEquals(listOf("password"), verdict.withheldFields.map { it.field })
        assertTrue(
            "the reason names the field, not its value: ${verdict.reason}",
            verdict.reason.contains("password"),
        )
    }

    @Test
    fun theMatchIsCaseInsensitiveAndBySubstring() {
        /*
         * A TOOL NAMING ITS FIELD apiKey, api_key, x-api-key OR apiKeyValue MEANS THE SAME THING.
         * A rule that only matched exact spellings would miss the one that mattered, and the miss
         * would be invisible.
         */
        val variants = listOf("apiKey", "API_KEY", "x-api-key", "apiKeyValue", "my_secret", "AUTH_TOKEN")
        val verdict = AgentArgumentRedaction.evaluate("httpPost", variants)
        assertEquals(
            "every spelling of a credential is withheld",
            variants.toSet(),
            verdict.withheldFields.map { it.field }.toSet(),
        )
    }

    @Test
    fun aSecretBearingToolWithholdsEverythingWhateverTheFieldsAreCalled() {
        /*
         * A TOOL THAT EXISTS TO HANDLE A CREDENTIAL HAS ONE JOB. Its arguments are the credential
         * whether or not the names say so, and guessing which of an unknown tool's arguments is the
         * secret is worse than withholding them all with one clear reason.
         */
        val verdict = AgentArgumentRedaction.evaluate(
            toolName = "sshExec",
            argumentNames = listOf("host", "command"),
        )
        assertTrue("the tool itself makes the call sensitive", verdict.hasWithheld)
        assertEquals(setOf("host", "command"), verdict.withheldFields.map { it.field }.toSet())
        assertTrue(
            "and the reason says why rather than accusing the fields: ${verdict.reason}",
            verdict.reason.contains("credential"),
        )
    }

    @Test
    fun redactingReplacesTheValueAndKeepsTheName() {
        /*
         * THE NAME IS NOT THE SECRET. A record saying a call passed apiKey and not what it passed is
         * evidence of the shape of the call, which is what an audit needs; stripping the name as
         * well would leave a call that looks like it took no arguments at all.
         */
        val json = """{"url":"https://example.com","apiKey":"sk-live-abc123","count":3}"""
        val verdict = AgentArgumentRedaction.evaluate("httpPost", listOf("url", "apiKey", "count"))
        val redacted = AgentArgumentRedaction.redactArguments(json, verdict)

        assertTrue("the name survives", redacted.contains("\"apiKey\""))
        assertFalse("the value does not", redacted.contains("sk-live-abc123"))
        assertTrue("the marker is explicit", redacted.contains("[withheld]"))
        assertTrue("and the ordinary fields are untouched", redacted.contains("https://example.com"))
        assertTrue("including numbers", redacted.contains("\"count\":3"))
    }

    @Test
    fun redactionDoesNotReformatTheDocument() {
        /*
         * RE-SERIALISING WOULD REFORMAT EVERYTHING and the record would no longer prove what was
         * sent. The replacement is textual and touches only the matching value.
         */
        val json = """{"a" : "one",   "token" : "abc", "b":"two"}"""
        val verdict = AgentArgumentRedaction.evaluate("httpPost", listOf("a", "token", "b"))
        val redacted = AgentArgumentRedaction.redactArguments(json, verdict)

        assertTrue("the original spacing around the surviving fields is kept", redacted.contains("\"a\" : \"one\""))
        assertFalse(redacted.contains("abc"))
    }

    @Test
    fun aValueContainingAQuoteIsStillFullyRemoved() {
        /*
         * A SECRET CONTAINING AN ESCAPED QUOTE IS STILL A SECRET. The pattern consumes escaped
         * characters rather than stopping at the first quote inside the value, which is what stops
         * half a password being left in the record.
         */
        val json = """{"token":"ab\"cd\"ef"}"""
        val verdict = AgentArgumentRedaction.evaluate("httpPost", listOf("token"))
        val redacted = AgentArgumentRedaction.redactArguments(json, verdict)
        assertFalse("no fragment of the value survives: $redacted", redacted.contains("ab\\\"cd"))
        assertTrue(redacted.contains("[withheld]"))
    }

    @Test
    fun redactingWithNothingWithheldReturnsTheInputExactly() {
        val json = """{"quality":90}"""
        val verdict = AgentArgumentRedaction.evaluate("captureScreen", listOf("quality"))
        assertEquals(json, AgentArgumentRedaction.redactArguments(json, verdict))
    }

    @Test
    fun theReasonNamesEveryWithheldFieldWhenThereAreSeveral() {
        val verdict = AgentArgumentRedaction.evaluate(
            "httpPost",
            listOf("password", "token", "url"),
        )
        assertTrue(verdict.reason.contains("password"))
        assertTrue(verdict.reason.contains("token"))
        assertFalse("the ordinary field is not accused", verdict.reason.contains("url"))
    }
}
