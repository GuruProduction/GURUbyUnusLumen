// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.heartbeat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The token contract, and the spelling that was silently broken.
 *
 * WHAT WAS WRONG. The loader knew `{{human_name}}` and `{{time}}`. The shipped asset
 * is written with `${human}`. Nothing matched it, so every heartbeat beat in every
 * released build injected the literal text `${human}` where the owner's name should
 * have been. Nobody saw it, because a prompt is read by a model rather than by a
 * person, and the model simply did what the literal text said.
 *
 * THESE TESTS PIN BOTH SPELLINGS. A token a person might reasonably type must
 * substitute, and an unsubstituted one must never survive into the injected text:
 * that is the assertion that would have caught the bug.
 */
class HeartbeatPromptTokensTest {

    @Test
    fun `the shipped spelling substitutes`() {
        val out = HeartbeatPrompts.substitute("Hello ${'$'}{human}, this is your beat.", "Steven")
        assertTrue("the name must appear: $out", out.contains("Steven"))
        assertFalse("no literal token may survive: $out", out.contains("${'$'}{human}"))
    }

    @Test
    fun `the double-brace spelling substitutes too`() {
        val out = HeartbeatPrompts.substitute("Hello {{human_name}}.", "Steven")
        assertTrue("the name must appear: $out", out.contains("Steven"))
        assertFalse("no literal token may survive: $out", out.contains("{{human_name}}"))
    }

    @Test
    fun `a blank name becomes the house phrase rather than an empty gap`() {
        val out = HeartbeatPrompts.substitute("Hello ${'$'}{human}.", "")
        assertTrue("an unnamed owner is still addressed: $out", out.contains("your human"))
    }

    @Test
    fun `both clock spellings substitute`() {
        val dollar = HeartbeatPrompts.substitute("It is ${'$'}{time}.", "Steven")
        val braces = HeartbeatPrompts.substitute("It is {{time}}.", "Steven")
        assertFalse("no literal dollar token may survive: $dollar", dollar.contains("${'$'}{time}"))
        assertFalse("no literal brace token may survive: $braces", braces.contains("{{time}}"))
    }

    @Test
    fun `a prompt with no tokens is returned untouched`() {
        val plain = "Think about what matters today."
        assertTrue(HeartbeatPrompts.substitute(plain, "Steven") == plain)
    }
}
