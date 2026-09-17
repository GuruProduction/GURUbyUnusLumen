@file:OptIn(ExperimentalUuidApi::class)
package com.unuslumen.app.database.migrations

import androidx.core.database.getIntOrNull
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

// Added note folders
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE note_folders (name TEXT NOT NULL, id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL)")

        db.execSQL("CREATE TABLE IF NOT EXISTS `notes_new` (`title` TEXT NOT NULL, `content` TEXT NOT NULL, `created_date` INTEGER NOT NULL, `updated_date` INTEGER NOT NULL, `pinned` INTEGER NOT NULL, `folder_id` INTEGER, `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, FOREIGN KEY (folder_id) REFERENCES note_folders (id) ON UPDATE NO ACTION ON DELETE CASCADE)")
        db.execSQL("INSERT INTO notes_new (title, content, created_date, updated_date, pinned, id) SELECT title, content, created_date, updated_date, pinned, id FROM notes")
        db.execSQL("DROP TABLE notes")
        db.execSQL("ALTER TABLE notes_new RENAME TO notes")
    }
}

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE tasks ADD COLUMN recurring INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE tasks ADD COLUMN frequency INTEGER NOT NULL DEFAULT 0")
    }
}

val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE tasks ADD COLUMN frequency_amount INTEGER NOT NULL DEFAULT 1")
    }
}

// Migrating from using auto-incrementing integer IDs to UUIDs
val MIGRATION_4_5 = object : Migration(4, 5) {

    override fun migrate(db: SupportSQLiteDatabase) {
        // Create a mapping of old folder IDs to new UUIDs
        val folderIdMapping = HashMap<Int, String>()

        // Migrate note_folders and notes tables
        db.execSQL("CREATE TABLE note_folders_new (name TEXT NOT NULL, id TEXT PRIMARY KEY NOT NULL)")
        db.execSQL("CREATE TABLE notes_new (title TEXT NOT NULL, content TEXT NOT NULL, created_date INTEGER NOT NULL, updated_date INTEGER NOT NULL, pinned INTEGER NOT NULL, folder_id TEXT, id TEXT PRIMARY KEY NOT NULL)")

        val folderCursor = db.query("SELECT * FROM note_folders")
        while (folderCursor.moveToNext()) {
            val oldId = folderCursor.getInt(folderCursor.getColumnIndexOrThrow("id"))
            val name = folderCursor.getString(folderCursor.getColumnIndexOrThrow("name"))
            val newId = Uuid.random().toString()

            folderIdMapping[oldId] = newId

            db.execSQL(
                "INSERT INTO note_folders_new (id, name) VALUES (?, ?)",
                arrayOf(newId, name)
            )
        }
        folderCursor.close()

        val notesCursor = db.query("SELECT * FROM notes")
        while (notesCursor.moveToNext()) {
            val title = notesCursor.getString(notesCursor.getColumnIndexOrThrow("title"))
            val content = notesCursor.getString(notesCursor.getColumnIndexOrThrow("content"))
            val createdDate = notesCursor.getLong(notesCursor.getColumnIndexOrThrow("created_date"))
            val updatedDate = notesCursor.getLong(notesCursor.getColumnIndexOrThrow("updated_date"))
            val pinned = notesCursor.getInt(notesCursor.getColumnIndexOrThrow("pinned"))

            val oldFolderId =
                notesCursor.getIntOrNull(notesCursor.getColumnIndexOrThrow("folder_id"))
            val newFolderId = oldFolderId?.let { folderIdMapping[it] }

            val newId = Uuid.random().toString()

            db.execSQL(
                "INSERT INTO notes_new (id, title, content, created_date, updated_date, pinned, folder_id) VALUES (?, ?, ?, ?, ?, ?, ?)",
                arrayOf<Any?>(newId, title, content, createdDate, updatedDate, pinned, newFolderId)
            )
        }
        notesCursor.close()

        db.execSQL("DROP TABLE notes")
        db.execSQL("DROP TABLE note_folders")
        db.execSQL("ALTER TABLE note_folders_new RENAME TO note_folders")
        db.execSQL("ALTER TABLE notes_new RENAME TO notes")

        // Migrate bookmarks table
        db.execSQL("CREATE TABLE bookmarks_new (url TEXT NOT NULL, title TEXT NOT NULL, description TEXT NOT NULL, created_date INTEGER NOT NULL, updated_date INTEGER NOT NULL, id TEXT PRIMARY KEY NOT NULL)")

        val bookmarksCursor = db.query("SELECT * FROM bookmarks")
        while (bookmarksCursor.moveToNext()) {
            val url = bookmarksCursor.getString(bookmarksCursor.getColumnIndexOrThrow("url"))
            val title = bookmarksCursor.getString(bookmarksCursor.getColumnIndexOrThrow("title"))
            val description =
                bookmarksCursor.getString(bookmarksCursor.getColumnIndexOrThrow("description"))
            val createdDate =
                bookmarksCursor.getLong(bookmarksCursor.getColumnIndexOrThrow("created_date"))
            val updatedDate =
                bookmarksCursor.getLong(bookmarksCursor.getColumnIndexOrThrow("updated_date"))
            val newId = Uuid.random().toString()

            db.execSQL(
                "INSERT INTO bookmarks_new (id, url, title, description, created_date, updated_date) VALUES (?, ?, ?, ?, ?, ?)",
                arrayOf(newId, url, title, description, createdDate, updatedDate)
            )
        }
        bookmarksCursor.close()

        db.execSQL("DROP TABLE bookmarks")
        db.execSQL("ALTER TABLE bookmarks_new RENAME TO bookmarks")

        // Migrate alarms table first and collect alarm ID mapping
        val alarmIdSet = HashSet<Int>()
        db.execSQL("CREATE TABLE alarms_new (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, time INTEGER NOT NULL)")

        val alarmsCursor = db.query("SELECT * FROM alarms")
        while (alarmsCursor.moveToNext()) {
            val oldAlarmId = alarmsCursor.getInt(alarmsCursor.getColumnIndexOrThrow("id"))
            val time = alarmsCursor.getLong(alarmsCursor.getColumnIndexOrThrow("time"))

            db.execSQL(
                "INSERT INTO alarms_new (id, time) VALUES (?, ?)",
                arrayOf<Any?>(oldAlarmId, time)
            )

            alarmIdSet.add(oldAlarmId)
        }
        alarmsCursor.close()

        db.execSQL("DROP TABLE alarms")
        db.execSQL("ALTER TABLE alarms_new RENAME TO alarms")

        // Migrate tasks table
        db.execSQL("CREATE TABLE tasks_new (title TEXT NOT NULL, description TEXT NOT NULL, is_completed INTEGER NOT NULL, priority INTEGER NOT NULL, created_date INTEGER NOT NULL, updated_date INTEGER NOT NULL, sub_tasks TEXT NOT NULL, dueDate INTEGER NOT NULL, recurring INTEGER NOT NULL, frequency INTEGER NOT NULL, frequency_amount INTEGER NOT NULL, alarmId INTEGER, id TEXT PRIMARY KEY NOT NULL)")

        val tasksCursor = db.query("SELECT * FROM tasks")
        while (tasksCursor.moveToNext()) {
            val oldTaskId = tasksCursor.getInt(tasksCursor.getColumnIndexOrThrow("id"))
            val title = tasksCursor.getString(tasksCursor.getColumnIndexOrThrow("title"))
            val description =
                tasksCursor.getString(tasksCursor.getColumnIndexOrThrow("description"))
            val isCompleted = tasksCursor.getInt(tasksCursor.getColumnIndexOrThrow("is_completed"))
            val priority = tasksCursor.getInt(tasksCursor.getColumnIndexOrThrow("priority"))
            val createdDate = tasksCursor.getLong(tasksCursor.getColumnIndexOrThrow("created_date"))
            val updatedDate = tasksCursor.getLong(tasksCursor.getColumnIndexOrThrow("updated_date"))
            val subTasks = tasksCursor.getString(tasksCursor.getColumnIndexOrThrow("sub_tasks"))
            val dueDate = tasksCursor.getLong(tasksCursor.getColumnIndexOrThrow("dueDate"))
            val recurring = tasksCursor.getInt(tasksCursor.getColumnIndexOrThrow("recurring"))
            val frequency = tasksCursor.getInt(tasksCursor.getColumnIndexOrThrow("frequency"))
            val frequencyAmount =
                tasksCursor.getInt(tasksCursor.getColumnIndexOrThrow("frequency_amount"))

            // old version was using the task id as the alarm id
            val alarmId = if (oldTaskId in alarmIdSet) oldTaskId else null
            val newId = Uuid.random().toString()

            db.execSQL(
                "INSERT INTO tasks_new (id, title, description, is_completed, priority, created_date, updated_date, sub_tasks, dueDate, recurring, frequency, frequency_amount, alarmId) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                arrayOf<Any?>(
                    newId,
                    title,
                    description,
                    isCompleted,
                    priority,
                    createdDate,
                    updatedDate,
                    subTasks,
                    dueDate,
                    recurring,
                    frequency,
                    frequencyAmount,
                    alarmId
                )
            )
        }
        tasksCursor.close()

        db.execSQL("DROP TABLE tasks")
        db.execSQL("ALTER TABLE tasks_new RENAME TO tasks")

        // Migrate diary table
        db.execSQL("CREATE TABLE diary_new (title TEXT NOT NULL, content TEXT NOT NULL, created_date INTEGER NOT NULL, updated_date INTEGER NOT NULL, mood INTEGER NOT NULL, id TEXT PRIMARY KEY NOT NULL)")

        val diaryCursor = db.query("SELECT * FROM diary")
        while (diaryCursor.moveToNext()) {
            val title = diaryCursor.getString(diaryCursor.getColumnIndexOrThrow("title"))
            val content = diaryCursor.getString(diaryCursor.getColumnIndexOrThrow("content"))
            val createdDate = diaryCursor.getLong(diaryCursor.getColumnIndexOrThrow("created_date"))
            val updatedDate = diaryCursor.getLong(diaryCursor.getColumnIndexOrThrow("updated_date"))
            val mood = diaryCursor.getInt(diaryCursor.getColumnIndexOrThrow("mood"))
            val newId = Uuid.random().toString()

            db.execSQL(
                "INSERT INTO diary_new (id, title, content, created_date, updated_date, mood) VALUES (?, ?, ?, ?, ?, ?)",
                arrayOf(newId, title, content, createdDate, updatedDate, mood)
            )
        }
        diaryCursor.close()

        db.execSQL("DROP TABLE diary")
        db.execSQL("ALTER TABLE diary_new RENAME TO diary")
    }
}

