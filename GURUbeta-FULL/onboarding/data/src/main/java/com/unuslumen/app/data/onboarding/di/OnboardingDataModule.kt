// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.onboarding.di

import com.unuslumen.app.data.onboarding.ProfileBrainSync
import com.unuslumen.app.data.onboarding.UserProfileRepositoryImpl
import com.unuslumen.app.onboarding.domain.UserProfileProvider
import com.unuslumen.app.onboarding.domain.UserProfileRepository
import org.koin.dsl.module

/**
 * OnboardingDataModule — the wiring for the profile row and its brain sync.
 *
 * One instance serves both the repository and the provider because they read
 * the same row through the same DAO; binding one class to two interfaces keeps
 * them guaranteed-consistent. ProfileBrainSync is a singleton so concurrent
 * sync passes serialise through one piece of bookkeeping state.
 *
 * The ProfileFactWriter binding does NOT live here. It lives in the portal
 * data module, because its implementation wraps BrainService and this module
 * must never depend on portal:data.
 */
val onboardingDataModule = module {
    single { UserProfileRepositoryImpl(get()) }
    single<UserProfileRepository> { get<UserProfileRepositoryImpl>() }
    single<UserProfileProvider> { get<UserProfileRepositoryImpl>() }
    single { ProfileBrainSync(get(), get()) }
}
