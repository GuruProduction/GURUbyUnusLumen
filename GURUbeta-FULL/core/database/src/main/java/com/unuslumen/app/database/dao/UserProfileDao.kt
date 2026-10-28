// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.unuslumen.app.database.entity.UserProfileEntity
import kotlinx.coroutines.flow.Flow

/**
 * UserProfileDao — one row, upsert, read by the fixed singleton id.
 *
 * The flow getter exists so a future settings screen can show the profile
 * live while the human edits it. The suspend getter is what the send path
 * uses, because it runs inside a coroutine and must never block on a flow.
 */
@Dao
interface UserProfileDao {

    @Query("SELECT * FROM guru_user_profile WHERE id = :id LIMIT 1")
    suspend fun getProfile(id: String): UserProfileEntity?

    @Query("SELECT * FROM guru_user_profile WHERE id = :id LIMIT 1")
    fun getProfileFlow(id: String): Flow<UserProfileEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(profile: UserProfileEntity)

    @Query("DELETE FROM guru_user_profile WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("SELECT COUNT(*) FROM guru_user_profile")
    suspend fun count(): Int
}
