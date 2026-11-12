// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.database.snapshot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.TimeZone
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Proof for the snapshot format's decisions.
 *
 * Every test here answers a question that would otherwise be answered by trying a
 * restore on a handset and hoping: does a wrong password fail, does an edited
 * header fail, does a newer schema refuse, does a changed row count refuse. The
 * round trip is included because a format that seals but cannot open is worse than
 * no format at all, and it is exactly the mistake a hand-written header invites.
 */
class DatabaseSnapshotFormatTest {

    private val passphrase = "correct horse battery staple".toCharArray()

    private fun randomBytes(size: Int, seed: Int): ByteArray {
        val bytes = ByteArray(size)
        var value = seed
        for (i in bytes.indices) {
            // Deterministic so a failure reproduces. Not random: the crypto is proved
            // by round trip and tag failure, not by the quality of this fixture.
            value = value * 1103515245 + 12345
            bytes[i] = (value ushr 16).toByte()
        }
        return bytes
    }

    private fun seal(plaintext: ByteArray, password: CharArray): ByteArray {
        // A real random source, not the seeded fixture above, because the property being
        // proved is that a fresh salt and nonce per snapshot make two files of identical
        // content differ. A seeded generator would hide a production bug that reused them.
        val random = SecureRandom()
        val salt = ByteArray(SnapshotFormat.SALT_BYTES).also { random.nextBytes(it) }
        val iv = ByteArray(SnapshotFormat.IV_BYTES).also { random.nextBytes(it) }
        val key = SnapshotFormat.deriveKey(password, salt, SnapshotFormat.DEFAULT_ITERATIONS)
        val cipher = Cipher.getInstance(SnapshotFormat.cipherTransformation())
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(SnapshotFormat.TAG_BITS, iv),
        )
        val sealed = cipher.doFinal(plaintext)

