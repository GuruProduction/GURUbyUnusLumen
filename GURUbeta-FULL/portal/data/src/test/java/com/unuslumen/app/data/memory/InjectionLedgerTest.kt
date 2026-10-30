// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.memory

import com.unuslumen.app.database.dao.LanePrecision
import com.unuslumen.app.database.dao.MemoryInjectionDao
import com.unuslumen.app.database.entity.MemoryInjectionEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * InjectionLedger tests.
 *
 * These exist because the ledger shipped once and silently recorded nothing. The
 * constructor took the ledger as a nullable parameter with a null default, the DI
 * factory passed only two arguments, and Koin resolves a missing nullable to the
 * default rather than failing. The table was created, the schema version was
 * bumped, and every row count stayed at zero. Nothing in the build or the tests
 * noticed, because nothing asserted that a row was written.
 *
 * The lesson encoded here: a nullable collaborator with a default is a feature
 * flag that is off by default, and if nothing tests the on state, off is what
 * ships.
 */
class InjectionLedgerTest {

    /** In-memory stand-in that records what the ledger asked it to store. */
    private class FakeDao : MemoryInjectionDao {
        val rows = mutableListOf<MemoryInjectionEntity>()
        val confirmed = mutableListOf<Pair<String, String>>()
        val restated = mutableListOf<Triple<String, String, Boolean>>()

        override suspend fun insertAll(rows: List<MemoryInjectionEntity>) {
            this.rows += rows
        }

        override suspend fun insert(row: MemoryInjectionEntity) {
            rows += row
        }

        override suspend fun forConversation(conversationId: String) =
            rows.filter { it.conversationId == conversationId }

        override suspend fun recent(limit: Int) = rows.takeLast(limit).reversed()

        override suspend fun countFor(conversationId: String, memoryId: String) =
            rows.count { it.conversationId == conversationId && it.memoryId == memoryId }

        override suspend fun markConfirmedUsed(
            conversationId: String,
            memoryId: String,
            whenMillis: Long,
        ) {
            confirmed += conversationId to memoryId
        }

        // Derived from `rows`, matching what the real SQL does. An earlier version
        // read a separate list that nothing ever appended to, so the ledger's
        // duplicate guard could never fire and the test failed on correct
        // production code. The fake must mirror the query, not approximate it.
        override suspend fun countRestated(conversationId: String, memoryId: String) =
            rows.count {
                it.conversationId == conversationId &&
                    it.memoryId == memoryId &&
                    it.restatedWithoutInjection
            }

        override suspend fun precisionByLane(sinceMillis: Long): List<LanePrecision> =
            emptyList()

        override suspend fun recallFailuresSince(sinceMillis: Long) =
            rows.count { it.restatedWithoutInjection }

        override suspend fun pruneOlderThan(timestamp: Long) {}
        override suspend fun pruneExcessCount(keepCount: Int) {}
        override suspend fun count() = rows.size
    }

    @Test
    fun `recording injections persists every lane with its rank`() = runBlocking {
        val dao = FakeDao()
        val ledger = InjectionLedger(dao)

        ledger.recordInjections(
            query = "where is the spare key",
            conversationId = "conv-1",
            injections = listOf(
                InjectedMemory("aaa", MemoryInjectionEntity.LANE_FACT, 1, 0.91f, "key in kitchen"),
                InjectedMemory("bbb", MemoryInjectionEntity.LANE_FACT, 2, 0.44f, "key is spare"),
                InjectedMemory("ccc", MemoryInjectionEntity.LANE_MESSAGE, 1, 0f, "user asked earlier"),
            ),
        )

        assertEquals("all three lanes must be recorded", 3, dao.rows.size)
        assertEquals("conv-1", dao.rows.first().conversationId)
        assertEquals(1, dao.rows.first().rankInLane)
        assertEquals(0.91f, dao.rows.first().score, 0.0001f)
        assertTrue(
            "a rank-2 hit must keep rank 2, not collapse to 1",
            dao.rows.any { it.memoryId == "bbb" && it.rankInLane == 2 },
        )
        assertFalse(
            "a normal injection is not a recall failure",
            dao.rows.any { it.restatedWithoutInjection },
        )
    }

    @Test
    fun `an empty injection list writes nothing`() = runBlocking {
        val dao = FakeDao()
        val ledger = InjectionLedger(dao)

        ledger.recordInjections(query = "hello", conversationId = "conv-1", injections = emptyList())

        assertEquals(0, dao.rows.size)
    }

    @Test
    fun `the query is capped so one huge message cannot bloat the ledger`() = runBlocking {
        val dao = FakeDao()
        val ledger = InjectionLedger(dao)

        ledger.recordInjections(
            query = "x".repeat(MemoryInjectionEntity.MAX_QUERY_CHARS + 5000),
            conversationId = "conv-1",
            injections = listOf(InjectedMemory("aaa", MemoryInjectionEntity.LANE_FACT, 1, 1f, "c")),
        )

        assertEquals(
            MemoryInjectionEntity.MAX_QUERY_CHARS,
            dao.rows.first().query.length,
        )
    }

    @Test
    fun `a restated memory the preamble missed is recorded once per conversation`() = runBlocking {
        val dao = FakeDao()
        val ledger = InjectionLedger(dao)

        ledger.recordRecallFailure("i told you the key", "conv-1", "aaa", 0.8f)
        ledger.recordRecallFailure("the key, like i said", "conv-1", "aaa", 0.8f)

        assertEquals(
            "restating the same fact twice is one retrieval failure, not two",
            1,
            dao.rows.count { it.restatedWithoutInjection },
        )
        assertEquals(InjectionLedger.LANE_RESTATED, dao.rows.first().lane)
    }

    @Test
    fun `missed relevant memories become recall failures only when not already injected`() =
        runBlocking {
            val dao = FakeDao()
            val ledger = InjectionLedger(dao)

            ledger.recordMissedRelevant(
                query = "boiler",
                conversationId = "conv-1",
                relevant = listOf(
                    InjectedMemory("injected", MemoryInjectionEntity.LANE_FACT, 1, 0.9f, "a"),
                    InjectedMemory("missed", MemoryInjectionEntity.LANE_FACT, 2, 0.8f, "b"),
                ),
                injectedIds = setOf("injected"),
            )

            val failures = dao.rows.filter { it.restatedWithoutInjection }
            assertEquals("exactly one memory was missed", 1, failures.size)
            assertEquals("missed", failures.first().memoryId)
        }

    @Test
    fun `a dao failure never propagates to the send path`() = runBlocking {
        val exploding = object : MemoryInjectionDao by FakeDao() {
            override suspend fun insertAll(rows: List<MemoryInjectionEntity>) {
                throw IllegalStateException("disk full")
            }
        }
        val ledger = InjectionLedger(exploding)

        // Must not throw: the ledger is best-effort and a write failure cannot be
        // allowed to fail a conversation turn.
        ledger.recordInjections(
            query = "anything",
            conversationId = "conv-1",
            injections = listOf(InjectedMemory("aaa", MemoryInjectionEntity.LANE_FACT, 1, 1f, "c")),
        )
    }
}
