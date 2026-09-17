package com.unuslumen.app.presentation

import androidx.compose.material3.SnackbarHostState
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unuslumen.app.domain.model.Note
import com.unuslumen.app.domain.model.NoteFolder
import com.unuslumen.app.domain.use_case.CreateNoteFolderUseCase
import com.unuslumen.app.domain.use_case.GetAllNoteFoldersUseCase
import com.unuslumen.app.domain.use_case.GetAllNotesUseCase
import com.unuslumen.app.domain.use_case.SearchNotesUseCase
import com.unuslumen.app.preferences.PrefsConstants
import com.unuslumen.app.preferences.domain.model.Order
import com.unuslumen.app.preferences.domain.model.OrderType
import com.unuslumen.app.preferences.domain.model.booleanPreferencesKey
import com.unuslumen.app.preferences.domain.model.intPreferencesKey
import com.unuslumen.app.preferences.domain.model.toInt
import com.unuslumen.app.preferences.domain.model.toOrder
import com.unuslumen.app.preferences.domain.use_case.GetPreferenceUseCase
import com.unuslumen.app.preferences.domain.use_case.SavePreferenceUseCase
import com.unuslumen.app.ui.ItemView
import com.unuslumen.app.ui.R
import com.unuslumen.app.ui.errors.toMessageResId
import com.unuslumen.app.ui.snackbar.showSnackbar
import com.unuslumen.app.ui.toNotesView
import com.unuslumen.app.util.errors.NoteException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.koin.android.annotation.KoinViewModel

@KoinViewModel
class NotesViewModel(
    private val getAllNotes: GetAllNotesUseCase,
    private val searchNotes: SearchNotesUseCase,
    private val getPreference: GetPreferenceUseCase,
    private val savePreference: SavePreferenceUseCase,
    private val getAllFolders: GetAllNoteFoldersUseCase,
    private val createFolder: CreateNoteFolderUseCase,
) : ViewModel() {

    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        if (throwable is NoteException) {
            viewModelScope.launch {
                notesUiState.value.snackbarHostState.showSnackbar(throwable.toMessageResId())
            }
        }
    }

    private val _notesUiState = MutableStateFlow(UiState())
    val notesUiState = _notesUiState.asStateFlow()

    private var getNotesJob: Job? = null

    init {
        viewModelScope.launch {
            launch {
                combine(
                    getPreference(
                        intPreferencesKey(PrefsConstants.NOTES_ORDER_KEY),
                        Order.DateModified(OrderType.ASC).toInt()
                    ),
                    getPreference(
                        intPreferencesKey(PrefsConstants.NOTE_VIEW_KEY),
                        ItemView.LIST.value
                    ),
                    getPreference(
                        booleanPreferencesKey(PrefsConstants.SHOW_ALL_NOTES_KEY),
                        false
                    )
                ) { order, view, showAllNotes ->
                    val nextOrder = order.toOrder()
                    getNotes(nextOrder, showAllNotes)
                    _notesUiState.update {
                        it.copy(
                            notesOrder = nextOrder,
                            showAllNotes = showAllNotes,
                            noteView = view.toNotesView()
                        )
                    }
                }.collect()
            }

            launch {
                getAllFolders().collect { folders ->
                    _notesUiState.update {
                        it.copy(
                            folders = folders
                        )
                    }
                }
            }
        }
    }

    fun onEvent(event: NoteEvent) {
        when (event) {

            is NoteEvent.SearchNotes -> viewModelScope.launch {
                _notesUiState.update {
                    it.copy(
                        searchNotes = searchNotes(event.query)
                    )
                }
            }

            is NoteEvent.UpdateOrder -> viewModelScope.launch {
                savePreference(
                    intPreferencesKey(PrefsConstants.NOTES_ORDER_KEY),
                    event.order.toInt()
                )
            }

            is NoteEvent.UpdateView -> viewModelScope.launch {
                savePreference(
                    intPreferencesKey(PrefsConstants.NOTE_VIEW_KEY),
                    event.view.value
                )
            }

            is NoteEvent.ShowAllNotes -> viewModelScope.launch {
                savePreference(
                    booleanPreferencesKey(PrefsConstants.SHOW_ALL_NOTES_KEY),
                    event.showAll
                )
            }

            is NoteEvent.CreateFolder -> viewModelScope.launch(exceptionHandler) {
                if (event.name.isBlank()) {
                    notesUiState.value.snackbarHostState.showSnackbar(R.string.error_empty_title)
                } else {
                    createFolder(event.name)
                }
            }

        }
    }

    data class UiState(
        val notes: List<Note> = emptyList(),
        val notesOrder: Order = Order.DateModified(OrderType.ASC),
        val noteView: ItemView = ItemView.LIST,
        val navigateUp: Boolean = false,
        val searchNotes: List<Note> = emptyList(),
        val folders: List<NoteFolder> = emptyList(),
        val folderNotes: List<Note> = emptyList(),
        val showAllNotes: Boolean = false,
        val snackbarHostState: SnackbarHostState = SnackbarHostState(),
    )

    private fun getNotes(order: Order, showAllNotes: Boolean) {
        getNotesJob?.cancel()
        getNotesJob = getAllNotes(order, showAllNotes)
            .onEach { notes ->
                _notesUiState.update {
                    it.copy(
                        notes = notes,
                        notesOrder = order
                    )
                }
            }.launchIn(viewModelScope)
    }
}