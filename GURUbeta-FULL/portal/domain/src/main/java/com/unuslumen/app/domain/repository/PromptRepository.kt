package com.unuslumen.app.domain.repository

interface PromptRepository {
    suspend fun getSystemPrompt(): String
}