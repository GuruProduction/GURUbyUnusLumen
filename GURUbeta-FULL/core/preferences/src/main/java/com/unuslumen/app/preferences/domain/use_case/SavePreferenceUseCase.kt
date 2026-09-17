package com.unuslumen.app.preferences.domain.use_case

import com.unuslumen.app.preferences.domain.model.PrefsKey
import com.unuslumen.app.preferences.domain.repository.PreferenceRepository
import org.koin.core.annotation.Single

@Single
class SavePreferenceUseCase(
  private val preferenceRepository: PreferenceRepository
) {
    suspend operator fun <T> invoke(key: PrefsKey<T>, value: T) = preferenceRepository.savePreference(key, value)
}