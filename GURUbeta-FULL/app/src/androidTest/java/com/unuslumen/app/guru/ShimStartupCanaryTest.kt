// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.guru

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The shim startup canary issue 334 asks for, which would have caught #328 in one second.
 *
 * WHY `/system/bin/echo` AND NOTHING FROM THE PREFIX. `echo` has no relationship to Termux, no
 * relationship to exec restrictions, and no reason to fail. If it crashes with libpathshim.so
 * preloaded, the shim is the only possible cause. That is the one test that cannot be fooled by
 * an environment-specific story: it does not matter whether the prefix is extracted, what
 * TERMUX_APP__DATA_DIR is, or which device this runs on.
 *
 * WHAT IT WOULD HAVE CAUGHT. #328 was every hook calling an unchecked `dlsym(RTLD_NEXT, ...)`,
 * and bionic calls `access()` during `__libc_preinit` before the linker can answer. Every
 * preloaded process died with SIGSEGV before `main()`, so the whole Termux exec path looked
 * like the kernel refusing execution. Preloading into `echo` reproduces that in one command.
 *
 * THE CONTROL IS PART OF THE TEST. A canary that also fails without the library proves
 * nothing, so the same command runs with no preload first. Both results are asserted, and a
 * failure names which one broke.
 */
@RunWith(AndroidJUnit4::class)
class ShimStartupCanaryTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val echo get() = File("/system/bin/echo")

    private fun linker(): String =
        if (System.getProperty("os.arch")?.contains("64") == true) "/system/bin/linker64"
        else "/system/bin/linker"

    /** Run `/system/bin/echo <word>`, optionally with the shim preloaded. */
    private fun runEcho(word: String, preloadShim: Boolean): Triple<Int, String, String> {
        val args = mutableListOf(linker(), echo.absolutePath, word)
        val env = mutableMapOf("PATH" to "/system/bin:/system/xbin")
        if (preloadShim) {
            val shim = File(context.applicationInfo.nativeLibraryDir, "libpathshim.so")
            if (!shim.isFile) return Triple(-99, "", "libpathshim.so is not in nativeLibraryDir")
            env["LD_PRELOAD"] = shim.absolutePath
            /*
             * THE SHIM'S OWN PREFIX VARIABLES ARE SET, because a shim that is inert is not the
             * thing under test. #328 happened with the shim ACTIVE, when bionic called into the
             * access hook during preinit.
             */
            env["GURU_SHIM_FROM"] = "/data/data/com.termux/files"
            env["GURU_SHIM_TO"] = File(context.filesDir, "termux").absolutePath
        }
        return try {
            val pb = ProcessBuilder(args)
            pb.environment().clear()
            pb.environment().putAll(env)
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

    @Test
    fun echoRunsWithNoPreloadAtAll() {
        // The control. If this fails, the harness is wrong and nothing below means anything.
        val (code, out, err) = runEcho("alive", preloadShim = false)
        println("CANARY_CONTROL exit=$code out=[$out] err=[${err.take(200)}]")
        assertEquals("the control run must exit 0. err=[${err.take(300)}]", 0, code)
        assertEquals("the control run must print its argument", "alive", out)
    }

    @Test
    fun theShimDoesNotKillAProcessItIsPreloadedInto() {
        // The canary. #328 made this segfault with SIGSEGV before main(), so exit was 139,
        // stdout was empty and nothing was printed about why.
        val (code, out, err) = runEcho("alive", preloadShim = true)
        println("CANARY_SHIM exit=$code out=[$out] err=[${err.take(200)}]")

        assertTrue(
            "libpathshim.so killed /system/bin/echo: exit=$code out=[$out] err=[${err.take(300)}]. " +
                "Exit 139 or an empty stdout here is #328 returning: a hook is calling a null " +
                "dlsym during libc preinit.",
            code == 0,
        )
        assertEquals(
            "The shim must be transparent: the process must still print what it was asked to. " +
                "Empty stdout with exit 0 would mean the process died before main() ran.",
            "alive",
            out,
        )
    }

    /**
     * The shim must not change what a path outside its own prefix does.
     *
     * MEASURED FIRST, AND THE FIRST ATTEMPT WAS WRONG. It read /system/build.prop through the
     * shim and failed with exit 1, which looks like the shim breaking an unrelated path. It is
     * not: that file is refused to this app UID by SELinux whether or not any library is
     * loaded, so the test was measuring a file permission and calling it a shim fault.
     *
     * The comparison is therefore against the same command with NO preload, which is the only
     * form that can attribute a difference to the shim. If both runs behave the same, the shim
     * is transparent for that path whatever the answer is.
     */
    @Test
    fun theShimDoesNotChangeWhatAPathOutsideItsPrefixDoes() {
        val shim = File(context.applicationInfo.nativeLibraryDir, "libpathshim.so")
        assertTrue("libpathshim.so is not in nativeLibraryDir", shim.isFile)

        fun runCat(preload: Boolean): Pair<Int, Int> {
            val env = mutableMapOf("PATH" to "/system/bin:/system/xbin")
            if (preload) {
                env["LD_PRELOAD"] = shim.absolutePath
                env["GURU_SHIM_FROM"] = "/data/data/com.termux/files"
                env["GURU_SHIM_TO"] = File(context.filesDir, "termux").absolutePath
            }
            return runCatching {
                val pb = ProcessBuilder(linker(), "/system/bin/cat", "/system/build.prop")
                pb.environment().clear()
                pb.environment().putAll(env)
                val proc = pb.start()
                val out = proc.inputStream.bufferedReader().use { it.readText() }
                val err = proc.errorStream.bufferedReader().use { it.readText() }
                val code = proc.waitFor()
                println("CANARY_CAT preload=$preload exit=$code bytes=${out.length} err=[${err.take(120)}]")
                code to out.length
            }.getOrElse { -1 to 0 }
        }

        val without = runCat(preload = false)
        val with = runCat(preload = true)

        assertEquals(
            "The shim changed the outcome of reading a /system path: without=$without " +
                "with=$with. A shim that alters unrelated paths is worse than the bug it fixes.",
            without,
            with,
        )
    }
}