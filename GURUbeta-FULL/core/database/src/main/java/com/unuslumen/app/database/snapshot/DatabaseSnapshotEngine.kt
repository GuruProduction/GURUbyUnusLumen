// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.database.snapshot

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Writes a portable, encrypted snapshot of the whole brain, and restores one.
 *
 * THE FOUR THINGS THIS GETS RIGHT THAT A FILE COPY DOES NOT.
 *
 * 1. THE COPY IS TAKEN THROUGH SQLITE, NOT THE FILESYSTEM. A running Room database
 *    keeps committed pages in `guru_db-wal` until a checkpoint, so `cp guru_db` can
 *    produce a file that opens cleanly and is missing the last half hour of the
 *    user's life. [stageCopy] attaches the source read-only and uses SQLite's backup
 *    step, which walks the logical pages and therefore sees the WAL.
 *
 * 2. THIS APP'S OWN WAL AND SHM ARE STAGED WITH THE COPY. When the copy is written,
 *    the staged database is left in whatever journalling mode it was opened in, so a
 *    staged `-wal` can exist and hold pages the main file has not absorbed. Sealing
 *    only the main file would silently drop them.
 *
 * 3. NO PLAINTEXT SIDECAR IS EVER LEFT BEHIND. The issue that opened this work
 *    leaked two `-wal` files beside an encrypted snapshot. App-private storage is
 *    still storage, a `-wal` holds plaintext pages, and deleting the database does
 *    not delete its sidecars. [wipeStaging] removes every artefact it created, and
 *    the staging directory name is random per run so two snapshots cannot collide.
 *
 * 4. THE RESTORE MOVES THE OLD BRAIN ASIDE, IT DOES NOT OVERWRITE IT. A restore that
 *    destroys the current database before the new one is proven is a second way to
 *    lose the brain in the act of recovering it. [installStaged] renames the live
 *    files to a timestamped aside-set, moves the staged files in, and only then
 *    deletes the aside-set. If the move fails halfway the aside-set is renamed back.
 *
 * WHY THE SNAPSHOT IS NOT SEALED TO THIS PHONE. See [SnapshotFormat]: a snapshot
 * exists for the phone the user has not bought yet, so the key is a passphrase, and
 * it never touches the Keystore.
 */
