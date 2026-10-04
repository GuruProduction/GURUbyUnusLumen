// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.domain.repository

import com.unuslumen.app.domain.model.CreateLuxifyRequest
import com.unuslumen.app.domain.model.LuxifySkill
import com.unuslumen.app.domain.model.LuxifySummary
import kotlinx.coroutines.flow.Flow

interface LuxifyRepository {

    fun getAllSkillsFlow(): Flow<List<LuxifySkill>>

    suspend fun getAllSkills(): List<LuxifySkill>

    suspend fun getActiveSkills(): List<LuxifySkill>

    suspend fun getSkill(id: String): LuxifySkill?

    suspend fun getSkillByName(name: String): LuxifySkill?

    suspend fun createSkill(request: CreateLuxifyRequest): LuxifySkill

    suspend fun updateSkill(id: String, request: CreateLuxifyRequest): LuxifySkill

    suspend fun enableSkill(id: String): LuxifySkill

    suspend fun disableSkill(id: String): LuxifySkill

    suspend fun deleteSkill(id: String)

    suspend fun getSummary(): LuxifySummary

    suspend fun isNameAvailable(name: String): Boolean

    suspend fun installDynamicSkill(
        name: String,
        description: String,
        whenToUse: String,
        allowedTools: List<String>,
        bodyMarkdown: String
    ): LuxifySkill

    suspend fun getDynamicSkills(): List<LuxifySkill>

    suspend fun deleteDynamicSkill(id: String)
}