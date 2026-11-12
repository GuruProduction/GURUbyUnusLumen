// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.tools.backup

import android.content.Context
import com.unuslumen.app.data.tools.registry.ToolDefinition
import com.unuslumen.app.data.tools.registry.ToolExecutionResult
import com.unuslumen.app.data.tools.registry.ToolExecutor
import com.unuslumen.app.data.tools.registry.ToolParameter
import com.unuslumen.app.data.tools.registry.ToolParameterType
import com.unuslumen.app.data.tools.registry.ToolResultData
import com.unuslumen.app.data.tools.registry.ToolResultExtractor
import com.unuslumen.app.data.tools.registry.ToolSetRegistration
import com.unuslumen.app.data.tools.registry.permission.DomainToolModule
import com.unuslumen.app.database.snapshot.DatabaseSnapshotEngine
import com.unuslumen.app.database.snapshot.SnapshotExtractOutcome
import com.unuslumen.app.database.snapshot.SnapshotFormat
import com.unuslumen.app.database.snapshot.SnapshotInstallOutcome
import com.unuslumen.app.database.snapshot.SnapshotManifest
import com.unuslumen.app.database.snapshot.SnapshotVerification
import com.unuslumen.app.database.snapshot.SnapshotVerifier
import com.unuslumen.app.database.snapshot.SnapshotWriteOutcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.koin.core.module.Module
import org.koin.dsl.module
import java.io.File
import java.util.TimeZone
import kotlin.reflect.KClass

/**
 * The brain backup domain: take a snapshot, verify one, restore one.
 *
 * WHY THIS EXISTS AT ALL. The brain is the product. GURU's whole promise is "the same
 * GURU every session, a better GURU every time", and every word of that lives in one
 * SQLite file that had no export path, no import path and no copy that survived a
 * phone upgrade. Losing the phone lost the companion, permanently, with no warning
 * and no recovery.
 *
 * WHY IT IS A TOOL AS WELL AS A SETTINGS SCREEN. Settings is for the owner pressing a
 * button. The tool is for GURU being asked "back yourself up before you do that" in a
 * conversation, and for a job firing on a schedule with nobody present. Both routes
 * reach the same engine, so there is one implementation of the risky part.
 *
 * WHAT THE RESULTS ALWAYS SAY. A snapshot result names the file, its byte count and
 * the row count it verified. A restore result says plainly that a restart is needed,
 * because Room holds the old database open and a restore that claims to have taken
 * effect while the old handle is live is the lie this whole area exists to remove.
 */

@Serializable
data class BrainSnapshotResult(
    val success: Boolean,
    val filePath: String? = null,
    val bytes: Long? = null,
    val tables: Int? = null,
    val totalRows: Long? = null,
    val schemaVersion: Int? = null,
    val prunedFiles: List<String> = emptyList(),
    val message: String? = null,
    val error: String? = null,
) : ToolResultData

@Serializable
data class BrainSnapshotVerifyResult(
    val success: Boolean,
    val usable: Boolean = false,
    /** The exact verification outcome, so a caller can branch without parsing prose. */
    val verdict: String = "UNKNOWN",
    val createdAtMillis: Long? = null,
    val schemaVersion: Int? = null,
    val tables: Int? = null,
    val totalRows: Long? = null,
    val message: String? = null,
) : ToolResultData

@Serializable
data class BrainRestoreResult(
    val success: Boolean,
    val restartRequired: Boolean = false,
    val replacedExistingBrain: Boolean? = null,
    val tables: Int? = null,
    val totalRows: Long? = null,
    val schemaVersion: Int? = null,
    val message: String? = null,
    val error: String? = null,
) : ToolResultData

@Serializable
data class BrainSnapshotListing(
    val path: String,
    val bytes: Long,
    val name: String,
) : ToolResultData

@Serializable
data class BrainSnapshotListResult(
    val success: Boolean,
    val folder: String,
    val snapshots: List<BrainSnapshotListing> = emptyList(),
    val message: String? = null,
    val error: String? = null,
) : ToolResultData

object BrainBackupDomainDefinitions : ToolSetRegistration {
    const val SNAPSHOT_CREATE = "brainSnapshotCreate"
    const val SNAPSHOT_VERIFY = "brainSnapshotVerify"
    const val SNAPSHOT_RESTORE = "brainSnapshotRestore"
    const val SNAPSHOT_LIST = "brainSnapshotList"

