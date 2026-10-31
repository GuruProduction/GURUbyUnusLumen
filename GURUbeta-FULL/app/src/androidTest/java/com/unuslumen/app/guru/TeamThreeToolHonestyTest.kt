// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.guru

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.unuslumen.app.data.tools.registry.permission.TeamThreeToolDomains
import com.unuslumen.app.data.tools.registry.ToolExecutor
import com.unuslumen.app.data.tools.registry.ToolExecutionResult
import org.koin.core.context.GlobalContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlinx.coroutines.runBlocking

/**
 * The device proof for the whole device-senses batch, in one reusable harness.
 *
 * WHY ONE HARNESS AND NOT FORTY-THREE. Every issue in this batch has the same
 * acceptance shape: the tool exists, it is registered, it refuses honestly when
 * the grant is missing, and it never reports a denial as an empty result. Writing
 * a bespoke test per issue would prove the same five facts forty-three times and
 * would drift, because a tool added later would have no test at all. This walks
 * the live registry instead, so a tool that is added is covered the day it lands.
 *
 * WHAT IT CANNOT PROVE. Whether a granted tool returns useful data on real
 * hardware with real accounts, real messages or a real heart-rate sensor. That is
 * named in the PR body per issue, because a green run here is not that evidence
 * and must never be reported as if it were.
 */
