// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.heartbeat

import android.app.AlarmManager
import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.core.qualifier.named
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * The switch guard in the scheduling authority.
 *
 * WHY THIS IS THE IMPORTANT ONE. Every path into the chain — boot, the alarm
 * receiver's rebook, and the end block of a run — arrives through
 * `HeartbeatScheduler.schedule`. That is why the switch is honoured there and not
 * only in the room: a guard in the room alone would be undone by the next alarm,
 * because a fire already booked rebooks itself and the chain carries on as though
 * nobody had touched it.
 *
 * THE ASSERTION IS THE ALARM SHADOW, NOT OUR OWN PREFERENCE. Asking a preference
 * "did you book" would prove the preference. Robolectric's AlarmManager shadow is
 * the system's view of what was actually scheduled, which is the thing that wakes a
 * phone.
 */
@RunWith(RobolectricTestRunner::class)
class HeartbeatScheduleGuardTest {

    private lateinit var context: Application

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // The scheduler injects an application scope and two preference use cases by
        // Koin. This is the smallest container they can resolve from, so the object
        // can run without standing up the whole app graph.
        //
        // startKoin, NOT koinApplication: HeartbeatScheduler is a KoinComponent and
        // resolves through the GLOBAL context. A koinApplication block builds a
        // container and never starts it, so every inject() inside the object throws
        // "KoinApplication has not been started".
        startKoin {
            androidContext(context)
            modules(
                module {
                    single(named("applicationScope")) {
                        CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
                    }
                    single {
                        mockk<com.unuslumen.app.preferences.domain.repository.PreferenceRepository>(
                            relaxed = true,
                        )
                    }
                    single { com.unuslumen.app.preferences.domain.use_case.GetPreferenceUseCase(get()) }
                    single { com.unuslumen.app.preferences.domain.use_case.SavePreferenceUseCase(get()) }
                }
            )
        }
    }

    @After
    fun tearDown() {
        unmockkAll()
        stopKoin()
    }

    private fun scheduledAlarms(): Int {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        return shadowOf(alarmManager).scheduledAlarms.size
    }

    @Test
    fun `switched off writes no booking`() {
        mockkObject(HeartbeatChainState)
        every { HeartbeatChainState.isEnabled() } returns false

        HeartbeatScheduler.schedule(context)

        assertEquals(
            "the chain is switched off, so no alarm may be booked. An alarm that fires only " +
                "to decide not to work is a battery cost with no purpose.",
            0,
            scheduledAlarms(),
        )
    }

    @Test
    fun `switched on books exactly one alarm`() {
        mockkObject(HeartbeatChainState)
        every { HeartbeatChainState.isEnabled() } returns true

        HeartbeatScheduler.schedule(context)

        assertEquals(
            "the chain is switched on, so it must book. The booking code itself is untouched.",
            1,
            scheduledAlarms(),
        )
    }

    @Test
    fun `off cancels an alarm booked while on`() {
        mockkObject(HeartbeatChainState)
        every { HeartbeatChainState.isEnabled() } returns true
        HeartbeatScheduler.schedule(context)
        assertEquals("precondition: one alarm is booked", 1, scheduledAlarms())

        every { HeartbeatChainState.isEnabled() } returns false
        HeartbeatScheduler.cancel(context)

        assertEquals(
            "off means off: an alarm booked before the switch was turned off must be removed",
            0,
            scheduledAlarms(),
        )
    }
}
