// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.use_case

import android.content.Context
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import com.unuslumen.app.database.snapshot.DatabaseSnapshotEngine
import com.unuslumen.app.database.snapshot.SnapshotExtractOutcome
import com.unuslumen.app.database.snapshot.SnapshotFormat
import com.unuslumen.app.database.snapshot.SnapshotInstallOutcome
import com.unuslumen.app.database.snapshot.SnapshotVerification
import com.unuslumen.app.database.snapshot.SnapshotVerifier
import com.unuslumen.app.database.snapshot.SnapshotWriteOutcome
import com.unuslumen.app.domain.model.BrainRestoreOutcome
import com.unuslumen.app.domain.model.BrainSnapshotCheck
import com.unuslumen.app.domain.model.BrainSnapshotOutcome
import com.unuslumen.app.domain.model.BrainSnapshotVerdict
import com.unuslumen.app.domain.use_case.CheckBrainSnapshotUseCase
import com.unuslumen.app.domain.use_case.CreateBrainSnapshotUseCase
import com.unuslumen.app.domain.use_case.RestoreBrainSnapshotUseCase
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.koin.core.annotation.Factory
import org.koin.core.annotation.Named
import java.io.File
import java.io.InputStream
import java.util.TimeZone

/**
 * The Settings screen's route into the snapshot engine.
 *
 * THE STORAGE ACCESS FRAMEWORK IS THE HARD PART HERE, NOT THE CRYPTO.
 *
 * The owner picks a folder through a picker, and what comes back is a tree URI with no
 * filesystem path. The engine works in File terms because that is what SQLite needs, so
 * the two meet in these classes: the engine produces sealed bytes, and these classes move
 * them through the resolver into a document the picked folder owns.
 *
 * The same asymmetry runs the other way on restore. A picked file is a document URI with
 * no path, so its bytes are copied into a cache file and the engine is handed that file.
 * The copy is deleted the moment the operation finishes, because a snapshot's plaintext
 * database must never outlive the call that unpacked it.
 *
 * THREE CLASSES RATHER THAN ONE. Checking and restoring take the same two arguments, so a
 * single class implementing both would declare `invoke` twice with one signature and not
 * compile. They are also genuinely separate decisions: a check changes nothing, and a
 * restore replaces the brain. Two classes make that difference visible at the type level.
 */

