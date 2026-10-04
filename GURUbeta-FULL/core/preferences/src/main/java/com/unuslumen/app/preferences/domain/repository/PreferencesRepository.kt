// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.preferences.domain.repository

import com.unuslumen.app.preferences.domain.model.PrefsKey
import kotlinx.coroutines.flow.Flow

interface PreferenceRepository {

    suspend fun <T> savePreference(key: PrefsKey<T>, value: T)

    fun <T> getPreference(key: PrefsKey<T>, defaultValue: T): Flow<T>

}