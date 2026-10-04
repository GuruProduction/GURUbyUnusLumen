// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.tools

import com.unuslumen.app.data.tools.registry.ToolResultData
import kotlinx.serialization.Serializable

@Serializable data class FsSearchResult(val files: List<String>, val error: String?) : ToolResultData
@Serializable data class FsOperationResult(val success: Boolean, val error: String?) : ToolResultData
@Serializable data class FsInfoResult(val exists: Boolean, val path: String, val name: String = "", val isDirectory: Boolean = false, val isFile: Boolean = false, val sizeBytes: Long = 0, val lastModified: Long = 0, val canRead: Boolean = false, val canWrite: Boolean = false, val canExecute: Boolean = false, val isHidden: Boolean = false, val parentPath: String = "", val error: String?) : ToolResultData
@Serializable data class ArchiveResult(val success: Boolean, val path: String, val sizeBytes: Long, val fileCount: Int, val error: String?) : ToolResultData
@Serializable data class ArchiveEntry(val name: String, val isDirectory: Boolean, val sizeBytes: Long, val compressedSize: Long) : ToolResultData
@Serializable data class ArchiveListResult(val entries: List<ArchiveEntry>, val error: String?) : ToolResultData