@Factory
class CreateBrainSnapshotUseCaseImpl(
    private val context: Context,
    private val engine: DatabaseSnapshotEngine,
    @Named("ioDispatcher") private val ioDispatcher: CoroutineDispatcher,
) : CreateBrainSnapshotUseCase {

    override suspend fun invoke(
        folderUri: String,
        passphrase: String,
        keep: Int,
    ): BrainSnapshotOutcome = withContext(ioDispatcher) {
        val pickedDir = DocumentFile.fromTreeUri(context, folderUri.toUri())
            ?: return@withContext BrainSnapshotOutcome(
                succeeded = false,
                fileName = null,
                bytes = 0,
                tables = 0,
                totalRows = 0,
                schemaVersion = 0,
                prunedCount = 0,
                message = "That folder could not be opened. Pick it again.",
            )

        val sealed = engine.sealSnapshot(passphrase.toCharArray())
        if (sealed !is SnapshotWriteOutcome.Written) {
            return@withContext BrainSnapshotOutcome(
                succeeded = false,
                fileName = null,
                bytes = 0,
                tables = 0,
                totalRows = 0,
                schemaVersion = 0,
                prunedCount = 0,
                message = (sealed as SnapshotWriteOutcome.Failed).message,
            )
        }

        val name = SnapshotVerifier.fileNameFor(System.currentTimeMillis(), TimeZone.getDefault())

        // Written to a document only after the engine has proved the bytes restore, so a
        // folder can never hold a snapshot that was never verified.
        val document = pickedDir.createFile(SNAPSHOT_MIME_TYPE, name)
            ?: return@withContext BrainSnapshotOutcome(
                succeeded = false,
                fileName = name,
                bytes = 0,
                tables = 0,
                totalRows = 0,
                schemaVersion = 0,
                prunedCount = 0,
                message = "The folder refused a new file called $name. Check that GURU still has " +
                    "access to it.",
            )

        try {
            val stream = context.contentResolver.openOutputStream(document.uri)
                ?: return@withContext BrainSnapshotOutcome(
                    succeeded = false,
                    fileName = name,
                    bytes = 0,
                    tables = 0,
                    totalRows = 0,
                    schemaVersion = 0,
                    prunedCount = 0,
                    message = "The folder accepted a file but would not open it for writing.",
                )
            stream.use { it.write(sealed.sealed) }
        } catch (e: Exception) {
            // A partial document is removed rather than left behind: a truncated snapshot
            // in a folder reads as a valid backup until the day it is needed.
            runCatching { document.delete() }
            return@withContext BrainSnapshotOutcome(
                succeeded = false,
                fileName = name,
                bytes = 0,
                tables = 0,
                totalRows = 0,
                schemaVersion = 0,
                prunedCount = 0,
                message = "The snapshot could not be written to the folder: ${e.message}.",
            )
        }

        val pruned = pruneFolder(pickedDir, keep)

        BrainSnapshotOutcome(
            succeeded = true,
            fileName = name,
            bytes = sealed.sealed.size.toLong(),
            tables = sealed.manifest.tableRowCounts.size,
            totalRows = sealed.manifest.totalRows,
            schemaVersion = sealed.manifest.databaseSchemaVersion,
            prunedCount = pruned,
            message = sealed.verification.message,
        )
    }

    /**
     * Delete older snapshots beyond [keep], by name.
     *
     * Names sort chronologically because the writer formats them as a timestamp, so the
     * oldest are the ones at the front of a sorted list. Only files this app named are
     * candidates: the owner's folder may hold anything, and a cleanup that deleted the
     * oldest file in a folder would eventually delete something that matters.
     */
    private fun pruneFolder(folder: DocumentFile, keep: Int): Int {
        val snapshots = folder.listFiles()
            .filter { it.isFile && it.name.orEmpty().endsWith(".${SnapshotFormat.FILE_EXTENSION}") }
            .filter { it.name.orEmpty().startsWith("guru-brain_") }
            .sortedBy { it.name.orEmpty() }

        val excess = snapshots.size - keep.coerceAtLeast(1)
        if (excess <= 0) return 0

        var deleted = 0
        for (document in snapshots.take(excess)) {
            if (document.delete()) deleted++
        }
        return deleted
    }

    companion object {
        /**
         * A snapshot is not JSON or an image, and a picker filters on MIME type, so a
         * generic binary type keeps the file selectable everywhere without pretending to be
         * something it is not.
         */
        const val SNAPSHOT_MIME_TYPE: String = "application/octet-stream"
    }
}

@Factory
class CheckBrainSnapshotUseCaseImpl(
    private val context: Context,
    private val engine: DatabaseSnapshotEngine,
    @Named("ioDispatcher") private val ioDispatcher: CoroutineDispatcher,
) : CheckBrainSnapshotUseCase {

    override suspend fun invoke(fileUri: String, passphrase: String): BrainSnapshotCheck =
        withContext(ioDispatcher) {
            val staged = stagePickedFile(context, fileUri)
                ?: return@withContext BrainSnapshotCheck(
                    verdict = BrainSnapshotVerdict.NOT_A_SNAPSHOT,
                    usable = false,
                    createdAtMillis = null,
                    tables = 0,
                    totalRows = 0,
                    schemaVersion = 0,
                    message = "That file could not be opened. Pick it again.",
                )

            try {
                engine.verifySnapshots(staged, passphrase.toCharArray()).toCheck()
            } finally {
                staged.delete()
            }
        }

    private fun SnapshotVerification.toCheck(): BrainSnapshotCheck {
        val usable = this as? SnapshotVerification.Usable
        return BrainSnapshotCheck(
            verdict = when (this) {
                is SnapshotVerification.Usable -> BrainSnapshotVerdict.USABLE
                is SnapshotVerification.NotASnapshot -> BrainSnapshotVerdict.NOT_A_SNAPSHOT
                is SnapshotVerification.SchemaTooNew -> BrainSnapshotVerdict.SCHEMA_TOO_NEW
                is SnapshotVerification.CannotOpen -> BrainSnapshotVerdict.CANNOT_OPEN
                is SnapshotVerification.CorruptContent -> BrainSnapshotVerdict.CORRUPT_CONTENT
                is SnapshotVerification.DamagedDatabase -> BrainSnapshotVerdict.DAMAGED_DATABASE
                is SnapshotVerification.CountMismatch -> BrainSnapshotVerdict.COUNT_MISMATCH
            },
            usable = isUsable,
            createdAtMillis = usable?.manifest?.createdAtMillis,
            tables = usable?.manifest?.tableRowCounts?.size ?: 0,
            totalRows = usable?.manifest?.totalRows ?: 0,
            schemaVersion = usable?.manifest?.databaseSchemaVersion ?: 0,
            message = message,
        )
    }
}

