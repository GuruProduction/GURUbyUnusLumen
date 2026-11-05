// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.heartbeat

import com.unuslumen.app.domain.repository.HeartbeatBookingRepository
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
                single<HeartbeatBookingRepository> { FakeHeartbeatBookingRepository() }
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
     * The diary's view model must resolve too, and for the same reason.
     *
     * THE ROOM NOW HAS TWO VIEW MODELS AND THE SCREEN ASKS FOR BOTH. A missing definition
     * for either one throws at composition, which is a crash on open rather than an empty
     * tab. This is the second half of the same guard.
     */
    @Test
    fun `the diary view model resolves from the heartbeat module`() {
        val resolved = try {
            container().get<HeartbeatDiaryViewModel>()
            true
        } catch (e: NoDefinitionFoundException) {
            false
        }

        assertTrue(
            "HeartbeatDiaryViewModel did not resolve from heartbeatModule: the diary tab " +
                "would crash the room on open",
            resolved,
        )
    }

    /**
     * The diary's repository must be the real binding, not a default.
     *
     * A missing binding here is an empty diary rather than a crash, which is the quieter
     * and worse failure: the tab would open, show forty-eight open half hours, and refuse
     * every booking because the write went nowhere.
     */
    @Test
    fun `the diary's repository is present and can hold a booking`() = runBlocking {
        val repository = container().get<HeartbeatBookingRepository>()
        val result = repository.book(
            com.unuslumen.app.domain.model.HeartbeatBooking(
                id = "probe",
                name = "Probe",
                instruction = "Prove the binding is real.",
                repeat = com.unuslumen.app.domain.model.RepeatKind.DAILY,
                // Noon on a fixed date, so the probe is on a half hour in any time zone.
                startsAt = java.util.Calendar.getInstance().apply {
                    clear()
                    set(2026, 10, 5, 12, 0, 0)
                }.timeInMillis,
            )
        )

        assertTrue(
            "the diary repository did not accept a booking: ${result.exceptionOrNull()?.message}",
            result.isSuccess,
        )
        assertTrue("and it kept it", repository.bookings().isNotEmpty())
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
