// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.heartbeat

import android.util.Log
import com.unuslumen.app.database.dao.HeartbeatPromptDao
import com.unuslumen.app.database.entity.HeartbeatPromptEntity
import com.unuslumen.app.domain.repository.HeartbeatPromptRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.core.annotation.Single

/**
 * The heartbeat prompt, and the only place it comes from.
 *
 * ONE SOURCE: THE DATABASE ROW THE OWNER WROTE.
 *
 * There used to be a second source. The prompt shipped inside the APK as
 * `assets/heartbeat/HEARTBEAT_PROMPT.md`, and this class fell back to it whenever
 * nothing had been saved. That fallback is gone. What GURU says to itself every
 * thirty minutes is its owner's decision, and shipping a default meant the app
 * carried an opinion on that decision, which is not the app's opinion to hold.
 *
 * SO A FRESH INSTALL HAS NO PROMPT. [current] answers an empty string, the beat ends
 * before it reaches the model, and the room says why and asks for one. That is the
 * honest state and it is visible. The alternative was words nobody chose being
 * injected forty-eight times a day.
 *
 * [reset] deletes the row rather than writing anything back, because "cleared" and
 * "set to these words" are different states and only one of them is true.
 */
@Single(binds = [HeartbeatPromptRepository::class])
class HeartbeatPromptRepositoryImpl(
    private val dao: HeartbeatPromptDao,
) : HeartbeatPromptRepository {

    private companion object {
        const val TAG = "guru_heartbeat"
    }

    override suspend fun current(): String = withContext(Dispatchers.IO) {
        runCatching { dao.get()?.content }
            .onFailure { Log.w(TAG, "Heartbeat prompt read failed: ${it.message}") }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: ""
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
        // Nothing is written back: there is no shipped text to restore. After a reset
        // the honest answer is "no prompt", which is exactly what a fresh install has.
    }
}