    override val definitions = listOf(
        ToolDefinition(
            name = SNAPSHOT_CREATE,
            description = "Write an encrypted, portable snapshot of the whole GURU brain to a folder " +
                "you name, and verify it restores before keeping it. The copy is taken through " +
                "SQLite so pages still in the write-ahead log are included, which a plain file " +
                "copy would silently miss. The passphrase is the only key: it is not stored, it " +
                "cannot be recovered, and a snapshot without it is unreadable forever. Returns the " +
                "file path, its size, and the row count it proved.",
            category = "backup",
            parameters = listOf(
                ToolParameter(
                    "folderPath", ToolParameterType.String, true,
                    "Absolute path to a folder to write the snapshot into. It is created if missing.",
                ),
                ToolParameter(
                    "passphrase", ToolParameterType.String, true,
                    "The passphrase that seals the snapshot. The human must remember it; it is never stored.",
                ),
                ToolParameter(
                    "keep", ToolParameterType.Integer, false,
                    "How many snapshots to keep in that folder, deleting the oldest beyond this. Default 5.",
                ),
            ),
            permissions = emptyList(),
        ),
        ToolDefinition(
            name = SNAPSHOT_VERIFY,
            description = "Check whether a snapshot file can actually be restored, without installing " +
                "it. Opens the seal, checks every file hash against the manifest taken at write " +
                "time, runs SQLite's integrity check, and compares every table's row count. A file " +
                "that is a photo, a truncated download or a snapshot from a newer GURU is named as " +
                "exactly that rather than reported as a wrong password.",
            category = "backup",
            parameters = listOf(
                ToolParameter("filePath", ToolParameterType.String, true, "Absolute path to the snapshot file."),
                ToolParameter("passphrase", ToolParameterType.String, true, "The passphrase it was sealed with."),
            ),
            permissions = emptyList(),
        ),
        ToolDefinition(
            name = SNAPSHOT_RESTORE,
            description = "Replace this device's brain with the contents of a snapshot. The snapshot is " +
                "verified first and refused if anything does not match. The existing database is " +
                "moved aside and only deleted once the restore has completed, so a failure puts the " +
                "previous brain back. GURU MUST BE RESTARTED afterwards, because the running process " +
                "still holds the old database open; the result says so rather than claiming the " +
                "restore is already live.",
            category = "backup",
            parameters = listOf(
                ToolParameter("filePath", ToolParameterType.String, true, "Absolute path to the snapshot file."),
                ToolParameter("passphrase", ToolParameterType.String, true, "The passphrase it was sealed with."),
            ),
            permissions = emptyList(),
        ),
        ToolDefinition(
            name = SNAPSHOT_LIST,
            description = "List the GURU snapshots in a folder, newest first, with their sizes. Only " +
                "files this app named are listed, so a folder holding other things reports only the " +
                "snapshots in it.",
            category = "backup",
            parameters = listOf(
                ToolParameter("folderPath", ToolParameterType.String, true, "Absolute path to a folder to list."),
            ),
            permissions = emptyList(),
        ),
    )

    override fun executorClass(): KClass<out ToolExecutor> = BrainBackupDomainExecutor::class
    override fun extractorClass(): KClass<out ToolResultExtractor>? = null
}

class BrainBackupDomainExecutor(private val context: Context) : ToolExecutor {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val engine = DatabaseSnapshotEngine(context)

    override suspend fun execute(toolName: String, args: Map<String, Any?>): ToolExecutionResult =
        when (toolName) {
            BrainBackupDomainDefinitions.SNAPSHOT_CREATE -> snapshotCreate(args)
            BrainBackupDomainDefinitions.SNAPSHOT_VERIFY -> snapshotVerify(args)
            BrainBackupDomainDefinitions.SNAPSHOT_RESTORE -> snapshotRestore(args)
            BrainBackupDomainDefinitions.SNAPSHOT_LIST -> snapshotList(args)
            else -> ToolExecutionResult.error("Unknown tool: $toolName")
        }

