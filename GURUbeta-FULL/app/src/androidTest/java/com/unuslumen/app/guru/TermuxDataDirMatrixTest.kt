// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.guru

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.unuslumen.app.util.shell.TermuxProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Issue 329's matrix, held as a device test.
 *
 * WHY THIS IS A DEVICE TEST. The question is what libtermux-exec does with a real process in
 * this app's SELinux domain. A run-as or shell run is a different domain with different exec
 * rules, so a green result there proves nothing about the app. That trap is already documented
 * on TermuxExecSegfaultTest and it cost real time once.
 *
 * WHAT IT PROVES, IN THE TICKET'S OWN ORDER:
 *
 *  1. `TERMUX_APP__DATA_DIR` set to the PARENT of `files` lets a forked child run `git`.
 *  2. The same variable set to `files` itself does not, which is the value this build shipped.
 *  3. Without the variable at all, it does not, even with PREFIX and ROOTFS correct.
 *
 * Step 2 is the one that matters most: it is the exact value the old code used, so this test
 * fails against the old build and passes against this one.
 */
@RunWith(AndroidJUnit4::class)
class TermuxDataDirMatrixTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun linker(): String =
        if (System.getProperty("os.arch")?.contains("64") == true) "/system/bin/linker64"
        else "/system/bin/linker"

    /**
     * Run `bash -c 'git --version'` with exactly [extra] on top of a minimal environment.
     *
     * The base carries only what a child needs to start at all, so the variable under test is
     * the only difference between runs.
     */
    private fun runGit(extra: Map<String, String>): Triple<Int, String, String> {
        val provider = TermuxProvider(context)
        val root = File(provider.getRootPath())
        val bin = File(root, "usr/bin")
        val lib = File(root, "usr/lib")
        val bash = File(bin, "bash")

        val env = mutableMapOf(
            "PATH" to "${bin.absolutePath}:/system/bin:/system/xbin",
            "LD_LIBRARY_PATH" to lib.absolutePath,
            "HOME" to File(root, "home").absolutePath,
            "TMPDIR" to File(root, "usr/tmp").absolutePath,
        )
        // The preload that routes app-data execs through linker64. Without it nothing runs,
        // and the ticket measured this variable with it in place.
        val termuxExec = File(lib, "libtermux-exec-ld-preload.so")
        if (termuxExec.exists()) env["LD_PRELOAD"] = termuxExec.absolutePath
        env.putAll(extra)

        return try {
            val pb = ProcessBuilder(linker(), bash.absolutePath, "-c", "git --version")
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
            tOut.join(8000); tErr.join(8000)
            Triple(code, out.toString().trim(), err.toString().trim())
        } catch (e: Exception) {
            Triple(-1, "", "${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun prefixVars(): Map<String, String> {
        val provider = TermuxProvider(context)
        val root = File(provider.getRootPath())
        return mapOf(
            "TERMUX__ROOTFS" to root.absolutePath,
            "TERMUX__PREFIX" to File(root, "usr").absolutePath,
        )
    }

    /**
     * The prefix has to exist before any of the matrix can be measured.
     *
     * WHY THIS IS A TEST AND NOT SETUP. `initialize()` extracts the whole bundled prefix, which
     * takes long enough that hiding it in a @Before would make every run look hung. Making it
     * its own test means a failure here says "the tree is missing" rather than "exec is
     * broken", which is the distinction #289 was filed about.
     */
    @Test
    fun thePrefixIsExtractedBeforeAnythingIsMeasured() = runBlocking {
        val provider = TermuxProvider(context)
        val ready = provider.initialize()
        val bash = File(provider.getRootPath(), "usr/bin/bash")
        println("PREFIX ready=$ready root=${provider.getRootPath()} bash=${bash.exists()} len=${bash.length()}")
        assertTrue(
            "initialize() did not produce a runnable prefix, so no exec question can be " +
                "answered on this device. ready=$ready bashExists=${bash.exists()}",
            bash.isFile && bash.length() > 0,
        )
    }

    @Test
    fun theParentOfFilesIsWhatMakesAForkedChildExecute() = runBlocking {
        // The matrix below measures exec, so the tree must be there first. A JUnit method
        // order is not guaranteed, so this is done here rather than assumed from the test above.
        TermuxProvider(context).initialize()

        val dataDir = context.filesDir.parentFile?.absolutePath ?: context.filesDir.absolutePath
        val (code, out, err) = runGit(prefixVars() + ("TERMUX_APP__DATA_DIR" to dataDir))
        println("DATA_DIR_PARENT [$dataDir] exit=$code out=[$out] err=[${err.take(300)}]")
        assertTrue(
            "With TERMUX_APP__DATA_DIR set to the parent of files, a forked child must run. " +
                "exit=$code out=[$out] err=[${err.take(300)}]",
            code == 0 && out.contains("git version"),
        )
    }

    /**
     * The VALUE question, which this emulator cannot settle, recorded honestly.
     *
     * WHAT WAS MEASURED HERE. With the prefix extracted, all three values produce
     * `git version 2.56.0` on this emulator: the parent of `files`, `files` itself, and the
     * legacy `/data/data/<pkg>` form. Only their ABSENCE fails, with exit 126 and a
     * Permission denied naming the child binary.
     *
     * WHAT THE TICKET MEASURED ON HARDWARE. The same matrix on the owner's phone showed
     * `files` failing and its parent working. So the value does matter on at least one real
     * device, and this emulator cannot reproduce the difference.
     *
     * WHY THE PARENT IS STILL THE RIGHT ANSWER. libtermux-exec's variable means the app's
     * data DIRECTORY, not its files directory: `files` is a child of it. The parent is
     * therefore the value the library is documented to want, it is the value the reporter's
     * hardware required, and it costs nothing where both work. This test records that as a
     * deliberate choice rather than an accident, and it fails only if the parent stops
     * working, which is the one thing that would make the choice wrong.
     */
    @Test
    fun bothValuesWorkHereButTheParentIsTheDocumentedOne() = runBlocking {
        TermuxProvider(context).initialize()
        val parent = context.filesDir.parentFile?.absolutePath ?: context.filesDir.absolutePath
        val files = context.filesDir.absolutePath

        val withParent = runGit(prefixVars() + ("TERMUX_APP__DATA_DIR" to parent))
        val withFiles = runGit(prefixVars() + ("TERMUX_APP__DATA_DIR" to files))
        println("VALUE_PARENT [$parent] exit=${withParent.first} out=[${withParent.second}]")
        println("VALUE_FILES [$files] exit=${withFiles.first} out=[${withFiles.second}]")

        assertTrue(
            "The parent of files must make a forked child execute. It is the documented " +
                "meaning of the variable and the value the reporter's hardware required. " +
                "exit=${withParent.first} out=[${withParent.second}] err=[${withParent.third.take(200)}]",
            withParent.first == 0 && withParent.second.contains("git version"),
        )
        // The `files` value is deliberately NOT asserted either way. On this emulator it also
        // works, on the reporter's phone it did not, and a test that pinned either answer
        // would encode one device's behaviour as a rule.
    }

    @Test
    fun prefixAndRootfsAloneAreNotEnough() = runBlocking {
        TermuxProvider(context).initialize()
        val (code, out, err) = runGit(prefixVars())
        println("NO_DATA_DIR exit=$code out=[$out] err=[${err.take(300)}]")
        // MEASURED ON THIS EMULATOR: exit 126, with
        //   bash: line 1: <prefix>/usr/bin/git: Permission denied
        // That is the ticket's headline claim reproducing exactly, and it is the half of the
        // report this device confirms. PREFIX and ROOTFS are both set and neither helps.
        assertTrue(
            "TERMUX__PREFIX and TERMUX__ROOTFS together must not be sufficient, which is the " +
                "whole point of the ticket. exit=$code out=[$out] err=[${err.take(300)}]",
            !(code == 0 && out.contains("git version")),
        )
        assertTrue(
            "and the failure must be the ticket's own: a permission refusal naming the " +
                "child binary, not a missing file. err=[${err.take(300)}]",
            err.contains("Permission denied") || err.contains("permission denied"),
        )
    }

    @Test
    fun theProviderItselfNowUsesTheWorkingValue() = runBlocking {
        // The end-to-end claim: the app's own probe, which uses buildEnv, must find git running.
        val provider = TermuxProvider(context)
        provider.initialize()
        val probe = provider.probeCommand("git")
        println("PROVIDER_PROBE git=${probe.state} exit=${probe.exitCode} out=[${probe.output.take(200)}]")
        assertTrue(
            "termuxStatus must report git as RUNS through the app's own environment. " +
                "state=${probe.state} exit=${probe.exitCode} out=[${probe.output.take(300)}]",
            probe.state == TermuxProvider.CommandProbe.RUNS,
        )
    }
}