val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE conversations (
                id TEXT PRIMARY KEY NOT NULL,
                title TEXT NOT NULL DEFAULT '',
                created_date INTEGER NOT NULL DEFAULT 0,
                updated_date INTEGER NOT NULL DEFAULT 0,
                message_count INTEGER NOT NULL DEFAULT 0
            )
        """.trimIndent())

        db.execSQL("""
            CREATE TABLE messages (
                id TEXT PRIMARY KEY NOT NULL,
                conversation_id TEXT NOT NULL,
                role TEXT NOT NULL DEFAULT 'user',
                content TEXT NOT NULL DEFAULT '',
                timestamp INTEGER NOT NULL DEFAULT 0,
                embedding TEXT NOT NULL DEFAULT '',
                tool_calls TEXT NOT NULL DEFAULT '',
                tool_results TEXT NOT NULL DEFAULT '',
                FOREIGN KEY (conversation_id) REFERENCES conversations(id) ON DELETE CASCADE
            )
        """.trimIndent())

        db.execSQL("CREATE INDEX index_messages_conversation_id ON messages(conversation_id)")

        db.execSQL("""
            CREATE VIRTUAL TABLE messages_fts USING fts5(
                content,
                role,
                content='messages',
                content_rowid='rowid'
            )
        """.trimIndent())

        db.execSQL("""
            CREATE TRIGGER messages_ai AFTER INSERT ON messages BEGIN
                INSERT INTO messages_fts(rowid, content, role)
                VALUES (new.rowid, new.content, new.role);
            END
        """.trimIndent())

        db.execSQL("""
            CREATE TRIGGER messages_ad AFTER DELETE ON messages BEGIN
                INSERT INTO messages_fts(messages_fts, rowid, content, role)
                VALUES ('delete', old.rowid, old.content, old.role);
            END
        """.trimIndent())

        db.execSQL("""
            CREATE TRIGGER messages_au AFTER UPDATE ON messages BEGIN
                INSERT INTO messages_fts(messages_fts, rowid, content, role)
                VALUES ('delete', old.rowid, old.content, old.role);
                INSERT INTO messages_fts(rowid, content, role)
                VALUES (new.rowid, new.content, new.role);
            END
        """.trimIndent())

        db.execSQL("""
            CREATE TABLE memory_facts (
                id TEXT PRIMARY KEY NOT NULL,
                category TEXT NOT NULL DEFAULT '',
                fact TEXT NOT NULL DEFAULT '',
                embedding TEXT NOT NULL DEFAULT '',
                confidence REAL NOT NULL DEFAULT 1.0,
                source_conversation_ids TEXT NOT NULL DEFAULT '',
                extracted_date INTEGER NOT NULL DEFAULT 0,
                last_recalled_date INTEGER NOT NULL DEFAULT 0
            )
        """.trimIndent())

        db.execSQL("""
            CREATE VIRTUAL TABLE memory_facts_fts USING fts5(
                fact,
                category,
                content='memory_facts',
                content_rowid='rowid'
            )
        """.trimIndent())

        db.execSQL("""
            CREATE TRIGGER memory_facts_ai AFTER INSERT ON memory_facts BEGIN
                INSERT INTO memory_facts_fts(rowid, fact, category)
                VALUES (new.rowid, new.fact, new.category);
            END
        """.trimIndent())

        db.execSQL("""
            CREATE TRIGGER memory_facts_ad AFTER DELETE ON memory_facts BEGIN
                INSERT INTO memory_facts_fts(memory_facts_fts, rowid, fact, category)
                VALUES ('delete', old.rowid, old.fact, old.category);
            END
        """.trimIndent())

        db.execSQL("""
            CREATE TRIGGER memory_facts_au AFTER UPDATE ON memory_facts BEGIN
                INSERT INTO memory_facts_fts(memory_facts_fts, rowid, fact, category)
                VALUES ('delete', old.rowid, old.fact, old.category);
                INSERT INTO memory_facts_fts(rowid, fact, category)
                VALUES (new.rowid, new.fact, new.category);
            END
        """.trimIndent())

        db.execSQL("""
            CREATE TABLE conversation_threads (
                id TEXT PRIMARY KEY NOT NULL,
                title TEXT NOT NULL DEFAULT '',
                summary TEXT NOT NULL DEFAULT '',
                conversation_ids TEXT NOT NULL DEFAULT '',
                created_date INTEGER NOT NULL DEFAULT 0
            )
        """.trimIndent())

        db.execSQL("""
            CREATE TABLE hive_mind_state (
                id INTEGER PRIMARY KEY NOT NULL DEFAULT 1,
                last_processed_conversation_id TEXT NOT NULL DEFAULT '',
                last_processed_timestamp INTEGER NOT NULL DEFAULT 0,
                total_facts_extracted INTEGER NOT NULL DEFAULT 0,
                total_threads_discovered INTEGER NOT NULL DEFAULT 0,
                is_processing INTEGER NOT NULL DEFAULT 0,
                last_processing_start INTEGER NOT NULL DEFAULT 0,
                last_processing_end INTEGER NOT NULL DEFAULT 0
            )
        """.trimIndent())
    }
}

val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Create prompt_sections table
        db.execSQL("""
            CREATE TABLE prompt_sections (
                id TEXT PRIMARY KEY NOT NULL,
                displayName TEXT NOT NULL,
                masterContent TEXT NOT NULL,
                isEditable INTEGER NOT NULL DEFAULT 1,
                `order` INTEGER NOT NULL
            )
        """.trimIndent())

        // Create prompt_amendments table
        db.execSQL("""
            CREATE TABLE prompt_amendments (
                id TEXT PRIMARY KEY NOT NULL,
                section_id TEXT NOT NULL,
                type TEXT NOT NULL,
                content TEXT NOT NULL,
                proposedBy TEXT NOT NULL,
                status TEXT NOT NULL,
                rationale TEXT,
                created_at INTEGER NOT NULL,
                approved_at INTEGER,
                rejected_at INTEGER,
                rollback_reason TEXT,
                version INTEGER NOT NULL DEFAULT 1,
                FOREIGN KEY (section_id) REFERENCES prompt_sections(id) ON DELETE CASCADE
            )
        """.trimIndent())

        // Create index on section_id for faster queries
        db.execSQL("CREATE INDEX index_prompt_amendments_section_id ON prompt_amendments(section_id)")
    }
}

val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Create guru_defined_tools table
        db.execSQL("""
            CREATE TABLE guru_defined_tools (
                id TEXT PRIMARY KEY NOT NULL,
                name TEXT NOT NULL,
                displayName TEXT NOT NULL,
                description TEXT NOT NULL,
                parameters TEXT NOT NULL,
                implementation TEXT NOT NULL,
                status TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                approved_at INTEGER,
                created_by TEXT NOT NULL,
                last_used_at INTEGER,
                use_count INTEGER NOT NULL DEFAULT 0,
                rationale TEXT
            )
        """.trimIndent())

        // Create index on name for faster lookups
        db.execSQL("CREATE INDEX index_guru_defined_tools_name ON guru_defined_tools(name)")
        
        // Create index on status for filtering
        db.execSQL("CREATE INDEX index_guru_defined_tools_status ON guru_defined_tools(status)")
    }
}

val MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Create guru_skills table
        db.execSQL("""
            CREATE TABLE guru_skills (
                id TEXT PRIMARY KEY NOT NULL,
                name TEXT NOT NULL,
                displayName TEXT NOT NULL,
                description TEXT NOT NULL,
                trigger TEXT NOT NULL,
                triggerConfig TEXT NOT NULL,
                steps TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                last_run_at INTEGER,
                run_count INTEGER NOT NULL DEFAULT 0,
                enabled INTEGER NOT NULL DEFAULT 1
            )
        """.trimIndent())

        // Create index on name for faster lookups
        db.execSQL("CREATE INDEX index_guru_skills_name ON guru_skills(name)")
        
        // Create index on trigger for filtering by trigger type
        db.execSQL("CREATE INDEX index_guru_skills_trigger ON guru_skills(trigger)")
        
        // Create index on enabled for filtering
        db.execSQL("CREATE INDEX index_guru_skills_enabled ON guru_skills(enabled)")
    }
}

val MIGRATION_9_10 = object : Migration(9, 10) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Create guru_hooks table
        db.execSQL("""
            CREATE TABLE guru_hooks (
                id TEXT PRIMARY KEY NOT NULL,
                name TEXT NOT NULL,
                displayName TEXT NOT NULL,
                description TEXT NOT NULL,
                eventType TEXT NOT NULL,
                triggerTiming TEXT NOT NULL,
                condition TEXT,
                action TEXT NOT NULL,
                priority INTEGER NOT NULL,
                enabled INTEGER NOT NULL DEFAULT 1,
                createdAt INTEGER NOT NULL,
                lastTriggeredAt INTEGER,
                triggerCount INTEGER NOT NULL DEFAULT 0
            )
        """.trimIndent())

        // Create index on name for faster lookups
        db.execSQL("CREATE INDEX index_guru_hooks_name ON guru_hooks(name)")
        
        // Create index on eventType for filtering
        db.execSQL("CREATE INDEX index_guru_hooks_eventType ON guru_hooks(eventType)")
        
        // Create index on enabled for filtering
        db.execSQL("CREATE INDEX index_guru_hooks_enabled ON guru_hooks(enabled)")
    }
}

val MIGRATION_10_11 = object : Migration(10, 11) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Create guru_jobs table
        db.execSQL("""
            CREATE TABLE guru_jobs (
                id TEXT PRIMARY KEY NOT NULL,
                name TEXT NOT NULL,
                displayName TEXT NOT NULL,
                description TEXT NOT NULL,
                scheduleType TEXT NOT NULL,
                scheduleConfig TEXT NOT NULL,
                action TEXT NOT NULL,
                input TEXT,
                enabled INTEGER NOT NULL DEFAULT 1,
                createdAt INTEGER NOT NULL,
                lastRunAt INTEGER,
                nextRunAt INTEGER,
                runCount INTEGER NOT NULL DEFAULT 0,
                lastResult TEXT,
                failureCount INTEGER NOT NULL DEFAULT 0,
                lastError TEXT
            )
        """.trimIndent())

        // Create index on name for faster lookups
        db.execSQL("CREATE INDEX index_guru_jobs_name ON guru_jobs(name)")
        
        // Create index on enabled for filtering
        db.execSQL("CREATE INDEX index_guru_jobs_enabled ON guru_jobs(enabled)")
        
        // Create index on nextRunAt for finding due jobs
        db.execSQL("CREATE INDEX index_guru_jobs_nextRunAt ON guru_jobs(nextRunAt)")
    }
}

val MIGRATION_11_12 = object : Migration(11, 12) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Create guru_thought_cycles table
        db.execSQL("""
            CREATE TABLE guru_thought_cycles (
                id TEXT PRIMARY KEY NOT NULL,
                name TEXT NOT NULL,
                displayName TEXT NOT NULL,
                description TEXT NOT NULL,
                triggerType TEXT NOT NULL,
                triggerConfig TEXT NOT NULL,
                thoughtProcess TEXT NOT NULL,
                outputType TEXT NOT NULL,
                outputConfig TEXT,
                enabled INTEGER NOT NULL DEFAULT 1,
                createdAt INTEGER NOT NULL,
                lastRunAt INTEGER,
                lastResult TEXT,
                runCount INTEGER NOT NULL DEFAULT 0,
                insightCount INTEGER NOT NULL DEFAULT 0,
                actionCount INTEGER NOT NULL DEFAULT 0,
                proposalCount INTEGER NOT NULL DEFAULT 0
            )
        """.trimIndent())

        // Create indexes for thought cycles
        db.execSQL("CREATE INDEX index_guru_thought_cycles_name ON guru_thought_cycles(name)")
        db.execSQL("CREATE INDEX index_guru_thought_cycles_enabled ON guru_thought_cycles(enabled)")
        db.execSQL("CREATE INDEX index_guru_thought_cycles_lastRunAt ON guru_thought_cycles(lastRunAt)")
        
        // Create guru_insights table
        db.execSQL("""
            CREATE TABLE guru_insights (
                id TEXT PRIMARY KEY NOT NULL,
                cycleId TEXT NOT NULL,
                type TEXT NOT NULL,
                title TEXT NOT NULL,
                content TEXT NOT NULL,
                confidence REAL NOT NULL,
                source TEXT NOT NULL,
                actionable INTEGER NOT NULL DEFAULT 0,
                actionTaken INTEGER NOT NULL DEFAULT 0,
                actionType TEXT,
                actionId TEXT,
                createdAt INTEGER NOT NULL,
                acknowledgedAt INTEGER,
                dismissedAt INTEGER
            )
        """.trimIndent())

        // Create indexes for insights
        db.execSQL("CREATE INDEX index_guru_insights_cycleId ON guru_insights(cycleId)")
        db.execSQL("CREATE INDEX index_guru_insights_createdAt ON guru_insights(createdAt)")
        db.execSQL("CREATE INDEX index_guru_insights_type ON guru_insights(type)")
    }
}

val MIGRATION_12_13 = object : Migration(12, 13) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Create projects table (replacing bookmarks conceptually, but keeping bookmarks for backward compat)
        db.execSQL("""
            CREATE TABLE projects (
                id TEXT PRIMARY KEY NOT NULL,
                title TEXT NOT NULL,
                description TEXT NOT NULL DEFAULT '',
                prompt_overlay TEXT NOT NULL DEFAULT '',
                created_date INTEGER NOT NULL DEFAULT 0,
                updated_date INTEGER NOT NULL DEFAULT 0,
                color TEXT NOT NULL DEFAULT '#6366f1',
                icon TEXT NOT NULL DEFAULT 'folder',
                is_active INTEGER NOT NULL DEFAULT 1,
                message_count INTEGER NOT NULL DEFAULT 0,
                document_count INTEGER NOT NULL DEFAULT 0,
                last_message_preview TEXT NOT NULL DEFAULT '',
                last_message_date INTEGER NOT NULL DEFAULT 0
            )
        """.trimIndent())

        // Create indexes for projects
        db.execSQL("CREATE INDEX index_projects_title ON projects(title)")
        db.execSQL("CREATE INDEX index_projects_updated_date ON projects(updated_date)")

        // Create project_messages table
        db.execSQL("""
            CREATE TABLE project_messages (
                id TEXT PRIMARY KEY NOT NULL,
                project_id TEXT NOT NULL,
                role TEXT NOT NULL DEFAULT 'user',
                content TEXT NOT NULL DEFAULT '',
                timestamp INTEGER NOT NULL DEFAULT 0,
                embedding TEXT NOT NULL DEFAULT '',
                tool_calls TEXT NOT NULL DEFAULT '',
                tool_results TEXT NOT NULL DEFAULT '',
                FOREIGN KEY (project_id) REFERENCES projects(id) ON DELETE CASCADE
            )
        """.trimIndent())

        // Create indexes for project_messages
        db.execSQL("CREATE INDEX index_project_messages_project_id ON project_messages(project_id)")
        db.execSQL("CREATE INDEX index_project_messages_timestamp ON project_messages(timestamp)")

        // Create project_documents table
        db.execSQL("""
            CREATE TABLE project_documents (
                id TEXT PRIMARY KEY NOT NULL,
                project_id TEXT NOT NULL,
                title TEXT NOT NULL,
                content TEXT NOT NULL DEFAULT '',
                type TEXT NOT NULL DEFAULT 'TEXT',
                created_date INTEGER NOT NULL DEFAULT 0,
                updated_date INTEGER NOT NULL DEFAULT 0,
                embedding TEXT NOT NULL DEFAULT '',
                source_uri TEXT NOT NULL DEFAULT '',
                size INTEGER NOT NULL DEFAULT 0,
                FOREIGN KEY (project_id) REFERENCES projects(id) ON DELETE CASCADE
            )
        """.trimIndent())

        // Create indexes for project_documents
        db.execSQL("CREATE INDEX index_project_documents_project_id ON project_documents(project_id)")
        db.execSQL("CREATE INDEX index_project_documents_title ON project_documents(title)")

        // Create project_facts table
        db.execSQL("""
            CREATE TABLE project_facts (
                id TEXT PRIMARY KEY NOT NULL,
                project_id TEXT NOT NULL,
                category TEXT NOT NULL DEFAULT '',
                fact TEXT NOT NULL DEFAULT '',
                embedding TEXT NOT NULL DEFAULT '',
                confidence REAL NOT NULL DEFAULT 1.0,
                source_message_ids TEXT NOT NULL DEFAULT '',
                extracted_date INTEGER NOT NULL DEFAULT 0,
                last_recalled_date INTEGER NOT NULL DEFAULT 0,
                FOREIGN KEY (project_id) REFERENCES projects(id) ON DELETE CASCADE
            )
        """.trimIndent())

        // Create indexes for project_facts
        db.execSQL("CREATE INDEX index_project_facts_project_id ON project_facts(project_id)")
        db.execSQL("CREATE INDEX index_project_facts_category ON project_facts(category)")

        // Create FTS5 virtual tables for full-text search
        db.execSQL("""
            CREATE VIRTUAL TABLE project_messages_fts USING fts5(
                content,
                role,
                content='project_messages',
                content_rowid='rowid'
            )
        """.trimIndent())

        db.execSQL("""
            CREATE TRIGGER project_messages_ai AFTER INSERT ON project_messages BEGIN
                INSERT INTO project_messages_fts(rowid, content, role)
                VALUES (new.rowid, new.content, new.role);
            END
        """.trimIndent())

        db.execSQL("""
            CREATE TRIGGER project_messages_ad AFTER DELETE ON project_messages BEGIN
                INSERT INTO project_messages_fts(project_messages_fts, rowid, content, role)
                VALUES ('delete', old.rowid, old.content, old.role);
            END
        """.trimIndent())

        db.execSQL("""
            CREATE TRIGGER project_messages_au AFTER UPDATE ON project_messages BEGIN
                INSERT INTO project_messages_fts(project_messages_fts, rowid, content, role)
                VALUES ('delete', old.rowid, old.content, old.role);
                INSERT INTO project_messages_fts(rowid, content, role)
                VALUES (new.rowid, new.content, new.role);
            END
        """.trimIndent())

        db.execSQL("""
            CREATE VIRTUAL TABLE project_documents_fts USING fts5(
                title,
                content,
                content='project_documents',
                content_rowid='rowid'
            )
        """.trimIndent())

        db.execSQL("""
            CREATE TRIGGER project_documents_ai AFTER INSERT ON project_documents BEGIN
                INSERT INTO project_documents_fts(rowid, title, content)
                VALUES (new.rowid, new.title, new.content);
            END
        """.trimIndent())

        db.execSQL("""
            CREATE TRIGGER project_documents_ad AFTER DELETE ON project_documents BEGIN
                INSERT INTO project_documents_fts(project_documents_fts, rowid, title, content)
                VALUES ('delete', old.rowid, old.title, old.content);
            END
        """.trimIndent())

        db.execSQL("""
            CREATE TRIGGER project_documents_au AFTER UPDATE ON project_documents BEGIN
                INSERT INTO project_documents_fts(project_documents_fts, rowid, title, content)
                VALUES ('delete', old.rowid, old.title, old.content);
                INSERT INTO project_documents_fts(rowid, title, content)
                VALUES (new.rowid, new.title, new.content);
            END
        """.trimIndent())

        db.execSQL("""
            CREATE VIRTUAL TABLE project_facts_fts USING fts5(
                fact,
                category,
                content='project_facts',
                content_rowid='rowid'
            )
        """.trimIndent())

        db.execSQL("""
            CREATE TRIGGER project_facts_ai AFTER INSERT ON project_facts BEGIN
                INSERT INTO project_facts_fts(rowid, fact, category)
                VALUES (new.rowid, new.fact, new.category);
            END
        """.trimIndent())

        db.execSQL("""
            CREATE TRIGGER project_facts_ad AFTER DELETE ON project_facts BEGIN
                INSERT INTO project_facts_fts(project_facts_fts, rowid, fact, category)
                VALUES ('delete', old.rowid, old.fact, old.category);
            END
        """.trimIndent())

        db.execSQL("""
            CREATE TRIGGER project_facts_au AFTER UPDATE ON project_facts BEGIN
                INSERT INTO project_facts_fts(project_facts_fts, rowid, fact, category)
                VALUES ('delete', old.rowid, old.fact, old.category);
                INSERT INTO project_facts_fts(rowid, fact, category)
                VALUES (new.rowid, new.fact, new.category);
            END
        """.trimIndent())
    }
}

val MIGRATION_13_14 = object : Migration(13, 14) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Rename prompt_sections.order to section_order
        db.execSQL("ALTER TABLE prompt_sections RENAME COLUMN `order` TO section_order")

        // Rebuild conversations table with DEFAULT clauses
        db.execSQL("""
            CREATE TABLE conversations_new (
                id TEXT PRIMARY KEY NOT NULL,
                title TEXT NOT NULL DEFAULT '',
                created_date INTEGER NOT NULL DEFAULT 0,
                updated_date INTEGER NOT NULL DEFAULT 0,
                message_count INTEGER NOT NULL DEFAULT 0
            )
        """.trimIndent())
        db.execSQL("INSERT INTO conversations_new SELECT * FROM conversations")
        db.execSQL("DROP TABLE conversations")
        db.execSQL("ALTER TABLE conversations_new RENAME TO conversations")

        // Rebuild messages table with DEFAULT clauses (including FTS5)
        db.execSQL("DROP TRIGGER IF EXISTS messages_ai")
        db.execSQL("DROP TRIGGER IF EXISTS messages_ad")
        db.execSQL("DROP TRIGGER IF EXISTS messages_au")
        db.execSQL("DROP TABLE IF EXISTS messages_fts")

        db.execSQL("""
            CREATE TABLE messages_new (
                id TEXT PRIMARY KEY NOT NULL,
                conversation_id TEXT NOT NULL,
                role TEXT NOT NULL DEFAULT 'user',
                content TEXT NOT NULL DEFAULT '',
                timestamp INTEGER NOT NULL DEFAULT 0,
                embedding TEXT NOT NULL DEFAULT '',
                tool_calls TEXT NOT NULL DEFAULT '',
                tool_results TEXT NOT NULL DEFAULT '',
                FOREIGN KEY (conversation_id) REFERENCES conversations(id) ON DELETE CASCADE
            )
        """.trimIndent())
        db.execSQL("INSERT INTO messages_new SELECT * FROM messages")
        db.execSQL("DROP TABLE messages")
        db.execSQL("ALTER TABLE messages_new RENAME TO messages")
        db.execSQL("CREATE INDEX index_messages_conversation_id ON messages(conversation_id)")

        db.execSQL("""
            CREATE VIRTUAL TABLE messages_fts USING fts5(
                content,
                role,
                content='messages',
                content_rowid='rowid'
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TRIGGER messages_ai AFTER INSERT ON messages BEGIN
                INSERT INTO messages_fts(rowid, content, role)
                VALUES (new.rowid, new.content, new.role);
            END
        """.trimIndent())
        db.execSQL("""
            CREATE TRIGGER messages_ad AFTER DELETE ON messages BEGIN
                INSERT INTO messages_fts(messages_fts, rowid, content, role)
                VALUES ('delete', old.rowid, old.content, old.role);
            END
        """.trimIndent())
        db.execSQL("""
            CREATE TRIGGER messages_au AFTER UPDATE ON messages BEGIN
                INSERT INTO messages_fts(messages_fts, rowid, content, role)
                VALUES ('delete', old.rowid, old.content, old.role);
                INSERT INTO messages_fts(rowid, content, role)
                VALUES (new.rowid, new.content, new.role);
            END
        """.trimIndent())
        db.execSQL("INSERT INTO messages_fts(messages_fts) VALUES('rebuild')")

        // Rebuild memory_facts table with DEFAULT clauses (including FTS5)
        db.execSQL("DROP TRIGGER IF EXISTS memory_facts_ai")
        db.execSQL("DROP TRIGGER IF EXISTS memory_facts_ad")
        db.execSQL("DROP TRIGGER IF EXISTS memory_facts_au")
        db.execSQL("DROP TABLE IF EXISTS memory_facts_fts")

        db.execSQL("""
            CREATE TABLE memory_facts_new (
                id TEXT PRIMARY KEY NOT NULL,
                category TEXT NOT NULL DEFAULT '',
                fact TEXT NOT NULL DEFAULT '',
                embedding TEXT NOT NULL DEFAULT '',
                confidence REAL NOT NULL DEFAULT 1.0,
                source_conversation_ids TEXT NOT NULL DEFAULT '',
                extracted_date INTEGER NOT NULL DEFAULT 0,
                last_recalled_date INTEGER NOT NULL DEFAULT 0
            )
        """.trimIndent())
        db.execSQL("INSERT INTO memory_facts_new SELECT * FROM memory_facts")
        db.execSQL("DROP TABLE memory_facts")
        db.execSQL("ALTER TABLE memory_facts_new RENAME TO memory_facts")

        db.execSQL("""
            CREATE VIRTUAL TABLE memory_facts_fts USING fts5(
                fact,
                category,
                content='memory_facts',
                content_rowid='rowid'
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TRIGGER memory_facts_ai AFTER INSERT ON memory_facts BEGIN
                INSERT INTO memory_facts_fts(rowid, fact, category)
                VALUES (new.rowid, new.fact, new.category);
            END
        """.trimIndent())
        db.execSQL("""
            CREATE TRIGGER memory_facts_ad AFTER DELETE ON memory_facts BEGIN
                INSERT INTO memory_facts_fts(memory_facts_fts, rowid, fact, category)
                VALUES ('delete', old.rowid, old.fact, old.category);
            END
        """.trimIndent())
        db.execSQL("""
            CREATE TRIGGER memory_facts_au AFTER UPDATE ON memory_facts BEGIN
                INSERT INTO memory_facts_fts(memory_facts_fts, rowid, fact, category)
                VALUES ('delete', old.rowid, old.fact, old.category);
                INSERT INTO memory_facts_fts(rowid, fact, category)
                VALUES (new.rowid, new.fact, new.category);
            END
        """.trimIndent())
        db.execSQL("INSERT INTO memory_facts_fts(memory_facts_fts) VALUES('rebuild')")

        // Rebuild conversation_threads table with DEFAULT clauses
        db.execSQL("""
            CREATE TABLE conversation_threads_new (
                id TEXT PRIMARY KEY NOT NULL,
                title TEXT NOT NULL DEFAULT '',
                summary TEXT NOT NULL DEFAULT '',
                conversation_ids TEXT NOT NULL DEFAULT '',
                created_date INTEGER NOT NULL DEFAULT 0
            )
        """.trimIndent())
        db.execSQL("INSERT INTO conversation_threads_new SELECT * FROM conversation_threads")
        db.execSQL("DROP TABLE conversation_threads")
        db.execSQL("ALTER TABLE conversation_threads_new RENAME TO conversation_threads")

        // Rebuild hive_mind_state table with DEFAULT clauses
        db.execSQL("""
            CREATE TABLE hive_mind_state_new (
                id INTEGER PRIMARY KEY NOT NULL DEFAULT 1,
                last_processed_conversation_id TEXT NOT NULL DEFAULT '',
                last_processed_timestamp INTEGER NOT NULL DEFAULT 0,
                total_facts_extracted INTEGER NOT NULL DEFAULT 0,
                total_threads_discovered INTEGER NOT NULL DEFAULT 0,
                is_processing INTEGER NOT NULL DEFAULT 0,
                last_processing_start INTEGER NOT NULL DEFAULT 0,
                last_processing_end INTEGER NOT NULL DEFAULT 0
            )
        """.trimIndent())
        db.execSQL("INSERT INTO hive_mind_state_new SELECT * FROM hive_mind_state")
        db.execSQL("DROP TABLE hive_mind_state")
        db.execSQL("ALTER TABLE hive_mind_state_new RENAME TO hive_mind_state")

        // Rebuild prompt_sections table with DEFAULT clauses (already renamed order to section_order)
        db.execSQL("""
            CREATE TABLE prompt_sections_new (
                id TEXT PRIMARY KEY NOT NULL,
                displayName TEXT NOT NULL,
                masterContent TEXT NOT NULL,
                isEditable INTEGER NOT NULL DEFAULT 1,
                section_order INTEGER NOT NULL
            )
        """.trimIndent())
        db.execSQL("INSERT INTO prompt_sections_new (id, displayName, masterContent, isEditable, section_order) SELECT id, displayName, masterContent, isEditable, section_order FROM prompt_sections")
        db.execSQL("DROP TABLE prompt_sections")
        db.execSQL("ALTER TABLE prompt_sections_new RENAME TO prompt_sections")

        // Rebuild prompt_amendments table with DEFAULT clauses
        db.execSQL("""
            CREATE TABLE prompt_amendments_new (
                id TEXT PRIMARY KEY NOT NULL,
                section_id TEXT NOT NULL,
                type TEXT NOT NULL,
                content TEXT NOT NULL,
                proposedBy TEXT NOT NULL,
                status TEXT NOT NULL,
                rationale TEXT,
                created_at INTEGER NOT NULL,
                approved_at INTEGER,
                rejected_at INTEGER,
                rollback_reason TEXT,
                version INTEGER NOT NULL DEFAULT 1,
                FOREIGN KEY (section_id) REFERENCES prompt_sections(id) ON DELETE CASCADE
            )
        """.trimIndent())
        db.execSQL("INSERT INTO prompt_amendments_new SELECT * FROM prompt_amendments")
        db.execSQL("DROP TABLE prompt_amendments")
        db.execSQL("ALTER TABLE prompt_amendments_new RENAME TO prompt_amendments")
        db.execSQL("CREATE INDEX index_prompt_amendments_section_id ON prompt_amendments(section_id)")

        // Rebuild guru_defined_tools table with DEFAULT clauses
        db.execSQL("""
            CREATE TABLE guru_defined_tools_new (
                id TEXT PRIMARY KEY NOT NULL,
                name TEXT NOT NULL,
                displayName TEXT NOT NULL,
                description TEXT NOT NULL,
                parameters TEXT NOT NULL,
                implementation TEXT NOT NULL,
                status TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                approved_at INTEGER,
                created_by TEXT NOT NULL,
                last_used_at INTEGER,
                use_count INTEGER NOT NULL DEFAULT 0,
                rationale TEXT
            )
        """.trimIndent())
        db.execSQL("INSERT INTO guru_defined_tools_new SELECT * FROM guru_defined_tools")
        db.execSQL("DROP TABLE guru_defined_tools")
        db.execSQL("ALTER TABLE guru_defined_tools_new RENAME TO guru_defined_tools")
        db.execSQL("CREATE INDEX index_guru_defined_tools_name ON guru_defined_tools(name)")
        db.execSQL("CREATE INDEX index_guru_defined_tools_status ON guru_defined_tools(status)")

        // Rebuild guru_skills table with DEFAULT clauses
        db.execSQL("""
            CREATE TABLE guru_skills_new (
                id TEXT PRIMARY KEY NOT NULL,
                name TEXT NOT NULL,
                displayName TEXT NOT NULL,
                description TEXT NOT NULL,
                trigger TEXT NOT NULL,
                triggerConfig TEXT NOT NULL,
                steps TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                last_run_at INTEGER,
                run_count INTEGER NOT NULL DEFAULT 0,
                enabled INTEGER NOT NULL DEFAULT 1
            )
        """.trimIndent())
        db.execSQL("INSERT INTO guru_skills_new SELECT * FROM guru_skills")
        db.execSQL("DROP TABLE guru_skills")
        db.execSQL("ALTER TABLE guru_skills_new RENAME TO guru_skills")
        db.execSQL("CREATE INDEX index_guru_skills_name ON guru_skills(name)")
        db.execSQL("CREATE INDEX index_guru_skills_trigger ON guru_skills(trigger)")
        db.execSQL("CREATE INDEX index_guru_skills_enabled ON guru_skills(enabled)")

        // Rebuild guru_hooks table with DEFAULT clauses
        db.execSQL("""
            CREATE TABLE guru_hooks_new (
                id TEXT PRIMARY KEY NOT NULL,
                name TEXT NOT NULL,
                displayName TEXT NOT NULL,
                description TEXT NOT NULL,
                eventType TEXT NOT NULL,
                triggerTiming TEXT NOT NULL,
                condition TEXT,
                action TEXT NOT NULL,
                priority INTEGER NOT NULL,
                enabled INTEGER NOT NULL DEFAULT 1,
                createdAt INTEGER NOT NULL,
                lastTriggeredAt INTEGER,
                triggerCount INTEGER NOT NULL DEFAULT 0
            )
        """.trimIndent())
        db.execSQL("INSERT INTO guru_hooks_new SELECT * FROM guru_hooks")
        db.execSQL("DROP TABLE guru_hooks")
        db.execSQL("ALTER TABLE guru_hooks_new RENAME TO guru_hooks")
        db.execSQL("CREATE INDEX index_guru_hooks_name ON guru_hooks(name)")
        db.execSQL("CREATE INDEX index_guru_hooks_eventType ON guru_hooks(eventType)")
        db.execSQL("CREATE INDEX index_guru_hooks_enabled ON guru_hooks(enabled)")

        // Rebuild guru_jobs table with DEFAULT clauses
        db.execSQL("""
            CREATE TABLE guru_jobs_new (
                id TEXT PRIMARY KEY NOT NULL,
                name TEXT NOT NULL,
                displayName TEXT NOT NULL,
                description TEXT NOT NULL,
                scheduleType TEXT NOT NULL,
                scheduleConfig TEXT NOT NULL,
                action TEXT NOT NULL,
                input TEXT,
                enabled INTEGER NOT NULL DEFAULT 1,
                createdAt INTEGER NOT NULL,
                lastRunAt INTEGER,
                nextRunAt INTEGER,
                runCount INTEGER NOT NULL DEFAULT 0,
                lastResult TEXT,
                failureCount INTEGER NOT NULL DEFAULT 0,
                lastError TEXT
            )
        """.trimIndent())
        db.execSQL("INSERT INTO guru_jobs_new SELECT * FROM guru_jobs")
        db.execSQL("DROP TABLE guru_jobs")
        db.execSQL("ALTER TABLE guru_jobs_new RENAME TO guru_jobs")
        db.execSQL("CREATE INDEX index_guru_jobs_name ON guru_jobs(name)")
        db.execSQL("CREATE INDEX index_guru_jobs_enabled ON guru_jobs(enabled)")
        db.execSQL("CREATE INDEX index_guru_jobs_nextRunAt ON guru_jobs(nextRunAt)")

        // Rebuild guru_thought_cycles table with DEFAULT clauses
        db.execSQL("""
            CREATE TABLE guru_thought_cycles_new (
                id TEXT PRIMARY KEY NOT NULL,
                name TEXT NOT NULL,
                displayName TEXT NOT NULL,
                description TEXT NOT NULL,
                triggerType TEXT NOT NULL,
                triggerConfig TEXT NOT NULL,
                thoughtProcess TEXT NOT NULL,
                outputType TEXT NOT NULL,
                outputConfig TEXT,
                enabled INTEGER NOT NULL DEFAULT 1,
                createdAt INTEGER NOT NULL,
                lastRunAt INTEGER,
                lastResult TEXT,
                runCount INTEGER NOT NULL DEFAULT 0,
                insightCount INTEGER NOT NULL DEFAULT 0,
                actionCount INTEGER NOT NULL DEFAULT 0,
                proposalCount INTEGER NOT NULL DEFAULT 0
            )
        """.trimIndent())
        db.execSQL("INSERT INTO guru_thought_cycles_new SELECT * FROM guru_thought_cycles")
        db.execSQL("DROP TABLE guru_thought_cycles")
        db.execSQL("ALTER TABLE guru_thought_cycles_new RENAME TO guru_thought_cycles")
        db.execSQL("CREATE INDEX index_guru_thought_cycles_name ON guru_thought_cycles(name)")
        db.execSQL("CREATE INDEX index_guru_thought_cycles_enabled ON guru_thought_cycles(enabled)")
        db.execSQL("CREATE INDEX index_guru_thought_cycles_lastRunAt ON guru_thought_cycles(lastRunAt)")

        // Rebuild guru_insights table with DEFAULT clauses
        db.execSQL("""
            CREATE TABLE guru_insights_new (
                id TEXT PRIMARY KEY NOT NULL,
                cycleId TEXT NOT NULL,
                type TEXT NOT NULL,
                title TEXT NOT NULL,
                content TEXT NOT NULL,
                confidence REAL NOT NULL,
                source TEXT NOT NULL,
                actionable INTEGER NOT NULL DEFAULT 0,
                actionTaken INTEGER NOT NULL DEFAULT 0,
                actionType TEXT,
                actionId TEXT,
                createdAt INTEGER NOT NULL,
                acknowledgedAt INTEGER,
                dismissedAt INTEGER
            )
        """.trimIndent())
        db.execSQL("INSERT INTO guru_insights_new SELECT * FROM guru_insights")
        db.execSQL("DROP TABLE guru_insights")
        db.execSQL("ALTER TABLE guru_insights_new RENAME TO guru_insights")
        db.execSQL("CREATE INDEX index_guru_insights_cycleId ON guru_insights(cycleId)")
        db.execSQL("CREATE INDEX index_guru_insights_createdAt ON guru_insights(createdAt)")
        db.execSQL("CREATE INDEX index_guru_insights_type ON guru_insights(type)")

        // Rebuild notes table with DEFAULT clauses
        db.execSQL("""
            CREATE TABLE notes_new (
                title TEXT NOT NULL,
                content TEXT NOT NULL,
                created_date INTEGER NOT NULL,
                updated_date INTEGER NOT NULL,
                pinned INTEGER NOT NULL,
                folder_id TEXT,
                id TEXT PRIMARY KEY NOT NULL,
                FOREIGN KEY (folder_id) REFERENCES note_folders(id) ON DELETE CASCADE
            )
        """.trimIndent())
        db.execSQL("INSERT INTO notes_new SELECT * FROM notes")
        db.execSQL("DROP TABLE notes")
        db.execSQL("ALTER TABLE notes_new RENAME TO notes")
        db.execSQL("CREATE INDEX index_notes_folder_id ON notes(folder_id)")

        // Rebuild projects table with DEFAULT clauses
        db.execSQL("""
            CREATE TABLE projects_new (
                id TEXT PRIMARY KEY NOT NULL,
                title TEXT NOT NULL,
                description TEXT NOT NULL DEFAULT '',
                prompt_overlay TEXT NOT NULL DEFAULT '',
                created_date INTEGER NOT NULL DEFAULT 0,
                updated_date INTEGER NOT NULL DEFAULT 0,
                color TEXT NOT NULL DEFAULT '#6366f1',
                icon TEXT NOT NULL DEFAULT 'folder',
                is_active INTEGER NOT NULL DEFAULT 1,
                message_count INTEGER NOT NULL DEFAULT 0,
                document_count INTEGER NOT NULL DEFAULT 0,
                last_message_preview TEXT NOT NULL DEFAULT '',
                last_message_date INTEGER NOT NULL DEFAULT 0
            )
        """.trimIndent())
        db.execSQL("INSERT INTO projects_new SELECT * FROM projects")
        db.execSQL("DROP TABLE projects")
        db.execSQL("ALTER TABLE projects_new RENAME TO projects")
        db.execSQL("CREATE INDEX index_projects_title ON projects(title)")
        db.execSQL("CREATE INDEX index_projects_updated_date ON projects(updated_date)")

        // Rebuild project_messages table with DEFAULT clauses (including FTS5)
        db.execSQL("DROP TRIGGER IF EXISTS project_messages_ai")
        db.execSQL("DROP TRIGGER IF EXISTS project_messages_ad")
        db.execSQL("DROP TRIGGER IF EXISTS project_messages_au")
        db.execSQL("DROP TABLE IF EXISTS project_messages_fts")

        db.execSQL("""
            CREATE TABLE project_messages_new (
                id TEXT PRIMARY KEY NOT NULL,
                project_id TEXT NOT NULL,
                role TEXT NOT NULL DEFAULT 'user',
                content TEXT NOT NULL DEFAULT '',
                timestamp INTEGER NOT NULL DEFAULT 0,
                embedding TEXT NOT NULL DEFAULT '',
                tool_calls TEXT NOT NULL DEFAULT '',
                tool_results TEXT NOT NULL DEFAULT '',
                FOREIGN KEY (project_id) REFERENCES projects(id) ON DELETE CASCADE
            )
        """.trimIndent())
        db.execSQL("INSERT INTO project_messages_new SELECT * FROM project_messages")
        db.execSQL("DROP TABLE project_messages")
        db.execSQL("ALTER TABLE project_messages_new RENAME TO project_messages")
        db.execSQL("CREATE INDEX index_project_messages_project_id ON project_messages(project_id)")
        db.execSQL("CREATE INDEX index_project_messages_timestamp ON project_messages(timestamp)")

        db.execSQL("""
            CREATE VIRTUAL TABLE project_messages_fts USING fts5(
                content,
                role,
                content='project_messages',
                content_rowid='rowid'
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TRIGGER project_messages_ai AFTER INSERT ON project_messages BEGIN
                INSERT INTO project_messages_fts(rowid, content, role)
                VALUES (new.rowid, new.content, new.role);
            END
        """.trimIndent())
        db.execSQL("""
            CREATE TRIGGER project_messages_ad AFTER DELETE ON project_messages BEGIN
                INSERT INTO project_messages_fts(project_messages_fts, rowid, content, role)
                VALUES ('delete', old.rowid, old.content, old.role);
            END
        """.trimIndent())
        db.execSQL("""
            CREATE TRIGGER project_messages_au AFTER UPDATE ON project_messages BEGIN
                INSERT INTO project_messages_fts(project_messages_fts, rowid, content, role)
                VALUES ('delete', old.rowid, old.content, old.role);
                INSERT INTO project_messages_fts(rowid, content, role)
                VALUES (new.rowid, new.content, new.role);
            END
        """.trimIndent())
        db.execSQL("INSERT INTO project_messages_fts(project_messages_fts) VALUES('rebuild')")

        // Rebuild project_documents table with DEFAULT clauses (including FTS5)
        db.execSQL("DROP TRIGGER IF EXISTS project_documents_ai")
        db.execSQL("DROP TRIGGER IF EXISTS project_documents_ad")
        db.execSQL("DROP TRIGGER IF EXISTS project_documents_au")
        db.execSQL("DROP TABLE IF EXISTS project_documents_fts")

        db.execSQL("""
            CREATE TABLE project_documents_new (
                id TEXT PRIMARY KEY NOT NULL,
                project_id TEXT NOT NULL,
                title TEXT NOT NULL,
                content TEXT NOT NULL DEFAULT '',
                type TEXT NOT NULL DEFAULT 'TEXT',
                created_date INTEGER NOT NULL DEFAULT 0,
                updated_date INTEGER NOT NULL DEFAULT 0,
                embedding TEXT NOT NULL DEFAULT '',
                source_uri TEXT NOT NULL DEFAULT '',
                size INTEGER NOT NULL DEFAULT 0,
                FOREIGN KEY (project_id) REFERENCES projects(id) ON DELETE CASCADE
            )
        """.trimIndent())
        db.execSQL("INSERT INTO project_documents_new SELECT * FROM project_documents")
        db.execSQL("DROP TABLE project_documents")
        db.execSQL("ALTER TABLE project_documents_new RENAME TO project_documents")
        db.execSQL("CREATE INDEX index_project_documents_project_id ON project_documents(project_id)")
        db.execSQL("CREATE INDEX index_project_documents_title ON project_documents(title)")

        db.execSQL("""
            CREATE VIRTUAL TABLE project_documents_fts USING fts5(
                title,
                content,
                content='project_documents',
                content_rowid='rowid'
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TRIGGER project_documents_ai AFTER INSERT ON project_documents BEGIN
                INSERT INTO project_documents_fts(rowid, title, content)
                VALUES (new.rowid, new.title, new.content);
            END
        """.trimIndent())
        db.execSQL("""
            CREATE TRIGGER project_documents_ad AFTER DELETE ON project_documents BEGIN
                INSERT INTO project_documents_fts(project_documents_fts, rowid, title, content)
                VALUES ('delete', old.rowid, old.title, old.content);
            END
        """.trimIndent())
        db.execSQL("""
            CREATE TRIGGER project_documents_au AFTER UPDATE ON project_documents BEGIN
                INSERT INTO project_documents_fts(project_documents_fts, rowid, title, content)
                VALUES ('delete', old.rowid, old.title, old.content);
                INSERT INTO project_documents_fts(rowid, title, content)
                VALUES (new.rowid, new.title, new.content);
            END
        """.trimIndent())
        db.execSQL("INSERT INTO project_documents_fts(project_documents_fts) VALUES('rebuild')")

        // Rebuild project_facts table with DEFAULT clauses (including FTS5)
        db.execSQL("DROP TRIGGER IF EXISTS project_facts_ai")
        db.execSQL("DROP TRIGGER IF EXISTS project_facts_ad")
        db.execSQL("DROP TRIGGER IF EXISTS project_facts_au")
        db.execSQL("DROP TABLE IF EXISTS project_facts_fts")

        db.execSQL("""
            CREATE TABLE project_facts_new (
                id TEXT PRIMARY KEY NOT NULL,
                project_id TEXT NOT NULL,
                category TEXT NOT NULL DEFAULT '',
                fact TEXT NOT NULL DEFAULT '',
                embedding TEXT NOT NULL DEFAULT '',
                confidence REAL NOT NULL DEFAULT 1.0,
                source_message_ids TEXT NOT NULL DEFAULT '',
                extracted_date INTEGER NOT NULL DEFAULT 0,
                last_recalled_date INTEGER NOT NULL DEFAULT 0,
                FOREIGN KEY (project_id) REFERENCES projects(id) ON DELETE CASCADE
            )
        """.trimIndent())
        db.execSQL("INSERT INTO project_facts_new SELECT * FROM project_facts")
        db.execSQL("DROP TABLE project_facts")
        db.execSQL("ALTER TABLE project_facts_new RENAME TO project_facts")
        db.execSQL("CREATE INDEX index_project_facts_project_id ON project_facts(project_id)")
        db.execSQL("CREATE INDEX index_project_facts_category ON project_facts(category)")

        db.execSQL("""
            CREATE VIRTUAL TABLE project_facts_fts USING fts5(
                fact,
                category,
                content='project_facts',
                content_rowid='rowid'
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TRIGGER project_facts_ai AFTER INSERT ON project_facts BEGIN
                INSERT INTO project_facts_fts(rowid, fact, category)
                VALUES (new.rowid, new.fact, new.category);
            END
        """.trimIndent())
        db.execSQL("""
            CREATE TRIGGER project_facts_ad AFTER DELETE ON project_facts BEGIN
                INSERT INTO project_facts_fts(project_facts_fts, rowid, fact, category)
                VALUES ('delete', old.rowid, old.fact, old.category);
            END
        """.trimIndent())
        db.execSQL("""
            CREATE TRIGGER project_facts_au AFTER UPDATE ON project_facts BEGIN
                INSERT INTO project_facts_fts(project_facts_fts, rowid, fact, category)
                VALUES ('delete', old.rowid, old.fact, old.category);
                INSERT INTO project_facts_fts(rowid, fact, category)
                VALUES (new.rowid, new.fact, new.category);
            END
        """.trimIndent())
        db.execSQL("INSERT INTO project_facts_fts(project_facts_fts) VALUES('rebuild')")

        // Convert diary mood from ordinal to value
        db.execSQL("UPDATE diary SET mood = 5 - mood WHERE mood BETWEEN 0 AND 4")
    }
}

val MIGRATION_14_15 = object : Migration(14, 15) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Rename guru_skills table to guru_automations using safe CREATE + INSERT + DROP pattern
        db.execSQL("""
            CREATE TABLE guru_automations (
                id TEXT PRIMARY KEY NOT NULL,
                name TEXT NOT NULL,
                displayName TEXT NOT NULL,
                description TEXT NOT NULL,
                trigger TEXT NOT NULL,
                triggerConfig TEXT NOT NULL,
                steps TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                last_run_at INTEGER,
                run_count INTEGER NOT NULL DEFAULT 0,
                enabled INTEGER NOT NULL DEFAULT 1
            )
        """.trimIndent())
        db.execSQL("""
            INSERT INTO guru_automations (id, name, displayName, description, trigger, triggerConfig, steps, created_at, last_run_at, run_count, enabled)
            SELECT id, name, displayName, description, trigger, triggerConfig, steps, created_at, last_run_at, run_count, enabled FROM guru_skills
        """.trimIndent())
        db.execSQL("DROP TABLE guru_skills")
        db.execSQL("CREATE INDEX index_guru_automations_name ON guru_automations(name)")
        db.execSQL("CREATE INDEX index_guru_automations_trigger ON guru_automations(trigger)")
        db.execSQL("CREATE INDEX index_guru_automations_enabled ON guru_automations(enabled)")

        // Create luxify_skills table for user-created Luxify skills
        db.execSQL("""
            CREATE TABLE luxify_skills (
                id TEXT PRIMARY KEY NOT NULL,
                name TEXT NOT NULL,
                description TEXT NOT NULL,
                when_to_use TEXT NOT NULL DEFAULT '',
                allowed_tools TEXT NOT NULL DEFAULT '',
                body_markdown TEXT NOT NULL DEFAULT '',
                source TEXT NOT NULL DEFAULT 'user',
                enabled INTEGER NOT NULL DEFAULT 1,
                created_at INTEGER NOT NULL DEFAULT 0,
                updated_at INTEGER NOT NULL DEFAULT 0
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX index_luxify_skills_name ON luxify_skills(name)")
        db.execSQL("CREATE INDEX index_luxify_skills_enabled ON luxify_skills(enabled)")
        db.execSQL("CREATE INDEX index_luxify_skills_source ON luxify_skills(source)")
    }
}
