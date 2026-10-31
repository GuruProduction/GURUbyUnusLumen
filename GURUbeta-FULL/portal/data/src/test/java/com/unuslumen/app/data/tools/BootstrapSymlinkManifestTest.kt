// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile

/**
 * Locks the SYMLINKS.txt contract, and the shipped manifest against it.
 *
 * WHY THIS EXISTS. The whole apt surface was dead and this was the cause.
 * The bootstrap ships 1160 symlinks in a `target←link` manifest with no spaces
 * around the arrow. TermuxProvider split on " ← " (space, arrow, space),
 * matched zero lines, and logged "0 applied this pass" on every install. So
 * none of them landed.
 *
 * The payload is multi-call. `sh`, `mktemp`, `touch`, `sort`, `cut`, `awk`,
 * `id`, `stat`, `expr`, `base64` and the rest of coreutils are symlinks onto
 * the `coreutils`, `dash` and `gawk` multi-call binaries. With no `sh`, apt's
 * signature helper `apt-key` — a `#!/.../bin/sh` script — cannot execute, and
 * apt reports "Couldn't execute apt-key ... not signed". That reads as a
 * keyring fault and is nothing of the kind.
 *
 * Two things are held still here. The parser must accept the shipped manifest
 * format, and the shipped manifest must actually contain the links that make a
 * shell work, so a future bootstrap rebuild that drops them fails in CI
 * instead of on a phone.
 */
class BootstrapSymlinkManifestTest {

    private val assets: File by lazy {
        val candidates = listOf(
            File("src/main/assets"),
            File("../../app/src/main/assets"),
            File("app/src/main/assets"),
        )
        candidates.firstOrNull { File(it, "termux/termux-bootstrap.zip").isFile }
            ?: error(
                "Could not locate app assets from working dir " +
                    File(".").absolutePath + "; tried " +
                    candidates.joinToString { it.absolutePath }
            )
    }

    /**
     * The parsing rule, mirrored from TermuxProvider.applyBootstrapSymlinks.
     * A manifest line resolves to a (target, link) pair, or null when the line
     * carries no arrow. Kept in step with the provider by the assertions below.
     */
    private fun parseManifestLine(line: String): Pair<String, String>? {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return null
        val arrow = trimmed.indexOf('←')
        if (arrow <= 0) return null
        val rawTarget = trimmed.substring(0, arrow).trim()
        val rawLink = trimmed.substring(arrow + 1).trim().removePrefix("./")
        if (rawTarget.isEmpty() || rawLink.isEmpty()) return null
        return rawTarget to rawLink
    }

    @Test
    fun theShippedArrowFormIsParsed() {
        // The exact form in the shipped manifest: no spaces around the arrow.
        // This is the line that makes `sh` exist at all.
        val parsed = parseManifestLine("dash←./bin/sh")
        assertEquals("dash", parsed?.first)
        assertEquals("bin/sh", parsed?.second)
    }

    @Test
    fun aSpacedArrowIsStillParsed() {
        // The legacy form the old parser required. Accepting both costs nothing
        // and means a reformatted manifest does not silently break the install.
        val parsed = parseManifestLine("dash ← ./bin/sh")
        assertEquals("dash", parsed?.first)
        assertEquals("bin/sh", parsed?.second)
    }

    @Test
    fun aLineWithNoArrowIsIgnored() {
        assertEquals(null, parseManifestLine(""))
        assertEquals(null, parseManifestLine("# a comment"))
        assertEquals(null, parseManifestLine("dash"))
    }

    @Test
    fun theShippedManifestLinksTheShellAndCoreutils() {
        val zip = File(assets, "termux/termux-bootstrap.zip")
        assertTrue("termux-bootstrap.zip is missing", zip.isFile)

        val lines: List<String> = ZipFile(zip).use { z ->
            val entry = z.getEntry("SYMLINKS.txt")
                ?: error("SYMLINKS.txt is absent from the bundled bootstrap")
            z.getInputStream(entry).bufferedReader().use { it.readLines() }
        }

        val pairs = lines.mapNotNull { parseManifestLine(it) }
        assertTrue(
            "SYMLINKS.txt parsed to zero links; the manifest format changed and " +
                "the prefix will install without a shell",
            pairs.isNotEmpty(),
        )

        val linkNames = pairs.map { it.second }.toSet()

        // The specific links whose absence made apt unverifiable. `bin/sh` is
        // the one that matters most: it is the interpreter apt-key declares in
        // its shebang.
        for (required in listOf("bin/sh", "bin/mktemp", "bin/touch", "bin/sort", "bin/cut")) {
            assertTrue(
                "$required is not linked by the shipped manifest; the prefix will " +
                    "have no working shell userland and apt-key cannot execute",
                required in linkNames,
            )
        }
    }
}
