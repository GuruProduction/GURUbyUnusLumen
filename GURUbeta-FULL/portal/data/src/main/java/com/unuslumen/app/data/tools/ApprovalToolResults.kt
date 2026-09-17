package com.unuslumen.app.data.tools

import com.unuslumen.app.data.tools.registry.ToolResultData
import kotlinx.serialization.Serializable

@Serializable data class ApprovalItem(val id: String, val type: String, val name: String, val description: String, val status: String, val createdAt: Long, val rationale: String?, val detail: String) : ToolResultData
@Serializable data class PendingApprovalsResult(val items: List<ApprovalItem>) : ToolResultData
@Serializable data class ApprovalDetailsResult(val id: String, val type: String, val name: String, val description: String, val status: String, val createdAt: Long, val createdBy: String, val rationale: String?, val fullContent: String) : ToolResultData
@Serializable data class ApprovalActionResult(val id: String, val type: String, val name: String, val status: String, val message: String) : ToolResultData