// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.heartbeat

import android.content.Context
import com.unuslumen.app.domain.repository.HeartbeatBookingRepository
import com.unuslumen.app.domain.repository.HeartbeatPromptRepository
import com.unuslumen.app.preferences.domain.use_case.GetPreferenceUseCase
import kotlinx.coroutines.runBlocking
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
 *
 * THE THREE CHAIN CALLBACKS, AND WHY THEY ARE CALLBACKS. The scheduler is an Android
 * AlarmManager authority living in another Gradle module. A view model holding one
 * directly would tie the room to the chain's implementation, and the room has to open
 * even when the chain cannot be reached. So each door is a narrow lambda, resolved
 * lazily at the moment it is used.
 */
val heartbeatModule = module {
    viewModel {
        HeartbeatViewModel(
            promptRepository = get<HeartbeatPromptRepository>(),
            getPreference = get<GetPreferenceUseCase>(),
            // READ THE SWITCH. Same source the scheduler reads, so the toggle and the
            // booking can never disagree about whether the chain is on.
            isEnabledNow = {
                runCatching {
                    com.unuslumen.app.data.heartbeat.HeartbeatChainState.isEnabled()
                }.getOrDefault(false)
            },
            // SET THE SWITCH, THEN BOOK OR CANCEL. The write is the switch; the
            // scheduling is the consequence. Turning it on asks for a booking and
            // turning it off cancels one, which is what "off" has to mean if a switch
            // is going to mean anything.
            applyEnabled = { enabled ->
                runCatching {
                    runBlocking {
                        com.unuslumen.app.data.heartbeat.HeartbeatChainState.setEnabled(enabled)
                    }
                    val context = get<Context>()
                    if (enabled) {
                        com.unuslumen.app.data.heartbeat.HeartbeatScheduler.schedule(context)
                    } else {
                        com.unuslumen.app.data.heartbeat.HeartbeatScheduler.cancel(context)
                    }
                }.onFailure {
                    android.util.Log.w("guru_heartbeat", "Switch apply failed: ${it.message}")
                }
            },
            /*
             * FIRE ONE NOW, IN THE OPEN.
             *
             * The prompt goes to the portal's pending-prompt holder and the portal is
             * opened. That holder is what the ask bar already uses, and the portal
             * already watches it and sends through its own real chat path, so the beat
             * streams into the canvas exactly like a typed sentence.
             *
             * THE ROOM KNOWS NOTHING ABOUT THE PORTAL'S GRADLE MODULE. It hands over a
             * string in a shared domain holder; who is listening is not its business.
             *
             * WHY NOT HeartbeatScheduler.fireNow ANY MORE. That started the background
             * service, which really did send the beat, but into a chat the portal had
             * already loaded. The reply reached the database and never the screen, so
             * from the owner's side the button did nothing. The scheduled chain still
             * runs that way and still should; this is the button, and the button is meant
             * to be watched.
             */
            fireNow = { injectedPrompt ->
                runCatching {
                    com.unuslumen.app.domain.PendingPortalPrompt.set(injectedPrompt)
                    // True means "the prompt is waiting". The room's screen then navigates
                    // to the portal, because navigation belongs to the composable that
                    // already holds the NavHostController, not to a DI module.
                    true
                }.getOrDefault(false)
            },
        )
    }

    /*
     * THE DIARY, DECLARED THE SAME WAY AND FOR THE SAME REASON.
     *
     * The diary is a second view model in the same room, so it gets a second explicit
     * definition. The generated-module fault that hit Ottio and Agents does not care how
     * many view models a module holds: it emits nothing for either of them.
     *
     * THE CLOCK IS NOT OVERRIDDEN HERE. The default is System.currentTimeMillis, and a
     * test constructs the view model directly with its own clock rather than reaching
     * into the container for a fake one. A production binding with a fake clock in it is
     * the kind of thing that ships.
     */
    viewModel {
        HeartbeatDiaryViewModel(
            bookingRepository = get<HeartbeatBookingRepository>(),
            promptRepository = get<HeartbeatPromptRepository>(),
        )
    }
}
