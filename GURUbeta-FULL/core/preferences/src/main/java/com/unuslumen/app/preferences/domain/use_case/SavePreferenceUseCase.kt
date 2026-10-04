// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
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