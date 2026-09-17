package com.unuslumen.app.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unuslumen.app.preferences.domain.model.PrefsKey
import com.unuslumen.app.preferences.domain.use_case.GetPreferenceUseCase
import com.unuslumen.app.preferences.domain.use_case.SavePreferenceUseCase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import org.koin.android.annotation.KoinViewModel

@KoinViewModel
class SettingsViewModel(
    private val savePreference: SavePreferenceUseCase,
    private val getPreference: GetPreferenceUseCase,
) : ViewModel() {

    fun <T> getSettings(key: PrefsKey<T>, defaultValue: T): Flow<T> {
        return getPreference(key, defaultValue)
    }

    fun <T> saveSettings(key: PrefsKey<T>, value: T) {
        viewModelScope.launch {
            savePreference(key, value)
        }
    }
}