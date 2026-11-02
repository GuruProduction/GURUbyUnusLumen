// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.ottio

import com.unuslumen.app.domain.repository.AgentCatalogue
import com.unuslumen.app.domain.repository.GuruAgentRepository
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/**
 * Ottio's bindings, declared by hand.
 *
 * WHY THIS IS NOT @Module PLUS @KoinViewModel. It was, and it produced an empty
 * module. Koin's KSP pass generated MarketViewModel into a file tagged
 * `moduleTagId="DefaultModule"`, and default-module generation is switched off in this
 * build, so the generated OttioModule came out as `module { }` with nothing in it. The
 * room resolved its screen and then crashed on open with "No definition found for type"
 * because the view model behind it had never been registered anywhere.
 *
 * The annotation is fine in a module whose generated output lands in the same module as
 * the consumer. Across a Gradle boundary it did not, so this declares the definition the
 * way the DSL is meant to be used and removes the guesswork: what is written here is
 * exactly what Koin holds.
 *
 * THE STORES COME FROM ELSEWHERE. AgentCatalogue and GuruAgentRepository are the app's
 * shared stores in portal:domain, not this room's private state: a shop reads the stock
 * room, it does not keep its own copy of it.
 */
val ottioModule = module {
    viewModel {
        MarketViewModel(
            agentRepository = get<GuruAgentRepository>(),
            agentCatalogue = get<AgentCatalogue>(),
        )
    }
}