class DatabaseSnapshotEngine(private val context: Context) {

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = false
        encodeDefaults = true
    }

    private fun databaseFile(): File =
        context.getDatabasePath(com.unuslumen.app.database.guruDatabase.DATABASE_NAME)

    private fun stagingRoot(): File = File(context.cacheDir, "brain_snapshot_staging")

    private fun newStagingDir(): File {
        val dir = File(stagingRoot(), "run-${System.currentTimeMillis()}-${System.nanoTime()}")
        dir.mkdirs()
        return dir
    }

    // ------------------------------------------------------------------
    // Writing
    // ------------------------------------------------------------------

    /**
     * Take a verified, encrypted snapshot into [destination].
     *
     * The order is deliberate: copy, count, seal, re-open the seal and verify it,
     * then and only then move it to [destination]. A snapshot that cannot be opened
     * is worse than no snapshot, because it is discovered at the worst possible
     * moment, so the file the person is given has already been round-tripped.
     */
    suspend fun writeSnapshot(
        destination: File,
        passphrase: CharArray,
        progress: ((String) -> Unit)? = null,
    ): SnapshotWriteOutcome {
        val source = databaseFile()
        if (!source.exists()) {
            return SnapshotWriteOutcome.Failed(
                "There is no brain database on this device yet, so there is nothing to snapshot.",
            )
        }

        val staging = newStagingDir()
        try {
            progress?.invoke("Copying the brain through SQLite so pages still in the write-ahead log are included")
            val staged = stageCopy(source, staging)
                ?: return SnapshotWriteOutcome.Failed(
                    "SQLite refused the backup step, so no copy of the brain was taken. Nothing was written.",
                )

            progress?.invoke("Counting rows in every table")
            val counts = readRowCounts(staged)
            val schemaVersion = readSchemaVersion(staged)
            if (counts.isEmpty()) {
                return SnapshotWriteOutcome.Failed(
                    "The staged copy reports no tables at all, which means the copy did not carry the " +
                        "brain. Nothing was written.",
                )
            }

            // The staged database is closed by this point, so its WAL holds anything not
            // yet folded in. Moving it into the archive's staging slot keeps those pages.
            foldWalIntoMain(staged)

            progress?.invoke("Sealing the snapshot with AES-256-GCM")
            val manifest = buildManifest(
                createdAtMillis = System.currentTimeMillis(),
                schemaVersion = schemaVersion,
                counts = counts,
                staged = staging,
                sourceBytes = source.length(),
            )

            val payload = ByteArrayFile(staged)
            val payloadEntry = SnapshotFileEntry(
                name = SnapshotFormat.DATABASE_ENTRY,
                sizeBytes = staged.length(),
                sha256 = hashFile(staged),
            )
            val files = listOf(payloadEntry)

            val archive = buildArchive(payload, manifest)
            val sealed = seal(archive, passphrase)

            progress?.invoke("Opening the snapshot again to prove it restores")
            val verification = verifySealedBytes(sealed, passphrase, schemaVersion, counts)
            if (!verification.isUsable) {
                return SnapshotWriteOutcome.Failed(
                    "The snapshot was written but failed its own restore check, so it was not kept: " +
                        verification.message,
                )
            }

            // Written to a sibling first and renamed, so a failure here cannot leave a
            // half-file at the destination that looks like a valid snapshot.
            val temp = File(destination.absolutePath + ".partial")
            temp.parentFile?.mkdirs()
            temp.writeBytes(sealed)
            if (!temp.renameTo(destination)) {
                temp.copyTo(destination, overwrite = true)
                temp.delete()
            }

            return SnapshotWriteOutcome.Written(
                file = destination,
                manifest = manifest,
                bytes = destination.length(),
                sealBytes = sealed.size,
                message = buildString {
                    append("Snapshot written to ${destination.name} (")
                    append("${destination.length()} bytes). ")
                    append(verification.message)
                },
            )
        } catch (e: Exception) {
            Log.w("guru_backup", "Snapshot write failed: ${e.message}")
            return SnapshotWriteOutcome.Failed("The snapshot failed: ${e.message ?: e::class.simpleName}.")
        } finally {
            // Runs on every path, including the failure returns above. This is the step
            // the opening issue got wrong and leaked plaintext sidecars.
            wipeStaging(staging)
        }
    }

    /**
     * Copy the live database in a form that includes everything the write-ahead log
     * holds.
     *
     * TWO ROUTES, AND BOTH ARE LOGICAL OR WAL-AWARE. Neither is `cp guru_db`.
     *
     * `VACUUM INTO` is the canonical answer: it writes a complete new database from the
     * live one, so the result has no WAL of its own and cannot be missing a committed
     * page. It needs SQLite 3.27, which is Android 11 and later.
     *
     * Below that, the copy is the main file plus its `-wal` and `-shm` as a matched
     * set. SQLite treats those three as one unit: a `-wal` without its database is
     * ignored, and a database with a copied `-wal` replays exactly the frames the log
     * holds. Copying the database alone is the failure this whole method exists to
     * avoid, so the sidecars travel with it. The staged copy is then opened and closed
     * in write mode, which checkpoints those frames into the main file and leaves one
     * file to seal.
     */
    private fun stageCopy(source: File, staging: File): File? {
        val staged = File(staging, SnapshotFormat.DATABASE_ENTRY)

        if (stageWithVacuumInto(source, staged)) {
            return staged.takeIf { it.exists() && it.length() > 0 }
        }

        Log.d("guru_backup", "VACUUM INTO unavailable; staging the database with its write-ahead log")
        return stageWithWalSet(source, staged)
    }

    /**
     * Ask SQLite to write a complete copy of the live database.
     *
     * The path is interpolated rather than bound because `VACUUM INTO` takes a literal
     * target; a single quote in the app's own data directory is the only character that
     * could break it, so it is escaped rather than assumed absent.
     */
    private fun stageWithVacuumInto(source: File, staged: File): Boolean {
        if (staged.exists()) staged.delete()
        var db: SQLiteDatabase? = null
        return try {
            db = SQLiteDatabase.openDatabase(source.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
            val escaped = staged.absolutePath.replace("'", "''")
            db.execSQL("VACUUM INTO '$escaped'")
            staged.exists() && staged.length() > 0
        } catch (e: Exception) {
            Log.d("guru_backup", "VACUUM INTO could not be used: ${e.message}")
            false
        } finally {
            runCatching { db?.close() }
        }
    }

    /**
     * Copy the database and its log as one set, then checkpoint the copy.
     *
     * The order matters: the log is copied before the database so a write landing
     * mid-copy can only make the staged log longer than the staged database, which
     * SQLite handles, never shorter, which would lose a frame.
     */
    private fun stageWithWalSet(source: File, staged: File): File? {
        return try {
            val wal = File(source.absolutePath + "-wal")
            val shm = File(source.absolutePath + "-shm")

            if (wal.exists()) wal.copyTo(File(staged.absolutePath + "-wal"), overwrite = true)
            source.copyTo(staged, overwrite = true)
            if (shm.exists()) shm.copyTo(File(staged.absolutePath + "-shm"), overwrite = true)

            // Reading through SQLite now proves the set is coherent and folds the log in.
            SQLiteDatabase.openDatabase(staged.absolutePath, null, SQLiteDatabase.OPEN_READWRITE)
                .use { d -> d.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() } }

            staged.takeIf { it.exists() && it.length() > 0 }
        } catch (e: Exception) {
            Log.w("guru_backup", "Staging the database with its write-ahead log failed: ${e.message}")
            null
        }
    }

    /**
     * Fold a staged `-wal` into its main file, then remove the sidecars.
     *
     * Opening and closing the copy in write mode checkpoints the WAL by default, so
     * the pages land in the main file and the sidecars become removable. This runs
     * before hashing so the hash describes the bytes actually sealed.
     */
    private fun foldWalIntoMain(staged: File) {
        runCatching {
            val db = SQLiteDatabase.openDatabase(staged.absolutePath, null, SQLiteDatabase.OPEN_READWRITE)
            db.use {
                it.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { cursor ->
                    cursor.moveToFirst()
                }
            }
        }.onFailure {
            Log.w("guru_backup", "WAL checkpoint of the staged copy failed: ${it.message}")
        }
        deleteSidecars(staged)
    }

    /** Delete a database's `-wal` and `-shm`. They hold plaintext pages and outlive the file. */
    private fun deleteSidecars(db: File) {
        for (suffix in listOf("-wal", "-shm", "-journal")) {
            val sidecar = File(db.absolutePath + suffix)
            if (sidecar.exists()) {
                val gone = sidecar.delete()
                Log.d("guru_backup", "Snapshot staging: ${sidecar.name} deleted=$gone")
            }
        }
    }

    private fun readRowCounts(db: File): Map<String, Long> {
        val counts = LinkedHashMap<String, Long>()
        val database = SQLiteDatabase.openDatabase(db.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        database.use { d ->
            val tables = mutableListOf<String>()
            d.rawQuery(
                "SELECT name FROM sqlite_master WHERE type='table' " +
                    "AND name NOT LIKE 'sqlite_%' AND name NOT LIKE 'android_%' " +
                    "AND name NOT LIKE '%_fts' AND name NOT LIKE '%_fts_%' ORDER BY name",
                null,
            ).use { cursor ->
                while (cursor.moveToNext()) tables.add(cursor.getString(0))
            }
            for (table in tables) {
                val count = runCatching {
                    d.rawQuery("SELECT COUNT(*) FROM \"$table\"", null).use { cursor ->
                        if (cursor.moveToFirst()) cursor.getLong(0) else 0L
                    }
                }.getOrDefault(-1L)
                // A table SQLite will not count is recorded as -1 so a restore compares
                // the same failure rather than treating it as an empty table. An empty
                // table and an unreadable one are different facts.
                counts[table] = count
            }
        }
        return counts
    }

    private fun readSchemaVersion(db: File): Int {
        val database = SQLiteDatabase.openDatabase(db.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        return database.use { d ->
            d.rawQuery("PRAGMA user_version", null).use { cursor ->
                if (cursor.moveToFirst()) cursor.getInt(0) else 0
            }
        }
    }

    // ------------------------------------------------------------------
    // Reading
    // ------------------------------------------------------------------

    /**
     * Verify a snapshot without installing it.
     *
     * This is what a Settings screen calls to answer "is this file any good" before
     * the owner commits to replacing their brain with it.
     */
    suspend fun verifySnapshots(
        file: File,
        passphrase: CharArray,
    ): SnapshotVerification {
        if (!file.exists()) {
            return SnapshotVerification.NotASnapshot("There is no file at ${file.name}.")
        }
        if (file.length() < SnapshotFormat.MIN_SEALED_BYTES) {
            return SnapshotVerification.NotASnapshot(
                "${file.name} is ${file.length()} bytes, which is smaller than a snapshot header. " +
                    "That is not a GURU snapshot.",
            )
        }
        val bytes = runCatching { file.readBytes() }.getOrElse {
            return SnapshotVerification.NotASnapshot("${file.name} could not be read: ${it.message}.")
        }
        return verifySealedBytes(bytes, passphrase, buildSchemaVersion = currentBuildSchemaVersion())
    }

    private fun verifySealedBytes(
        sealed: ByteArray,
        passphrase: CharArray,
        buildSchemaVersion: Int,
        expectedCounts: Map<String, Long>? = null,
    ): SnapshotVerification {
        val headerRead = SnapshotFormat.readHeader(sealed.inputStream())
        when (headerRead) {
            is SnapshotFormat.HeaderRead.NotASnapshot ->
                return SnapshotVerification.NotASnapshot(headerRead.reason)
            is SnapshotFormat.HeaderRead.UnsupportedVersion ->
                return SnapshotVerification.SchemaTooNew(headerRead.reason)
            is SnapshotFormat.HeaderRead.WeakenedKeyDerivation ->
                return SnapshotVerification.CannotOpen(headerRead.reason)
            is SnapshotFormat.HeaderRead.Read -> Unit
        }
        val header = headerRead.header

        val archive = try {
            open(sealed, header, passphrase)
        } catch (e: Exception) {
            // A GCM tag failure and a wrong passphrase are the same event to the caller,
            // and saying "wrong password or altered file" is the honest sentence because
            // the platform genuinely cannot tell them apart.
            return SnapshotVerification.CannotOpen(
                "The snapshot could not be opened. That means the passphrase is wrong or the file " +
                    "was altered after it was written; the two cannot be told apart.",
            )
        }

        val entries = try {
            parseArchive(archive)
        } catch (e: Exception) {
            return SnapshotVerification.CorruptContent(
                "The snapshot decrypted but its contents could not be read: ${e.message}.",
            )
        }

        val manifest = entries.manifest
        val databaseBytes = entries.database
        val entry = manifest.files.firstOrNull { it.name == SnapshotFormat.DATABASE_ENTRY }
            ?: return SnapshotVerification.CorruptContent(
                "The manifest does not name a ${SnapshotFormat.DATABASE_ENTRY} entry at all.",
            )

        SnapshotVerifier.verifyFile(entry, databaseBytes.size.toLong(), SnapshotFormat.sha256(databaseBytes))
            ?.let { return it }

        // The database is opened from a temporary file because SQLite needs a path, and
        // the whole point of counting rows is to ask SQLite rather than trust the bytes.
        val probeDir = newStagingDir()
        val probe = File(probeDir, "verify_${SnapshotFormat.DATABASE_ENTRY}")
        return try {
            probe.writeBytes(databaseBytes)
            val observedCounts = runCatching { readRowCounts(probe) }.getOrElse {
                return SnapshotVerification.DamagedDatabase(
                    "SQLite could not read the snapshot's database: ${it.message}.",
                )
            }
            val observedSchema = runCatching { readSchemaVersion(probe) }.getOrElse { -1 }

            val integrity = runCatching {
                SQLiteDatabase.openDatabase(probe.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { d ->
                    d.rawQuery("PRAGMA integrity_check", null).use { cursor ->
                        if (cursor.moveToFirst()) cursor.getString(0) else "no result"
                    }
                }
            }.getOrElse { "unreadable: ${it.message}" }

            if (!integrity.equals("ok", ignoreCase = true)) {
                return SnapshotVerification.DamagedDatabase(
                    "SQLite's integrity check on the snapshot's database answered '$integrity', so the " +
                        "content is not installable.",
                )
            }

            val verdict = SnapshotVerifier.verifyContent(
                manifest = manifest,
                observedRowCounts = observedCounts,
                observedSchemaVersion = observedSchema,
                buildSchemaVersion = buildSchemaVersion,
            )

            // On the write path the writer already knows what it just counted, so this
            // catches a manifest that does not describe the archive it sits in.
            if (verdict.isUsable && expectedCounts != null && expectedCounts != observedCounts) {
                return SnapshotVerification.CountMismatch(
                    "The snapshot's own manifest does not match the rows it carries.",
                )
            }

            verdict
        } finally {
            wipeStaging(probeDir)
        }
    }

    /**
     * Decrypt a snapshot's database into [destination].
     *
     * This does NOT install it. Installing is a separate decision with its own
     * failure path, because the moment a brain is replaced is the moment it is most
     * possible to lose.
     */
    suspend fun extractTo(file: File, passphrase: CharArray, destination: File): SnapshotExtractOutcome {
        val verification = verifySnapshots(file, passphrase)
        if (!verification.isUsable) {
            return SnapshotExtractOutcome.Refused(verification)
        }
        val sealed = runCatching { file.readBytes() }.getOrElse {
            return SnapshotExtractOutcome.Refused(
                SnapshotVerification.NotASnapshot("${file.name} could not be read: ${it.message}."),
            )
        }
        val headerRead = SnapshotFormat.readHeader(sealed.inputStream())
        if (headerRead !is SnapshotFormat.HeaderRead.Read) {
            return SnapshotExtractOutcome.Refused(
                SnapshotVerification.NotASnapshot("The snapshot header became unreadable between checks."),
            )
        }
        return try {
            val archive = open(sealed, headerRead.header, passphrase)
            val entries = parseArchive(archive)
            destination.parentFile?.mkdirs()
            destination.writeBytes(entries.database)
            // A fresh file must not inherit a stale sidecar from a previous attempt at
            // the same path, or the restored brain reads pages from the wrong history.
            deleteSidecars(destination)
            SnapshotExtractOutcome.Extracted(destination, entries.manifest, verification)
        } catch (e: Exception) {
            SnapshotExtractOutcome.Refused(
                SnapshotVerification.CorruptContent("The snapshot could not be extracted: ${e.message}."),
            )
        }
    }

    /**
     * Replace the live brain with an already-extracted database.
     *
     * The live files are renamed aside rather than deleted, and the rename is undone if
     * the install does not complete. A restore that leaves the user with no database at
     * all is the failure this method exists to make impossible.
     *
     * THE CALLER MUST STOP THE PROCESS BEFORE OPENING THE NEW FILE. Room holds the old
     * database open, so a restore in a running app can only stage the file and ask for
     * a restart; claiming it took effect while the old handle is live would be a lie.
     */
    suspend fun installStaged(staged: File): SnapshotInstallOutcome {
        val live = databaseFile()
        val stamp = System.currentTimeMillis()
        val asideMain = File(live.absolutePath + ".replaced-$stamp")
        val asideWal = File(live.absolutePath + "-wal.replaced-$stamp")
        val asideShm = File(live.absolutePath + "-shm.replaced-$stamp")

        if (!staged.exists() || staged.length() == 0L) {
            return SnapshotInstallOutcome.Failed("The staged database is missing or empty.")
        }

        val hadLive = live.exists()
        try {
            live.parentFile?.mkdirs()

            if (hadLive) {
                val sidecars = listOf(File(live.absolutePath + "-wal"), File(live.absolutePath + "-shm"))
                for (sidecar in sidecars) {
                    if (sidecar.exists()) {
                        val moved = sidecar.renameTo(if (sidecar.name.endsWith("-wal")) asideWal else asideShm)
                        if (!moved) {
                            return SnapshotInstallOutcome.Failed(
                                "The existing write-ahead log could not be moved aside, so the brain was " +
                                    "left untouched. Nothing changed.",
                            )
                        }
                    }
                }
                if (!live.renameTo(asideMain)) {
                    // Undo the sidecar moves so the install is a no-op rather than a brain
                    // with a database missing its log.
                    restoreAside(asideWal, File(live.absolutePath + "-wal"))
                    restoreAside(asideShm, File(live.absolutePath + "-shm"))
                    return SnapshotInstallOutcome.Failed(
                        "The existing database could not be moved aside, so the brain was left " +
                            "untouched. Nothing changed.",
                    )
                }
            }

            if (!staged.renameTo(live)) {
                staged.copyTo(live, overwrite = true)
                staged.delete()
            }

            // The restored file must not inherit any sidecar at the live path, because a
            // stale log would replay writes from a different brain over the restored one.
            deleteSidecars(live)

            // Only now is the old brain disposable.
            asideMain.delete()
            asideWal.delete()
            asideShm.delete()

            return SnapshotInstallOutcome.Installed(
                hadExistingBrain = hadLive,
                message = if (hadLive) {
                    "The restored brain is in place and the previous one was removed. GURU must be " +
                        "restarted before it opens the new database."
                } else {
                    "The restored brain is in place on a device that had none. GURU must be restarted " +
                        "before it opens the database."
                },
            )
        } catch (e: Exception) {
            restoreAside(asideMain, live)
            restoreAside(asideWal, File(live.absolutePath + "-wal"))
            restoreAside(asideShm, File(live.absolutePath + "-shm"))
            return SnapshotInstallOutcome.Failed(
                "The restore failed while moving files and the previous brain was put back: " +
                    "${e.message}.",
            )
        }
    }

    private fun restoreAside(aside: File, live: File) {
        if (aside.exists()) {
            runCatching {
                if (live.exists()) live.delete()
                aside.renameTo(live)
            }
        }
    }

    // ------------------------------------------------------------------
    // Archive and seal
    // ------------------------------------------------------------------

    /**
     * The sealed archive's layout: a 4-byte manifest length, the manifest JSON, then
     * the database bytes.
     *
     * Deliberately simple. A zip would need a library, a tar would need a header
     * writer, and both would add a second way for the format to be wrong. One length
     * prefix cannot be misread, and the manifest carries every hash needed to prove
     * the bytes behind it.
     */
    private fun buildArchive(database: ByteArrayFile, manifest: SnapshotManifest): ByteArray {
        val manifestBytes = json.encodeToString(SnapshotManifest.serializer(), manifest).toByteArray()
        val fileBytes = database.readAll()
        val out = java.io.ByteArrayOutputStream(4 + manifestBytes.size + fileBytes.size)
        val data = java.io.DataOutputStream(out)
        data.writeInt(manifestBytes.size)
        data.write(manifestBytes)
        data.write(fileBytes)
        data.flush()
        return out.toByteArray()
    }

    private data class ArchiveEntries(val manifest: SnapshotManifest, val database: ByteArray)

    private fun parseArchive(archive: ByteArray): ArchiveEntries {
        val data = java.io.DataInputStream(archive.inputStream())
        val manifestLength = data.readInt()
        require(manifestLength > 0 && manifestLength <= archive.size - 4) {
            "The manifest length $manifestLength does not fit in ${archive.size} bytes of archive."
        }
        val manifestBytes = ByteArray(manifestLength)
        data.readFully(manifestBytes)
        val manifest = json.decodeFromString(SnapshotManifest.serializer(), String(manifestBytes))
        val database = ByteArray(archive.size - 4 - manifestLength)
        data.readFully(database)
        return ArchiveEntries(manifest, database)
    }

    private fun buildManifest(
        createdAtMillis: Long,
        schemaVersion: Int,
        counts: Map<String, Long>,
        staged: File,
        sourceBytes: Long,
    ): SnapshotManifest {
        val entry = SnapshotFileEntry(
            name = SnapshotFormat.DATABASE_ENTRY,
            sizeBytes = staged.length(),
            sha256 = hashFile(staged),
        )
        return SnapshotManifest(
            createdAtMillis = createdAtMillis,
            databaseSchemaVersion = schemaVersion,
            files = listOf(entry),
            tableRowCounts = counts,
            // An uncountable table records -1, which would poison a plain sum, so only
            // real counts are added and the map itself carries the -1s visibly.
            totalRows = counts.values.filter { it >= 0 }.sum(),
            sourceDatabaseBytes = sourceBytes,
        )
    }

    private fun seal(plaintext: ByteArray, passphrase: CharArray): ByteArray {
        val random = java.security.SecureRandom()
        val salt = ByteArray(SnapshotFormat.SALT_BYTES).also { random.nextBytes(it) }
        val iv = ByteArray(SnapshotFormat.IV_BYTES).also { random.nextBytes(it) }
        val key = SnapshotFormat.deriveKey(passphrase, salt, SnapshotFormat.DEFAULT_ITERATIONS)
        val cipher = Cipher.getInstance(SnapshotFormat.cipherTransformation())
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(SnapshotFormat.TAG_BITS, iv),
        )
        val sealedBody = cipher.doFinal(plaintext)

        val out = java.io.ByteArrayOutputStream(SnapshotFormat.MIN_SEALED_BYTES + sealedBody.size)
        SnapshotFormat.writeHeader(
            out,
            SnapshotFormat.Header(
                version = SnapshotFormat.VERSION,
                iterations = SnapshotFormat.DEFAULT_ITERATIONS,
                salt = salt,
                iv = iv,
            ),
        )
        out.write(sealedBody)
        return out.toByteArray()
    }

    private fun open(sealed: ByteArray, header: SnapshotFormat.Header, passphrase: CharArray): ByteArray {
        val key = SnapshotFormat.deriveKey(passphrase, header.salt, header.iterations)
        val cipher = Cipher.getInstance(SnapshotFormat.cipherTransformation())
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(SnapshotFormat.TAG_BITS, header.iv),
        )
        val headerLength = SnapshotFormat.MIN_SEALED_BYTES - (SnapshotFormat.TAG_BITS / 8)
        return cipher.doFinal(sealed, headerLength, sealed.size - headerLength)
    }

    private fun hashFile(file: File): String = FileInputStream(file).use { SnapshotFormat.sha256(it) }

    private fun currentBuildSchemaVersion(): Int = com.unuslumen.app.database.guruDatabase.SCHEMA_VERSION

    private fun wipeStaging(dir: File) {
        if (!dir.exists()) return
        dir.listFiles()?.forEach { child ->
            if (child.isDirectory) wipeStaging(child) else child.delete()
        }
        dir.delete()
    }

    /** Read [file] fully. Named so the archive builder does not hide a second full read. */
    private class ByteArrayFile(private val file: File) {
        fun readAll(): ByteArray = file.readBytes()
    }

    /** How a rotation decides what to keep. */
    data class RetentionPolicy(val keep: Int) {
        init {
            require(keep >= 1) { "A snapshot retention policy must keep at least one file." }
        }
    }

    /**
     * Delete the oldest snapshots beyond [policy]'s keep count.
     *
     * Only files this engine named are candidates. A folder the owner picked may hold
     * anything, and a cleanup that deletes by "oldest file" rather than "oldest
     * snapshot" is how a person loses their photos to a backup feature.
     */
    fun prune(folder: File, policy: RetentionPolicy): List<File> {
        if (!folder.isDirectory) return emptyList()
        val snapshots = folder.listFiles()
            ?.filter { it.isFile && it.name.startsWith("guru-brain_") && it.name.endsWith(".${SnapshotFormat.FILE_EXTENSION}") }
            ?.sortedBy { it.name }
            ?: return emptyList()

        if (snapshots.size <= policy.keep) return emptyList()
        val doomed = snapshots.dropLast(policy.keep)
        return doomed.filter { it.delete() }
    }

    /** Existing snapshots in a folder, newest first, so a Settings screen can list them. */
    fun list(folder: File): List<File> {
        if (!folder.isDirectory) return emptyList()
        return folder.listFiles()
            ?.filter { it.isFile && it.name.startsWith("guru-brain_") && it.name.endsWith(".${SnapshotFormat.FILE_EXTENSION}") }
            ?.sortedByDescending { it.name }
            ?: emptyList()
    }
}

/** What a snapshot write did. */
sealed class SnapshotWriteOutcome {
    data class Written(
        val file: File,
        val manifest: SnapshotManifest,
        val bytes: Long,
        val sealBytes: Int,
        val message: String,
    ) : SnapshotWriteOutcome()

    data class Failed(val message: String) : SnapshotWriteOutcome()

    val succeeded: Boolean get() = this is Written

    fun message(): String = when (this) {
        is Written -> message
        is Failed -> message
    }
}

/** What an extraction did. */
sealed class SnapshotExtractOutcome {
    data class Extracted(
        val staged: File,
        val manifest: SnapshotManifest,
        val verification: SnapshotVerification,
    ) : SnapshotExtractOutcome()

    data class Refused(val verification: SnapshotVerification) : SnapshotExtractOutcome()
}

/** What an install did. */
sealed class SnapshotInstallOutcome {
    data class Installed(val hadExistingBrain: Boolean, val message: String) : SnapshotInstallOutcome()
    data class Failed(val message: String) : SnapshotInstallOutcome()

    val succeeded: Boolean get() = this is Installed
}
