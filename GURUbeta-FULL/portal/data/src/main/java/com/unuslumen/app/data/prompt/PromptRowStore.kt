// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.prompt

import androidx.room.withTransaction
import com.unuslumen.app.database.dao.PromptRowDao
import com.unuslumen.app.database.entity.PromptRowEntity
import com.unuslumen.app.database.entity.PromptRowVersionEntity
import com.unuslumen.app.database.guruDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import org.koin.core.annotation.Single

/**
 * The prompt stack as rows, with a version guard on every write.
 *
 * WHY THIS IS A STORE AND NOT A TOOL. The tools are five thin verbs over this. Every rule
 * that matters lives here, so the same rules apply to a local edit, to a sync from the
 * server, and to a reset: what a version means, what happens to the old text, and when a
 * write is refused because the row moved on.
 *
 * THE GUARD IS THE DIFFERENCE BETWEEN AN EDITOR AND A CLOBBER. `updatePromptRow` takes the
 * version the caller read. If the row is still at that version the write lands and the new
 * version is one higher; if it is not, the write changes nothing and the caller is told the
 * row moved. Without that, two edits built on the same read produce the second one silently
 * winning, and the first person's words are simply gone.
 *
 * EVERY WRITE KEEPS THE OLD TEXT. Before a row's content changes, its current text is
 * inserted into history under its current version; the new text then becomes the new
 * version. That is the server's own rule, and it is the reason a bad edit is recoverable
 * rather than permanent.
 */
