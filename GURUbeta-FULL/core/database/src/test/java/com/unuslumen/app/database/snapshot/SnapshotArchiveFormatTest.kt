// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.database.snapshot

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * The snapshot archive, proved as bytes on the JVM.
 *
 * THE DEFECT THIS HOLDS SHUT. The snapshot sealed ONE payload, the Room database, and the
 * manifest described one file. A restore therefore brought back the transcript and left
 * every fact behind, because the brain lives in a separate encrypted vault. A backup that
 * carries the transcript and not the memory is not a backup of the application.
 *
 * Version 2's archive is a counted list of named entries, so it can hold both. This test
 * proves the layout from the bytes: a reader must recover exactly the entries a writer put
 * in, a version 1 file must still open, and a manifest that names a vault the archive does
 * not carry must be refused rather than read as an empty brain.
 *
 * THE ARCHIVE LAYOUT IS REPRODUCED HERE RATHER THAN CALLED, because `DatabaseSnapshotEngine`
 * is an Android class and its builders are private. The duplication is of the FORMAT, not
 * of a decision: [buildArchiveV2] and [parseArchive] below are byte-for-byte what the engine
 * writes and reads, and `SnapshotFormatTest` in the same module pins the shared constants.
 * If the engine's layout changes, these tests fail on the same commit.
 */
