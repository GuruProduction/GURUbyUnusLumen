package com.unuslumen.app.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.SkipQueryVerification
import com.unuslumen.app.database.entity.MemoryFactEntity

@Dao
interface MemoryFactDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFact(fact: MemoryFactEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFacts(facts: List<MemoryFactEntity>)

    @Query("SELECT * FROM memory_facts ORDER BY extracted_date DESC")
    suspend fun getAllFacts(): List<MemoryFactEntity>

    @Query("SELECT * FROM memory_facts WHERE id = :factId")
    suspend fun getFact(factId: String): MemoryFactEntity?

    @Query("SELECT * FROM memory_facts WHERE category = :category ORDER BY extracted_date DESC")
    suspend fun getFactsByCategory(category: String): List<MemoryFactEntity>

    // --- Brain Plan queries ---

    @Query("SELECT * FROM memory_facts WHERE layer = :layer ORDER BY strength DESC, extracted_date DESC")
    suspend fun getFactsByLayer(layer: String): List<MemoryFactEntity>

    @Query("SELECT * FROM memory_facts WHERE layer IN ('BUFFER', 'EPISODIC') ORDER BY strength ASC LIMIT :limit")
    suspend fun getDecayCandidates(limit: Int = 50): List<MemoryFactEntity>

    @Query("UPDATE memory_facts SET strength = :strength, access_count = :accessCount WHERE id = :factId")
    suspend fun updateDecay(factId: String, strength: Float, accessCount: Int)

    @Query("UPDATE memory_facts SET last_recalled_date = :timestamp, access_count = access_count + 1 WHERE id = :factId")
    suspend fun updateRecalledDate(factId: String, timestamp: Long)

    @Query("UPDATE memory_facts SET layer = :newLayer WHERE id = :factId")
    suspend fun promoteLayer(factId: String, newLayer: String)

    @Query("SELECT * FROM memory_facts WHERE domain = :domain ORDER BY strength DESC")
    suspend fun getFactsByDomain(domain: String): List<MemoryFactEntity>

    @Query("SELECT * FROM memory_facts WHERE domain = :domain AND topic = :topic ORDER BY strength DESC")
    suspend fun getFactsByDomainTopic(domain: String, topic: String): List<MemoryFactEntity>

    @Query("SELECT * FROM memory_facts WHERE domain = :domain AND topic = :topic AND subtopic = :subtopic ORDER BY strength DESC")
    suspend fun getFactsByDomainTopicSubtopic(domain: String, topic: String, subtopic: String): List<MemoryFactEntity>

    @Query("SELECT * FROM memory_facts WHERE signature != '' ORDER BY extracted_date DESC")
    suspend fun getFactsWithSignatures(): List<MemoryFactEntity>

    @Query("SELECT * FROM memory_facts WHERE emotional_valence != 0.0 ORDER BY emotional_valence DESC")
    suspend fun getFactsWithEmotionalValence(): List<MemoryFactEntity>

    @Query("UPDATE memory_facts SET signature = :signature WHERE id = :factId")
    suspend fun updateSignature(factId: String, signature: String)

    @Query("DELETE FROM memory_facts WHERE id = :factId")
    suspend fun deleteFact(factId: String)

    @Query("SELECT * FROM memory_facts WHERE fact LIKE '%' || :query || '%' OR category LIKE '%' || :query || '%' ORDER BY extracted_date DESC")
    suspend fun searchFactsFts(query: String): List<MemoryFactEntity>

    @Query("SELECT * FROM memory_facts WHERE fact LIKE '%' || :query || '%' ORDER BY extracted_date DESC")
    suspend fun searchFactsByFactText(query: String): List<MemoryFactEntity>

    @SkipQueryVerification
    @Query("""
        SELECT f.* FROM memory_facts f
        WHERE f.rowid IN (SELECT rowid FROM memory_facts_fts WHERE memory_facts_fts MATCH :query)
        ORDER BY f.extracted_date DESC
    """)
    suspend fun searchFactsFts5(query: String): List<MemoryFactEntity>

    @Query("SELECT COUNT(*) FROM memory_facts")
    suspend fun getFactCount(): Int

    @Query("SELECT COUNT(*) FROM memory_facts WHERE layer = :layer")
    suspend fun getFactCountByLayer(layer: String): Int
}
