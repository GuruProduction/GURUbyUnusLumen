package com.unuslumen.app.presentation.thoughts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unuslumen.app.thoughts.domain.model.GuruInsight
import com.unuslumen.app.thoughts.domain.model.GuruThoughtCycle
import com.unuslumen.app.thoughts.domain.model.ThoughtCyclesSummary
import com.unuslumen.app.thoughts.domain.repository.ThoughtCycleRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.koin.android.annotation.KoinViewModel

/**
 * ThoughtsViewModel — feeds the thought-cycles screens their live data.
 *
 * Cycles and insights ride repository flows, so anything the background
 * sweep, an event trigger or a chat run produces lands on screen live.
 * A run command executes a cycle on demand from the screen button exactly
 * as a chat trigger would; the result message surfaces the outcome, and
 * failures say WHY instead of dying silently.
 */
@KoinViewModel
class ThoughtsViewModel(
    private val thoughtCycleRepository: ThoughtCycleRepository
) : ViewModel() {

    val cycles: StateFlow<List<GuruThoughtCycle>> =
        thoughtCycleRepository.getAllCyclesFlow()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val insights: StateFlow<List<GuruInsight>> =
        thoughtCycleRepository.getAllInsightsFlow()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _summary = MutableStateFlow<ThoughtCyclesSummary?>(null)
    val summary: StateFlow<ThoughtCyclesSummary?> = _summary.asStateFlow()

    private val _runningCycleId = MutableStateFlow<String?>(null)
    val runningCycleId: StateFlow<String?> = _runningCycleId.asStateFlow()

    private val _lastRunMessage = MutableStateFlow<String?>(null)
    val lastRunMessage: StateFlow<String?> = _lastRunMessage.asStateFlow()

    init {
        refreshSummary()
    }

    fun refreshSummary() {
        viewModelScope.launch {
            _summary.value = thoughtCycleRepository.getSummary()
        }
    }

    fun runCycle(cycleId: String) {
        if (_runningCycleId.value != null) return
        viewModelScope.launch {
            _runningCycleId.value = cycleId
            try {
                val result = thoughtCycleRepository.executeCycle(cycleId)
                _lastRunMessage.value = if (result.success) {
                    "${result.insights.size} insights · ${result.actions.size} actions · ${result.proposals.size} proposals"
                } else {
                    "Run failed: ${result.error ?: "unknown error"}"
                }
            } catch (e: Exception) {
                _lastRunMessage.value = "Run failed: ${e.message}"
            } finally {
                _runningCycleId.value = null
                refreshSummary()
            }
        }
    }

    fun enableCycle(cycleId: String) {
        viewModelScope.launch { thoughtCycleRepository.enableCycle(cycleId) }
    }

    fun disableCycle(cycleId: String) {
        viewModelScope.launch { thoughtCycleRepository.disableCycle(cycleId) }
    }

    fun acknowledgeInsight(insightId: String) {
        viewModelScope.launch { thoughtCycleRepository.acknowledgeInsight(insightId) }
    }

    fun dismissInsight(insightId: String) {
        viewModelScope.launch { thoughtCycleRepository.dismissInsight(insightId) }
    }
}