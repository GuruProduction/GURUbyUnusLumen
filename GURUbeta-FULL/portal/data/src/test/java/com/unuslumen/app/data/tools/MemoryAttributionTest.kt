// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.tools

import com.unuslumen.app.data.memory.AttributedMemoryWriter
import com.unuslumen.app.data.tools.registry.ToolActor
import com.unuslumen.app.data.tools.registry.ToolProgressSink
import com.unuslumen.app.domain.memory.Conversation
import com.unuslumen.app.domain.memory.ConversationMessage
import com.unuslumen.app.domain.memory.ConversationThread
import com.unuslumen.app.domain.memory.FactListing
import com.unuslumen.app.domain.memory.MemoryFact
import com.unuslumen.app.domain.memory.MemoryRepository
import com.unuslumen.app.domain.memory.RetrievedContext
import com.unuslumen.app.domain.memory.ToolResultSearchHit
import com.unuslumen.app.domain.model.AiMessage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest
import java.util.Collections

/**
 * Who wrote a memory, proved on the JVM.
 *
 * THE DEFECT THIS CLOSES. GURU and every spawned agent write into one Cerebrum
 * brain. `BrainService.storeFact` has always accepted a role, a conversation id
 * and a message id and turned them into the row's provenance label, and nothing
 * on the tool path ever passed any of them: `persistFacts` called it with one
 * argument and the other three took their empty defaults. So the label read
 * `guru.role= conversation= message=` for every fact in the brain, and an agent
 * contradicting GURU was indistinguishable in storage from GURU changing its mind.
 *
 * WHAT THESE TESTS HOLD.
 *  - an agent's write carries the agent's own id and name;
 *  - GURU's write carries "guru" and the real conversation;
 *  - a call nobody attributed writes exactly the empty label the app wrote before,
 *    so no existing memory changes meaning;
 *  - the deterministic content id is untouched by attribution, because identity
 *    and authorship are different facts and conflating them would break every
 *    stored id the brain already holds.
 *
 * The fake below implements [AttributedMemoryWriter], which is the capability the
 * real repository has. A fake that did not would let these tests pass while the
 * feature did nothing, which is the failure mode the whole change exists to avoid.
 */
class MemoryAttributionTest {

