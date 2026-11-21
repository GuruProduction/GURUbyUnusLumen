// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.unuslumen.app.database.entity.PromptRowEntity
import com.unuslumen.app.database.entity.PromptRowVersionEntity
import kotlinx.coroutines.flow.Flow

/**
 * Prompt rows and their history.
 *
 * THE GUARDED UPDATE IS THE POINT OF THIS FILE. `updateIfVersionMatches` carries the base
 * version in its WHERE clause, so a write built on a version that has since moved on
 * changes zero rows rather than overwriting text nobody read. The caller reads the count
 * and refuses; putting that decision in SQL rather than in Kotlin is what stops two
 * writers both passing a check they each made before the other wrote.
 *
 * NOTHING HERE DELETES A USER-AUTHORED ROW. [deleteRow] exists for a deliberate removal
 * and nothing in the tool surface calls it. A sync that finds a row withdrawn on the
 * server sets `is_active` false instead, because the local text is the owner's.
 */
@Dao
interface PromptRowDao {

    // ---------------- rows ----------------

    @Query("SELECT * FROM prompt_rows ORDER BY category ASC, name ASC")
    suspend fun allRows(): List<PromptRowEntity>

    @Query("SELECT * FROM prompt_rows ORDER BY category ASC, name ASC")
    fun observeRows(): Flow<List<PromptRowEntity>>

    @Query("SELECT * FROM prompt_rows WHERE is_active = 1 ORDER BY category ASC, name ASC")
    suspend fun activeRows(): List<PromptRowEntity>

    @Query("SELECT * FROM prompt_rows WHERE id = :id")
    suspend fun rowById(id: String): PromptRowEntity?

    @Query("SELECT * FROM prompt_rows WHERE slug = :slug")
    suspend fun rowBySlug(slug: String): PromptRowEntity?

    /** The cheap sync check: which slugs this device holds, and at what version. */
    @Query("SELECT slug, version, synced_version FROM prompt_rows")
    suspend fun slugVersions(): List<PromptSlugVersion>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putRow(row: PromptRowEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putRows(rows: List<PromptRowEntity>)

    @Update
    suspend fun updateRow(row: PromptRowEntity)

    /**
     * Update a row only when it is still at the version the caller read.
     *
     * RETURNS THE ROW COUNT, WHICH IS HOW A CALLER LEARNS ITS WRITE WAS REFUSED. Zero is
     * not an error and never throws: it means the text moved on since it was read.
     */
    @Query(
        """
        UPDATE prompt_rows
        SET content = :content, name = :name, version = :newVersion,
            is_edited = :isEdited, updated_at = :now
        WHERE id = :id AND version = :baseVersion
        """
    )
    suspend fun updateIfVersionMatches(
        id: String,
        baseVersion: Int,
        newVersion: Int,
        content: String,
        name: String,
        isEdited: Boolean,
        now: Long,
    ): Int

    /** Mark a row withdrawn without touching its text, which belongs to whoever wrote it. */
    @Query("UPDATE prompt_rows SET is_active = :isActive, updated_at = :now WHERE slug = :slug")
    suspend fun setActive(slug: String, isActive: Boolean, now: Long): Int

    /** Record that a sync brought this row to a version, without touching its text. */
    @Query("UPDATE prompt_rows SET synced_version = :syncedVersion, updated_at = :now WHERE id = :id")
    suspend fun setSyncedVersion(id: String, syncedVersion: Int, now: Long): Int

    /**
     * Delete a row. Its version history goes with it through the foreign key's cascade,
     * so this is one statement rather than two that can leave a version pointing at nothing.
     */
    @Query("DELETE FROM prompt_rows WHERE id = :id")
    suspend fun deleteRow(id: String)

    @Query("SELECT COUNT(*) FROM prompt_rows")
    suspend fun count(): Int

    // ---------------- versions ----------------

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun putVersion(version: PromptRowVersionEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun putVersionIgnoringDuplicate(version: PromptRowVersionEntity): Long

    @Query("SELECT * FROM prompt_row_versions WHERE row_id = :rowId ORDER BY version DESC")
    suspend fun versionsFor(rowId: String): List<PromptRowVersionEntity>

    @Query("SELECT COUNT(*) FROM prompt_row_versions WHERE row_id = :rowId")
    suspend fun versionCount(rowId: String): Int

    @Query("SELECT MAX(version) FROM prompt_row_versions WHERE row_id = :rowId")
    suspend fun highestVersion(rowId: String): Int?

    @Query("SELECT * FROM prompt_row_versions WHERE row_id = :rowId AND version = :version")
    suspend fun versionAt(rowId: String, version: Int): PromptRowVersionEntity?
}

/**
 * The three columns a sync needs to decide what moved, without loading any text.
 *
 * NOT AN ENTITY AND NOT A ROW: it is the projection Room builds from the query above, so
 * a sync pass reads three small columns per row rather than every prompt on the device.
 */
data class PromptSlugVersion(
    val slug: String,
    val version: Int,
    @androidx.room.ColumnInfo(name = "synced_version") val syncedVersion: Int,
)
