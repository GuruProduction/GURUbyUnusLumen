// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.tools

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import com.unuslumen.app.data.tools.registry.ToolExecutionResult
import com.unuslumen.app.data.tools.registry.ToolExecutor
import com.unuslumen.app.domain.portal.SqlDatabasePath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

class DatabaseToolExecutor(private val context: Context) : ToolExecutor {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * The directory this app's own databases live in.
     *
     * Derived from `Context.getDatabasePath` rather than assembled from the
     * package name, because the package name is not fixed: a debug build carries
     * a `.debug` suffix, and a caller hardcoding `com.unuslumen.app.gurubeta`
     * gets a path that exists on exactly one build type. Asking the platform is
     * the only version of this that stays true across installs.
     */
    private fun databaseDir(): String =
        context.getDatabasePath("guru_db").parent ?: context.filesDir.absolutePath

    override suspend fun execute(toolName: String, args: Map<String, Any?>): ToolExecutionResult = withContext(Dispatchers.IO) {
        when (toolName) {
            DatabaseToolDefinitions.SQL_QUERY -> {
                val query = args["query"] as? String
                    ?: return@withContext ToolExecutionResult.error("Missing 'query'")
                val limit = (args["limit"] as? Number)?.toInt() ?: 100
                when (val target = SqlDatabasePath.resolve(args["database"] as? String, databaseDir())) {
                    is SqlDatabasePath.Resolution.Refused -> {
                        val r = SqlQueryResult(false, emptyList(), emptyList(), 0, 0, target.reason)
                        return@withContext ToolExecutionResult.success(r, json.encodeToString(SqlQueryResult.serializer(), r))
                    }
                    is SqlDatabasePath.Resolution.Open -> try {
                        val d = SQLiteDatabase.openDatabase(target.path, null, SQLiteDatabase.OPEN_READONLY)
                        val c = d.rawQuery(query, null)
                        val cols = c.columnNames.toList()
                        val rows = mutableListOf<Map<String, String>>()
                        var cnt = 0
                        while (c.moveToNext() && cnt < limit) {
                            val row = mutableMapOf<String, String>()
                            for (col in cols) { val idx = c.getColumnIndex(col); if (idx >= 0) row[col] = c.getString(idx) ?: "null" }
                            rows.add(row); cnt++
                        }
                        val total = c.count; c.close(); d.close()
                        val r = SqlQueryResult(true, cols, rows, total, rows.size, null)
                        ToolExecutionResult.success(r, json.encodeToString(SqlQueryResult.serializer(), r))
                    } catch (e: Exception) {
                        val r = SqlQueryResult(
                            false, emptyList(), emptyList(), 0, 0,
                            SqlDatabasePath.openFailureMessage(target.path, databaseDir(), e.message),
                        )
                        ToolExecutionResult.success(r, json.encodeToString(SqlQueryResult.serializer(), r))
                    }
                }
            }
            DatabaseToolDefinitions.SQL_EXECUTE -> {
                val stmt = args["statement"] as? String
                    ?: return@withContext ToolExecutionResult.error("Missing 'statement'")
                when (val target = SqlDatabasePath.resolve(args["database"] as? String, databaseDir())) {
                    is SqlDatabasePath.Resolution.Refused -> {
                        val r = SqlExecuteResult(false, 0, target.reason)
                        return@withContext ToolExecutionResult.success(r, json.encodeToString(SqlExecuteResult.serializer(), r))
                    }
                    is SqlDatabasePath.Resolution.Open -> try {
                        val d = SQLiteDatabase.openDatabase(target.path, null, SQLiteDatabase.OPEN_READWRITE)
                        val s = d.compileStatement(stmt); s.execute(); s.close()
                        val c = d.rawQuery("SELECT changes()", null)
                        var changed = 0
                        if (c.moveToFirst()) changed = c.getInt(0)
                        c.close(); d.close()
                        val r = SqlExecuteResult(true, changed, null)
                        ToolExecutionResult.success(r, json.encodeToString(SqlExecuteResult.serializer(), r))
                    } catch (e: Exception) {
                        val r = SqlExecuteResult(
                            false, 0,
                            SqlDatabasePath.openFailureMessage(target.path, databaseDir(), e.message),
                        )
                        ToolExecutionResult.success(r, json.encodeToString(SqlExecuteResult.serializer(), r))
                    }
                }
            }
            DatabaseToolDefinitions.SQL_SCHEMA -> {
                when (val target = SqlDatabasePath.resolve(args["database"] as? String, databaseDir())) {
                    is SqlDatabasePath.Resolution.Refused -> {
                        val r = SqlSchemaResult(false, args["database"] as? String ?: "", emptyList(), target.reason)
                        return@withContext ToolExecutionResult.success(r, json.encodeToString(SqlSchemaResult.serializer(), r))
                    }
                    is SqlDatabasePath.Resolution.Open -> try {
                        val d = SQLiteDatabase.openDatabase(target.path, null, SQLiteDatabase.OPEN_READONLY)
                        val tc = d.rawQuery(
                            "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name NOT LIKE 'android_%' ORDER BY name",
                            null,
                        )
                        val tables = mutableListOf<TableSchema>()
                        while (tc.moveToNext()) {
                            val tn = tc.getString(0)
                            val cc = d.rawQuery("PRAGMA table_info('$tn')", null)
                            val cols = mutableListOf<ColumnSchema>()
                            while (cc.moveToNext()) {
                                cols.add(ColumnSchema(cc.getString(1), cc.getString(2), cc.getInt(3) == 1, cc.getInt(5) == 1))
                            }
                            cc.close()
                            val rc = d.rawQuery("SELECT COUNT(*) FROM '$tn'", null)
                            val rcnt = if (rc.moveToFirst()) rc.getLong(0) else 0L
                            rc.close()
                            tables.add(TableSchema(tn, cols, rcnt))
                        }
                        tc.close(); d.close()
                        val r = SqlSchemaResult(true, target.path, tables, null)
                        ToolExecutionResult.success(r, json.encodeToString(SqlSchemaResult.serializer(), r))
                    } catch (e: Exception) {
                        val r = SqlSchemaResult(
                            false, target.path, emptyList(),
                            SqlDatabasePath.openFailureMessage(target.path, databaseDir(), e.message),
                        )
                        ToolExecutionResult.success(r, json.encodeToString(SqlSchemaResult.serializer(), r))
                    }
                }
            }
            // The listing must show the SAME directory a bare name resolves against,
            // or an agent reads a path here and passes one that resolves nowhere.
            //
            // This used to prepend `/data/data/<package>/databases`, which is the
            // legacy layout: on any multi-user device the real path is
            // `/data/user/<id>/<package>/databases` and the hardcoded prefix either
            // does not exist or resolves through a symlink the app cannot traverse.
            // `getDatabasePath` is the platform's own answer and cannot drift.
            DatabaseToolDefinitions.SQL_LIST_DATABASES -> {
                try {
                    val results = mutableListOf<DatabaseFile>()
                    val dirs = listOf(databaseDir(), context.filesDir.absolutePath, context.cacheDir.absolutePath)
                    for (p in dirs.distinct().filter { it.isNotBlank() }) {
                        val dir = File(p)
                        if (!dir.exists() || !dir.isDirectory) continue
                        dir.listFiles()
                            ?.filter { it.isFile && (it.name.endsWith(".db") || it.name.endsWith(".sqlite") || it.name == com.unuslumen.app.database.guruDatabase.DATABASE_NAME) }
                            ?.sortedBy { it.name }
                            ?.forEach { results.add(DatabaseFile(it.absolutePath, it.name, it.length(), it.lastModified())) }
                    }
                    val r = SqlListDatabasesResult(results, null)
                    ToolExecutionResult.success(r, json.encodeToString(SqlListDatabasesResult.serializer(), r))
                } catch (e: Exception) {
                    val r = SqlListDatabasesResult(
                        emptyList(),
                        "Failed to list ${databaseDir()}: ${e.message}",
                    )
                    ToolExecutionResult.success(r, json.encodeToString(SqlListDatabasesResult.serializer(), r))
                }
            }
            DatabaseToolDefinitions.CONTENT_QUERY -> { val uri = args["uri"] as? String ?: return@withContext ToolExecutionResult.error("Missing 'uri'"); val proj = args["projection"] as? String ?: "*"; val sel = (args["selection"] as? String)?.ifBlank { null }; val selArgs = (args["selectionArgs"] as? String)?.ifBlank { null }; val sort = (args["sortOrder"] as? String)?.ifBlank { null }; val limit = (args["limit"] as? Number)?.toInt() ?: 50; try { val cols = if (proj == "*") null else proj.split(",").map { it.trim() }.toTypedArray(); val argsArr = selArgs?.split(",")?.map { it.trim() }?.toTypedArray(); val c = context.contentResolver.query(Uri.parse(uri), cols, sel, argsArr, sort) ?: return@withContext run { val r = ContentQueryResult(false, emptyList(), emptyList(), 0, 0, "Null cursor"); ToolExecutionResult.success(r, json.encodeToString(ContentQueryResult.serializer(), r)) }; val columns = c.columnNames.toList(); val rows = mutableListOf<Map<String, String>>(); var cnt = 0; while (c.moveToNext() && cnt < limit) { val row = mutableMapOf<String, String>(); for (col in columns) { val idx = c.getColumnIndex(col); if (idx >= 0) row[col] = c.getString(idx) ?: "null" }; rows.add(row); cnt++ }; val total = c.count; c.close(); val r = ContentQueryResult(true, columns, rows, total, rows.size, null); ToolExecutionResult.success(r, json.encodeToString(ContentQueryResult.serializer(), r)) } catch (e: Exception) { val r = ContentQueryResult(false, emptyList(), emptyList(), 0, 0, "Failed: ${e.message}"); ToolExecutionResult.success(r, json.encodeToString(ContentQueryResult.serializer(), r)) } }
            else -> ToolExecutionResult.error("Unknown tool: $toolName")
        }
    }
}