// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.database

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The agent execution foundation migration, proved against real SQLite.
 *
 * WHY THIS IS AN INSTRUMENTED TEST AND NOT A JVM ONE. MigrationTestHelper opens an
 * actual SQLite file through the platform, runs the registered Migration against it,
 * and then asks Room whether the resulting schema is the one the entities describe.
 * Robolectric's SQLite is not the device's, so a green result there would prove that
 * some SQLite accepted these statements, not that an upgrading install will boot.
 *
 * THE VERSION HISTORY IS HONEST ABOUT ITS OWN EDGES. Schemas are checked in from 4
 * onward; 1, 2 and 3 have no exported JSON and therefore cannot be migration-tested
 * with this tool at all. That gap is stated in the class rather than papered over, and
 * the per-version test below is skipped for a version with no asset instead of being
 * quietly omitted.
 *
 * Every test ends with `helper.closeWhenFinished(db)`, because a leaked handle keeps
 * the file open and the next test in the class fails for a reason unrelated to the
 * migration.
 */
@RunWith(AndroidJUnit4::class)
class AgentFoundationMigrationTest {

    private val databaseName = "agent-migration-test.db"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        guruDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory()
    )

    /**
     * THE MIGRATION THAT MATTERS TODAY. Build at 35, put real rows in the tables the
     * migration touches, migrate, then prove three things: nothing was lost, the new
     * tables exist, and the historical tool rows are NULL-linked rather than given an
     * invented run.
     */
    @Test
    fun migrationFrom35To36KeepsEveryRowAndAddsTheFoundation() {
        helper.createDatabase(databaseName, 35).use { db ->
            db.execSQL(
                """
                INSERT INTO conversations (id, title, created_date, updated_date, message_count)
                VALUES ('conv-1', 'a conversation', 1000, 2000, 1)
                """.trimIndent()
            )
            db.execSQL(
                """
                INSERT INTO messages (id, conversation_id, role, content, timestamp, embedding, tool_calls, tool_results, tool_name, tool_call_id)
                VALUES ('msg-1', 'conv-1', 'user', 'do the thing', 1500, '', '', '', '', '')
                """.trimIndent()
            )
            db.execSQL(
                """
                INSERT INTO guru_agents (id, slug, name, description, system_prompt, tool_permissions, source, enabled, version, created_at, updated_at)
                VALUES ('agent-1', 'grafter', 'Grafter', 'does the chain work', 'you are a grafter', 'shellExec', 'user', 1, 3, 1000, 2000)
                """.trimIndent()
            )
            db.execSQL(
                """
                INSERT INTO tool_results (id, tool_name, parameters, result, result_text, timestamp, conversation_id, success, ttl_minutes, result_signature)
                VALUES ('tr-1', 'shellExec', '{}', '{"ok":true}', 'ok', 1500, 'conv-1', 1, NULL, 'sig')
                """.trimIndent()
            )
            db.execSQL(
                """
                INSERT INTO tool_observations (id, package_name, activity_class, tool_name, arguments_json, result_summary, success, screen_fingerprint, accessibility_tree_json, conversation_id, timestamp, duration_ms)
                VALUES ('to-1', '', NULL, 'shellExec', '{}', 'ok', 1, NULL, NULL, 'conv-1', 1500, 12)
                """.trimIndent()
            )
        }

        val migrated = helper.runMigrationsAndValidate(
            databaseName,
            36,
            true,
            com.unuslumen.app.database.migrations.MIGRATION_35_36,
        )

        migrated.use { db ->
            // The old rows are all still there and unchanged.
            db.query("SELECT title, message_count FROM conversations WHERE id = 'conv-1'").use { c ->
                assertTrue("the conversation must survive the migration", c.moveToFirst())
                assertEquals("a conversation", c.getString(0))
                assertEquals(1, c.getInt(1))
            }
            db.query("SELECT content FROM messages WHERE id = 'msg-1'").use { c ->
                assertTrue("the message must survive the migration", c.moveToFirst())
                assertEquals("do the thing", c.getString(0))
            }
            db.query("SELECT name, version, tool_permissions FROM guru_agents WHERE id = 'agent-1'").use { c ->
                assertTrue("the agent definition must survive the migration", c.moveToFirst())
                assertEquals("Grafter", c.getString(0))
                assertEquals(3, c.getInt(1))
                assertEquals("shellExec", c.getString(2))
            }

            // The historical tool rows are NULL-linked, not given a fabricated run.
            db.query("SELECT agent_run_id, agent_invocation_id FROM tool_results WHERE id = 'tr-1'").use { c ->
                assertTrue(c.moveToFirst())
                assertTrue("a pre-run tool result must have no run", c.isNull(0))
                assertTrue("a pre-run tool result must have no invocation", c.isNull(1))
            }
            db.query("SELECT agent_run_id FROM tool_observations WHERE id = 'to-1'").use { c ->
                assertTrue(c.moveToFirst())
                assertTrue("a pre-run observation must have no run", c.isNull(0))
            }

            // The three new tables exist and are usable.
            db.execSQL(
                """
                INSERT INTO agent_runs (run_id, conversation_id, agent_id, agent_name, assignment, status,
                    agent_definition_snapshot_json, resolved_system_prompt, tool_manifest_snapshot_json,
                    model_provider, model_name, created_at, updated_at)
                VALUES ('run-1', 'conv-1', 'agent-1', 'Grafter', 'do the thing', 'CREATED',
                    '{"schemaVersion":1}', 'you are a grafter', '{"schemaVersion":1}', 'UnusLumen', 'a-model', 3000, 3000)
                """.trimIndent()
            )
            db.query("SELECT COUNT(*) FROM agent_runs").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(1, c.getInt(0))
            }
        }
        helper.closeWhenFinished(migrated)
    }

    /** A conversation delete must take its runs, events and checkpoints with it, and nothing else. */
    @Test
    fun deletingAConversationCascadesToRunsEventsAndCheckpoints() {
        val db = helper.createDatabase(databaseName, 36)
        db.execSQL(
            """
            INSERT INTO conversations (id, title, created_date, updated_date, message_count)
            VALUES ('conv-c', 'doomed', 1, 1, 0), ('conv-k', 'kept', 1, 1, 0)
            """.trimIndent()
        )
        db.execSQL(
            """
            INSERT INTO agent_runs (run_id, conversation_id, agent_id, agent_name, assignment, status,
                agent_definition_snapshot_json, resolved_system_prompt, tool_manifest_snapshot_json,
                model_provider, model_name, created_at, updated_at)
            VALUES ('run-c', 'conv-c', 'a', 'A', 'job', 'RUNNING', '{}', 'p', '{}', 'UnusLumen', 'm', 1, 1),
                   ('run-k', 'conv-k', 'a', 'A', 'job', 'RUNNING', '{}', 'p', '{}', 'UnusLumen', 'm', 1, 1)
            """.trimIndent()
        )
        db.execSQL(
            """
            INSERT INTO agent_run_events (event_id, run_id, sequence_number, event_type, actor_type,
                occurred_at, recorded_at, payload_json)
            VALUES ('ev-c', 'run-c', 1, 'RUN_CREATED', 'RUNTIME', 1, 1, '{}')
            """.trimIndent()
        )
        db.execSQL(
            """
            INSERT INTO agent_run_checkpoints (checkpoint_id, run_id, checkpoint_sequence, event_sequence,
                checkpoint_reason, state_json, state_sha256, created_at)
            VALUES ('cp-c', 'run-c', 1, 1, 'RUN_CREATED', '{}', 'hash', 1)
            """.trimIndent()
        )
        db.execSQL("DELETE FROM conversations WHERE id = 'conv-c'")

        db.query("SELECT COUNT(*) FROM agent_runs WHERE run_id = 'run-c'").use { c ->
            assertTrue(c.moveToFirst()); assertEquals(0, c.getInt(0))
        }
        db.query("SELECT COUNT(*) FROM agent_run_events WHERE run_id = 'run-c'").use { c ->
            assertTrue(c.moveToFirst()); assertEquals(0, c.getInt(0))
        }
        db.query("SELECT COUNT(*) FROM agent_run_checkpoints WHERE run_id = 'run-c'").use { c ->
            assertTrue(c.moveToFirst()); assertEquals(0, c.getInt(0))
        }
        db.query("SELECT COUNT(*) FROM agent_runs WHERE run_id = 'run-k'").use { c ->
            assertTrue(c.moveToFirst()); assertEquals("an unrelated run must survive", 1, c.getInt(0))
        }
        helper.closeWhenFinished(db)
    }

    /** The unique pair is the last line of defence on event ordering. Prove the database refuses a duplicate. */
    @Test
    fun aDuplicateEventSequenceIsRefusedByTheDatabase() {
        val db = helper.createDatabase(databaseName, 36)
        db.execSQL(
            """
            INSERT INTO conversations (id, title, created_date, updated_date, message_count)
            VALUES ('conv-e', 't', 1, 1, 0)
            """.trimIndent()
        )
        db.execSQL(
            """
            INSERT INTO agent_runs (run_id, conversation_id, agent_id, agent_name, assignment, status,
                agent_definition_snapshot_json, resolved_system_prompt, tool_manifest_snapshot_json,
                model_provider, model_name, created_at, updated_at)
            VALUES ('run-e', 'conv-e', 'a', 'A', 'job', 'RUNNING', '{}', 'p', '{}', 'UnusLumen', 'm', 1, 1)
            """.trimIndent()
        )
        db.execSQL(
            """
            INSERT INTO agent_run_events (event_id, run_id, sequence_number, event_type, actor_type,
                occurred_at, recorded_at, payload_json)
            VALUES ('ev-1', 'run-e', 1, 'RUN_CREATED', 'RUNTIME', 1, 1, '{}')
            """.trimIndent()
        )

        var refused = false
        try {
            db.execSQL(
                """
                INSERT INTO agent_run_events (event_id, run_id, sequence_number, event_type, actor_type,
                    occurred_at, recorded_at, payload_json)
                VALUES ('ev-2', 'run-e', 1, 'RUN_STATE_CHANGED', 'RUNTIME', 2, 2, '{}')
                """.trimIndent()
            )
        } catch (e: Exception) {
            refused = true
        }
        assertTrue("two events cannot share a sequence within one run", refused)
        helper.closeWhenFinished(db)
    }

    /** A different run starts its own sequence at 1: the uniqueness is per run, not global. */
    @Test
    fun sequencesAreScopedToTheirRun() {
        val db = helper.createDatabase(databaseName, 36)
        db.execSQL(
            """
            INSERT INTO conversations (id, title, created_date, updated_date, message_count)
            VALUES ('conv-s', 't', 1, 1, 0)
            """.trimIndent()
        )
        db.execSQL(
            """
            INSERT INTO agent_runs (run_id, conversation_id, agent_id, agent_name, assignment, status,
                agent_definition_snapshot_json, resolved_system_prompt, tool_manifest_snapshot_json,
                model_provider, model_name, created_at, updated_at)
            VALUES ('run-s1', 'conv-s', 'a', 'A', 'job', 'RUNNING', '{}', 'p', '{}', 'UnusLumen', 'm', 1, 1),
                   ('run-s2', 'conv-s', 'a', 'A', 'job', 'RUNNING', '{}', 'p', '{}', 'UnusLumen', 'm', 1, 1)
            """.trimIndent()
        )
        db.execSQL(
            """
            INSERT INTO agent_run_events (event_id, run_id, sequence_number, event_type, actor_type,
                occurred_at, recorded_at, payload_json)
            VALUES ('e1a', 'run-s1', 1, 'RUN_CREATED', 'RUNTIME', 1, 1, '{}'),
                   ('e1b', 'run-s2', 1, 'RUN_CREATED', 'RUNTIME', 1, 1, '{}')
            """.trimIndent()
        )
        db.query("SELECT COUNT(*) FROM agent_run_events").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("both runs may hold sequence 1", 2, c.getInt(0))
        }
        helper.closeWhenFinished(db)
    }

    /** Deleting a message must NULL the run's origin and leave the run, its assignment and its history alive. */
    @Test
    fun deletingTheOriginMessageLeavesTheRunIntact() {
        val db = helper.createDatabase(databaseName, 36)
        db.execSQL(
            """
            INSERT INTO conversations (id, title, created_date, updated_date, message_count)
            VALUES ('conv-m', 't', 1, 1, 1)
            """.trimIndent()
        )
        db.execSQL(
            """
            INSERT INTO messages (id, conversation_id, role, content, timestamp, embedding, tool_calls, tool_results, tool_name, tool_call_id)
            VALUES ('msg-m', 'conv-m', 'user', 'job', 1, '', '', '', '', '')
            """.trimIndent()
        )
        db.execSQL(
            """
            INSERT INTO agent_runs (run_id, conversation_id, origin_message_id, agent_id, agent_name, assignment, status,
                agent_definition_snapshot_json, resolved_system_prompt, tool_manifest_snapshot_json,
                model_provider, model_name, created_at, updated_at)
            VALUES ('run-m', 'conv-m', 'msg-m', 'a', 'A', 'the original job', 'COMPLETED', '{}', 'p', '{}', 'UnusLumen', 'm', 1, 1)
            """.trimIndent()
        )
        db.execSQL("DELETE FROM messages WHERE id = 'msg-m'")

        db.query("SELECT origin_message_id, assignment, status FROM agent_runs WHERE run_id = 'run-m'").use { c ->
            assertTrue("the run must survive its origin message", c.moveToFirst())
            assertTrue("the origin link becomes null", c.isNull(0))
            assertEquals("the assignment is still the assignment", "the original job", c.getString(1))
            assertEquals("COMPLETED", c.getString(2))
        }
        helper.closeWhenFinished(db)
    }

    /**
     * The historical chain.
     *
     * For every checked-in schema from 4 to 35, open it, migrate to 36, and let Room
     * validate the result against the entity-generated schema. A version with no
     * checked-in asset is reported as untested rather than silently passing: the
     * assertion below fails loudly if the schema directory is missing a file the test
     * expects, so the coverage statement in the PR body cannot drift from reality.
     */
    @Test
    fun everyCheckedInSchemaMigratesTo36() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val expected = (4..35).toList()
        val tested = mutableListOf<Int>()
        val missing = mutableListOf<Int>()

        for (version in expected) {
            val assetName = "com.unuslumen.app.database.guruDatabase/$version.json"
            val present = runCatching {
                context.assets.open("$assetName").close()
            }.isSuccess
            if (!present) {
                missing += version
                continue
            }
            val name = "chain-$version.db"
            try {
                helper.createDatabase(name, version).close()
                val db = helper.runMigrationsAndValidate(
                    name,
                    36,
                    true,
                    *allMigrations.toTypedArray(),
                )
                helper.closeWhenFinished(db)
                tested += version
            } catch (e: Throwable) {
                throw AssertionError(
                    "Schema $version did not migrate cleanly to 36: ${e.message}", e
                )
            }
        }

        assertTrue("no schema was testable, which cannot be right", tested.isNotEmpty())
        assertTrue(
            "schemas with no checked-in asset: $missing. v1-v3 have never had an exported schema, " +
                "so a database older than v4 cannot be covered by this tool.",
            missing.all { it < 4 } || missing.isEmpty()
        )
    }

    companion object {
        /**
         * Every registered migration, in order.
         *
         * Kept here as a list rather than reaching into DatabaseModule, because the
         * module's list is a Koin expression and a test that builds the production
         * container to read it would prove the container works rather than the
         * migrations do.
         */
        private val allMigrations = listOf(
            com.unuslumen.app.database.migrations.MIGRATION_4_5,
            com.unuslumen.app.database.migrations.MIGRATION_5_6,
            com.unuslumen.app.database.migrations.MIGRATION_6_7,
            com.unuslumen.app.database.migrations.MIGRATION_7_8,
            com.unuslumen.app.database.migrations.MIGRATION_8_9,
            com.unuslumen.app.database.migrations.MIGRATION_9_10,
            com.unuslumen.app.database.migrations.MIGRATION_10_11,
            com.unuslumen.app.database.migrations.MIGRATION_11_12,
            com.unuslumen.app.database.migrations.MIGRATION_12_13,
            com.unuslumen.app.database.migrations.MIGRATION_13_14,
            com.unuslumen.app.database.migrations.MIGRATION_14_15,
            com.unuslumen.app.database.migrations.MIGRATION_15_16,
            com.unuslumen.app.database.migrations.MIGRATION_16_17,
            com.unuslumen.app.database.migrations.MIGRATION_17_18,
            com.unuslumen.app.database.migrations.MIGRATION_18_19,
            com.unuslumen.app.database.migrations.MIGRATION_19_20,
            com.unuslumen.app.database.migrations.MIGRATION_20_21,
            com.unuslumen.app.database.migrations.MIGRATION_21_22,
            com.unuslumen.app.database.migrations.MIGRATION_22_23,
            com.unuslumen.app.database.migrations.MIGRATION_23_24,
            com.unuslumen.app.database.migrations.MIGRATION_24_25,
            com.unuslumen.app.database.migrations.MIGRATION_25_26,
            com.unuslumen.app.database.migrations.MIGRATION_26_27,
            com.unuslumen.app.database.migrations.MIGRATION_27_28,
            com.unuslumen.app.database.migrations.MIGRATION_28_29,
            com.unuslumen.app.database.migrations.MIGRATION_29_30,
            com.unuslumen.app.database.migrations.MIGRATION_30_31,
            com.unuslumen.app.database.migrations.MIGRATION_31_32,
            com.unuslumen.app.database.migrations.MIGRATION_32_33,
            com.unuslumen.app.database.migrations.MIGRATION_33_34,
            com.unuslumen.app.database.migrations.MIGRATION_34_35,
            com.unuslumen.app.database.migrations.MIGRATION_35_36,
        )
    }
}
