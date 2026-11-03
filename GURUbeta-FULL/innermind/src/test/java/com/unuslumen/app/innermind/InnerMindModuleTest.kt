// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.innermind

import com.unuslumen.app.database.dao.ConversationDao
import com.unuslumen.app.database.dao.FabricDao
import com.unuslumen.app.database.dao.GuruAgentDao
import com.unuslumen.app.database.dao.GuruNoteToSelfDao
import com.unuslumen.app.database.dao.GuruThoughtCycleDao
import com.unuslumen.app.database.dao.LuxifyDao
import com.unuslumen.app.database.dao.MemoryCrossReferenceDao
import com.unuslumen.app.database.dao.MemoryEdgeDao
import com.unuslumen.app.database.dao.NoteDao
import com.unuslumen.app.database.dao.ProjectDao
import com.unuslumen.app.database.dao.TaskDao
import com.unuslumen.app.database.dao.UserProfileDao
import com.unuslumen.app.domain.memory.MemoryRepository
import io.mockk.mockk
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.android.ext.koin.androidContext
import org.koin.core.error.NoDefinitionFoundException
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * The inner mind's view model must resolve from its own module.
 *
 * WHY THIS EXISTS. Two rooms before this one crashed on open with "No definition
 * found for type". Both had @Module and @KoinViewModel, and KSP still generated an
 * empty module across the Gradle boundary, putting the view model into a
 * DefaultModule this build does not assemble. A green compile says nothing about
 * this, because the definition is resolved at runtime.
 *
 * So this starts a container holding only innermindModule plus mocks for the
 * stores, and asks for the type the way the device does. It fails on exactly the
 * fault the other two rooms showed.
 */
@RunWith(RobolectricTestRunner::class)
class InnerMindModuleTest {

    @Test
    fun `inner mind view model resolves from the innermind module alone`() {
        val koin = koinApplication {
            // The module binds the room's dismissal marks from an Android context,
            // the same way the app's own startKoin supplies one. Without it the
            // container fails on the Context rather than on the view model, which
            // would make this test pass for the wrong reason.
            androidContext(RuntimeEnvironment.getApplication())
            modules(
                innermindModule,
                module {
                    single<MemoryRepository> { mockk(relaxed = true) }
                    single<ConversationDao> { mockk(relaxed = true) }
                    single<TaskDao> { mockk(relaxed = true) }
                    single<NoteDao> { mockk(relaxed = true) }
                    single<ProjectDao> { mockk(relaxed = true) }
                    single<GuruAgentDao> { mockk(relaxed = true) }
                    single<LuxifyDao> { mockk(relaxed = true) }
                    single<GuruThoughtCycleDao> { mockk(relaxed = true) }
                    single<GuruNoteToSelfDao> { mockk(relaxed = true) }
                    single<FabricDao> { mockk(relaxed = true) }
                    single<UserProfileDao> { mockk(relaxed = true) }
                    single<MemoryEdgeDao> { mockk(relaxed = true) }
                    single<MemoryCrossReferenceDao> { mockk(relaxed = true) }
                },
            )
        }.koin

        val resolved = try {
            koin.get<InnerMindViewModel>()
            true
        } catch (e: NoDefinitionFoundException) {
            false
        }

        assertTrue(
            "InnerMindViewModel did not resolve from innermindModule, which is exactly the " +
                "crash the agents and ottio rooms showed on open: No definition found for type",
            resolved,
        )
    }

    @Test
    fun `the mind source resolves from the innermind module alone`() {
        val koin = koinApplication {
            androidContext(RuntimeEnvironment.getApplication())
            modules(
                innermindModule,
                module {
                    single<MemoryRepository> { mockk(relaxed = true) }
                    single<ConversationDao> { mockk(relaxed = true) }
                    single<TaskDao> { mockk(relaxed = true) }
                    single<NoteDao> { mockk(relaxed = true) }
                    single<ProjectDao> { mockk(relaxed = true) }
                    single<GuruAgentDao> { mockk(relaxed = true) }
                    single<LuxifyDao> { mockk(relaxed = true) }
                    single<GuruThoughtCycleDao> { mockk(relaxed = true) }
                    single<GuruNoteToSelfDao> { mockk(relaxed = true) }
                    single<FabricDao> { mockk(relaxed = true) }
                    single<UserProfileDao> { mockk(relaxed = true) }
                    single<MemoryEdgeDao> { mockk(relaxed = true) }
                    single<MemoryCrossReferenceDao> { mockk(relaxed = true) }
                },
            )
        }.koin

        val resolved = try {
            koin.get<MindSource>()
            true
        } catch (e: NoDefinitionFoundException) {
            false
        }

        assertTrue(
            "MindSource did not resolve from innermindModule",
            resolved,
        )
    }
}
