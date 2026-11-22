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

    /**
     * The live Cerebrum vault, or null when this device has never booted a brain.
     *
     * THE SECOND HALF OF THE APPLICATION'S MEMORY. Room holds the transcript and the tool
     * results; every fact the brain extracted lives in this one file, under
     * `filesDir/cerebrum/state/cerebrum.vault`. The paths are the ones
     * `CerebrumHost.vaultDataDir` and `VAULT_FILE_NAME` in `persistence.rs` define, and
     * they are repeated here rather than imported because `core:database` must not depend
     * on `portal:data`; the constant is asserted against the real file in a test.
     *
     * A device with no vault is not an error: a fresh install has never written one.
     */
    private fun cerebrumVaultFile(): File =
        File(File(File(context.filesDir, CEREBRUM_DIR), CEREBRUM_STATE_DIR), SnapshotFormat.CEREBRUM_VAULT_ENTRY)

    /** Whether a brain has registered its flush, so a caller can report the state rather than guess. */
    fun vaultFlushRegistered(): Boolean = flushVaultBeforeSeal != null

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
     * Take a verified, encrypted snapshot and write it to [destination].
     *
     * A thin wrapper over [sealSnapshot] for callers that have a real filesystem path.
     * Settings hands the owner a Storage Access Framework folder instead, which has no
     * path and is written through a stream, so the artefact is produced by one method and
     * stored by whoever owns the destination. That split is why this class has no
     * knowledge of content resolvers and why the tool and the screen cannot drift.
     */
    suspend fun writeSnapshot(
        destination: File,
        passphrase: CharArray,
        progress: ((String) -> Unit)? = null,
    ): SnapshotWriteOutcome {
        val outcome = sealSnapshot(passphrase, progress)
        if (outcome !is SnapshotWriteOutcome.Written) return outcome

        return try {
            // Written to a sibling first and renamed, so a failure here cannot leave a
            // half-file at the destination that looks like a valid snapshot.
            val temp = File(destination.absolutePath + ".partial")
            temp.parentFile?.mkdirs()
            temp.writeBytes(outcome.sealed)
            if (!temp.renameTo(destination)) {
                temp.copyTo(destination, overwrite = true)
                temp.delete()
            }
            outcome.copy(
                file = destination,
                bytes = destination.length(),
                message = buildString {
                    append("Snapshot written to ${destination.name} (")
                    append("${destination.length()} bytes). ")
                    append(outcome.verification.message)
                },
            )
        } catch (e: Exception) {
            Log.w("guru_backup", "Snapshot could not be stored at ${destination.absolutePath}: ${e.message}")
            SnapshotWriteOutcome.Failed(
                "The snapshot was built and verified but could not be written to " +
                    "${destination.absolutePath}: ${e.message}.",
            )
        }
    }

    /**
     * Build, seal and self-verify a snapshot in memory.
     *
     * The order is deliberate: copy, count, seal, re-open the seal and verify it, and
     * only then hand the bytes back. A snapshot that cannot be opened is worse than no
     * snapshot, because it is discovered at the worst possible moment, so the bytes a
     * caller stores have already been round-tripped through their own reader.
     *
     * The returned [SnapshotWriteOutcome.Written] carries the sealed bytes, which is what
     * a Storage Access Framework destination needs: a tree URI has no path to rename to.
     */
    suspend fun sealSnapshot(
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
            // The manifest is built from the STAGED DATABASE, not from the staging directory.
            // Passing the directory produced a manifest describing a folder, which then failed
            // its own verification on every write. The unit test that asserts a snapshot is
            // taken at all is what caught it, which is why that test exists.
            // THE BRAIN ITSELF, read here and nowhere else. A device that has never booted
            // has no vault, and that is a state the manifest records by omission rather
            // than by an empty entry: an empty vault file and no vault file are different,
            // and restoring an empty one over a real brain would be a silent wipe.
            val vault = cerebrumVaultFile()
            val vaultHasBytes = vault.isFile && vault.length() > 0
            val vaultBytes: ByteArray? = if (vaultHasBytes) {
                /*
                 * FLUSH FIRST, OR REFUSE. A vault is a file the brain rewrites on its own
                 * cadence while the live state sits in memory, so reading it without a flush
                 * captures an older brain and every fact written since the last save is lost
                 * inside a snapshot that verifies perfectly.
                 *
                 * Refusing is the only honest answer when nothing can flush: a snapshot that
                 * omits the newest memories is worse than no snapshot at all, because the person
                 * holding it believes they are covered.
                 */
                val flush = flushVaultBeforeSeal
                    ?: return SnapshotWriteOutcome.Failed(
                        "This device holds a Cerebrum vault but no running brain registered to flush " +
                            "it, so a snapshot taken now could silently omit everything written since " +
                            "the last save. Nothing was written. Start GURU and try again.",
                    )
                progress?.invoke("Flushing the Cerebrum vault so the newest memories are in the file")
                runCatching { flush() }.getOrElse { flushError ->
                    return SnapshotWriteOutcome.Failed(
                        "The Cerebrum brain would not flush its memory to disk, so a snapshot taken now " +
                            "could omit the newest facts. Nothing was written: ${flushError.message}.",
                    )
                }
                // The flush rewrites the file, so the existence and length are re-read rather
                // than trusted from before it ran.
                if (!vault.isFile || vault.length() == 0L) {
                    return SnapshotWriteOutcome.Failed(
                        "The Cerebrum vault disappeared while it was being flushed, so there is no " +
                            "memory to carry. Nothing was written.",
                    )
                }
                progress?.invoke("Reading the Cerebrum vault so the memories travel with the transcript")
                runCatching { vault.readBytes() }.getOrElse { readError ->
                    return SnapshotWriteOutcome.Failed(
                        "The Cerebrum vault could not be read, so a snapshot of the transcript alone " +
                            "would claim to be a backup of the memory. Nothing was written: " +
                            "${readError.message}.",
                    )
                }
            } else {
                null
            }

            val entries = LinkedHashMap<String, ByteArray>(2)
            entries[SnapshotFormat.DATABASE_ENTRY] = staged.readBytes()
            vaultBytes?.let { entries[SnapshotFormat.CEREBRUM_VAULT_ENTRY] = it }

            val manifest = buildManifest(
                createdAtMillis = System.currentTimeMillis(),
                schemaVersion = schemaVersion,
                counts = counts,
                entries = entries,
                sourceBytes = source.length(),
                vaultIncluded = vaultBytes != null,
            )

            val archive = buildArchive(manifest, entries)
            val sealed = seal(archive, passphrase)

            progress?.invoke("Opening the snapshot again to prove it restores")
            val verification = verifySealedBytes(sealed, passphrase, schemaVersion, counts)
            if (!verification.isUsable) {
                return SnapshotWriteOutcome.Failed(
                    "The snapshot was written but failed its own restore check, so it was not kept: " +
                        verification.message,
                )
            }

            return SnapshotWriteOutcome.Written(
                file = null,
                sealed = sealed,
                verification = verification,
                manifest = manifest,
                bytes = sealed.size.toLong(),
                sealBytes = sealed.size,
                message = verification.message,
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
            parseArchive(archive, header.version)
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

        /*
         * THE VAULT IS CHECKED THE SAME WAY THE DATABASE IS, because an unverified second
         * entry is a claim rather than a payload. The three cases are distinct:
         *
         *  - the manifest lists a vault and the archive carries it: verify the hash;
         *  - the manifest lists it and the archive does not: corrupt, not "empty brain";
         *  - neither lists nor carries it: a version 1 snapshot, or a device that never
         *    booted a brain, and both are legitimately vaultless.
         */
        val manifestVaultEntry = manifest.files.firstOrNull {
            it.name == SnapshotFormat.CEREBRUM_VAULT_ENTRY
        }
        val carriedVault = entries.cerebrumVault
        if (manifestVaultEntry != null && carriedVault == null) {
            return SnapshotVerification.CorruptContent(
                "The manifest names a Cerebrum vault but the archive does not carry one, so this " +
                    "snapshot would restore a transcript with no memory behind it.",
            )
        }
        if (manifestVaultEntry != null && carriedVault != null) {
            SnapshotVerifier.verifyFile(
                manifestVaultEntry,
                carriedVault.size.toLong(),
                SnapshotFormat.sha256(carriedVault),
            )?.let { return it }
        }

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
            val entries = parseArchive(archive, headerRead.header.version)
            destination.parentFile?.mkdirs()
            destination.writeBytes(entries.database)
            // A fresh file must not inherit a stale sidecar from a previous attempt at
            // the same path, or the restored brain reads pages from the wrong history.
            deleteSidecars(destination)

            /*
             * THE VAULT COMES OUT BESIDE THE DATABASE, NOT INSTEAD OF IT.
             *
             * It is written into the same staging directory under its own name, so a caller
             * that installs both moves two files from one proven set. It is written as its
             * own SEALED bytes: nothing here decrypts the brain, and no key is derived. The
             * vault's Argon2id/XChaCha20 seal is intact and still needs its passphrase,
             * which means copying it is safe and opening it is not free.
             *
             * A version 1 snapshot has no vault, and the outcome says so through a null
             * path rather than an empty file, because an empty vault installed over a real
             * one is a silent wipe.
             */
            val vaultDestination = entries.cerebrumVault?.let { vaultBytes ->
                val vaultOut = File(destination.parentFile, SnapshotFormat.CEREBRUM_VAULT_ENTRY)
                vaultOut.parentFile?.mkdirs()
                vaultOut.writeBytes(vaultBytes)
                vaultOut
            }

            SnapshotExtractOutcome.Extracted(
                staged = destination,
                manifest = entries.manifest,
                verification = verification,
                stagedCerebrumVault = vaultDestination,
            )
        } catch (e: Exception) {
            SnapshotExtractOutcome.Refused(
                SnapshotVerification.CorruptContent("The snapshot could not be extracted: ${e.message}."),
            )
        }
    }

    /**
     * Put a restored vault in place, moving the live one aside rather than deleting it.
     *
     * THE SAME RULE AS THE DATABASE, and it matters more here, because the vault is the
     * only copy of every fact the brain ever extracted. A restore that failed halfway
     * through replacing it would leave a brain with neither the old memories nor the new
     * ones.
     *
     * The caller must stop the brain first: `CerebrumHost` holds the vault's key and an
     * open handle for the life of the process, so a vault swapped under a running brain is
     * not in effect. Reporting it as restored while the old handle is live would be a lie.
     */
    suspend fun installStagedCerebrumVault(stagedVault: File): SnapshotInstallOutcome {
        if (!stagedVault.isFile || stagedVault.length() == 0L) {
            return SnapshotInstallOutcome.Failed("The staged Cerebrum vault is missing or empty.")
        }
        val live = cerebrumVaultFile()
        val stamp = System.currentTimeMillis()
        val aside = File(live.absolutePath + ".replaced-$stamp")
        val hadLive = live.isFile

        return try {
            live.parentFile?.mkdirs()
            if (hadLive && !live.renameTo(aside)) {
                return SnapshotInstallOutcome.Failed(
                    "The existing vault could not be moved aside, so the brain was left untouched. " +
                        "Nothing changed.",
                )
            }
            if (!stagedVault.renameTo(live)) {
                stagedVault.copyTo(live, overwrite = true)
                stagedVault.delete()
            }
            aside.delete()
            SnapshotInstallOutcome.Installed(
                hadExistingBrain = hadLive,
                message = if (hadLive) {
                    "The restored Cerebrum vault is in place and the previous one was removed. GURU " +
                        "must be restarted before it opens it, and it still needs the passphrase that " +
                        "encrypts it."
                } else {
                    "The restored Cerebrum vault is in place on a device that had none. GURU must be " +
                        "restarted before it opens it."
                },
            )
        } catch (e: Exception) {
            runCatching {
                if (aside.isFile) {
                    if (live.isFile) live.delete()
                    aside.renameTo(live)
                }
            }
            SnapshotInstallOutcome.Failed(
                "The vault restore failed while moving files and the previous vault was put back: " +
                    "${e.message}.",
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
     * The sealed archive's layout, version 2: a 4-byte manifest length, the manifest
     * JSON, then a counted list of named entries.
     *
     * WHY THE ENTRIES ARE COUNTED RATHER THAN CONCATENATED. Version 1 sealed exactly one
     * payload after the manifest, so the reader took everything that was left. That works
     * for one file and cannot express two. A count and a per-entry name and length mean a
     * reader knows what it is holding before it reads it, and a future version can add a
     * third store without guessing where the bytes end.
     */
    private fun buildArchive(
        manifest: SnapshotManifest,
        entries: Map<String, ByteArray>,
    ): ByteArray {
        val manifestBytes = json.encodeToString(SnapshotManifest.serializer(), manifest).toByteArray()
        val out = java.io.ByteArrayOutputStream()
        val data = java.io.DataOutputStream(out)
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

    /**
     * What a sealed archive carried: the manifest and every named entry.
     *
     * `database` is a convenience view of the [SnapshotFormat.DATABASE_ENTRY] member,
     * because the Room database is what almost every caller wants and making them all
     * index a map would be noise. `cerebrumVault` is null for a version 1 file and for a
     * device that had no brain, which are different facts that the caller can tell apart
     * from the manifest.
     */
    private data class ArchiveEntries(
        val manifest: SnapshotManifest,
        val entries: Map<String, ByteArray>,
    ) {
        val database: ByteArray get() = entries.getValue(SnapshotFormat.DATABASE_ENTRY)
        val cerebrumVault: ByteArray? get() = entries[SnapshotFormat.CEREBRUM_VAULT_ENTRY]
    }

    private fun parseArchive(archive: ByteArray, formatVersion: Int): ArchiveEntries {
        val data = java.io.DataInputStream(archive.inputStream())
        val manifestLength = data.readInt()
        require(manifestLength > 0 && manifestLength <= archive.size - 4) {
            "The manifest length $manifestLength does not fit in ${archive.size} bytes of archive."
        }
        val manifestBytes = ByteArray(manifestLength)
        data.readFully(manifestBytes)
        val manifest = json.decodeFromString(SnapshotManifest.serializer(), String(manifestBytes))

        if (formatVersion < 2) {
            /*
             * A VERSION 1 ARCHIVE IS ONE PAYLOAD AND NOTHING ELSE. Everything after the
             * manifest is the database. Read this way rather than refused, so a backup
             * taken before the vault was included still restores: it simply has no brain
             * in it, and the caller is told that by the absence of the entry rather than
             * by a format error.
             */
            val database = ByteArray(archive.size - 4 - manifestLength)
            data.readFully(database)
            return ArchiveEntries(
                manifest = manifest,
                entries = mapOf(SnapshotFormat.DATABASE_ENTRY to database),
            )
        }

        val entryCount = data.readInt()
        require(entryCount in 0..64) { "The archive claims $entryCount entries, which is not a snapshot." }
        val parsed = LinkedHashMap<String, ByteArray>(entryCount)
        repeat(entryCount) {
            val nameLength = data.readInt()
            require(nameLength in 1..256) { "A snapshot entry name is $nameLength bytes long." }
            val nameBytes = ByteArray(nameLength)
            data.readFully(nameBytes)
            val entryLength = data.readInt()
            require(entryLength >= 0 && entryLength <= archive.size) {
                "Snapshot entry ${String(nameBytes)} claims $entryLength bytes, more than the archive holds."
            }
            val body = ByteArray(entryLength)
            data.readFully(body)
            parsed[String(nameBytes, Charsets.UTF_8)] = body
        }
        return ArchiveEntries(manifest = manifest, entries = parsed)
    }

    /**
     * Describe the seal's content.
     *
     * The file's size and hash are taken here, from the file that is about to be sealed, so
     * the manifest cannot describe a different artefact from the one behind it. They are
     * also the values a reader re-derives on restore, which is what makes a swapped file
     * detectable rather than merely suspicious.
     */
    private fun buildManifest(
        createdAtMillis: Long,
        schemaVersion: Int,
        counts: Map<String, Long>,
        entries: Map<String, ByteArray>,
        sourceBytes: Long,
        vaultIncluded: Boolean,
    ): SnapshotManifest {
        // One entry per file actually sealed, hashed from the bytes in hand. Hashing the
        // staged file on disk would describe a file rather than the payload behind the
        // manifest, which is how a manifest ends up describing something else.
        val fileEntries = entries.map { (name, bytes) ->
            SnapshotFileEntry(
                name = name,
                sizeBytes = bytes.size.toLong(),
                sha256 = SnapshotFormat.sha256(bytes),
            )
        }
        return SnapshotManifest(
            createdAtMillis = createdAtMillis,
            databaseSchemaVersion = schemaVersion,
            files = fileEntries,
            tableRowCounts = counts,
            // An uncountable table records -1, which would poison a plain sum, so only
            // real counts are added and the map itself carries the -1s visibly.
            totalRows = counts.values.filter { it >= 0 }.sum(),
            sourceDatabaseBytes = sourceBytes,
            cerebrumVaultIncluded = vaultIncluded,
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

    companion object VaultFlushRegistration {
        /**
         * The vault's directory names, mirroring `CerebrumHost` and `persistence.rs`.
         *
         * Repeated rather than imported because `core:database` sits below `portal:data` in
         * the module graph and must not depend on it. `CerebrumVaultPathTest` asserts that
         * the path this engine reads is the path the host writes, so the copy cannot drift
         * in silence.
         */
        const val CEREBRUM_DIR = "cerebrum"
        const val CEREBRUM_STATE_DIR = "state"

        /**
         * How a snapshot asks the running brain to write its in-memory state to the vault.
         *
         * THE DEFECT THIS EXISTS FOR. Reading `cerebrum.vault` copies whatever the last save
         * wrote. The brain keeps its whole state in memory and flushes on its own cadence, so a
         * snapshot taken between two flushes carries a stale brain: every fact written since the
         * last save is ABSENT, the snapshot verifies perfectly because the file is a valid vault,
         * just an older one, and the loss is found only when somebody restores it and discovers
         * the last hour missing.
         *
         * WHEN A VAULT EXISTS AND NOTHING CAN FLUSH, [sealSnapshot] REFUSES rather than sealing a
         * snapshot that silently omits the newest memories. A person holding that snapshot
         * believes they are covered, which makes it worse than no snapshot at all.
         *
         * `CerebrumHost` registers this when its class is first touched, because it is the only
         * object that holds a brain. The module graph already points that way: `portal:data`
         * depends on `core:database` and not the reverse.
         */
        @Volatile
        var flushVaultBeforeSeal: (() -> Unit)? = null
            private set

        /** Called by the object that owns the brain, at its class-initialisation time. */
        fun registerVaultFlush(flush: () -> Unit) {
            flushVaultBeforeSeal = flush
        }
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

/**
 * What a snapshot write did.
 *
 * [Written.file] is null when the snapshot was sealed but not yet stored, which is the
 * shape a Storage Access Framework destination needs: it has no path, so the caller
 * writes [Written.sealed] through a stream and never sees a File.
 *
 * [Written.verification] is carried rather than reduced to a sentence because the writer
 * refuses to store anything that did not verify, so a Written always means the row counts
 * and hashes already matched. Stating that here means no caller has to re-check.
 */
sealed class SnapshotWriteOutcome {
    /**
     * A plain class rather than a data class because it carries a ByteArray.
     *
     * A generated equals on an array compares references, so two snapshots of identical
     * content would be unequal and two references to one would be equal, which is the
     * wrong answer in both directions. Nothing compares these values, and copy() is
     * written out by hand so the shape stays a value with a readable modifier.
     */
    class Written(
        val file: File?,
        val sealed: ByteArray,
        val verification: SnapshotVerification,
        val manifest: SnapshotManifest,
        val bytes: Long,
        val sealBytes: Int,
        val message: String,
    ) : SnapshotWriteOutcome() {
        fun copy(
            file: File? = this.file,
            sealed: ByteArray = this.sealed,
            verification: SnapshotVerification = this.verification,
            manifest: SnapshotManifest = this.manifest,
            bytes: Long = this.bytes,
            sealBytes: Int = this.sealBytes,
            message: String = this.message,
        ): Written = Written(file, sealed, verification, manifest, bytes, sealBytes, message)
    }

    data class Failed(val message: String) : SnapshotWriteOutcome()

    val succeeded: Boolean get() = this is Written

    fun message(): String = when (this) {
        is Written -> message
        is Failed -> message
    }
}

/** What an extraction did. */
sealed class SnapshotExtractOutcome {
    /**
     * [stagedCerebrumVault] is the vault written beside the database, or null when the
     * snapshot carried none.
     *
     * NULL AND "EMPTY" ARE DIFFERENT. A version 1 snapshot, and a device that never booted
     * a brain, both legitimately have no vault; an empty file would be a vault that holds
     * nothing, and installing one over a real brain would wipe it silently. So absence is
     * expressed as absence.
     */
    data class Extracted(
        val staged: File,
        val manifest: SnapshotManifest,
        val verification: SnapshotVerification,
        val stagedCerebrumVault: File? = null,
    ) : SnapshotExtractOutcome()

    data class Refused(val verification: SnapshotVerification) : SnapshotExtractOutcome()
}

/** What an install did. */
sealed class SnapshotInstallOutcome {
    data class Installed(val hadExistingBrain: Boolean, val message: String) : SnapshotInstallOutcome()
    data class Failed(val message: String) : SnapshotInstallOutcome()

    val succeeded: Boolean get() = this is Installed
}
