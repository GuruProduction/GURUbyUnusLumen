// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.util.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue 31's three faults, held against the real record shapes the ticket pasted.
 *
 * Every fixture below is copied from the ticket's own output rather than invented, because the
 * whole complaint is that a package manager reported success while its records described a tree
 * that does not exist. A test built on a tidied-up fixture would prove the tidy version works.
 */
class DpkgRecordsTest {

    private val root = "/data/user/0/com.unuslumen.app.gurubeta.debug/files/termux"

    /** The exact .list the ticket pasted, leading `/.` and all. */
    private val figletList = """
        /.
        /data
        /data/data
        /data/data/com.termux
        /data/data/com.termux/files
        /data/data/com.termux/files/usr
        /data/data/com.termux/files/usr/bin/chkfont
        /data/data/com.termux/files/usr/bin/figlet
        /data/data/com.termux/files/usr/bin/figlist
    """.trimIndent() + "\n"

    /** The exact status stanza the ticket pasted. */
    private val figletStatus = """
        Package: figlet
        Status: install ok installed
        Installed-Size: 832
        Maintainer: @termux
        Architecture: aarch64
        Version: 2.2.5-3
    """.trimIndent()

    @Test
    fun anUpstreamListIsRewrittenOntoThisInstall() {
        val relocated = DpkgRecords.relocate(figletList, root)
        assertFalse(
            "the rewritten record must not name the upstream prefix: $relocated",
            DpkgRecords.namesUpstream(relocated),
        )
        assertTrue(
            "the binary row must resolve into this install's prefix. Got: $relocated",
            relocated.contains("$root/data/data/com.termux/files/usr/bin/figlet"),
        )
    }

    @Test
    fun theRewriteIsIdempotentSoARecordIsNotRewrittenTwice() {
        val once = DpkgRecords.relocate(figletList, root)
        val twice = DpkgRecords.relocate(once, root)
        assertEquals("a second pass must be a no-op, not a second replacement", once, twice)
    }

    @Test
    fun aRecordThatNamesNeitherFormIsReturnedUnchanged() {
        val clean = "/usr/bin/env\n/usr/share/doc\n"
        assertEquals(clean, DpkgRecords.relocate(clean, root))
    }

    @Test
    fun theBareAndSlashedFormsAreBothRecognised() {
        assertTrue(DpkgRecords.namesUpstream("/data/data/com.termux/files/usr/bin/sh"))
        assertTrue(
            "dpkg writes the bare form without a leading slash in generated fields",
            DpkgRecords.namesUpstream("data/data/com.termux/files/usr/bin/sh"),
        )
        assertFalse(DpkgRecords.namesUpstream("/usr/bin/sh"))
    }

    @Test
    fun theTicketsListIsReadAsThreeBinariesAndNoData() {
        // This is the shape of fault 2: the package exists to render banners in a font, and
        // the list owns three binaries and not one .flf file.
        val files = DpkgRecords.readList(figletList, root)
        assertEquals("the directory rows are still rows", 10, files.rows.size)
        assertEquals("three binaries", 3, files.binaries.size)
        assertEquals("and no data at all, which is the fault", 0, files.data.size)
        assertEquals("every row names the upstream prefix", 10, files.upstreamRows.size)
    }

    @Test
    fun aListWithNoRowsIsRecordedNothingRatherThanRecorded() {
        val files = DpkgRecords.readList("", root)
        assertEquals(DpkgRecords.InstallRecordState.RECORDED_NOTHING, DpkgRecords.installState(files))
    }

    @Test
    fun anAbsentListIsNotRecorded() {
        assertEquals(
            DpkgRecords.InstallRecordState.NOT_RECORDED,
            DpkgRecords.installState(null),
        )
    }

    @Test
    fun aListNamingOnlyUpstreamPathsIsItsOwnState() {
        val files = DpkgRecords.readList(figletList, root)
        assertEquals(
            "not one row resolves on this install, which is a different answer from a clean list",
            DpkgRecords.InstallRecordState.RECORDED_UPSTREAM_ONLY,
            DpkgRecords.installState(files),
        )
    }

    @Test
    fun aRelocatedListReadsAsARecordedInstall() {
        val relocated = DpkgRecords.relocate(figletList, root)
        val files = DpkgRecords.readList(relocated, root)
        assertEquals(DpkgRecords.InstallRecordState.RECORDED, DpkgRecords.installState(files))
    }

    @Test
    fun everyStateHasASentenceNamingAnObservedFact() {
        val states = DpkgRecords.InstallRecordState.values()
        for (state in states) {
            val sentence = DpkgRecords.describeInstallState(state, "figlet")
            assertTrue("$state must say something about figlet", sentence.contains("figlet"))
            assertFalse("$state must not be an empty sentence", sentence.isBlank())
        }
    }

    @Test
    fun theUpstreamStateNamesThePrefixItself() {
        val sentence = DpkgRecords.describeInstallState(
            DpkgRecords.InstallRecordState.RECORDED_UPSTREAM_ONLY,
            "figlet",
        )
        assertTrue(
            "the sentence must name the path that does not exist, so a reader can act: $sentence",
            sentence.contains(DpkgRecords.UPSTREAM_FILES_ROOT),
        )
    }

    @Test
    fun aRowResolvesToThePathDpkgWouldHaveWritten() {
        assertEquals(
            "$root/data/data/com.termux/files/usr/bin/figlet",
            DpkgRecords.resolveRow("/data/data/com.termux/files/usr/bin/figlet", root),
        )
    }

    @Test
    fun theInstallRootRowResolvesToTheRootItself() {
        assertEquals(root, DpkgRecords.resolveRow("/.", root))
    }

    @Test
    fun aRelativeRowIsAnchoredAtTheInstallRoot() {
        assertEquals(
            "$root/usr/share/figlet/std.flf",
            DpkgRecords.resolveRow("./usr/share/figlet/std.flf", root),
        )
    }

    @Test
    fun theStatusStanzaIsReadAsAnInstalledPackage() {
        assertEquals(listOf("figlet"), DpkgRecords.installedPackages(figletStatus))
        assertTrue(DpkgRecords.saysInstalled(figletStatus, "figlet"))
    }

    @Test
    fun anUnpackedButUnconfiguredPackageIsNotCalledInstalled() {
        val half = figletStatus.replace("install ok installed", "install ok unpacked")
        assertFalse(
            "a package left unpacked is exactly what a half-applied instdir produces",
            DpkgRecords.saysInstalled(half, "figlet"),
        )
    }

    @Test
    fun aPackageAbsentFromTheRecordIsNotCalledInstalled() {
        assertFalse(DpkgRecords.saysInstalled(figletStatus, "cowsay"))
    }

    @Test
    fun severalStanzasAreReadInOrder() {
        val two = figletStatus + "\n" + """
            Package: cowsay
            Status: install ok installed
            Version: 3.7.0
        """.trimIndent()
        assertEquals(listOf("figlet", "cowsay"), DpkgRecords.installedPackages(two))
        assertTrue(DpkgRecords.saysInstalled(two, "cowsay"))
    }

    @Test
    fun theBinaryAndDataViewsClassifyByPackageDirectory() {
        val withData = """
            /data/data/com.termux/files/usr/bin/figlet
            /data/data/com.termux/files/usr/share/figlet/std.flf
            /data/data/com.termux/files/usr/etc/figlet.conf
            /data/data/com.termux/files/usr/lib/libfoo.so
        """.trimIndent()
        val files = DpkgRecords.readList(withData, root)
        assertEquals(1, files.binaries.size)
        assertEquals(3, files.data.size)
    }
}
