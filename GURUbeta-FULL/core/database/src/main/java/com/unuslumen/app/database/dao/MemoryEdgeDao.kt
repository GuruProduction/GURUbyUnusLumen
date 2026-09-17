package com.unuslumen.app.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.unuslumen.app.database.entity.MemoryEdgeEntity

@Dao
interface MemoryEdgeDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEdge(edge: MemoryEdgeEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEdges(edges: List<MemoryEdgeEntity>)

    @Query("SELECT * FROM memory_edges WHERE source_id = :factId OR target_id = :factId")
    suspend fun getEdgesForFact(factId: String): List<MemoryEdgeEntity>

    @Query("SELECT * FROM memory_edges WHERE source_id = :factId")
    suspend fun getOutgoingEdges(factId: String): List<MemoryEdgeEntity>

    @Query("SELECT * FROM memory_edges WHERE target_id = :factId")
    suspend fun getIncomingEdges(factId: String): List<MemoryEdgeEntity>

    @Query("SELECT * FROM memory_edges WHERE edge_type = :edgeType")
    suspend fun getEdgesByType(edgeType: String): List<MemoryEdgeEntity>

    @Query("SELECT * FROM memory_edges WHERE source_id = :factId AND edge_type = :edgeType")
    suspend fun getOutgoingEdgesByType(factId: String, edgeType: String): List<MemoryEdgeEntity>

    @Query("SELECT * FROM memory_edges WHERE target_id = :factId AND edge_type = :edgeType")
    suspend fun getIncomingEdgesByType(factId: String, edgeType: String): List<MemoryEdgeEntity>

    @Query("SELECT COUNT(*) FROM memory_edges")
    suspend fun getEdgeCount(): Int

    @Query("DELETE FROM memory_edges WHERE source_id = :factId AND target_id = :targetId AND edge_type = :edgeType")
    suspend fun deleteEdge(factId: String, targetId: String, edgeType: String)

    @Query("DELETE FROM memory_edges WHERE source_id = :factId OR target_id = :factId")
    suspend fun deleteEdgesForFact(factId: String)
}
