// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.guru

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.unuslumen.app.util.shell.TermuxProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Issue 290: apt failing to start because it resolves its configuration against the upstream
 * Termux prefix, which does not exist on this device.
 *
 * MEASURED, NOT ASSUMED. The ticket reports `apt update` exiting 139 with nothing on either
 * stream through termuxExec, and the real error only appearing when run through the linker.
 * #291 has since fixed a different startup fault in the same prefix, so the first job here is
 * to find out what apt actually does NOW rather than repeat the ticket's result as if it were
 * current.
 *
 * THE TEST IS WRITTEN TO FAIL WITH APT'S OWN WORDS. If apt still cannot start, the assertion
 * message carries what it said, which is the evidence a fix needs. If it starts, the test is a
 * regression guard.
 */
@RunWith(AndroidJUnit4::class)
class AptStartupTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun linker(): String =
        if (System.getProperty("os.arch")?.contains("64") == true) "/system/bin/linker64"
        else "/system/bin/linker"

    private fun runApt(arg: String, extraEnv: Map<String, String> = emptyMap()): Triple<Int, String, String> {
        val provider = TermuxProvider(context)
        val root = File(provider.getRootPath())
        val binary = File(root, "usr/bin/apt-get")
        if (!binary.isFile) return Triple(-98, "", "apt-get is not in the prefix")

        val env = mutableMapOf(
            "LD_LIBRARY_PATH" to File(root, "usr/lib").absolutePath,
            "PREFIX" to File(root, "usr").absolutePath,
            "TERMUX__PREFIX" to File(root, "usr").absolutePath,
            "TERMUX__ROOTFS" to root.absolutePath,
            "TERMUX_APP__DATA_DIR" to (context.filesDir.parentFile?.absolutePath ?: context.filesDir.absolutePath),
            "HOME" to File(root, "home").absolutePath,
            "TMPDIR" to File(root, "usr/tmp").absolutePath,
            "PATH" to "${File(root, "usr/bin").absolutePath}:/system/bin:/system/xbin",
        )
        env.putAll(extraEnv)

        return try {
            val pb = ProcessBuilder(linker(), binary.absolutePath, arg)
            pb.environment().clear()
            pb.environment().putAll(env)
            pb.directory(File(root, "home"))
            val proc = pb.start()
            val out = StringBuilder()
            val err = StringBuilder()
            val tOut = Thread { runCatching { proc.inputStream.bufferedReader().use { out.append(it.readText()) } } }
            val tErr = Thread { runCatching { proc.errorStream.bufferedReader().use { err.append(it.readText()) } } }
            tOut.start(); tErr.start()
            val code = proc.waitFor()
            tOut.join(15000); tErr.join(15000)
            Triple(code, out.toString().trim(), err.toString().trim())
        } catch (e: Exception) {
            Triple(-1, "", "${e.javaClass.simpleName}: ${e.message}")
        }
    }

    @Test
    fun thePrefixIsExtractedBeforeAnythingIsMeasured() = runBlocking {
        val provider = TermuxProvider(context)
        val ready = provider.initialize()
        println("PREFIX ready=$ready")
        assertTrue("initialize() did not produce a prefix", ready)
    }

    /**
     * apt starts and prints its version, measured through the path a person actually uses.
     *
     * THE FIRST VERSION OF THIS TEST BUILT ITS OWN ENVIRONMENT AND WAS WRONG. It set the prefix
     * variables by hand, omitted APT_CONFIG, and asserted exit 0. apt then fell back to the
     * compiled-in /data/data/com.termux, failed to read that directory, and exited 100 — which
     * is apt working correctly in a broken environment, and reporting it honestly.
     *
     * The product sets APT_CONFIG itself, so the meaningful measurement is the one below and
     * the explicit-config one after it. This test now only asserts what is true of apt itself:
     * it runs, and it says which version it is. The exit code is recorded, not prescribed,
     * because apt legitimately returns non-zero when a configuration directory is unreadable.
     */
    @Test
    fun aptRunsAndSaysWhichVersionItIs() = runBlocking {
        TermuxProvider(context).initialize()
        val provider = TermuxProvider(context)
        val root = File(provider.getRootPath())
        val aptConf = File(root, "usr/etc/apt/apt.conf.d/01-root.conf")

        val (code, out, err) = runApt("--version", mapOf("APT_CONFIG" to aptConf.absolutePath))
        val combined = "$out $err".trim()
        println("APT_VERSION exit=$code out=[${out.take(200)}] err=[${err.take(300)}]")

        assertTrue(
            "apt-get must run and name its version. exit=$code out=[${out.take(200)}] " +
                "err=[${err.take(300)}]",
            combined.contains("apt "),
        )
        assertEquals(
            "With its configuration readable, apt-get --version must exit 0. " +
                "out=[${out.take(200)}] err=[${err.take(300)}]",
            0,
            code,
        )
    }

    @Test
    fun theAptConfigurationIsResolvedAgainstThisPrefixNotTheUpstreamOne() = runBlocking {
        TermuxProvider(context).initialize()
        val provider = TermuxProvider(context)
        val root = File(provider.getRootPath())

        // The drop-in the provider writes to redirect apt at this prefix.
        val rootConf = File(root, "usr/etc/apt/apt.conf.d/01-root.conf")
        println("APT_CONF exists=${rootConf.isFile} path=${rootConf.absolutePath}")
        assertTrue(
            "apt's root redirect configuration is missing, so apt would fall back to its " +
                "compiled-in /data/data/com.termux and refuse to start.",
            rootConf.isFile,
        )

        val text = rootConf.readText()
        println("APT_CONF content=[${text.take(300)}]")

        // The redirect must name THIS prefix, derived rather than hardcoded.
        assertTrue(
            "The apt configuration must point at this installation's prefix " +
                "(${root.absolutePath}). Got: ${text.take(300)}",
            text.contains(root.absolutePath),
        )
        assertTrue(
            "The apt configuration must not name the upstream com.termux prefix, which is " +
                "the fault this issue is about. Got: ${text.take(300)}",
            !text.contains("/data/data/com.termux"),
        )
        // apt's config parser rejects a statement without a terminating semicolon, which was a
        // separate defect: the file meant to fix apt could not be parsed at all.
        assertTrue(
            "Every apt configuration statement must end with a semicolon or the parser " +
                "rejects the file. Got: ${text.take(300)}",
            text.lineSequence().filter { it.contains("\"") }.all { it.trimEnd().endsWith(";") },
        )
    }

    /**
     * The specific call the ticket says fails, measured through the path the product uses.
     *
     * THE FIRST VERSION OF THIS TEST WAS WRONG BECAUSE IT BUILT ITS OWN ENVIRONMENT. It set the
     * prefix variables by hand and omitted APT_CONFIG, so apt fell back to the compiled-in
     * /data/data/com.termux and printed exactly the warning the ticket reports. That looked like
     * a product fault and was my harness. The product sets APT_CONFIG itself, so the honest
     * question is whether the PRODUCT's environment works, which is what this now measures via
     * probeApt, the same call termuxStatus makes.
     */
    @Test
    fun theProductsOwnAptEnvironmentResolvesItsConfiguration() = runBlocking {
        val provider = TermuxProvider(context)
        provider.initialize()
        val probe = provider.probeApt()
        println("APT_PRODUCT state=${probe.state} exit=${probe.exitCode} out=[${probe.output.take(240)}]")

        assertTrue(
            "apt must start through the app's own environment. The ticket's failure is apt " +
                "resolving its config against /data/data/com.termux, which does not exist. " +
                "state=${probe.state} exit=${probe.exitCode} out=[${probe.output.take(300)}]",
            probe.state == TermuxProvider.AptProbe.RUNS,
        )
        assertTrue(
            "apt must not report the upstream prefix. Got: ${probe.output.take(400)}",
            !probe.output.contains("/data/data/com.termux"),
        )
    }

    /**
     * And with APT_CONFIG named explicitly, which is what a shell user gets.
     *
     * This is the shape `termuxExec` produces, so it is the one a person's `apt update` takes.
     */
    @Test
    fun aptWithTheConfigNamedExplicitlyDoesNotWarnAboutTheUpstreamPrefix() = runBlocking {
        TermuxProvider(context).initialize()
        val provider = TermuxProvider(context)
        val root = File(provider.getRootPath())
        val aptConf = File(root, "usr/etc/apt/apt.conf.d/01-root.conf")

        val (code, out, err) = runApt("--version", mapOf("APT_CONFIG" to aptConf.absolutePath))
        val combined = "$out $err"
        println("APT_EXPLICIT exit=$code combined=[${combined.take(300)}]")

        assertTrue(
            "With APT_CONFIG pointing at this prefix's configuration, apt must not read the " +
                "upstream directory. Got: ${combined.take(400)}",
            !combined.contains("/data/data/com.termux"),
        )
        assertTrue(
            "apt must still print its version. exit=$code out=[${out.take(200)}]",
            combined.contains("apt"),
        )
    }
}
