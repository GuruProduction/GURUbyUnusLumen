// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.agents

import com.unuslumen.app.domain.repository.AgentCatalogue
import com.unuslumen.app.domain.repository.GuruAgentRepository
import com.unuslumen.app.preferences.domain.use_case.GetPreferenceUseCase
import com.unuslumen.app.preferences.domain.use_case.SavePreferenceUseCase
import io.mockk.mockk
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.error.NoDefinitionFoundException
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner

/**
 * The agents room's view model must resolve from its own module.
 *
 * WHY THIS EXISTS. The room crashed on open with "No definition found for type". It had
 * both @Module and @KoinViewModel, and KSP still generated an empty module: the view model
 * was tagged into a DefaultModule that this build disables. A green compile said nothing,
 * because the definition is resolved at runtime.
 *
 * So this starts a container holding only agentsModule and asks for the type, the same way
 * the device does. It fails on the exact fault the room showed.
 *
 * THE FOUR COLLABORATORS ARE MOCKS. They are real repositories and preference use cases
 * from other modules; the subject here is the wiring, not their behaviour, and none of them
 * is called during construction.
 */
@RunWith(RobolectricTestRunner::class)
class AgentsModuleTest {

    @Test
    fun `agents view model resolves from the agents module alone`() {
        val koin = koinApplication {
            modules(
                agentsModule,
                module {
                    single<GuruAgentRepository> { mockk(relaxed = true) }
                    single<AgentCatalogue> { mockk(relaxed = true) }
                    single<GetPreferenceUseCase> { mockk(relaxed = true) }
                    single<SavePreferenceUseCase> { mockk(relaxed = true) }
                },
            )
        }.koin

        val resolved = try {
            koin.get<AgentsViewModel>()
            true
        } catch (e: NoDefinitionFoundException) {
            false
        }

        assertTrue(
            "AgentsViewModel did not resolve from agentsModule, which is exactly the crash the " +
                "room showed on open: No definition found for type",
            resolved,
        )
    }
}
