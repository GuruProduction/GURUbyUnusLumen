// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.tools

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile

/**
 * Holds the 16 KB page-size contract for every bundled native library (issue 13).
 *
 * WHY THIS EXISTS. Installing a debug build on Android 37 raised the system's
 * App Compatibility warning before the app drew a frame:
 *
 *   This app isn't 16 KB compatible. LOAD segment and RELRO segment alignment
 *   checks failed. This app will be run using page size compatible mode.
 *
 * That warning is invisible from a compile, which is why it went unnoticed until
 * somebody ran the app. On newer hardware 16 KB pages are enforced rather than
 * warned about, so an app in compatibility mode is a support burden today and a
 * non-installing APK later.
 *
 * WHAT CAN AND CANNOT BE FIXED HERE. The alignment lives in each ELF's program
 * headers. Two distinct groups make up the bundle:
 *
 *  - libcerebrum_android.so is BUILT FROM THIS REPO by the Rust workspace, so its
 *    alignment is ours to set. cerebrum/.cargo/config.toml now passes
 *    -Wl,-z,max-page-size=16384 for both Android targets.
 *  - Every libguru_*.so and the python/sqlite/tesseract/bundle libraries are
 *    PREBUILT upstream artefacts shipped in jniLibs. They cannot be realigned
 *    here, because we do not compile them; each needs a rebuilt 16 KB upstream.
 *    Deleting them to silence this test would remove runtime dependencies, and
 *    rewriting ELF headers by hand would corrupt the binaries.
 *
 * So this test is the record of what remains, and it fails the moment a new
 * misaligned library is added. The per-library inventory of what still needs an
 * upstream rebuild is [KNOWN_MISALIGNED].
 *
 * The check itself is plain file IO, no Android APIs, so it runs on the JVM in
 * milliseconds and needs no device.
 */
class PageSizeAlignmentTest {

    /**
     * Libraries measured at p_align 0x1000 that have to be rebuilt upstream.
     *
     * Recorded so the size of the remaining job is stated in one place rather than
     * rediscovered by a device run. Anything here that becomes aligned is fine and
     * will not fail the test; an entry that is missing from the tree entirely is
     * also fine, because dropping a dependency is a legitimate way to retire it.
     */
    private val KNOWN_MISALIGNED: Set<String> = setOf(
        "libguru_curl.so",
        "libguru_ffmpeg.so",
        "libguru_git.so",
        "libguru_helloworld.so",
        "libguru_jq.so",
        "libguru_make.so",
        "libguru_openssl.so",
        "libguru_rsync.so",
        "libguru_scp.so",
        "libguru_sqlite3.so",
        "libguru_ssh.so",
        "libguru_tesseract.so",
        "libguru_tmux.so",
        "libguru_toybox.so",
        "libguru_unzip.so",
        "libguru_zip.so",
    )

    // The unit-test working directory is the MODULE, so a relative path resolves
    // under portal/data. Walk up to the app module, and fail with the resolved path
    // if it is not found rather than asserting against nothing.
    private val jniLibs: File by lazy {
        val candidates = listOf(
            File("src/main/jniLibs"),
            File("../../app/src/main/jniLibs"),
            File("app/src/main/jniLibs"),
        )
        candidates.firstOrNull { it.isDirectory }
            ?: error(
                "Could not locate app jniLibs from working dir " +
                    File(".").absolutePath + "; tried " +
                    candidates.joinToString { it.absolutePath },
            )
    }

    /** Every .so in the tree, keyed by its ABI directory. */
    private fun allLibraries(): List<Pair<String, File>> =
        jniLibs.listFiles().orEmpty()
            .filter { it.isDirectory }
            .flatMap { abi ->
                abi.listFiles().orEmpty()
                    .filter { it.isFile && it.name.endsWith(".so") }
                    .map { abi.name to it }
            }
            .sortedWith(compareBy({ it.first }, { it.second.name }))

    private data class ElfAlignment(
        val is64Bit: Boolean,
        /** p_align of every PT_LOAD segment, in file order. */
        val loadAlignments: List<Long>,
    )

