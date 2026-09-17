package com.unuslumen.app.database.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.unuslumen.app.database.entity.ToolResultEntity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

val MIGRATION_17_18 = object : Migration(17, 18) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // 1. Create the tool_results table
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS tool_results (
                id TEXT NOT NULL PRIMARY KEY,
                tool_name TEXT NOT NULL,
                parameters TEXT NOT NULL DEFAULT '',
                result TEXT NOT NULL DEFAULT '',
                result_text TEXT NOT NULL DEFAULT '',
                timestamp INTEGER NOT NULL DEFAULT 0,
                conversation_id TEXT NOT NULL DEFAULT '',
                success INTEGER NOT NULL DEFAULT 1,
                ttl_minutes INTEGER,
                result_signature TEXT NOT NULL DEFAULT ''
            )
        """.trimIndent())

        db.execSQL("CREATE INDEX IF NOT EXISTS index_tool_results_tool_name ON tool_results(tool_name)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_tool_results_timestamp ON tool_results(timestamp)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_tool_results_conversation_id ON tool_results(conversation_id)")

        // 2. Create FTS5 virtual table on result_text column (not result, to avoid indexing JSON structure)
        try {
            db.execSQL("""
                CREATE VIRTUAL TABLE tool_results_fts USING fts5(
                    result_text,
                    content='tool_results',
                    content_rowid='rowid'
                )
            """.trimIndent())

            // 3. Create triggers to keep FTS5 in sync
            db.execSQL("""
                CREATE TRIGGER tool_results_ai AFTER INSERT ON tool_results BEGIN
                    INSERT INTO tool_results_fts(rowid, result_text)
                    VALUES (new.rowid, new.result_text);
                END
            """.trimIndent())

            db.execSQL("""
                CREATE TRIGGER tool_results_ad AFTER DELETE ON tool_results BEGIN
                    INSERT INTO tool_results_fts(tool_results_fts, rowid, result_text)
                    VALUES ('delete', old.rowid, old.result_text);
                END
            """.trimIndent())

            db.execSQL("""
                CREATE TRIGGER tool_results_au AFTER UPDATE ON tool_results BEGIN
                    INSERT INTO tool_results_fts(tool_results_fts, rowid, result_text)
                    VALUES ('delete', old.rowid, old.result_text);
                    INSERT INTO tool_results_fts(rowid, result_text)
                    VALUES (new.rowid, new.result_text);
                END
            """.trimIndent())
        } catch (e: Exception) {
            // FTS5 not available — clean up and continue. LIKE-based search won't work
            // but the table still stores data that can be queried by tool name.
            db.execSQL("DROP TRIGGER IF EXISTS tool_results_ai")
            db.execSQL("DROP TRIGGER IF EXISTS tool_results_ad")
            db.execSQL("DROP TRIGGER IF EXISTS tool_results_au")
            db.execSQL("DROP TABLE IF EXISTS tool_results_fts")
        }

        // 4. Backfill existing tool results from the messages table
        // Triggers MUST exist before inserts so FTS5 gets populated for backfilled data.
        backfillFromMessages(db)
    }

    private fun backfillFromMessages(db: SupportSQLiteDatabase) {
        val json = Json { ignoreUnknownKeys = true }

        // Tool name is NOT stored in the messages table. The old format stores
        // arguments in tool_calls and the result in content/tool_results.
        // We try to infer the tool name from:
        // 1. A "name"/"tool"/"toolName" key in the tool_calls JSON (rare but possible)
        // 2. A regex match on error messages containing "Tool 'xxx' is not defined"
        // 3. Skipping the entry if no tool name can be inferred
        //
        // Going forward, new entries will always have the tool name because we write
        // directly to the tool_results table at execution time in AiRepositoryImpl.
        val toolNameRegex = Regex("""Tool '(\w+)' is not defined|Error: Tool '(\w+)'|Unknown tool name: (\w+)""")

        val cursor = db.query(
            "SELECT id, conversation_id, content, tool_calls, tool_results, timestamp " +
            "FROM messages WHERE role = 'tool' AND (tool_results != '' OR content != '')"
        )

        cursor.use { c ->
            var skipped = 0
            var inserted = 0
            while (c.moveToNext()) {
                try {
                    val messageId = c.getString(c.getColumnIndexOrThrow("id"))
                    val conversationId = c.getString(c.getColumnIndexOrThrow("conversation_id"))
                    val content = c.getString(c.getColumnIndexOrThrow("content")) ?: ""
                    val toolCalls = c.getString(c.getColumnIndexOrThrow("tool_calls")) ?: ""
                    val toolResults = c.getString(c.getColumnIndexOrThrow("tool_results")) ?: ""
                    val timestamp = c.getLong(c.getColumnIndexOrThrow("timestamp"))

                    // Extract result, falling back to content if tool_results is empty
                    val result = if (toolResults.isNotBlank()) toolResults else content
                    if (result.isBlank()) { skipped++; continue }

                    // Try to extract tool name from tool_calls JSON
                    var toolName = ""
                    var parameters = ""
                    if (toolCalls.isNotBlank()) {
                        try {
                            val parsed = json.parseToJsonElement(toolCalls)
                            if (parsed is JsonObject) {
                                toolName = (parsed["name"] as? JsonPrimitive)?.contentOrNull
                                    ?: (parsed["tool"] as? JsonPrimitive)?.contentOrNull
                                    ?: (parsed["toolName"] as? JsonPrimitive)?.contentOrNull
                                    ?: ""
                                parameters = parsed.toString()
                            }
                        } catch (e: Exception) {
                            // Not JSON, skip
                        }
                    }

                    // Fallback: try to extract from error messages in the result
                    if (toolName.isBlank()) {
                        val match = toolNameRegex.find(result)
                        if (match != null) {
                            toolName = match.groupValues.firstOrNull { it.isNotBlank() } ?: ""
                        }
                    }

                    if (toolName.isBlank()) {
                        // Fallback: try matching parameter names in tool_calls to known tool names
                        if (toolCalls.isNotBlank()) {
                            try {
                                val parsed = json.parseToJsonElement(toolCalls)
                                if (parsed is JsonObject) {
                                    val keys = parsed.keys.joinToString(" ").lowercase()
                                    // Match common parameter patterns to tool names
                                    toolName = inferToolFromParams(keys, result)
                                }
                            } catch (e: Exception) {}
                        }
                    }

                    if (toolName.isBlank()) { skipped++; continue }

                    val truncatedResult = ToolResultEntity.truncateResult(result)
                    val resultText = ToolResultEntity.flattenJsonToText(truncatedResult)
                    val truncatedParams = ToolResultEntity.truncateParameters(parameters)
                    val ttlMinutes = ToolResultEntity.getTtlForTool(toolName)

                    db.execSQL(
                        "INSERT OR REPLACE INTO tool_results (id, tool_name, parameters, result, result_text, timestamp, conversation_id, success, ttl_minutes, result_signature) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                        arrayOf<Any?>(
                            messageId,
                            toolName,
                            truncatedParams,
                            truncatedResult,
                            resultText,
                            timestamp,
                            conversationId,
                            1, // success = true (cannot determine from old format)
                            ttlMinutes,
                            "" // result_signature (SignatureEngine unreachable from migration)
                        )
                    )
                    inserted++
                } catch (e: Exception) {
                    skipped++
                    android.util.Log.w("guru_migration", "Backfill: skipped row due to error: ${e.message}")
                }
            }
            android.util.Log.d("guru_migration", "Backfill complete: $inserted inserted, $skipped skipped")
        }
    }

    private fun inferToolFromParams(paramKeys: String, resultContent: String): String {
        val result = resultContent.lowercase()
        return when {
            "creatednoteid" in result || "created_note_id" in result -> "createNote"
            "creatednotids" in result || "searchresults" in result -> "searchNotes"
            "createdtaskid" in result -> "createTask"
            "createdeventid" in result -> "createEvent"
            "createddiaryentry" in result -> "createDiaryEntry"
            "createdbookmarkid" in result -> "createBookmark"
            "success" in result && "spotify" in result -> "spotifyStatus"
            "temperature" in result && "condition" in result -> "weatherCurrent"
            "forecast" in result -> "weatherForecast"
            "query" in paramKeys && "database" in paramKeys -> "sqlQuery"
            "query" in paramKeys -> "searchNotes"
            "location" in paramKeys -> "weatherCurrent"
            "command" in paramKeys -> "executeShellCommand"
            "path" in paramKeys -> "readFile"
            "url" in paramKeys -> "webFetch"
            "messagetext" in paramKeys -> "sendMessage"
            else -> ""
        }
    }
}