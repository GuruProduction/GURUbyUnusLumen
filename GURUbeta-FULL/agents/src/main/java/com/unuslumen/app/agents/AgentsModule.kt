// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.agents

import com.unuslumen.app.domain.repository.AgentCatalogue
import com.unuslumen.app.domain.repository.GuruAgentRepository
import com.unuslumen.app.preferences.domain.use_case.GetPreferenceUseCase
import com.unuslumen.app.preferences.domain.use_case.SavePreferenceUseCase
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/**
 * The agents room's bindings, declared by hand.
 *
 * WHY NOT @Module PLUS @KoinViewModel. Both annotations were there and KSP still
 * produced an empty module: AgentsViewModel was generated into a file tagged
 * `moduleTagId="DefaultModule"`, and default-module generation is switched off in this
 * build. The room then crashed on open with "No definition found for type" because
 * nothing had registered its view model.
 *
 * Declaring the definition directly removes the dependency on how the generator groups
 * things across a Gradle boundary, which is where it went wrong twice.
 */
val agentsModule = module {
    /*
     * THE ROOM'S VIEW MODEL TAKES ITS AGENT ID FROM THE CALLER, because a room is always about one
     * named agent. A factory with a parameter is what Koin needs for that, and it keeps the id a
     * value rather than something the view model reaches for.
     */
    viewModel { params ->
        AgentRoomViewModel(
            agentId = params.get(),
            assembler = get(),
            broker = get(),
            runtime = get(),
            runHost = get(),
            // The one write the room owns: what it shows. Everything else about an agent is the
            // creator's, and a narrow binding is how that stays true.
            agents = get<GuruAgentRepository>(),
        )
    }
    viewModel {
        AgentsViewModel(
            agentRepository = get<GuruAgentRepository>(),
            agentCatalogue = get<AgentCatalogue>(),
            getPreference = get<GetPreferenceUseCase>(),
            savePreference = get<SavePreferenceUseCase>(),
        )
    }
}
