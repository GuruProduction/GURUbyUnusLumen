// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.onboarding

import com.unuslumen.app.database.dao.UserProfileDao
import com.unuslumen.app.onboarding.domain.UserProfile
import com.unuslumen.app.onboarding.domain.UserProfileProvider
import com.unuslumen.app.onboarding.domain.UserProfileRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.core.annotation.Single

/**
 * UserProfileRepositoryImpl — the profile row, and the read seam the send path
 * uses, in one class.
 *
 * Why one class for both: they read the same row and must never disagree. A
 * separate provider would be a second read path that could drift or cache
 * stale data. This way the block the model sees is always built from the row
 * as it stands at the moment of the send.
 *
 * Every method is failure-tolerant by design. Onboarding must never be able to
 * crash the chat path, so storage errors degrade to a blank profile rather
 * than throwing into a send.
 */
@Single
class UserProfileRepositoryImpl(
    private val userProfileDao: UserProfileDao
) : UserProfileRepository, UserProfileProvider {

    // =====================================================================
    // UserProfileRepository
    // =====================================================================

    override suspend fun getProfile(): UserProfile = withContext(Dispatchers.IO) {
        runCatching {
            userProfileDao.getProfile(UserProfile.SINGLETON_ID)?.toDomain()
        }.getOrNull() ?: UserProfile()
    }

    override suspend fun saveProfile(profile: UserProfile) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val existing = runCatching {
            userProfileDao.getProfile(UserProfile.SINGLETON_ID)
        }.getOrNull()
        val createdAt = existing?.createdAt?.takeIf { it > 0 } ?: now
        userProfileDao.upsert(
            profile.copy(
                id = UserProfile.SINGLETON_ID,
                createdAt = createdAt,
                updatedAt = now
            ).toEntity()
        )
    }

    override suspend fun isOnboardingComplete(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            userProfileDao.getProfile(UserProfile.SINGLETON_ID)
                ?.let { it.completed && it.dealAcceptedAt > 0 }
                ?: false
        }.getOrDefault(false)
    }

    override suspend fun acceptDeal(version: Int) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val existing = runCatching {
            userProfileDao.getProfile(UserProfile.SINGLETON_ID)
        }.getOrNull()
        val base = existing?.toDomain() ?: UserProfile()
        userProfileDao.upsert(
            base.copy(
                id = UserProfile.SINGLETON_ID,
                dealAcceptedAt = now,
                dealVersion = version,
                createdAt = existing?.createdAt?.takeIf { it > 0 } ?: now,
                updatedAt = now
            ).toEntity()
        )
    }

    override suspend fun markComplete() = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val existing = runCatching {
            userProfileDao.getProfile(UserProfile.SINGLETON_ID)
        }.getOrNull()
        val base = existing?.toDomain() ?: UserProfile()
        userProfileDao.upsert(
            base.copy(
                id = UserProfile.SINGLETON_ID,
                completed = true,
                createdAt = existing?.createdAt?.takeIf { it > 0 } ?: now,
                updatedAt = now
            ).toEntity()
        )
    }

    override suspend fun reset() = withContext(Dispatchers.IO) {
        userProfileDao.deleteById(UserProfile.SINGLETON_ID)
    }

    // =====================================================================
    // UserProfileProvider — the send-path read seam
    // =====================================================================

    override suspend fun profileBlock(): String = withContext(Dispatchers.Default) {
        runCatching {
            val row = userProfileDao.getProfile(UserProfile.SINGLETON_ID)
                ?.toDomain()
                ?: return@runCatching ""
            // An unaccepted deal means onboarding never finished; the block
            // stays empty so a half-filled profile never reaches a model.
            if (row.dealAcceptedAt <= 0) return@runCatching ""
            ProfileBlockBuilder.build(row)
        }.getOrDefault("")
    }

    override suspend fun preferredName(): String = withContext(Dispatchers.IO) {
        runCatching {
            userProfileDao.getProfile(UserProfile.SINGLETON_ID)?.preferredName ?: ""
        }.getOrDefault("")
    }

    override suspend fun isComplete(): Boolean = isOnboardingComplete()
}
