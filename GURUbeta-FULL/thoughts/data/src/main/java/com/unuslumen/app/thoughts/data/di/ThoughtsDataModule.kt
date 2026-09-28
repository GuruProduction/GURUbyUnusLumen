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