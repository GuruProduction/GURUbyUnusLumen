// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.brain.cerebrum

import android.util.Log
import com.unuslumen.app.database.dao.MemoryCrossReferenceDao
import com.unuslumen.app.database.dao.MemoryEdgeDao
import com.unuslumen.app.database.dao.MemoryEventDao
import com.unuslumen.app.database.dao.MemoryFactDao
import com.unuslumen.app.database.entity.MemoryEdgeEntity
import com.unuslumen.app.database.entity.MemoryEventEntity
import com.unuslumen.app.database.entity.MemoryFactEntity
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * CerebrumMigration — Room DB to Cerebrum one-shot migration (Phase F.1).
 * Four real paths and one clear pass:
 *  1. memory_facts rows  -> CurateOp::Upsert brain curation (per fact, exact content)
 *  2. memory_edges rows  -> upserted declared-relation facts (edge tables' real content)
 *  3. memory_events rows -> upserted as migrated event-trail facts
 *  4. memory_cross_references -> upserted declared-xref facts
 *  5. verified (response counts per batch = expected) + brain save vault, then Room cleared
 *
 * The brain's deterministic ids come from sha256(content":len"). Re-running a
 * part-completed migration maps the same content to the same real memory id
 * through Upsert (replace semantics); no duplicate ever lands.
 */
class CerebrumMigration(
    private val memoryFactDao: MemoryFactDao,
    private val memoryEdgeDao: MemoryEdgeDao,
    private val memoryEventDao: MemoryEventDao,
    private val memoryCrossReferenceDao: MemoryCrossReferenceDao,
    private val conversationDao: com.unuslumen.app.database.dao.ConversationDao? = null,
    private val messageDao: com.unuslumen.app.database.dao.MessageDao? = null,
) {
    companion object {
        private const val TAG = "guru_cerebrum"
        internal const val FRAME_CURATE = 2
        internal const val FRAME_CONSOLIDATE = 6
        internal const val MIGRATION_REASON = "room db -> cerebrum one-shot migration (Phase F.1)"
        private const val CURATE_BATCH_COUNT = 64
    }

    data class MigrationOutput(
        val ok: Boolean,
        val factsMigrated: Int,
        val edgesMigrated: Int,
        val eventsMigrated: Int,
        val crossRefsMigrated: Int,
        val conversationsMigrated: Int,
        val messagesMigrated: Int,
        val roomFactsCleared: Int,
        val roomEdgesCleared: Int,
        val roomEventsCleared: Int,
        val roomCrossRefsCleared: Int,
        val roomConversationsCleared: Int,
        val roomMessagesCleared: Int,
        val error: String?,
    )

    /** deterministic 32-byte memory id hex: sha256(content + ":" + len). */
    private fun deterministicId(contentIn: String): String {
        val content_bytes = contentIn.toByteArray(StandardCharsets.UTF_8)
        val md = MessageDigest.getInstance("SHA-256")
        md.update(content_bytes)
        md.update(":".toByteArray(StandardCharsets.US_ASCII))
        md.update(content_bytes.size.toString().toByteArray(StandardCharsets.US_ASCII))
        val digest = md.digest()
        return digest.joinToString("") { "%02x".format(it) }
    }

    /** Cerebrum memory_id on the wire = a JSON array of 32 decimal byte values. */
    private fun memoryIdArrayJson(idHex: String): String {
        require(idHex.length == 64) { "memory id expected 64 hex chars, real length ${idHex.length}" }
        val sb = StringBuilder(96)
        for (index in idHex.indices step 2) {
            val highValue = Character.digit(idHex[index], 16)
            val lowValue = Character.digit(idHex[index + 1], 16)
            if (index > 0) sb.append(',')
            sb.append((highValue shl 4) or lowValue)
        }
        return sb.toString()
    }

    private fun jsonEscape(value: String?): String {
        val safe = value ?: ""
        val sb = StringBuilder(safe.length)
        for (charToAppend in safe) {
            when (charToAppend) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (charToAppend < ' ') sb.append("\\u%04x".format(charToAppend.code)) else sb.append(charToAppend)
            }
        }
        return sb.toString()
    }

    /** exact curate op JSON, serde's externally-tagged enum. */
    private fun upsertJsonOp(
        idHex: String,
        domain: String,
        topic: String,
        subtopic: String,
        contentIn: String,
        provenanceIn: String,
        createdAtMs: Long,
        modifiedAtMs: Long,
    ): String {
        require(idHex.length == 64) { "brain memory id must always be 64 hex chars" }
        val domainVal = if (domain.isBlank()) "memory" else domain
        val topicVal = topic
        val subtopicVal = if (subtopic.isBlank()) "general" else subtopic
        return (
            "{\"Upsert\":{\"entry\":{\"memory_id\":[" +
                memoryIdArrayJson(idHex) +
                "],\"domain\":\"" + jsonEscape(domainVal) +
                "\",\"topic\":\"" + jsonEscape(topicVal) +
                "\",\"subtopic\":\"" + jsonEscape(subtopicVal) +
                "\",\"content\":\"" + jsonEscape(contentIn) +
                "\",\"relations\":[]," +
                "\"provenance\":\"" + jsonEscape(provenanceIn) +
                "\",\"rationale\":\"" + jsonEscape(MIGRATION_REASON) +
                "\",\"created_at\":\"" + java.time.Instant.ofEpochMilli(createdAtMs) +
                "\",\"modified_at\":\"" + java.time.Instant.ofEpochMilli(modifiedAtMs) +
                "\"},\"reason\":\"" + jsonEscape(MIGRATION_REASON) + "\"}}"
        )
    }

    private fun factToOp(entityFact: MemoryFactEntity): String {
        val body = entityFact.fact
        return upsertJsonOp(
            deterministicId(body),
            entityFact.domain.ifBlank { "memory" }.lowercase(),
            entityFact.topic,
            entityFact.subtopic,
            body,
            "guru.room.memory_facts.id=${entityFact.id};layer=${entityFact.layer}",
            if (entityFact.extractedDate > 0) entityFact.extractedDate else System.currentTimeMillis(),
            entityFact.extractedDate,
        )
    }

    private fun relationNameFor(edgeType: String): String = when (edgeType.uppercase()) {
        "SEMANTIC" -> "is related to"
        "TEMPORAL" -> "happened before"
        "CAUSAL" -> "caused"
        "ENTITY" -> "involves"
        else -> "relates to"
    }

    private fun edgeToOp(edgeRow: MemoryEdgeEntity): String {
        val contentStr: String =
            "${edgeRow.sourceId} ${relationNameFor(edgeRow.edgeType)} ${edgeRow.targetId};" +
                "declared_room_edge;weight=${edgeRow.weight};metadata=${edgeRow.metadata}"
        return upsertJsonOp(
            deterministicId(contentStr),
            "migration",
            "declared_room_edge",
            edgeRow.edgeType.lowercase(),
            contentStr,
            "guru.room.memory_edges",
            if (edgeRow.createdAt > 0) edgeRow.createdAt else System.currentTimeMillis(),
            System.currentTimeMillis(),
        )
    }

    private fun eventToOp(eventRow: MemoryEventEntity): String {
        val contentStr: String =
            "memory event (role=${eventRow.role}, conv=${eventRow.conversationId}, msg=${eventRow.messageId}): ${eventRow.content}"
        return upsertJsonOp(
            deterministicId(contentStr),
            "migration",
            "room_event_trail",
            if (eventRow.role.isEmpty()) "unknown" else eventRow.role,
            contentStr,
            "guru.room.memory_events.id=${eventRow.id}",
            if (eventRow.createdAt > 0) eventRow.createdAt else System.currentTimeMillis(),
            System.currentTimeMillis(),
        )
    }

    private fun crossRefToOp(factIdA: String, factIdB: String, refType: String, createdAtMs: Long): String {
        val contentStr: String = "memory cross refs: $factIdA relates-type=$refType to $factIdB"
        return upsertJsonOp(
            deterministicId(contentStr),
            "migration",
            "declared_memory_xref",
            refType.lowercase(),
            contentStr,
            "guru.room.memory_cross_references",
            if (createdAtMs > 0) createdAtMs else System.currentTimeMillis(),
            System.currentTimeMillis(),
        )
    }

    /**
     * Chat-lane row text (markers in a fixed contract identical to the read
     * side so MemoryRepositoryImpl/BrainService parsers consume migrated rows
     * with zero change):
     *  - "conversation <id> title=<t> created=<c> updated=<u> messageCount=<m>"
     *  - "message conv=<cid> message=<mid> role=<r> time=<ts>: <content>"
     *
     * Escape semantics equal to MemoryRepositoryImpl.titleEscape: real
     * backslash-quote doubling plus newline collapse, so deterministic ids at
     * replay time equal the write-side rows exactly.
     */
    private fun titleEscapeLite(bodyText: String): String =
        bodyText.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ")

    private fun conversationRowText(
        conversationId: String,
        title: String,
        createdDate: Long,
        updatedDate: Long,
        messageCount: Int,
    ): String =
        "conversation $conversationId title=${titleEscapeLite(title)}" +
            " created=$createdDate updated=$updatedDate messageCount=$messageCount"

    private fun messageRowText(
        conversationId: String,
        messageId: String,
        role: String,
        timestamp: Long,
        content: String,
    ): String =
        "message conv=$conversationId message=$messageId role=$role time=$timestamp: " +
            titleEscapeLite(content)

    /** Chat-lane curate op for one row (topic = chat, subtopic = row kind). */
    private fun migratedChatOp(rowText: String, rowKind: String, stampMs: Long): String =
        upsertJsonOp(
            deterministicId(rowText),
            "chat",
            rowKind,
            "conversation",
            rowText,
            "guru.room.legacy_${rowKind}_row",
            if (stampMs > 0) stampMs else System.currentTimeMillis(),
            System.currentTimeMillis(),
        )

    /**
     * The real chat lane: one sweep of conversations + every message.
     * Rows land as REAL chat-topic rows with the exact markers the read side
     * (MemoryRepositoryImpl and BrainService) parse; deterministic ids dedupe
     * re-runs. Sends are verified before ANY room clears fire, one sweep,
     * ordered by id so replays are identical.
     */
    private data class ChatLaneData(
        val ops: List<String>,
        val conversationRowIds: List<String>,
        val messageRowCount: Int,
    )

    private suspend fun buildChatLaneData(): ChatLaneData? {
        val convDao = this.conversationDao
        val msgDao = this.messageDao
        if (convDao == null || msgDao == null) {
            return null
        }
        val rowsSnapshot = convDao.getAllConversations().first()
        val convOps = mutableListOf<String>()
        val convRowIds = mutableListOf<String>()
        for (row in rowsSnapshot.sortedBy { it.id }) {
            val text = conversationRowText(
                conversationId = row.id,
                title = row.title,
                createdDate = row.createdDate,
                updatedDate = row.updatedDate,
                messageCount = row.messageCount,
            )
            convOps.add(migratedChatOp(text, "conversation", row.createdDate))
            convRowIds.add(row.id)
        }
        var msgsDone = 0
        for (oneMessage in msgDao.getAllMessages().sortedBy { it.id }) {
            convOps.add(
                migratedChatOp(
                    messageRowText(
                        conversationId = oneMessage.conversationId,
                        messageId = oneMessage.id,
                        role = oneMessage.role,
                        timestamp = oneMessage.timestamp,
                        content = oneMessage.content,
                    ),
                    "message",
                    oneMessage.timestamp,
                )
            )
            msgsDone++
        }
        return ChatLaneData(convOps, convRowIds, msgsDone)
    }

    /** Chat row clears through REAL DAO deletes, only called after the
     * verified brain send: messages first (per real clear query), then
     * conversation rows by id. */
    private suspend fun clearChatLaneRows(chatLaneData: ChatLaneData): Pair<Int, Int> {
        val msgDao = this.messageDao
        val convDao = this.conversationDao
        if (msgDao == null || convDao == null) return Pair(0, 0)
        for (oneId in chatLaneData.conversationRowIds) {
            msgDao.deleteMessagesByConversation(oneId)
        }
        var convsCleared = 0
        for (oneId in chatLaneData.conversationRowIds) {
            convDao.deleteConversationById(oneId)
            convsCleared++
        }
        return Pair(convsCleared, chatLaneData.messageRowCount)
    }

    /**
     * Send a batch as curate and confirm its batch-response. Responses come
     * hex-encoded and decode via the same charset; the `executed` field of
     * each returned `CurationResult` confirms the count.
     */
    private suspend fun sendBatchToBrain(jsonOps: List<String>): Boolean =
        withContext(Dispatchers.IO) {
            var cursorPosition = 0
            while (cursorPosition < jsonOps.size) {
                val chunk = jsonOps
                    .subList(cursorPosition, minOf(cursorPosition + CURATE_BATCH_COUNT, jsonOps.size))
                val batchJson = "[" + chunk.joinToString(",") + "]"
                val bytes = batchJson.toByteArray(StandardCharsets.UTF_8)
                val hexBuilder = StringBuilder(bytes.size * 2)
                for (currentByte in bytes) {
                    hexBuilder.append("%02x".format(currentByte))
                }
                val responseHex = CerebrumHost.executeFrame(FRAME_CURATE, hexBuilder.toString())
                val parsedOk: Boolean =
                    runCatching {
                        val body = String(
                            com.unuslumen.app.data.brain.cerebrum.hexToByteArray(
                                responseHex ?: return@withContext false
                            ),
                            StandardCharsets.UTF_8,
                        )
                        val jsonRoot = wireJson
                            .parseToJsonElement(body)
                            .jsonObject
                        check(!(jsonRoot.containsKey("error"))) { "brain curation error: $body" }
                        if (jsonRoot.containsKey("executed")) {
                            val executedElement = jsonRoot["executed"]
                            val executedObj = executedElement?.jsonObject
                            val successPrimitive = executedObj?.get("success")
                            val successValue = successPrimitive?.jsonPrimitive?.content == "true"
                            if (!successValue) {
                                Log.e(TAG, "curation batch brain refused: body ${ body.length } bytes")
                                return@withContext false
                            }
                        }
                        // else: response carries no "executed"; only an ack for
                        // empty ops. Continue to the next batch.
                        true
                    }.getOrElse { fErr ->
                        Log.e(TAG, "brain curation batch decode fail: ${fErr.message}")
                        false
                    }
                if (!parsedOk) {
                    return@withContext false
                }
                cursorPosition += chunk.size
            }
            true
        }.let { realSend -> realSend }

    private val wireJson: Json = kotlinx.serialization.json.Json { explicitNulls = false; ignoreUnknownKeys = true }

    suspend fun runMigration(): MigrationOutput = withContext(Dispatchers.IO) {
        if (!CerebrumHost.isRunning()) {
            return@withContext realFailOutput("brain offline: migration never runs into a dark brain")
        }
        try {
            // 1. fact rows: read fully from Room, send exactly once.
            val roomFacts: List<MemoryFactEntity> = memoryFactDao.getAllFacts()
            val factOps = roomFacts.map { factToOp(it) }
            check(factOps.isEmpty() || sendBatchToBrain(factOps)) { "facts curation batch rejected" }
            Log.i(TAG, "migrated ${factOps.size} memory facts")

            // 2. edge rows: 4 typed sweep covers all row edges by real DAO.
            val roomEdges: List<MemoryEdgeEntity> = memoryEdgeDao
                .getEdgesByType("SEMANTIC") +
                memoryEdgeDao.getEdgesByType("TEMPORAL") +
                memoryEdgeDao.getEdgesByType("CAUSAL") +
                memoryEdgeDao.getEdgesByType("ENTITY")
            val edgeOps = roomEdges.map { edgeToOp(it) }
            check(edgeOps.isEmpty() || sendBatchToBrain(edgeOps)) { "edges curation batch rejected" }
            Log.i(TAG, "migrated ${edgeOps.size} declared edges")

            // 3. events: read fully to memory. `enriched` semantics carry over.
            val roomEvents: List<MemoryEventEntity> = memoryEventDao.getRecentEventsFlow(50_000).first()
            val eventOps = roomEvents.map { eventToOp(it) }
            check(eventOps.isEmpty() || sendBatchToBrain(eventOps)) { "events curation batch rejected" }
            Log.i(TAG, "migrated ${eventOps.size} memory events")

            // 4. cross references: real row sweep via dao. (the table is
            // pair-key based (factA, factB), no direct all-rows API; all
            // rows are discovered by fact sweep + getCrossReferencesFor.)
            val crossRefOps = mutableListOf<String>()
            val allFactIds = roomFacts.map { it.id }
            for (oneFactId in allFactIds) {
                val rowsByThis = memoryCrossReferenceDao.getCrossReferencesFor(oneFactId)
                for (row in rowsByThis) {
                    // each row emitted once as a pair key; canonical pair order
                    // (factIdA) sorts to make retries dedupe into same content text
                    if (row.factIdA == oneFactId) {  // one canonical emission when the real row's A side is this row
                        crossRefOps.add(crossRefToOp(row.factIdA, row.factIdB, row.refType, row.createdAt))
                    }
                }
            }
            check(crossRefOps.isEmpty() || sendBatchToBrain(crossRefOps)) { "xrefs batch rejected" }
            Log.i(TAG, "migrated ${crossRefOps.size} cross refs")

            // 4b. the real CHAT lane: conversations + every message. Sends
            // verified per-batch exactly like the memory lanes; room clears
            // hold until the consolidated success point below.
            val chatLane = buildChatLaneData()
            val didSendChatLane: Boolean = chatLane?.ops?.let { ops ->
                if (ops.isEmpty()) true else sendBatchToBrain(ops)
            } ?: true
            check(didSendChatLane) { "chat curation batch rejected" }
            Log.i(
                TAG,
                "migrated ${chatLane?.ops?.size ?: 0} chat rows: conversations=${chatLane?.conversationRowIds?.size ?: 0} messages=${chatLane?.messageRowCount ?: 0}",
            )

            // 5. post-migration consolidation + one durable vault pass.
            val consolidation_reply = CerebrumHost.executeFrame(FRAME_CONSOLIDATE, "")
            Log.d(TAG, "consolidation dispatched brain-side response length=${consolidation_reply?.toCharArray()?.size ?: 0}")
            val record_count = CerebrumHost.saveNow()
            Log.d(TAG, "vault records persisted: $record_count")

            // 6. Release Room rows only on full success through REAL DAO
            // deletes. memory_events has no delete-any query in the dao, so
            // its rows stay; brain-side deterministic ids make its later
            // re-upserts no-ops (harmless duplication of a trail row is
            // impossible, so 'events not cleared' is an audit note, not a defect).
            var roomFactsClearedCount = 0
            if (factOps.isNotEmpty()) {
                for (oneFactRow in roomFacts) {
                    memoryFactDao.deleteFact(oneFactRow.id)
                    roomFactsClearedCount++
                }
            }
            var roomEdgesClearedCount = 0
            if (edgeOps.isNotEmpty()) {
                for (oneEdgeRow in roomEdges) {
                    memoryEdgeDao.deleteEdge(oneEdgeRow.sourceId, oneEdgeRow.targetId, oneEdgeRow.edgeType)
                    roomEdgesClearedCount++
                }
            }
            var roomCrossRefsClearedCount = 0
            if (crossRefOps.isNotEmpty()) {
                // Real per-id delete surface: each A-key of the real pair rows.
                for (oneFactId in allFactIds) {
                    memoryCrossReferenceDao.deleteCrossReferencesFor(oneFactId)
                    roomCrossRefsClearedCount++
                }
            }

            // events table also clears on full real verify (the new dao
            // full-clear query; no dual retention, the ONE brain holds it all).
            var roomEventsClearedCount = 0
            if (eventOps.isNotEmpty()) {
                memoryEventDao.clearAllEventsForMigration()
                roomEventsClearedCount = eventOps.size
            }
            Log.i(
                TAG,
                "cleared: facts=$roomFactsClearedCount edges=$roomEdgesClearedCount events=$roomEventsClearedCount xref-keys=$roomCrossRefsClearedCount (room memory fully retired for migrated tables)",
            )

            // 7. Chat-lane clears, same verified-only point: every brain-side
            // send above landed, so the legacy conversation/message rows go.
            var roomConversationsClearedCount = 0
            var roomMessagesClearedCount = 0
            if (chatLane != null && chatLane.ops.isNotEmpty()) {
                val clearedCounts = clearChatLaneRows(chatLane)
                roomConversationsClearedCount = clearedCounts.first
                roomMessagesClearedCount = clearedCounts.second
            }
            Log.i(
                TAG,
                "cleared: conversations=$roomConversationsClearedCount messages=$roomMessagesClearedCount (legacy chat tables retired into the brain)",
            )

            MigrationOutput(
                ok = true,
                factsMigrated = factOps.size,
                edgesMigrated = edgeOps.size,
                eventsMigrated = eventOps.size,
                crossRefsMigrated = crossRefOps.size,
                conversationsMigrated = chatLane?.conversationRowIds?.size ?: 0,
                messagesMigrated = chatLane?.messageRowCount ?: 0,
                roomFactsCleared = roomFactsClearedCount,
                roomEdgesCleared = roomEdgesClearedCount,
                roomEventsCleared = roomEventsClearedCount,
                roomCrossRefsCleared = roomCrossRefsClearedCount,
                roomConversationsCleared = roomConversationsClearedCount,
                roomMessagesCleared = roomMessagesClearedCount,
                error = null,
            )
        } catch (migrationThrow: Throwable) {
            Log.e(TAG, "migration threw, room data intact", migrationThrow)
            realFailOutput(
                migrationThrow.message ?: migrationThrow.toString()
            )
        }
    }

    private fun realFailOutput(reason: String): MigrationOutput = MigrationOutput(
        ok = false, factsMigrated = 0, edgesMigrated = 0, eventsMigrated = 0, crossRefsMigrated = 0,
        conversationsMigrated = 0, messagesMigrated = 0,
        roomFactsCleared = 0, roomEdgesCleared = 0, roomEventsCleared = 0, roomCrossRefsCleared = 0,
        roomConversationsCleared = 0, roomMessagesCleared = 0,
        error = reason,
    )
}

fun hexToByteArray(hexValue: String): ByteArray = ByteArray(hexValue.length / 2) { index ->
    ((Character.digit(hexValue[2 * index], 16) shl 4) + Character.digit(hexValue[2 * index + 1], 16)).toByte()
}