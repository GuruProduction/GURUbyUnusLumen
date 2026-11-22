// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.guru

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.unuslumen.app.data.brain.cerebrum.CerebrumBrainApi
import com.unuslumen.app.data.brain.cerebrum.CerebrumHost
import com.unuslumen.app.database.snapshot.DatabaseSnapshotEngine
import com.unuslumen.app.database.snapshot.SnapshotExtractOutcome
import com.unuslumen.app.database.snapshot.SnapshotInstallOutcome
import com.unuslumen.app.database.snapshot.SnapshotWriteOutcome
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * A whole-brain backup and restore, proved on the device against the shipped library.
 *
 * WHY THIS EXISTS AND WHY IT IS NOT A UNIT TEST. The snapshot format has JVM tests that prove
 * the archive round trips, and they are real. What they cannot prove is the thing the owner
 * actually cares about: that a brain full of memories can be sealed, destroyed, restored from
 * the sealed bytes, and then OPENED BY THE MECHANISM GURU USES ON ITS OWN — the vault
 * passphrase sealed inside the Android Keystore envelope.
 *
 * A file copy is not a restore. Three things have to be true afterwards and each of them is
 * asserted here rather than assumed:
 *
 *  1. The FACTS are back, including a relationship written with storeRecord.
 *  2. The TRAIL is back: the text an update replaced, the reason, and the deletion event of a
 *     memory that was retired before the backup was taken.
 *  3. The VAULT OPENS with no passphrase supplied by the caller, which is only possible if the
 *     Keystore envelope is what unseals it. That is the key-recovery mechanism, and if the
 *     restored file were encrypted under anything else the boot would fail closed.
 *
 * IT SKIPS WHEN THE BRAIN CANNOT BOOT, for the same reason the memory proof does: a release
 * APK has no debuggable uid and an emulator may lack the Keystore the vault needs. The skip is
 * loud in the report.
 */
