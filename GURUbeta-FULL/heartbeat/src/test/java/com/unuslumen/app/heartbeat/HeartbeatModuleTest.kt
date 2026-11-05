// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.heartbeat

import com.unuslumen.app.domain.repository.HeartbeatPromptRepository
import com.unuslumen.app.preferences.domain.use_case.GetPreferenceUseCase
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.android.ext.koin.androidContext
import org.koin.core.error.NoDefinitionFoundException
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner

/**
 * The room's view model must resolve from its own module, and the switch must reach
 * the real chain.
 *
 * WRITTEN BEFORE THE ROOM EVER RAN, because the same mistake was made twice already:
 * Ottio and Agents both shipped with empty generated modules and both crashed on open
 * with "No definition found for type", and a green compile said nothing either time.
 *
 * This starts a real Koin container holding only heartbeatModule and asks for the
 * view model the way the device does. If the definition is missing, this fails here
 * instead of on his phone.
 */
@RunWith(RobolectricTestRunner::class)
class HeartbeatModuleTest {

    private fun container() = koinApplication {
        androidContext(org.robolectric.RuntimeEnvironment.getApplication())
        modules(
            heartbeatModule,
            module {
                single<HeartbeatPromptRepository> { mockk(relaxed = true) }
                single<GetPreferenceUseCase> { mockk(relaxed = true) }
            },
        )
    }.koin

    @Test
    fun `heartbeat view model resolves from the heartbeat module alone`() {
        val resolved = try {
            container().get<HeartbeatViewModel>()
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
     * reachable, and a definition that quietly fell back to it would still resolve,
     * still build, and still pass the test above, while the button in the room did
     * nothing at all. This presses the button and requires an answer rather than
     * silence.
     */
    @Test
    fun `the fire-now callback is wired to the real chain, not to its default`() {
        val viewModel = container().get<HeartbeatViewModel>()
        viewModel.onEvent(HeartbeatEvent.FireNow)
        val state = viewModel.state.value

        // Either the fire was accepted and reported, or Android refused and said so, or
        // there is no prompt and the room asks for one first. What must never happen is
        // silence: that is the shape of the default lambda.
        assertTrue(
            "FireNow produced neither a notice nor an error, which means the callback " +
                "fell through to its default and the button in the room does nothing",
            state.notice != null || state.error != null,
        )
    }
}
