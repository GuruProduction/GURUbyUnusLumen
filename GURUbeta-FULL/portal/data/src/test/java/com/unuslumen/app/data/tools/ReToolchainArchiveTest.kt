// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile

/**
 * Holds the on-device toolchain archive to what issue 200 requires of it.
 *
 * WHY THIS EXISTS. The defect in issue 200 was not one broken function. It was a
 * tool surface that described tools which were never bundled, and every check in
 * the app agreed with the description because every check asked whether a file
 * existed. `jadx=true` came from a 5751-byte Gradle start script; the jars it
 * needed, and the JVM they needed, were absent from the whole APK.
 *
 * So there are two things to hold still, and this class holds both:
 *
 *  1. the archive genuinely contains the JVM and the four tools, with a shared
 *     library set that resolves, and
 *  2. the manifest the installer reads describes the archive that was actually
 *     packed — because a mismatch there produces a `java` that is a 32-byte text
 *     file, which fails in a way that looks nothing like its cause.
 *
 * All of this is file IO on the built asset, so it runs on the JVM in
 * milliseconds and needs no device. The device question — whether these binaries
 * actually execute inside the sandbox — is a different one and cannot be
 * answered here; it is answered by the device run recorded in the PR.
 */
class ReToolchainArchiveTest {

    // The unit-test working directory is the MODULE, so a relative path resolves
    // under portal/data. Walk up to the app module's assets and fail loudly with
    // the resolved path rather than silently asserting against nothing.
    private val archive: File by lazy {
        val candidates = listOf(
            File("../../app/src/main/assets/re-toolchain/re-toolchain.zip"),
            File("src/main/assets/re-toolchain/re-toolchain.zip"),
            File("app/src/main/assets/re-toolchain/re-toolchain.zip"),
        )
        candidates.firstOrNull { it.isFile }
            ?: error(
                "The toolchain archive is not present. Run ./fetch_re_toolchain.sh from " +
                    "GURUbeta-FULL. Working dir " + File(".").absolutePath + "; tried " +
                    candidates.joinToString { it.absolutePath },
            )
    }

    private val manifestJson: String by lazy {
        ZipFile(archive).use { z ->
            val entry = z.getEntry("re-toolchain-manifest.json")
            assertNotNull(
                "The archive has no re-toolchain-manifest.json. Without it the installer " +
                    "cannot recreate the JVM launcher symlinks, and java arrives as a text file.",
                entry,
            )
            z.getInputStream(entry).bufferedReader().use { it.readText() }
        }
    }

    private fun entryNames(): Set<String> =
        ZipFile(archive).use { z -> z.entries().toList().map { it.name }.toSet() }

    @Test
    fun theArchiveIsPresentAndSubstantial() {
        assertTrue("The toolchain archive is empty", archive.length() > 10_000_000)
    }

    /**
     * The JVM must be complete enough to RUN, not merely present.
     *
     * lib/modules is the whole Java class library image; without it `java -version`
     * fails with "Error occurred during initialization of VM". libjvm.so is the VM
     * itself. Both are checked because a trimmed JDK that lost either would still
     * look plausible in a file listing.
     */
    @Test
    fun theJvmIsCompleteEnoughToRun() {
        val names = entryNames()
        val jvm = "lib/jvm/java-21-openjdk/"
        assertTrue("java is missing", names.contains("${jvm}bin/java"))
        assertTrue("lib/modules is missing; the VM cannot start without the class library image",
            names.contains("${jvm}lib/modules"))
        assertTrue("lib/server/libjvm.so is missing; there is no VM",
            names.contains("${jvm}lib/server/libjvm.so"))
        assertTrue("libjli.so is missing; the launcher cannot bootstrap the VM",
            names.contains("${jvm}lib/libjli.so"))
    }

    /**
     * The four tools issue 200 names, plus the aligner.
     *
     * zipalign is the one the issue measured as present but unexecutable
     * (`./zipalign: Permission denied`), so shipping it in the one tree that is
     * actually runnable is the specific fix for that measurement.
     */
    @Test
    fun everyToolTheIssueNamesIsBundled() {
        val names = entryNames()
        for (tool in listOf("bin/aapt", "bin/aapt2", "bin/zipalign", "bin/apktool")) {
            assertTrue("$tool is missing from the toolchain archive", names.contains(tool))
        }
        assertTrue("apktool.jar is missing; bin/apktool is a wrapper for it alone",
            names.contains("share/java/apktool.jar"))
    }

