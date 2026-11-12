// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.di

import com.unuslumen.app.database.snapshot.DatabaseSnapshotEngine
import com.unuslumen.app.domain.di.SettingsDomainModule
import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Module
import org.koin.dsl.module
import org.koin.ksp.generated.module

@Module
@ComponentScan("com.unuslumen.app.data")
internal class SettingsDataModule

val settingsDataModule = module {
    includes(SettingsDataModule().module, SettingsDomainModule().module)

    // The snapshot engine is bound explicitly rather than by the component scan above,
    // because it lives in :core:database rather than this module, so the scan cannot reach
    // it. A factory and not a single: it holds nothing but the Context it was given, and a
    // singleton would keep that reference for the life of the process to save nothing.
    factory { DatabaseSnapshotEngine(get()) }
}