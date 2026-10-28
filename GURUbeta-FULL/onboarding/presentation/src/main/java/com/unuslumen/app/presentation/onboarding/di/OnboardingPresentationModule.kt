// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.presentation.onboarding.di

import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Module

/**
 * OnboardingPresentationModule — Koin scan for the onboarding screens.
 *
 * KSP only registers @KoinViewModel classes inside the package this scan
 * names, in the same compilation unit. The onboarding ViewModel lives in its
 * own module now, so without this scan resolving it throws "No definition
 * found" the first time the screen inflates.
 */
@Module
@ComponentScan("com.unuslumen.app.presentation.onboarding")
class OnboardingPresentationModule
