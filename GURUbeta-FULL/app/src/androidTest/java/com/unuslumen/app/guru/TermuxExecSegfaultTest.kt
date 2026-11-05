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
 * Issue 317: every binary in the bundled prefix segfaults (exit 139) when
 * termuxExec runs it, while the same prefix serves Python fine.
 *
 * WHY THIS IS A DEVICE TEST AND NOT A JVM TEST. The question is what the kernel
 * and the dynamic linker do with these ELFs in the APP's SELinux domain. A
 * run-as or adb-shell run is a different domain that does not carry Android's
 * W^X rule, so a green result there proves nothing about the app — the same trap
 * TermuxAptDomainTest already documents.
 *
 * The tests are ordered so each one rules out one layer, and the first failure
 * names the layer that broke:
 *
 *  1. execWithEnv with NO preload at all. If this segfaults, the binary or the
 *     loader is the fault and no shim is involved.
 *  2. The same exec with ONLY libtermux-exec-ld-preload.so. Isolates the
 *     termux-exec layer.
 *  3. The same exec with ONLY libpathshim.so. Isolates the shim.
 *  4. The same exec with BOTH, which is what the app actually does. If 1 to 3
 *     pass and this fails, the two libraries collide.
 *
 * Each stage reports the real exit code and both streams, so a failure says
 * which stage and what the process actually did rather than "it crashed".
 */
