// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.domain.use_case

import com.unuslumen.app.domain.repository.FileUtilsRepository
import com.unuslumen.app.preferences.PrefsConstants
import com.unuslumen.app.preferences.domain.model.stringPreferencesKey
import com.unuslumen.app.preferences.domain.use_case.SavePreferenceUseCase
import org.koin.core.annotation.Factory

@Factory
class UpdateExternalNotesFolderUseCase(
    private val savePreferenceUseCase: SavePreferenceUseCase,
    private val fileUtilsRepository: FileUtilsRepository
) {
    suspend operator fun invoke(uri: String) {
        savePreferenceUseCase(stringPreferencesKey(PrefsConstants.EXTERNAL_NOTES_FOLDER_URI), uri)
        savePreferenceUseCase(stringPreferencesKey(
            PrefsConstants.EXTERNAL_NOTES_FOLDER_PATH),
            fileUtilsRepository.getPathFromUri(uri).orEmpty()
        )
        fileUtilsRepository.takePersistablePermission(uri)
    }
}