// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.agents

import androidx.room.withTransaction
import com.unuslumen.app.database.guruDatabase
import com.unuslumen.app.domain.repository.AgentStoreTransactor
import org.koin.core.annotation.Single

/**
 * The production transaction boundary for the agent store.
 *
 * WHAT IT PROTECTS. An agent save writes the live row and an immutable definition version. A
 * failure between the two would leave either a version number with no document or a document with
 * no advanced number, and the second one collides with the unique index on the next save. Both
 * writes therefore go through one Room transaction and either both land or neither does.
 *
 * WHY IT IS A SEPARATE CLASS RATHER THAN THE REPOSITORY REACHING FOR THE DATABASE. The repository
 * then takes an interface, so a test can hand it a store that genuinely cannot roll back and say so
 * instead of pretending. That keeps the atomicity claim testable without letting a production path
 * silently pick up a no-op.
 */
@Single(binds = [AgentStoreTransactor::class])
class RoomAgentStoreTransactor(
    private val database: guruDatabase,
) : AgentStoreTransactor {
    override suspend fun <T> inTransaction(block: suspend () -> T): T =
        database.withTransaction { block() }
}
