// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.domain.repository

import com.unuslumen.app.domain.model.AiMessage
import com.unuslumen.app.domain.model.PortalResult
import kotlinx.coroutines.flow.Flow

interface AiRepository {

    suspend fun sendPrompt(prompt: String): PortalResult<String>

    /**
     * System+user send for framework lanes (hive extraction / threads): the
     * chat line never routes instructions through the master prompt, and the
     * gateway splices nothing without real user words, so a structured lane
     * needing model-shaped answers sends its own system content explicitly.
     * Default-args keep the single-arg call shape compiling everywhere.
     */
    suspend fun sendSystemPrompt(
        systemPrompt: String,
        userPrompt: String
    ): PortalResult<String> = sendPrompt("$systemPrompt\n\n$userPrompt")

    fun sendMessage(messages: List<AiMessage>, conversationId: String? = null): Flow<AiMessage>

    /**
     * Rebuild the model wiring from current device settings. Called after a
     * BYO save (provider, endpoint, key, model name) so the change applies to
     * the very next send instead of requiring an app restart.
     */
    fun reinitialise()
}