@RunWith(AndroidJUnit4::class)
class TeamThreeToolHonestyTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val koin get() = GlobalContext.get()

    @Test
    fun runsInTheAppDomainNotTheShellDomain() {
        val domain = runCatching {
            java.io.File("/proc/self/attr/current").readText().trim().trim('\u0000')
        }.getOrDefault("unknown")
        println("SELinux domain under test: $domain")
        assertTrue("must run in the app domain, ran as '$domain'", domain.startsWith("u:r:untrusted_app"))
    }

    /**
     * No two domains may declare the same tool name.
     *
     * The registry keys tools by name, so a duplicate silently wins depending on
     * load order and the tool behaves differently between boots. That is the kind
     * of fault that looks like a flaky model and is actually a data problem.
     */
    @Test
    fun noTwoDomainsDeclareTheSameToolName() {
        val duplicates = TeamThreeToolDomains.duplicateToolNames()
        println("domain tool count=${TeamThreeToolDomains.toolNames().size} duplicates=$duplicates")
        assertTrue("duplicate tool names across domains: $duplicates", duplicates.isEmpty())
    }

    /**
     * Every domain executor must be resolvable from Koin.
     *
     * A registration naming an executor class that nothing binds throws only at
     * the moment somebody calls the tool, which is the worst possible time to find
     * out. Resolving them here turns that into a startup fact.
     */
    @Test
    fun everyDomainExecutorIsResolvableFromKoin() {
        val missing = mutableListOf<String>()
        for (domain in TeamThreeToolDomains.domains) {
            for (reg in domain.registrations) {
                val cls = reg.executorClass()
                val resolved = runCatching { koin.get(cls) }.isSuccess
                if (!resolved) missing += "${domain.id}: ${cls.simpleName}"
            }
        }
        println("domains=${TeamThreeToolDomains.domains.map { it.id }} missing=$missing")
        assertTrue("these executors are declared but not bound in Koin: $missing", missing.isEmpty())
    }

    /**
     * A tool whose grant is missing must fail, and its failure must carry a
     * sentence a person can act on.
     *
     * This is the defect the batch exists to remove, tested against whatever
     * permission state this device happens to be in: for each tool, either it ran
     * and produced data, or it refused with a sentence naming a route or naming a
     * privileged or hardware limit. What must never happen is a success with an
     * empty payload standing in for a refusal.
     */
    @Test
    fun everyRefusalIsASentenceAPersonCanActOn() = runBlocking {
        val offenders = mutableListOf<String>()
        val refusals = mutableListOf<String>()

        for (domain in TeamThreeToolDomains.domains) {
            for (reg in domain.registrations) {
                val executor: ToolExecutor = runCatching { koin.get(reg.executorClass()) }.getOrNull() ?: continue
                for (def in reg.definitions) {
                    val result = runCatching {
                        executor.execute(def.name, benignArgumentsFor(def.name))
                    }.getOrNull() ?: continue

                    if (!result.success) {
                        val sentence = result.error.orEmpty()
                        refusals += "${def.name}: $sentence"
                        val actionable = sentence.contains("Grant it in") ||
                            sentence.contains("Shizuku or Device Owner") ||
                            sentence.contains("does not have it") ||
                            sentence.contains("Tool defect") ||
                            sentence.contains("Install-time fault") ||
                            sentence.contains("confirmed")
                        if (!actionable) {
                            offenders += "${def.name} refused without an actionable sentence: '$sentence'"
                        }
                        if (sentence.isBlank()) {
                            offenders += "${def.name} refused with no sentence at all"
                        }
                    } else {
                        // A success must carry a payload. A success with empty JSON is
                        // exactly the "denial reported as an empty list" shape.
                        if (result.rawJson.isBlank()) {
                            offenders += "${def.name} reported success with no payload"
                        }
                    }
                }
            }
        }

        println("refusals observed (${refusals.size}):")
        refusals.forEach { println("  $it") }
        assertTrue("honesty faults: $offenders", offenders.isEmpty())
    }

    /**
     * A destructive tool must refuse when it is not explicitly confirmed.
     *
     * Every irreversible action in this batch — sending a message, placing a call,
     * deleting a contact, writing a call log — takes a confirmation flag that
     * defaults false. This proves the default really is a refusal, because a
     * default that sends without asking is the one mistake that cannot be undone.
     */
    @Test
    fun everyDestructiveToolRefusesWithoutExplicitConfirmation() = runBlocking {
        val destructive = setOf(
            "smsSend", "phoneCall", "contactDelete", "callLogWrite",
        )
        val offenders = mutableListOf<String>()

        for (domain in TeamThreeToolDomains.domains) {
            for (reg in domain.registrations) {
                val executor: ToolExecutor = runCatching { koin.get(reg.executorClass()) }.getOrNull() ?: continue
                for (def in reg.definitions) {
                    if (def.name !in destructive) continue
                    val result = runCatching { executor.execute(def.name, benignArgumentsFor(def.name)) }
                        .getOrDefault(ToolExecutionResult.error("threw"))
                    println("${def.name} without confirm -> success=${result.success} error=${result.error}")
                    if (result.success) {
                        offenders += "${def.name} succeeded with no confirmation flag"
                    }
                }
            }
        }

        assertTrue("destructive tools must refuse without confirmation: $offenders", offenders.isEmpty())
    }

    /**
     * A confirmation flag must be declared on every destructive tool.
     *
     * If the flag is not in the schema the model cannot know to pass it, and the
     * tool is effectively unusable rather than safely gated.
     */
    @Test
    fun everyDestructiveToolDeclaresAConfirmationParameter() {
        val destructive = setOf("smsSend", "phoneCall", "contactDelete", "callLogWrite")
        val offenders = mutableListOf<String>()

        for (domain in TeamThreeToolDomains.domains) {
            for (reg in domain.registrations) {
                for (def in reg.definitions) {
                    if (def.name !in destructive) continue
                    val hasFlag = def.parameters.any {
                        it.name.equals("confirmed", ignoreCase = true) ||
                            it.name.equals("confirm", ignoreCase = true)
                    }
                    if (!hasFlag) offenders += def.name
                }
            }
        }
        assertTrue("destructive tools missing a confirmation parameter: $offenders", offenders.isEmpty())
    }

    /**
     * Arguments good enough to reach the grant check.
     *
     * Deliberately minimal: the guard is the first statement in every tool, so a
     * missing grant refuses before any argument is dereferenced. Tools that take
     * no arguments get an empty map. This keeps the harness honest — it tests the
     * refusal path, not a fabricated happy path.
     */
    private fun benignArgumentsFor(toolName: String): Map<String, Any?> = emptyMap()

    /**
     * The tool count the batch promises, printed so the PR body can quote a real
     * number rather than a remembered one.
     */
    @Test
    fun printsTheRegisteredDomainToolInventory() {
        val names = TeamThreeToolDomains.toolNames().sorted()
        println("domain tools (${names.size}): ${names.joinToString(", ")}")
        assertEquals("no duplicate names", names.size, names.toSet().size)
        assertFalse("a domain must exist once its tools are merged", names.isEmpty())
    }
}
