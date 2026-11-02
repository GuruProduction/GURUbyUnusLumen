// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.heartbeat

import android.content.Context
import com.unuslumen.app.domain.repository.HeartbeatPromptRepository
import com.unuslumen.app.preferences.domain.use_case.GetPreferenceUseCase
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/**
 * The heartbeat room's bindings, declared by hand.
 *
 * NOT @Module PLUS @KoinViewModel, DELIBERATELY. That pair was used for Ottio and
 * Agents and produced EMPTY modules: Koin's KSP pass tagged both view models into a
 * DefaultModule that this build switches off, so the generated modules came out as
 * `module { }` and both rooms crashed on open. Declaring the definition explicitly
 * removes the dependency on how the generator groups things across a Gradle
 * boundary, and there is a test beside this file that starts a real container and
 * asks for the view model, which is what the device does.
 */
val heartbeatModule = module {
    viewModel {
        HeartbeatViewModel(
            promptRepository = get<HeartbeatPromptRepository>(),
            getPreference = get<GetPreferenceUseCase>(),
            // THE DOOR TO THE CHAIN, FOR ONE PURPOSE: firing a beat by hand.
            //
            // A dead heartbeat and a dead alarm chain have the same symptom, and
            // nothing on screen could tell them apart. This callback is resolved lazily
            // at the moment the button is pressed, so the view model never holds an
            // AlarmManager authority and the room never depends on the chain existing
            // until somebody asks it to fire.
            fireNow = {
                runCatching {
                    com.unuslumen.app.data.heartbeat.HeartbeatScheduler.fireNow(
                        get<Context>(),
                    )
                }.getOrDefault(false)
            },
        )
    }
}
