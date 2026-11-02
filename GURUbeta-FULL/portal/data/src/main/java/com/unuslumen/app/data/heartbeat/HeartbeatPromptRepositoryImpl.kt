// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.heartbeat

import android.content.Context
import android.util.Log
import com.unuslumen.app.database.dao.HeartbeatPromptDao
import com.unuslumen.app.database.entity.HeartbeatPromptEntity
import com.unuslumen.app.domain.repository.HeartbeatPromptRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.core.annotation.Single

/**
 * The heartbeat prompt, with the bundled asset as its floor.
 *
 * TWO SOURCES, ONE ANSWER. The saved row wins when there is one. When there is no
 * row the bundled asset answers, which is why a fresh install still beats exactly as
 * it did before this existed.
 *
 * THE ASSET IS READ THROUGH [HeartbeatPrompts] RATHER THAN COPIED HERE. That object
 * already owns the asset path, the token contract and the fail-closed behaviour, and
 * a second reader would be a second place for those to drift apart.
 *
 * A BROKEN READ NEVER INJECTS NOTHING. If the database read fails the bundled text
 * answers, because a beat with a real prompt is better than a beat with none and the
 * bundled prompt is a known good one. The failure is logged rather than swallowed.
 */
@Single(binds = [HeartbeatPromptRepository::class])
class HeartbeatPromptRepositoryImpl(
    private val context: Context,
    private val dao: HeartbeatPromptDao,
) : HeartbeatPromptRepository {

    private companion object {
        const val TAG = "guru_heartbeat"
    }

    override suspend fun current(): String = withContext(Dispatchers.IO) {
        val stored = runCatching { dao.get() }.getOrNull()?.content
        if (!stored.isNullOrBlank()) return@withContext stored

        val bundled = runCatching { HeartbeatPrompts.bundledText(context) }.getOrNull()
        if (bundled.isNullOrBlank()) {
            Log.w(TAG, "No stored heartbeat prompt and no readable bundled asset")
            return@withContext ""
        }
        bundled
    }

    override suspend fun isEdited(): Boolean = withContext(Dispatchers.IO) {
        runCatching { dao.get()?.content?.isNotBlank() == true }.getOrDefault(false)
    }

    override suspend fun save(content: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            require(content.isNotBlank()) {
                "A heartbeat prompt cannot be empty: the beat would inject nothing."
            }
            dao.put(
                HeartbeatPromptEntity(
                    content = content,
                    updatedAt = System.currentTimeMillis(),
                )
            )
        }.onFailure { Log.w(TAG, "Heartbeat prompt save failed: ${it.message}") }
    }

    override suspend fun reset(): Unit = withContext(Dispatchers.IO) {
        runCatching { dao.clear() }
            .onFailure { Log.w(TAG, "Heartbeat prompt reset failed: ${it.message}") }
        // Nothing is returned because there is nothing useful to say: after a reset
        // the bundled text is in force, which is the same answer as a fresh install.
    }
}
