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
 * Issue 291: aapt, aapt2 and zipalign failing to link on a missing libc++ symbol.
 *
 * THE TICKET'S CLAIM, WHICH IS DIRECTLY MEASURABLE:
 *
 *   CANNOT LINK EXECUTABLE ".../bin/aapt2": cannot locate symbol
 *   "_ZNSt6__ndk113__hash_memoryEPKvm" referenced by ".../bin/aapt2"
 *
 * That symbol is `std::__ndk1::__hash_memory(void const*, unsigned long)`, and the ticket says
 * the bundled `libc++_shared.so` does not export it, so the prefix carries a version skew
 * between its C++ runtime and the three tools built against a newer one.
 *
 * WHY THIS IS A DEVICE TEST RATHER THAN A FILE CHECK. The answer depends on the bytes of the
 * library that ships inside the APK and gets extracted on a real install, and on whether the
 * linker resolves the symbol against it. Reading a file on a build machine would prove nothing
 * about either.
 *
 * THE TEST IS WRITTEN TO PASS EITHER WAY, and that is deliberate. If the tools link, this is
 * already fixed and the test is a regression guard. If they do not, the test fails with the
 * linker's own words, which is the evidence the ticket asked for. It does not assert a defect
 * as expected behaviour.
 */
@RunWith(AndroidJUnit4::class)
class AaptLinkTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun linker(): String =
        if (System.getProperty("os.arch")?.contains("64") == true) "/system/bin/linker64"
        else "/system/bin/linker"

    /** Run one bundled tool through the linker, exactly as the ticket's reproduction does. */
    private fun runTool(name: String, arg: String): Triple<Int, String, String> {
        val provider = TermuxProvider(context)
        val root = File(provider.getRootPath())
        val binary = File(root, "usr/bin/$name")
        if (!binary.isFile) return Triple(-98, "", "$name is not present in the prefix")

        val env = mapOf(
            "LD_LIBRARY_PATH" to File(root, "usr/lib").absolutePath,
            "PREFIX" to File(root, "usr").absolutePath,
            "PATH" to "/system/bin:/system/xbin",
        )
        return try {
            val args = mutableListOf(linker(), binary.absolutePath)
            if (arg.isNotEmpty()) args.add(arg)
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
    fun thePrefixIsExtractedBeforeAnythingIsMeasured() = runBlocking {
        val provider = TermuxProvider(context)
        val ready = provider.initialize()
        val bash = File(provider.getRootPath(), "usr/bin/bash")
        println("PREFIX ready=$ready bash=${bash.exists()}")
        assertTrue("initialize() did not produce a prefix, so nothing can be measured", bash.isFile)
    }

    @Test
    fun aaptToolsLinkOrSayExactlyWhyNot() = runBlocking {
        TermuxProvider(context).initialize()

        val missing = mutableListOf<String>()
        val unlinked = mutableListOf<String>()

        /*
         * EACH TOOL IS ASKED IN ITS OWN DIALECT, and getting that wrong looked like a fault.
         * The first version asked all three for "version": aapt2 and aapt answer it with exit 0,
         * but zipalign rejects "version" and "-h" alike, printing "invalid option" and exiting 2.
         * That is a working binary refusing a bad argument, not a link failure, and the test
         * would have reported it as one. zipalign with NO argument prints its usage, which is
         * the form that measures the binary rather than my guess at its flags.
         */
        val dialects = mapOf(
            "aapt2" to "version",
            "aapt" to "version",
            "zipalign" to "",
        )
        for ((name, arg) in dialects) {
            val (code, out, err) = runTool(name, arg)
            val combined = "$out $err".trim()
            println("AAPT $name exit=$code out=[${out.take(120)}] err=[${err.take(240)}]")

            when {
                code == -98 -> missing.add(name)
                combined.contains("CANNOT LINK EXECUTABLE") -> unlinked.add("$name: ${combined.take(240)}")
                // ASSERT ON OUTPUT, NOT EXIT CODE (#334's own lesson). A tool that linked but
                // printed nothing would pass an exit-code check while proving nothing.
                combined.isBlank() -> unlinked.add("$name linked but printed nothing (exit=$code)")
            }
        }

        // A tool that is not shipped is a different fact from a tool that will not link, and
        // the ticket is about the second one. Both are reported so neither hides the other.
        assertTrue(
            "These tools are not in the prefix at all, which is a packaging fault rather " +
                "than the libc++ skew this ticket describes: $missing",
            missing.isEmpty(),
        )
        assertTrue(
            "These tools cannot link. The ticket's diagnosis says libc++_shared.so is too " +
                "old to export the symbol they need: $unlinked",
            unlinked.isEmpty(),
        )
    }

    /**
     * Whether the shipped library exports the symbol the ticket names.
     *
     * This is the second half of the diagnosis and it is independent of whether the tools
     * happen to run: a library can export the symbol while a tool fails for another reason, and
     * the reverse. Both facts together are what the ticket asserts.
     */
    /**
     * The prefix's own libc++ must export the symbol, which is the fix rather than a symptom.
     *
     * A byte search rather than `nm`, for the same reason the production check uses one: the
     * question is whether the string is in the library, and spawning a process to answer it
     * would make the test depend on a tool that may itself be missing.
     */
    @Test
    fun thePrefixLibcxxExportsTheSymbolTheToolsNeed() = runBlocking {
        TermuxProvider(context).initialize()
        val provider = TermuxProvider(context)
        val cxx = File(provider.getRootPath(), "usr/lib/libc++_shared.so")
        println("LIBCXX exists=${cxx.isFile} size=${cxx.length()}")
        assertTrue("libc++_shared.so is not in the prefix's lib directory", cxx.isFile)

        val bytes = cxx.readBytes().toString(Charsets.ISO_8859_1)
        val hasSymbol = bytes.contains("__hash_memory")
        println("LIBCXX exports__hash_memory=$hasSymbol size=${bytes.length}")
        assertTrue(
            "The prefix's libc++_shared.so does not export __hash_memory, so aapt, aapt2 and " +
                "zipalign cannot link. Issue 291's fix installs the re-toolchain copy, which " +
                "does export it, into the prefix.",
            hasSymbol,
        )
    }
}