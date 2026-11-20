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
            guruDatabase.SCHEMA_VERSION,
            true,
            com.unuslumen.app.database.migrations.MIGRATION_35_36,
            com.unuslumen.app.database.migrations.MIGRATION_36_37,
            com.unuslumen.app.database.migrations.MIGRATION_37_38,
            com.unuslumen.app.database.migrations.MIGRATION_38_39,
            com.unuslumen.app.database.migrations.MIGRATION_39_40,
            com.unuslumen.app.database.migrations.MIGRATION_40_41,
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

    /**
     * A conversation delete must take its runs, events and checkpoints with it, and nothing else.
     *
     * FOREIGN KEYS ARE OFF BY DEFAULT IN SQLITE. Room turns them on when it opens the
     * database, but `MigrationTestHelper.createDatabase` hands back a raw handle, so a
     * cascade test written against it without this pragma proves nothing: the delete
     * succeeds, nothing cascades, and the rows are still there. That is exactly what the
     * first run of this test reported, and the fault was the test rather than the schema.
     */
    @Test
    fun deletingAConversationCascadesToRunsEventsAndCheckpoints() {
        val db = helper.createDatabase(databaseName, guruDatabase.SCHEMA_VERSION)
        db.execSQL("PRAGMA foreign_keys = ON")
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
        val db = helper.createDatabase(databaseName, guruDatabase.SCHEMA_VERSION)
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
        val db = helper.createDatabase(databaseName, guruDatabase.SCHEMA_VERSION)
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
        val db = helper.createDatabase(databaseName, guruDatabase.SCHEMA_VERSION)
        // Same reason as the cascade test: without this the SET NULL never fires and the
        // test would report a schema defect that does not exist.
        db.execSQL("PRAGMA foreign_keys = ON")
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
     * The Phase 2 migration: an existing agent gains configuration without losing anything.
     *
     * THE SPECIFIC FEAR THIS CLOSES. Adding eleven columns to a table that already holds an
     * agent's instructions and tool grants, on a device whose owner cannot fix it if it goes
     * wrong. So the test builds at 36, writes a real agent with a real prompt and real tools,
     * migrates, and then asserts three things: every original value is unchanged, every new
     * column carries the versioned empty document rather than a NULL, and `definition_version`
     * starts at 1 so the first edit appends version 2 rather than colliding with the unique
     * index at 1.
     */
    @Test
    fun migrationFrom36To37GivesAnExistingAgentConfigurationWithoutLosingAnything() {
        helper.createDatabase(databaseName, 36).use { db ->
            db.execSQL(
                """
                INSERT INTO guru_agents (id, slug, name, description, system_prompt,
                    tool_permissions, source, enabled, version, created_at, updated_at)
                VALUES ('agent-old', 'grafter', 'Grafter', 'does the chain work',
                    'you are a grafter', 'shellExec, captureScreen', 'user', 1, 3, 1000, 2000)
                """.trimIndent()
            )
        }

        val migrated = helper.runMigrationsAndValidate(
            databaseName,
            guruDatabase.SCHEMA_VERSION,
            true,
            com.unuslumen.app.database.migrations.MIGRATION_36_37,
            com.unuslumen.app.database.migrations.MIGRATION_37_38,
            com.unuslumen.app.database.migrations.MIGRATION_38_39,
            com.unuslumen.app.database.migrations.MIGRATION_39_40,
            com.unuslumen.app.database.migrations.MIGRATION_40_41,
        )

        migrated.use { db ->
            db.query(
                "SELECT name, slug, description, system_prompt, tool_permissions, source, " +
                    "enabled, version, created_at, updated_at, definition_version, " +
                    "visual_json, visibility_json, autonomy_json, manifest_json, skill_ids " +
                    "FROM guru_agents WHERE id = 'agent-old'"
            ).use { c ->
                assertTrue("the existing agent must survive the migration", c.moveToFirst())
                assertEquals("the name is unchanged", "Grafter", c.getString(0))
                assertEquals("the slug is unchanged", "grafter", c.getString(1))
                assertEquals("does the chain work", c.getString(2))
                assertEquals("you are a grafter", c.getString(3))
                assertEquals(
                    "the tool grants are unchanged, separator and all",
                    "shellExec, captureScreen",
                    c.getString(4),
                )
                assertEquals("user", c.getString(5))
                assertEquals(1, c.getInt(6))
                assertEquals("the published version is unchanged", 3, c.getInt(7))
                assertEquals(1000L, c.getLong(8))
                assertEquals(2000L, c.getLong(9))
                assertEquals(
                    "an existing agent starts its local edit count at 1, so its first edit " +
                        "writes version 2 and does not collide with the unique index",
                    1,
                    c.getInt(10),
                )
                // Every configuration document defaults to the versioned empty envelope, never NULL,
                // because the domain types are non-null and a NULL would crash a read.
                for (column in 11..14) {
                    assertEquals(
                        "column $column must default to the versioned empty document",
                        "{\"schemaVersion\":1}",
                        c.getString(column),
                    )
                }
                assertEquals("no skills were granted before, so none appear now", "", c.getString(15))
            }

            // The history table exists, is empty, and accepts the first version row.
            db.query("SELECT COUNT(*) FROM agent_definition_versions").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(
                    "a migration must not invent history for an agent whose edits were never recorded",
                    0,
                    c.getInt(0),
                )
            }
        }
        helper.closeWhenFinished(migrated)
    }

    /**
     * The definition-history uniqueness, proved by the database rather than by a comment.
     *
     * Two rows for the same agent and the same version would mean two different definitions both
     * claiming to be version 3, which is precisely the ambiguity the table exists to remove. The
     * index has to refuse the second one.
     */
    @Test
    fun twoDefinitionsCannotClaimTheSameVersionOfOneAgent() {
        val db = helper.createDatabase(databaseName, guruDatabase.SCHEMA_VERSION)
        db.execSQL(
            """
            INSERT INTO agent_definition_versions (version_row_id, agent_id, slug, name,
                definition_version, description, definition_json, reason, author, saved_at)
            VALUES ('vr-1', 'agent-x', 'x', 'X', 3, '', '{}', 'EDITED', 'USER', 1000)
            """.trimIndent()
        )

        var refused = false
        try {
            db.execSQL(
                """
                INSERT INTO agent_definition_versions (version_row_id, agent_id, slug, name,
                    definition_version, description, definition_json, reason, author, saved_at)
                VALUES ('vr-2', 'agent-x', 'x', 'X', 3, '', '{}', 'EDITED', 'USER', 2000)
                """.trimIndent()
            )
        } catch (e: Exception) {
            refused = true
        }
        assertTrue("two definitions cannot both be version 3 of one agent", refused)

        // A different agent may hold its own version 3.
        db.execSQL(
            """
            INSERT INTO agent_definition_versions (version_row_id, agent_id, slug, name,
                definition_version, description, definition_json, reason, author, saved_at)
            VALUES ('vr-3', 'agent-y', 'y', 'Y', 3, '', '{}', 'EDITED', 'USER', 3000)
            """.trimIndent()
        )
        db.query("SELECT COUNT(*) FROM agent_definition_versions").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("versions are scoped to their agent", 2, c.getInt(0))
        }
        helper.closeWhenFinished(db)
    }

    /**
     * Deleting an agent's row leaves its definition history alive.
     *
     * A DELIBERATE ABSENCE OF A FOREIGN KEY. History is evidence of what the agent was, and a
     * cascade would delete the evidence with the subject: any run that referenced it would then
     * point at a version nobody can read. Only the owner's own delete removes the history, and
     * that is an explicit call in the repository rather than a schema behaviour.
     */
    @Test
    fun deletingTheAgentRowLeavesItsDefinitionHistory() {
        val db = helper.createDatabase(databaseName, guruDatabase.SCHEMA_VERSION)
        db.execSQL("PRAGMA foreign_keys = ON")
        db.execSQL(
            """
            INSERT INTO guru_agents (id, slug, name, description, system_prompt,
                tool_permissions, source, enabled, version, created_at, updated_at)
            VALUES ('agent-d', 'doomed', 'Doomed', '', 'you are doomed', '', 'user', 1, 1, 1, 1)
            """.trimIndent()
        )
        db.execSQL(
            """
            INSERT INTO agent_definition_versions (version_row_id, agent_id, slug, name,
                definition_version, description, definition_json, reason, author, saved_at)
            VALUES ('vr-d', 'agent-d', 'doomed', 'Doomed', 1, '', '{}', 'CREATED', 'USER', 1)
            """.trimIndent()
        )
        db.execSQL("DELETE FROM guru_agents WHERE id = 'agent-d'")

        db.query("SELECT COUNT(*) FROM agent_definition_versions WHERE agent_id = 'agent-d'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(
                "the row is gone but what it was is still readable",
                1,
                c.getInt(0),
            )
        }
        helper.closeWhenFinished(db)
    }

    /**
     * The Phase 5 migration: a run gains a conversation and an approval record without losing
     * anything, and both cascade with the run.
     *
     * THE CASCADE IS THE IMPORTANT HALF. A message that outlives its run belongs to nothing, and a
     * person who deletes a conversation expects the whole record to go: runs, events, checkpoints,
     * messages and approvals. Proved here rather than assumed from the foreign key's declaration,
     * because SQLite only enforces it when the pragma is on.
     */
    @Test
    fun migrationFrom37To38AddsMessagesAndApprovalsAndTheyCascadeWithTheRun() {
        helper.createDatabase(databaseName, 37).use { db ->
            db.execSQL(
                """
                INSERT INTO conversations (id, title, created_date, updated_date, message_count)
                VALUES ('conv-p5', 'a conversation', 1000, 2000, 0)
                """.trimIndent()
            )
            db.execSQL(
                """
                INSERT INTO agent_runs (run_id, conversation_id, agent_id, agent_name, assignment,
                    status, agent_definition_snapshot_json, resolved_system_prompt,
                    tool_manifest_snapshot_json, model_provider, model_name, created_at, updated_at)
                VALUES ('run-p5', 'conv-p5', 'agent-1', 'Researcher', 'find it', 'RUNNING',
                    '{"schemaVersion":1}', 'you research', '{"schemaVersion":1}', 'UnusLumen', 'm', 1, 1)
                """.trimIndent()
            )
        }

        val migrated = helper.runMigrationsAndValidate(
            databaseName,
            guruDatabase.SCHEMA_VERSION,
            true,
            com.unuslumen.app.database.migrations.MIGRATION_37_38,
            com.unuslumen.app.database.migrations.MIGRATION_38_39,
            com.unuslumen.app.database.migrations.MIGRATION_39_40,
            com.unuslumen.app.database.migrations.MIGRATION_40_41,
        )

        migrated.use { db ->
            // The pre-existing run survived the migration untouched.
            db.query("SELECT agent_name, assignment, status FROM agent_runs WHERE run_id = 'run-p5'").use { c ->
                assertTrue("the run must survive", c.moveToFirst())
                assertEquals("Researcher", c.getString(0))
                assertEquals("find it", c.getString(1))
                assertEquals("RUNNING", c.getString(2))
            }

            // The new tables accept their first rows.
            db.execSQL(
                """
                INSERT INTO agent_messages (message_id, run_id, sequence_number, author_type,
                    recipient_type, message_type, content, created_at)
                VALUES ('msg-1', 'run-p5', 1, 'GURU', 'AGENT', 'ASSIGNMENT', 'find it', 1000)
                """.trimIndent()
            )
            db.execSQL(
                """
                INSERT INTO agent_approvals (approval_id, run_id, action, what, why, affected,
                    reversible, consequences, requested_permission, action_fingerprint, requested_at)
                VALUES ('ap-1', 'run-p5', 'sendSms', 'send it', 'needed', 'one message', 0,
                    'cannot be recalled', 'approval.external.sms', 'abc123', 1000)
                """.trimIndent()
            )

            db.query("SELECT delivery_state FROM agent_messages WHERE message_id = 'msg-1'").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(
                    "a message defaults to QUEUED, because nothing has taken it yet",
                    "QUEUED",
                    c.getString(0),
                )
            }
            db.query("SELECT state FROM agent_approvals WHERE approval_id = 'ap-1'").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("PENDING", c.getString(0))
            }

            // Cascade: delete the conversation, and the whole record goes with it.
            db.execSQL("PRAGMA foreign_keys = ON")
            db.execSQL("DELETE FROM conversations WHERE id = 'conv-p5'")

            for (table in listOf("agent_runs", "agent_messages", "agent_approvals")) {
                db.query("SELECT COUNT(*) FROM $table").use { c ->
                    assertTrue(c.moveToFirst())
                    assertEquals(
                        "$table must not outlive the conversation that owns it",
                        0,
                        c.getInt(0),
                    )
                }
            }
        }
        helper.closeWhenFinished(migrated)
    }

    /** Two messages in one run cannot share a sequence, and one agent's sequence is its own. */
    @Test
    fun messageSequenceIsUniquePerRun() {
        val db = helper.createDatabase(databaseName, guruDatabase.SCHEMA_VERSION)
        db.execSQL(
            """
            INSERT INTO conversations (id, title, created_date, updated_date, message_count)
            VALUES ('conv-m', 't', 1, 1, 0)
            """.trimIndent()
        )
        db.execSQL(
            """
            INSERT INTO agent_runs (run_id, conversation_id, agent_id, agent_name, assignment,
                status, agent_definition_snapshot_json, resolved_system_prompt,
                tool_manifest_snapshot_json, model_provider, model_name, created_at, updated_at)
            VALUES ('run-a', 'conv-m', 'a', 'A', 'job', 'RUNNING', '{}', 'p', '{}', 'U', 'm', 1, 1),
                   ('run-b', 'conv-m', 'a', 'A', 'job', 'RUNNING', '{}', 'p', '{}', 'U', 'm', 1, 1)
            """.trimIndent()
        )
        db.execSQL(
            """
            INSERT INTO agent_messages (message_id, run_id, sequence_number, author_type,
                recipient_type, message_type, content, created_at)
            VALUES ('m1', 'run-a', 1, 'USER', 'AGENT', 'INSTRUCTION', 'one', 1)
            """.trimIndent()
        )

        var refused = false
        try {
            db.execSQL(
                """
                INSERT INTO agent_messages (message_id, run_id, sequence_number, author_type,
                    recipient_type, message_type, content, created_at)
                VALUES ('m2', 'run-a', 1, 'USER', 'AGENT', 'INSTRUCTION', 'two', 2)
                """.trimIndent()
            )
        } catch (e: Exception) {
            refused = true
        }
        assertTrue("two messages cannot share a sequence within one run", refused)

        // A second run may hold its own sequence 1.
        db.execSQL(
            """
            INSERT INTO agent_messages (message_id, run_id, sequence_number, author_type,
                recipient_type, message_type, content, created_at)
            VALUES ('m3', 'run-b', 1, 'USER', 'AGENT', 'INSTRUCTION', 'three', 3)
            """.trimIndent()
        )
        db.query("SELECT COUNT(*) FROM agent_messages").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("message ordering is scoped to the run", 2, c.getInt(0))
        }
        helper.closeWhenFinished(db)
    }

    /** A decision is made once. The conditional update is what makes a second tap a no-op. */
    @Test
    fun anApprovalCanOnlyBeDecidedOnce() {
        val db = helper.createDatabase(databaseName, guruDatabase.SCHEMA_VERSION)
        db.execSQL(
            """
            INSERT INTO conversations (id, title, created_date, updated_date, message_count)
            VALUES ('conv-ap', 't', 1, 1, 0)
            """.trimIndent()
        )
        db.execSQL(
            """
            INSERT INTO agent_runs (run_id, conversation_id, agent_id, agent_name, assignment,
                status, agent_definition_snapshot_json, resolved_system_prompt,
                tool_manifest_snapshot_json, model_provider, model_name, created_at, updated_at)
            VALUES ('run-ap', 'conv-ap', 'a', 'A', 'job', 'WAITING_FOR_USER', '{}', 'p', '{}', 'U', 'm', 1, 1)
            """.trimIndent()
        )
        db.execSQL(
            """
            INSERT INTO agent_approvals (approval_id, run_id, action, what, why, affected,
                reversible, consequences, requested_permission, action_fingerprint, requested_at)
            VALUES ('ap-x', 'run-ap', 'sendSms', 'send', 'why', 'affected', 0, 'consequences',
                'perm', 'fp', 1)
            """.trimIndent()
        )

        // The first decision lands.
        db.execSQL(
            "UPDATE agent_approvals SET state = 'APPROVED', decided_by = 'USER', decided_at = 10 " +
                "WHERE approval_id = 'ap-x' AND state = 'PENDING'"
        )
        db.query("SELECT state FROM agent_approvals WHERE approval_id = 'ap-x'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("APPROVED", c.getString(0))
        }

        // The second finds nothing pending and changes nothing.
        db.execSQL(
            "UPDATE agent_approvals SET state = 'REJECTED', decided_by = 'USER', decided_at = 20 " +
                "WHERE approval_id = 'ap-x' AND state = 'PENDING'"
        )
        db.query("SELECT state, decided_at FROM agent_approvals WHERE approval_id = 'ap-x'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(
                "a second decision must not overwrite the first",
                "APPROVED",
                c.getString(0),
            )
            assertEquals(10L, c.getLong(1))
        }
        helper.closeWhenFinished(db)
    }

    /**
     * The Phase 6 migration: a run gains artefacts and context provenance, and both cascade.
     *
     * THE TWO ENDS OF "WHY DOES THIS EXIST" ARE PROVED TOGETHER. An artefact says what was produced
     * and by which run; a context reference says what that run was reading. Both hang off the run,
     * so deleting the conversation takes the whole record and neither is left pointing at nothing.
     */
    @Test
    fun migrationFrom38To39AddsArtefactsAndContextAndBothCascade() {
        helper.createDatabase(databaseName, 38).use { db ->
            db.execSQL(
                """
                INSERT INTO conversations (id, title, created_date, updated_date, message_count)
                VALUES ('conv-p6', 'a conversation', 1, 1, 0)
                """.trimIndent()
            )
            db.execSQL(
                """
                INSERT INTO agent_runs (run_id, conversation_id, agent_id, agent_name, assignment,
                    status, agent_definition_snapshot_json, resolved_system_prompt,
                    tool_manifest_snapshot_json, model_provider, model_name, created_at, updated_at)
                VALUES ('run-p6', 'conv-p6', 'agent-1', 'Writer', 'write it', 'RUNNING',
                    '{"schemaVersion":1}', 'you write', '{"schemaVersion":1}', 'UnusLumen', 'm', 1, 1)
                """.trimIndent()
            )
        }

        val migrated = helper.runMigrationsAndValidate(
            databaseName,
            guruDatabase.SCHEMA_VERSION,
            true,
            com.unuslumen.app.database.migrations.MIGRATION_38_39,
            com.unuslumen.app.database.migrations.MIGRATION_39_40,
            com.unuslumen.app.database.migrations.MIGRATION_40_41,
        )

        migrated.use { db ->
            // The pre-existing run survived.
            db.query("SELECT agent_name FROM agent_runs WHERE run_id = 'run-p6'").use { c ->
                assertTrue("the run must survive the migration", c.moveToFirst())
                assertEquals("Writer", c.getString(0))
            }

            // Both new tables accept rows.
            db.execSQL(
                """
                INSERT INTO agent_artefacts (artefact_id, run_id, agent_id, agent_name, title, kind,
                    storage_kind, storage_ref, created_at)
                VALUES ('art-1', 'run-p6', 'agent-1', 'Writer', 'the report', 'REPORT',
                    'INLINE', 'inline-1', 1000)
                """.trimIndent()
            )
            db.execSQL(
                """
                INSERT INTO agent_context_references (reference_id, run_id, source_kind, source_ref,
                    created_at)
                VALUES ('ctx-1', 'run-p6', 'MEMORY', 'mem-abc', 1000)
                """.trimIndent()
            )

            db.query("SELECT storage_kind, final_output FROM agent_artefacts WHERE artefact_id = 'art-1'").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("INLINE", c.getString(0))
                assertEquals(
                    "an artefact defaults to being a final output",
                    1,
                    c.getInt(1),
                )
            }
            db.query(
                "SELECT supplied_to_model, within_scope FROM agent_context_references " +
                    "WHERE reference_id = 'ctx-1'"
            ).use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(
                    "a candidate is NOT supplied until the caller says so, which is the whole " +
                        "distinction between a retrieval and an influence",
                    0,
                    c.getInt(0),
                )
                assertEquals(1, c.getInt(1))
            }

            // Cascade: deleting the conversation takes both new tables with it.
            db.execSQL("PRAGMA foreign_keys = ON")
            db.execSQL("DELETE FROM conversations WHERE id = 'conv-p6'")

            for (table in listOf("agent_runs", "agent_artefacts", "agent_context_references")) {
                db.query("SELECT COUNT(*) FROM $table").use { c ->
                    assertTrue(c.moveToFirst())
                    assertEquals("$table must not outlive its conversation", 0, c.getInt(0))
                }
            }
        }
        helper.closeWhenFinished(migrated)
    }

    /**
     * The Phase 8 migration: schedules exist, and one schedule's window can only be taken once.
     *
     * THE CONDITIONAL ADVANCE IS THE WHOLE SAFETY OF SCHEDULED WORK. Two runners — a worker and a
     * manual trigger, or one worker and a restart — can both see the same window as due. If both
     * could advance it, the same job would start twice, and the second start would carry real side
     * effects for work nobody asked for twice. The update names the due time it expects to replace,
     * so exactly one succeeds.
     */
    @Test
    fun migrationFrom39To40AddsSchedulesAndOnlyOneRunnerCanTakeAWindow() {
        helper.createDatabase(databaseName, 39).use { db ->
            db.execSQL(
                """
                INSERT INTO conversations (id, title, created_date, updated_date, message_count)
                VALUES ('conv-p8', 'a conversation', 1, 1, 0)
                """.trimIndent()
            )
        }

        val migrated = helper.runMigrationsAndValidate(
            databaseName,
            guruDatabase.SCHEMA_VERSION,
            true,
            com.unuslumen.app.database.migrations.MIGRATION_39_40,
            com.unuslumen.app.database.migrations.MIGRATION_40_41,
        )

        migrated.use { db ->
            // The pre-existing conversation survived: the migration touched nothing that existed.
            db.query("SELECT title FROM conversations WHERE id = 'conv-p8'").use { c ->
                assertTrue("an existing conversation must survive", c.moveToFirst())
                assertEquals("a conversation", c.getString(0))
            }

            // The new table accepts a row, with its defaults.
            db.execSQL(
                """
                INSERT INTO agent_schedules (schedule_id, agent_id, agent_slug, title, assignment,
                    interval_millis, next_due_at, created_at, updated_at)
                VALUES ('sch-1', 'agent-1', 'grafter', 'the morning sweep', 'check the thing',
                    3600000, 5000, 1, 1)
                """.trimIndent()
            )
            db.query(
                "SELECT enabled, missed_count, next_due_at, conversation_id FROM agent_schedules " +
                    "WHERE schedule_id = 'sch-1'"
            ).use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("a schedule starts armed", 1, c.getInt(0))
                assertEquals("and with no missed windows", 0, c.getInt(1))
                assertEquals(5000L, c.getLong(2))
                assertTrue("no conversation exists until the first run", c.isNull(3))
            }

            /*
             * THE RACE, PROVED RATHER THAN DESCRIBED. Both runners see the same due time; the first
             * update matches it and moves it, so the second matches nothing. One window, one run.
             */
            db.execSQL(
                "UPDATE agent_schedules SET next_due_at = 3605000, last_run_id = 'run-a' " +
                    "WHERE schedule_id = 'sch-1' AND next_due_at = 5000"
            )
            db.execSQL(
                "UPDATE agent_schedules SET next_due_at = 3605000, last_run_id = 'run-b' " +
                    "WHERE schedule_id = 'sch-1' AND next_due_at = 5000"
            )
            db.query("SELECT last_run_id, next_due_at FROM agent_schedules WHERE schedule_id = 'sch-1'").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(
                    "the second runner must not have taken the window",
                    "run-a",
                    c.getString(0),
                )
                assertEquals(3605000L, c.getLong(1))
            }

            // A disabled schedule has no next due time, so nothing can find it due.
            db.execSQL(
                "UPDATE agent_schedules SET enabled = 0, next_due_at = NULL WHERE schedule_id = 'sch-1'"
            )
            db.query(
                "SELECT COUNT(*) FROM agent_schedules WHERE enabled = 1 AND next_due_at IS NOT NULL " +
                    "AND next_due_at <= 99999999"
            ).use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("a disarmed schedule is never due", 0, c.getInt(0))
            }
        }
        helper.closeWhenFinished(migrated)
    }

    /**
     * The historical chain.
     *
     * For every checked-in schema from 4 to the version before this build, open it, migrate to
     * the current version, and let Room validate the result against the entity-generated schema.
     * A version with no checked-in asset is reported as untested rather than silently passing:
     * the assertion below fails loudly if the schema directory is missing a file the test
     * expects, so the coverage statement in the PR body cannot drift from reality.
     */
    @Test
    fun everyCheckedInSchemaMigratesToTheCurrentVersion() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val expected = (4..guruDatabase.SCHEMA_VERSION - 1).toList()
        val tested = mutableListOf<Int>()
        val missing = mutableListOf<Int>()
        val skippedNoFts5 = mutableListOf<Int>()

        /*
         * NOT EVERY DEVICE'S SQLITE HAS FTS5, AND THE MIGRATION CHAIN NEEDS IT.
         *
         * `MIGRATION_5_6` creates `messages_fts`, a virtual table using the fts5 module.
         * The platform SQLite on this emulator reports "no such module: fts5", so the
         * chain from v4 cannot be replayed here at all. That is a fact about this runtime,
         * not a defect in the migration, and the two must not be reported as one.
         *
         * So availability is probed first and the versions that need it are RECORDED as
         * skipped rather than quietly counted as passing. A test that silently skipped
         * them would claim coverage it does not have, which is worse than the gap itself.
         */
        val fts5Available = runCatching {
            val probeName = "fts5-probe.db"
            context.deleteDatabase(probeName)
            android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(
                context.getDatabasePath(probeName), null
            ).use { probe ->
                probe.execSQL("CREATE VIRTUAL TABLE probe USING fts5(body)")
                probe.execSQL("DROP TABLE probe")
            }
            context.deleteDatabase(probeName)
            true
        }.getOrDefault(false)

        for (version in expected) {
            val assetName = "com.unuslumen.app.database.guruDatabase/$version.json"
            val present = runCatching {
                context.assets.open("$assetName").close()
            }.isSuccess
            if (!present) {
                missing += version
                continue
            }
            // The chain passes through the fts5-creating migration, so without the module
            // every version below it is untestable on this device.
            if (!fts5Available) {
                skippedNoFts5 += version
                continue
            }
            val name = "chain-$version.db"
            try {
                helper.createDatabase(name, version).close()
                val db = helper.runMigrationsAndValidate(
                    name,
                    guruDatabase.SCHEMA_VERSION,
                    true,
                    *allMigrations.toTypedArray(),
                )
                helper.closeWhenFinished(db)
                tested += version
            } catch (e: Throwable) {
                throw AssertionError(
                    "Schema $version did not migrate cleanly to " +
                        "${guruDatabase.SCHEMA_VERSION}: ${e.message}", e
                )
            }
        }

        println(
            "MIGRATION CHAIN COVERAGE: tested=$tested, " +
                "no checked-in schema=$missing, " +
                "skipped because this runtime lacks fts5=$skippedNoFts5, " +
                "fts5Available=$fts5Available"
        )

        /*
         * THE COVERAGE GAPS ARE ASSERTED EXACTLY, NOT WAVED AT.
         *
         * The first version of this assertion claimed only v1-v3 were missing, on the
         * assumption that the schema directory was contiguous from 4. It is not: 8, 9, 10
         * and 11 were never exported either. The device run caught that, which is the
         * reason the gap is written out here as a hard list rather than a range: a future
         * schema export or a deleted file changes the set and fails this test, so the
         * claim in the PR body cannot drift from what is actually on disk.
         */
        val knownMissing = setOf(8, 9, 10, 11)
        assertEquals(
            "the set of versions with no exported schema has changed. If a schema was " +
                "added or removed, update this test and the coverage claim together.",
            knownMissing,
            missing.toSet(),
        )
        assertTrue(
            "either the chain ran somewhere or it was skipped for a stated reason, " +
                "but this device must be able to do one of them",
            tested.isNotEmpty() || skippedNoFts5.isNotEmpty()
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
            com.unuslumen.app.database.migrations.MIGRATION_36_37,
            com.unuslumen.app.database.migrations.MIGRATION_37_38,
            com.unuslumen.app.database.migrations.MIGRATION_38_39,
            com.unuslumen.app.database.migrations.MIGRATION_39_40,
            com.unuslumen.app.database.migrations.MIGRATION_40_41,
        )
    }
}
