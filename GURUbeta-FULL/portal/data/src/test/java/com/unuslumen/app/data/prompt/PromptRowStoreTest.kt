// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.prompt

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.unuslumen.app.database.entity.PromptRowEntity
import com.unuslumen.app.database.entity.PromptRowVersionEntity
import com.unuslumen.app.database.guruDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The prompt store, against a real database.
 *
 * THE THREE THINGS THIS FILE EXISTS FOR, all of which are about not losing somebody's words:
 * a write built on a stale version must be refused rather than applied over newer text; every
 * write must keep the text it replaced; and a reset must be recoverable rather than being the
 * one action in the room that destroys work.
 *
 * It runs on real SQLite through Robolectric because the behaviour under test IS the
 * transaction: the history insert, the guarded update and the rollback must land together or
 * not at all, and a fake would prove only that the fake agrees with itself.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PromptRowStoreTest {

    private lateinit var database: guruDatabase
    private lateinit var store: PromptRowStore

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            guruDatabase::class.java,
        ).allowMainThreadQueries().build()
        store = PromptRowStore(database, database.promptRowDao())
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun row(
        slug: String = "guru-identity",
        content: String = "You are GURU.",
        version: Int = 1,
        deliveryMode: String = PromptRowEntity.DELIVERY_ALWAYS,
        isActive: Boolean = true,
    ) = PromptRowEntity(
        id = "row-$slug",
        slug = slug,
        category = "identity",
        name = "Identity",
        content = content,
        deliveryMode = deliveryMode,
        triggerKeywordsJson = "[]",
        isActive = isActive,
        version = version,
        createdAt = 1,
        updatedAt = 1,
        isEdited = false,
        syncedVersion = version,
    )

    /** A fresh row can be read back by its slug. */
    @Test
    fun aRowIsReadableByItsSlug() = runBlocking {
        database.promptRowDao().putRow(row())
        assertEquals("You are GURU.", store.bySlug("guru-identity")?.content)
    }

    /** An edit lands, bumps the version and keeps the version it replaced. */
    @Test
    fun anEditBumpsTheVersionAndKeepsTheTextItReplaced() = runBlocking {
        database.promptRowDao().putRow(row())

        val outcome = store.updateContent("guru-identity", expectedVersion = 1, newContent = "You are GURU, and you are careful.")

        assertTrue("the write landed: $outcome", outcome is PromptRowStore.WriteOutcome.Applied)
        outcome as PromptRowStore.WriteOutcome.Applied
        assertEquals(2, outcome.version)

        val stored = store.bySlug("guru-identity")!!
        assertEquals("You are GURU, and you are careful.", stored.content)
        assertTrue("the owner edited this row", stored.isEdited)

        val history = store.history(stored.id)
        assertEquals("the previous text is kept", 1, history.size)
        assertEquals(1, history.single().version)
        assertEquals("You are GURU.", history.single().content)
    }

    /**
     * THE DEFECT THIS FILE EXISTS FOR. A write built on a stale version is refused.
     *
     * Two people read version 1. The first writes, which makes version 2. The second writes
     * against version 1, and if that landed it would replace words nobody read. It is
     * refused, the row is untouched, and the caller is told which versions disagree.
     */
    @Test
    fun aWriteOnAStaleVersionIsRefusedAndChangesNothing() = runBlocking {
        database.promptRowDao().putRow(row())

        store.updateContent("guru-identity", expectedVersion = 1, newContent = "First writer's words.")

        val second = store.updateContent("guru-identity", expectedVersion = 1, newContent = "Second writer's words.")

        assertTrue("the stale write must be refused: $second", second is PromptRowStore.WriteOutcome.Stale)
        second as PromptRowStore.WriteOutcome.Stale
        assertEquals("it says what it expected", 1, second.expectedVersion)
        assertEquals("and what the row actually holds", 2, second.actualVersion)

        assertEquals(
            "the first writer's words survive",
            "First writer's words.",
            store.bySlug("guru-identity")?.content,
        )
        assertEquals(
            "a refused write leaves no history row for a change that never happened",
            1,
            store.history("row-guru-identity").size,
        )
    }

    /** Editing a row that is not there is a named absence, not a silent success. */
    @Test
    fun editingAMissingRowIsReportedAsMissing() = runBlocking {
        val outcome = store.updateContent("nope", expectedVersion = 1, newContent = "x")
        assertTrue(outcome is PromptRowStore.WriteOutcome.Missing)
    }

    /**
     * A reset keeps the override and returns the row to the server's text.
     *
     * A RESET MUST NOT BE THE ONE ACTION THAT DESTROYS WORK. The owner's text is written
     * into history before the synced text returns.
     */
    @Test
    fun aResetKeepsTheOverrideInHistoryAndRestoresTheSyncedText() = runBlocking {
        database.promptRowDao().putRow(row())
        store.updateContent("guru-identity", expectedVersion = 1, newContent = "My own words.")

        val reset = store.resetToSynced("guru-identity", expectedVersion = 2)

        assertTrue("the reset landed: $reset", reset is PromptRowStore.WriteOutcome.Applied)
        val stored = store.bySlug("guru-identity")!!
        assertEquals("the server's text is back", "You are GURU.", stored.content)
        assertFalse("and the row is no longer marked edited", stored.isEdited)

        val history = store.history(stored.id)
        assertTrue(
            "the override is recoverable from history: $history",
            history.any { it.content == "My own words." },
        )
        assertTrue(
            "and the history says it was a reset",
            history.any { it.writtenBy == PromptRowVersionEntity.WRITTEN_BY_RESET },
        )
    }

    /** A reset on an untouched row is a no-op reported as such, not a failure. */
    @Test
    fun resettingAnUneditedRowReportsItIsAlreadySynced() = runBlocking {
        database.promptRowDao().putRow(row())
        val outcome = store.resetToSynced("guru-identity", expectedVersion = 1)
        assertTrue(outcome is PromptRowStore.WriteOutcome.Applied)
        outcome as PromptRowStore.WriteOutcome.Applied
        assertEquals("the version did not move", 1, outcome.version)
    }

    /**
     * A sync never overwrites a row the owner has edited.
     *
     * THE OWNER'S WORDS OUTRANK THE SERVER'S. The server's copy is the one that has fallen
     * behind their intent, so a sync reports that it skipped the row and leaves the text.
     */
    @Test
    fun aSyncDoesNotOverwriteAnEditedRow() = runBlocking {
        database.promptRowDao().putRow(row())
        store.updateContent("guru-identity", expectedVersion = 1, newContent = "My own words.")

        val applied = store.applyFromServer(
            slug = "guru-identity",
            id = "row-guru-identity",
            category = "identity",
            name = "Identity",
            content = "The server's newer words.",
            deliveryMode = PromptRowEntity.DELIVERY_ALWAYS,
            triggerKeywordsJson = "[]",
            matchThreshold = null,
            globalThreshold = 0.75,
            version = 9,
            isActive = true,
        )

        assertFalse("a sync must not claim it applied over an edit", applied)
        assertEquals(
            "and the owner's words survive",
            "My own words.",
            store.bySlug("guru-identity")?.content,
        )
    }

    /** A sync writes a row the device does not have, and one it has not edited. */
    @Test
    fun aSyncWritesNewRowsAndUntouchedOnes() = runBlocking {
        val created = store.applyFromServer(
            slug = "guru-voice", id = "row-voice", category = "voice", name = "Voice",
            content = "Be brief.", deliveryMode = PromptRowEntity.DELIVERY_ALWAYS,
            triggerKeywordsJson = "[]", matchThreshold = null, globalThreshold = 0.75,
            version = 1, isActive = true,
        )
        assertTrue(created)
        assertEquals("Be brief.", store.bySlug("guru-voice")?.content)

        val updated = store.applyFromServer(
            slug = "guru-voice", id = "row-voice", category = "voice", name = "Voice",
            content = "Be briefer.", deliveryMode = PromptRowEntity.DELIVERY_ALWAYS,
            triggerKeywordsJson = "[]", matchThreshold = null, globalThreshold = 0.75,
            version = 2, isActive = true,
        )
        assertTrue(updated)
        assertEquals("Be briefer.", store.bySlug("guru-voice")?.content)
        assertEquals("the version followed the server", 2, store.bySlug("guru-voice")?.version)
    }

    /** A row the server withdrew is marked inactive rather than deleted, and its text stays. */
    @Test
    fun aWithdrawalMarksTheRowInactiveAndKeepsItsText() = runBlocking {
        database.promptRowDao().putRow(row())
        store.applyFromServer(
            slug = "guru-identity", id = "row-guru-identity", category = "identity", name = "Identity",
            content = "You are GURU.", deliveryMode = PromptRowEntity.DELIVERY_ALWAYS,
            triggerKeywordsJson = "[]", matchThreshold = null, globalThreshold = 0.75,
            version = 2, isActive = false,
        )
        val stored = store.bySlug("guru-identity")!!
        assertFalse("the row is withdrawn", stored.isActive)
        assertEquals("but its text is still there", "You are GURU.", stored.content)
    }
}
