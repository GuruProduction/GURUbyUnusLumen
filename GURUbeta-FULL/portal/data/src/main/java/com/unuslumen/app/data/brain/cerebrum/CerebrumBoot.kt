// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.brain.cerebrum

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * CerebrumBoot — the single wiring point that boots (and later stops) the
 * Cerebrum brain through the app process. GuruApplication.onCreate fires
 * [boot]; Android process death cleans it naturally (in-process state), and
 * an upgrade/restart of the app re-fires boot with a restore-from-vault.
 *
 * Boot flow (exactly per plan Phase F):
 *  1. first run: generate a random high-entropy vault passphrase, seal it in
 *     the keystore envelope (CerebrumHost does the actual encryption), never
 *     display it, never log it.
 *  2. otherwise unseal.
 *  3. JNI boot: the shared library restores full brain state from the
 *     encrypted vault and starts both foreground no-listener persistence.
 *     (No TCP; the only transport, a 0-prefixed filesDir socket, belongs to
 *     the same app's uid. There is no egress.)
 *  4. Room-to-cerebrum one-shot migration runs on a first boot with an
 *     existing room memory table (all four memory tables read).
 *  5. saveNow persists the fresh vault.
 */
object CerebrumBoot {
    private const val TAG = "guru_cerebrum"

    /** Random 320-bit hex passphrase generated at first run. Never displays. */
    private fun randomPassphrase(): String {
        val bytes = ByteArray(40)
        java.security.SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    // Whether this app process already boot-stamped a migration; the idempotent
    // migration guard means one boot cycle's failure retries on the following boot.
    @Volatile
    var migrationSummary: String? = null
        private set

    private val bootScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun boot(context: Context) {
        bootScope.launch {
            withContext(Dispatchers.IO) {
                val result = CerebrumHost.boot(context, randomPassphrase())
                if (result.ok) {
                    Log.i(TAG, "cerebrum brain ready (boot stamp ${result.stamp})")
                } else {
                    Log.e(TAG, "cerebrum boot failed: ${result.error ?: "unknown"}")
                }
            }
        }
    }

    /**
     * The one-shot migration path after a first successful brain boot:
     * requires Koin context to hand over the memory daos. The caller (the
     * GuruApplication boot routine) supplies them; everything else stays
     * within this object.
     */
    fun bootAndMigrate(
        context: Context,
        memoryFactDao: com.unuslumen.app.database.dao.MemoryFactDao,
        memoryEdgeDao: com.unuslumen.app.database.dao.MemoryEdgeDao,
        memoryEventDao: com.unuslumen.app.database.dao.MemoryEventDao,
        memoryCrossReferenceDao: com.unuslumen.app.database.dao.MemoryCrossReferenceDao,
    ) {
        bootScope.launch {
            val boot = CerebrumHost.boot(context, randomPassphrase())
            if (!boot.ok) {
                Log.e(TAG, "cerebrum boot failed, migration skipped: ${boot.error}")
                return@launch
            }
            // One brain, one vault: real migrate chain, idempotent by id determinism.
            val migrationDone = try {
                val migration = CerebrumMigration(
                    memoryFactDao,
                    memoryEdgeDao,
                    memoryEventDao,
                    memoryCrossReferenceDao,
                )
                migration.runMigration()
            } catch (thrown: Exception) {
                Log.e(TAG, "cerebrum migration threw: ${thrown.message}")
                null
            }
            migrationSummary = migrationDone?.let { m ->
                if (m.ok) {
                    "ok facts=${m.factsMigrated} edges=${m.edgesMigrated} xrefs=${m.crossRefsMigrated}" +
                        " cleared facts/edges/${m.roomFactsCleared}/${m.roomEdgesCleared}"
                } else "failed: ${m.error ?: "unknown"}"
            }
            Log.i(TAG, "cerebrum migrationSummary: $migrationSummary")
        }
    }

    fun shutdown() {
        CerebrumHost.stop()
    }
}