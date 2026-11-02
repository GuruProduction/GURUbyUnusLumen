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
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/**
 * The innermind room's bindings, declared by hand.
 *
 * WHY NOT @Module PLUS @KoinViewModel. That is exactly how the agents room
 * crashed on open: both annotations were present and KSP still produced an empty
 * module across the Gradle boundary, because the generated definitions landed in
 * a default module this build does not assemble. Declaring the definitions here
 * removes the dependency on how the generator groups things between modules,
 * which is where it went wrong twice.
 *
 * [MindSource] is built from the DAOs rather than from eight domain repositories
 * because the map needs the rows themselves. See the note on MindSource.
 */
val innermindModule = module {

    single {
        MindSource(
            memoryRepository = get<MemoryRepository>(),
            conversationDao = get<ConversationDao>(),
            taskDao = get<TaskDao>(),
            noteDao = get<NoteDao>(),
            projectDao = get<ProjectDao>(),
            agentDao = get<GuruAgentDao>(),
            skillDao = get<LuxifyDao>(),
            thoughtDao = get<GuruThoughtCycleDao>(),
            noteToSelfDao = get<GuruNoteToSelfDao>(),
            fabricDao = get<FabricDao>(),
            userProfileDao = get<UserProfileDao>(),
            edgeDao = get<MemoryEdgeDao>(),
            crossReferenceDao = get<MemoryCrossReferenceDao>(),
        )
    }

    viewModel {
        InnerMindViewModel(source = get<MindSource>())
    }
}
