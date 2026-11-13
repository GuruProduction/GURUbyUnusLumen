// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.util.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Issue 209's packaging rule, as far as it can be proved without a device.
 *
 * The ticket's two measurements are what every assertion here rests on: `/data/local/tmp` is not
 * writable at app UID, and app storage under `u:r:untrusted_app` is W^X so no loose binary there
 * can execute. The native library directory is the only executable location an unprivileged app
 * has, so the adb binary must be IN it and referenced BY name.
 */
class AdbPackagingTest {

    /**
     * The repository root, found by walking up from the working directory.
     *
     * A hardcoded absolute path would make this test pass on this machine and fail everywhere
     * else, which is the opposite of useful for a rule about packaging.
     */
    private val root: File = run {
        var candidate = File(System.getProperty("user.dir")).absoluteFile
        while (candidate.parentFile != null && !File(candidate, "GURUbeta-FULL").isDirectory) {
            candidate = candidate.parentFile!!
        }
        File(candidate, "GURUbeta-FULL").takeIf { it.isDirectory } ?: candidate
    }

    /** The provider's own source, so the rules can be checked without a Context. */
    private fun providerSource(): String =
        File(root, "core/util/src/main/java/com/unuslumen/app/util/shell/ReToolingProvider.kt")
            .readText()

    @Test
    fun theBinaryIsPackagedAsANativeLibrary() {
        // Android only extracts and executes a file in jniLibs when its name has the lib prefix
        // and the .so suffix. Under any other name it is data, and data cannot be executed.
        for (abi in listOf("arm64-v8a", "x86_64")) {
            val so = File(root, "app/src/main/jniLibs/$abi/libguru_adb.so")
            assertTrue(
                "the adb binary must be packaged at jniLibs/$abi/libguru_adb.so. " +
                    "Without it this build cannot run adb at all.",
                so.isFile,
            )
            assertTrue(
                "and it must not be empty: ${so.length()} bytes",
                so.length() > 100_000,
            )
        }
    }

    @Test
    fun thePackagedBinaryIsAnElfExecutableNotALibrary() {
        // The name says .so because that is what makes Android extract it. The CONTENT has to be
        // an executable, or the installer will extract something that cannot run.
        val so = File(root, "app/src/main/jniLibs/arm64-v8a/libguru_adb.so")
        val header = so.inputStream().use { it.readNBytes(20) }
        assertEquals("ELF magic", 0x7f, header[0].toInt())
        assertEquals('E'.code, header[1].toInt())
        assertEquals('L'.code, header[2].toInt())
        assertEquals('F'.code, header[3].toInt())
        // e_type at offset 16, little endian. ET_DYN (3) is a PIE, which is what Android requires
        // since API 21; ET_EXEC (2) would be refused on a modern device.
        val eType = (header[16].toInt() and 0xff) or ((header[17].toInt() and 0xff) shl 8)
        assertEquals("the binary must be a PIE (ET_DYN) to run on modern Android", 3, eType)
    }

    @Test
    fun noCodeExecutesAnythingFromTheDeadDataLocalTmpPath() {
        /*
         * The ticket's own error was `sh: /data/local/tmp/guru_adb: inaccessible or not found`,
         * because that directory is not writable at app UID. So no CODE may name it.
         *
         * COMMENTS ARE EXEMPT, and that is deliberate rather than a concession. The file explains
         * at length why that path cannot work, and deleting the explanation to satisfy a grep
         * would leave the next person free to try it again. The rule is about what executes.
         */
        val deadPath = "/data" + "/local/tmp"
        val code = providerSource().lineSequence()
            .filterNot { it.trimStart().startsWith("*") }
            .filterNot { it.trimStart().startsWith("/*") }
            .filterNot { it.trimStart().startsWith("//") }
            .joinToString("\n")
        assertFalse(
            "no executable line may reference $deadPath: it is not writable at app UID, which " +
                "is the measured cause of issue 209",
            code.contains(deadPath),
        )
    }

    @Test
    fun theCopyFailuresAreGoneRatherThanSwallowed() {
        // The old code wrapped the copy in try/catch and logged a warning, so a failure to place
        // the binary produced a log line and a wrapper pointing at a path that did not exist.
        val source = providerSource()
        assertFalse(
            "the adb path must not be assembled from filesDir any more, because nothing there can " +
                "execute",
            source.contains("File(toolsDir, ADB_FILENAME)"),
        )
    }

    @Test
    fun theBinaryIsResolvedFromTheNativeLibraryDirectory() {
        val source = providerSource()
        assertTrue(
            "adb must resolve from applicationInfo.nativeLibraryDir, the only executable location",
            source.contains("nativeLibraryDir"),
        )
        assertTrue(
            "and by the packaged library name",
            source.contains("libguru_adb.so"),
        )
    }

    @Test
    fun wrapAdbNamesTheResolvedPathRatherThanACopy() {
        // The old wrapAdb copied the binary on every call and swallowed the failure. A copy is
        // the mechanism that produced exit 127 while reporting nothing.
        val source = providerSource()
        val wrap = source.substringAfter("fun wrapAdb(args: String): String =")
            .substringBefore("fun adbUnavailableReason")
        assertTrue(
            "the wrapper must interpolate the resolved path: $wrap",
            wrap.contains("adbPath.absolutePath"),
        )
        assertFalse(
            "it must not copy the binary anywhere at call time: $wrap",
            wrap.contains("copyTo"),
        )
    }

    @Test
    fun theWrapperStillPointsAtTheLibrariesAndTheSocket() {
        val source = providerSource()
        val wrap = source.substringAfter("fun wrapAdb(args: String): String =")
            .substringBefore("fun adbUnavailableReason")
        assertTrue(
            "the shared libraries must stay on LD_LIBRARY_PATH: $wrap",
            wrap.contains("LD_LIBRARY_PATH"),
        )
        assertTrue(
            "and ADB_SERVER_SOCKET is required because this adb refuses -L: $wrap",
            wrap.contains("ADB_SERVER_SOCKET"),
        )
    }

    @Test
    fun aMissingBinaryGetsASentenceRatherThanA127() {
        // A packaging fault and a device fault both produce exit 127, and the ticket spent a round
        // diagnosing the wrong one. The reason names which it is.
        val source = providerSource()
        assertTrue(
            "there must be a function that explains why adb cannot run",
            source.contains("fun adbUnavailableReason()"),
        )
        assertTrue(
            "and it must name the packaging cause in words",
            source.contains("not packaged into jniLibs"),
        )
    }
}
