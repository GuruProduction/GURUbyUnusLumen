// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.memory

import android.content.Context
import com.unuslumen.app.domain.memory.EmbeddingService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.core.annotation.Factory

/**
 * LocalEmbeddingService — STUB (TFLite model removed)
 *
 * The EmbeddingGemma TFLite model was making the app unusable on startup
 * by compiling 169+ subgraphs and blocking the main thread for 5+ seconds.
 * This stub returns failure for all embedding operations so the rest of
 * the brain system degrades gracefully (FTS5 + signature search still work).
 */
@Factory
class LocalEmbeddingService(
    private val context: Context
) : EmbeddingService {

    override suspend fun isAvailable(): Boolean = false

    override suspend fun generateEmbedding(text: String): Result<List<Float>> {
        return Result.failure(IllegalStateException("Embedding model disabled"))
    }

    suspend fun warmupAsync() {
        // No-op — model removed
    }
}