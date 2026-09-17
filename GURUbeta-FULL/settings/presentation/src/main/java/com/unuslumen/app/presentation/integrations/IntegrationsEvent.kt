package com.unuslumen.app.presentation.integrations

import com.unuslumen.app.preferences.domain.model.AiProvider
import com.unuslumen.app.preferences.domain.model.PrefsKey

sealed class IntegrationsEvent {
    data class ToggleAiProvider(val enabled: Boolean) : IntegrationsEvent()
    data class SelectProvider(val provider: AiProvider) : IntegrationsEvent()
    data class UpdateApiKey(val provider: AiProvider, val key: String) : IntegrationsEvent()
    data class UpdateModel(val provider: AiProvider, val model: String) : IntegrationsEvent()
    data class ToggleCustomURL(val provider: AiProvider, val enabled: Boolean) : IntegrationsEvent()
    data class UpdateCustomURL(val provider: AiProvider, val url: String) : IntegrationsEvent()

    data class ToggleAiTools(val enabled: Boolean) : IntegrationsEvent()

    data class SetExternalNotesEnabled(val enabled: Boolean) : IntegrationsEvent()
    data class SelectExternalNotesFolder(val folderUri: String) : IntegrationsEvent()
    data class FetchModels(val baseUrl: String) : IntegrationsEvent()
    data class PairAdb(val pairingCode: String, val port: Int? = null) : IntegrationsEvent()
    data class CheckAdbStatus(val dummy: Unit = Unit) : IntegrationsEvent()
    data class ToggleFloatingOrb(val enabled: Boolean) : IntegrationsEvent()
    data class AutoPairAdb(val dummy: Unit = Unit) : IntegrationsEvent()
    data class SavePreference<T>(val key: PrefsKey<T>, val value: T) : IntegrationsEvent()
}