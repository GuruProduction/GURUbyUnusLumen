// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.tools

import com.unuslumen.app.data.tools.registry.ToolDefinition
import com.unuslumen.app.data.tools.registry.ToolExecutor
import com.unuslumen.app.data.tools.registry.ToolParameter
import com.unuslumen.app.data.tools.registry.ToolParameterType
import com.unuslumen.app.data.tools.registry.ToolResultExtractor
import com.unuslumen.app.data.tools.registry.ToolSetRegistration
import kotlin.reflect.KClass

object DatabaseToolDefinitions : ToolSetRegistration {
    const val SQL_QUERY = "sqlQuery"; const val SQL_EXECUTE = "sqlExecute"; const val SQL_SCHEMA = "sqlGetSchema"
    const val SQL_LIST_DATABASES = "sqlListDatabases"; const val CONTENT_QUERY = "queryContentProvider"

    override val definitions = listOf(
        // THE DATABASE PARAMETER SAYS WHAT IT ACCEPTS (issue 478).
        //
        // It used to say "Full path to SQLite database file", and a caller who
        // passed `guru_db` got SQLite's own refusal back, which reads as "this
        // database does not exist" rather than "wrong shape of path". The bare
        // name is now resolved against the app's own database directory, and the
        // description says so, because a description is the only documentation an
        // agent has.
        ToolDefinition(name = SQL_QUERY, description = "Execute a SELECT query on a SQLite database. Returns results as JSON array of objects. The 'database' argument takes the app's own database by bare name (for example \"guru_db\", which holds GURU's messages, tool results and memory tables) or an absolute path to any other database file.", category = "database", parameters = listOf(ToolParameter("database", ToolParameterType.String, true, "Bare name of one of the app's own databases, such as \"guru_db\", or an absolute path"), ToolParameter("query", ToolParameterType.String, true, "SQL SELECT query"), ToolParameter("limit", ToolParameterType.Integer, false, "Max rows, default 100")), permissions = emptyList()),
        ToolDefinition(name = SQL_EXECUTE, description = "Execute an INSERT, UPDATE, DELETE, CREATE TABLE, ALTER TABLE, DROP TABLE, or any non-SELECT SQL statement on a SQLite database. This is permanent. The 'database' argument takes a bare name such as \"guru_db\" or an absolute path.", category = "database", parameters = listOf(ToolParameter("database", ToolParameterType.String, true, "Bare name of one of the app's own databases, such as \"guru_db\", or an absolute path"), ToolParameter("statement", ToolParameterType.String, true, "SQL statement")), permissions = emptyList()),
        ToolDefinition(name = SQL_SCHEMA, description = "Get the schema of a SQLite database — lists all tables and their columns with types. The 'database' argument takes a bare name such as \"guru_db\" or an absolute path.", category = "database", parameters = listOf(ToolParameter("database", ToolParameterType.String, true, "Bare name of one of the app's own databases, such as \"guru_db\", or an absolute path")), permissions = emptyList()),
        ToolDefinition(name = SQL_LIST_DATABASES, description = "List the SQLite database files in the app's own database directory, with their absolute paths, sizes and modification times. Use this first when you do not know a database's name.", category = "database", parameters = emptyList(), permissions = emptyList()),
        ToolDefinition(name = CONTENT_QUERY, description = "Query an Android content provider. Use for accessing contacts, calendar, media store, SMS, call logs.", category = "database", parameters = listOf(ToolParameter("uri", ToolParameterType.String, true, "Content URI"), ToolParameter("projection", ToolParameterType.String, false, "Columns, comma-separated"), ToolParameter("selection", ToolParameterType.String, false, "WHERE clause"), ToolParameter("selectionArgs", ToolParameterType.String, false, "Selection args"), ToolParameter("sortOrder", ToolParameterType.String, false, "ORDER BY"), ToolParameter("limit", ToolParameterType.Integer, false, "Max rows")), permissions = emptyList())
    )
    override fun executorClass(): KClass<out ToolExecutor> = DatabaseToolExecutor::class
    override fun extractorClass(): KClass<out ToolResultExtractor>? = null
}