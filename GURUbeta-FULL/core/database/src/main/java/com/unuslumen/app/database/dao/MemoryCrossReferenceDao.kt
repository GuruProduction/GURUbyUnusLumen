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
