// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.tools

import com.unuslumen.app.data.tools.registry.ToolResultData
import kotlinx.serialization.Serializable

@Serializable data class DefineToolResult(val toolId: String, val name: String, val displayName: String, val status: String, val message: String) : ToolResultData
@Serializable data class MyToolsResult(val tools: List<ToolInfo>, val summary: com.unuslumen.app.domain.model.GuruToolsSummary) : ToolResultData
@Serializable data class ToolInfo(val id: String, val name: String, val displayName: String, val description: String, val status: String, val useCount: Int, val createdAt: Long) : ToolResultData
@Serializable data class ToolDetailsResult(val tool: DetailedToolInfo) : ToolResultData
@Serializable data class DetailedToolInfo(val id: String, val name: String, val displayName: String, val description: String, val parameters: String, val implementation: String, val status: String, val createdAt: Long, val approvedAt: Long?, val createdBy: String, val lastUsedAt: Long?, val useCount: Int, val rationale: String?) : ToolResultData
@Serializable data class UpdateToolResult(val toolId: String, val name: String, val status: String, val message: String) : ToolResultData
@Serializable data class DeleteToolResult(val toolId: String, val name: String, val message: String) : ToolResultData
@Serializable data class ApproveToolResult(val toolId: String, val name: String, val message: String) : ToolResultData
@Serializable data class DisableToolResult(val toolId: String, val name: String, val message: String) : ToolResultData
@Serializable data class EnableToolResult(val toolId: String, val name: String, val message: String) : ToolResultData