// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.guru

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.unuslumen.app.data.tools.TermuxToolDefinitions
import com.unuslumen.app.data.tools.TermuxToolExecutor
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * THE BATTERY, run through the real tool surface.
 *
 * WHY THIS EXISTS AND WHY THE PROVIDER TESTS ARE NOT ENOUGH. Every previous test
 * in this class called TermuxProvider directly. GURU does not: it calls
 * termuxStatus, termuxExec and termuxInstall through TermuxToolExecutor, and the
 * two faults that survived three releases both lived in THAT layer rather than
 * in the provider —
 *
 *   1. `ensureReady()` gated initialize() on isAvailable(), which is true for any
 *      populated bin directory, so the probe never ran on a real install.
 *   2. termuxStatus had the same gate, so prefixExecutes stayed null and vanished
 *      from the JSON entirely.
 *
 * A provider test passes with both of those bugs present, which is exactly what
 * happened: the fix shipped, the handset reported no new fields, and the reason
 * was one layer above everything that had been tested.
 *
 * So this drives the tool names and the argument maps GURU actually sends, and
 * asserts on the JSON-shaped result a person would read.
 */
@RunWith(AndroidJUnit4::class)
class TermuxToolSurfaceTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun executor() = TermuxToolExecutor(context)

    /**
     * termuxStatus must carry a MEASURED verdict, not just per-tool booleans.
     *
     * The handset report on 3.46 showed the fields missing entirely. They were
     * missing because nothing had run the probe, and that is the failure this
     * asserts against: null is not acceptable on a device where the prefix is
     * installed, because the whole point of the field is to say whether the tree
     * works.
     */
    @Test
    fun termuxStatusReportsAMeasuredPrefixVerdict() = runBlocking {
        val result = executor().execute(TermuxToolDefinitions.TERMUX_STATUS, emptyMap())
        val json = result.toString()
        println("TERMUX_STATUS -> $json")

        assertTrue(
            "termuxStatus returned no usable payload: $json",
            json.contains("binCount"),
        )
        assertTrue(
            "termuxStatus did not include prefixExecutes, which means the execution probe " +
                "never ran. That is the exact fault the 3.46 handset report showed. Payload: $json",
            json.contains("prefixExecutes"),
        )
        assertTrue(
            "termuxStatus did not include prefixHealth, so there is no observed reason " +
                "attached to the verdict. Payload: $json",
            json.contains("prefixHealth"),
        )
        assertTrue(
            "The prefix reported itself as not executing. This battery is only meaningful " +
                "on a device where the prefix works. Payload: $json",
            json.contains("\"prefixExecutes\":true"),
        )
    }

    /**
     * termuxExec must actually run a command and return its output.
     *
     * This is the single claim issue 317 opened with: exit 139 and empty stdout
     * from `bash -c 'echo alive'`. The assertion is on the text coming back, not
     * on an exit code, because a wrong path can produce a plausible code.
     */
    @Test
    fun termuxExecRunsARealCommandThroughTheToolSurface() = runBlocking {
        val result = executor().execute(
            TermuxToolDefinitions.TERMUX_EXEC,
            mapOf("command" to "bash -c 'echo alive'"),
        )
        val json = result.toString()
        println("TERMUX_EXEC -> $json")

        assertTrue(
            "termuxExec did not return the command's output. On the handset this reported " +
                "exit 139 with empty stdout and stderr. Payload: $json",
            json.contains("alive"),
        )
        assertTrue(
            "termuxExec reported failure for a command that should succeed. Payload: $json",
            json.contains("\"success\":true"),
        )
    }

    /** git, curl and apt through the same surface, since the issue names all three. */
    @Test
    fun everyBinaryInTheIssueRunsThroughTermuxExec() = runBlocking {
        val failures = mutableListOf<String>()
        for (command in listOf("git --version", "curl --version", "apt --version")) {
            val result = executor().execute(
                TermuxToolDefinitions.TERMUX_EXEC,
                mapOf("command" to command),
            )
            val json = result.toString()
            println("TERMUX_EXEC[$command] -> $json")
            if (!json.contains("\"success\":true") || !json.contains("stdout")) {
                failures.add("$command -> $json")
            }
        }
        assertTrue(
            "These commands did not run through termuxExec: $failures",
            failures.isEmpty(),
        )
    }

    /**
     * termuxInstall must reach apt and report apt's own words.
     *
     * The handset reported exit 139 here with empty output. Anything other than a
     * segfault is progress, but the assertion is that apt actually answered,
     * because "success" from a package manager that never ran is the false green
     * this whole surface exists to prevent.
     */
    @Test
    fun termuxInstallReachesAptAndReportsItsOutput() = runBlocking {
        val result = executor().execute(
            TermuxToolDefinitions.TERMUX_INSTALL,
            mapOf("packages" to "figlet"),
        )
        val json = result.toString()
        println("TERMUX_INSTALL -> $json")

        assertTrue(
            "termuxInstall produced empty output, which is the signature of the exec " +
                "failure issue 317 describes. Payload: $json",
            json.contains("output") && !json.contains("\"output\":\"\""),
        )
        assertTrue(
            "termuxInstall still reports exit 139, so apt never started. Payload: $json",
            !json.contains("\"exitCode\":139"),
        )
    }

    /** The binary apt installs must then RUN, which is the end of the chain. */
    @Test
    fun aPackageAptDeliveredExecutesThroughTermuxExec() = runBlocking {
        val result = executor().execute(
            TermuxToolDefinitions.TERMUX_EXEC,
            mapOf("command" to "figlet -d \"\$PREFIX/share/figlet\" hi"),
        )
        val json = result.toString()
        println("FIGLET -> $json")
        assertTrue(
            "figlet did not render. It is installed by termuxInstall in the previous test, so " +
                "this is the end-to-end claim: apt delivers a binary and it executes. Payload: $json",
            json.contains("_") || json.contains("|") || json.contains("/"),
        )
    }
}
