// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.tools

import com.unuslumen.app.data.tools.registry.ToolResultData
import kotlinx.serialization.Serializable

@Serializable data class SqlQueryResult(val success: Boolean, val columns: List<String>, val rows: List<Map<String, String>>, val totalRows: Int, val returnedRows: Int, val error: String?) : ToolResultData
@Serializable data class SqlExecuteResult(val success: Boolean, val rowsAffected: Int, val error: String?) : ToolResultData
@Serializable data class ColumnSchema(val name: String, val type: String, val notNull: Boolean, val primaryKey: Boolean) : ToolResultData
@Serializable data class TableSchema(val name: String, val columns: List<ColumnSchema>, val rowCount: Long) : ToolResultData
@Serializable data class SqlSchemaResult(val success: Boolean, val database: String, val tables: List<TableSchema>, val error: String?) : ToolResultData
@Serializable data class DatabaseFile(val path: String, val name: String, val sizeBytes: Long, val lastModified: Long) : ToolResultData
@Serializable data class SqlListDatabasesResult(val databases: List<DatabaseFile>, val error: String?) : ToolResultData
@Serializable data class ContentQueryResult(val success: Boolean, val columns: List<String>, val rows: List<Map<String, String>>, val totalRows: Int, val returnedRows: Int, val error: String?) : ToolResultData