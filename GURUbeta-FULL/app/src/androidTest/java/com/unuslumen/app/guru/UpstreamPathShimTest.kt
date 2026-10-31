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
 * Proves that a package with a COMPILED-IN upstream path works with no root and
 * no adb.
 *
 * WHY THIS EXISTS. Issue 31 found figlet running but unable to open its font,
 * because it looks in `/data/data/com.termux/files/usr/share/figlet` — a string
 * compiled into the ELF, printed by `figlet -I3`. That path cannot be rewritten
 * in a file, cannot be created without root, and cannot be faked with proot
 * because Android's seccomp filter blocks ptrace for ordinary apps.
 *
 * The answer is libpathshim.so, an LD_PRELOAD library that rewrites a leading
 * upstream prefix at the libc boundary. This test is the proof that it works on
 * a stock unrooted device, and it fails loudly if the shim is absent, inert, or
 * loaded but not translating.
 */
@RunWith(AndroidJUnit4::class)
class UpstreamPathShimTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun runsInTheAppDomainNotTheShellDomain() {
        val d = runCatching { File("/proc/self/attr/current").readText().trim().trim('\u0000') }
            .getOrDefault("unknown")
        println("SELinux domain under test: $d")
        assertTrue("Must run in the app domain, ran as '$d'", d.startsWith("u:r:untrusted_app"))
    }

    /**
     * No root, no adb, and the test says so.
     *
     * If either of these ever became a prerequisite, this test is where the
     * project rule is enforced: everything GURU does must work on a stock phone
     * with nothing attached.
     */
    @Test
    fun thisRunsWithoutRootOrAdb() {
        val su = listOf("/system/bin/su", "/system/xbin/su", "/sbin/su")
            .firstOrNull { File(it).exists() }
        println("su present: $su")
        assertTrue(
            "This test found a root binary at $su. The point is that the feature works WITHOUT root.",
            su == null,
        )
    }

    /**
     * figlet must find its own fonts through the shim.
     *
     * No `-d` flag is passed. If the shim is not translating the compiled-in
     * path this fails with "standard: Unable to open font file", which is the
     * exact failure issue 31 recorded.
     */
    @Test
    fun figletFindsItsFontsThroughTheCompiledUpstreamPath() = runBlocking {
        val provider = TermuxProvider(context)
        provider.initialize()

        val fonts = File(provider.getRootPath(), "usr/share/figlet/standard.flf")
        assertTrue("standard.flf is missing from the prefix", fonts.isFile)

        val result = provider.execBash("echo GURU | figlet 2>&1")
        println("figlet through shim :: exit=${result.exitCode}")
        println("output:\n${result.stdout.take(400)}")

        assertTrue(
            "figlet could not open its font. The compiled-in upstream path is not " +
                "being redirected by libpathshim.so. output=${result.stdout.take(400)}",
            !result.stdout.contains("Unable to open font file"),
        )
        assertTrue(
            "figlet produced no output. exit=${result.exitCode} out=${result.stdout.take(300)}",
            result.stdout.isNotBlank(),
        )
    }
}
