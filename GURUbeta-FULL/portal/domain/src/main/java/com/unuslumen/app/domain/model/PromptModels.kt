package com.unuslumen.app.domain.model

import kotlinx.serialization.Serializable

/**
 * Types of amendments that can be made to a prompt section.
 */
enum class AmendmentType {
    ADD,      // Append content to the section
    MODIFY,   // Change specific parts of the section
    REPLACE   // Replace the entire section content
}

/**
 * Who proposed an amendment.
 */
enum class Proposer {
    USER,
    GURU
}

/**
 * Status of an amendment.
 */
enum class AmendmentStatus {
    PENDING,     // Awaiting approval
    APPROVED,    // Approved and active
    REJECTED,    // Rejected by user
    ROLLED_BACK  // Was approved but later rolled back
}

/**
 * Represents a section of Guru's system prompt.
 */
@Serializable
data class PromptSection(
    val id: String,
    val displayName: String,
    val masterContent: String,
    val isEditable: Boolean,
    val order: Int,
    val amendments: List<PromptAmendment> = emptyList()
)

/**
 * Represents an amendment to a prompt section.
 */
@Serializable
data class PromptAmendment(
    val id: String,
    val sectionId: String,
    val type: AmendmentType,
    val content: String,
    val proposedBy: Proposer,
    val status: AmendmentStatus,
    val rationale: String? = null,
    val createdAt: Long,
    val approvedAt: Long? = null,
    val rejectedAt: Long? = null,
    val rollbackReason: String? = null,
    val version: Int
)

/**
 * Result of assembling a prompt section.
 */
@Serializable
data class AssembledPrompt(
    val sectionId: String,
    val content: String,
    val amendmentCount: Int,
    val hasPendingAmendments: Boolean
)

/**
 * Result of assembling all prompts.
 */
@Serializable
data class AssembledPrompts(
    val sections: Map<String, AssembledPrompt>,
    val fullPrompt: String
)

/**
 * Request to propose a new amendment.
 */
@Serializable
data class ProposeAmendmentRequest(
    val sectionId: String,
    val type: AmendmentType,
    val content: String,
    val proposedBy: Proposer,
    val rationale: String? = null
)

/**
 * Summary of pending approvals.
 */
@Serializable
data class PendingApprovalsSummary(
    val promptAmendments: Int,
    val oldestPendingAt: Long? = null
)