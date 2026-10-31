// TEMPORARY proof that the alignment gate fires. Not committed.
package com.unuslumen.app.data.tools

import org.junit.Test

class AlignmentGateProofTest {
    @Test
    fun theGateFailsWhenAKnownLibraryIsRemovedFromTheAllowList() {
        // Reflection-free approach: a known-misaligned library must be reported by
        // the same ELF reader the real test uses. If the reader silently returns
        // nothing, this fails loudly rather than passing on an empty set.
        val t = PageSizeAlignmentTest()
        val libsField = PageSizeAlignmentTest::class.java
            .getDeclaredField("jniLibs")
            .apply { isAccessible = true }
        val dir = libsField.get(t) as java.io.File
        check(dir.isDirectory) { "jniLibs not found at ${dir.absolutePath}" }

        val libs = dir.listFiles().orEmpty().filter { it.isDirectory }
            .flatMap { abi -> abi.listFiles().orEmpty().filter { it.name.endsWith(".so") } }
        check(libs.size == 26) { "expected 26 bundled libraries, found ${libs.size}" }

        val read = PageSizeAlignmentTest::class.java
            .getDeclaredMethod("readElfAlignment", java.io.File::class.java)
            .apply { isAccessible = true }

        val target = java.io.File(dir, "arm64-v8a/libguru_curl.so")
        check(target.isFile) { "libguru_curl.so missing" }
        val header = read.invoke(t, target)
        val alignsField = header!!.javaClass.getDeclaredField("loadAlignments").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val aligns = alignsField.get(header) as List<Long>
        println("PROOF libguru_curl.so loadAlignments = ${aligns.map { "0x" + it.toString(16) }}")
        check(aligns.isNotEmpty() && aligns.all { it < 0x4000 }) {
            "the reader found $aligns; it must report the real 4 KB alignments"
        }

        val ours = java.io.File(dir, "arm64-v8a/libcerebrum_android.so")
        val oursHeader = read.invoke(t, ours)
        @Suppress("UNCHECKED_CAST")
        val oursAligns = alignsField.get(oursHeader) as List<Long>
        println("PROOF libcerebrum_android.so loadAlignments = ${oursAligns.map { "0x" + it.toString(16) }}")
        check(oursAligns.isNotEmpty() && oursAligns.all { it >= 0x4000 }) {
            "our own library must be 16 KB aligned, found $oursAligns"
        }
    }
}
