package com.unuslumen.app.presentation.integrations

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unuslumen.app.data.noteMarkdownModule
import com.unuslumen.app.data.noteRoomModule
import com.unuslumen.app.domain.repository.FileUtilsRepository
import com.unuslumen.app.domain.use_case.UpdateExternalNotesFolderUseCase
import com.unuslumen.app.preferences.PrefsConstants
import com.unuslumen.app.preferences.domain.model.AiProvider
import com.unuslumen.app.preferences.domain.model.PrefsKey
import com.unuslumen.app.preferences.domain.model.PrefsKey.BooleanKey
import com.unuslumen.app.preferences.domain.model.PrefsKey.IntKey
import com.unuslumen.app.preferences.domain.model.stringPreferencesKey
import com.unuslumen.app.preferences.domain.model.toAiProvider
import com.unuslumen.app.preferences.domain.use_case.GetPreferenceUseCase
import com.unuslumen.app.preferences.domain.use_case.SavePreferenceUseCase
import com.unuslumen.app.util.shell.AdbPairingClient
import com.unuslumen.app.util.shell.ShellExecutor
import com.unuslumen.app.util.orb.GuruOrbService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.annotation.KoinViewModel
import org.koin.core.context.GlobalContext.loadKoinModules
import org.koin.core.context.GlobalContext.unloadKoinModules

