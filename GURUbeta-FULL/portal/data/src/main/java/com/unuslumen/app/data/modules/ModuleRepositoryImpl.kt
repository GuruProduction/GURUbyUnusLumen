// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.modules

import com.unuslumen.app.database.dao.GuruModuleDao
import com.unuslumen.app.database.dao.GuruModuleRevisionDao
import com.unuslumen.app.database.dao.GuruTileOrderDao
import com.unuslumen.app.database.entity.GuruModuleEntity
import com.unuslumen.app.database.entity.GuruModuleRevisionEntity
import com.unuslumen.app.database.entity.GuruTileOrderEntity
import com.unuslumen.app.domain.model.CreateModuleRequest
import com.unuslumen.app.domain.model.GuruModule
import com.unuslumen.app.domain.model.ModuleRetention
import com.unuslumen.app.domain.model.ModuleRevision
import com.unuslumen.app.domain.model.ModuleStatus
import com.unuslumen.app.domain.model.ModuleSummary
import com.unuslumen.app.domain.model.TileOrder
import com.unuslumen.app.domain.repository.ModuleRepository
import com.unuslumen.app.domain.repository.ValidationResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.koin.core.annotation.Single
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
@Single(binds = [ModuleRepository::class])
class ModuleRepositoryImpl(
    private val moduleDao: GuruModuleDao,
    private val revisionDao: GuruModuleRevisionDao,
    private val tileOrderDao: GuruTileOrderDao
) : ModuleRepository {

    private fun GuruModuleEntity.toDomain(): GuruModule = GuruModule(
        id = id,
        name = name,
        displayName = displayName,
        description = description,
        category = category,
        compositionHtml = compositionHtml,
        compositionCss = compositionCss,
        compositionJs = compositionJs,
        dataJson = dataJson,
        iconPath = iconPath,
        revision = revision,
        status = ModuleStatus.fromRaw(status),
        sortOrder = sortOrder,
        source = source,
        createdAt = createdAt,
        updatedAt = updatedAt
    )

    private fun GuruModuleRevisionEntity.toDomain(): ModuleRevision = ModuleRevision(
        id = id,
        moduleId = moduleId,
        revision = revision,
        compositionHtml = compositionHtml,
        compositionCss = compositionCss,
        compositionJs = compositionJs,
        dataJson = dataJson,
        createdAt = createdAt
    )

    override suspend fun createModule(request: CreateModuleRequest): GuruModule = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val module = GuruModuleEntity(
            id = Uuid.random().toString(),
            name = request.name,
            displayName = request.displayName,
            description = request.description,
            category = request.category,
            compositionHtml = "",
            compositionCss = "",
            compositionJs = "",
            dataJson = "{}",
            status = ModuleStatus.DRAFT.name,
            source = "guru",
            createdAt = now,
            updatedAt = now
        )
        moduleDao.insertModule(module)
        module.toDomain()
    }

    override suspend fun getModule(id: String): GuruModule? = withContext(Dispatchers.IO) {
        moduleDao.getModuleById(id)?.toDomain()
    }

    override suspend fun getModuleByName(name: String): GuruModule? = withContext(Dispatchers.IO) {
        moduleDao.getModuleByName(name)?.toDomain()
    }

    override suspend fun getAllModules(): List<GuruModule> = withContext(Dispatchers.IO) {
        moduleDao.getAllModules().map { it.toDomain() }
    }

    override fun getAllModulesFlow(): Flow<List<GuruModule>> =
        moduleDao.getAllModulesFlow().map { modules -> modules.map { it.toDomain() } }

    override suspend fun getModulesByStatus(status: ModuleStatus): List<GuruModule> = withContext(Dispatchers.IO) {
        moduleDao.getModulesByStatus(status.name).map { it.toDomain() }
    }

    override fun getModulesByStatusFlow(status: ModuleStatus): Flow<List<GuruModule>> =
        moduleDao.getModulesByStatusFlow(status.name).map { modules -> modules.map { it.toDomain() } }

    override suspend fun renameModule(id: String, displayName: String): GuruModule = withContext(Dispatchers.IO) {
        moduleDao.renameModule(id, displayName, System.currentTimeMillis())
        moduleDao.getModuleById(id)?.toDomain() ?: throw IllegalArgumentException("Module not found: $id")
    }

    override suspend fun setIcon(id: String, iconPath: String?): GuruModule = withContext(Dispatchers.IO) {
        moduleDao.setIcon(id, iconPath, System.currentTimeMillis())
        moduleDao.getModuleById(id)?.toDomain() ?: throw IllegalArgumentException("Module not found: $id")
    }

    override suspend fun isNameAvailable(name: String): Boolean = withContext(Dispatchers.IO) {
        moduleDao.countByName(name) == 0
    }

    override suspend fun saveComposition(id: String, html: String, css: String, js: String): GuruModule =
        withContext(Dispatchers.IO) {
            val existing = moduleDao.getModuleById(id) ?: throw IllegalArgumentException("Module not found: $id")
            val now = System.currentTimeMillis()
            moduleDao.saveComposition(id, html, css, js, now)

            val updated = moduleDao.getModuleById(id) ?: throw IllegalArgumentException("Module vanished: $id")
            // Snapshot the new revision — every composition persists as history.
            revisionDao.insertRevision(
                GuruModuleRevisionEntity(
                    id = Uuid.random().toString(),
                    moduleId = id,
                    revision = updated.revision,
                    compositionHtml = html,
                    compositionCss = css,
                    compositionJs = js,
                    dataJson = existing.dataJson,
                    createdAt = now
                )
            )
            revisionDao.pruneRevisions(id, ModuleRetention.REVISIONS_PER_MODULE)
            updated.toDomain()
        }

    override suspend fun saveData(id: String, dataJson: String): GuruModule = withContext(Dispatchers.IO) {
        moduleDao.saveData(id, dataJson, System.currentTimeMillis())
        moduleDao.getModuleById(id)?.toDomain() ?: throw IllegalArgumentException("Module not found: $id")
    }

    override suspend fun getData(id: String): String? = withContext(Dispatchers.IO) {
        moduleDao.getModuleById(id)?.dataJson
    }

    override suspend fun getRevisions(id: String): List<ModuleRevision> = withContext(Dispatchers.IO) {
        revisionDao.getRevisionsForModule(id).map { it.toDomain() }
    }

    override suspend fun getRevision(id: String, revision: Int): ModuleRevision? = withContext(Dispatchers.IO) {
        revisionDao.getRevision(id, revision)?.toDomain()
    }

    override suspend fun rollbackToRevision(id: String, revision: Int): GuruModule {
        val snapshot = getRevision(id, revision)
            ?: throw IllegalArgumentException("No revision $revision for module $id")
        // Restoring saves as a NEW revision — history never rewrites, even itself.
        return saveComposition(id, snapshot.compositionHtml, snapshot.compositionCss, snapshot.compositionJs)
    }

    override suspend fun registerModule(id: String): GuruModule = withContext(Dispatchers.IO) {
        moduleDao.setStatus(id, ModuleStatus.ACTIVE.name, System.currentTimeMillis())
        val module = moduleDao.getModuleById(id)?.toDomain() ?: throw IllegalArgumentException("Module not found: $id")
        // Doctrine: the room the numen just finished leads the lobby.
        joinAtTop(id)
        module
    }

    override suspend fun retireModule(id: String): GuruModule = withContext(Dispatchers.IO) {
        moduleDao.setStatus(id, ModuleStatus.RETIRED.name, System.currentTimeMillis())
        // Retired rooms leave the order list too — door gone, tiles intact elsewhere.
        tileOrderDao.deleteOrder("module:$id")
        moduleDao.getModuleById(id)?.toDomain() ?: throw IllegalArgumentException("Module not found: $id")
    }

    override suspend fun deleteModule(id: String) = withContext(Dispatchers.IO) {
        revisionDao.deleteRevisionsForModule(id)
        tileOrderDao.deleteOrder("module:$id")
        moduleDao.deleteModule(id)
    }

    override suspend fun getSummary(): ModuleSummary = withContext(Dispatchers.IO) {
        val all = moduleDao.getAllModules()
        val mapped = all.map { ModuleStatus.fromRaw(it.status) }
        ModuleSummary(
            totalModules = all.size,
            activeModules = mapped.count { it == ModuleStatus.ACTIVE },
            draftModules = mapped.count { it == ModuleStatus.DRAFT },
            retiredModules = mapped.count { it == ModuleStatus.RETIRED }
        )
    }

    override suspend fun getTileOrders(): List<TileOrder> = withContext(Dispatchers.IO) {
        tileOrderDao.getAllOrders().map { TileOrder(id = it.id, sortOrder = it.sortOrder) }
    }

    override fun getTileOrdersFlow(): Flow<List<TileOrder>> =
        tileOrderDao.getAllOrdersFlow().map { orders -> orders.map { TileOrder(id = it.id, sortOrder = it.sortOrder) } }

    override suspend fun saveTileOrders(orders: List<TileOrder>) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        tileOrderDao.saveAll(
            orders.mapIndexed { index, order ->
                GuruTileOrderEntity(id = order.id, sortOrder = index, updatedAt = now)
            }
        )
        // The module rows carry their own sortOrder mirrors so sorting
        // works from either store without a join.
        for (order in orders) {
            if (order.id.startsWith("module:")) {
                val moduleId = order.id.removePrefix("module:")
                moduleDao.setSortOrder(moduleId, order.sortOrder, now)
            }
        }
    }

    override suspend fun joinAtTop(id: String) = withContext(Dispatchers.IO) {
        val orders = tileOrderDao.getAllOrders().toMutableList()
        val entry = GuruTileOrderEntity(id = "module:$id", sortOrder = 0, updatedAt = System.currentTimeMillis())
        // New room first, everything else slides down one.
        val rewritten = listOf(entry) + orders.map { it.copy(sortOrder = it.sortOrder + 1) }
        tileOrderDao.saveAll(rewritten)
    }
}