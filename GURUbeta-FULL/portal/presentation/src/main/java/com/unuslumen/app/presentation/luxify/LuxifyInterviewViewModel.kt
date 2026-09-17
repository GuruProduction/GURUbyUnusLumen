package com.unuslumen.app.presentation.luxify

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unuslumen.app.domain.model.CreateLuxifyRequest
import com.unuslumen.app.domain.repository.LuxifyRepository
import com.unuslumen.app.presentation.luxify.components.SkillDocumentData
import com.unuslumen.app.presentation.luxify.components.SkillStepData
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.koin.android.annotation.KoinViewModel

/**
 * Parsed representation of a guru interview question marker.
 */
@Serializable
data class InterviewQuestionData(
    val question: String,
    val suggestions: List<QuestionSuggestion> = emptyList(),
    val phase: Int = 1,
    val field: String = ""
)

@Serializable
data class QuestionSuggestion(
    val value: String,
    val label: String,
    val description: String
)

/**
 * Parsed representation of a guru final-skill marker.
 */
@Serializable
data class LuxifySkillPayload(
    val name: String,
    val description: String,
    val whenToUse: String,
    val allowedTools: List<String> = emptyList(),
    val steps: List<LuxifySkillPayloadStep> = emptyList()
)

@Serializable
data class LuxifySkillPayloadStep(
    val title: String,
    val description: String,
    val successCriteria: String
)

/**
 * Interview state exposed by [LuxifyInterviewViewModel].
 */
data class LuxifyInterviewState(
    val interviewActive: Boolean = false,
    val currentQuestion: InterviewQuestionData? = null,
    val currentSkillDocument: SkillDocumentData? = null,
    val collectedAnswers: Map<String, String> = emptyMap()
)

/**
 * ViewModel that drives the Luxify conversational skill-creation interview.
 * It parses tagged guru responses, renders native question cards and the final
 * skill document, and persists the confirmed skill through [LuxifyRepository].
 */
@KoinViewModel
class LuxifyInterviewViewModel(
    private val luxifyRepository: LuxifyRepository
) : ViewModel() {

    private val _state = MutableStateFlow(LuxifyInterviewState())
    val state: StateFlow<LuxifyInterviewState> = _state.asStateFlow()

    private val jsonParser = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /**
     * Starts a fresh interview, clearing any previous question or document state.
     */
    fun startInterview() {
        _state.value = LuxifyInterviewState(interviewActive = true)
    }

    /**
     * Inspects a guru response. If it contains an interview question marker it
     * updates [LuxifyInterviewState.currentQuestion]. If it contains a final
     * skill marker it updates [LuxifyInterviewState.currentSkillDocument].
     *
     * @return true if the content was handled as an interview artifact, false
     *         if it should be rendered as a normal assistant text message.
     */
    fun handleGuruResponse(content: String): Boolean {
        val trimmed = content.trim()
        val questionJson = extractMarker(trimmed, "[LUXIFY_QUESTION]", "[/LUXIFY_QUESTION]")
        if (questionJson != null) {
            return try {
                val question = jsonParser.decodeFromString<InterviewQuestionData>(questionJson)
                _state.update {
                    it.copy(
                        currentQuestion = question,
                        currentSkillDocument = null
                    )
                }
                true
            } catch (e: Exception) {
                false
            }
        }

        val skillJson = extractMarker(trimmed, "[LUXIFY_SKILL]", "[/LUXIFY_SKILL]")
        if (skillJson != null) {
            return try {
                val payload = jsonParser.decodeFromString<LuxifySkillPayload>(skillJson)
                val document = SkillDocumentData(
                    name = payload.name,
                    description = payload.description,
                    whenToUse = payload.whenToUse,
                    allowedTools = payload.allowedTools,
                    steps = payload.steps.map { step ->
                        SkillStepData(
                            title = step.title,
                            description = step.description,
                            successCriteria = step.successCriteria
                        )
                    }
                )
                _state.update {
                    it.copy(
                        currentQuestion = null,
                        currentSkillDocument = document
                    )
                }
                true
            } catch (e: Exception) {
                false
            }
        }

        return false
    }

    /**
     * Records the user's answer for the active question and clears the question
     * so the caller can send the answer back to the guru as a normal message.
     */
    fun submitAnswer(answer: String) {
        val question = _state.value.currentQuestion ?: return
        if (question.field.isBlank()) return

        _state.update {
            it.copy(
                collectedAnswers = it.collectedAnswers + (question.field to answer),
                currentQuestion = null
            )
        }
    }

    /**
     * Persists the confirmed skill document to the local skill repository and
     * clears interview state.
     */
    fun confirmSkill() {
        val document = _state.value.currentSkillDocument ?: return

        viewModelScope.launch {
            try {
                val bodyMarkdown = buildSkillMarkdown(document)
                val request = CreateLuxifyRequest(
                    name = document.name,
                    description = document.description,
                    whenToUse = document.whenToUse,
                    allowedTools = document.allowedTools,
                    bodyMarkdown = bodyMarkdown
                )
                luxifyRepository.createSkill(request)
                cancelInterview()
            } catch (e: Exception) {
                // Leave the document on screen so the user can retry.
            }
        }
    }

    /**
     * Abandons the interview and clears all interview state.
     */
    fun cancelInterview() {
        _state.value = LuxifyInterviewState()
    }

    private fun extractMarker(content: String, startTag: String, endTag: String): String? {
        val startIndex = content.indexOf(startTag)
        if (startIndex == -1) return null
        val endIndex = content.indexOf(endTag, startIndex + startTag.length)
        if (endIndex == -1) return null
        return content.substring(startIndex + startTag.length, endIndex).trim()
    }

    private fun buildSkillMarkdown(document: SkillDocumentData): String {
        val builder = StringBuilder()
        builder.appendLine("# ${document.name}")
        builder.appendLine()
        builder.appendLine(document.description)
        builder.appendLine()
        builder.appendLine("## When to use")
        builder.appendLine(document.whenToUse)
        builder.appendLine()
        if (document.allowedTools.isNotEmpty()) {
            builder.appendLine("## Allowed tools")
            document.allowedTools.forEach { tool ->
                builder.appendLine("- $tool")
            }
            builder.appendLine()
        }
        builder.appendLine("## Steps")
        document.steps.forEachIndexed { index, step ->
            builder.appendLine("${index + 1}. ${step.title}")
            if (step.description.isNotBlank()) {
                builder.appendLine("   ${step.description}")
            }
            if (step.successCriteria.isNotBlank()) {
                builder.appendLine("   Success criteria: ${step.successCriteria}")
            }
            builder.appendLine()
        }
        return builder.toString().trim()
    }
}
