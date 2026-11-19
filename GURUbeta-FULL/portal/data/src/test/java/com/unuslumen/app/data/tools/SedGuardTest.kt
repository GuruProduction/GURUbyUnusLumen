// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sed guard decides whether a command INVOKES sed, not whether a string
 * contains three particular letters (issue 467).
 *
 * WHY THESE EXIST. The guard had two opposite faults in two places. The shell
 * path was four hand-written phrase tests, so a pipe with no trailing space, a
 * newline-separated command, or `$(sed ...)` slipped through. The Python path
 * was a bare `contains("sed")`, so a script that merely said "used" or "based"
 * next to a `subprocess` call was refused with a message about editing files.
 *
 * A restriction that refuses honest work teaches a caller to abandon the
 * approach rather than rewrite it, which is a defect in the product and not a
 * safety feature. These tests hold both directions: the invocations must be
 * caught, and the incidental substrings must not.
 */
class SedGuardTest {

    private val guard = FileAccessGuard()

    // ------------------------------------------------------------------
    // The refusals that must still happen.
    // ------------------------------------------------------------------

    @Test
    fun a_bare_sed_is_blocked_and_the_index_is_reported() {
        assertEquals(0, guard.findSedInvocation("sed -i 's/a/b/' file.txt"))
        assertTrue(guard.checkSedBlocked("sed -i 's/a/b/' file.txt"))
    }

    @Test
    fun a_pipe_invocation_is_blocked() {
        assertTrue(guard.checkSedBlocked("cat file.txt | sed 's/a/b/'"))
    }

    @Test
    fun a_pipe_with_no_space_after_it_is_blocked() {
        // The previous phrase test required a space after the pipe, so this
        // passed straight through and then edited a file.
        assertTrue(guard.checkSedBlocked("cat f|sed 's/a/b/'"))
    }

    @Test
    fun a_newline_separated_command_is_blocked() {
        // A shell script built by a model is a string with newlines in it, and
        // the old tests only knew about the start of the string.
        assertTrue(guard.checkSedBlocked("echo one\nsed -i 's/a/b/' file"))
    }

    @Test
    fun a_command_substitution_is_blocked() {
        assertTrue(guard.checkSedBlocked("echo \"\$(sed -n 1p file)\""))
    }

    @Test
    fun a_subshell_invocation_is_blocked() {
        assertTrue(guard.checkSedBlocked("(sed -i 's/a/b/' file)"))
    }

    @Test
    fun a_chained_invocation_is_blocked() {
        assertTrue(guard.checkSedBlocked("cd /tmp && sed -i 's/a/b/' f"))
        assertTrue(guard.checkSedBlocked("cd /tmp; sed -i 's/a/b/' f"))
    }

    // ------------------------------------------------------------------
    // The false positives that must stop happening.
    // ------------------------------------------------------------------

    @Test
    fun a_word_ending_in_those_letters_is_not_an_invocation() {
        // This is the Python false positive in its purest form, and it also
        // covered the shell path's own `contains` checks.
        assertNull(guard.findSedInvocation("echo used"))
        assertNull(guard.findSedInvocation("echo passed"))
        assertNull(guard.findSedInvocation("echo based"))
        assertNull(guard.findSedInvocation("echo closed"))
        assertFalse(guard.checkSedBlocked("echo used"))
    }

    @Test
    fun a_filename_beginning_with_those_letters_is_not_an_invocation() {
        assertNull(guard.findSedInvocation("cat sed_config.json"))
        assertNull(guard.findSedInvocation("grep -r SED /etc"))
        assertFalse(guard.checkSedBlocked("cat sed_config.json"))
    }

    @Test
    fun a_word_with_a_prefix_is_not_an_invocation() {
        // `unsed` and `resed` are whole tokens that contain the letters. Neither
        // is a command, and neither was reachable through the phrase tests but
        // both were through the substring check.
        assertNull(guard.findSedInvocation("echo unsed"))
        assertNull(guard.findSedInvocation("echo mysed"))
    }

    @Test
    fun an_ordinary_command_that_mentions_the_word_runs() {
        // The whole point: a command in this shape must reach the shell.
        listOf(
            "echo hello",
            "ls -la /sdcard",
            "grep -n 'used' report.txt",
            "python3 script.py",
            "cat notes.md",
        ).forEach { command ->
            assertFalse("$command must not be refused by the sed guard", guard.checkSedBlocked(command))
        }
    }

    // ------------------------------------------------------------------
    // The index is what makes the refusal actionable.
    // ------------------------------------------------------------------

    @Test
    fun the_reported_index_points_at_the_invocation() {
        val command = "echo one | sed 's/a/b/'"
        val at = guard.findSedInvocation(command)
        assertNotNull(at)
        assertEquals("sed", command.substring(at!!, at + 3))
    }
}