    private fun brainIdFor(content: String): String {
        val contentBytes = content.toByteArray(Charsets.UTF_8)
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(contentBytes)
        digest.update(":".toByteArray(Charsets.US_ASCII))
        digest.update(contentBytes.size.toString().toByteArray(Charsets.US_ASCII))
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** One write, as it reached the store. */
    private data class RecordedWrite(
        val factText: String,
        val actor: ToolActor,
    )

    /**
     * A brain that records the actor of every write.
     *
     * `writes` is the assertion surface: it holds what the store was actually told,
     * not what the tool believed it was sending.
     */
    private class RecordingBrain : MemoryRepository, AttributedMemoryWriter {
        // Synchronised because the concurrency test drives two writes at once, and an
        // unsynchronised map there would be testing this fake's thread safety rather
        // than the executor's attribution.
        val stored: MutableMap<String, MemoryFact> =
            Collections.synchronizedMap(LinkedHashMap<String, MemoryFact>())
        val writes: MutableList<RecordedWrite> =
            Collections.synchronizedList(mutableListOf<RecordedWrite>())

        override suspend fun persistFacts(facts: List<MemoryFact>): List<MemoryFact> {
            // Reached only when a caller goes through the plain path. Recorded with
            // the unattributed actor so a test can prove the default is honest.
            return facts.map { one ->
                writes += RecordedWrite(one.fact, ToolActor.UNATTRIBUTED)
                store(one)
            }
        }

        override suspend fun persistFactsAs(
            facts: List<MemoryFact>,
            actor: ToolActor,
        ): List<MemoryFact> = facts.map { one ->
            writes += RecordedWrite(one.fact, actor)
            store(one)
        }

        private fun store(one: MemoryFact): MemoryFact {
            val id = digestOf(one.fact)
            val row = one.copy(id = id, domain = one.category.lowercase())
            stored[id] = row
            return row
        }

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
            writes += RecordedWrite(content, ToolActor.UNATTRIBUTED)
            return store(
                MemoryFact(
                    id = "",
                    category = domain.ifBlank { "memory" },
                    fact = content,
                    confidence = confidence,
                    extractedDate = System.currentTimeMillis(),
                    domain = domain.ifBlank { "memory" },
                    topic = topic.ifBlank { "general" },
                    subtopic = subtopic.ifBlank { "general" },
                )
            )
        }

        override suspend fun storeRecordAs(
            content: String,
            domain: String,
            topic: String,
            subtopic: String,
            provenance: String,
            rationale: String,
            relatedTo: List<String>,
            confidence: Float,
            actor: ToolActor,
        ): MemoryFact {
            writes += RecordedWrite(content, actor)
            return store(
                MemoryFact(
                    id = "",
                    category = domain.ifBlank { "memory" },
                    fact = content,
                    confidence = confidence,
                    extractedDate = System.currentTimeMillis(),
                    domain = domain.ifBlank { "memory" },
                    topic = topic.ifBlank { "general" },
                    subtopic = subtopic.ifBlank { "general" },
                )
            )
        }

        override suspend fun getAllFacts(): List<MemoryFact> = stored.values.toList()
        override suspend fun getFact(factId: String): MemoryFact? = stored[factId]
        override suspend fun listFacts(category: String?): FactListing {
            val all = stored.values.toList()
            val matching = if (category == null) all else all.filter { it.category == category }
            return FactListing(matching, all.size, matching.size, brainReachable = true)
        }
        override suspend fun getFactsByCategory(category: String): List<MemoryFact> =
            stored.values.filter { it.category.equals(category, ignoreCase = true) }
        override suspend fun searchFacts(query: String, category: String?): List<MemoryFact> =
            stored.values.filter { it.fact.contains(query, ignoreCase = true) }
        override suspend fun deleteFact(factId: String) { stored.remove(factId) }
        override suspend fun markFactRecalled(factId: String) = Unit

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

    private fun payloadOf(result: com.unuslumen.app.data.tools.registry.ToolExecutionResult): String =
        if (result.success) result.rawJson else (result.error ?: "")

    @Test
    fun `a spawned agent's fact carries its own id and name`() = runBlocking {
        val brain = RecordingBrain()
        val executor = MemoryToolExecutor(brain)
        val agentActor = ToolActor.agent(
            agentId = "00000000-0000-0000-0000-000000000006",
            agentName = "Code Engineer",
            conversationId = "conv-abc",
            messageId = "msg-123",
        )

        val result = executor.executeAsActor(
            MemoryToolDefinitions.ADD_MEMORY_FACT,
            mapOf("fact" to "the build fails without the SDK path", "category" to "lesson"),
            agentActor,
            ToolProgressSink.NONE,
        )

        assertTrue("the write must succeed: ${payloadOf(result)}", result.success)
        assertEquals("exactly one write must reach the brain", 1, brain.writes.size)
        val write = brain.writes.single()
        assertEquals(ToolActor.KIND_AGENT, write.actor.kind)
        assertEquals("00000000-0000-0000-0000-000000000006", write.actor.agentId)
        assertEquals("Code Engineer", write.actor.agentName)
        assertEquals("conv-abc", write.actor.conversationId)
        assertEquals("msg-123", write.actor.messageId)
        assertEquals(
            "the role must lead with the STABLE ID, so two agents sharing a name are two agents",
            "agent:00000000-0000-0000-0000-000000000006:Code%20Engineer",
            write.actor.roleLabel(),
        )
    }

    @Test
    fun `GURU's own fact carries the guru role and the real conversation`() = runBlocking {
        val brain = RecordingBrain()
        val executor = MemoryToolExecutor(brain)
        val guruActor = ToolActor.guru(conversationId = "conv-xyz", messageId = "msg-999")

        val result = executor.executeAsActor(
            MemoryToolDefinitions.ADD_MEMORY_FACT,
            mapOf("fact" to "the human prefers tea to coffee", "category" to "user_preference"),
            guruActor,
            ToolProgressSink.NONE,
        )

        assertTrue("the write must succeed: ${payloadOf(result)}", result.success)
        val write = brain.writes.single()
        assertEquals(ToolActor.KIND_GURU, write.actor.kind)
        assertEquals("guru", write.actor.roleLabel())
        assertEquals("conv-xyz", write.actor.conversationId)
        assertEquals("msg-999", write.actor.messageId)
        assertEquals(
            "an agent id on a guru write would be a lie",
            "",
            write.actor.agentId,
        )
    }

    /**
     * The default path. A caller that was never taught to attribute must produce
     * the SAME provenance the app produced before attribution existed, or every
     * memory written before this change would read differently from every one
     * written after it.
     */
    @Test
    fun `an unattributed call writes the empty actor and claims to be nobody`() = runBlocking {
        val brain = RecordingBrain()
        val executor = MemoryToolExecutor(brain)

        val result = executor.execute(
            MemoryToolDefinitions.ADD_MEMORY_FACT,
            mapOf("fact" to "an unattributed fact", "category" to "knowledge"),
        )

        assertTrue("the write must succeed: ${payloadOf(result)}", result.success)
        val write = brain.writes.single()
        assertFalse("an unattributed call must not claim an identity", write.actor.isAttributed)
        assertEquals("", write.actor.roleLabel())
        assertEquals("", write.actor.provenanceSuffix())
    }

    /**
     * The actor must not touch the id.
     *
     * Identity is `sha256(content + ":" + length)`. If attribution changed the id,
     * every fact already in the brain would become unreachable by the id its owner
     * was given, which is issue 201 all over again with a different cause.
     */
    @Test
    fun `attribution does not move the deterministic content id`() = runBlocking {
        val brain = RecordingBrain()
        val executor = MemoryToolExecutor(brain)
        val content = "the fire door must never be propped open"

        executor.executeAsActor(
            MemoryToolDefinitions.ADD_MEMORY_FACT,
            mapOf("fact" to content, "category" to "knowledge"),
            ToolActor.agent(agentId = "agent-id", agentName = "Sentinel", conversationId = "c", messageId = "m"),
            ToolProgressSink.NONE,
        )

        val expectedId = brainIdFor(content)
        assertTrue(
            "the brain's own id must be the only id stored, got: ${brain.stored.keys}",
            brain.stored.containsKey(expectedId),
        )
        assertEquals(1, brain.stored.size)
    }

    /**
     * `cortexRecord` keeps the model's own provenance and appends the actor.
     *
     * The model's provenance ("conversation", "inference", "tool_result") is a
     * real fact about where a memory came from. Replacing it to gain attribution
     * would trade one truth for another, so both travel.
     */
    @Test
    fun `cortexRecord carries the actor alongside the model's provenance`() = runBlocking {
        val brain = RecordingBrain()
        val executor = MemoryToolExecutor(brain)

        val result = executor.executeAsActor(
            MemoryToolDefinitions.CORTEX_RECORD,
            mapOf(
                "content" to "the supplier ships on tuesdays",
                "domain" to "work",
                "topic" to "suppliers",
                "subtopic" to "schedule",
                "provenance" to "conversation",
            ),
            ToolActor.agent(agentId = "agent-7", agentName = "Grafter", conversationId = "c1", messageId = "m1"),
            ToolProgressSink.NONE,
        )

        assertTrue("the record must be written: ${payloadOf(result)}", result.success)
        val write = brain.writes.single()
        assertEquals(ToolActor.KIND_AGENT, write.actor.kind)
        assertEquals("agent-7", write.actor.agentId)
        assertEquals(
            "the suffix must name the agent id first, which is what resolves to a row",
            "actor=agent:agent-7:Grafter",
            write.actor.provenanceSuffix(),
        )
    }

    /**
     * AN UNATTRIBUTED CALL still works on a store with no provenance field.
     *
     * That is the honest half of the fallback: nobody claimed an identity, so there is
     * nothing to lose by writing anonymously. Every read-only surface and every test
     * fake in the app is this case.
     */
    @Test
    fun `an unattributed call still stores on a store without attribution support`() = runBlocking {
        val plainBrain = object : MemoryRepository by RecordingBrain() {
            // Deliberately does NOT implement AttributedMemoryWriter.
        }
        val executor = MemoryToolExecutor(plainBrain)

        val result = executor.execute(
            MemoryToolDefinitions.ADD_MEMORY_FACT,
            mapOf("fact" to "a fact on a store with no provenance field", "category" to "knowledge"),
        )

        assertTrue("the fact must still be stored: ${payloadOf(result)}", result.success)
        assertEquals(
            "the plain persistFacts path must have carried it",
            1,
            plainBrain.getTotalFacts(),
        )
    }

    /**
     * AN ATTRIBUTED WRITE FAILS CLOSED when identity cannot be recorded.
     *
     * The first shape of this fell back to an anonymous write. An agent would then
     * receive success while its identity went nowhere, and the entire point of the
     * change is that a stored memory says which intelligence wrote it. A silent
     * anonymous write is the failure mode dressed as a success.
     */
    @Test
    fun `an attributed fact is REFUSED when the store cannot record attribution`() = runBlocking {
        val plainBrain = object : MemoryRepository by RecordingBrain() {
            // Deliberately does NOT implement AttributedMemoryWriter.
        }
        val executor = MemoryToolExecutor(plainBrain)

        val result = executor.executeAsActor(
            MemoryToolDefinitions.ADD_MEMORY_FACT,
            mapOf("fact" to "a fact an agent wanted attributed", "category" to "knowledge"),
            ToolActor.agent(agentId = "a", agentName = "A", conversationId = "c", messageId = "m"),
            ToolProgressSink.NONE,
        )

        assertFalse("an unattributable write must not report success: ${payloadOf(result)}", result.success)
        assertTrue(
            "the refusal must say why: ${payloadOf(result)}",
            payloadOf(result).contains("cannot record attribution", ignoreCase = true),
        )
        assertEquals(
            "NOTHING may be stored, or the memory exists without its author",
            0,
            plainBrain.getTotalFacts(),
        )
    }

    /** The same rule for a typed record. */
    @Test
    fun `an attributed cortex record is REFUSED when the store cannot record attribution`() = runBlocking {
        val plainBrain = object : MemoryRepository by RecordingBrain() {
            // Deliberately does NOT implement AttributedMemoryWriter.
        }
        val executor = MemoryToolExecutor(plainBrain)

        val result = executor.executeAsActor(
            MemoryToolDefinitions.CORTEX_RECORD,
            mapOf("content" to "a typed record an agent wanted attributed", "domain" to "work"),
            ToolActor.agent(agentId = "a", agentName = "A", conversationId = "c", messageId = "m"),
            ToolProgressSink.NONE,
        )

        assertFalse("it must not report success: ${payloadOf(result)}", result.success)
        assertEquals("nothing may be stored", 0, plainBrain.getTotalFacts())
    }

    /**
     * An agent with NO ID is refused ON THE PRODUCTION PATH TOO.
     *
     * THE FIRST VERSION OF THIS TEST USED A STORE WITHOUT ATTRIBUTION SUPPORT, which is
     * the one repository in the app that is not the real one. The production store DOES
     * implement the attributed writer, so the same actor sailed through it, and
     * `roleLabel()` wrote a bare "agent" that names nobody. The check was on the wrong
     * side of the branch.
     *
     * This version uses RecordingBrain, which is the capable store, so it exercises the
     * path production actually takes.
     */
    @Test
    fun `an attributed call with no id is refused on the production store`() = runBlocking {
        val brain = RecordingBrain()
        val executor = MemoryToolExecutor(brain)

        val result = executor.executeAsActor(
            MemoryToolDefinitions.ADD_MEMORY_FACT,
            mapOf("fact" to "a fact claimed by nobody in particular", "category" to "knowledge"),
            ToolActor(kind = ToolActor.KIND_AGENT),
            ToolProgressSink.NONE,
        )

        assertFalse("it must not report success: ${payloadOf(result)}", result.success)
        assertTrue(
            "the refusal must name the missing id: ${payloadOf(result)}",
            payloadOf(result).contains("no id", ignoreCase = true),
        )
        assertEquals("and NOTHING may reach the brain", 0, brain.writes.size)
    }

    /** The same refusal on a store that cannot record attribution. */
    @Test
    fun `an attributed call with no id is refused on an incapable store too`() = runBlocking {
        val plainBrain = object : MemoryRepository by RecordingBrain() {
            // Deliberately does NOT implement AttributedMemoryWriter.
        }
        val executor = MemoryToolExecutor(plainBrain)

        val result = executor.executeAsActor(
            MemoryToolDefinitions.ADD_MEMORY_FACT,
            mapOf("fact" to "a fact claimed by nobody in particular", "category" to "knowledge"),
            ToolActor(kind = ToolActor.KIND_AGENT),
            ToolProgressSink.NONE,
        )

        assertFalse("it must not report success: ${payloadOf(result)}", result.success)
    }

    /** cortexRecord obeys the same rule on the production store. */
    @Test
    fun `an attributed cortex record with no id is refused on the production store`() = runBlocking {
        val brain = RecordingBrain()
        val executor = MemoryToolExecutor(brain)

        val result = executor.executeAsActor(
            MemoryToolDefinitions.CORTEX_RECORD,
            mapOf("content" to "a typed record from an unnamed agent", "domain" to "work"),
            ToolActor(kind = ToolActor.KIND_AGENT),
            ToolProgressSink.NONE,
        )

        assertFalse("it must not report success: ${payloadOf(result)}", result.success)
        assertEquals("nothing may reach the brain", 0, brain.writes.size)
    }

    /** An unknown actor kind is refused rather than written as an empty label. */
    @Test
    fun `an unknown actor kind is refused`() = runBlocking {
        val brain = RecordingBrain()
        val executor = MemoryToolExecutor(brain)

        val result = executor.executeAsActor(
            MemoryToolDefinitions.ADD_MEMORY_FACT,
            mapOf("fact" to "a fact from an identity nobody defined", "category" to "knowledge"),
            ToolActor(kind = "somethingelse", agentId = "an-id"),
            ToolProgressSink.NONE,
        )

        assertFalse("it must not report success: ${payloadOf(result)}", result.success)
        assertTrue(
            "the refusal must name the unknown kind: ${payloadOf(result)}",
            payloadOf(result).contains("unknown identity", ignoreCase = true),
        )
        assertEquals("nothing may reach the brain", 0, brain.writes.size)
    }

    /** GURU is always a usable identity, so a GURU write is never refused for lack of an id. */
    @Test
    fun `a guru write is never refused for missing an agent id`() = runBlocking {
        val brain = RecordingBrain()
        val executor = MemoryToolExecutor(brain)

        val result = executor.executeAsActor(
            MemoryToolDefinitions.ADD_MEMORY_FACT,
            mapOf("fact" to "guru is allowed to write memory", "category" to "knowledge"),
            ToolActor.guru(conversationId = "c", messageId = "m"),
            ToolProgressSink.NONE,
        )

        assertTrue("a guru write must succeed: ${payloadOf(result)}", result.success)
        assertEquals(1, brain.writes.size)
    }

    /**
     * The actor does not survive the call.
     *
     * If it did, a later unattributed write would inherit whichever agent happened
     * to call last, which is exactly the kind of silent misattribution this change
     * exists to prevent.
     */
    @Test
    fun `an unattributed call after an attributed one records nobody`() = runBlocking {
        val brain = RecordingBrain()
        val executor = MemoryToolExecutor(brain)

        executor.executeAsActor(
            MemoryToolDefinitions.ADD_MEMORY_FACT,
            mapOf("fact" to "first, attributed to an agent", "category" to "knowledge"),
            ToolActor.agent(agentId = "agent-1", agentName = "One", conversationId = "c", messageId = "m"),
            ToolProgressSink.NONE,
        )
        executor.execute(
            MemoryToolDefinitions.ADD_MEMORY_FACT,
            mapOf("fact" to "second, attributed to nobody", "category" to "knowledge"),
        )

        assertEquals("two writes must have landed", 2, brain.writes.size)
        assertEquals(ToolActor.KIND_AGENT, brain.writes[0].actor.kind)
        assertFalse(
            "the second call must not inherit the first call's identity",
            brain.writes[1].actor.isAttributed,
        )
    }

    /**
     * The exact strings, character for character.
     *
     * Pinned here because the provenance label is the only place attribution is
     * visible, and a shape that drifts silently is a label nothing can read. The
     * other half of the label is built in BrainService:
     *
     *   "guru.role=$role conversation=$conversationId message=$messageId"
     *   then provenanceLabelFor() appends "|confidence=%.4f"
     *
     * so the three complete labels this change produces are asserted below.
     */
    @Test
    fun `the provenance label is exact for guru, an agent and nobody`() {
        val guru = ToolActor.guru(conversationId = "conv-xyz", messageId = "msg-999")
        val agent = ToolActor.agent(
            agentId = "ed267bed-940d-4add-a781-876b9d62548c",
            agentName = "Code Engineer",
            conversationId = "conv-abc",
            messageId = "msg-123",
        )

        assertEquals("guru", guru.roleLabel())
        assertEquals("agent:ed267bed-940d-4add-a781-876b9d62548c:Code%20Engineer", agent.roleLabel())
        assertEquals("", ToolActor.UNATTRIBUTED.roleLabel())

        // Composed the way BrainService.storeFact composes it.
        assertEquals(
            "guru.role=guru conversation=conv-xyz message=msg-999|confidence=0.9000",
            "guru.role=${guru.roleLabel()} conversation=${guru.conversationId} " +
                "message=${guru.messageId}|confidence=0.9000",
        )
        assertEquals(
            "guru.role=agent:ed267bed-940d-4add-a781-876b9d62548c:Code%20Engineer " +
                "conversation=conv-abc message=msg-123|confidence=0.9000",
            "guru.role=${agent.roleLabel()} conversation=${agent.conversationId} " +
                "message=${agent.messageId}|confidence=0.9000",
        )
        // The unattributed label is byte-identical to what the app wrote before this
        // change, which is what keeps every existing memory's meaning intact.
        assertEquals(
            "guru.role= conversation= message=|confidence=0.9000",
            "guru.role=${ToolActor.UNATTRIBUTED.roleLabel()} " +
                "conversation=${ToolActor.UNATTRIBUTED.conversationId} " +
                "message=${ToolActor.UNATTRIBUTED.messageId}|confidence=0.9000",
        )

        // The cortexRecord suffix, appended to whatever the model said. GURU carries
        // "actor=guru" rather than nothing, because a fact the main assistant wrote is
        // attributed too: the point is that no write is anonymous.
        assertEquals("actor=guru", guru.provenanceSuffix())
        assertEquals(
            "actor=agent:ed267bed-940d-4add-a781-876b9d62548c:Code%20Engineer",
            agent.provenanceSuffix(),
        )
        // Only a call nobody claimed appends nothing, which is what preserves the
        // byte-identical label for every caller that was never taught about actors.
        assertEquals("", ToolActor.UNATTRIBUTED.provenanceSuffix())
    }

    /**
     * THE HAZARD THAT WAS THERE AND IS NOW FIXED.
     *
     * The first shape of this label was `agent:<display name>`. The name can contain
     * spaces, and the label is a space-delimited key=value string, so
     * `guru.role=agent:Code Engineer conversation=...` split on whitespace into a
     * different field count than it claimed. Worse, two agents sharing a display name
     * were one agent in storage.
     *
     * Both are fixed by putting the stable id first and escaping the name, so this test
     * now asserts the LABEL IS PARSEABLE rather than warning that it is not.
     */
    @Test
    fun `the agent role label is parseable and carries the stable id first`() {
        val agent = ToolActor.agent(
            agentId = "ed267bed-940d-4add-a781-876b9d62548c",
            agentName = "Code Engineer",
            conversationId = "c",
            messageId = "m",
        )

        val label = agent.roleLabel()
        assertEquals("agent:ed267bed-940d-4add-a781-876b9d62548c:Code%20Engineer", label)

        // No raw whitespace anywhere, so a key=value reader cannot gain a field.
        assertFalse("the label must contain no raw space: $label", label.contains(' '))

        // The id is the identity and comes before the name.
        val parts = label.split(':')
        assertEquals("agent", parts[0])
        assertEquals("ed267bed-940d-4add-a781-876b9d62548c", parts[1])

        // The escaping is reversible, and reading it back gives the name a person knows.
        assertEquals("Code Engineer", ToolActor.unescapeSegment(parts[2]))
    }

    /** A name containing a slash or a colon must not break the field structure either. */
    @Test
    fun `an agent name with punctuation is escaped and round-trips`() {
        val agent = ToolActor.agent(agentId = "id", agentName = "Ops/Triage: v2")
        val label = agent.roleLabel()

        assertFalse("no raw space: $label", label.contains(' '))
        assertEquals(
            "the slash and colon must both be encoded, or a reader splits on them",
            "agent:id:Ops%2FTriage%3A%20v2",
            label,
        )
        val name = label.split(':').drop(2).joinToString(":")
        assertEquals("Ops/Triage: v2", ToolActor.unescapeSegment(name))
    }

    /** A name that is already safe must not be re-encoded, so labels stay readable. */
    @Test
    fun `a simple name passes through unescaped`() {
        val agent = ToolActor.agent(agentId = "id", agentName = "sentinel-2.1")
        assertEquals("agent:id:sentinel-2.1", agent.roleLabel())
        assertEquals("sentinel-2.1", ToolActor.unescapeSegment("sentinel-2.1"))
    }

    /**
     * ACCENTED NAMES ROUND-TRIP EXACTLY.
     *
     * The first encoder walked UTF-8 BYTES and asked `isLetterOrDigit` on each byte's
     * numeric value. Byte 0xE9, the lead byte of a three-byte character like 'é', is the
     * Char U+00E9, which that predicate calls a letter, so the byte was appended raw and
     * a multi-byte character came out half-escaped and unreconstructable. For an
     * application meant for people worldwide, an accented name is ordinary, not an edge
     * case.
     */
    @Test
    fun `an accented agent name round-trips exactly`() {
        val name = "Rédacteur Élite"
        val agent = ToolActor.agent(agentId = "id", agentName = name)
        val label = agent.roleLabel()

        assertFalse("no raw space may survive: $label", label.contains(' '))
        assertTrue(
            "every non-ASCII byte must be percent-encoded, so the label is pure ASCII: $label",
            label.all { it.code < 128 },
        )
        val encoded = label.split(':').drop(2).joinToString(":")
        assertEquals(name, ToolActor.unescapeSegment(encoded))
    }

    @Test
    fun `an emoji in an agent name round-trips exactly`() {
        val name = "Ops 🛠️ Team"
        val agent = ToolActor.agent(agentId = "id", agentName = name)
        val label = agent.roleLabel()

        assertFalse("no raw space: $label", label.contains(' '))
        assertTrue("pure ASCII label: $label", label.all { it.code < 128 })
        val encoded = label.split(':').drop(2).joinToString(":")
        assertEquals(name, ToolActor.unescapeSegment(encoded))
    }

    @Test
    fun `a CJK agent name round-trips exactly`() {
        val name = "運用チーム"
        val agent = ToolActor.agent(agentId = "id", agentName = name)
        val label = agent.roleLabel()

        assertTrue("pure ASCII label: $label", label.all { it.code < 128 })
        val encoded = label.split(':').drop(2).joinToString(":")
        assertEquals(name, ToolActor.unescapeSegment(encoded))
    }

    /**
     * Two writes in a row, each keeping its own identity.
     *
     * HONEST NOTE ON WHAT THIS DOES NOT PROVE. A `two concurrent attributed writes
     * keep their own identities` test was written and then deleted, because it passed
     * against a deliberately broken implementation that held the actor in a mutable
     * field. The reason it could not discriminate is structural: the actor is read as
     * an argument at the moment `persistFactsAs` is called, and there is no suspension
     * point between a field being written and being read on this path, so a
     * single-threaded interleaving cannot be forced without injecting a test hook into
     * production code.
     *
     * A test that passes on broken code is worse than no test, so it is gone. The
     * property it aimed at is instead guaranteed by construction: the actor is a
     * parameter on `executeAsActor`, threaded to the two write branches and nowhere
     * else, so there is no shared mutable slot to cross over. What IS tested here is
     * the sequential half, which does discriminate: an attributed call followed by an
     * unattributed one must not carry the identity forward, and that fails against any
     * implementation that keeps the actor in a field without clearing it.
     */
    @Test
    fun `two sequential writes keep their own identities`() = runBlocking {
        val brain = RecordingBrain()
        val executor = MemoryToolExecutor(brain)

        executor.executeAsActor(
            MemoryToolDefinitions.ADD_MEMORY_FACT,
            mapOf("fact" to "written by the first agent", "category" to "knowledge"),
            ToolActor.agent(agentId = "agent-one", agentName = "One", conversationId = "c1", messageId = "m1"),
            ToolProgressSink.NONE,
        )
        executor.executeAsActor(
            MemoryToolDefinitions.ADD_MEMORY_FACT,
            mapOf("fact" to "written by the second agent", "category" to "knowledge"),
            ToolActor.agent(agentId = "agent-two", agentName = "Two", conversationId = "c2", messageId = "m2"),
            ToolProgressSink.NONE,
        )

        assertEquals("both writes must have landed", 2, brain.writes.size)
        val first = brain.writes.single { it.factText == "written by the first agent" }
        val second = brain.writes.single { it.factText == "written by the second agent" }
        assertEquals("agent-one", first.actor.agentId)
        assertEquals("agent-two", second.actor.agentId)
    }
}
