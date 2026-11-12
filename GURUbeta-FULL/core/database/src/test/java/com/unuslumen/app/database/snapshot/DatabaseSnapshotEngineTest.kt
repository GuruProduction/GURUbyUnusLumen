// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.database.snapshot

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * The snapshot engine, against a real database, with real pages in a real write-ahead log.
 *
 * WHAT THIS PROVES, AND WHY IT CANNOT BE PROVED ANY OTHER WAY.
 *
 * The defect this feature exists for was that `guru_db` had no portable copy. The tempting
 * fix is `cp guru_db`, and it is wrong in a way that only shows up under load: a database in
 * WAL mode holds committed pages in `guru_db-wal` until a checkpoint, so a file copy can
 * open cleanly, pass an integrity check, and be missing the last half hour of a person's
 * messages. This test writes rows, deliberately leaves them in the log by never closing or
 * checkpointing the writer, and then asserts the snapshot contains them. A filesystem copy
 * would fail this test, which is the entire point of having it.
 *
 * It also proves the restore path: a snapshot taken here is decrypted, installed over a
 * database that has since been changed, and the original rows come back.
 *
 * WHAT IT CANNOT PROVE, stated so nobody mistakes this for device evidence: that a real
 * handset's SQLite build behaves identically at every API level, and that the Storage Access
 * Framework route writes into a folder the owner actually chose. Those are the release
 * test's job.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DatabaseSnapshotEngineTest {

    private lateinit var context: Context
    private lateinit var engine: DatabaseSnapshotEngine

    private val databaseName = "guru_db"

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        engine = DatabaseSnapshotEngine(context)
        deleteLiveDatabase()
    }

    @After
    fun tearDown() {
        deleteLiveDatabase()
    }

    private fun databaseFile(): File = context.getDatabasePath(databaseName)

    private fun deleteLiveDatabase() {
        val live = databaseFile()
        for (suffix in listOf("", "-wal", "-shm", "-journal")) {
            val file = File(live.absolutePath + suffix)
            if (file.exists()) file.delete()
        }
    }

    /**
     * Create a database that looks enough like the brain for a snapshot to be meaningful.
     *
     * The table names are the real ones from the app's schema for the two that carry a
     * person's words, because a snapshot of a database with test-only names would prove
     * nothing about the tables that matter.
     */
    private fun createBrainWithRows(messages: Int, memoryFacts: Int): SQLiteDatabase {
        val live = databaseFile()
        live.parentFile?.mkdirs()
        val db = SQLiteDatabase.openOrCreateDatabase(live, null)

        // WAL is what the app runs with, and it is the whole reason a file copy is unsafe.
        db.rawQuery("PRAGMA journal_mode=WAL", null).use { it.moveToFirst() }

        db.execSQL("CREATE TABLE IF NOT EXISTS messages (id INTEGER PRIMARY KEY, conversation_id TEXT, content TEXT)")
        db.execSQL("CREATE TABLE IF NOT EXISTS memory_facts (id INTEGER PRIMARY KEY, fact TEXT, created_at INTEGER)")
        db.execSQL("PRAGMA user_version = ${com.unuslumen.app.database.guruDatabase.SCHEMA_VERSION}")

        db.beginTransaction()
        try {
            for (i in 1..messages) {
                db.execSQL("INSERT INTO messages (conversation_id, content) VALUES (?, ?)", arrayOf("c1", "message $i"))
            }
            for (i in 1..memoryFacts) {
                // Explicitly Any, because a String next to a Long infers an intersection type
                // and SQLite's bind takes Object. The mixed-type column pair is deliberate:
                // it proves a TEXT and an INTEGER column both survive the round trip.
                db.execSQL(
                    "INSERT INTO memory_facts (fact, created_at) VALUES (?, ?)",
                    arrayOf<Any>("fact $i", i.toLong()),
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return db
    }

    @Test
    fun `a snapshot taken while rows are still in the write-ahead log contains those rows`() = runTest {
        // The writer stays open and is never checkpointed, so these rows live in the -wal.
        val writer = createBrainWithRows(messages = 25, memoryFacts = 7)

        val wal = File(databaseFile().absolutePath + "-wal")
        println("write-ahead log present=${wal.exists()} bytes=${if (wal.exists()) wal.length() else 0}")
        assertTrue(
            "this test is meaningless unless the rows really are in the log, so the log must exist",
            wal.exists() && wal.length() > 0,
        )

        val destination = File(context.cacheDir, "snapshot-test.${SnapshotFormat.FILE_EXTENSION}")
        val passphrase = "a passphrase for the test".toCharArray()

        val outcome = engine.writeSnapshot(destination, passphrase)

        // The writer is closed only after the snapshot, so the copy had to see the log to
        // see these rows. Closing it first would make a filesystem copy pass this test.
        writer.close()

        assertTrue(
            "the snapshot must be written: ${(outcome as? SnapshotWriteOutcome.Failed)?.message}",
            outcome.succeeded,
        )
        val written = outcome as SnapshotWriteOutcome.Written
        assertEquals("every row must be counted", 32L, written.manifest.totalRows)
        assertEquals(25L, written.manifest.tableRowCounts["messages"] ?: -1L)
        assertEquals(7L, written.manifest.tableRowCounts["memory_facts"] ?: -1L)
    }

    @Test
    fun `the snapshot leaves no plaintext sidecar in the staging area`() = runTest {
        val writer = createBrainWithRows(messages = 5, memoryFacts = 2)
        val destination = File(context.cacheDir, "snapshot-sidecar-test.${SnapshotFormat.FILE_EXTENSION}")

        engine.writeSnapshot(destination, "another test passphrase".toCharArray())
        writer.close()

        // The opening issue leaked two -wal files beside an encrypted snapshot. A -wal holds
        // plaintext pages, so its presence beside a sealed file defeats the seal entirely.
        val staging = File(context.cacheDir, "brain_snapshot_staging")
        val leftovers = staging.listFiles()?.toList().orEmpty()
        val plaintext = leftovers.flatMap { dir ->
            dir.listFiles()?.toList().orEmpty()
        }.filter { it.name.endsWith("-wal") || it.name.endsWith("-shm") || it.name == databaseName }

        println("staging leftovers: ${leftovers.map { it.name }}")
        assertTrue("no plaintext database or sidecar may survive in staging: $plaintext", plaintext.isEmpty())
    }

    @Test
    fun `a snapshot opens, verifies and restores the rows it carries`() = runTest {
        val writer = createBrainWithRows(messages = 12, memoryFacts = 3)
        writer.close()

        val destination = File(context.cacheDir, "snapshot-restore-test.${SnapshotFormat.FILE_EXTENSION}")
        val passphrase = "restore passphrase".toCharArray()
        val outcome = engine.writeSnapshot(destination, passphrase)
        assertTrue("snapshot must be written", outcome.succeeded)

        val verification = engine.verifySnapshots(destination, passphrase)
        assertTrue(
            "a snapshot the writer just verified must verify again: ${verification.message}",
            verification.isUsable,
        )

        // Change the live database so a successful restore is visible as a change, not as
        // the absence of one.
        val changer = SQLiteDatabase.openDatabase(databaseFile().absolutePath, null, SQLiteDatabase.OPEN_READWRITE)
        changer.execSQL("DELETE FROM messages")
        changer.close()

        val staged = File(context.cacheDir, "restore-stage/$databaseName")
        val extract = engine.extractTo(destination, passphrase, staged)
        assertTrue("extract must succeed", extract is SnapshotExtractOutcome.Extracted)

        val install = engine.installStaged(staged)
        assertTrue(
            "install must succeed: ${(install as? SnapshotInstallOutcome.Failed)?.message}",
            install.succeeded,
        )
        assertTrue((install as SnapshotInstallOutcome.Installed).hadExistingBrain)

        val restored = SQLiteDatabase.openDatabase(databaseFile().absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        val messages = restored.rawQuery("SELECT COUNT(*) FROM messages", null).use { c ->
            if (c.moveToFirst()) c.getLong(0) else -1L
        }
        val facts = restored.rawQuery("SELECT COUNT(*) FROM memory_facts", null).use { c ->
            if (c.moveToFirst()) c.getLong(0) else -1L
        }
        restored.close()

        assertEquals("the deleted rows must come back", 12L, messages)
        assertEquals(3L, facts)
    }

    @Test
    fun `a wrong passphrase is refused and says so without claiming corruption`() = runTest {
        val writer = createBrainWithRows(messages = 3, memoryFacts = 1)
        writer.close()
        val destination = File(context.cacheDir, "snapshot-wrong-pass.${SnapshotFormat.FILE_EXTENSION}")
        engine.writeSnapshot(destination, "the right passphrase".toCharArray())

        val verification = engine.verifySnapshots(destination, "the wrong passphrase".toCharArray())

        assertTrue(verification is SnapshotVerification.CannotOpen)
        assertFalse(verification.isUsable)
        assertTrue(
            "the message must admit the two causes cannot be separated",
            verification.message.contains("wrong") && verification.message.contains("altered"),
        )
    }

    @Test
    fun `a snapshot from a newer schema is refused rather than installed`() = runTest {
        val writer = createBrainWithRows(messages = 2, memoryFacts = 1)
        // Pretend this database came from a build with one more migration than this one has.
        val bumped = com.unuslumen.app.database.guruDatabase.SCHEMA_VERSION + 1
        writer.execSQL("PRAGMA user_version = $bumped")
        writer.close()

        val destination = File(context.cacheDir, "snapshot-newer.${SnapshotFormat.FILE_EXTENSION}")
        val passphrase = "schema test passphrase".toCharArray()
        engine.writeSnapshot(destination, passphrase)

        // The writer verified against its own schema, so re-check against this build's.
        val verification = engine.verifySnapshots(destination, passphrase)

        assertTrue(
            "a snapshot whose schema is newer than this build must be refused: ${verification.message}",
            verification is SnapshotVerification.SchemaTooNew,
        )
    }

    @Test
    fun `a file that is not a snapshot is named as the wrong file`() = runTest {
        val notASnapshot = File(context.cacheDir, "holiday-photo.jpg")
        notASnapshot.writeBytes(ByteArray(8192) { 0x11 })

        val verification = engine.verifySnapshots(notASnapshot, "anything at all".toCharArray())

        assertTrue(verification is SnapshotVerification.NotASnapshot)
    }

    @Test
    fun `retention deletes the oldest snapshots and keeps the newest`() {
        val folder = File(context.cacheDir, "retention-test")
        folder.mkdirs()
        folder.listFiles()?.forEach { it.delete() }

        val names = listOf(
            "guru-brain_2026-01-01_00-00-00.${SnapshotFormat.FILE_EXTENSION}",
            "guru-brain_2026-02-01_00-00-00.${SnapshotFormat.FILE_EXTENSION}",
            "guru-brain_2026-03-01_00-00-00.${SnapshotFormat.FILE_EXTENSION}",
            // A file the owner put there. It must never be deleted by retention.
            "important-document.pdf",
        )
        names.forEach { name -> File(folder, name).writeText("x") }

        val pruned = engine.prune(folder, DatabaseSnapshotEngine.RetentionPolicy(keep = 2))

        assertEquals("exactly the oldest snapshot must go", 1, pruned.size)
        assertTrue(pruned.first().name.contains("2026-01-01"))
        assertTrue("the owner's own file must survive", File(folder, "important-document.pdf").exists())
        assertEquals(2, engine.list(folder).size)
        assertNotNull(engine.list(folder).firstOrNull())
    }
}