@KoinViewModel
class IntegrationsViewModel(
    private val savePreference: SavePreferenceUseCase,
    private val getPreference: GetPreferenceUseCase,
    private val updateExternalNotesFolder: UpdateExternalNotesFolderUseCase,
    private val fileUtilsRepository: FileUtilsRepository
) : ViewModel() {

    /**
     * Lazy AI repository access: only the provider-save events need it. Resolved
     * through the global Koin context so the ViewModel's constructor signature
     * (and every existing construction site) stays untouched.
     */
    private val aiRepository: com.unuslumen.app.domain.repository.AiRepository? by lazy {
        try {
            org.koin.core.context.GlobalContext.get().get()
        } catch (e: Exception) {
            null
        }
    }

    /**
     * BYO changes must land on the next send, not after an app restart. Every
     * provider-connection save therefore asks the AI repository to rebuild its
     * executor + streaming client from the freshly written prefs. DataStore
     * writes are launch-async; the rebuild path already waits half a beat so
     * the last write is visible before it reads prefs.
     */
    private fun notifyAiRepositorySettingsChanged() {
        try {
            aiRepository?.reinitialise()
        } catch (e: Exception) {
            android.util.Log.w("guru", "Model re-init after save failed: ${e.message}")
        }
    }

    // ADB pairing state
    private val _adbPairingState = MutableStateFlow<AdbPairingState>(AdbPairingState.Idle)
    val adbPairingState: StateFlow<AdbPairingState> = _adbPairingState

    private val _adbPaired = MutableStateFlow(false)
    val adbPaired: StateFlow<Boolean> = _adbPaired

    // Floating orb state
    private val _floatingOrbEnabled = MutableStateFlow(false)
    val floatingOrbEnabled: StateFlow<Boolean> = _floatingOrbEnabled

    init {
        viewModelScope.launch {
            getPreference(BooleanKey(PrefsConstants.GURU_FLOATING_ORB_ENABLED), false).collect { enabled ->
                _floatingOrbEnabled.value = enabled
            }
        }
    }

    fun fetchAvailableModels(baseUrl: String) {
        // Model fetching is now handled by LlmConfigFetcher on the AI repository side
    }

    /**
     * Pair with ADB Wireless Debugging using a pairing code.
     * The user gets the code from: Settings > Developer Options > Wireless Debugging > Pair device
     */
    fun pairWithAdb(context: Context, pairingCode: String, port: Int? = null) {
        viewModelScope.launch {
            _adbPairingState.value = AdbPairingState.Pairing
            val shellExecutor = ShellExecutor(context)
            val result = if (port != null && port > 0) {
                shellExecutor.pairWithAdb(pairingCode, port)
            } else {
                shellExecutor.autoPairWithAdb(pairingCode)
            }
            if (result.success) {
                _adbPairingState.value = AdbPairingState.Paired
                _adbPaired.value = true
            } else {
                _adbPairingState.value = AdbPairingState.Error(result.error ?: "Pairing failed")
            }
        }
    }

    /**
     * Check if ADB is available and update state.
     */
    fun checkAdbStatus(context: Context) {
        viewModelScope.launch {
            val shellExecutor = ShellExecutor(context)
            val available = shellExecutor.isAdbAvailable()
            if (available) {
                _adbPaired.value = true
                _adbPairingState.value = AdbPairingState.Paired
            } else {
                _adbPaired.value = false
                _adbPairingState.value = AdbPairingState.Idle
            }
        }
    }

    /**
     * Auto-pair ADB using the accessibility service.
     * Guru navigates to Settings, reads the pairing code, and pairs automatically.
     */
    fun autoPairAdb(context: Context) {
        viewModelScope.launch {
            _adbPairingState.value = AdbPairingState.Pairing
            val shellExecutor = ShellExecutor(context)
            val result = shellExecutor.autoPairAdb()
            if (result.success) {
                _adbPairingState.value = AdbPairingState.Paired
                _adbPaired.value = true
            } else {
                _adbPairingState.value = AdbPairingState.Error(result.error ?: "Auto-pair failed")
            }
        }
    }

    fun <T> getSettings(key: PrefsKey<T>, defaultValue: T): Flow<T> {
        return getPreference(key, defaultValue)
    }

    fun getAiProvider(): Flow<AiProvider> = getPreference(
        IntKey(PrefsConstants.AI_PROVIDER_KEY),
        AiProvider.None.id
    ).map { it.toAiProvider() }

    fun getExternalNotesFolderPath(): Flow<String?> {
        return getPreference(
            stringPreferencesKey(PrefsConstants.EXTERNAL_NOTES_FOLDER_URI),
            ""
        ).map { uri ->
            if (uri.isBlank()) null
            else fileUtilsRepository.getPathFromUri(uri)
        }
    }

    /** The user's BYO connection details, all on-device. */
    fun getByoBaseUrl(): Flow<String> = getPreference(
        stringPreferencesKey(PrefsConstants.BYO_BASE_URL_KEY),
        ""
    )

    fun getByoApiKey(): Flow<String> = getPreference(
        stringPreferencesKey(PrefsConstants.BYO_API_KEY_KEY),
        ""
    )

    fun getByoModelName(): Flow<String> = getPreference(
        stringPreferencesKey(PrefsConstants.BYO_MODEL_NAME_KEY),
        ""
    )

    fun onEvent(event: IntegrationsEvent) {
        when (event) {
            is IntegrationsEvent.ToggleAiProvider -> {
                saveSettings(
                    IntKey(PrefsConstants.AI_PROVIDER_KEY),
                    if (event.enabled) AiProvider.UnusLumen.id else AiProvider.None.id
                )
            }

            is IntegrationsEvent.SelectProvider -> {
                saveSettings(
                    IntKey(PrefsConstants.AI_PROVIDER_KEY),
                    event.provider.id
                )
                notifyAiRepositorySettingsChanged()
            }

            is IntegrationsEvent.UpdateApiKey -> {
                // BYO key: stored on device only, consumed by the AI repository.
                // Never uploaded anywhere (README: your key stays on your device).
                saveSettings(stringPreferencesKey(PrefsConstants.BYO_API_KEY_KEY), event.key.trim())
                notifyAiRepositorySettingsChanged()
            }

            is IntegrationsEvent.UpdateModel -> {
                // Model name as the user's server knows it, saved on device.
                saveSettings(stringPreferencesKey(PrefsConstants.BYO_MODEL_NAME_KEY), event.model.trim())
                notifyAiRepositorySettingsChanged()
            }

            is IntegrationsEvent.ToggleCustomURL -> {
                // Toggling the custom-URL shape switches between the user's own
                // endpoint (Ollama/OpenAI-compat) and the previous provider.
                saveSettings(
                    IntKey(PrefsConstants.AI_PROVIDER_KEY),
                    if (event.enabled) event.provider.id else AiProvider.None.id
                )
            }

            is IntegrationsEvent.UpdateCustomURL -> {
                // The user's own OpenAI-compatible / Ollama endpoint.
                saveSettings(stringPreferencesKey(PrefsConstants.BYO_BASE_URL_KEY), event.url.trim())
                notifyAiRepositorySettingsChanged()
            }

            is IntegrationsEvent.ToggleAiTools -> {
                saveSettings(
                    BooleanKey(PrefsConstants.AI_TOOLS_ENABLED_KEY),
                    event.enabled
                )
            }

            is IntegrationsEvent.SelectExternalNotesFolder -> {
                viewModelScope.launch {
                    updateExternalNotesFolder(event.folderUri)
                    unloadKoinModules(noteRoomModule)
                    loadKoinModules(noteMarkdownModule(event.folderUri))
                }
            }

            is IntegrationsEvent.SetExternalNotesEnabled -> {
                saveSettings(
                    BooleanKey(PrefsConstants.EXTERNAL_NOTES_ENABLED),
                    event.enabled
                )
                viewModelScope.launch {
                    if (event.enabled) {
                        val rootUri = getPreference(
                            stringPreferencesKey(PrefsConstants.EXTERNAL_NOTES_FOLDER_URI),
                            ""
                        ).first()
                        if (rootUri.isNotBlank()) {
                            loadKoinModules(noteMarkdownModule(rootUri))
                        }
                    } else {
                        loadKoinModules(noteRoomModule)
                    }
                }
            }

            is IntegrationsEvent.FetchModels -> {
                // Model fetching is now server-side
            }

            is IntegrationsEvent.PairAdb -> {
                // Context will be passed from the UI layer
                // This event is handled directly in the composable
            }

            is IntegrationsEvent.CheckAdbStatus -> {
                // This event is handled directly in the composable
            }

            is IntegrationsEvent.ToggleFloatingOrb -> {
                saveSettings(
                    BooleanKey(PrefsConstants.GURU_FLOATING_ORB_ENABLED),
                    event.enabled
                )
            }

            is IntegrationsEvent.AutoPairAdb -> {
                // Handled in composable with context
            }

            is IntegrationsEvent.SavePreference<*> -> {
                @Suppress("UNCHECKED_CAST")
                saveSettings(event.key as PrefsKey<Any?>, event.value)
            }
        }
    }

    private fun <T> saveSettings(key: PrefsKey<T>, value: T) {
        viewModelScope.launch {
            savePreference(key, value)
        }
    }
}

/**
 * State of ADB Wireless Debugging pairing.
 */
sealed class AdbPairingState {
    /** Not yet attempted pairing */
    object Idle : AdbPairingState()
    /** Currently pairing in progress */
    object Pairing : AdbPairingState()
    /** Successfully paired */
    object Paired : AdbPairingState()
    /** Pairing failed */
    data class Error(val message: String) : AdbPairingState()
}