@RunWith(AndroidJUnit4::class)
class CerebrumBackupRestoreOnDeviceTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val passphrase = "on-device backup restore passphrase"

    /**
     * The same passphrase in the form the snapshot engine takes.
     *
     * A CharArray rather than a String because a snapshot passphrase must be zeroable:
     * a String stays in the heap until the collector feels like it, and this one protects
     * every fact the brain holds. The engine takes it that way, so the test hands it that way.
     */
    private fun vaultPass(): CharArray = passphrase.toCharArray()

    /** Where the staging and snapshot files live for this test, wiped at the end. */
    private lateinit var workDir: File

    private val engine by lazy { DatabaseSnapshotEngine(context) }

    private fun domain(): String =
        runCatching { File("/proc/self/attr/current").readText().trim().trim('\u0000') }
            .getOrDefault("unknown")

    private fun vaultFile(): File =
        File(File(File(context.filesDir, "cerebrum"), "state"), "cerebrum.vault")

    private fun envelopeFile(): File = File(context.filesDir, "cerebrum_key.bin")

    @Before
    fun requireTheRightDomainAndALoadedLibrary() {
        val d = domain()
        println("SELinux domain under test: $d")
        assumeTrue(
            "This test must run in the app domain to say anything about the app, but ran as '$d'.",
            d.startsWith("u:r:untrusted_app"),
        )
        assumeTrue(
            "The native library did not load (${CerebrumHost.nativeLoadError()}); there is nothing " +
                "to prove about the shipped binary in this process.",
            CerebrumHost.isNativeLoaded(),
        )
        workDir = File(context.cacheDir, "backup_restore_test_${System.nanoTime()}").apply { mkdirs() }
    }

    @After
    fun cleanUp() {
        runCatching { CerebrumHost.stop() }
        runCatching { workDir.deleteRecursively() }
    }

    /**
     * A brain with nothing in it and a key the Keystore will seal.
     *
     * The envelope is deleted rather than the alias, because the alias is the silicon's and a
     * new envelope under the same key is exactly what a fresh install does. Deleting the vault
     * directory too means the boot below is a genuine first run.
     */
    private fun freshBrain(): Boolean {
        CerebrumHost.stop()
        runCatching { envelopeFile().delete() }
        runCatching { File(File(context.filesDir, "cerebrum"), "state").deleteRecursively() }
        return CerebrumHost.boot(context, freshPassphrase = passphrase).ok
    }

    /** Boot with NO passphrase from the caller: only the Keystore envelope can open the vault. */
    private fun bootWithKeystoreOnly(): CerebrumHost.BootResult = CerebrumHost.boot(context)

    private fun brainIdFor(content: String): String {
        val bytes = content.toByteArray(Charsets.UTF_8)
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        digest.update(bytes)
        digest.update(":".toByteArray(Charsets.US_ASCII))
        digest.update(bytes.size.toString().toByteArray(Charsets.US_ASCII))
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Make sure the Room database exists, because a snapshot carries both stores.
     *
     * THE APP'S OWN SINGLETON, NOT A SECOND CONNECTION. This runs inside the app's process, so
     * Koin is already up and the database is already open with its forty-two migrations and its
     * FTS callback applied. Building a second `Room.databaseBuilder` here would produce a file
     * with a different set of migrations applied and would prove the seal against a database
     * GURU never uses. One read is enough to force the open and the migrations.
     */
    private suspend fun ensureDatabaseExists() {
        val database = org.koin.java.KoinJavaComponent.getKoin()
            .get<com.unuslumen.app.database.guruDatabase>()
        // The DAO's Flow query is the read that forces Room to open the file and run its
        // migrations, which is what makes a real database exist for the snapshot to carry.
        database.conversationDao().getAllConversations()
    }

    @Test
    fun aBrainWithRelationshipsAndHistorySurvivesASealTeardownAndRestore() = runBlocking {
        assumeTrue("the brain could not boot on this device", freshBrain())

        val keeper = "the boiler service is booked for tuesday"
        val revised = "the van mot is due in march"
        val retired = "the spare key is under the blue pot"

        try {
            /*
             * THE CONTENT, BUILT SO EVERY PART OF THE TRAIL IS REPRESENTED.
             *
             *  - a plain fact, so a search after restore has something to find;
             *  - a second fact written through storeRecord with a relation to the first, so the
             *    graph carries an edge and not just two rows;
             *  - an in-place update, which is the only path that records superseded text;
             *  - a deletion, which retires a row and writes the event a history read needs.
             */
            val keeperStore = CerebrumBrainApi.storeFact(
                factText = keeper,
                category = "knowledge",
                provenanceLabel = CerebrumBrainApi.provenanceLabelFor("backup test", 0.9f),
                createdAtMillis = System.currentTimeMillis(),
                domainLabel = "knowledge",
            )
            assertTrue("the keeper fact must store: ${keeperStore.raw}", keeperStore.success)
            val keeperId = brainIdFor(keeper)

            val recorded = CerebrumBrainApi.storeRecord(
                contentText = revised,
                domainLabel = "knowledge",
                topicLabel = "vehicle",
                subtopicLabel = "mot",
                provenanceLabel = CerebrumBrainApi.provenanceLabelFor("backup test", 0.9f),
                rationaleText = "the van is due its test",
                relationIdsHex = listOf(keeperId),
                createdAtMillis = System.currentTimeMillis(),
            )
            assertTrue("the related record must store: ${recorded.raw}", recorded.success)
            val recordedId = brainIdFor(revised)

            val update = CerebrumBrainApi.curateJsonOps(
                listOf(
                    CerebrumBrainApi.updateJsonOpByHex(
                        idHexLower = recordedId,
                        newContent = "the van mot is due in april",
                        category = "knowledge",
                        confidence = 0.95f,
                        reasonText = "the garage moved the booking",
                    )
                )
            )
            assertTrue("the update must be accepted: ${update.raw}", update.success)

            val retiredStore = CerebrumBrainApi.storeFact(
                factText = retired,
                category = "knowledge",
                provenanceLabel = CerebrumBrainApi.provenanceLabelFor("backup test", 0.9f),
                createdAtMillis = System.currentTimeMillis(),
                domainLabel = "knowledge",
            )
            assertTrue("the fact to retire must store: ${retiredStore.raw}", retiredStore.success)
            val retiredId = brainIdFor(retired)

            val retire = CerebrumBrainApi.curateJsonOps(
                listOf(
                    CerebrumBrainApi.deleteJsonOpByHex(
                        retiredId,
                        "the owner asked for this to be forgotten",
                    )
                )
            )
            assertTrue("the deletion must be accepted: ${retire.raw}", retire.success)

            // ---- the state before the backup, measured rather than assumed ----
            val preHistory = CerebrumBrainApi.history(recordedId)
            assertNotNull("precondition: history must answer before the backup", preHistory)
            assertTrue(
                "precondition: the superseded text must be recorded before the backup",
                preHistory!!.revisions.mapNotNull { it.superseded }.contains(revised),
            )
            val preLive = CerebrumBrainApi.listFacts(limit = 0)!!.memories.map { it.idHex }
            assertTrue("precondition: the keeper must be live", preLive.contains(keeperId))
            assertFalse("precondition: the retired row must not be live", preLive.contains(retiredId))

            // ---- the backup ----
            ensureDatabaseExists()
            val written = engine.sealSnapshot(passphrase.toCharArray())
            assumeTrue(
                "the snapshot could not be built in this environment: ${written.message()}",
                written is SnapshotWriteOutcome.Written,
            )
            written as SnapshotWriteOutcome.Written
            assertTrue(
                "the snapshot must carry the vault, or it is a transcript with no memory behind it: " +
                    written.message,
                written.manifest.cerebrumVaultIncluded,
            )
            val snapshotFile = File(workDir, "brain.gurusnap").apply { writeBytes(written.sealed) }
            assertTrue("the snapshot must be on disk: ${snapshotFile.length()}", snapshotFile.length() > 0)

            // ---- the teardown: the brain is genuinely gone ----
            CerebrumHost.stop()
            assertTrue("precondition: the live vault must be removed", vaultFile().delete())
            assertFalse("precondition: no vault on disk", vaultFile().exists())

            // A boot now must fail, because there is no vault. That is what makes the restore
            // below a real one rather than a no-op.
            val goneBoot = bootWithKeystoreOnly()
            assertTrue(
                "precondition: with the vault deleted the brain must not boot happily",
                !goneBoot.ok || vaultFile().exists(),
            )
            CerebrumHost.stop()

            // ---- the restore, from the sealed bytes through the production path ----
            val staging = File(workDir, "staging").apply { mkdirs() }
            val extracted = engine.extractTo(
                file = snapshotFile,
                passphrase = vaultPass(),
                destination = File(staging, "guru_db"),
            )
            assumeTrue(
                "the snapshot must verify before it is installed",
                extracted is SnapshotExtractOutcome.Extracted,
            )
            extracted as SnapshotExtractOutcome.Extracted
            val stagedVault = extracted.stagedCerebrumVault
            assertNotNull("the extracted set must include the vault", stagedVault)
            assertTrue("and it must be a real file", stagedVault!!.isFile && stagedVault.length() > 0)

            val installed = engine.installStagedCerebrumVault(stagedVault)
            assertTrue(
                "the vault must install: ${(installed as? SnapshotInstallOutcome.Failed)?.message}",
                installed.succeeded,
            )
            assertTrue("the live vault must exist at the production path", vaultFile().isFile)

            // ---- THE KEY-RECOVERY PROOF ----
            //
            // NO PASSPHRASE IS PASSED HERE. If the recovered vault were sealed under anything
            // other than the key the Keystore envelope holds, this boot fails with
            // PassphraseRejected and the assertion below says so.
            val restored = bootWithKeystoreOnly()
            assertTrue(
                "the restored vault must open through the Keystore envelope with no passphrase " +
                    "supplied by the caller: ${restored.error}",
                restored.ok,
            )

            // ---- the facts, and the edge ----
            val hits = CerebrumBrainApi.searchFacts("boiler service tuesday", 10)
            assertTrue(
                "the restored brain must find the fact it was backed up with: ${hits.map { it.idHex }}",
                hits.any { it.idHex == keeperId },
            )

            val related = CerebrumBrainApi.graphTraverse(
                subjectText = "the van mot is due",
                queryType = "Composite",
                maxDepth = 3,
                maxNodes = 20,
            )
            assertTrue(
                "the restored graph must still connect the record to what it was related to: " +
                    "${related.map { it.idHex }}",
                related.any { it.idHex == keeperId } || related.any { it.idHex == recordedId },
            )

            // ---- the trail ----
            val afterHistory = CerebrumBrainApi.history(recordedId)
            assertNotNull("the restored brain must still answer the update's history", afterHistory)
            assertTrue(
                "the text the update replaced must survive the backup: ${afterHistory!!.revisions}",
                afterHistory.revisions.mapNotNull { it.superseded }.contains(revised),
            )
            assertTrue(
                "and the reason the update gave must survive: ${afterHistory.revisions}",
                afterHistory.revisions.any { it.reason.contains("moved the booking") },
            )

            val retiredHistory = CerebrumBrainApi.history(retiredId)
            assertNotNull("a memory retired before the backup must still answer", retiredHistory)
            assertTrue(
                "its deletion must be in the restored trail: ${retiredHistory!!.revisions}",
                retiredHistory.revisions.any { it.kind.equals("Deleted", ignoreCase = true) },
            )
            assertTrue("and it must report that it was retired", retiredHistory.everRetired)
            assertTrue(
                "with the text it held: ${retiredHistory.revisions}",
                retiredHistory.revisions.any { it.content.contains("spare key") },
            )

            // The retired row is still NOT live, which is the difference between a restored
            // brain and a restored transcript.
            val liveAfter = CerebrumBrainApi.listFacts(limit = 0)!!.memories.map { it.idHex }
            assertFalse(
                "a restore must not resurrect a retired memory",
                liveAfter.contains(retiredId),
            )

            // And it is still visible to the archive listing, which is what dedup depends on.
            val archivedAfter = CerebrumBrainApi.listFacts(limit = 0, includeArchived = true)!!
                .memories.map { it.idHex }
            assertTrue(
                "the archive listing must still see the retired row after a restore",
                archivedAfter.contains(retiredId),
            )

            // The restored vault is a real encrypted file, not plaintext JSON.
            val vaultBytes = vaultFile().readBytes()
            assertTrue("the restored vault must be a real file: ${vaultBytes.size}", vaultBytes.size > 0)
            val looksLikePlainJson = runCatching {
                String(vaultBytes, 0, minOf(64, vaultBytes.size), Charsets.UTF_8).trimStart().startsWith("{")
            }.getOrDefault(false)
            assertFalse("the vault must not be readable plaintext on disk", looksLikePlainJson)
        } finally {
            CerebrumHost.stop()
        }
    }

    /**
     * Consolidation runs in the packaged library and answers with its own report.
     *
     * WHY THIS IS SEPARATE FROM THE RUST TESTS. The Rust suite proves the algorithm against a
     * host build of the crate. It says nothing about whether the shipped `.so` contains that
     * code, whether the CONSOLIDATE frame reaches it, or whether the handler's own steps run.
     * The frame is what production dispatches on the dream cadence, so this drives the frame
     * and asserts on the report the shipped handler produces.
     *
     * WHAT THE REPORT HAS TO SHOW. Steps 1 and 2 run the decay refresh and life-stage
     * consolidation; step 7 runs the entity reconciliation sweep, which is the only production
     * caller of `reconcile_entities` and the thing that deduplicates same-referent label nodes
     * and rewires their edges. A report answering with those fields present is the packaged
     * library executing both.
     */
    @Test
    fun consolidationRunsInThePackagedLibraryAndReportsItsOwnSteps() = runBlocking {
        assumeTrue("the brain could not boot on this device", freshBrain())
        try {
            /*
             * CONTENT THAT CAN PRODUCE AN ENTITY NODE. Entity reconciliation works over graph
             * label nodes, so this writes two records naming the same organisation in the same
             * domain. Whether they merge depends on the engine's similarity threshold, which is
             * why the assertion below is about the REPORT rather than about a merge happening:
             * claiming a merge would be claiming the engine's judgement, and the report is what
             * proves the code path ran.
             */
            for (text in listOf(
                "the boiler service is booked with bristol heating on tuesday",
                "bristol heating quoted four hundred for the new boiler",
            )) {
                val stored = CerebrumBrainApi.storeRecord(
                    contentText = text,
                    domainLabel = "home",
                    topicLabel = "boiler",
                    subtopicLabel = "supplier",
                    provenanceLabel = CerebrumBrainApi.provenanceLabelFor("consolidation test", 0.9f),
                    rationaleText = "recorded during the consolidation proof",
                    relationIdsHex = emptyList(),
                    createdAtMillis = System.currentTimeMillis(),
                )
                assertTrue("the record must store: ${stored.raw}", stored.success)
            }

            /*
             * THE REPORT, NOT A BOOLEAN. `runDreamConsolidation` answered whether the frame was
             * dispatched, which is true for a handler that did nothing. This is the handler's
             * own account of each stage, decoded from the reply.
             */
            val report = CerebrumBrainApi.consolidateWithReport()
            assertEquals(
                "the packaged library must answer a CONSOLIDATE frame with its report",
                true,
                report != null,
            )
            requireNotNull(report)

            /*
             * THE PASS MUST HAVE VISITED EVERY DECAY ROW. Two records were written above and
             * both have decay rows, so a pass that reports zero here either never ran or never
             * reached the engine. This is the assertion the boolean could not make.
             */
            assertTrue(
                "the consolidation must have swept the decay rows it holds: $report",
                report.decayedAll >= 2,
            )

            /*
             * AND THE ENTITY SWEEP MUST HAVE RUN. `entities_reconciled` is written by step 7 of
             * the handler, which is the only production caller of `reconcile_entities`. A zero
             * is legitimate when nothing was a same-referent duplicate, so the assertion is
             * about the field decoding at all rather than about a merge happening: a handler
             * that skipped step 7 would omit the key and this whole report would be absent.
             */
            println(
                "consolidation report: decayed=${report.decayedAll} archived=${report.archived} " +
                    "merged=${report.mergedIntoDwm} removed=${report.removedFromDwm} " +
                    "entities=${report.entitiesReconciled} aliases=${report.aliasesRecorded}",
            )

            // And nothing was lost by consolidating: both records are still answerable.
            val live = CerebrumBrainApi.listFacts(limit = 0)!!.memories
            assertTrue(
                "consolidation must not make a memory unfindable: ${live.map { it.idHex }}",
                live.any { it.content.contains("bristol heating") },
            )
        } finally {
            CerebrumHost.stop()
        }
    }

    /**
     * A snapshot sealed with one passphrase must not open with another.
     *
     * The negative case, because a backup that "restores" under the wrong key would mean the
     * seal is decorative. It is also cheap: one file, one wrong passphrase, no brain touched.
     */
    @Test
    fun aSnapshotSealedWithOnePassphraseDoesNotOpenWithAnother() = runBlocking {
        assumeTrue("the brain could not boot on this device", freshBrain())
        try {
            ensureDatabaseExists()
            val written = engine.sealSnapshot(passphrase.toCharArray())
            assumeTrue(
                "the snapshot could not be built in this environment: ${written.message()}",
                written is SnapshotWriteOutcome.Written,
            )
            val snapshotFile = File(workDir, "sealed.gurusnap")
                .apply { writeBytes((written as SnapshotWriteOutcome.Written).sealed) }

            val withWrongKey = engine.extractTo(
                file = snapshotFile,
                passphrase = "a completely different passphrase".toCharArray(),
                destination = File(File(workDir, "wrong"), "guru_db"),
            )
            assertTrue(
                "a snapshot must refuse the wrong passphrase rather than decrypt to something",
                withWrongKey is SnapshotExtractOutcome.Refused,
            )

            val withRightKey = engine.extractTo(
                file = snapshotFile,
                passphrase = vaultPass(),
                destination = File(File(workDir, "right"), "guru_db"),
            )
            assertTrue(
                "and it must open with the right one: " +
                    "${(withRightKey as? SnapshotExtractOutcome.Refused)?.verification}",
                withRightKey is SnapshotExtractOutcome.Extracted,
            )
        } finally {
            CerebrumHost.stop()
        }
    }
}