    private suspend fun snapshotCreate(args: Map<String, Any?>): ToolExecutionResult {
        val folderPath = (args["folderPath"] as? String)?.trim()
        if (folderPath.isNullOrBlank()) {
            return failure(
                BrainSnapshotResult.serializer(),
                BrainSnapshotResult(success = false, error = "This call needs 'folderPath' to write into."),
            )
        }
        val passphraseText = args["passphrase"] as? String
        if (passphraseText.isNullOrEmpty()) {
            return failure(
                BrainSnapshotResult.serializer(),
                BrainSnapshotResult(
                    success = false,
                    error = "This call needs a 'passphrase'. Without it the snapshot could never be " +
                        "opened again, so none was written.",
                ),
            )
        }
        if (passphraseText.length < MIN_PASSPHRASE_LENGTH) {
            return failure(
                BrainSnapshotResult.serializer(),
                BrainSnapshotResult(
                    success = false,
                    error = "The passphrase must be at least $MIN_PASSPHRASE_LENGTH characters. This " +
                        "file holds every conversation, note and memory the owner has, and a short " +
                        "passphrase is the only weak point in an otherwise strong seal.",
                ),
            )
        }

        val keep = ((args["keep"] as? Number)?.toInt() ?: DEFAULT_KEEP).coerceIn(1, 100)
        val folder = File(folderPath)
        if (!folder.exists() && !folder.mkdirs()) {
            return failure(
                BrainSnapshotResult.serializer(),
                BrainSnapshotResult(
                    success = false,
                    error = "The folder $folderPath does not exist and could not be created.",
                ),
            )
        }
        if (!folder.isDirectory) {
            return failure(
                BrainSnapshotResult.serializer(),
                BrainSnapshotResult(success = false, error = "$folderPath is a file, not a folder."),
            )
        }

        val destination = File(
            folder,
            SnapshotVerifier.fileNameFor(System.currentTimeMillis(), TimeZone.getDefault()),
        )
        val passphrase = passphraseText.toCharArray()
        val outcome = withContext(Dispatchers.IO) { engine.writeSnapshot(destination, passphrase) }

        return when (outcome) {
            is SnapshotWriteOutcome.Failed -> failure(
                BrainSnapshotResult.serializer(),
                BrainSnapshotResult(success = false, filePath = destination.absolutePath, error = outcome.message),
            )

            is SnapshotWriteOutcome.Written -> {
                val pruned = withContext(Dispatchers.IO) {
                    engine.prune(folder, DatabaseSnapshotEngine.RetentionPolicy(keep))
                }
                val result = BrainSnapshotResult(
                    success = true,
                    filePath = outcome.file.absolutePath,
                    bytes = outcome.bytes,
                    tables = outcome.manifest.tableRowCounts.size,
                    totalRows = outcome.manifest.totalRows,
                    schemaVersion = outcome.manifest.databaseSchemaVersion,
                    prunedFiles = pruned.map { it.name },
                    message = buildString {
                        append(outcome.message)
                        append(' ')
                        append("The passphrase is not stored anywhere: this file cannot be opened without it.")
                        if (pruned.isNotEmpty()) {
                            append(" Removed ${pruned.size} older snapshot(s) beyond the keep limit of $keep.")
                        }
                    },
                )
                success(json, BrainSnapshotResult.serializer(), result)
            }
        }
    }

    private suspend fun snapshotVerify(args: Map<String, Any?>): ToolExecutionResult {
        val file = args["filePath"]?.let { File(it as String) }
            ?: return failure(
                BrainSnapshotVerifyResult.serializer(),
                BrainSnapshotVerifyResult(success = false, message = "This call needs 'filePath'.", verdict = "NO_FILE"),
            )
        val passphrase = (args["passphrase"] as? String)?.toCharArray()
            ?: return failure(
                BrainSnapshotVerifyResult.serializer(),
                BrainSnapshotVerifyResult(
                    success = false,
                    verdict = "NO_PASSPHRASE",
                    message = "This call needs 'passphrase' to open the snapshot.",
                ),
            )

        val verification = withContext(Dispatchers.IO) { engine.verifySnapshots(file, passphrase) }
        val usable = verification as? SnapshotVerification.Usable
        val manifest: SnapshotManifest? = usable?.manifest

        val result = BrainSnapshotVerifyResult(
            success = true,
            usable = verification.isUsable,
            verdict = verdictName(verification),
            createdAtMillis = manifest?.createdAtMillis,
            schemaVersion = manifest?.databaseSchemaVersion,
            tables = manifest?.tableRowCounts?.size,
            totalRows = manifest?.totalRows,
            message = verification.message,
        )
        // A verification that ran to a verdict is a successful call even when the verdict
        // is "this file is unusable". The tool did its job; the file is the bad news, and
        // conflating the two would make every unfavourable answer look like a tool failure.
        return ToolExecutionResult(
            rawJson = json.encodeToString(BrainSnapshotVerifyResult.serializer(), result),
            resultData = result,
            resultObject = null,
            success = true,
            error = null,
        )
    }

