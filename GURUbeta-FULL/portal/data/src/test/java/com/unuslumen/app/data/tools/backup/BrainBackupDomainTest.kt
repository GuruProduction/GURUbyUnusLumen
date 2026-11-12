// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.tools.backup

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.unuslumen.app.data.tools.registry.ToolDefinition
import com.unuslumen.app.data.tools.registry.ToolRegistry
import com.unuslumen.app.data.tools.registry.ToolSetRegistration
import com.unuslumen.app.data.tools.registry.permission.TeamThreeToolDomains
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The brain backup tools, checked where it matters: are they reachable at all.
 *
 * WHY THIS TEST EXISTS AND WHY IT IS NOT A FORMALITY. This project has shipped tools that
 * were written, compiled, tested and never registered, so they did not exist in the running
 * app. A request to control a television reached nothing for exactly that reason, and the
 * lesson recorded in the domain registry is that a domain is not installed until it appears
 * in the aggregator.
 *
 * So this does not test that the class compiles. It registers through the same aggregator the
 * app's boot reads, with the same definition-to-executor resolution, and then asks the registry
 * whether `brainSnapshotCreate` is a name it knows. If the domain were dropped from that
 * aggregator, every other test in this repo would still pass and this one would fail.
 *
 * The container holds only this domain's bindings. Registering every domain would need every
 * other module's Koin graph, which is the app's job, not this test's: what matters here is that
 * this domain's declarations reach a real registry with a real executor behind them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BrainBackupDomainTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `the brain backup tools are registered under the names a caller uses`() {
        val registry = ToolRegistry()
        val koin = startKoin {
            androidContext(context)
            // Only this domain's bindings, because the registry is what is under test here,
            // not every other domain's Koin graph. A container holding only these bindings
            // still proves the thing that matters: the aggregator names this domain, its
            // definitions reach the registry, and its executor resolves by the class the
            // definitions name. The app's own boot then supplies the rest.
            modules(BrainBackupDomainModule.bindings())
        }.koin

        try {
            registerThisDomain(registry, koin)

            val expected = listOf(
                BrainBackupDomainDefinitions.SNAPSHOT_CREATE,
                BrainBackupDomainDefinitions.SNAPSHOT_VERIFY,
                BrainBackupDomainDefinitions.SNAPSHOT_RESTORE,
                BrainBackupDomainDefinitions.SNAPSHOT_LIST,
            )
            val present = registry.getAllToolNames()

            for (name in expected) {
                assertTrue(
                    "$name is not in the registry, which means it does not exist in the running app",
                    present.contains(name),
                )
                assertNotNull("$name registered with no executor", registry.getExecutor(name))
            }
        } finally {
            stopKoin()
        }
    }

    @Test
    fun `the domain appears in the aggregator that installs it`() {
        val ids = TeamThreeToolDomains.domains.map { it.id }
        assertTrue(
            "brain_backup is not in TeamThreeToolDomains, so its tools are never registered",
            ids.contains(BrainBackupDomainModule.id),
        )

        // The aggregator's registration list is what the app's boot iterates, so the domain
        // being present by id but absent from that list would still mean nothing registers.
        val registered = TeamThreeToolDomains.registrations()
        assertTrue(
            "the domain's definitions are not in the aggregator's registration list",
            registered.any { it === BrainBackupDomainDefinitions },
        )
    }

    /**
     * Register one domain into a registry, the way the app's boot registers all of them.
     *
     * Written here rather than calling [ToolRegistration.registerAll], which iterates every
     * domain in the generated list and therefore needs every other domain's bindings in the
     * container. That is correct for the app and useless for an isolated test.
     */
    private fun registerThisDomain(registry: ToolRegistry, koin: org.koin.core.Koin) {
        for (reg in BrainBackupDomainModule.registrations) {
            // The class is named as a type argument because the registry resolves by class and
            // an inferred star projection cannot be passed to get().
            val executor = koin.get<com.unuslumen.app.data.tools.registry.ToolExecutor>(reg.executorClass())
            val extractorClass = reg.extractorClass()
            val extractor = if (extractorClass != null) {
                koin.get<com.unuslumen.app.data.tools.registry.ToolResultExtractor>(extractorClass)
            } else {
                null
            }
            for (definition in reg.definitions) {
                registry.register(definition, executor, extractor)
            }
        }
    }

    @Test
    fun `every declared tool has a description that warns a model what it cannot do`() {
        val thin = BrainBackupDomainDefinitions.definitions
            .filter { it.description.length < 120 }
            .map(ToolDefinition::name)
        assertTrue("descriptions too thin to be useful: $thin", thin.isEmpty())
    }

    @Test
    fun `every tool name is distinct from every other tool in the app`() {
        // A duplicate name is not a crash: the registry's map silently keeps whichever
        // registered last, so the tool would behave differently depending on load order.
        val own = BrainBackupDomainDefinitions.definitions.map(ToolDefinition::name)
        val others = TeamThreeToolDomains.registrations()
            .filter { it !== BrainBackupDomainDefinitions }
            .flatMap { it.definitions.map(ToolDefinition::name) }

        val collisions = own.filter { it in others }
        assertTrue("these names are claimed twice: $collisions", collisions.isEmpty())
    }

    @Test
    fun `every tool declares the parameters it actually reads`() {
        // A tool whose schema omits an argument it requires is unreachable in practice: the
        // dispatcher validates against the schema, so the executor never sees the value.
        val byName = BrainBackupDomainDefinitions.definitions.associateBy(ToolDefinition::name)

        val create = byName.getValue(BrainBackupDomainDefinitions.SNAPSHOT_CREATE)
        assertTrue(
            "snapshot create must require folderPath, or it cannot know where to write",
            create.parameters.any { it.name == "folderPath" && it.required },
        )
        assertTrue(
            "snapshot create must require a passphrase, or the file could never be reopened",
            create.parameters.any { it.name == "passphrase" && it.required },
        )

        val verify = byName.getValue(BrainBackupDomainDefinitions.SNAPSHOT_VERIFY)
        assertTrue(verify.parameters.any { it.name == "filePath" && it.required })
        assertTrue(verify.parameters.any { it.name == "passphrase" && it.required })

        val restore = byName.getValue(BrainBackupDomainDefinitions.SNAPSHOT_RESTORE)
        assertTrue(restore.parameters.any { it.name == "filePath" && it.required })
        assertTrue(restore.parameters.any { it.name == "passphrase" && it.required })

        val list = byName.getValue(BrainBackupDomainDefinitions.SNAPSHOT_LIST)
        assertTrue(list.parameters.any { it.name == "folderPath" && it.required })
    }

    @Test
    fun `no backup tool declares a permission because none of them needs one`() {
        // Guarding a tool that needs no grant would send a person to a Settings screen that
        // has nothing to switch on, which is worse than saying nothing. Reading the app's own
        // database and writing into a folder the owner just picked need no permission.
        val guarded = BrainBackupDomainDefinitions.definitions
            .filter { it.permissions.isNotEmpty() }
            .map { it.name to it.permissions }
        assertEquals("no backup tool may declare a permission", emptyList<Pair<String, List<String>>>(), guarded)
    }

    @Test
    fun `the domain binds exactly the executor its definitions name`() {
        // A domain that binds one executor and declares another puts a tool's description and
        // its behaviour in two places, which is how issues 40 and 44 shipped a tool whose claim
        // did not match its code.
        val declared = BrainBackupDomainDefinitions.executorClass().java
        val registrations: List<ToolSetRegistration> = BrainBackupDomainModule.registrations

        assertTrue(
            "the module must carry the definitions object",
            registrations.any { it === BrainBackupDomainDefinitions },
        )
        assertEquals(BrainBackupDomainExecutor::class.java, declared)
    }
}
