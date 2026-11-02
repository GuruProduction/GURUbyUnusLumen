// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.heartbeat

import com.unuslumen.app.domain.repository.HeartbeatPromptRepository
import com.unuslumen.app.preferences.domain.use_case.GetPreferenceUseCase
import io.mockk.mockk
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.error.NoDefinitionFoundException
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner

/**
 * The room's view model must resolve from its own module.
 *
 * WRITTEN BEFORE THE ROOM EVER RAN, because the same mistake was made twice already:
 * Ottio and Agents both shipped with empty generated modules and both crashed on open
 * with "No definition found for type", and a green compile said nothing either time.
 *
 * This starts a real Koin container holding only heartbeatModule and asks for the
 * view model the way the device does. If the definition is missing, this fails here
 * instead of on his phone.
 *
 * The two collaborators are mocks: they are real app repositories from other modules,
 * and none of them is called during construction.
 */
@RunWith(RobolectricTestRunner::class)
class HeartbeatModuleTest {

    @Test
    fun `heartbeat view model resolves from the heartbeat module alone`() {
        val koin = koinApplication {
            modules(
                heartbeatModule,
                module {
                    single<HeartbeatPromptRepository> { mockk(relaxed = true) }
                    single<GetPreferenceUseCase> { mockk(relaxed = true) }
                },
            )
        }.koin

        val resolved = try {
            koin.get<HeartbeatViewModel>()
            true
        } catch (e: NoDefinitionFoundException) {
            false
        }

        assertTrue(
            "HeartbeatViewModel did not resolve from heartbeatModule: the room would crash on open",
            resolved,
        )
    }
}