@OptIn(ExperimentalUuidApi::class)
@Single
class PromptRowStore(
    private val database: guruDatabase,
    private val dao: PromptRowDao,
) {

    /** What an edit produced. A refusal is a value, because it is an ordinary outcome. */
    sealed interface WriteOutcome {
        data class Applied(
            val slug: String,
            val version: Int,
            val previousVersion: Int,
        ) : WriteOutcome

        /** The row is at a different version than the caller read. Nothing was written. */
        data class Stale(
            val slug: String,
            val expectedVersion: Int,
            val actualVersion: Int,
        ) : WriteOutcome

        data class Missing(val slug: String) : WriteOutcome
    }

    suspend fun all(): List<PromptRowEntity> = withContext(Dispatchers.IO) { dao.allRows() }

    suspend fun active(): List<PromptRowEntity> = withContext(Dispatchers.IO) { dao.activeRows() }

    suspend fun bySlug(slug: String): PromptRowEntity? = withContext(Dispatchers.IO) { dao.rowBySlug(slug) }

    suspend fun historyDepth(rowId: String): Int = withContext(Dispatchers.IO) { dao.versionCount(rowId) }

    suspend fun history(rowId: String): List<PromptRowVersionEntity> =
        withContext(Dispatchers.IO) { dao.versionsFor(rowId) }

    suspend fun slugVersions() = withContext(Dispatchers.IO) { dao.slugVersions() }

    /**
     * Replace one row's text, guarded by the version the caller read.
     *
     * THE HISTORY WRITE AND THE UPDATE ARE ONE TRANSACTION. A history row committed for a
     * write that then fails would claim a version that never existed, and an update that
     * landed without its history would lose the text it replaced.
     */
    suspend fun updateContent(
        slug: String,
        expectedVersion: Int,
        newContent: String,
        newName: String? = null,
        writtenBy: String = PromptRowVersionEntity.WRITTEN_BY_DEVICE,
        now: Long = System.currentTimeMillis(),
    ): WriteOutcome = withContext(Dispatchers.IO) {
        database.withTransaction {
            val row = dao.rowBySlug(slug)
                ?: return@withTransaction WriteOutcome.Missing(slug)

            if (row.version != expectedVersion) {
                // NOTHING IS WRITTEN. Not the history, not the row. A stale attempt leaves
                // no trace, because a trace of a write that did not happen is worse than none.
                return@withTransaction WriteOutcome.Stale(
                    slug = slug,
                    expectedVersion = expectedVersion,
                    actualVersion = row.version,
                )
            }

            val nextVersion = row.version + 1
            val name = newName?.trim()?.takeIf { it.isNotEmpty() } ?: row.name

            // The text being replaced goes into history under the version it was in force as.
            dao.putVersion(
                PromptRowVersionEntity(
                    id = Uuid.random().toString(),
                    rowId = row.id,
                    version = row.version,
                    name = row.name,
                    content = row.content,
                    writtenBy = writtenBy,
                    createdAt = now,
                )
            )

            val changed = dao.updateIfVersionMatches(
                id = row.id,
                baseVersion = row.version,
                newVersion = nextVersion,
                content = newContent,
                name = name,
                isEdited = writtenBy != PromptRowVersionEntity.WRITTEN_BY_SERVER,
                now = now,
            )
            if (changed != 1) {
                // The guard refused between the read and the write. Roll the history back by
                // throwing, so the transaction undoes it rather than keeping a version row
                // for a change that never landed.
                error("the prompt row moved while it was being written")
            }
            WriteOutcome.Applied(slug = slug, version = nextVersion, previousVersion = row.version)
        }
    }

    /**
     * Return a row to its synced text, keeping the override in history first.
     *
     * A RESET IS A WRITE THAT MUST NOT LOSE WHAT IT REPLACES. The owner's text goes into
     * history marked as a reset before the row returns to the version the server sent, so
     * a reset that was a mistake is recoverable rather than the one action in the room that
     * destroys work.
     */
    suspend fun resetToSynced(
        slug: String,
        expectedVersion: Int,
        now: Long = System.currentTimeMillis(),
    ): WriteOutcome = withContext(Dispatchers.IO) {
        database.withTransaction {
            val row = dao.rowBySlug(slug) ?: return@withTransaction WriteOutcome.Missing(slug)
            if (row.version != expectedVersion) {
                return@withTransaction WriteOutcome.Stale(slug, expectedVersion, row.version)
            }
            if (!row.isEdited) {
                // Nothing to reset. Reported as applied at the version it already holds, so a
                // caller that pressed reset on an untouched row is not told it failed.
                return@withTransaction WriteOutcome.Applied(slug, row.version, row.version)
            }
            val syncedText = dao.versionAt(row.id, row.syncedVersion)
                ?: return@withTransaction WriteOutcome.Missing(slug)

            dao.putVersion(
                PromptRowVersionEntity(
                    id = Uuid.random().toString(),
                    rowId = row.id,
                    version = row.version,
                    name = row.name,
                    content = row.content,
                    writtenBy = PromptRowVersionEntity.WRITTEN_BY_RESET,
                    createdAt = now,
                )
            )

            val nextVersion = row.version + 1
            val changed = dao.updateIfVersionMatches(
                id = row.id,
                baseVersion = row.version,
                newVersion = nextVersion,
                content = syncedText.content,
                name = syncedText.name,
                isEdited = false,
                now = now,
            )
            if (changed != 1) error("the prompt row moved while it was being reset")
            WriteOutcome.Applied(slug, nextVersion, row.version)
        }
    }

    /**
     * Write a row from the server.
     *
     * A ROW THE OWNER HAS EDITED IS NEVER OVERWRITTEN. The local text is the owner's and the
     * server's copy is the one that has fallen behind their intent. A row the server has
     * withdrawn is marked inactive rather than deleted, for the same reason: the text stays,
     * and the withdrawal is visible as a state rather than as an absence.
     */
    suspend fun applyFromServer(
        slug: String,
        id: String,
        category: String,
        name: String,
        content: String,
        deliveryMode: String,
        triggerKeywordsJson: String,
        matchThreshold: Double?,
        globalThreshold: Double,
        version: Int,
        isActive: Boolean,
        now: Long = System.currentTimeMillis(),
    ): Boolean = withContext(Dispatchers.IO) {
        database.withTransaction {
            val existing = dao.rowBySlug(slug)
            if (existing == null) {
                dao.putRow(
                    PromptRowEntity(
                        id = id,
                        slug = slug,
                        category = category,
                        name = name,
                        content = content,
                        deliveryMode = deliveryMode,
                        triggerKeywordsJson = triggerKeywordsJson,
                        matchThreshold = matchThreshold,
                        globalThreshold = globalThreshold,
                        isActive = isActive,
                        version = version,
                        createdAt = now,
                        updatedAt = now,
                        isEdited = false,
                        syncedVersion = version,
                    )
                )
                dao.putVersion(
                    PromptRowVersionEntity(
                        id = Uuid.random().toString(),
                        rowId = id,
                        version = version,
                        name = name,
                        content = content,
                        writtenBy = PromptRowVersionEntity.WRITTEN_BY_SERVER,
                        createdAt = now,
                    )
                )
                return@withTransaction true
            }

            // The owner's words outrank the server's, and a withdrawal is still recorded.
            if (existing.isEdited) {
                if (existing.isActive != isActive) {
                    dao.setActive(slug, isActive, now)
                }
                return@withTransaction false
            }

            /*
             * TOLERANT OF A VERSION THAT IS ALREADY IN HISTORY, AND THAT IS CORRECT HERE.
             *
             * Creating a row records its first version, so by the time a sync replaces that
             * version the history already holds it. Re-inserting the same fact would fail the
             * unique index and abort a sync that is doing exactly what it should. Ignoring the
             * duplicate keeps the existing history row, which already says what that version
             * said, so nothing is lost and nothing is claimed twice.
             */
            dao.putVersionIgnoringDuplicate(
                PromptRowVersionEntity(
                    id = Uuid.random().toString(),
                    rowId = existing.id,
                    version = existing.version,
                    name = existing.name,
                    content = existing.content,
                    writtenBy = PromptRowVersionEntity.WRITTEN_BY_SERVER,
                    createdAt = now,
                )
            )
            dao.putRow(
                existing.copy(
                    category = category,
                    name = name,
                    content = content,
                    deliveryMode = deliveryMode,
                    triggerKeywordsJson = triggerKeywordsJson,
                    matchThreshold = matchThreshold,
                    globalThreshold = globalThreshold,
                    version = version,
                    isActive = isActive,
                    updatedAt = now,
                    syncedVersion = version,
                )
            )
            true
        }
    }
}