    private suspend fun snapshotRestore(args: Map<String, Any?>): ToolExecutionResult {
        val file = args["filePath"]?.let { File(it as String) }
            ?: return failure(
                BrainRestoreResult.serializer(),
                BrainRestoreResult(success = false, error = "This call needs 'filePath'."),
            )
        val passphrase = (args["passphrase"] as? String)?.toCharArray()
            ?: return failure(
                BrainRestoreResult.serializer(),
                BrainRestoreResult(success = false, error = "This call needs 'passphrase' to open the snapshot."),
            )

        // Staged into a cache subdirectory so the extract and the install are two
        // separate decisions: nothing about the live brain is touched until the file has
        // been proven and unpacked.
        val stagingDir = File(context.cacheDir, "brain_restore_staging/${System.currentTimeMillis()}")
        val staged = File(stagingDir, SnapshotFormat.DATABASE_ENTRY)

        val extract = withContext(Dispatchers.IO) { engine.extractTo(file, passphrase, staged) }
        when (extract) {
            is SnapshotExtractOutcome.Refused -> return failure(
                BrainRestoreResult.serializer(),
                BrainRestoreResult(
                    success = false,
                    error = "The snapshot was refused before anything changed: ${extract.verification.message}",
                ),
            )

            is SnapshotExtractOutcome.Extracted -> Unit
        }
        val manifest = (extract as SnapshotExtractOutcome.Extracted).manifest

        val install = withContext(Dispatchers.IO) { engine.installStaged(staged) }
        return when (install) {
            is SnapshotInstallOutcome.Failed -> failure(
                BrainRestoreResult.serializer(),
                BrainRestoreResult(success = false, error = install.message),
            )

            is SnapshotInstallOutcome.Installed -> {
                // The staged copy is now the live brain, so this only cleans the directory
                // the extraction used. A failure to tidy is logged inside the engine and
                // never changes the answer about whether the restore landed.
                withContext(Dispatchers.IO) { stagingDir.deleteRecursively() }
                val result = BrainRestoreResult(
                    success = true,
                    restartRequired = true,
                    replacedExistingBrain = install.hadExistingBrain,
                    tables = manifest.tableRowCounts.size,
                    totalRows = manifest.totalRows,
                    schemaVersion = manifest.databaseSchemaVersion,
                    message = install.message,
                )
                success(json, BrainRestoreResult.serializer(), result)
            }
        }
    }

    private suspend fun snapshotList(args: Map<String, Any?>): ToolExecutionResult {
        val folderPath = (args["folderPath"] as? String)?.trim()
        if (folderPath.isNullOrBlank()) {
            return failure(
                BrainSnapshotListResult.serializer(),
                BrainSnapshotListResult(success = false, folder = "", error = "This call needs 'folderPath'."),
            )
        }
        val folder = File(folderPath)
        val snapshots = withContext(Dispatchers.IO) { engine.list(folder) }
        val result = BrainSnapshotListResult(
            success = true,
            folder = folder.absolutePath,
            snapshots = snapshots.map { BrainSnapshotListing(it.absolutePath, it.length(), it.name) },
            message = if (snapshots.isEmpty()) {
                "No GURU snapshots in $folderPath. A folder holding other files reports nothing rather " +
                    "than listing files this app did not write."
            } else {
                "${snapshots.size} snapshot(s) in $folderPath, newest first."
            },
        )
        return success(json, BrainSnapshotListResult.serializer(), result)
    }

    /** The verdict as a stable token, so a caller can branch on it rather than on prose. */
    private fun verdictName(verification: SnapshotVerification): String = when (verification) {
        is SnapshotVerification.Usable -> "USABLE"
        is SnapshotVerification.NotASnapshot -> "NOT_A_SNAPSHOT"
        is SnapshotVerification.SchemaTooNew -> "SCHEMA_TOO_NEW"
        is SnapshotVerification.CannotOpen -> "CANNOT_OPEN"
        is SnapshotVerification.CorruptContent -> "CORRUPT_CONTENT"
        is SnapshotVerification.DamagedDatabase -> "DAMAGED_DATABASE"
        is SnapshotVerification.CountMismatch -> "COUNT_MISMATCH"
    }

    private fun <T : ToolResultData> success(
        json: Json,
        serializer: kotlinx.serialization.KSerializer<T>,
        payload: T,
    ): ToolExecutionResult = ToolExecutionResult(
        rawJson = json.encodeToString(serializer, payload),
        resultData = payload,
        resultObject = null,
        success = true,
        error = null,
    )

    private fun <T : ToolResultData> failure(
        serializer: kotlinx.serialization.KSerializer<T>,
        payload: T,
    ): ToolExecutionResult = ToolExecutionResult(
        rawJson = json.encodeToString(serializer, payload),
        resultData = payload,
        resultObject = null,
        success = false,
        error = when (payload) {
            is BrainSnapshotResult -> payload.error
            is BrainSnapshotVerifyResult -> payload.message
            is BrainRestoreResult -> payload.error
            is BrainSnapshotListResult -> payload.error
            else -> null
        },
    )

    companion object {
        /** Short enough to type on a phone keyboard, long enough that guessing is hopeless. */
        const val MIN_PASSPHRASE_LENGTH: Int = 8

        const val DEFAULT_KEEP: Int = 5
    }
}

object BrainBackupDomainModule : DomainToolModule {
    override val id: String = "brain_backup"

    override val registrations: List<ToolSetRegistration> = listOf(BrainBackupDomainDefinitions)

    override fun bindings(): Module = module {
        factory { BrainBackupDomainExecutor(get()) }
    }
}
