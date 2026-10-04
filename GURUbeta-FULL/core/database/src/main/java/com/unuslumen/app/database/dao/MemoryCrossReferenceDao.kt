// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.unuslumen.app.database.entity.MemoryCrossReferenceEntity

@Dao
interface MemoryCrossReferenceDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCrossReference(ref: MemoryCrossReferenceEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCrossReferences(refs: List<MemoryCrossReferenceEntity>)

    @Query("SELECT * FROM memory_cross_references WHERE fact_id_a = :factId OR fact_id_b = :factId")
    suspend fun getCrossReferencesFor(factId: String): List<MemoryCrossReferenceEntity>

    @Query("SELECT * FROM memory_cross_references WHERE ref_type = :refType")
    suspend fun getByType(refType: String): List<MemoryCrossReferenceEntity>

    @Query("SELECT * FROM memory_cross_references WHERE fact_id_a = :factIdA AND fact_id_b = :factIdB")
    suspend fun getReference(factIdA: String, factIdB: String): MemoryCrossReferenceEntity?

    @Query("SELECT COUNT(*) FROM memory_cross_references")
    suspend fun getCount(): Int

    @Query("DELETE FROM memory_cross_references WHERE fact_id_a = :factId OR fact_id_b = :factId")
    suspend fun deleteCrossReferencesFor(factId: String)
}