    /**
     * Read the ELF program headers and return the p_align of every PT_LOAD.
     *
     * Throws rather than guessing: a file that is not an ELF, or that is too short
     * to hold its own headers, is a broken artefact and should be loud.
     */
    private fun readElfAlignment(file: File): ElfAlignment {
        RandomAccessFile(file, "r").use { raf ->
            val ident = ByteArray(16)
            raf.readFully(ident)
            require(ident[0] == 0x7f.toByte() && ident[1] == 'E'.code.toByte() &&
                ident[2] == 'L'.code.toByte() && ident[3] == 'F'.code.toByte()
            ) { "${file.name} is not an ELF file" }

            val is64Bit = ident[4] == 2.toByte()
            val littleEndian = ident[5] == 1.toByte()

            fun u16(offset: Long): Int {
                val b = ByteArray(2).also { raf.seek(offset); raf.readFully(it) }
                return if (littleEndian) {
                    (b[0].toInt() and 0xFF) or ((b[1].toInt() and 0xFF) shl 8)
                } else {
                    ((b[0].toInt() and 0xFF) shl 8) or (b[1].toInt() and 0xFF)
                }
            }

            fun u32(offset: Long): Long {
                val b = ByteArray(4).also { raf.seek(offset); raf.readFully(it) }
                return if (littleEndian) {
                    (b[0].toLong() and 0xFF) or
                        ((b[1].toLong() and 0xFF) shl 8) or
                        ((b[2].toLong() and 0xFF) shl 16) or
                        ((b[3].toLong() and 0xFF) shl 24)
                } else {
                    ((b[0].toLong() and 0xFF) shl 24) or
                        ((b[1].toLong() and 0xFF) shl 16) or
                        ((b[2].toLong() and 0xFF) shl 8) or
                        (b[3].toLong() and 0xFF)
                }
            }

            fun u64(offset: Long): Long {
                val b = ByteArray(8).also { raf.seek(offset); raf.readFully(it) }
                var value = 0L
                if (littleEndian) {
                    for (i in 7 downTo 0) value = (value shl 8) or (b[i].toLong() and 0xFF)
                } else {
                    for (i in 0..7) value = (value shl 8) or (b[i].toLong() and 0xFF)
                }
                return value
            }

            // ELF64: e_phoff at 0x20 (8 bytes), e_phentsize at 0x36, e_phnum at 0x38.
            // ELF32: e_phoff at 0x1C (4 bytes), e_phentsize at 0x2A, e_phnum at 0x2C.
            val phoff = if (is64Bit) u64(0x20) else u32(0x1C)
            val phentsize = if (is64Bit) u16(0x36) else u16(0x2A)
            val phnum = if (is64Bit) u16(0x38) else u16(0x2C)
            require(phoff > 0 && phnum > 0) { "${file.name} declares no program headers" }

            val aligns = mutableListOf<Long>()
            for (i in 0 until phnum) {
                val base = phoff + i.toLong() * phentsize
                val pType = u32(base)                       // p_type is 4 bytes in both
                if (pType != 1L) continue                  // PT_LOAD only
                // p_align sits at 0x30 in ELF64 and 0x1C in ELF32 program headers.
                aligns.add(if (is64Bit) u64(base + 0x30) else u32(base + 0x1C))
            }
            return ElfAlignment(is64Bit, aligns)
        }
    }

    @Test
    fun everyBundledLibraryHasALoadableSixteenKilobyteAlignment() {
        val libraries = allLibraries()
        assertTrue(
            "No .so files were found under ${jniLibs.absolutePath}. Either the ABI " +
                "directories moved or this test is looking in the wrong place, and " +
                "passing on an empty set would hide the whole issue.",
            libraries.isNotEmpty(),
        )

        val misaligned = mutableListOf<String>()
        for ((abi, file) in libraries) {
            val header = readElfAlignment(file)
            // 16 KB is 0x4000. A 64 KB alignment (0x10000) also satisfies the
            // requirement, and the NDK uses it on some targets, so the rule is
            // "at least 16 KB", never an exact match.
            val bad = header.loadAlignments.filter { it < 0x4000L }
            if (bad.isNotEmpty()) {
                misaligned.add(
                    "$abi/${file.name} p_align=${bad.joinToString { "0x" + it.toString(16) }}",
                )
            }
        }

        val unexpected = misaligned.filterNot { entry ->
            KNOWN_MISALIGNED.any { entry.contains(it) }
        }

        assertTrue(
            "These bundled libraries are NOT 16 KB page aligned. On Android 15+ the " +
                "loader refuses a PT_LOAD segment below 0x4000, and the app is placed in " +
                "compatibility mode or fails to install. libcerebrum_android.so is built " +
                "from this repo and must be 16 KB aligned; anything else here is a prebuilt " +
                "upstream artefact needing a rebuild, and should be added to KNOWN_MISALIGNED " +
                "with a note about which upstream supplies it:\n" + misaligned.joinToString("\n"),
            unexpected.isEmpty(),
        )
    }

    @Test
    fun ourOwnCerebrumLibraryIsSixteenKilobyteAligned() {
        // The one library whose alignment this repo decides, because the Rust
        // workspace builds it. If this fails, cerebrum/.cargo/config.toml has lost
        // its -Wl,-z,max-page-size=16384 flag for the target being built.
        val candidates = allLibraries().filter { it.second.name == "libcerebrum_android.so" }
        assertTrue(
            "libcerebrum_android.so is not present under ${jniLibs.absolutePath}; it is the " +
                "JNI host for the brain and cannot be optional.",
            candidates.isNotEmpty(),
        )
        for ((abi, file) in candidates) {
            val header = readElfAlignment(file)
            assertTrue(
                "$abi/libcerebrum_android.so is not 16 KB aligned " +
                    "(p_align=${header.loadAlignments.joinToString { "0x" + it.toString(16) }}). " +
                    "This library is built by cerebrum/cerebrum-android, so the fix belongs in " +
                    "cerebrum/.cargo/config.toml: add -C link-arg=-Wl,-z,max-page-size=16384 " +
                    "to the rustflags of the target being built.",
                header.loadAlignments.isNotEmpty() && header.loadAlignments.all { it >= 0x4000L },
            )
        }
    }
}
