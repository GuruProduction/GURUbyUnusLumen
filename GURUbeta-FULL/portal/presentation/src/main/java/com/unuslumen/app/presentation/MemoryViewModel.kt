package com.unuslumen.app.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unuslumen.app.domain.memory.MemoryFact
import com.unuslumen.app.domain.memory.ConversationThread
import com.unuslumen.app.domain.memory.MemoryRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.koin.android.annotation.KoinViewModel

@KoinViewModel
class MemoryViewModel(
    private val memoryRepository: MemoryRepository
) : ViewModel() {

    private val _facts = MutableStateFlow<List<MemoryFact>>(emptyList())
    val facts: StateFlow<List<MemoryFact>> = _facts.asStateFlow()

    private val _threads = MutableStateFlow<List<ConversationThread>>(emptyList())
    val threads: StateFlow<List<ConversationThread>> = _threads.asStateFlow()

    private val _selectedCategory = MutableStateFlow<String?>(null)
    val selectedCategory: StateFlow<String?> = _selectedCategory.asStateFlow()

    private val _totalFacts = MutableStateFlow(0)
    val totalFacts: StateFlow<Int> = _totalFacts.asStateFlow()

    private val _totalThreads = MutableStateFlow(0)
    val totalThreads: StateFlow<Int> = _totalThreads.asStateFlow()

    val categories = listOf("user_preference", "personal_info", "project_info", "decision", "pattern", "relationship", "knowledge")

    init {
        loadAll()
    }

    fun loadAll() {
        viewModelScope.launch {
            _facts.value = memoryRepository.getAllFacts()
            _threads.value = memoryRepository.getAllThreads()
            _totalFacts.value = memoryRepository.getTotalFacts()
            _totalThreads.value = memoryRepository.getTotalThreads()
        }
    }

    fun filterByCategory(category: String?) {
        _selectedCategory.value = category
        viewModelScope.launch {
            _facts.value = if (category != null) {
                memoryRepository.getFactsByCategory(category)
            } else {
                memoryRepository.getAllFacts()
            }
        }
    }
}
