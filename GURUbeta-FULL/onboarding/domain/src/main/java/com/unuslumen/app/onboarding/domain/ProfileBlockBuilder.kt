// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.onboarding.domain

/**
 * RETIRED — do not use this file, and do not add anything to it.
 *
 * There were two ProfileBlockBuilders with the same name and the same job, one
 * here in domain and one in onboarding/data. Only the data one is wired into
 * the send path (UserProfileRepositoryImpl.profileBlock). They had already
 * drifted: this copy read typed columns only and ignored the catalogue
 * entirely, so every question without a dedicated column was silently dropped
 * from the block the model receives.
 *
 * The live implementation lives at:
 *   onboarding/data/src/main/java/com/unuslumen/app/data/onboarding/ProfileBlockBuilder.kt
 *
 * It walks OnboardingQuestionCatalogue, resolves each answer from its typed
 * column or the JSON bag, and renders it through the question's factTemplate.
 * Edit that one. This file stays as an empty shell so the retirement is visible
 * in history rather than silent.
 */