@Factory
class RestoreBrainSnapshotUseCaseImpl(
    private val context: Context,
    private val engine: DatabaseSnapshotEngine,
    @Named("ioDispatcher") private val ioDispatcher: CoroutineDispatcher,
) : RestoreBrainSnapshotUseCase {

    override suspend fun invoke(fileUri: String, passphrase: String): BrainRestoreOutcome =
        withContext(ioDispatcher) {
            val staged = stagePickedFile(context, fileUri)
                ?: return@withContext refused("That file could not be opened. Pick it again.")

            val unpackDir = File(context.cacheDir, "brain_restore/${System.currentTimeMillis()}")
            val unpacked = File(unpackDir, SnapshotFormat.DATABASE_ENTRY)
            try {
                val manifest = when (val extract = engine.extractTo(staged, passphrase.toCharArray(), unpacked)) {
                    // Refused before anything was touched, so the live brain is intact and
                    // the message says why the file could not be used.
                    is SnapshotExtractOutcome.Refused ->
                        return@withContext refused("Nothing was changed. ${extract.verification.message}")

                    is SnapshotExtractOutcome.Extracted -> extract.manifest
                }

                when (val install = engine.installStaged(unpacked)) {
                    is SnapshotInstallOutcome.Failed -> refused(install.message)

                    is SnapshotInstallOutcome.Installed -> BrainRestoreOutcome(
                        succeeded = true,
                        restartRequired = true,
                        replacedExistingBrain = install.hadExistingBrain,
                        tables = manifest.tableRowCounts.size,
                        totalRows = manifest.totalRows,
                        schemaVersion = manifest.databaseSchemaVersion,
                        message = install.message,
                    )
                }
            } finally {
                // Both the picked copy and the unpacked database are plaintext, so neither
                // outlives this call. The installed database is at the live path, not here.
                runCatching { unpacked.delete() }
                runCatching { unpackDir.deleteRecursively() }
                staged.delete()
            }
        }

    private fun refused(message: String) = BrainRestoreOutcome(
        succeeded = false,
        restartRequired = false,
        replacedExistingBrain = false,
        tables = 0,
        totalRows = 0,
        schemaVersion = 0,
        message = message,
    )
}

/**
 * Copy a picked document into a cache file.
 *
 * The engine needs a path and a document URI has none. Each caller deletes the copy, so an
 * aborted check cannot leave a snapshot's contents in the cache directory. Shared between
 * the two classes that read a picked file because both need exactly this and a second copy
 * of it would be a second place to forget the delete.
 */
internal fun stagePickedFile(context: Context, fileUri: String): File? {
    return try {
        val input: InputStream = context.contentResolver.openInputStream(fileUri.toUri())
            ?: return null
        val dir = File(context.cacheDir, "brain_snapshot_read")
        dir.mkdirs()
        val staged = File(dir, "picked-${System.currentTimeMillis()}.${SnapshotFormat.FILE_EXTENSION}")
        input.use { source -> staged.outputStream().use { source.copyTo(it) } }
        staged.takeIf { it.exists() && it.length() > 0 }
    } catch (e: Exception) {
        null
    }
}
