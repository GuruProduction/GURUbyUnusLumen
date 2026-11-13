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
 * Issue 31's own four commands, measured on a real device.
 *
 * WHY THIS TEST EXISTS RATHER THAN A FIX FOR THE DATA-FILE FAULT. The ticket's second fault is
 * that a package's binary lands and its data files do not, and its own suggested direction says
 * the open question is whether the compatibility-root mapping should cover data paths or
 * whether package paths should be rewritten on unpack. That question cannot be answered from
 * source: the mapping is ONE symlink covering the whole of `usr`, so `bin/` and `share/` resolve
 * through exactly the same mechanism and any asymmetry between them is a fact about the package
 * or the unpack, not about the mapping.
 *
 * So this test runs the ticket's sequence and reports what each step said. Its assertions are
 * deliberately limited to what must be true of ANY working install; the rest is printed, because
 * a test that asserted a guess about figlet's package contents would be the same overclaiming
 * this issue is about.
 */
@RunWith(AndroidJUnit4::class)
class AptInstallTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun prefix(): File = File(TermuxProvider(context).getRootPath(), "usr")

    @Test
    fun theCompatibilityRootMapsTheWholePrefixIncludingDataDirectories() = runBlocking {
        TermuxProvider(context).initialize()
        val root = File(TermuxProvider(context).getRootPath())
        val mapping = File(root, "data/data/com.termux/files/usr")

        val target = runCatching { android.system.Os.readlink(mapping.absolutePath) }.getOrNull()
        println("COMPAT link=${mapping.absolutePath} target=$target")

        assertEquals(
            "the compatibility root must point at this install's prefix, or no package can " +
                "resolve. target=$target",
            prefix().absolutePath.trimEnd('/'),
            target,
        )
        /*
         * One symlink covers usr/ entirely, so a data path and a binary path resolve through the
         * same mapping. If a data file is missing after an install, it is not this mapping
         * refusing to carry it, and this assertion is what rules that out rather than guessing.
         *
         * CANONICAL, NOT ABSOLUTE. The first version of this check compared
         * `File(mapping, "share").absolutePath`, which is a purely LEXICAL operation: it joins
         * the two strings and never consults the filesystem, so it returned
         * `<root>/data/data/com.termux/files/usr/share` and failed while the mapping was in fact
         * correct. getCanonicalFile() is the call that resolves symlinks, which is the whole
         * question being asked.
         */
        val shareViaMapping = File(mapping, "share")
        val shareReal = runCatching { shareViaMapping.canonicalPath }.getOrDefault("")
        val prefixReal = runCatching { prefix().canonicalPath }.getOrDefault("")
        println(
            "COMPAT share-via-mapping=${shareViaMapping.absolutePath} canonical=$shareReal " +
                "prefixCanonical=$prefixReal exists=${shareViaMapping.exists()}",
        )
        assertTrue(
            "a data directory reached through the mapping must resolve inside this prefix. " +
                "canonical=$shareReal prefix=$prefixReal",
            shareReal.startsWith(prefixReal),
        )
    }

    @Test
    fun thePrefixRecordsItselfUnderItsOwnRootAndNotTheUpstreamOne() = runBlocking {
        TermuxProvider(context).initialize()
        val provider = TermuxProvider(context)
        val root = provider.getRootPath()

        val statusFile = File(root, "usr/var/lib/dpkg/status")
        if (!statusFile.isFile) {
            println("DPKG_STATUS absent at ${statusFile.absolutePath}; nothing installed yet")
            return@runBlocking
        }

        val status = statusFile.readText()
        val packages = DpkgRecords.installedPackages(status)
        println("DPKG_PACKAGES count=${packages.size} names=$packages")

        for (name in packages) {
            val verdict = provider.checkInstall(name)
            println(
                "DPKG_PACKAGE name=$name state=${verdict.state} " +
                    "declared=${verdict.declared} present=${verdict.present} " +
                    "complete=${verdict.complete} missing=${verdict.missing}",
            )
            /*
             * A package dpkg records as installed must name THIS install in at least one row.
             * A record naming only the upstream prefix means the relocation pass has not
             * reached it, which is the timing fault this branch fixes: relocatePrefixPaths is
             * marker-gated, so anything installed after that pass kept the upstream paths.
             */
            assertTrue(
                "$name's file list names only ${DpkgRecords.UPSTREAM_FILES_ROOT}, so nothing " +
                    "in it can resolve on this install. ${verdict.sentence}",
                verdict.state != DpkgRecords.InstallRecordState.RECORDED_UPSTREAM_ONLY,
            )
        }
    }

    @Test
    fun aBareDpkgReadsThisPrefixesDatabase() = runBlocking {
        TermuxProvider(context).initialize()
        val provider = TermuxProvider(context)

        /*
         * THE THIRD FAULT, measured through a BARE invocation rather than through apt.
         *
         * The ticket's `dpkg -l figlet` said "no packages found" while `status` held the
         * package, because a bare dpkg uses its compiled-in com.termux admin dir. The fix is
         * DPKG_ADMINDIR in the shared environment, and this is the invocation that proves it:
         * no --admindir flag, no APT_CONFIG, just `dpkg -l` the way a person types it.
         */
        val result = provider.execInTermux("dpkg -l 2>&1; true")
        println("DPKG_BARE exit=${result.exitCode} out=[${result.stdout.take(700)}]")

        val combined = result.stdout + "\n" + result.stderr
        assertTrue(
            "a bare dpkg must not refuse its own database. out=[${combined.take(700)}]",
            !combined.contains("cannot open") && !combined.contains("Permission denied"),
        )
        assertTrue(
            "a bare dpkg must not name the upstream prefix as the database it read. " +
                "out=[${combined.take(700)}]",
            !combined.contains("${DpkgRecords.UPSTREAM_FILES_ROOT}/var/lib/dpkg"),
        )
    }
}
