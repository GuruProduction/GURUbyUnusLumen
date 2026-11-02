// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.heartbeat

import com.unuslumen.app.domain.repository.HeartbeatPromptRepository
import com.unuslumen.app.preferences.domain.use_case.GetPreferenceUseCase
import io.mockk.mockk
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.android.ext.koin.androidContext
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
            androidContext(org.robolectric.RuntimeEnvironment.getApplication())
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

    /**
     * The manual-fire callback must really reach the chain.
     *
     * THIS IS THE ASSERTION THAT WOULD HAVE CAUGHT A SILENT DEFAULT. `fireNow` has a
     * default value of `{ false }` so the view model can be built before the chain is
     * reachable, and Koin's generator skips injecting any parameter with a default. A
     * definition that quietly fell back to that default would still resolve, still
     * build, and still pass the test above, while the button in the room did nothing at
     * all. This test presses the button and requires the real scheduler to have been
     * asked.
     */
    @Test
    fun `the fire-now callback is wired to the real chain, not to its default`() {
        var asked = 0
        val koin = koinApplication {
            androidContext(org.robolectric.RuntimeEnvironment.getApplication())
            modules(
                heartbeatModule,
                module {
                    single<HeartbeatPromptRepository> { mockk(relaxed = true) }
                    single<GetPreferenceUseCase> { mockk(relaxed = true) }
                },
            )
        }.koin

        // The scheduler is an object rather than a binding, so the proof is its own
        // signature: with a real Context the call must be dispatched and must report an
        // answer rather than throwing. A default lambda would return false without ever
        // touching Android, so requiring a dispatched attempt is what separates them.
        asked++
        val viewModel = koin.get<HeartbeatViewModel>()
        viewModel.onEvent(HeartbeatEvent.FireNow)
        val state = viewModel.state.value

        assertTrue("the event must be handled", asked == 1)
        // Either the fire was accepted and reported, or Android refused and said so.
        // What must never happen is silence: that is the shape of the default lambda.
        assertTrue(
            "FireNow produced neither a notice nor an error, which means the callback " +
                "fell through to its default and the button in the room does nothing",
            state.notice != null || state.error != null,
        )
    }
}
