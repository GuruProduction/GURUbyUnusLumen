package com.unuslumen.app.data.tools

import com.unuslumen.app.data.tools.registry.ToolExecutionResult
import com.unuslumen.app.data.tools.registry.ToolExecutor
import com.unuslumen.app.domain.model.ToolStatus
import com.unuslumen.app.domain.repository.GuruToolRepository
import kotlinx.serialization.json.Json

class ApprovalToolExecutor(private val guruToolRepository: GuruToolRepository) : ToolExecutor {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun execute(toolName: String, args: Map<String, Any?>): ToolExecutionResult = when (toolName) {
        ApprovalToolDefinitions.GET_PENDING_APPROVALS -> getPendingApprovals()
        ApprovalToolDefinitions.GET_APPROVAL_DETAILS -> getApprovalDetails(args)
        ApprovalToolDefinitions.APPROVE_ITEM -> approveItem(args)
        ApprovalToolDefinitions.REJECT_ITEM -> rejectItem(args)
        else -> ToolExecutionResult.error("Unknown tool: $toolName")
    }

    private suspend fun getPendingApprovals(): ToolExecutionResult {
        val pending = guruToolRepository.getToolsByStatus(ToolStatus.PENDING)
        val items = pending.map { t -> ApprovalItem(t.id, "TOOL", t.displayName, t.description, t.status.name, t.createdAt, t.rationale, "Tool: ${t.name}") }
        val r = PendingApprovalsResult(items); return ToolExecutionResult.success(r, json.encodeToString(PendingApprovalsResult.serializer(), r))
    }

    private suspend fun getApprovalDetails(args: Map<String, Any?>): ToolExecutionResult {
        val itemId = args["itemId"] as? String ?: return ToolExecutionResult.error("Missing 'itemId'")
        val t = guruToolRepository.getTool(itemId) ?: return ToolExecutionResult.error("Not found: $itemId")
        val r = ApprovalDetailsResult(t.id, "TOOL", t.displayName, t.description, t.status.name, t.createdAt, t.createdBy, t.rationale, "Name: ${t.name}\nDesc: ${t.description}\nParams: ${t.parameters}")
        return ToolExecutionResult.success(r, json.encodeToString(ApprovalDetailsResult.serializer(), r))
    }

    private suspend fun approveItem(args: Map<String, Any?>): ToolExecutionResult {
        val itemId = args["itemId"] as? String ?: return ToolExecutionResult.error("Missing 'itemId'")
        val t = guruToolRepository.getTool(itemId) ?: return ToolExecutionResult.error("Not found: $itemId")
        if (t.status != ToolStatus.PENDING) { val r = ApprovalActionResult(itemId, "TOOL", t.displayName, t.status.name, "Already ${t.status.name.lowercase()}."); return ToolExecutionResult.success(r, json.encodeToString(ApprovalActionResult.serializer(), r)) }
        val approved = guruToolRepository.approveTool(itemId)
        val r = ApprovalActionResult(itemId, "TOOL", approved.displayName, approved.status.name, "Approved.")
        return ToolExecutionResult.success(r, json.encodeToString(ApprovalActionResult.serializer(), r))
    }

    private suspend fun rejectItem(args: Map<String, Any?>): ToolExecutionResult {
        val itemId = args["itemId"] as? String ?: return ToolExecutionResult.error("Missing 'itemId'")
        val reason = args["reason"] as? String
        val t = guruToolRepository.getTool(itemId) ?: return ToolExecutionResult.error("Not found: $itemId")
        if (t.status != ToolStatus.PENDING) { val r = ApprovalActionResult(itemId, "TOOL", t.displayName, t.status.name, "Already ${t.status.name.lowercase()}."); return ToolExecutionResult.success(r, json.encodeToString(ApprovalActionResult.serializer(), r)) }
        guruToolRepository.deleteTool(itemId)
        val r = ApprovalActionResult(itemId, "TOOL", t.displayName, "REJECTED", "Rejected.${reason?.let { " Reason: $it" } ?: ""}")
        return ToolExecutionResult.success(r, json.encodeToString(ApprovalActionResult.serializer(), r))
    }
}