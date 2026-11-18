// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.prompts

import com.unuslumen.app.preferences.domain.use_case.GetPreferenceUseCase
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/**
 * The prompts room's bindings, declared by hand.
 *
 * NOT `@Module` PLUS `@KoinViewModel`, DELIBERATELY. That pair produced EMPTY modules
 * for Ottio and Agents: Koin's KSP pass grouped both view models into a DefaultModule
 * this build switches off, so the generated modules came out as `module { }` and both
 * rooms crashed on open. Declaring the definition explicitly removes the dependency on
 * how the generator groups things across a Gradle boundary, and there is a test beside
 * this file that asks a real container for the view model, which is what the device does.
 */
val promptsModule = module {
    viewModel {
        PromptsViewModel(
            context = get(),
            getPreference = get<GetPreferenceUseCase>(),
        )
    }
}
