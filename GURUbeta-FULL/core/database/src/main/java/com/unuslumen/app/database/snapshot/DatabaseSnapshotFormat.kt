// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.database.snapshot

import kotlinx.serialization.Serializable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * The on-disk format of a GURU brain snapshot.
 *
 * WHY A FORMAT OF OUR OWN RATHER THAN A RAW FILE COPY. The brain is one SQLite
 * database, but a phone upgrade must carry it to a different handset with a
 * different build, and it must be safe to leave lying in a folder the owner
 * picked. So the artefact is a single self-describing file: a plaintext header
 * that says how to open it, then one AES-256-GCM sealed blob, then nothing.
 *
 * THE HEADER IS PLAINTEXT ON PURPOSE. Salt and iteration count are not secrets;
 * the password is. Putting them ahead of the ciphertext means a restore on a new
 * phone can derive the key without knowing anything about the phone that wrote
 * the file, which is the entire point of a portable snapshot. Nothing about the
 * database itself, not one table name, appears outside the seal.
 *
 * WHY PBKDF2 AND NOT THE KEYSTORE. CredentialVault seals service secrets under
 * hardware keys that physically cannot leave the device. That is exactly wrong
 * here: a snapshot sealed to this phone's silicon is useless on the new phone it
 * exists for. A passphrase the human remembers is the only key that survives a
 * handset change, so it is the key this format uses.
 *
 * THIS FILE HAS NO ANDROID IMPORTS. Every decision that can be wrong lives here
 * so a JVM test can prove it in seconds rather than waiting for a handset.
 */
object SnapshotFormat {

    /** Eight bytes that identify the file as ours before anything else is read. */
    val MAGIC: ByteArray = byteArrayOf(
        'G'.code.toByte(), 'U'.code.toByte(), 'R'.code.toByte(), 'U'.code.toByte(),
        'S'.code.toByte(), 'N'.code.toByte(), 'A'.code.toByte(), 'P'.code.toByte(),
    )

    /**
     * Bumped whenever the layout below changes shape.
     *
     * A reader refuses a version it does not know rather than guessing: reading a
     * newer file with an older parser is how a restore silently loses columns.
     */
    const val VERSION: Int = 1

    /** 16 bytes. Random per snapshot, stored in the header, never reused. */
    const val SALT_BYTES: Int = 16

    /** 12 bytes, the GCM standard nonce length, random per snapshot. */
    const val IV_BYTES: Int = 12

    /** 128-bit authentication tag, which is what makes a wrong password detectable. */
    const val TAG_BITS: Int = 128

    /**
     * PBKDF2-HMAC-SHA256 work factor.
     *
     * 210,000 is the OWASP 2023 figure for this PRF. It is stored in the header so
     * a snapshot written today still opens if the default changes later, and a
     * reader enforces [MIN_ITERATIONS] so a tampered header cannot ask for a
     * thousand iterations and make an offline guess cheap.
     */
    const val DEFAULT_ITERATIONS: Int = 210_000

    /** Never accept a snapshot that claims less work than this. */
    const val MIN_ITERATIONS: Int = 100_000

    /** AES-256. */
    const val KEY_BITS: Int = 256

    /**
     * Smallest possible sealed file: the header, a tag and no body.
     *
     * Used to reject a truncated picker result before any cryptography runs, so a
     * zero-byte file reads as "that is not a snapshot" rather than as a bad password.
     */
    const val MIN_SEALED_BYTES: Int =
        8 + 4 + 4 + SALT_BYTES + IV_BYTES + (TAG_BITS / 8)

    /** The name every snapshot artefact carries, so a folder can find them again. */
    const val FILE_EXTENSION: String = "gurusnap"

    /** The name of the database inside the sealed archive. Matches guruDatabase.DATABASE_NAME. */
    const val DATABASE_ENTRY: String = "guru_db"

    /** The manifest inside the sealed archive. */
    const val MANIFEST_ENTRY: String = "manifest.json"

    private const val CIPHER = "AES/GCM/NoPadding"
    private const val PRF = "PBKDF2WithHmacSHA256"

    fun cipherTransformation(): String = CIPHER