    /**
     * The manifest's declared symlinks must match entries that are really in the
     * archive, and each must point at a file that is really in the archive.
     *
     * This is the check that catches the failure mode this whole design exists to
     * avoid: java.util.zip cannot read a unix mode, so `zip -y`'s symlink arrives
     * on the device as a small regular file. The installer fixes that by creating
     * the links from this manifest — which only works if the manifest describes
     * the archive correctly.
     */
    @Test
    fun theManifestDeclaresTheJvmLaunchersAndTheyResolve() {
        val names = entryNames()
        val expected = listOf("java", "javac", "jar", "jarsigner", "keytool")
        for (launcher in expected) {
            assertTrue(
                "The manifest does not declare bin/$launcher as a symlink. Without that " +
                    "declaration the installer extracts a 32-byte file whose content is the " +
                    "target path, and $launcher cannot be executed.",
                manifestJson.contains("\"bin/$launcher\""),
            )
            assertTrue("bin/$launcher is not an entry in the archive", names.contains("bin/$launcher"))
            assertTrue(
                "bin/$launcher is declared but its target is not in the archive, so the " +
                    "link would dangle",
                names.contains("lib/jvm/java-21-openjdk/bin/$launcher"),
            )
        }
    }

    /**
     * The shared libraries the tools name must all be inside the archive.
     *
     * The check that matters cannot live here in full — it needs to read ELF
     * dynamic sections, which is what fetch_re_toolchain.sh does at pack time and
     * refuses to pack when anything is missing. What can be asserted here is the
     * list the packer proved: the specific objects that a naive bundle omits,
     * every one of which is a CANNOT LINK EXECUTABLE at run time rather than an
     * obvious fault.
     */
    @Test
    fun theLibrariesThatWouldOtherwiseFailAtLoadTimeAreBundled() {
        val names = entryNames()
        val required = listOf(
            // named by libjvm.so
            "lib/libandroid-shmem.so",
            "lib/libandroid-spawn.so",
            // named by aapt2
            "lib/libfmt.so",
            "lib/libprotobuf.so",
            // named by aapt and aapt2
            "lib/libpng16.so",
            "lib/libexpat.so.1",
            "lib/libc++_shared.so",
            // named by libasound, which libjsound names
            "lib/libandroid-sysv-semaphore.so",
        )
        for (lib in required) {
            assertTrue("$lib is missing; the tool that needs it cannot load", names.contains(lib))
        }
    }

    /**
     * A launcher must not be shipped as a decompiler, and a decompiler must not be
     * claimed when it is not there.
     *
     * The archive deliberately carries no jadx or dex2jar jar: they are 72 MB and
     * 20 MB of pure Java, downloaded into the prefix on demand instead. This test
     * pins that decision so a future change cannot half-add one and leave the tool
     * surface claiming a decompiler that is not installable.
     */
    @Test
    fun theHeavyJavaDecompilersAreDeliberatelyNotInTheArchive() {
        val names = entryNames()
        assertTrue(
            "A jadx jar has appeared in the archive. That is 72 MB in every APK for a " +
                "tool used occasionally; add it to the on-demand download path instead " +
                "of the bundle, and update this test with the reason.",
            names.none { it.contains("jadx") && it.endsWith(".jar") },
        )
    }

    /**
     * The archive must not carry a second libc++_shared.so in a nested directory.
     *
     * The app already ships an older copy (1374336 bytes) for the apt family, and
     * this archive carries the current one (1423696 bytes). Both have the soname
     * libc++_shared.so, so two copies at once would be resolved by search order.
     * Exactly one copy, at the top of lib/, is the arrangement the archive was
     * built and reasoned about.
     */
    @Test
    fun exactlyOneLibcSharedCopyIsShipped() {
        val copies = entryNames().filter { it.endsWith("libc++_shared.so") }
        assertEquals(
            "Expected exactly one libc++_shared.so in the toolchain archive, found $copies. " +
                "More than one is resolved by search order, and the two versions in play " +
                "here differ.",
            1,
            copies.size,
        )
        assertTrue("The single libc++_shared.so must be at lib/, not nested",
            copies.single() == "lib/libc++_shared.so")
    }
}
