// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.ottio

import com.unuslumen.app.domain.repository.AgentCatalogue
import com.unuslumen.app.domain.repository.GuruAgentRepository
import io.mockk.mockk
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.error.NoDefinitionFoundException
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner

/**
 * The room's view model must actually be resolvable from its own module.
 *
 * WHY THIS TEST EXISTS, AND WHY IT IS WRITTEN THIS WAY. Both rooms crashed on open with
 * "No definition found for type". The first attempt used @Module and @KoinViewModel and
 * produced an EMPTY generated module: KSP tagged the view model with
 * `moduleTagId="DefaultModule"`, default-module generation is switched off in this build,
 * and the generated module came out as `module { }`. The compile was green, because a
 * missing definition is a runtime fact rather than a compile-time one.
 *
 * So this does what Koin does on the device: it starts a real container holding only this
 * module and asks for the type. A definition that exists and a definition that resolves are
 * different things, and only the second one stops the crash.
 *
 * THE COLLABORATORS ARE MOCKED. MarketViewModel needs AgentCatalogue and
 * GuruAgentRepository, which are real repositories backed by Room and Tor. The subject here
 * is that the MODULE knows the view model, not that the repositories work, so the two are
 * relaxed mocks. Nothing on them is called during construction.
 */
// Robolectric because building the view model needs Android's main looper, which a plain
// JVM test does not have. The subject is still the container, not the device.
@RunWith(RobolectricTestRunner::class)
class OttioModuleTest {

    @Test
    fun `market view model resolves from the ottio module alone`() {
        val koin = koinApplication {
            modules(
                ottioModule,
                module {
                    single<GuruAgentRepository> { mockk(relaxed = true) }
                    single<AgentCatalogue> { mockk(relaxed = true) }
                },
            )
        }.koin

        val resolved = try {
            koin.get<MarketViewModel>()
            true
        } catch (e: NoDefinitionFoundException) {
            false
        }

        assertTrue(
            "MarketViewModel did not resolve from ottioModule, which is exactly the crash the " +
                "room showed on open: No definition found for type",
            resolved,
        )
    }
}
