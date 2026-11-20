// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.domain.repository

/**
 * Runs several agent-store writes as one indivisible unit.
 *
 * WHY THIS IS A TYPE RATHER THAN A DIRECT `database.withTransaction` CALL. Saving an agent now
 * writes two things: the live row and an immutable definition version. Those must either both land
 * or neither, because a version number with no document behind it is a history entry nobody can
 * open, and a document with no bumped number collides with the unique index on the next save.
 *
 * Naming the concept also keeps the guarantee testable. Production supplies the Room
 * implementation, so the real database is what enforces atomicity. A store with no transaction
 * boundary supplies the direct one, and says so, rather than pretending a transaction happened
 * when nothing could roll back.
 *
 * THE HONEST LIMIT OF THE DIRECT IMPLEMENTATION. It executes the block and does not roll anything
 * back. That is correct only for an in-memory stand-in whose writes cannot half-fail, and it is
 * named [direct] with that warning rather than left as a silent default a production path might
 * pick up by accident.
 */
interface AgentStoreTransactor {

    /** Run [block] atomically where the store supports it. */
    suspend fun <T> inTransaction(block: suspend () -> T): T

    companion object {
        /**
         * No transaction boundary. For a stand-in store only.
         *
         * NOT A PRODUCTION DEFAULT. A durable store must supply a real implementation or the
         * live row and its history can diverge on a failure between the two writes.
         */
        val direct: AgentStoreTransactor = object : AgentStoreTransactor {
            override suspend fun <T> inTransaction(block: suspend () -> T): T = block()
        }
    }
}
