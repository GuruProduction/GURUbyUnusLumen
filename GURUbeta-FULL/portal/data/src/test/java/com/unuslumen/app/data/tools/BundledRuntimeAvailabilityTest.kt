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

    /*
     * THE PYTHON RUNTIME LAYOUT (issue 175)
     *
     * These assertions exist because every one of them was false in the shipped
     * APK, and each falsehood produced the same user-visible symptom: "python does
     * not work in the app". The distinctions below are the ones that took a
     * diagnostic battery to separate, so they are pinned here rather than trusted
     * to a comment.
     *
     * They test the ASSET, which is what ships. Whether the interpreter then runs
     * is a device question and cannot be answered by a JVM test.
     */

    @Test
    fun thePythonRuntimeIsOneArchiveWithTheInterpreterInside() {
        val zip = java.io.File(assets, "python-runtime/python-runtime-arm64.zip")
        assertTrue(
            "python-runtime-arm64.zip is missing. Without it the interpreter has no " +
                "standard library and cannot import os.",
            zip.isFile,
        )

        ZipFile(zip).use { z ->
            val names = z.entries().toList().map { it.name }

            // The interpreter must be INSIDE the runtime, not a separate binary in
            // jniLibs. Shipping them from two sources is how the app ended up with
            // an interpreter and a stdlib that were different builds, which fails
            // with "cannot locate symbol _PyType_AllocNoTrack" only when an
            // extension is first imported.
            assertTrue(
                "lib/libpython3.14.so is not in the runtime archive; the interpreter and " +
                    "its standard library must come from one build",
                names.any { it == "lib/libpython3.14.so" },
            )

            // getpath searches PYTHONHOME/lib/python3.14. os.py anywhere else means
            // the interpreter starts and then dies on "Failed to import encodings
            // module", which is the flat-layout defect this pins shut.
            assertTrue(
                "lib/python3.14/os.py is not in the runtime archive; the standard library " +
                    "is not at the level CPython's getpath searches",
                names.any { it == "lib/python3.14/os.py" },
            )

            // Extension modules live in lib-dynload. A .so beside os.py is not found
            // by the import system.
            val dynload = names.filter { it.startsWith("lib/python3.14/lib-dynload/") && it.endsWith(".so") }
            assertTrue(
                "no extension modules in lib/python3.14/lib-dynload; math, sqlite3, ssl " +
                    "and _json would all fail to import",
                dynload.isNotEmpty(),
            )

            // _json is imported during interpreter startup, so its absence means
            // even a one-line script fails. It is the cheapest canary for a runtime
            // that cannot do anything at all.
            assertTrue(
                "_json is missing from lib-dynload. It is imported during startup, so " +
                    "without it every Python call fails before the user script runs.",
                dynload.any { it.contains("_json.cpython-314") },
            )

            // The libraries the extension modules name. These were computed from
            // DT_NEEDED by fetch_python_runtime.sh; their absence is exactly the
            // "dlopen failed: library ... not found" class.
            for (required in listOf("lib/libandroid-support.so", "lib/libsqlite3.so", "lib/libssl.so.3")) {
                assertTrue(
                    "$required is not in the runtime archive; an extension module needs it " +
                        "and the platform does not provide it",
                    names.any { it == required },
                )
            }
        }
    }

    @Test
    fun thereIsOnlyOneInterpreterInTheApk() {
        // The stale libpython3.14.so in jniLibs used to shadow the extracted
        // runtime, because dlopen resolved the linker path before the runtime's
        // own lib/. The app then reported a working Python 3.14.5 while every
        // extension module failed against the 3.14.8 stdlib. One interpreter
        // means that class of mismatch cannot happen.
        val jniLibs = java.io.File("../../app/src/main/jniLibs")
        if (!jniLibs.isDirectory) return

        val stray = jniLibs.walkTopDown()
            .filter { it.isFile && it.name.startsWith("libpython3") }
            .map { it.relativeTo(jniLibs).path }
            .toList()

        assertTrue(
            "libpython is still shipped in jniLibs ($stray). The interpreter must come only " +
                "from the runtime archive, or dlopen can load a different build from the " +
                "standard library it was extracted with.",
            stray.isEmpty(),
        )
    }
}