    /**
     * Derive the AES-256 key from a passphrase.
     *
     * The passphrase is converted to chars and the caller's array is cleared by the
     * caller, not here: PBEKeySpec copies it, and wiping it inside this function
     * would break a caller that reuses the same passphrase for a verify immediately
     * afterwards, which is the common path.
     */
    fun deriveKey(passphrase: CharArray, salt: ByteArray, iterations: Int): ByteArray {
        require(passphrase.isNotEmpty()) { "A snapshot passphrase cannot be empty." }
        require(iterations >= MIN_ITERATIONS) {
            "Refusing $iterations PBKDF2 iterations; the format requires at least $MIN_ITERATIONS."
        }
        val spec = PBEKeySpec(passphrase, salt, iterations, KEY_BITS)
        return try {
            SecretKeyFactory.getInstance(PRF).generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun sha256(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            digest.update(buffer, 0, read)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** Everything the reader needs before the key can be derived. */
    data class Header(
        val version: Int,
        val iterations: Int,
        val salt: ByteArray,
        val iv: ByteArray,
    ) {
        // ByteArray gives identity equals, which turns every comparison of two
        // headers into a false negative. Value semantics are what any caller means.
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Header) return false
            return version == other.version &&
                iterations == other.iterations &&
                salt.contentEquals(other.salt) &&
                iv.contentEquals(other.iv)
        }

        override fun hashCode(): Int {
            var result = version
            result = 31 * result + iterations
            result = 31 * result + salt.contentHashCode()
            result = 31 * result + iv.contentHashCode()
            return result
        }
    }

    fun writeHeader(out: OutputStream, header: Header) {
        val data = DataOutputStream(out)
        data.write(MAGIC)
        data.writeInt(header.version)
        data.writeInt(header.iterations)
        data.write(header.salt)
        data.write(header.iv)
        data.flush()
    }

    /**
     * Read and validate the header.
     *
     * Every failure is a distinct message because the three causes need different
     * actions from the person holding the file: a wrong magic means they picked the
     * wrong file, an unknown version means the snapshot is from a newer GURU, and a
     * weak iteration count means the file was edited.
     */
    fun readHeader(input: InputStream): HeaderRead {
        val data = DataInputStream(input)
        val magic = ByteArray(MAGIC.size)
        try {
            data.readFully(magic)
        } catch (_: Exception) {
            return HeaderRead.NotASnapshot("the file is shorter than a snapshot header")
        }
        if (!magic.contentEquals(MAGIC)) {
            return HeaderRead.NotASnapshot("the file does not begin with the GURU snapshot marker")
        }

        val version = readIntOrNull(data)
            ?: return HeaderRead.NotASnapshot("the header ended before its format version")
        if (version > VERSION) {
            return HeaderRead.UnsupportedVersion(
                "this snapshot is format version $version and this build reads version $VERSION at most"
            )
        }
        if (version < 1) {
            return HeaderRead.NotASnapshot("format version $version does not exist")
        }

        val iterations = readIntOrNull(data)
            ?: return HeaderRead.NotASnapshot("the header ended before its work factor")
        if (iterations < MIN_ITERATIONS) {
            return HeaderRead.WeakenedKeyDerivation(
                "the header asks for $iterations PBKDF2 iterations, below the $MIN_ITERATIONS minimum, " +
                    "so this file has been edited and will not be opened"
            )
        }

        val salt = ByteArray(SALT_BYTES)
        val iv = ByteArray(IV_BYTES)
        try {
            data.readFully(salt)
            data.readFully(iv)
        } catch (_: Exception) {
            return HeaderRead.NotASnapshot("the header ended before its salt and nonce")
        }

        return HeaderRead.Read(Header(version, iterations, salt, iv))
    }

    private fun readIntOrNull(data: DataInputStream): Int? = try {
        data.readInt()
    } catch (_: Exception) {
        null
    }

    sealed class HeaderRead {
        data class Read(val header: Header) : HeaderRead()
        data class NotASnapshot(val reason: String) : HeaderRead()
        data class UnsupportedVersion(val reason: String) : HeaderRead()
        data class WeakenedKeyDerivation(val reason: String) : HeaderRead()
    }
}

/** One file inside the sealed archive, with the hash taken at write time. */
@Serializable
data class SnapshotFileEntry(
    val name: String,
    val sizeBytes: Long,
    val sha256: String,
)

/**
 * What the snapshot claims about itself, sealed inside it.
 *
 * The row counts are the reason this exists. A restore that decrypts cleanly and
 * passes an integrity check can still be the wrong file, and a count mismatch is
 * the only cheap way to catch a snapshot taken from a different install or a
 * half-written export. The hash of each file catches corruption; the counts catch
 * substitution.
 */
@Serializable
data class SnapshotManifest(
    val formatVersion: Int = SnapshotFormat.VERSION,
    /** Wall clock at write time, so a person can tell which snapshot is which. */
    val createdAtMillis: Long,
    /** SQLite's own user_version, which is how Room tracks the schema. */
    val databaseSchemaVersion: Int,
    /** Every file the seal carries, with its size and hash. */
    val files: List<SnapshotFileEntry>,
    /** Every table and how many rows it held, measured inside the same read. */
    val tableRowCounts: Map<String, Long>,
    val totalRows: Long,
    /** The main database file's byte length before packaging. */
    val sourceDatabaseBytes: Long,
)

/**
 * The answer to "can this file actually be restored".
 *
 * Ordered by severity in the reader's mind: [Usable] is the only one that may be
 * installed, and every other member names the specific thing that is wrong so the
 * person is not told "restore failed" when the real answer is "that is your
 * holiday photo".
 */
sealed class SnapshotVerification {
    abstract val message: String

    /** Everything matched: the file may be staged for install. */
    data class Usable(
        val manifest: SnapshotManifest,
        override val message: String,
    ) : SnapshotVerification()

    /** The magic or the header is wrong, so this is not one of ours. */
    data class NotASnapshot(override val message: String) : SnapshotVerification()

