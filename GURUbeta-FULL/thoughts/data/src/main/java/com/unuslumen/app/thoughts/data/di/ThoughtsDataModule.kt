// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.thoughts.data.di

import com.unuslumen.app.thoughts.data.thoughts.ThoughtCycleRepositoryImpl
import com.unuslumen.app.thoughts.domain.repository.ThoughtCycleRepository
import org.koin.dsl.module

/**
 * ThoughtsDataModule — Koin wiring for the thought-cycle engine.
 *
 * Lives in the thoughts module because the engine belongs to it. Portal's
 * aiDataModule includes this so the shared Koin graph sees the engine binding;
 * chat tool executors resolve ThoughtCycleRepository through this single
 * binding exactly as they always did.
 */
val thoughtsDataModule = module {
    single<ThoughtCycleRepository> { ThoughtCycleRepositoryImpl(get(), get(), get(), get()) }
}