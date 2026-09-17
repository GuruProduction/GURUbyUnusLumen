package com.unuslumen.app.presentation

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unuslumen.app.domain.autoFormatNotePrompt
import com.unuslumen.app.domain.correctSpellingNotePrompt
import com.unuslumen.app.domain.model.PortalResult
import com.unuslumen.app.domain.model.Note
import com.unuslumen.app.domain.model.NoteFolder
import com.unuslumen.app.domain.summarizeNotePrompt
import com.unuslumen.app.domain.use_case.DeleteNoteUseCase
import com.unuslumen.app.domain.use_case.GetAllNoteFoldersUseCase
import com.unuslumen.app.domain.use_case.GetNoteFolderUseCase
import com.unuslumen.app.domain.use_case.GetNoteUseCase
import com.unuslumen.app.domain.use_case.SendAiPromptUseCase
import com.unuslumen.app.domain.use_case.UpsertNoteUseCase
import com.unuslumen.app.preferences.PrefsConstants
import com.unuslumen.app.preferences.domain.model.AiProvider
import com.unuslumen.app.preferences.domain.model.intPreferencesKey
import com.unuslumen.app.preferences.domain.model.toAiProvider
import com.unuslumen.app.preferences.domain.use_case.GetPreferenceUseCase
import com.unuslumen.app.ui.R
import com.unuslumen.app.ui.errors.toSnackbarError
import com.unuslumen.app.ui.snackbar.showSnackbar
import com.unuslumen.app.util.date.now
import com.unuslumen.app.util.errors.NoteException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.koin.android.annotation.KoinViewModel
import org.koin.core.annotation.Named