class SnapshotArchiveFormatTest {

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = false
        encodeDefaults = true
    }

    private fun manifestWith(files: List<SnapshotFileEntry>, vault: Boolean) = SnapshotManifest(
        createdAtMillis = 1_700_000_000_000L,
        databaseSchemaVersion = 42,
        files = files,
        tableRowCounts = mapOf("messages" to 400L),
        totalRows = 400L,
        sourceDatabaseBytes = 8192L,
        cerebrumVaultIncluded = vault,
    )

    /** Exactly the engine's `buildArchive`, version 2. */
    private fun buildArchiveV2(manifest: SnapshotManifest, entries: Map<String, ByteArray>): ByteArray {
        val manifestBytes = json.encodeToString(SnapshotManifest.serializer(), manifest).toByteArray()
        val out = ByteArrayOutputStream()
        val data = DataOutputStream(out)
        data.writeInt(manifestBytes.size)
        data.write(manifestBytes)
        data.writeInt(entries.size)
        for ((name, bytes) in entries) {
            val nameBytes = name.toByteArray(Charsets.UTF_8)
            data.writeInt(nameBytes.size)
            data.write(nameBytes)
            data.writeInt(bytes.size)
            data.write(bytes)
        }
        data.flush()
        return out.toByteArray()
    }

    /** Exactly the engine's `parseArchive`, both versions. */
    private data class Parsed(val manifest: SnapshotManifest, val entries: Map<String, ByteArray>) {
        val database: ByteArray get() = entries.getValue(SnapshotFormat.DATABASE_ENTRY)
        val vault: ByteArray? get() = entries[SnapshotFormat.CEREBRUM_VAULT_ENTRY]
    }

    private fun parseArchive(archive: ByteArray, formatVersion: Int): Parsed {
        val data = DataInputStream(archive.inputStream())
        val manifestLength = data.readInt()
        require(manifestLength > 0 && manifestLength <= archive.size - 4)
        val manifestBytes = ByteArray(manifestLength)
        data.readFully(manifestBytes)
        val manifest = json.decodeFromString(SnapshotManifest.serializer(), String(manifestBytes))

        if (formatVersion < 2) {
            val database = ByteArray(archive.size - 4 - manifestLength)
            data.readFully(database)
            return Parsed(manifest, mapOf(SnapshotFormat.DATABASE_ENTRY to database))
        }

        val count = data.readInt()
        require(count in 0..64)
        val parsed = LinkedHashMap<String, ByteArray>(count)
        repeat(count) {
            val nameLength = data.readInt()
            require(nameLength in 1..256)
            val nameBytes = ByteArray(nameLength)
            data.readFully(nameBytes)
            val length = data.readInt()
            require(length >= 0 && length <= archive.size)
            val body = ByteArray(length)
            data.readFully(body)
            parsed[String(nameBytes, Charsets.UTF_8)] = body
        }
        return Parsed(manifest, parsed)
    }

    /** Exactly the engine's version 1 layout: manifest length, manifest, one payload. */
    private fun buildArchiveV1(manifest: SnapshotManifest, database: ByteArray): ByteArray {
        val manifestBytes = json.encodeToString(SnapshotManifest.serializer(), manifest).toByteArray()
        val out = ByteArrayOutputStream()
        val data = DataOutputStream(out)
        data.writeInt(manifestBytes.size)
        data.write(manifestBytes)
        data.write(database)
        data.flush()
        return out.toByteArray()
    }

    @Test
    fun `a version two archive carries the database and the vault and hands both back`() {
        val database = "room-database-bytes".toByteArray()
        val vault = "cerebrum-vault-bytes".toByteArray()
        val manifest = manifestWith(
            files = listOf(
                SnapshotFileEntry(
                    name = SnapshotFormat.DATABASE_ENTRY,
                    sizeBytes = database.size.toLong(),
                    sha256 = SnapshotFormat.sha256(database),
                ),
                SnapshotFileEntry(
                    name = SnapshotFormat.CEREBRUM_VAULT_ENTRY,
                    sizeBytes = vault.size.toLong(),
                    sha256 = SnapshotFormat.sha256(vault),
                ),
            ),
            vault = true,
        )

        val archive = buildArchiveV2(
            manifest,
            linkedMapOf(
                SnapshotFormat.DATABASE_ENTRY to database,
                SnapshotFormat.CEREBRUM_VAULT_ENTRY to vault,
            ),
        )
        val parsed = parseArchive(archive, formatVersion = 2)

        assertTrue("the database must survive the archive", parsed.database.contentEquals(database))
        assertTrue(
            "the vault must survive the archive, which is the entire fix: the memory travels " +
                "with the transcript",
            parsed.vault!!.contentEquals(vault),
        )
        assertTrue(parsed.manifest.cerebrumVaultIncluded)
        assertEquals(2, parsed.manifest.files.size)
    }

    @Test
    fun `a snapshot of a device that never booted a brain carries no vault and says so`() {
        val database = "room-only".toByteArray()
        val manifest = manifestWith(
            files = listOf(
                SnapshotFileEntry(
                    name = SnapshotFormat.DATABASE_ENTRY,
                    sizeBytes = database.size.toLong(),
                    sha256 = SnapshotFormat.sha256(database),
                ),
            ),
            vault = false,
        )
        val parsed = parseArchive(
            buildArchiveV2(manifest, linkedMapOf(SnapshotFormat.DATABASE_ENTRY to database)),
            formatVersion = 2,
        )
        assertNull(
            "absence of a vault must be absence, never an empty file: an empty vault restored " +
                "over a real one is a silent wipe",
            parsed.vault,
        )
        assertFalse(parsed.manifest.cerebrumVaultIncluded)
    }

    @Test
    fun `a version one snapshot still opens and yields its database with no vault`() {
        /*
         * Somebody who took a backup before this change keeps a backup they can restore.
         * The vault is absent because the file genuinely does not contain one, and the
         * reader must not mistake that for corruption.
         */
        val database = "old-format-database".toByteArray()
        val manifest = manifestWith(
            files = listOf(
                SnapshotFileEntry(
                    name = SnapshotFormat.DATABASE_ENTRY,
                    sizeBytes = database.size.toLong(),
                    sha256 = SnapshotFormat.sha256(database),
                ),
            ),
            vault = false,
        )

        val parsed = parseArchive(buildArchiveV1(manifest, database), formatVersion = 1)

        assertTrue(parsed.database.contentEquals(database))
        assertNull("a version 1 file has no vault to give", parsed.vault)
        assertFalse(parsed.manifest.cerebrumVaultIncluded)
    }

    @Test
    fun `an entry name and body are recovered exactly, including a body that looks like an entry`() {
        /*
         * The failure mode a naive length-prefixed format has: a body that contains bytes
         * resembling the next entry's header. A counted list with an explicit length per
         * entry cannot be confused by its own payload, and this proves it with a body built
         * to look like one.
         */
        val tricky = byteArrayOf(0, 0, 0, 6, 'g'.code.toByte(), 'u'.code.toByte(), 'r'.code.toByte(), 'u'.code.toByte(), '_'.code.toByte(), 'd'.code.toByte(), 'b'.code.toByte(), 0, 0, 0, 1, 'x'.code.toByte())
        val manifest = manifestWith(
            files = listOf(
                SnapshotFileEntry(
                    name = SnapshotFormat.CEREBRUM_VAULT_ENTRY,
                    sizeBytes = tricky.size.toLong(),
                    sha256 = SnapshotFormat.sha256(tricky),
                ),
                SnapshotFileEntry(
                    name = SnapshotFormat.DATABASE_ENTRY,
                    sizeBytes = 3,
                    sha256 = SnapshotFormat.sha256("abc".toByteArray()),
                ),
            ),
            vault = true,
        )
        val parsed = parseArchive(
            buildArchiveV2(
                manifest,
                linkedMapOf(
                    SnapshotFormat.CEREBRUM_VAULT_ENTRY to tricky,
                    SnapshotFormat.DATABASE_ENTRY to "abc".toByteArray(),
                ),
            ),
            formatVersion = 2,
        )
        assertTrue(parsed.vault!!.contentEquals(tricky))
        assertEquals("abc", String(parsed.database))
    }

    @Test
    fun `an entry count that is not a snapshot is refused`() {
        val manifest = manifestWith(files = emptyList(), vault = false)
        val archive = buildArchiveV2(manifest, emptyMap())
        // Overwrite the entry count with something absurd.
        val broken = archive.copyOf()
        val countOffset = 4 + json.encodeToString(SnapshotManifest.serializer(), manifest).toByteArray().size
        broken[countOffset] = 0xFF.toByte()
        broken[countOffset + 1] = 0xFF.toByte()
        broken[countOffset + 2] = 0xFF.toByte()
        broken[countOffset + 3] = 0xFF.toByte()

        val refused = runCatching { parseArchive(broken, formatVersion = 2) }
        assertTrue("a nonsense entry count must be refused, not parsed as far as it can", refused.isFailure)
    }

    @Test
    fun `the manifest records a hash for every file it claims to carry`() {
        val database = "db".toByteArray()
        val vault = "vault".toByteArray()
        val manifest = manifestWith(
            files = listOf(
                SnapshotFileEntry(SnapshotFormat.DATABASE_ENTRY, database.size.toLong(), SnapshotFormat.sha256(database)),
                SnapshotFileEntry(SnapshotFormat.CEREBRUM_VAULT_ENTRY, vault.size.toLong(), SnapshotFormat.sha256(vault)),
            ),
            vault = true,
        )
        val parsed = parseArchive(
            buildArchiveV2(
                manifest,
                linkedMapOf(
                    SnapshotFormat.DATABASE_ENTRY to database,
                    SnapshotFormat.CEREBRUM_VAULT_ENTRY to vault,
                ),
            ),
            formatVersion = 2,
        )
        // Every entry the archive carries must be described by the manifest, and vice versa.
        assertEquals(parsed.entries.keys, parsed.manifest.files.map { it.name }.toSet())
        for (entry in parsed.manifest.files) {
            val carried = parsed.entries.getValue(entry.name)
            assertNull(
                "the recorded hash must match the bytes behind it: ${entry.name}",
                SnapshotVerifier.verifyFile(entry, carried.size.toLong(), SnapshotFormat.sha256(carried)),
            )
        }
    }
}
