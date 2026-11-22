// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.tools

import com.unuslumen.app.data.memory.persistedFactRoundTrip
import com.unuslumen.app.domain.memory.Conversation
import com.unuslumen.app.domain.memory.ConversationMessage
import com.unuslumen.app.domain.memory.ConversationThread
import com.unuslumen.app.domain.memory.FactListing
import com.unuslumen.app.domain.memory.MemoryFact
import com.unuslumen.app.domain.memory.MemoryHistory
import com.unuslumen.app.domain.memory.MemoryRepository
import com.unuslumen.app.domain.memory.MemoryRevision
import com.unuslumen.app.domain.memory.RetrievedContext
import com.unuslumen.app.domain.memory.ToolResultSearchHit
import com.unuslumen.app.domain.model.AiMessage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

/**
 * The memory tool family's identity contract, proved on the JVM.
 *
 * Issue 201 reported three symptoms that were really one broken promise: the id
 * a caller is given must be an id the caller can use. These tests hold the tool
 * lane to it without a device, because the arithmetic is all here:
 *
 * - `addMemoryFact` reports the brain's deterministic id, never an invented one.
 * - `deleteMemoryFact` accepts that id immediately, with no search in between.
 * - delete and update resolve by exact lookup, so a fact the listing would not
 *   show can still be removed.
 * - `listMemoryFacts` reports true totals rather than its own page length.
 *
 * The repository below stands in for the brain. It is deliberately strict: it
 * only stores a fact under the deterministic content id, and it only resolves an
 * id the brain would actually hold. That is what makes "the tool reported a
 * usable id" a real assertion rather than a tautology.
 */
class MemoryToolIdentityTest {

