// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.unuslumen.app.database.entity.ConversationThreadEntity

@Dao
interface ConversationThreadDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertThread(thread: ConversationThreadEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertThreads(threads: List<ConversationThreadEntity>)

    @Query("SELECT * FROM conversation_threads ORDER BY created_date DESC")
    suspend fun getAllThreads(): List<ConversationThreadEntity>

    @Query("SELECT * FROM conversation_threads WHERE id = :threadId")
    suspend fun getThread(threadId: String): ConversationThreadEntity?

    @Query("DELETE FROM conversation_threads WHERE id = :threadId")
    suspend fun deleteThread(threadId: String)

    @Query("SELECT COUNT(*) FROM conversation_threads")
    suspend fun getThreadCount(): Int
}
