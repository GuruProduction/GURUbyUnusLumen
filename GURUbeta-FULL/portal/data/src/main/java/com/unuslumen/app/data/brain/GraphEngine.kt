package com.unuslumen.app.data.brain

import com.unuslumen.app.database.dao.MemoryEdgeDao
import com.unuslumen.app.database.entity.MemoryEdgeEntity
import com.unuslumen.app.database.entity.MemoryFactEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.LinkedList

/**
 * GraphEngine — Manages relationships between facts via typed edges.
 *
 * BFS loads edges only for nodes it visits, not the entire graph.
 * Each visited node triggers one getEdgesForFact query, so the total
 * queries equal the number of visited nodes, not the total edge count.
 */
class GraphEngine(
    private val edgeDao: MemoryEdgeDao
) {

    companion object {
        const val EDGE_SEMANTIC = "SEMANTIC"
        const val EDGE_TEMPORAL = "TEMPORAL"
        const val EDGE_CAUSAL = "CAUSAL"
        const val EDGE_ENTITY = "ENTITY"
    }

    suspend fun createEdge(
        sourceId: String,
        targetId: String,
        edgeType: String,
        weight: Float = 1.0f,
        currentTime: Long,
        metadata: String = ""
    ) = withContext(Dispatchers.Default) {
        edgeDao.insertEdge(MemoryEdgeEntity(
            sourceId = sourceId,
            targetId = targetId,
            edgeType = edgeType,
            weight = weight,
            createdAt = currentTime,
            metadata = metadata
        ))
    }

    suspend fun buildSemanticEdges(
        newFact: MemoryFactEntity,
        existingFacts: List<MemoryFactEntity>,
        currentTime: Long,
        minSimilarity: Float = 0.5f
    ) = withContext(Dispatchers.Default) {
        if (newFact.signature.isBlank()) return@withContext

        val newSig = SignatureEngine.decodeSignature(newFact.signature)
        val edges = mutableListOf<MemoryEdgeEntity>()

        for (existing in existingFacts) {
            if (existing.id == newFact.id || existing.signature.isBlank()) continue

            val existingSig = SignatureEngine.decodeSignature(existing.signature)
            val similarity = SignatureEngine.similarity(newSig, existingSig)

            if (similarity >= minSimilarity) {
                edges.add(MemoryEdgeEntity(
                    sourceId = newFact.id,
                    targetId = existing.id,
                    edgeType = EDGE_SEMANTIC,
                    weight = similarity,
                    createdAt = currentTime
                ))
            }
        }

        if (edges.isNotEmpty()) {
            edgeDao.insertEdges(edges)
        }
    }

    suspend fun buildTemporalEdges(
        factsInOrder: List<MemoryFactEntity>,
        currentTime: Long
    ) = withContext(Dispatchers.Default) {
        val edges = mutableListOf<MemoryEdgeEntity>()
        for (i in 0 until factsInOrder.size - 1) {
            edges.add(MemoryEdgeEntity(
                sourceId = factsInOrder[i].id,
                targetId = factsInOrder[i + 1].id,
                edgeType = EDGE_TEMPORAL,
                weight = 1.0f,
                createdAt = currentTime
            ))
        }
        if (edges.isNotEmpty()) {
            edgeDao.insertEdges(edges)
        }
    }

    /**
     * BFS traversal from a starting fact, up to maxDepth hops.
     * Loads edges only for visited nodes using getEdgesForFact.
     * Total queries = number of visited nodes, not total edges in the graph.
     */
    suspend fun bfs(
        startFactId: String,
        maxDepth: Int = 3,
        edgeTypes: Set<String> = setOf(EDGE_SEMANTIC, EDGE_TEMPORAL, EDGE_CAUSAL, EDGE_ENTITY)
    ): Map<Int, List<String>> = withContext(Dispatchers.Default) {
        val result = mutableMapOf<Int, MutableList<String>>()
        val visited = mutableSetOf(startFactId)
        val queue = LinkedList<Pair<String, Int>>()
        queue.add(startFactId to 0)

        while (queue.isNotEmpty()) {
            val polled = queue.poll()
            if (polled == null) break
            val (current, depth) = polled
            if (depth >= maxDepth) continue

            // Load edges only for this node
            val edges = edgeDao.getEdgesForFact(current)
                .filter { it.edgeType in edgeTypes }

            for (edge in edges) {
                val neighbor = if (edge.sourceId == current) edge.targetId else edge.sourceId
                if (neighbor !in visited) {
                    visited.add(neighbor)
                    result.getOrPut(depth + 1) { mutableListOf() }.add(neighbor)
                    queue.add(neighbor to depth + 1)
                }
            }
        }

        result
    }

    /**
     * Get directly connected facts (depth 1) for a given fact.
     * Single database query.
     */
    suspend fun getConnectedFacts(
        factId: String,
        edgeTypes: Set<String> = setOf(EDGE_SEMANTIC, EDGE_ENTITY)
    ): List<Pair<String, Float>> = withContext(Dispatchers.Default) {
        edgeDao.getEdgesForFact(factId)
            .filter { it.edgeType in edgeTypes }
            .map { edge ->
                val otherId = if (edge.sourceId == factId) edge.targetId else edge.sourceId
                otherId to edge.weight
            }
            .sortedByDescending { it.second }
    }
}
