// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.unuslumen.app.database.entity.HeartbeatPromptEntity

/**
 * One row, read and written.
 *
 * There is exactly one heartbeat prompt, so there is no list query and no delete:
 * the room can edit it or restore it, and neither of those needs to be able to
 * create a second one.
 */
@Dao
interface HeartbeatPromptDao {

    /** The prompt as written, or null when nothing has been saved yet. */
    @Query("SELECT * FROM heartbeat_prompt WHERE id = :id")
    suspend fun get(id: String = HeartbeatPromptEntity.SINGLETON_ID): HeartbeatPromptEntity?

    /**
     * Write the prompt, replacing the existing row.
     *
     * REPLACE rather than IGNORE because an edit is the entire point. The fixed id
     * means this can only ever touch the one row.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(prompt: HeartbeatPromptEntity)

    /** Return to whatever shipped, by removing the owner's edit. */
    @Query("DELETE FROM heartbeat_prompt WHERE id = :id")
    suspend fun clear(id: String = HeartbeatPromptEntity.SINGLETON_ID)
}
