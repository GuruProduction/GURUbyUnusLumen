package com.unuslumen.app.data.tools

import com.unuslumen.app.data.tools.registry.ToolDefinition
import com.unuslumen.app.data.tools.registry.ToolExecutor
import com.unuslumen.app.data.tools.registry.ToolParameter
import com.unuslumen.app.data.tools.registry.ToolParameterType
import com.unuslumen.app.data.tools.registry.ToolResultExtractor
import com.unuslumen.app.data.tools.registry.ToolSetRegistration
import kotlin.reflect.KClass

object ApprovalToolDefinitions : ToolSetRegistration {
    const val GET_PENDING_APPROVALS = "getPendingApprovals"
    const val APPROVE_ITEM = "approveItem"
    const val REJECT_ITEM = "rejectItem"
    const val GET_APPROVAL_DETAILS = "getApprovalDetails"

    override val definitions = listOf(
        ToolDefinition(name = GET_PENDING_APPROVALS, description = "Get ALL items awaiting user approval across every system.", category = "approval", parameters = emptyList(), permissions = emptyList()),
        ToolDefinition(name = GET_APPROVAL_DETAILS, description = "Get full details for a specific pending item.", category = "approval", parameters = listOf(ToolParameter("itemId", ToolParameterType.String, true, "The ID of the approval item to inspect.")), permissions = emptyList()),
        ToolDefinition(name = APPROVE_ITEM, description = "Approve a pending item. USER tool only.", category = "approval", parameters = listOf(ToolParameter("itemId", ToolParameterType.String, true, "The ID of the item to approve.")), permissions = emptyList()),
        ToolDefinition(name = REJECT_ITEM, description = "Reject a pending item. USER tool only.", category = "approval", parameters = listOf(ToolParameter("itemId", ToolParameterType.String, true, "The ID of the item to reject."), ToolParameter("reason", ToolParameterType.String, false, "Optional reason for rejection.")), permissions = emptyList())
    )
    override fun executorClass(): KClass<out ToolExecutor> = ApprovalToolExecutor::class
    override fun extractorClass(): KClass<out ToolResultExtractor>? = null
}