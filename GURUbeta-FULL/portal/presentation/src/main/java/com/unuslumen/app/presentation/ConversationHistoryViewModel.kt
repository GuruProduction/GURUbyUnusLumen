package com.unuslumen.app.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unuslumen.app.domain.memory.Conversation
import com.unuslumen.app.domain.memory.ConversationMessage
import com.unuslumen.app.domain.memory.MemoryRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.koin.android.annotation.KoinViewModel

@KoinViewModel
class ConversationHistoryViewModel(
    private val memoryRepository: MemoryRepository
) : ViewModel() {

    private val _conversations = MutableStateFlow<List<Conversation>>(emptyList())
    val conversations: StateFlow<List<Conversation>> = _conversations.asStateFlow()

    private val _messages = MutableStateFlow<List<ConversationMessage>>(emptyList())
    val messages: StateFlow<List<ConversationMessage>> = _messages.asStateFlow()

    private val _selectedConversationId = MutableStateFlow<String?>(null)
    val selectedConversationId: StateFlow<String?> = _selectedConversationId.asStateFlow()

    init {
        loadConversations()
    }

    fun loadConversations() {
        viewModelScope.launch {
            _conversations.value = memoryRepository.getAllConversations()
        }
    }

    fun selectConversation(conversationId: String) {
        _selectedConversationId.value = conversationId
        viewModelScope.launch {
            _messages.value = memoryRepository.getMessagesByConversation(conversationId)
        }
    }

    fun deleteConversation(conversationId: String) {
        viewModelScope.launch {
            memoryRepository.deleteConversation(conversationId)
            _selectedConversationId.value = null
            _messages.value = emptyList()
            loadConversations()
        }
    }

    fun clearSelection() {
        _selectedConversationId.value = null
        _messages.value = emptyList()
    }
}
