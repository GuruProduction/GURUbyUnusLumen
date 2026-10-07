// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.presentation.modules

import android.webkit.WebView
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unuslumen.app.domain.model.GuruModule
import com.unuslumen.app.domain.repository.AutomationRunRepository
import com.unuslumen.app.domain.repository.AutomationRepository
import com.unuslumen.app.domain.repository.ModuleRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.koin.android.annotation.KoinViewModel

/**
 * Feeds ModuleScreen: one room's live state, PLUS the living-data wire.
 *
 * Composition rides the repository Flow so the room updates in front of
 * the user the moment the numen re-saves it. Data rides in parallel: the
 * ViewModel collects the sovereign automations/run-recent flows and pushes
 * named snapshots into the canvas (ModuleCanvas.pushData) keyed by the
 * data-room-bind names the room's composition declared. Doctrine: rooms
 * are living mirrors, not photos — data fills the room at open and
 * re-pushes on every change.
 *
 * View rooms bind two canonical keys:
 *   "automations" — the full automation list snapshot
 *   "runs"        — recent automation run traces
 * A render failure sets [renderError] honestly — the broken-room surface
 * says "needs attention, ask me to fix it", never a silent blank.
 */
@KoinViewModel
class ModuleViewModel(
    private val moduleRepository: ModuleRepository,
    private val automationRepository: AutomationRepository,
    private val automationRunRepository: AutomationRunRepository
) : ViewModel() {

    data class ModuleUiState(
        val module: GuruModule? = null,
        val loading: Boolean = true,
        val notFound: Boolean = false,
        val renderError: Boolean = false
    )

    private val _state = MutableStateFlow(ModuleUiState())
    val state: StateFlow<ModuleUiState> = _state.asStateFlow()

    /** Canvas webview wired from ModuleScreen for data pushes. */
    private var canvasWebView: WebView? = null

    fun setCanvas(webView: WebView?) {
        canvasWebView = null
        canvasWebView = webView
    }

    /** Serializable snapshot DTOs — the payload the canvas JS hydrates from. */
    @Serializable
    data class AutomationSnapshot(
        val id: String,
        val name: String,
        val displayName: String,
        val description: String,
        val trigger: String,
        val enabled: Boolean,
        val runCount: Int,
        val lastRunAt: Long?
    )

    @Serializable
    data class RunSnapshot(
        val id: String,
        val automationName: String,
        val trigger: String,
        val startedAt: Long,
        val durationMs: Long,
        val status: String,
        val steps: List<StepTraceSnapshot>
    ) {
        @Serializable
        data class StepTraceSnapshot(
            val index: Int,
            val tool: String,
            val success: Boolean,
            val error: String? = null,
            val durationMs: Long
        )
    }

    private val json = Json { ignoreUnknownKeys = true }

    fun bind(moduleId: String) {
        viewModelScope.launch {
            moduleRepository.getAllModulesFlow().collectLatest { modules ->
                val module = modules.find { it.id == moduleId }
                _state.value = _state.value.copy(
                    module = module,
                    loading = false,
                    notFound = module == null,
                    // A saved-but-unrenderable composition (the numen saved
                    // bad HTML/JS) stays honestly visible until repaired.
                    renderError = module != null && module.compositionHtml.isBlank() && module.status.name != "DRAFT"
                )
            }
        }
        // The living-data wire: every automations change and every run-level
        // mutation re-pushes to the rendered canvas while the room is open.
        viewModelScope.launch {
            automationRepository.getAllAutomationsFlow().collectLatest { autos ->
                val snapshot = autos.map {
                    AutomationSnapshot(
                        id = it.id,
                        name = it.name,
                        displayName = it.displayName,
                        description = it.description,
                        trigger = it.trigger.name,
                        enabled = it.enabled,
                        runCount = it.runCount,
                        lastRunAt = it.lastRunAt
                    )
                }
                pushSnapshot("automations", json.encodeToString(ListSerializer(AutomationSnapshot.serializer()), snapshot))
            }
        }
        viewModelScope.launch {
            automationRunRepository.getRecentRunsFlow(limit = 50).collectLatest { runs ->
                val snapshot = runs.map { run ->
                    RunSnapshot(
                        id = run.id,
                        automationName = run.automationName,
                        trigger = run.trigger.name,
                        startedAt = run.startedAt,
                        durationMs = run.durationMs,
                        status = run.status.name,
                        steps = run.steps.map {
                            RunSnapshot.StepTraceSnapshot(it.index, it.tool, it.success, it.error, it.durationMs)
                        }
                    )
                }
                pushSnapshot("runs", json.encodeToString(ListSerializer(RunSnapshot.serializer()), snapshot))
            }
        }
    }

    private fun pushSnapshot(key: String, payload: String) {
        canvasWebView?.let { ModuleCanvas_pushData(it, key, payload) }
    }

    fun reportRenderError(failed: Boolean) {
        _state.value = _state.value.copy(renderError = failed)
    }
}