@KoinViewModel
class NoteDetailsViewModel(
    private val getNote: GetNoteUseCase,
    private val upsertNote: UpsertNoteUseCase,
    private val deleteNote: DeleteNoteUseCase,
    private val getPreference: GetPreferenceUseCase,
    private val getAllFolders: GetAllNoteFoldersUseCase,
    private val getNoteFolder: GetNoteFolderUseCase,
    private val sendAiPrompt: SendAiPromptUseCase,
    @Named("applicationScope") private val applicationScope: CoroutineScope,
    id: String,
    folderId: String,
) : ViewModel() {

    private val _noteUiState = MutableStateFlow(UiState())
    val noteUiState = _noteUiState.asStateFlow()

    var title by mutableStateOf("")
        private set
    var content by mutableStateOf("")
        private set

    private val exceptionHandler = CoroutineExceptionHandler { _, ex ->
        viewModelScope.launch {
            val exception = ex as? NoteException ?: NoteException.UnknownError
            noteUiState.value.snackbarHostState.showSnackbar(exception.toSnackbarError())
        }
    }
    private var autoSaveJob: Job? = null
    private val saveMutex = Mutex()

    private val _aiEnabled = MutableStateFlow(false)
    val aiEnabled = _aiEnabled.asStateFlow()
    var aiState by mutableStateOf((AiState()))
        private set
    private var aiActionJob: Job? = null

    init {
        viewModelScope.launch(exceptionHandler) {
            val note: Note? = if (id.isNotBlank()) getNote(id) else null
            val folderId = note?.folderId ?: folderId.ifBlank { null }
            val folder = folderId?.let { getNoteFolder(it) }
            val folders = getAllFolders().first()

            if (id.isNotBlank() && note == null) {
                noteUiState.value.snackbarHostState.showSnackbar(R.string.error_item_not_found)
            }

            if (note != null) {
                title = note.title
                content = note.content
            }

            _noteUiState.update {
                it.copy(
                    note = note?.copy(folderId = folder?.id ?: note.folderId),
                    folder = folder,
                    folders = folders,
                    readingMode = note != null,
                    pinned = note?.pinned ?: false
                )
            }
        }
        viewModelScope.launch(exceptionHandler) {
            getPreference(intPreferencesKey(PrefsConstants.AI_PROVIDER_KEY), AiProvider.None.id)
                .map { it.toAiProvider() }
                .collect { provider ->
                    _aiEnabled.update { provider != AiProvider.None }
                }
        }
    }

    fun onEvent(event: NoteDetailsEvent) {
        when (event) {
            is NoteDetailsEvent.ScreenOnStop -> applicationScope.launch(exceptionHandler) {
                if (!noteUiState.value.navigateUp) forceSaveNote()
            }

            is NoteDetailsEvent.DeleteNote -> viewModelScope.launch {
                deleteNote(event.note)
                _noteUiState.update { it.copy(navigateUp = true) }
            }

            NoteDetailsEvent.ToggleReadingMode -> _noteUiState.update {
                it.copy(readingMode = !it.readingMode)
            }

            is NoteDetailsEvent.UpdateTitle -> {
                title = event.title
                autoSaveNote()
            }

            is NoteDetailsEvent.UpdateContent -> {
                content = event.content
                autoSaveNote()
            }

            is NoteDetailsEvent.UpdateFolder -> {
                _noteUiState.update { it.copy(folder = event.folder) }
                viewModelScope.launch(exceptionHandler) {
                    forceSaveNote()
                }
            }

            is NoteDetailsEvent.UpdatePinned -> {
                _noteUiState.update { it.copy(pinned = event.pinned) }
                autoSaveNote()
            }

            is AiAction -> aiActionJob = viewModelScope.launch {
                val prompt = when (event) {
                    is NoteDetailsEvent.Summarize -> event.content.summarizeNotePrompt
                    is NoteDetailsEvent.AutoFormat -> event.content.autoFormatNotePrompt
                    is NoteDetailsEvent.CorrectSpelling -> event.content.correctSpellingNotePrompt
                }
                aiState = aiState.copy(
                    loading = true,
                    showAiSheet = true,
                    result = null,
                    error = null
                )
                val result = sendAiPrompt(prompt)
                aiState = when (result) {
                    is PortalResult.Success -> aiState.copy(
                        loading = false,
                        result = result.data,
                        error = null
                    )

                    is PortalResult.Failure -> aiState.copy(error = result, loading = false)
                }
            }

            NoteDetailsEvent.AiResultHandled -> {
                aiActionJob?.cancel()
                aiActionJob = null
                aiState = aiState.copy(showAiSheet = false)
            }
        }
    }

    private fun autoSaveNote() {
        autoSaveJob?.cancel()
        autoSaveJob = applicationScope.launch(exceptionHandler) {
            delay(2000L)
            saveMutex.withLock {
                saveNote()
            }
        }
    }

    private suspend fun forceSaveNote() {
        autoSaveJob?.cancel()
        saveMutex.withLock { saveNote() }
    }

    private suspend fun saveNote() {
        if (noteUiState.value.navigateUp) return

        val folderId = noteUiState.value.folder?.id
        val pinned = noteUiState.value.pinned

        if (noteUiState.value.note == null) {
            if (title.isNotBlank() || content.isNotBlank()) {
                val note = Note(
                    title = title,
                    content = content,
                    folderId = folderId,
                    pinned = pinned,
                    createdDate = now(),
                    updatedDate = now()
                )
                val noteId = upsertNote(note, null)
                _noteUiState.update {
                    it.copy(
                        note = note.copy(
                            id = noteId,
                            title = title,
                            content = content
                        )
                    )
                }
            }
        } else {
            val currentNote = noteUiState.value.note!!
            if (currentNote.title != title ||
                currentNote.content != content ||
                currentNote.folderId != folderId ||
                currentNote.pinned != pinned
            ) {
                val newNote = currentNote.copy(
                    title = title,
                    content = content,
                    folderId = folderId,
                    pinned = pinned,
                    updatedDate = now()
                )
                val noteId = upsertNote(newNote, currentNote.folderId)
                _noteUiState.update {
                    it.copy(
                        note = newNote.copy(id = noteId)
                    )
                }
            }
        }
    }

    data class UiState(
        val note: Note? = null,
        val navigateUp: Boolean = false,
        val readingMode: Boolean = false,
        val folders: List<NoteFolder> = emptyList(),
        val folder: NoteFolder? = null,
        val pinned: Boolean = false,
        val snackbarHostState: SnackbarHostState = SnackbarHostState()
    )

    data class AiState(
        val loading: Boolean = false,
        val result: String? = null,
        val error: PortalResult.Failure? = null,
        val showAiSheet: Boolean = false,
    )
}