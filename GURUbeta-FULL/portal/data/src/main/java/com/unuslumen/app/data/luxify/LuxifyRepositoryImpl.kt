package com.unuslumen.app.data.luxify

import com.unuslumen.app.database.dao.LuxifyDao
import com.unuslumen.app.database.entity.LuxifyEntity
import com.unuslumen.app.domain.model.CreateLuxifyRequest
import com.unuslumen.app.domain.model.LuxifySkill
import com.unuslumen.app.domain.model.LuxifySummary
import com.unuslumen.app.domain.repository.LuxifyRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.koin.core.annotation.Single
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
@Single(binds = [LuxifyRepository::class])
class LuxifyRepositoryImpl(
    private val luxifyDao: LuxifyDao,
    private val scanner: LuxifyScanner,
    private val registry: LuxifyRegistry
) : LuxifyRepository {

    override fun getAllSkillsFlow(): Flow<List<LuxifySkill>> {
        return luxifyDao.getAllLuxifyFlow().map { entities ->
            val databaseSkills = entities.map { it.toDomain() }
            val bundledSkills = registry.getAllSkills().filter { it.source == "bundled" }
            (bundledSkills + databaseSkills).distinctBy { it.name }
        }
    }

    override suspend fun getAllSkills(): List<LuxifySkill> = withContext(Dispatchers.IO) {
        registry.loadSkills()
        val bundledSkills = registry.getAllSkills().filter { it.source == "bundled" }
        val databaseSkills = luxifyDao.getAllLuxify().map { it.toDomain() }
        (bundledSkills + databaseSkills).distinctBy { it.name }
    }

    override suspend fun getActiveSkills(): List<LuxifySkill> = withContext(Dispatchers.IO) {
        registry.loadSkills()
        val bundledSkills = registry.getActiveSkills().filter { it.source == "bundled" }
        val databaseSkills = luxifyDao.getEnabledLuxify().map { it.toDomain() }
        (bundledSkills + databaseSkills).distinctBy { it.name }
    }

    override suspend fun getSkill(id: String): LuxifySkill? = withContext(Dispatchers.IO) {
        registry.loadSkills()
        registry.getSkillByName(id) ?: luxifyDao.getLuxifyById(id)?.toDomain()
    }

    override suspend fun getSkillByName(name: String): LuxifySkill? = withContext(Dispatchers.IO) {
        registry.loadSkills()
        registry.getSkillByName(name) ?: luxifyDao.getLuxifyByName(name)?.toDomain()
    }

    override suspend fun createSkill(request: CreateLuxifyRequest): LuxifySkill = withContext(Dispatchers.IO) {
        val skill = LuxifyEntity(
            id = Uuid.random().toString(),
            name = request.name,
            description = request.description,
            whenToUse = request.whenToUse,
            allowedTools = request.allowedTools.joinToString(", "),
            bodyMarkdown = request.bodyMarkdown,
            source = "user",
            enabled = true,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
        luxifyDao.insertLuxify(skill)
        val domain = skill.toDomain()
        registry.addSkill(domain)
        domain
    }

    override suspend fun updateSkill(id: String, request: CreateLuxifyRequest): LuxifySkill = withContext(Dispatchers.IO) {
        val existing = luxifyDao.getLuxifyById(id) ?: throw IllegalArgumentException("Skill not found: $id")
        val updated = existing.copy(
            name = request.name,
            description = request.description,
            whenToUse = request.whenToUse,
            allowedTools = request.allowedTools.joinToString(", "),
            bodyMarkdown = request.bodyMarkdown,
            updatedAt = System.currentTimeMillis()
        )
        luxifyDao.updateLuxify(updated)
        registry.loadSkills()
        updated.toDomain()
    }

    override suspend fun enableSkill(id: String): LuxifySkill = withContext(Dispatchers.IO) {
        luxifyDao.setEnabled(id, true)
        registry.setEnabled(id, true)
        luxifyDao.getLuxifyById(id)?.toDomain() ?: throw IllegalArgumentException("Skill not found: $id")
    }

    override suspend fun disableSkill(id: String): LuxifySkill = withContext(Dispatchers.IO) {
        luxifyDao.setEnabled(id, false)
        registry.setEnabled(id, false)
        luxifyDao.getLuxifyById(id)?.toDomain() ?: throw IllegalArgumentException("Skill not found: $id")
    }

    override suspend fun deleteSkill(id: String) = withContext(Dispatchers.IO) {
        luxifyDao.deleteLuxify(id)
        registry.removeSkill(id)
    }

    override suspend fun getSummary(): LuxifySummary = withContext(Dispatchers.IO) {
        registry.loadSkills()
        val bundled = registry.getAllSkills().count { it.source == "bundled" }
        val allDb = luxifyDao.getAllLuxify()
        val dynamic = allDb.count { it.source == "dynamic" }
        val user = allDb.count { it.source == "user" }
        val total = bundled + allDb.size
        val enabled = registry.getActiveSkills().count { it.source == "bundled" } + allDb.count { it.enabled }
        LuxifySummary(
            totalSkills = total,
            enabledSkills = enabled,
            bundledSkills = bundled,
            dynamicSkills = dynamic,
            userSkills = user
        )
    }

    override suspend fun isNameAvailable(name: String): Boolean = withContext(Dispatchers.IO) {
        registry.loadSkills()
        registry.getSkillByName(name) == null && luxifyDao.getLuxifyByName(name) == null
    }

    override suspend fun installDynamicSkill(
        name: String,
        description: String,
        whenToUse: String,
        allowedTools: List<String>,
        bodyMarkdown: String
    ): LuxifySkill = withContext(Dispatchers.IO) {
        val skill = LuxifyEntity(
            id = Uuid.random().toString(),
            name = name,
            description = description,
            whenToUse = whenToUse,
            allowedTools = allowedTools.joinToString(", "),
            bodyMarkdown = bodyMarkdown,
            source = "dynamic",
            enabled = true,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
        luxifyDao.insertLuxify(skill)
        skill.toDomain()
    }

    override suspend fun getDynamicSkills(): List<LuxifySkill> = withContext(Dispatchers.IO) {
        luxifyDao.getLuxifyBySource("dynamic").map { it.toDomain() }
    }

    override suspend fun deleteDynamicSkill(id: String) = withContext(Dispatchers.IO) {
        val skill = luxifyDao.getLuxifyById(id)
        if (skill?.source == "dynamic") {
            luxifyDao.deleteLuxify(id)
        }
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