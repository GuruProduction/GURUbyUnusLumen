// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.tools

import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.zip.ZipFile

/**
 * Locks the bundled runtime assets and the availability contract.
 *
 * WHY THIS EXISTS. GURU reported on a fresh v3.9 install that "the HTTPS remote
 * helper binary isn't shipped, and Termux isn't installed". Both statements were
 * wrong about the artifacts: the APK ships assets/git-termux/git-termux.zip with
 * git-remote-http and git-remote-https inside it, and assets/termux/
 * termux-bootstrap.zip with bash and the termux-exec preload. The app had even
 * installed and verified the runtime in its own logs.
 *
 * The real defect was that TermuxProvider.isAvailable() read an instance field
 * that only became true on the object that ran initialize(). Application boot
 * used one instance; TermuxToolExecutor used another; the second answered false
 * and the model relayed that as fact.
 *
 * These tests hold two things still: the artifacts are actually packaged, and the
 * availability question cannot be answered from a per-instance latch.
 */
class BundledRuntimeAvailabilityTest {

    // The unit-test working directory is the MODULE, so a plain relative path
    // resolves under portal/data. Walk up to the app module's assets, and fail
    // loudly with the resolved path if it is not found rather than silently
    // asserting against nothing.
    private val assets: java.io.File by lazy {
        val candidates = listOf(
            java.io.File("src/main/assets"),
            java.io.File("../../app/src/main/assets"),
            java.io.File("app/src/main/assets"),
        )
        candidates.firstOrNull { java.io.File(it, "termux/termux-bootstrap.zip").isFile }
            ?: error(
                "Could not locate app assets from working dir " +
                    java.io.File(".").absolutePath + "; tried " +
                    candidates.joinToString { it.absolutePath }
            )
    }

    @Test
    fun gitRuntimeIsBundledWithItsHttpsHelper() {
        val zip = java.io.File(assets, "git-termux/git-termux.zip")
        assertTrue("git-termux.zip is missing from the APK assets", zip.isFile)

        ZipFile(zip).use { z ->
            val names = z.entries().toList().map { it.name }
            assertTrue(
                "bin/git is not in the bundled git runtime",
                names.any { it == "bin/git" },
            )
            assertTrue(
                "git-remote-http is not in the bundled git runtime; HTTPS cannot work without it",
                names.any { it.endsWith("git-remote-http") },
            )
            assertTrue(
                "git-remote-https is not in the bundled git runtime; git resolves this name for https URLs",
                names.any { it.endsWith("git-remote-https") },
            )
        }
    }

    @Test
    fun termuxBootstrapIsBundledWithBashAndTheExecPreload() {
        val zip = java.io.File(assets, "termux/termux-bootstrap.zip")
        assertTrue("termux-bootstrap.zip is missing from the APK assets", zip.isFile)

        ZipFile(zip).use { z ->
            val names = z.entries().toList().map { it.name }
            assertTrue(
                "bash is not in the bundled bootstrap; the Termux prefix cannot run anything",
                names.any { it == "bin/bash" },
            )
            assertTrue(
                "the termux-exec preload is not in the bundled bootstrap; app-data execs cannot be " +
                    "routed through linker64, which Android's W^X rule requires on targetSdk 29+",
                names.any { it.endsWith("libtermux-exec-ld-preload.so") },
            )
            assertTrue(
                "libcurl is not in the bundled bootstrap; git has no HTTP transport",
                names.any { it.contains("libcurl.so") },
            )
        }
    }

    @Test
    fun theApkShipsTheseAsAssetsNotOnlyAsSource() {
        // A guard against the artifacts existing in the repo but not in what users
        // download: the APK packaging must include assets/ untouched.
        assertTrue("src/main/assets is missing", assets.isDirectory)
        val runtimeZip = java.io.File(assets, "git-termux/git-termux.zip")
        assertTrue(
            "git-termux.zip is present but empty, which would install an unverifiable runtime",
            runtimeZip.length() > 1_000_000,
        )
    }
}
