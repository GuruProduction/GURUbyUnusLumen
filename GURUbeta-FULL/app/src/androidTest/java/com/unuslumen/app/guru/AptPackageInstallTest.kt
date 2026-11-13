// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.guru

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.unuslumen.app.util.shell.DpkgRecords
import com.unuslumen.app.util.shell.TermuxProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Issue 4's own reproduction, run for real: `apt update` then `apt install -y figlet`.
 *
 * WHAT #4 CLAIMED AND WHAT IS NOW TRUE. It reported apt dying with exit 100 on every operation
 * because dpkg's database and apt's config looked for hardcoded `com.termux` paths. Every
 * mechanism it asked for is now in the prefix: `APT_CONFIG` in the shared environment, `--admindir`
 * and `--instdir` in the drop-in, the compatibility root verified by `readlink`, and a
 * records-relocation pass that runs after each install. What has NEVER been measured is whether
 * the thing a person actually types works, and that is the only question this test asks.
 *
 * FIGLET IS THE TICKET'S OWN PACKAGE, chosen because it fails in the most informative way: the
 * binary is tiny and the data it needs is a separate directory of fonts, so an install that
 * delivers a binary and no fonts reports success and then cannot run. That is the exact shape the
 * ticket documented.
 */
@RunWith(AndroidJUnit4::class)
class AptPackageInstallTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun aptUpdateReachesTheRepositoryThroughTheTorCircuit() = runBlocking {
        TermuxProvider(context).initialize()
        val provider = TermuxProvider(context)
        val result = provider.execAptUpdate()
        val combined = (result.stdout + "\n" + result.stderr).trim()
        println("APT_UPDATE exit=${result.exitCode} out=[${combined.take(900)}]")

        assertTrue(
            "apt update must not fail on its own configuration, which was issue 4's whole " +
                "complaint. out=[${combined.take(900)}]",
            !combined.contains("Unable to determine a suitable packaging system type"),
        )
        assertTrue(
            "apt update must not read the upstream prefix. out=[${combined.take(900)}]",
            !combined.contains("${DpkgRecords.UPSTREAM_FILES_ROOT}/etc/apt"),
        )
        assertEquals("apt update must reach the repository. out=[${combined.take(900)}]", 0, result.exitCode)
    }

    @Test
    fun aptInstallsFigletAndTheFontDirectoryLandsWithIt() = runBlocking {
        TermuxProvider(context).initialize()
        val provider = TermuxProvider(context)
        val install = provider.execAptInstall("figlet")
        println("APT_INSTALL exit=${install.exitCode} out=[${(install.stdout + install.stderr).take(1200)}]")

        val verdict = provider.checkInstall("figlet")
        println(
            "FIGLET state=${verdict.state} declared=${verdict.declared} present=${verdict.present} " +
                "complete=${verdict.complete} missing=${verdict.missing}",
        )

        /*
         * The ticket's second fault, directly. figlet's binary reads a .flf font file, and the
         * fault was the binary arriving while the fonts did not. Both are asserted separately
         * because "the package installed" and "the tool runs" are different claims, and the
         * ticket is the case where the first was true and the second was not.
         */
        val prefix = File(provider.getRootPath(), "usr")
        val binary = File(prefix, "bin/figlet")
        val fontDir = File(prefix, "share/figlet")

        println("FIGLET binary=${binary.isFile} fontDir=${fontDir.isDirectory}")
        assertTrue("the figlet binary must be on disk at ${binary.absolutePath}", binary.isFile)
        assertTrue(
            "figlet's font directory must be on disk at ${fontDir.absolutePath}, which is the " +
                "data file half of the ticket's second fault",
            fontDir.isDirectory,
        )

        val run = provider.execBinary("figlet", listOf("GURU"))
        println("FIGLET_RUN exit=${run.exitCode} out=[${(run.stdout + run.stderr).take(400)}]")
        assertEquals(
            "figlet must RUN, which is what a person actually asked for. " +
                "out=[${(run.stdout + run.stderr).take(400)}]",
            0,
            run.exitCode,
        )
        assertTrue(
            "figlet must produce its banner rather than a font error. " +
                "out=[${(run.stdout + run.stderr).take(400)}]",
            run.stdout.contains("_") || run.stdout.contains("|"),
        )
        assertTrue(
            "figlet must not report a missing font. out=[${(run.stdout + run.stderr).take(400)}]",
            !(run.stdout + run.stderr).contains("Unable to open font file"),
        )
    }

    @Test
    fun dpkgQuerySeesThePackageAptInstalled() = runBlocking {
        TermuxProvider(context).initialize()
        val provider = TermuxProvider(context)
        val root = File(provider.getRootPath())

        // The third fault: `dpkg -l` said "no packages found" while status held the package,
        // because a bare dpkg read the compiled-in com.termux admin dir.
        val query = provider.execInTermux("dpkg -l figlet 2>&1; true")
        println("DPKG_QUERY exit=${query.exitCode} out=[${(query.stdout + query.stderr).take(700)}]")

        val combined = query.stdout + "\n" + query.stderr
        assertTrue(
            "dpkg -l must find the package apt installed, rather than reporting that no package " +
                "matches. out=[${combined.take(700)}]",
            !combined.contains("no packages found matching"),
        )

        val status = File(root, "usr/var/lib/dpkg/status")
        assertTrue("dpkg's status database must exist at ${status.absolutePath}", status.isFile)
        assertTrue(
            "the status database must record figlet after a successful install",
            DpkgRecords.installedPackages(status.readText()).contains("figlet"),
        )
    }
}