@RunWith(AndroidJUnit4::class)
class TermuxExecSegfaultTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun domain(): String =
        runCatching { File("/proc/self/attr/current").readText().trim().trim('\u0000') }
            .getOrDefault("unknown")

    private fun linker(): String =
        if (System.getProperty("os.arch")?.contains("64") == true) {
            "/system/bin/linker64"
        } else {
            "/system/bin/linker"
        }

    /** Run bash with an explicitly chosen preload list, in this app's domain. */
    private fun runBashWithPreload(preloads: List<String>): Triple<Int, String, String> {
        val provider = TermuxProvider(context)
        val root = File(provider.getRootPath())
        val bin = File(provider.getBinPath())
        val lib = File(provider.getLibPath())
        val bash = File(bin, "bash")

        val env = mutableMapOf(
            "PREFIX" to File(root, "usr").absolutePath,
            "HOME" to File(root, "home").absolutePath,
            "TMPDIR" to File(root, "usr/tmp").absolutePath,
            "PATH" to "${bin.absolutePath}:/system/bin:/system/xbin",
            "LD_LIBRARY_PATH" to lib.absolutePath,
            "TERMUX__ROOTFS" to root.absolutePath,
            "TERMUX__PREFIX" to File(root, "usr").absolutePath,
            "TERMUX_PREFIX" to File(root, "usr").absolutePath,
            "GURU_SHIM_FROM" to "/data/data/com.termux/files",
            "GURU_SHIM_TO" to root.absolutePath,
        )
        if (preloads.isNotEmpty()) env["LD_PRELOAD"] = preloads.joinToString(":")

        return try {
            val pb = ProcessBuilder(linker(), bash.absolutePath, "-c", "echo ALIVE; exit 7")
            pb.environment().putAll(env)
            pb.directory(File(root, "home"))
            pb.redirectErrorStream(false)
            val proc = pb.start()
            val out = StringBuilder()
            val err = StringBuilder()
            val tOut = Thread { runCatching { proc.inputStream.bufferedReader().use { out.append(it.readText()) } } }
            val tErr = Thread { runCatching { proc.errorStream.bufferedReader().use { err.append(it.readText()) } } }
            tOut.start(); tErr.start()
            val code = proc.waitFor()
            tOut.join(5000); tErr.join(5000)
            Triple(code, out.toString().trim(), err.toString().trim())
        } catch (e: Exception) {
            Triple(-1, "", "${e.javaClass.simpleName}: ${e.message}")
        }
    }

    @Test
    fun runsInTheAppDomainNotTheShellDomain() {
        val d = domain()
        println("SELinux domain under test: $d")
        assertTrue(
            "This test must run in the app domain, but ran as '$d'. A result from " +
                "the shell or runas domain proves nothing about the app.",
            d.startsWith("u:r:untrusted_app"),
        )
    }

    /**
     * Stage 0: the prefix is actually installed, so a later failure is about
     * exec rather than about a missing tree.
     */
    @Test
    fun thePrefixExists() = runBlocking {
        val provider = TermuxProvider(context)
        provider.initialize()
        val bash = File(provider.getBinPath(), "bash")
        val preload = File(provider.getLibPath(), "libtermux-exec-ld-preload.so")
        println("root=${provider.getRootPath()}")
        println("bash=${bash.absolutePath} exists=${bash.exists()} len=${bash.length()} exec=${bash.canExecute()}")
        println("termux-exec preload exists=${preload.exists()}")
        assertTrue("bash is missing from the prefix", bash.isFile && bash.length() > 0)
    }

    /**
     * Stage 1: no preload at all.
     *
     * A segfault here means the binary itself, the loader, or the page-size rule
     * is the fault, and neither preload library is implicated.
     */
    @Test
    fun bashRunsWithNoPreload() = runBlocking {
        TermuxProvider(context).initialize()
        val (code, out, err) = runBashWithPreload(emptyList())
        println("NO_PRELOAD exit=$code out=[$out] err=[${err.take(400)}]")
        assertTrue(
            "bash did not run without any preload. exit=$code out=[$out] err=[${err.take(400)}]",
            code == 7 && out.contains("ALIVE"),
        )
    }

    /**
     * Stage 2: termux-exec's preload alone, which is what routes app-data execs
     * through linker64.
     */
    @Test
    fun bashRunsWithTermuxExecPreloadOnly() = runBlocking {
        val provider = TermuxProvider(context)
        provider.initialize()
        val preload = File(provider.getLibPath(), "libtermux-exec-ld-preload.so")
        assertTrue("libtermux-exec-ld-preload.so is missing", preload.isFile)
        val (code, out, err) = runBashWithPreload(listOf(preload.absolutePath))
        println("TERMUXEXEC_ONLY exit=$code out=[$out] err=[${err.take(400)}]")
        assertTrue(
            "bash did not run with only the termux-exec preload. exit=$code " +
                "out=[$out] err=[${err.take(400)}]",
            code == 7 && out.contains("ALIVE"),
        )
    }

    /**
     * Stage 3: the path shim alone.
     *
     * The shim hooks open/openat/stat/access/execve/opendir/fopen/scandir and
     * every hook dereferences a dlsym result with no null check, so a symbol
     * bionic does not export at this API level is a null call, which the kernel
     * reports as SIGSEGV.
     */
    @Test
    fun bashRunsWithPathShimOnly() = runBlocking {
        val provider = TermuxProvider(context)
        provider.initialize()
        val shim = File(context.applicationInfo.nativeLibraryDir, "libpathshim.so")
        assertTrue("libpathshim.so is not in nativeLibraryDir", shim.isFile)
        val (code, out, err) = runBashWithPreload(listOf(shim.absolutePath))
        println("SHIM_ONLY exit=$code out=[$out] err=[${err.take(400)}]")
        assertTrue(
            "bash did not run with only the path shim. exit=$code out=[$out] err=[${err.take(400)}]",
            code == 7 && out.contains("ALIVE"),
        )
    }

    /**
     * Stage 4: both preloads together, in the order buildEnv() uses. This is the
     * exact shape every termuxExec call runs.
     */
    @Test
    fun bashRunsWithBothPreloadsInTheAppOrder() = runBlocking {
        val provider = TermuxProvider(context)
        provider.initialize()
        val termuxExec = File(provider.getLibPath(), "libtermux-exec-ld-preload.so")
        val shim = File(context.applicationInfo.nativeLibraryDir, "libpathshim.so")
        val (code, out, err) = runBashWithPreload(listOf(termuxExec.absolutePath, shim.absolutePath))
        println("BOTH_PRELOADS exit=$code out=[$out] err=[${err.take(400)}]")
        assertTrue(
            "bash did not run with both preloads in the app's order. exit=$code " +
                "out=[$out] err=[${err.take(400)}]",
            code == 7 && out.contains("ALIVE"),
        )
    }

    /**
     * Issue 317's image half: Pillow and numpy must IMPORT, not merely be present.
     *
     * This is the claim the reporter actually cared about. processFile already
     * returned dimensions and OCR text, so image handling looked supported while
     * there was nothing to call for the pixels. Shipping the packages is not the
     * same fact as being able to use them: a compiled extension with a missing
     * or misaligned DT_NEEDED fails at import, and the failure arrives as
     * "cannot locate symbol" the first time an agent tries to crop a photo.
     *
     * The test therefore does real pixel work — opens a generated image, resizes
     * it, reads a pixel back, and runs an array op in numpy — so a pass means
     * the whole stack is usable rather than present on disk.
     */
    @Test
    fun pillowAndNumpyImportAndDoRealPixelWork() = runBlocking {
        val python = com.unuslumen.app.util.shell.PythonProvider(context)
        val runtime = python.initialize()
        println("python available=${runtime.available} version=${runtime.version}")
        println("image tooling=${runtime.imageTooling?.describe()}")

        assertTrue(
            "The Python runtime itself is not available, so this cannot test the image stack: " +
                "${runtime.failure} ${runtime.detail}",
            runtime.available,
        )

        val script = """
import sys
from PIL import Image, ImageDraw
import numpy as np

# Build a 64x32 image, draw into it, resize it, and read a pixel back. Every
# step touches a different compiled extension: _imaging for the buffer,
# _imagingft is not needed but _imaging is, and numpy for the array copy.
im = Image.new("RGB", (64, 32), (10, 20, 30))
d = ImageDraw.Draw(im)
d.rectangle((0, 0, 31, 15), fill=(255, 0, 0))
big = im.resize((128, 64))
px = big.getpixel((5, 5))

arr = np.asarray(im)
mean = float(arr.mean())

print("PIL", Image.__version__)
print("NUMPY", np.__version__)
print("PIXEL", px)
print("SIZE", big.size)
print("MEAN", round(mean, 3))
""".trimIndent()

        val (exitCode, stdout, stderr) = runPython(python, script)
        println("IMAGE PROBE exit=$exitCode out=[$stdout] err=[${stderr.take(600)}]")

        assertTrue(
            "The image probe did not exit 0. This is the real answer to issue 317's image " +
                "half. exit=$exitCode stderr=${stderr.take(600)}",
            exitCode == 0,
        )
        assertTrue("PIL did not report a version: $stdout", stdout.contains("PIL "))
        assertTrue("numpy did not report a version: $stdout", stdout.contains("NUMPY "))
        assertTrue(
            "The resize produced no pixel, so the imaging extension did not do real work: " +
                stdout,
            stdout.contains("PIXEL"),
        )
    }

    /** Run a script through the bundled interpreter and collect both streams. */
    private fun runPython(
        python: com.unuslumen.app.util.shell.PythonProvider,
        script: String,
    ): Triple<Int, String, String> {
        val file = java.io.File(context.cacheDir, "guru_317_probe_${System.nanoTime()}.py")
        return try {
            file.writeText(script)
            val pb = ProcessBuilder(python.buildExecCommand(file.absolutePath))
            pb.environment().putAll(python.getPythonEnv(null))
            val proc = pb.start()
            val out = StringBuilder()
            val err = StringBuilder()
            val tOut = Thread { runCatching { proc.inputStream.bufferedReader().use { out.append(it.readText()) } } }
            val tErr = Thread { runCatching { proc.errorStream.bufferedReader().use { err.append(it.readText()) } } }
            tOut.start(); tErr.start()
            val code = proc.waitFor()
            tOut.join(10000); tErr.join(10000)
            Triple(code, out.toString().trim(), err.toString().trim())
        } catch (e: Exception) {
            Triple(-1, "", "${e.javaClass.simpleName}: ${e.message}")
        } finally {
            file.delete()
        }
    }

    /**
     * The end-to-end claim issue 317 makes: termuxExec gets exit 139 from bash,
     * git, curl and apt, and termuxInstall dies before apt runs.
     */
    @Test
    fun everyBinaryTheIssueNamesActuallyRuns() = runBlocking {
        val provider = TermuxProvider(context)
        provider.initialize()
        val failures = mutableListOf<String>()
        for (name in listOf("bash", "git", "curl", "apt")) {
            val probe = provider.probeCommand(name)
            println("PROBE $name -> ${probe.state} exit=${probe.exitCode} path=${probe.path} out=[${probe.output.take(200)}]")
            if (probe.state != TermuxProvider.CommandProbe.RUNS) {
                failures.add("$name=${probe.state} exit=${probe.exitCode} ${probe.output.take(160)}")
            }
        }
        assertTrue("These binaries the issue names did not run: $failures", failures.isEmpty())
    }
}
