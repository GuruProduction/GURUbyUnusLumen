package com.unuslumen.app.domain.memory

interface EmbeddingService {
    suspend fun generateEmbedding(text: String): Result<List<Float>>
    suspend fun isAvailable(): Boolean
}