    /** The file was made by a GURU newer than this build, so it may hold columns this build cannot keep. */
    data class SchemaTooNew(override val message: String) : SnapshotVerification()

    /** The GCM tag failed, which means a wrong passphrase or a file that was altered. */
    data class CannotOpen(override val message: String) : SnapshotVerification()

    /** A file inside the seal does not match the hash recorded when it was written. */
    data class CorruptContent(override val message: String) : SnapshotVerification()

    /** SQLite says the database itself is damaged. */
    data class DamagedDatabase(override val message: String) : SnapshotVerification()

    /** The content decrypted and opened, but it is not the database the manifest describes. */
    data class CountMismatch(override val message: String) : SnapshotVerification()

    val isUsable: Boolean get() = this is Usable
}

/**
 * The decisions, kept apart from the file handling so they can be proven.
 *
 * Every branch here is a way a restore can go wrong in the field, and every one of
 * them is a one-line JVM assertion rather than a handset experiment.
 */
object SnapshotVerifier {

    /**
     * Compare the manifest against what the decrypted database actually reports.
     *
     * A table present in the manifest but absent from the database is the failure
     * that matters most, because it means the file is a different database that
     * happens to decrypt. A table present in the database but absent from the
     * manifest is the same fault seen from the other side, so both directions are
     * checked rather than only the counts that match.
     */
    fun verifyContent(
        manifest: SnapshotManifest,
        observedRowCounts: Map<String, Long>,
        observedSchemaVersion: Int,
        buildSchemaVersion: Int,
    ): SnapshotVerification {
        if (observedSchemaVersion > buildSchemaVersion) {
            return SnapshotVerification.SchemaTooNew(
                "the snapshot is schema version $observedSchemaVersion and this build stops at " +
                    "$buildSchemaVersion, so installing it would drop every column added since. " +
                    "Update GURU first, then restore."
            )
        }

        val missing = manifest.tableRowCounts.keys - observedRowCounts.keys
        if (missing.isNotEmpty()) {
            return SnapshotVerification.CountMismatch(
                "the manifest lists ${missing.size} table(s) the database does not have: " +
                    missing.sorted().joinToString(", ").take(400) + ". This file is not the " +
                    "database it claims to be."
            )
        }

        val unexpected = observedRowCounts.keys - manifest.tableRowCounts.keys
        if (unexpected.isNotEmpty()) {
            return SnapshotVerification.CountMismatch(
                "the database holds ${unexpected.size} table(s) the manifest does not list: " +
                    unexpected.sorted().joinToString(", ").take(400) + ". This file is not the " +
                    "database it claims to be."
            )
        }

        val differing = manifest.tableRowCounts.entries
            .filter { (table, expected) -> observedRowCounts[table] != expected }
            .sortedBy { it.key }

        if (differing.isNotEmpty()) {
            val detail = differing.take(8).joinToString("; ") { (table, expected) ->
                "$table expected $expected, found ${observedRowCounts[table] ?: 0}"
            }
            val more = if (differing.size > 8) " and ${differing.size - 8} more" else ""
            return SnapshotVerification.CountMismatch(
                "the row counts do not match the manifest, so the content changed after the " +
                    "snapshot was written: $detail$more."
            )
        }

        return SnapshotVerification.Usable(
            manifest = manifest,
            message = "Verified ${manifest.totalRows} rows across ${manifest.tableRowCounts.size} " +
                "tables at schema version $observedSchemaVersion. Every file hash and every row " +
                "count matches what was recorded when the snapshot was written.",
        )
    }

    /**
     * Compare a file on disk against the hash recorded for it at write time.
     *
     * A size difference is checked first because it needs no hashing and produces a
     * clearer sentence: "this file was truncated" is more useful than "hashes differ".
     */
    fun verifyFile(entry: SnapshotFileEntry, actualSize: Long, actualSha256: String): SnapshotVerification? {
        if (actualSize != entry.sizeBytes) {
            return SnapshotVerification.CorruptContent(
                "${entry.name} is $actualSize bytes but the manifest recorded ${entry.sizeBytes}, " +
                    "so the file was truncated or replaced."
            )
        }
        if (!actualSha256.equals(entry.sha256, ignoreCase = true)) {
            return SnapshotVerification.CorruptContent(
                "${entry.name} hashes to $actualSha256 but the manifest recorded ${entry.sha256}, " +
                    "so its bytes changed after the snapshot was written."
            )
        }
        return null
    }

    /**
     * The filename a snapshot should carry.
     *
     * A sortable timestamp rather than an epoch number, because the owner reads this
     * in a file picker and "2026-11-12_09-14-22" answers "which one is newest"
     * without arithmetic. Rotation depends on the same property.
     */
    fun fileNameFor(createdAtMillis: Long, zone: java.util.TimeZone): String {
        val format = java.text.SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", java.util.Locale.UK)
        format.timeZone = zone
        return "guru-brain_${format.format(java.util.Date(createdAtMillis))}.${SnapshotFormat.FILE_EXTENSION}"
    }
}
