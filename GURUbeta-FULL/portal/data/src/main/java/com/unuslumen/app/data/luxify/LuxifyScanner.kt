package com.unuslumen.app.data.luxify

import android.content.Context
import com.unuslumen.app.database.dao.LuxifyDao
import com.unuslumen.app.database.entity.LuxifyEntity
import com.unuslumen.app.domain.model.LuxifySkill
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class LuxifyScanner(
    private val context: Context,
    private val luxifyDao: LuxifyDao
) {

    suspend fun scanAll(): List<LuxifySkill> = withContext(Dispatchers.IO) {
        val bundled = scanBundled()
        val userCreated = scanUserCreated()
        (bundled + userCreated).distinctBy { it.name }
    }

    suspend fun scanBundled(): List<LuxifySkill> = withContext(Dispatchers.IO) {
        val assetManager = context.assets
        val skillsDir = "skills"

        val dirs = try {
            assetManager.list(skillsDir) ?: emptyArray()
        } catch (e: Exception) {
            return@withContext emptyList()
        }

        dirs.mapNotNull { dirName ->
            // Each skill lives in its own subfolder with a SKILL.md file inside
            val skillFilePath = "$skillsDir/$dirName/SKILL.md"

            val content = try {
                assetManager.open(skillFilePath).bufferedReader().use { it.readText() }
            } catch (e: Exception) {
                return@mapNotNull null
            }

            val parsed = LuxifyParser.parse(content) ?: return@mapNotNull null

            LuxifySkill(
                id = "bundled_${parsed.name}",
                name = parsed.name,
                description = parsed.description,
                whenToUse = parsed.whenToUse,
                allowedTools = parsed.allowedTools,
                bodyMarkdown = parsed.bodyMarkdown,
                source = "bundled",
                enabled = true,
                createdAt = 0,
                updatedAt = 0
            )
        }
    }

    suspend fun scanUserCreated(): List<LuxifySkill> = withContext(Dispatchers.IO) {
        luxifyDao.getAllLuxify().map { it.toDomain() }
    }

    private fun LuxifyEntity.toDomain(): LuxifySkill {
        val tools = if (allowedTools.isBlank()) {
            emptyList()
        } else {
            allowedTools.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        }

        return LuxifySkill(
            id = id,
            name = name,
            description = description,
            whenToUse = whenToUse,
            allowedTools = tools,
            bodyMarkdown = bodyMarkdown,
            source = source,
            enabled = enabled,
            createdAt = createdAt,
            updatedAt = updatedAt
        )
    }
}