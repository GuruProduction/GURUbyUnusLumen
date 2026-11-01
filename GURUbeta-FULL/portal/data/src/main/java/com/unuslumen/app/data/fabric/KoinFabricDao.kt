// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.fabric

import android.content.Context
import com.unuslumen.app.database.dao.FabricDao
import org.koin.core.context.GlobalContext

/**
 * Resolving the fabric's DAO when a caller only has a Context. Issue #187.
 *
 * WHY THIS IS NOT JUST `GlobalContext.get().get()`. The JVM honesty test constructs
 * every domain executor from a bare Context with no Koin container started, so a
 * direct `GlobalContext.get()` throws there and the test reports the executor as
 * unbuildable. That test is right to insist on constructibility: a registration
 * naming an executor nothing can build fails only when somebody calls the tool.
 *
 * THE FALLBACK IS HONEST, NOT A STUB. When Koin is absent or has no binding, the
 * returned DAO answers every call by throwing with a sentence that says what is
 * missing. That means a real app with a broken binding fails loudly and specifically
 * rather than silently returning an empty house, and a test that only needs the
 * executor to construct can do so and assert on the tools that do not touch storage.
 */
object KoinFabricDao {

    fun resolve(context: Context): FabricDao {
        // A started container with the binding is the real path, which is every
        // production call: Koin starts in GuruApplication.onCreate before any tool.
        val fromKoin = runCatching {
            GlobalContext.get().get<FabricDao>()
        }.getOrNull()
        if (fromKoin != null) return fromKoin

        return MissingFabricDao
    }
}

/**
 * The DAO used when no database binding exists.
 *
 * Every method refuses with the same sentence, which names the real fault: the
 * fabric's store was never bound. A silent empty list here would read as a house
 * with no devices, and a person would go looking at their television instead of at
 * the app.
 */
private object MissingFabricDao : FabricDao {

    private fun missing(): Nothing = throw IllegalStateException(
        "The smart home fabric has no database binding, so no device could be stored or read. " +
            "This is a fault in the app's wiring rather than a missing permission.",
    )

    override suspend fun getAllDevices() = missing()
    override fun getAllDevicesFlow() = missing()
    override suspend fun getDevice(id: String) = missing()
    override suspend fun getDevicesByTransport(transport: String) = missing()
    override suspend fun getDevicesInRoom(room: String) = missing()
    override suspend fun getRooms() = missing()
    override suspend fun upsertDevice(device: com.unuslumen.app.database.entity.FabricDeviceEntity) = missing()
    override suspend fun upsertDevices(devices: List<com.unuslumen.app.database.entity.FabricDeviceEntity>) = missing()
    override suspend fun placeDevice(id: String, name: String, room: String?, now: Long) = missing()
    override suspend fun updateState(id: String, stateJson: String, available: Boolean, now: Long) = missing()
    override suspend fun deleteDevice(id: String) = missing()
    override suspend fun deleteAllDevices() = missing()
    override suspend fun countDevices() = missing()
    override suspend fun getAllScenes() = missing()
    override fun getAllScenesFlow() = missing()
    override suspend fun getSceneByName(name: String) = missing()
    override suspend fun upsertScene(scene: com.unuslumen.app.database.entity.FabricSceneEntity) = missing()
    override suspend fun deleteScene(name: String) = missing()
}
