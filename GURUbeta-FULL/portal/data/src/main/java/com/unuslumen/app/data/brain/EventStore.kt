package com.unuslumen.app.data.brain

import com.unuslumen.app.database.dao.MemoryEventDao
import com.unuslumen.app.database.entity.MemoryEventEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * EventStore — Append-only audit trail of every memory event.
 *
 * Every time a fact is created, recalled, updated, promoted, pruned, or
 * cross-referenced, an event is recorded. Events track whether they've been
 * "enriched" — meaning a background process has analyzed them for patterns,
 * insights, or follow-up actions.
 *
 * Ported from Cerebrum's event_store crate to Kotlin.
 */
class EventStore(
    private val eventDao: MemoryEventDao
) {

    companion object {
        const val EVENT_FACT_CREATED = "FACT_CREATED"
        const val EVENT_FACT_RECALLED = "FACT_RECALLED"
        const val EVENT_FACT_UPDATED = "FACT_UPDATED"
        const val EVENT_FACT_PROMOTED = "FACT_PROMOTED"
        const val EVENT_FACT_PRUNED = "FACT_PRUNED"
        const val EVENT_EDGE_CREATED = "EDGE_CREATED"
        const val EVENT_CROSS_REF_CREATED = "CROSS_REF_CREATED"
        const val EVENT_COMPACTION = "COMPACTION"
    }

    suspend fun recordEvent(
        conversationId: String,
        messageId: String,
        role: String,
        content: String,
        currentTime: Long
    ) = withContext(Dispatchers.Default) {
        val event = MemoryEventEntity(
            id = UUID.randomUUID().toString(),
            conversationId = conversationId,
            messageId = messageId,
            role = role,
            content = content.take(5000),
            enriched = 0,
            createdAt = currentTime
        )
        eventDao.insertEvent(event)
    }

    suspend fun getUnenrichedEvents(limit: Int = 100): List<MemoryEventEntity> = withContext(Dispatchers.Default) {
        eventDao.getUnenrichedEvents(limit)
    }

    suspend fun markEnriched(eventId: String) = withContext(Dispatchers.Default) {
        eventDao.markEnriched(eventId)
    }

    suspend fun markBatchEnriched(eventIds: List<String>) = withContext(Dispatchers.Default) {
        eventDao.markBatchEnriched(eventIds)
    }

    suspend fun getEventCount(): Int = withContext(Dispatchers.Default) {
        eventDao.getEventCount()
    }

    suspend fun getUnenrichedCount(): Int = withContext(Dispatchers.Default) {
        eventDao.getUnenrichedCount()
    }
}
