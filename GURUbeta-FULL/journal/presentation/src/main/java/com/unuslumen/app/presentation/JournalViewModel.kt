package com.unuslumen.app.presentation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unuslumen.app.preferences.PrefsConstants
import com.unuslumen.app.domain.model.JournalEntry
import com.unuslumen.app.domain.use_case.*
import com.unuslumen.app.preferences.domain.model.Order
import com.unuslumen.app.preferences.domain.model.OrderType
import com.unuslumen.app.preferences.domain.model.intPreferencesKey
import com.unuslumen.app.preferences.domain.model.toInt
import com.unuslumen.app.preferences.domain.model.toOrder
import com.unuslumen.app.preferences.domain.use_case.GetPreferenceUseCase
import com.unuslumen.app.preferences.domain.use_case.SavePreferenceUseCase
import com.unuslumen.app.util.date.formatDateForMapping
import com.unuslumen.app.util.date.inTheLast30Days
import com.unuslumen.app.util.date.inTheLastYear
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import org.koin.android.annotation.KoinViewModel
import org.koin.core.annotation.Named

@KoinViewModel
class JournalViewModel(
    private val getAlEntries: GetAllJournalEntriesUseCase,
    private val searchEntries: SearchJournalEntriesUseCase,
    private val getPreference: GetPreferenceUseCase,
    private val savePreference: SavePreferenceUseCase,
    private val getEntriesForChart: GetJournalForChartUseCase,
    @Named("defaultDispatcher") private val defaultDispatcher: CoroutineDispatcher
) : ViewModel() {

    var uiState by mutableStateOf(UiState())
        private set

    private var getEntriesJob: Job? = null

    init {
        viewModelScope.launch {
            getPreference(
                intPreferencesKey(PrefsConstants.JOURNAL_ORDER_KEY),
                Order.DateModified(OrderType.ASC).toInt()
            ).collect {
                getEntries(it.toOrder())
            }
        }
    }

    fun onEvent(event: JournalEvent) {
        when (event) {
            is JournalEvent.SearchEntries -> viewModelScope.launch {
                val entries = searchEntries(event.query)
                uiState = uiState.copy(
                    searchEntries = entries
                )
            }
            is JournalEvent.UpdateOrder -> viewModelScope.launch {
                savePreference(
                    intPreferencesKey(PrefsConstants.JOURNAL_ORDER_KEY),
                    event.order.toInt()
                )
            }
            is JournalEvent.ChangeChartEntriesRange -> viewModelScope.launch {
                uiState = uiState.copy(chartEntries = getEntriesForChart {
                    if (event.monthly) it.createdDate.inTheLast30Days()
                    else it.createdDate.inTheLastYear()
                })
            }
        }
    }

    data class UiState(
        val entries: Map<String, List<JournalEntry>> = emptyMap(),
        val entriesOrder: Order = Order.DateModified(OrderType.ASC),
        val searchEntries: List<JournalEntry> = emptyList(),
        val chartEntries : List<JournalEntry> = emptyList()
    )

    private fun getEntries(order: Order) {
        getEntriesJob?.cancel()
        getEntriesJob = getAlEntries(order)
            .onEach { entries ->
                uiState = uiState.copy(
                    entries = entries.groupBy {
                        it.createdDate.formatDateForMapping()
                    },
                    entriesOrder = order
                )
            }
            .flowOn(defaultDispatcher)
            .launchIn(viewModelScope)
    }

}