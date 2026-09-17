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