// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.util.shell

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Holds the toolchain manifest reader to the archive it actually has to read.
 *
 * WHY THIS EXISTS. The manifest is the only thing that tells the installer which
 * paths are symlinks. java.util.zip cannot report a unix mode, so a symlink
 * packed with `zip -y` arrives on the device as a small regular file whose
 * CONTENT is the target path — a 32-byte "java" the linker refuses. The
 * installer recreates the links from this manifest, so if the reader returns
 * nothing, or the wrong thing, the JVM lands on disk unreachable while every
 * file looks present. That is the same class of defect as issue 200 itself.
 *
 * These tests build their own zips, so they prove the READER rather than
 * re-proving the packer, and they run in milliseconds with no device.
 */
@RunWith(RobolectricTestRunner::class)
class ToolchainManifestReaderTest {

    private val context: Application =
        ApplicationProvider.getApplicationContext<Application>()

    private fun zipWith(entries: Map<String, String>): File {
        val file = File.createTempFile("toolchain-test", ".zip")
        file.deleteOnExit()
        ZipOutputStream(file.outputStream()).use { out ->
            for ((name, content) in entries) {
                out.putNextEntry(ZipEntry(name))
                out.write(content.toByteArray())
                out.closeEntry()
            }
        }
        return file
    }

    private val validManifest = """
        {
          "schema": 1,
          "jvmHome": "lib/jvm/java-21-openjdk",
          "symlinks": [
            { "path": "bin/java", "target": "lib/jvm/java-21-openjdk/bin/java" },
            { "path": "bin/jar",  "target": "lib/jvm/java-21-openjdk/bin/jar" }
          ],
          "tools": [
            { "name": "java", "probe": ["-version"], "kind": "jvm" },
            { "name": "aapt", "probe": ["version"],  "kind": "native" }
          ]
        }
    """.trimIndent()

    @Test
    fun readsTheSymlinksToolsAndJvmHome() {
        val zip = zipWith(mapOf("re-toolchain-manifest.json" to validManifest))
        val manifest = TermuxProvider(context).readToolchainManifest(zip)

        assertNotNull("A well-formed manifest must parse", manifest)
        assertEquals(1, manifest!!.schema)
        assertEquals("lib/jvm/java-21-openjdk", manifest.jvmHome)
        assertEquals(2, manifest.symlinks.size)
        assertEquals("bin/java", manifest.symlinks[0].path)
        assertEquals("lib/jvm/java-21-openjdk/bin/java", manifest.symlinks[0].target)
        assertEquals(2, manifest.tools.size)
        assertEquals(listOf("-version"), manifest.tools[0].probe)
        assertEquals(listOf("version"), manifest.tools[1].probe)
    }

    /**
     * Reordered fields must not matter.
     *
     * This is the failure a regex parser would have had: it would depend on the
     * key order inside each object, and JSON does not promise one. A typed
     * decoder does not care, and this test pins that so a future hand-rolled
     * "optimisation" cannot reintroduce the dependency.
     */
    @Test
    fun fieldOrderDoesNotMatter() {
        val reordered = """
            {
              "tools": [ { "kind": "jvm", "probe": ["-version"], "name": "java" } ],
              "symlinks": [ { "target": "lib/jvm/java-21-openjdk/bin/java", "path": "bin/java" } ],
              "jvmHome": "lib/jvm/java-21-openjdk",
              "schema": 1
            }
        """.trimIndent()
        val zip = zipWith(mapOf("re-toolchain-manifest.json" to reordered))
        val manifest = TermuxProvider(context).readToolchainManifest(zip)

        assertNotNull("Field order must not affect parsing", manifest)
        assertEquals("bin/java", manifest!!.symlinks.single().path)
        assertEquals("java", manifest.tools.single().name)
    }

    /**
     * A manifest with no symlinks is treated as absent, deliberately.
     *
     * Without the links, `bin/java` is a text file. Installing that and reporting
     * the toolchain present is exactly the lie issue 200 removed, so the reader
     * refuses and the caller withholds the install marker.
     */
    @Test
    fun aManifestWithNoSymlinksIsRejected() {
        val noLinks = """
            { "schema": 1, "jvmHome": "lib/jvm/java-21-openjdk",
              "symlinks": [], "tools": [] }
        """.trimIndent()
        val zip = zipWith(mapOf("re-toolchain-manifest.json" to noLinks))

        assertNull(
            "A manifest declaring no symlinks must be rejected: java would be unlaunchable",
            TermuxProvider(context).readToolchainManifest(zip),
        )
    }

    @Test
    fun anArchiveWithNoManifestIsRejected() {
        val zip = zipWith(mapOf("bin/java" to "not the manifest"))
        assertNull(TermuxProvider(context).readToolchainManifest(zip))
    }

    @Test
    fun malformedJsonIsRejectedRatherThanThrowing() {
        val zip = zipWith(mapOf("re-toolchain-manifest.json" to "{ this is not json"))
        assertNull(TermuxProvider(context).readToolchainManifest(zip))
    }

    /**
     * Unknown keys are ignored rather than fatal.
     *
     * The manifest carries a free-text description and a later version may carry
     * more. A reader that failed on an unknown key would strand every handset on
     * the older build the moment the packer added a field.
     */
    @Test
    fun unknownKeysAreIgnored() {
        val withExtra = """
            {
              "schema": 1,
              "description": "free text the reader does not need",
              "futureField": { "nested": true },
              "jvmHome": "lib/jvm/java-21-openjdk",
              "symlinks": [ { "path": "bin/java", "target": "lib/jvm/java-21-openjdk/bin/java" } ],
              "tools": []
            }
        """.trimIndent()
        val zip = zipWith(mapOf("re-toolchain-manifest.json" to withExtra))
        val manifest = TermuxProvider(context).readToolchainManifest(zip)

        assertNotNull("An unknown key must not prevent parsing", manifest)
        assertEquals(1, manifest!!.symlinks.size)
    }
}
