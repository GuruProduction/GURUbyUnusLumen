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
        ToolDefinition(name = SQL_QUERY, description = "Execute a SELECT query on any SQLite database file. Returns results as JSON array of objects.", category = "database", parameters = listOf(ToolParameter("database", ToolParameterType.String, true, "Full path to SQLite database file"), ToolParameter("query", ToolParameterType.String, true, "SQL SELECT query"), ToolParameter("limit", ToolParameterType.Integer, false, "Max rows, default 100")), permissions = emptyList()),
        ToolDefinition(name = SQL_EXECUTE, description = "Execute an INSERT, UPDATE, DELETE, CREATE TABLE, ALTER TABLE, DROP TABLE, or any non-SELECT SQL statement on a SQLite database. This is permanent.", category = "database", parameters = listOf(ToolParameter("database", ToolParameterType.String, true, "Database file path"), ToolParameter("statement", ToolParameterType.String, true, "SQL statement")), permissions = emptyList()),
        ToolDefinition(name = SQL_SCHEMA, description = "Get the schema of a SQLite database — lists all tables and their columns with types.", category = "database", parameters = listOf(ToolParameter("database", ToolParameterType.String, true, "Database file path")), permissions = emptyList()),
        ToolDefinition(name = SQL_LIST_DATABASES, description = "Find SQLite database files on the device.", category = "database", parameters = emptyList(), permissions = emptyList()),
        ToolDefinition(name = CONTENT_QUERY, description = "Query an Android content provider. Use for accessing contacts, calendar, media store, SMS, call logs.", category = "database", parameters = listOf(ToolParameter("uri", ToolParameterType.String, true, "Content URI"), ToolParameter("projection", ToolParameterType.String, false, "Columns, comma-separated"), ToolParameter("selection", ToolParameterType.String, false, "WHERE clause"), ToolParameter("selectionArgs", ToolParameterType.String, false, "Selection args"), ToolParameter("sortOrder", ToolParameterType.String, false, "ORDER BY"), ToolParameter("limit", ToolParameterType.Integer, false, "Max rows")), permissions = emptyList())
    )
    override fun executorClass(): KClass<out ToolExecutor> = DatabaseToolExecutor::class
    override fun extractorClass(): KClass<out ToolResultExtractor>? = null
}