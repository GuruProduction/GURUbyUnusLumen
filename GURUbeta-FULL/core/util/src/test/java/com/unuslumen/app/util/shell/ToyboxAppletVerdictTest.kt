// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.util.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which toybox applets may stay on PATH (issue 463).
 *
 * THE DEFECT. Around two hundred applet symlinks were created and trusted, so
 * `command -v awk` succeeded while `awk` exited 127 with "Unknown command awk". The
 * measured split is arbitrary — curl, cut, tar, sqlite3, find and nc run; awk, tr,
 * grep, sort, wget, hexdump and unzip do not — so no allow-list written by hand could
 * be right, and the only honest test is to run the thing.
 *
 * These tests hold the decision rule. The process spawning itself needs a device,
 * and the PR body says so.
 */
class ToyboxAppletVerdictTest {

    @Test
    fun an_applet_toybox_refuses_is_dropped() {
        val verdict = ToyboxAppletVerdict.decide(
            exitCode = 127,
            stderr = "toybox: Unknown command awk (see \"toybox --help\")",
        )
        assertTrue("awk must be dropped: $verdict", verdict is ToyboxAppletVerdict.Verdict.Drop)
        assertTrue(
            "the reason must be legible in the log: $verdict",
            (verdict as ToyboxAppletVerdict.Verdict.Drop).reason.contains("127"),
        )
    }

    @Test
    fun an_applet_that_ran_and_complained_about_its_arguments_is_kept() {
        // THIS IS THE RULE THAT MATTERS MOST. awk with no program exits 2, grep with
        // no pattern exits 2, sed with no script exits 1. Dropping on any non-zero
        // exit would remove every tool that has required arguments, which is most of
        // them, and the PATH would then advertise almost nothing.
        listOf(1, 2, 64).forEach { code ->
            assertEquals(
                "exit $code is an applet that ran",
                ToyboxAppletVerdict.Verdict.Keep,
                ToyboxAppletVerdict.decide(code, "Usage: awk [-F fs] ..."),
            )
        }
    }

    @Test
    fun an_applet_that_answered_cleanly_is_kept() {
        assertEquals(
            ToyboxAppletVerdict.Verdict.Keep,
            ToyboxAppletVerdict.decide(0, ""),
        )
    }

    @Test
    fun a_link_that_cannot_be_started_is_dropped() {
        // A dangling symlink — the reinstall case, where Android generates a new
        // hashed nativeLibraryDir and every old link points at a dead path.
        val verdict = ToyboxAppletVerdict.decide(null, "No such file or directory")
        assertTrue(verdict is ToyboxAppletVerdict.Verdict.Drop)
        assertTrue(
            (verdict as ToyboxAppletVerdict.Verdict.Drop).reason.contains("could not be started"),
        )
    }

    @Test
    fun the_reason_survives_a_missing_or_empty_stderr() {
        // The decision is the exit code; the wording is decoration and its absence
        // must not change the verdict.
        assertTrue(ToyboxAppletVerdict.decide(127, null) is ToyboxAppletVerdict.Verdict.Drop)
        assertTrue(ToyboxAppletVerdict.decide(127, "") is ToyboxAppletVerdict.Verdict.Drop)
        assertEquals(ToyboxAppletVerdict.Verdict.Keep, ToyboxAppletVerdict.decide(2, null))
    }

    @Test
    fun the_unknown_applet_code_is_the_one_toybox_actually_uses() {
        // 127 is "command not found" by shell convention and is what the ticket
        // measured. It is named as a constant so a future reader cannot quietly
        // change it to a guess.
        assertEquals(127, ToyboxAppletVerdict.UNKNOWN_APPLET_EXIT)
    }
}
