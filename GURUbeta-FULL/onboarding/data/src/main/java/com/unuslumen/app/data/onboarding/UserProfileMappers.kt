// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.onboarding

import com.unuslumen.app.database.entity.UserProfileEntity
import com.unuslumen.app.onboarding.domain.UserProfile

/**
 * The one mapping between the Room row and the domain profile.
 *
 * Kept in the data module so neither the entity nor the domain model has to
 * know about the other. Every field is explicit: a new column that is added
 * to the entity but forgotten here is a compile error, not a silent drop.
 */
fun UserProfileEntity.toDomain(): UserProfile = UserProfile(
    id = id,
    preferredName = preferredName,
    fullName = fullName,
    pronouns = pronouns,
    location = location,
    age = age,
    identity = identity,
    neurodivergence = neurodivergence,
    beliefs = beliefs,
    sexuality = sexuality,
    politics = politics,
    family = family,
    work = work,
    currentFocus = currentFocus,
    adjustments = adjustments,
    healthOrLegal = healthOrLegal,
    interests = interests,
    newsSources = newsSources,
    socials = socials,
    neverAskAgain = neverAskAgain,
    extraAnswersJson = extraAnswersJson,
    factsByIdJson = factsByIdJson,
    pendingBrainSyncJson = pendingBrainSyncJson,
    dealAcceptedAt = dealAcceptedAt,
    dealVersion = dealVersion,
    completed = completed,
    createdAt = createdAt,
    updatedAt = updatedAt
)

fun UserProfile.toEntity(): UserProfileEntity = UserProfileEntity(
    id = id,
    preferredName = preferredName,
    fullName = fullName,
    pronouns = pronouns,
    location = location,
    age = age,
    identity = identity,
    neurodivergence = neurodivergence,
    beliefs = beliefs,
    sexuality = sexuality,
    politics = politics,
    family = family,
    work = work,
    currentFocus = currentFocus,
    adjustments = adjustments,
    healthOrLegal = healthOrLegal,
    interests = interests,
    newsSources = newsSources,
    socials = socials,
    neverAskAgain = neverAskAgain,
    extraAnswersJson = extraAnswersJson,
    factsByIdJson = factsByIdJson,
    pendingBrainSyncJson = pendingBrainSyncJson,
    dealAcceptedAt = dealAcceptedAt,
    dealVersion = dealVersion,
    completed = completed,
    createdAt = createdAt,
    updatedAt = updatedAt
)
