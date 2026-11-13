// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.util.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue 292's measured fix, held as a test.
 *
 * The ticket's second comment is the specification: adding the JVM's own lib directories to
 * `LD_LIBRARY_PATH` turned `java -version` from a silent death into `openjdk version "21.0.13"`.
 * These tests pin the ordering and the presence rules so that cannot regress quietly.
 */
class JvmLaunchTest {

    private val prefix = "/data/user/0/pkg/files/termux/usr"
    private val jvmHome = "$prefix/lib/jvm/java-21-openjdk"

    @Test
    fun theJvmDirectoriesAreOnTheLibraryPath() {
        val dirs = JvmLaunch.libraryPath(prefix) { true }
        assertTrue(
            "the JVM's own lib directory must be searched, which is the whole of issue 292. " +
                "Got: $dirs",
            dirs.contains("$jvmHome/lib"),
        )
        assertTrue(
            "and its server VM directory, which is where the JVM actually lives. Got: $dirs",
            dirs.contains("$jvmHome/lib/server"),
        )
    }

    @Test
    fun theServerVmDirectoryComesBeforeThePlainLib() {
        val dirs = JvmLaunch.libraryPath(prefix) { true }
        assertTrue(
            "lib/server must precede lib so the server build wins a shared name. Got: $dirs",
            dirs.indexOf("$jvmHome/lib/server") < dirs.indexOf("$jvmHome/lib"),
        )
    }

    @Test
    fun theJvmDirectoriesComeBeforeThePrefixLib() {
        // Version skew is the reason: the prefix lib may carry an older copy of a library the
        // JVM needs, and that is exactly what made aapt fail in issue 291.
        val value = JvmLaunch.libraryPathValue(
            prefixPath = prefix,
            existing = listOf("$prefix/lib", "/other/lib"),
            jvmDirs = JvmLaunch.libraryPath(prefix) { true },
        )
        val parts = value.split(":")
        assertTrue(
            "the JVM lib must precede the prefix lib. Got: $value",
            parts.indexOf("$jvmHome/lib") < parts.indexOf("$prefix/lib"),
        )
    }

    @Test
    fun aPrefixWithoutAJvmKeepsItsLibraryPathUnchanged() {
        val value = JvmLaunch.libraryPathValue(
            prefixPath = prefix,
            existing = listOf("$prefix/lib", "/other/lib"),
            jvmDirs = JvmLaunch.libraryPath(prefix) { false },
        )
        assertEquals(
            "a prefix with no toolchain must get exactly the value it had, so this change " +
                "cannot break a device that has not installed one",
            "$prefix/lib:/other/lib",
            value,
        )
    }

    @Test
    fun anIntermediateDirectoryThatIsMissingIsOmitted() {
        // A packaged JDK might ship lib but not lib/server on a client build. Listing a path
        // that is not there is a failed stat per lookup and a lie about the device.
        val dirs = JvmLaunch.libraryPath(prefix) { it == jvmHome || it == "$jvmHome/lib" }
        assertFalse("lib/server is absent and must not be listed. Got: $dirs", dirs.contains("$jvmHome/lib/server"))
        assertTrue(dirs.contains("$jvmHome/lib"))
    }

    @Test
    fun duplicatesAreRemovedAndOrderPreserved() {
        val value = JvmLaunch.libraryPathValue(
            prefixPath = prefix,
            existing = listOf("$jvmHome/lib", "$prefix/lib"),
            jvmDirs = listOf("$jvmHome/lib/server", "$jvmHome/lib"),
        )
        val parts = value.split(":")
        assertEquals(
            "a repeated entry is a second failed stat per lookup for no gain. Got: $value",
            parts.size,
            parts.distinct().size,
        )
        assertEquals("$jvmHome/lib/server", parts.first())
    }

    @Test
    fun aTrailingSlashDoesNotChangeTheValue() {
        assertEquals(
            JvmLaunch.libraryPath("$prefix/") { true },
            JvmLaunch.libraryPath(prefix) { true },
        )
    }

    @Test
    fun aBinBinarySymlinkedIntoTheJvmIsRecognised() {
        assertTrue(
            "bin/java resolves into the JVM home, which is why the resolved path is what matters",
            JvmLaunch.needsJvmLibraries("$jvmHome/bin/java"),
        )
        assertFalse(
            "a prefix binary that is not part of the JVM does not need its directories",
            JvmLaunch.needsJvmLibraries("$prefix/bin/figlet"),
        )
    }

    @Test
    fun theLaunchNamesTheLinkerAsTheProgram() {
        val args = JvmLaunch.launch("/system/bin/linker64", "$jvmHome/bin/java", listOf("-version"))
        assertEquals(
            "every bundled binary here is a PIE whose interpreter is the linker, so the linker " +
                "is what gets exec'd and the binary is its first argument",
            listOf("/system/bin/linker64", "$jvmHome/bin/java", "-version"),
            args,
        )
    }

    @Test
    fun theLinkersOwnUsageTextIsRecognised() {
        val real = "Usage: /system/bin/linker64 [--list] PROGRAM [ARGS-FOR-PROGRAM...]"
        assertTrue(
            "this exact text is what round one of the ticket mistook for a broken launcher",
            JvmLaunch.isLinkerUsageOutput(real),
        )
        assertFalse(
            "a genuine java version string must not be mistaken for it",
            JvmLaunch.isLinkerUsageOutput("openjdk version \"21.0.13\" 2025-10-21"),
        )
    }

    @Test
    fun aLibraryFailureNamesThePathWhenThePathIsWrong() {
        val sentence = JvmLaunch.failureSentence(
            output = "CANNOT LINK EXECUTABLE \"bin/java\": library \"libjli.so\" not found",
            resolvedPath = "$jvmHome/bin/java",
            jvmDirsOnPath = false,
        )
        assertTrue(
            "when the JVM directories are absent the sentence must say that is the cause, not " +
                "leave the reader to guess: $sentence",
            sentence.contains("NOT on LD_LIBRARY_PATH"),
        )
    }

    @Test
    fun aLibraryFailureWithACorrectPathBlamesTheArchiveInstead() {
        val sentence = JvmLaunch.failureSentence(
            output = "CANNOT LINK EXECUTABLE \"bin/java\": library \"libjli.so\" not found",
            resolvedPath = "$jvmHome/bin/java",
            jvmDirsOnPath = true,
        )
        assertTrue(
            "with the path correct the remaining cause is a missing file, and saying so is the " +
                "difference between a five-minute fix and another afternoon: $sentence",
            sentence.contains("missing a file"),
        )
    }
}