        val out = ByteArrayOutputStream()
        SnapshotFormat.writeHeader(
            out,
            SnapshotFormat.Header(
                version = SnapshotFormat.VERSION,
                iterations = SnapshotFormat.DEFAULT_ITERATIONS,
                salt = salt,
                iv = iv,
            ),
        )
        out.write(sealed)
        return out.toByteArray()
    }

    private fun open(file: ByteArray, password: CharArray): ByteArray {
        val headerRead = SnapshotFormat.readHeader(ByteArrayInputStream(file))
        assertTrue("the header must read back", headerRead is SnapshotFormat.HeaderRead.Read)
        val header = (headerRead as SnapshotFormat.HeaderRead.Read).header
        val key = SnapshotFormat.deriveKey(password, header.salt, header.iterations)
        val cipher = Cipher.getInstance(SnapshotFormat.cipherTransformation())
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(SnapshotFormat.TAG_BITS, header.iv),
        )
        val offset = SnapshotFormat.MIN_SEALED_BYTES - (SnapshotFormat.TAG_BITS / 8)
        return cipher.doFinal(file, offset, file.size - offset)
    }

    @Test
    fun `a sealed snapshot opens with the same passphrase and returns the same bytes`() {
        val plaintext = "the whole brain of one person".toByteArray()
        val file = seal(plaintext, passphrase)

        val opened = open(file, "correct horse battery staple".toCharArray())

        assertArrayEquals(plaintext, opened)
    }

    @Test
    fun `the right passphrase is required because a single character change fails the tag`() {
        val file = seal("brain".toByteArray(), passphrase)

        var threw = false
        try {
            open(file, "correct horse battery stapl3".toCharArray())
        } catch (_: Exception) {
            threw = true
        }

        assertTrue("a wrong passphrase must fail rather than return bytes", threw)
    }

    @Test
    fun `editing one byte of the ciphertext fails the tag`() {
        val file = seal("brain".toByteArray(), passphrase)
        val tampered = file.copyOf()
        tampered[tampered.size - 1] = (tampered[tampered.size - 1] + 1).toByte()

        var threw = false
        try {
            open(tampered, "correct horse battery staple".toCharArray())
        } catch (_: Exception) {
            threw = true
        }

        assertTrue("a changed ciphertext must be refused, not read", threw)
    }

    @Test
    fun `a file that is not a snapshot is named as the wrong file rather than a bad password`() {
        val photo = ByteArray(4096) { 0x42 }

        val read = SnapshotFormat.readHeader(ByteArrayInputStream(photo))

        assertTrue(read is SnapshotFormat.HeaderRead.NotASnapshot)
        assertTrue(
            (read as SnapshotFormat.HeaderRead.NotASnapshot).reason.contains("marker"),
        )
    }

    @Test
    fun `a truncated file is rejected before any cryptography runs`() {
        val file = seal("brain".toByteArray(), passphrase)
        val truncated = file.copyOfRange(0, 12)

        val read = SnapshotFormat.readHeader(ByteArrayInputStream(truncated))

        assertTrue(read is SnapshotFormat.HeaderRead.NotASnapshot)
    }

    @Test
    fun `a snapshot from a newer build is refused with its version named`() {
        val out = ByteArrayOutputStream()
        SnapshotFormat.writeHeader(
            out,
            SnapshotFormat.Header(
                version = SnapshotFormat.VERSION + 1,
                iterations = SnapshotFormat.DEFAULT_ITERATIONS,
                salt = ByteArray(SnapshotFormat.SALT_BYTES),
                iv = ByteArray(SnapshotFormat.IV_BYTES),
            ),
        )

        val read = SnapshotFormat.readHeader(ByteArrayInputStream(out.toByteArray()))

        assertTrue(read is SnapshotFormat.HeaderRead.UnsupportedVersion)
        assertTrue(
            (read as SnapshotFormat.HeaderRead.UnsupportedVersion).reason.contains(
                (SnapshotFormat.VERSION + 1).toString(),
            ),
        )
    }

    @Test
    fun `a header with a weakened work factor is refused instead of opened cheaply`() {
        val out = ByteArrayOutputStream()
        SnapshotFormat.writeHeader(
            out,
            SnapshotFormat.Header(
                version = SnapshotFormat.VERSION,
                iterations = 1_000,
                salt = ByteArray(SnapshotFormat.SALT_BYTES),
                iv = ByteArray(SnapshotFormat.IV_BYTES),
            ),
        )

        val read = SnapshotFormat.readHeader(ByteArrayInputStream(out.toByteArray()))

        assertTrue(read is SnapshotFormat.HeaderRead.WeakenedKeyDerivation)
    }

    @Test
    fun `the header survives a round trip with its salt and nonce intact`() {
        val salt = randomBytes(SnapshotFormat.SALT_BYTES, 3)
        val iv = randomBytes(SnapshotFormat.IV_BYTES, 5)
        val out = ByteArrayOutputStream()
        SnapshotFormat.writeHeader(
            out,
            SnapshotFormat.Header(SnapshotFormat.VERSION, SnapshotFormat.DEFAULT_ITERATIONS, salt, iv),
        )

        val read = SnapshotFormat.readHeader(ByteArrayInputStream(out.toByteArray()))

        val header = (read as SnapshotFormat.HeaderRead.Read).header
        assertEquals(SnapshotFormat.VERSION, header.version)
        assertEquals(SnapshotFormat.DEFAULT_ITERATIONS, header.iterations)
        assertArrayEquals(salt, header.salt)
        assertArrayEquals(iv, header.iv)
    }

    @Test
    fun `two snapshots of identical content do not produce identical files`() {
        val first = seal("brain".toByteArray(), passphrase)
        val second = seal("brain".toByteArray(), passphrase)

        // A fresh salt and nonce per snapshot is what stops a reader learning that two
        // backups hold the same content, and what stops a nonce ever being reused.
        assertFalse("two snapshots must not share a header", first.contentEquals(second))
    }

    @Test
    fun `a snapshot of a newer schema is refused because installing it would drop columns`() {
        val manifest = SnapshotManifest(
            createdAtMillis = 1_700_000_000_000,
            databaseSchemaVersion = 35,
            files = emptyList(),
            tableRowCounts = mapOf("notes" to 3L),
            totalRows = 3,
            sourceDatabaseBytes = 1024,
        )

        val verdict = SnapshotVerifier.verifyContent(
            manifest = manifest,
            observedRowCounts = mapOf("notes" to 3L),
            observedSchemaVersion = 36,
            buildSchemaVersion = 35,
        )

        assertTrue(verdict is SnapshotVerification.SchemaTooNew)
        assertTrue(verdict.message.contains("36"))
    }

    @Test
    fun `a database missing a table the manifest lists is refused`() {
        val manifest = SnapshotManifest(
            createdAtMillis = 1,
            databaseSchemaVersion = 35,
            files = emptyList(),
            tableRowCounts = mapOf("notes" to 3L, "journal" to 9L),
            totalRows = 12,
            sourceDatabaseBytes = 1024,
        )

        val verdict = SnapshotVerifier.verifyContent(
            manifest = manifest,
            observedRowCounts = mapOf("notes" to 3L),
            observedSchemaVersion = 35,
            buildSchemaVersion = 35,
        )

        assertTrue(verdict is SnapshotVerification.CountMismatch)
        assertTrue("the missing table must be named", verdict.message.contains("journal"))
    }

    @Test
    fun `a database holding a table the manifest does not list is refused`() {
        val manifest = SnapshotManifest(
            createdAtMillis = 1,
            databaseSchemaVersion = 35,
            files = emptyList(),
            tableRowCounts = mapOf("notes" to 3L),
            totalRows = 3,
            sourceDatabaseBytes = 1024,
        )

        val verdict = SnapshotVerifier.verifyContent(
            manifest = manifest,
            observedRowCounts = mapOf("notes" to 3L, "surprise" to 1L),
            observedSchemaVersion = 35,
            buildSchemaVersion = 35,
        )

        assertTrue(verdict is SnapshotVerification.CountMismatch)
        assertTrue("the unexpected table must be named", verdict.message.contains("surprise"))
    }

    @Test
    fun `a matching count on one table does not excuse a differing count on another`() {
        val manifest = SnapshotManifest(
            createdAtMillis = 1,
            databaseSchemaVersion = 35,
            files = emptyList(),
            tableRowCounts = mapOf("notes" to 3L, "journal" to 9L),
            totalRows = 12,
            sourceDatabaseBytes = 1024,
        )

        val verdict = SnapshotVerifier.verifyContent(
            manifest = manifest,
            observedRowCounts = mapOf("notes" to 3L, "journal" to 8L),
            observedSchemaVersion = 35,
            buildSchemaVersion = 35,
        )

        assertTrue(verdict is SnapshotVerification.CountMismatch)
        assertTrue("the short table must be named", verdict.message.contains("journal"))
    }

    @Test
    fun `an exact match is usable and says what it verified`() {
        val manifest = SnapshotManifest(
            createdAtMillis = 1,
            databaseSchemaVersion = 35,
            files = emptyList(),
            tableRowCounts = mapOf("notes" to 3L, "journal" to 9L),
            totalRows = 12,
            sourceDatabaseBytes = 1024,
        )

        val verdict = SnapshotVerifier.verifyContent(
            manifest = manifest,
            observedRowCounts = mapOf("notes" to 3L, "journal" to 9L),
            observedSchemaVersion = 35,
            buildSchemaVersion = 35,
        )

        assertTrue(verdict.isUsable)
        assertTrue(verdict.message.contains("12 rows"))
    }

    @Test
    fun `a file whose size changed is refused without hashing it`() {
        val entry = SnapshotFileEntry("guru_db", sizeBytes = 100, sha256 = "abc")

        val verdict = SnapshotVerifier.verifyFile(entry, actualSize = 99, actualSha256 = "abc")

        assertTrue(verdict is SnapshotVerification.CorruptContent)
        assertTrue(verdict!!.message.contains("truncated or replaced"))
    }

    @Test
    fun `a file whose bytes changed is refused even when its size matches`() {
        val entry = SnapshotFileEntry("guru_db", sizeBytes = 100, sha256 = "abc")

        val verdict = SnapshotVerifier.verifyFile(entry, actualSize = 100, actualSha256 = "abd")

        assertTrue(verdict is SnapshotVerification.CorruptContent)
        assertTrue(verdict!!.message.contains("bytes changed"))
    }

    @Test
    fun `hash comparison ignores case so a lowercase digest matches an uppercase one`() {
        val entry = SnapshotFileEntry("guru_db", sizeBytes = 100, sha256 = "ABCDEF")

        val verdict = SnapshotVerifier.verifyFile(entry, actualSize = 100, actualSha256 = "abcdef")

        assertEquals(null, verdict)
    }

    @Test
    fun `two different byte streams do not share a hash`() {
        val one = SnapshotFormat.sha256("the brain".toByteArray())
        val two = SnapshotFormat.sha256("the brain.".toByteArray())

        assertNotEquals(one, two)
    }

    @Test
    fun `hashing a stream and hashing the same bytes agree`() {
        val bytes = "a stream of bytes long enough to cross one read buffer".repeat(5000).toByteArray()

        val fromBytes = SnapshotFormat.sha256(bytes)
        val fromStream = SnapshotFormat.sha256(ByteArrayInputStream(bytes))

        assertEquals("a streamed hash must equal a one-shot hash or a large file is unverifiable", fromBytes, fromStream)
        assertEquals(
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) },
            fromBytes,
        )
    }

    @Test
    fun `snapshot names sort newest last so rotation can drop the oldest`() {
        val older = SnapshotVerifier.fileNameFor(1_700_000_000_000, TimeZone.getTimeZone("UTC"))
        val newer = SnapshotVerifier.fileNameFor(1_700_000_100_000, TimeZone.getTimeZone("UTC"))

        assertTrue("a later snapshot must sort after an earlier one", newer > older)
        assertTrue(older.endsWith(".${SnapshotFormat.FILE_EXTENSION}"))
    }

    @Test
    fun `snapshot names carry the zone they were taken in`() {
        val utc = SnapshotVerifier.fileNameFor(1_700_000_000_000, TimeZone.getTimeZone("UTC"))
        val bristol = SnapshotVerifier.fileNameFor(1_700_000_000_000, TimeZone.getTimeZone("Europe/London"))

        // November is GMT, so these agree. The point of the test is that the parameter
        // is honoured rather than ignored, which is proved by a summer instant.
        val summerUtc = SnapshotVerifier.fileNameFor(1_690_000_000_000, TimeZone.getTimeZone("UTC"))
        val summerBristol = SnapshotVerifier.fileNameFor(1_690_000_000_000, TimeZone.getTimeZone("Europe/London"))

        assertNotEquals(summerUtc, summerBristol)
        assertTrue(utc.isNotEmpty() && bristol.isNotEmpty())
    }

    private fun assertArrayEquals(expected: ByteArray, actual: ByteArray) {
        assertEquals(
            "expected ${expected.size} bytes but found ${actual.size}",
            expected.size,
            actual.size,
        )
        for (i in expected.indices) {
            assertEquals("byte $i differs", expected[i], actual[i])
        }
    }
}