    /** The brain's id rule: sha256("content:length"), as 64 lowercase hex. */
    private fun brainIdFor(content: String): String {
        val contentBytes = content.toByteArray(Charsets.UTF_8)
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(contentBytes)
        digest.update(":".toByteArray(Charsets.US_ASCII))
        digest.update(contentBytes.size.toString().toByteArray(Charsets.US_ASCII))
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * A repository that behaves like the brain on the facts it holds.
     *
     * `stored` is keyed by the deterministic content id, exactly as the brain
     * does, so a tool that reports any other id is caught immediately.
     */
    private class BrainLikeRepository : MemoryRepository {
        val stored = LinkedHashMap<String, MemoryFact>()
        /** Retired rows, which a live listing cannot show and dedup must still see. */
        val archived = LinkedHashMap<String, MemoryFact>()

        /**
         * The append-only trail, keyed by memory id.
         *
         * KEPT SEPARATELY FROM [stored] BECAUSE THAT IS WHAT THE BRAIN DOES. The trail
         * outlives the row, which is the entire reason a history read can answer for a
         * deleted memory, so a fake that read the live rows would make the one behaviour
         * worth proving impossible to prove.
         */
        val trail = LinkedHashMap<String, MutableList<MemoryRevision>>()

        var brainReachable = true

        private fun record(
            id: String,
            kind: String,
            content: String,
            superseded: String?,
            reason: String,
            actor: String = "guru.role=",
        ) {
            trail.getOrPut(id) { mutableListOf() }.add(
                MemoryRevision(
                    kind = kind,
                    content = content,
                    superseded = superseded,
                    reason = reason,
                    actor = actor,
                    recordedAt = "2026-11-21T00:00:00Z",
                )
            )
        }

        override suspend fun persistFacts(facts: List<MemoryFact>): List<MemoryFact> {
            val out = mutableListOf<MemoryFact>()
            for (one in facts) {
                val id = digestOf(one.fact)
                val row = one.copy(id = id, domain = one.category.lowercase())
                stored[id] = row
                record(id, "Created", row.fact, null, "")
                out.add(row)
            }
            return out
        }

        /**
         * The typed record path, answered the way the brain answers it.
         *
         * `storeRecord` was added to MemoryRepository for issue 486 and this fake
         * stopped implementing the interface, which broke compilation of the whole
         * unit suite. It is implemented properly rather than as an empty stub,
         * because a stub here would let a cortex test pass without proving anything:
         * the id has to be the deterministic content id, and the hierarchy has to be
         * what the caller asked for.
         */
        override suspend fun storeRecord(
            content: String,
            domain: String,
            topic: String,
            subtopic: String,
            provenance: String,
            rationale: String,
            relatedTo: List<String>,
            confidence: Float,
        ): MemoryFact {
            val id = digestOf(content)
            val row = MemoryFact(
                id = id,
                category = domain.ifBlank { "memory" },
                fact = content,
                confidence = confidence,
                extractedDate = System.currentTimeMillis(),
                domain = domain.ifBlank { "memory" },
                topic = topic.ifBlank { "general" },
                subtopic = subtopic.ifBlank { "general" },
            )
            stored[id] = row
            record(id, "Created", content, null, rationale)
            return row
        }

        /**
         * The trail, read exactly as the brain serves it: from the event log, including
         * revisions of a row that has since been retired.
         */
        override suspend fun getFactHistory(factId: String): MemoryHistory? {
            if (!brainReachable) return null
            val revisions = trail[factId] ?: return null
            val live = stored[factId]
            val retired = archived[factId]
            return MemoryHistory(
                memoryId = factId,
                lifecycle = when {
                    live != null -> "Active"
                    retired != null -> "Archived"
                    else -> "Archived"
                },
                category = (live ?: retired)?.category.orEmpty(),
                everRetired = revisions.any { it.kind == "Deleted" } || retired != null,
                revisions = revisions.toList(),
            )
        }

        override suspend fun getAllFacts(): List<MemoryFact> = stored.values.toList()

        override suspend fun getFact(factId: String): MemoryFact? = stored[factId]

        override suspend fun listFacts(category: String?): FactListing {
            if (!brainReachable) return FactListing(emptyList(), 0, 0, brainReachable = false)
            val all = stored.values.toList()
            val matching = if (category == null) {
                all
            } else {
                all.filter { it.category.equals(category, ignoreCase = true) }
            }
            return FactListing(matching, all.size, matching.size, brainReachable = true)
        }

        override suspend fun getFactsByCategory(category: String): List<MemoryFact> =
            stored.values.filter { it.category.equals(category, ignoreCase = true) }

        /**
         * The retired rows, which this fake keeps separate from the live ones.
         *
         * `getArchivedFactsForDeduplication` was added to MemoryRepository for the
         * anti-resurrection fix, and a fake that does not implement it stops the whole
         * unit suite compiling. Answering it with the live rows would be worse than
         * answering with nothing: the method exists precisely because the live rows are
         * the wrong comparison set.
         */
        override suspend fun getArchivedFactsForDeduplication(): List<MemoryFact> =
            archived.values.toList()

        /**
         * Update IN PLACE, keeping the id, exactly as the brain's Update op does.
         *
         * A fake that implemented this as a delete plus a write would hide the very defect
         * the production change exists to remove, so it overwrites the stored row under the
         * same key and leaves the identity alone.
         */
        override suspend fun updateFact(
            factId: String,
            newContent: String,
            category: String,
            confidence: Float,
            reason: String,
        ): MemoryFact {
            val existing = stored[factId] ?: error("no such fact: $factId")
            val updated = existing.copy(
                fact = newContent,
                category = category,
                confidence = confidence,
            )
            stored[factId] = updated
            // The superseded text is recorded, which is the one thing an update exists to
            // do beyond changing the row.
            record(factId, "Updated", newContent, existing.fact, reason)
            return updated
        }

        override suspend fun searchFacts(query: String, category: String?): List<MemoryFact> =
            stored.values.filter { it.fact.contains(query, ignoreCase = true) }

        /**
         * Retire a row rather than erasing it, exactly as the brain's No-Delete
         * doctrine does. That is what makes the retired set meaningful, and it is what
         * the anti-resurrection rule is about: the row still exists, so a repeat write
         * of the same content resolves to it.
         */
        override suspend fun deleteFact(factId: String) {
            stored.remove(factId)?.let { retired ->
                archived[factId] = retired
                record(factId, "Deleted", retired.fact, null, "guru deleteFact: brain archive")
            }
        }

        override suspend fun markFactRecalled(factId: String) = Unit

        // Unused by these tests; present so the interface is complete.
        override suspend fun createConversation(conversation: Conversation) = Unit
        override suspend fun updateConversation(conversation: Conversation) = Unit
        override suspend fun deleteConversation(conversationId: String) = Unit
        override suspend fun getConversation(conversationId: String): Conversation? = null
        override suspend fun getAllConversations(): List<Conversation> = emptyList()
        override suspend fun searchConversations(query: String): List<Conversation> = emptyList()
        override suspend fun persistMessage(message: ConversationMessage) = Unit
        override suspend fun persistMessages(messages: List<ConversationMessage>) = Unit
        override suspend fun getMessagesByConversation(conversationId: String): List<ConversationMessage> = emptyList()
        override suspend fun updateMessageEmbedding(messageId: String, embedding: List<Float>) = Unit
        /**
         * The graph walk, which this fake does not exercise.
         *
         * traverseGraph was added to MemoryRepository after this fake was written, so
         * the class stopped implementing the interface and the whole unit suite stopped
         * compiling. An empty answer is the honest one here: none of these tests is
         * about the graph, and the graph has its own tests where the edges are built.
         */
        override suspend fun traverseGraph(
            subject: String,
            edgeType: String,
            maxDepth: Int,
            maxNodes: Int,
        ): List<MemoryFact> = emptyList()

        override suspend fun persistThreads(threads: List<ConversationThread>) = Unit
        override suspend fun getAllThreads(): List<ConversationThread> = emptyList()
        override suspend fun buildContextPreamble(
            query: String,
            humanName: String,
            currentMessages: List<AiMessage>,
            conversationId: String,
        ): RetrievedContext = RetrievedContext(emptyList(), emptyList(), emptyList(), "")
        override suspend fun persistAiMessages(conversationId: String, messages: List<AiMessage>) = Unit
        override suspend fun getMessagesNeedingEmbeddings(): List<ConversationMessage> = emptyList()
        override suspend fun getUnprocessedConversations(): List<Conversation> = emptyList()
        override suspend fun getLastProcessedConversationId(): String = ""
        override suspend fun markHiveProcessing(conversationId: String, factsExtracted: Int, threadsDiscovered: Int) = Unit
        override suspend fun getTotalFacts(): Int = stored.size
        override suspend fun getTotalThreads(): Int = 0
        override suspend fun searchMessages(query: String): List<ConversationMessage> = emptyList()
        override suspend fun searchToolResults(query: String): List<ToolResultSearchHit> = emptyList()

        companion object {
            fun digestOf(content: String): String {
                val contentBytes = content.toByteArray(Charsets.UTF_8)
                val digest = MessageDigest.getInstance("SHA-256")
                digest.update(contentBytes)
                digest.update(":".toByteArray(Charsets.US_ASCII))
                digest.update(contentBytes.size.toString().toByteArray(Charsets.US_ASCII))
                return digest.digest().joinToString("") { "%02x".format(it) }
            }
        }
    }

    /**
     * The text a tool result carries, for assertions.
     *
     * A success carries its serialized payload in `rawJson`; a failure carries
     * its sentence in `error` and no payload at all. Reading both through one
     * helper keeps each assertion about the message, not about which field the
     * framework happens to use for it.
     */
    private fun payloadOf(result: com.unuslumen.app.data.tools.registry.ToolExecutionResult): String =
        if (result.success) result.rawJson else (result.error ?: "")

    @Test
    fun `add reports the brain id and delete accepts it with no search`() = runBlocking {
        val repository = BrainLikeRepository()
        val executor = MemoryToolExecutor(repository)
        val content = "GURU-DELETE-REPRO-ONE the kettle lives in the kitchen"

        val addResult = executor.execute(
            MemoryToolDefinitions.ADD_MEMORY_FACT,
            mapOf("fact" to content, "category" to "knowledge", "confidence" to 0.1),
        )
        assertTrue("add must succeed: ${payloadOf(addResult)}", addResult.success)

        // The reported id must be the brain's deterministic id for this content.
        val expectedId = brainIdFor(content)
        assertTrue(
            "add must report the brain's real id, got: ${payloadOf(addResult)}",
            payloadOf(addResult).contains(expectedId),
        )
        assertTrue("the brain must actually hold that id", repository.stored.containsKey(expectedId))

        // And delete must accept that exact id FIRST TIME, with no search.
        val deleteResult = executor.execute(
            MemoryToolDefinitions.DELETE_MEMORY_FACT,
            mapOf("factId" to expectedId),
        )
        assertTrue("delete must accept the id add reported: ${payloadOf(deleteResult)}", deleteResult.success)
        assertFalse(
            "the fact must genuinely be gone from the brain",
            repository.stored.containsKey(expectedId),
        )
    }

    @Test
    fun `add never reports a random uuid`() = runBlocking {
        val repository = BrainLikeRepository()
        val executor = MemoryToolExecutor(repository)

        // `home` is not one of the permitted categories (issue 485), so this fixture
        // used a word the vocabulary now refuses. The test is about the ID SHAPE,
        // not about the category, so it uses a real one.
        val addResult = executor.execute(
            MemoryToolDefinitions.ADD_MEMORY_FACT,
            mapOf("fact" to "the boiler service is booked for tuesday", "category" to "personal_info"),
        )

        // A uuid is 36 characters with hyphens; a brain id is 64 hex characters.
        // Asserting the shape catches the exact regression: an invented handle.
        val payload = payloadOf(addResult)
        assertFalse(
            "a reported id must be 64-hex, never a uuid: $payload",
            Regex("\"id\":\"[0-9a-f]{8}-[0-9a-f]{4}").containsMatchIn(payload),
        )
        assertTrue(
            "the reported id must be the 64-hex brain form: $payload",
            Regex("\"id\":\"[0-9a-f]{64}\"").containsMatchIn(payload),
        )
    }

    /**
     * The defect the issue calls out as most damaging: delete proved existence
     * with a relevance search, so a fact the search did not surface reported
     * "not found" even though the brain held it. The exact lookup must resolve
     * any id the brain holds, regardless of its wording.
     */
    @Test
    fun `delete resolves a fact by id even when a keyword search would miss it`() = runBlocking {
        val repository = BrainLikeRepository()
        val executor = MemoryToolExecutor(repository)
        val content = "my sister is flying to lisbon on thursday"
        val id = brainIdFor(content)
        repository.stored[id] = MemoryFact(
            id = id,
            category = "knowledge",
            fact = content,
            confidence = 0.9f,
            extractedDate = 1L,
        )

        // A search for an unrelated word finds nothing, which is what the old
        // existence check was doing and why it reported not-found.
        assertNull(
            "precondition: a keyword search must miss this fact",
            repository.searchFacts("boiler", null).firstOrNull { it.id == id },
        )

        val deleteResult = executor.execute(
            MemoryToolDefinitions.DELETE_MEMORY_FACT,
            mapOf("factId" to id),
        )
        assertTrue(
            "delete must resolve by id, not by whether a search happens to surface it: ${payloadOf(deleteResult)}",
            deleteResult.success,
        )
        assertFalse("the fact must be gone", repository.stored.containsKey(id))
    }

    @Test
    fun `delete of a genuinely absent id reports not found`() = runBlocking {
        val repository = BrainLikeRepository()
        val executor = MemoryToolExecutor(repository)
        val absentId = brainIdFor("this fact was never stored")

        val deleteResult = executor.execute(
            MemoryToolDefinitions.DELETE_MEMORY_FACT,
            mapOf("factId" to absentId),
        )

        assertFalse("an absent id must fail", deleteResult.success)
        assertTrue(
            "the failure must say not found: ${payloadOf(deleteResult)}",
            payloadOf(deleteResult).contains("not found", ignoreCase = true),
        )
    }

    @Test
    fun `delete rejects a non brain id instead of silently doing nothing`() = runBlocking {
        val repository = BrainLikeRepository()
        val executor = MemoryToolExecutor(repository)

        val deleteResult = executor.execute(
            MemoryToolDefinitions.DELETE_MEMORY_FACT,
            mapOf("factId" to "5b39855b-2e02-4b15-8079-f756566ea52d"),
        )

        assertFalse(
            "a uuid is not a brain id and must be refused: ${payloadOf(deleteResult)}",
            deleteResult.success,
        )
    }

    @Test
    fun `update keeps the identity and rewrites the row in place`() = runBlocking {
        /*
         * THE CONTRACT THIS ASSERTS USED TO BE THE OPPOSITE, AND THE OLD VERSION WAS THE BUG.
         *
         * It used to require that an update produced a NEW id and archived the old row. That
         * is not an update: an id is the hash of the content, so writing new text creates a
         * different row and the old one is archived with no record of what it said. The
         * update-history mechanism exists so "what did this memory say before" has an answer,
         * and the old path never produced the event that makes the answer possible.
         *
         * The identity now survives, so every id a caller was given still resolves, and the
         * superseded text is the brain's business rather than something lost between two
         * writes.
         */
        val repository = BrainLikeRepository()
        val executor = MemoryToolExecutor(repository)
        val originalContent = "the landlord has not fixed the boiler"
        val originalId = brainIdFor(originalContent)
        repository.stored[originalId] = MemoryFact(
            id = originalId,
            category = "home",
            fact = originalContent,
            confidence = 0.9f,
            extractedDate = 1L,
        )

        val updatedContent = "the landlord fixed the boiler on monday"
        val updateResult = executor.execute(
            MemoryToolDefinitions.UPDATE_MEMORY_FACT,
            mapOf("factId" to originalId, "fact" to updatedContent, "confidence" to 0.95),
        )

        assertTrue("update must accept a search id: ${payloadOf(updateResult)}", updateResult.success)
        assertTrue(
            "the row must keep its id, so every id the caller holds still resolves: ${payloadOf(updateResult)}",
            payloadOf(updateResult).contains(originalId),
        )
        assertEquals(
            "the text is rewritten in place",
            updatedContent,
            repository.stored.getValue(originalId).fact,
        )
        assertEquals(
            "and the confidence the caller asked for is the one stored",
            0.95f,
            repository.stored.getValue(originalId).confidence,
            0.0001f,
        )
        assertFalse(
            "no second row is created for the same memory: that is what made an update a delete plus a write",
            repository.stored.containsKey(brainIdFor(updatedContent)),
        )
    }

    /**
     * The listing must be an enumeration, not a keyword page. Two facts with no
     * word in common must both appear, which is what the old "search for the
     * word memory" listing could not do.
     */
    @Test
    fun `list reports every fact and its true totals`() = runBlocking {
        val repository = BrainLikeRepository()
        val executor = MemoryToolExecutor(repository)

        val contents = listOf(
            "the kettle is in the kitchen",
            "my sister is flying to lisbon",
            "the quarterly invoice arrived",
        )
        for ((index, text) in contents.withIndex()) {
            val id = brainIdFor(text)
            repository.stored[id] = MemoryFact(
                id = id,
                category = "knowledge",
                fact = text,
                confidence = 0.5f + index * 0.1f,
                extractedDate = index.toLong(),
            )
        }

        val listResult = executor.execute(MemoryToolDefinitions.LIST_MEMORY_FACTS, emptyMap())
        assertTrue("list must succeed: ${payloadOf(listResult)}", listResult.success)

        val payload = payloadOf(listResult)
        for (text in contents) {
            assertTrue("listing omitted $text: $payload", payload.contains(text))
        }
        assertTrue("the true total must be reported: $payload", payload.contains("\"totalLive\":3"))
        assertTrue("the shown count must be reported: $payload", payload.contains("\"shown\":3"))
    }

    /**
     * A brain that is not running must be reported as unreachable, not as an
     * empty memory. The two are different truths about the machine.
     */
    @Test
    fun `list reports an unreachable brain rather than an empty memory`() = runBlocking {
        val repository = BrainLikeRepository()
        repository.brainReachable = false
        val executor = MemoryToolExecutor(repository)

        val listResult = executor.execute(MemoryToolDefinitions.LIST_MEMORY_FACTS, emptyMap())

        assertFalse("an unreachable brain is a failure, not an empty list", listResult.success)
        assertTrue(
            "the failure must say the brain is not running: ${payloadOf(listResult)}",
            payloadOf(listResult).contains("not running", ignoreCase = true),
        )
    }

    /**
     * The history tool reaches the trail through the tool surface, and it carries the
     * superseded text, the reason and the actor.
     *
     * THE DEFECT THIS HOLDS SHUT. Every piece of this existed and was unreachable: the
     * engine writes an append-only event per mutation, `EventStore::history_for` answers
     * from it, the HISTORY frame dispatches, and `CerebrumBrainApi.history` parses it. What
     * did not exist was a DOOR, so "what did this memory say before" was stored and
     * unreadable. This drives the tool the model actually calls.
     */
    @Test
    fun `memoryHistory reports each revision with the text it replaced`() = runBlocking {
        val repository = BrainLikeRepository()
        val executor = MemoryToolExecutor(repository)
        val original = "the boiler service is booked for tuesday"
        val id = brainIdFor(original)
        repository.stored[id] = MemoryFact(
            id = id,
            category = "knowledge",
            fact = original,
            confidence = 0.9f,
            extractedDate = 1L,
        )
        repository.trail[id] = mutableListOf(
            MemoryRevision("Created", original, null, "", "guru.role=", "2026-11-21T10:00:00Z"),
        )

        executor.execute(
            MemoryToolDefinitions.UPDATE_MEMORY_FACT,
            mapOf(
                "factId" to id,
                "fact" to "the boiler service is booked for wednesday",
                "confidence" to 0.95,
            ),
        )

        val historyResult = executor.execute(
            MemoryToolDefinitions.MEMORY_HISTORY,
            mapOf("factId" to id),
        )
        val payload = payloadOf(historyResult)
        assertTrue("history must succeed: $payload", historyResult.success)
        assertTrue(
            "the trail must carry the original text as the superseded one: $payload",
            payload.contains(original),
        )
        assertTrue(
            "and the new text: $payload",
            payload.contains("the boiler service is booked for wednesday"),
        )
        assertTrue("the revision count must be stated: $payload", payload.contains("\"revisionCount\":2"))
        assertTrue("the lifecycle must be stated: $payload", payload.contains("\"lifecycle\":\"Active\""))
    }

    /**
     * A DELETED MEMORY STILL ANSWERS ITS HISTORY, which is the case the read exists for.
     *
     * Every other verb refuses a retired row. Asking who deleted something and what it said
     * is a question that can only be asked after the deletion, so a history read that
     * behaved like the others would be a read nobody could ever use.
     */
    @Test
    fun `memoryHistory still answers after the memory is deleted`() = runBlocking {
        val repository = BrainLikeRepository()
        val executor = MemoryToolExecutor(repository)
        val content = "the spare key is under the blue pot"
        val id = brainIdFor(content)
        repository.stored[id] = MemoryFact(
            id = id,
            category = "knowledge",
            fact = content,
            confidence = 0.9f,
            extractedDate = 1L,
        )
        repository.trail[id] = mutableListOf(
            MemoryRevision("Created", content, null, "", "guru.role=", "2026-11-21T10:00:00Z"),
        )

        val deleteResult = executor.execute(
            MemoryToolDefinitions.DELETE_MEMORY_FACT,
            mapOf("factId" to id),
        )
        assertTrue("precondition: the delete must land: ${payloadOf(deleteResult)}", deleteResult.success)
        assertFalse("precondition: the row must be gone from the live set", repository.stored.containsKey(id))

        val historyResult = executor.execute(
            MemoryToolDefinitions.MEMORY_HISTORY,
            mapOf("factId" to id),
        )
        val payload = payloadOf(historyResult)
        assertTrue("a deleted memory must still answer its history: $payload", historyResult.success)
        assertTrue("the deletion must be recorded: $payload", payload.contains("Deleted"))
        assertTrue("and the retirement flagged: $payload", payload.contains("\"everRetired\":true"))
        assertTrue("with the text it held: $payload", payload.contains(content))
    }

    @Test
    fun `memoryHistory refuses a non brain id without touching the brain`() = runBlocking {
        val repository = BrainLikeRepository()
        val executor = MemoryToolExecutor(repository)

        val result = executor.execute(
            MemoryToolDefinitions.MEMORY_HISTORY,
            mapOf("factId" to "5b39855b-2e02-4b15-8079-f756566ea52d"),
        )
        assertFalse("a uuid is not a brain id: ${payloadOf(result)}", result.success)
        assertTrue(
            "the refusal must name the id shape: ${payloadOf(result)}",
            payloadOf(result).contains("Not a memory id"),
        )
    }

    @Test
    fun `memoryHistory reports an id with no trail rather than inventing one`() = runBlocking {
        val repository = BrainLikeRepository()
        val executor = MemoryToolExecutor(repository)

        val result = executor.execute(
            MemoryToolDefinitions.MEMORY_HISTORY,
            mapOf("factId" to brainIdFor("this was never stored or recorded")),
        )
        assertFalse(
            "an id with no row and no trail must fail rather than return an empty history: ${payloadOf(result)}",
            result.success,
        )
    }

    /**
     * The round trip helper must hand back the stored row, and must fail loudly
     * rather than falling back to the caller's draft. The old fallback IS the
     * reported bug: it returned a draft carrying a uuid the brain cannot resolve.
     */
    @Test
    fun `the round trip returns the stored row and never a caller draft`() = runBlocking {
        val repository = BrainLikeRepository()
        val draft = MemoryFact(
            id = "",
            category = "knowledge",
            fact = "the spare key hangs behind the microwave",
            confidence = 0.8f,
            extractedDate = 1L,
        )

        val stored = persistedFactRoundTrip(repository, draft)

        assertNotNull(stored.id)
        assertEquals(brainIdFor(draft.fact), stored.id)
        assertEquals(1, repository.stored.size)
    }
}
