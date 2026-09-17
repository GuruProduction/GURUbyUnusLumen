package com.unuslumen.app.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.unuslumen.app.database.entity.LuxifyEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface LuxifyDao {

    @Query("SELECT * FROM luxify_skills ORDER BY name ASC")
    suspend fun getAllLuxify(): List<LuxifyEntity>

    @Query("SELECT * FROM luxify_skills ORDER BY name ASC")
    fun getAllLuxifyFlow(): Flow<List<LuxifyEntity>>

    @Query("SELECT * FROM luxify_skills WHERE enabled = 1 ORDER BY name ASC")
    suspend fun getEnabledLuxify(): List<LuxifyEntity>

    @Query("SELECT * FROM luxify_skills WHERE enabled = 1 ORDER BY name ASC")
    fun getEnabledLuxifyFlow(): Flow<List<LuxifyEntity>>

    @Query("SELECT * FROM luxify_skills WHERE source = :source ORDER BY name ASC")
    suspend fun getLuxifyBySource(source: String): List<LuxifyEntity>

    @Query("SELECT * FROM luxify_skills WHERE id = :id")
    suspend fun getLuxifyById(id: String): LuxifyEntity?

    @Query("SELECT * FROM luxify_skills WHERE name = :name")
    suspend fun getLuxifyByName(name: String): LuxifyEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLuxify(skill: LuxifyEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLuxifyList(skills: List<LuxifyEntity>)

    @Update
    suspend fun updateLuxify(skill: LuxifyEntity)

    @Query("UPDATE luxify_skills SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: String, enabled: Boolean)

    @Query("DELETE FROM luxify_skills WHERE id = :id")
    suspend fun deleteLuxify(id: String)

    @Query("DELETE FROM luxify_skills WHERE source = :source")
    suspend fun deleteLuxifyBySource(source: String)

    @Query("SELECT COUNT(*) FROM luxify_skills")
    suspend fun getCount(): Int

    @Query("SELECT COUNT(*) FROM luxify_skills WHERE enabled = 1")
    suspend fun getEnabledCount(): Int

    @Query("SELECT COUNT(*) FROM luxify_skills WHERE source = :source")
    suspend fun getCountBySource(source: String): Int
}