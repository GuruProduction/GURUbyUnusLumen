// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.tools.permission

import android.Manifest
import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.unuslumen.app.data.tools.registry.ToolDefinition
import com.unuslumen.app.data.tools.registry.ToolExecutionResult
import com.unuslumen.app.data.tools.registry.ToolSetRegistration
import com.unuslumen.app.data.tools.registry.permission.TeamThreePermissions
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The personal-data domain, checked on the JVM without a device.
 *
 * WHAT THIS PROVES. Every one of the eight tools is declared under the name the
 * executor dispatches on, every permission string it declares resolves in the
 * catalogue rather than falling through to a platform guess, and every tool that
 * must refuse actually refuses through the executor when its grant is denied.
 *
 * WHY THE REFUSAL IS THE THING WORTH ASSERTING. The defect this batch exists to
 * remove is a denial reported as an empty result: "no calls matched" and "GURU is
 * not allowed to see your calls" are different facts, and only the second one
 * needs the person to do something. A tool that returns `success = true` with an
 * empty list when it was never allowed to look has told a lie by omission, and
 * this test is the guard against it creeping back in.
 *
 * WHAT THIS CANNOT PROVE, stated so it is never mistaken for device evidence:
 * that a real call log returns a call, that a real contact is created in a real
 * provider, that a real photo is written, or that Android in fact holds the
 * permissions the catalogue claims. Robolectric ships only part of the platform's
 * permission database, so nothing here asserts that the framework declares a
 * permission or that a tier matches the device. Those need Steven's own phone and
 * belong in the release test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CallLogContactsDomainHonestyTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val definitions: List<ToolDefinition> = CallLogContactsDomainDefinitions.definitions

    /** Every permission this domain declares, by the platform string it names. */
    private val declaredPermissions: List<String> =
        definitions.flatMap { it.permissions }.distinct()

    private fun execute(toolName: String, args: Map<String, Any?> = emptyMap()): ToolExecutionResult =
        runBlocking {
            CallLogContactsDomainExecutor(context).execute(toolName, args)
        }

    // ------------------------------------------------------------------
    // Declaration
    // ------------------------------------------------------------------

    /**
     * The tool names are the dispatcher's contract: the executor's `when` matches on
     * these exact strings, so a rename in one place and not the other produces a tool
     * that exists in the catalogue and answers "Unknown tool" when called.
     */
    @Test
    fun everyExpectedToolIsDeclaredAndNoOthersAre() {
        val names = definitions.map { it.name }
        println("calllog_contacts tools (${names.size}): ${names.sorted()}")
        assertEquals(
            listOf(
                "callLogRead",
                "callLogWrite",
                "contactsList",
                "contactCreate",
                "contactsUpdate",
                "contactsDelete",
                "contactPhoto",
                "accountsList",
            ).sorted(),
            names.sorted(),
        )
        assertEquals("duplicate tool names", names.size, names.toSet().size)
    }

    /** The domain id is what the aggregator and the dispatcher key the domain by. */
    @Test
    fun theDomainIsCallLogContacts() {
        assertEquals("calllog_contacts", CallLogContactsDomainModule.id)
    }

    /**
     * Every declared permission must resolve in the catalogue.
     *
     * A string the catalogue does not know falls through to a platform lookup, and a
     * string the platform does not know is reported as a tool defect. Both are worse
     * than a catalogue entry, which carries the Settings route the refusal names.
     */
    @Test
    fun everyDeclaredPermissionResolvesInTheCatalogue() {
        val unresolved = declaredPermissions.filter { TeamThreePermissions.forPermission(it) == null }
        println("declared permissions (${declaredPermissions.size}): ${declaredPermissions.sorted()}")
        assertTrue("permissions with no catalogue entry: $unresolved", unresolved.isEmpty())
    }

    /**
     * A tool that reads or writes a store must declare the grant that gates it, and
     * the account list must name GET_ACCOUNTS rather than borrowing an adjacent one.
     *
     * There is no exception in this domain: unlike the senses tools, not one of these
     * eight touches only in-process state, so an empty permission list here would mean
     * a tool that runs and then throws underneath.
     */
    @Test
    fun everyStoreToolDeclaresTheGrantThatGatesIt() {
        val unguarded = definitions.filter { it.permissions.isEmpty() }.map { it.name }
        assertTrue("tools that touch a store but declare no permission: $unguarded", unguarded.isEmpty())

        val accounts = definitions.first { it.name == "accountsList" }
        println("accountsList permissions: ${accounts.permissions}")
        assertEquals(listOf(Manifest.permission.GET_ACCOUNTS), accounts.permissions)
    }

    /** No permission list may contain a blank string, which would resolve to nothing. */
    @Test
    fun noPermissionStringIsBlank() {
        val blank = definitions.filter { def -> def.permissions.any { it.isBlank() } }.map { it.name }
        assertTrue("blank permission strings: $blank", blank.isEmpty())
    }

    /** Every tool description must say enough to warn a model off a stronger claim. */
    @Test
    fun everyDescriptionSaysSomethingASchemaCannot() {
        val thin = definitions.filter { it.description.length < 120 }.map { it.name }
        assertTrue("descriptions too thin to warn a model: $thin", thin.isEmpty())
    }

    // ------------------------------------------------------------------
    // Refusal
    // ------------------------------------------------------------------

    /**
     * A denied grant must produce a refusal, not an empty success.
     *
     * Robolectric denies an undeclared permission by default, and it is denied
     * explicitly here anyway so the test states its own setup rather than relying on
     * a default that a future Robolectric version could change.
     *
     * The arguments passed are the ones each tool would need to do real work, because
     * a refusal that only happens on a missing argument proves nothing: these tools
     * guard before they read any argument at all, and the test is checking that order.
     */
    @Test
    fun everyToolRefusesWhenItsGrantIsDenied() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(application).denyPermissions(
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.WRITE_CALL_LOG,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.WRITE_CONTACTS,
            Manifest.permission.GET_ACCOUNTS,
        )

        val calls = mapOf(
            "callLogRead" to mapOf<String, Any?>("limit" to 10),
            "callLogWrite" to mapOf<String, Any?>("number" to "0123456789", "type" to "OUTGOING"),
            "contactsList" to mapOf<String, Any?>("limit" to 10),
            "contactCreate" to mapOf<String, Any?>("displayName" to "Test Person"),
            "contactsUpdate" to mapOf<String, Any?>("contactId" to 1L, "displayName" to "Renamed"),
            "contactsDelete" to mapOf<String, Any?>("contactId" to 1L, "confirm" to true),
            "contactPhoto" to mapOf<String, Any?>("contactId" to 1L),
            "accountsList" to emptyMap<String, Any?>(),
        )

        val failures = mutableListOf<String>()
        for (definition in definitions) {
            val args = calls.getValue(definition.name)
            val result = execute(definition.name, args)
            println("${definition.name} denied -> success=${result.success} error=${result.error}")
            if (result.success) {
                failures += "${definition.name} reported success with its grant denied"
            }
            val error = result.error
            if (error.isNullOrBlank()) {
                failures += "${definition.name} refused with no error sentence"
            } else {
                if (!error.contains("permission", ignoreCase = true)) {
                    failures += "${definition.name} error does not name the permission: $error"
                }
                if (!error.trimEnd().endsWith(".")) {
                    failures += "${definition.name} error does not end with a full stop: $error"
                }
                if (error.contains("  ")) {
                    failures += "${definition.name} error has a doubled space: $error"
                }
            }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    /**
     * A refusal must name the capability in words, never the raw platform constant.
     *
     * The catalogue turns `android.permission.READ_CALL_LOG` into "Call log" for
     * exactly this reason: a sentence quoting the constant is one a person cannot act
     * on, and it is the shape the old dispatcher produced.
     */
    @Test
    fun noRefusalQuotesAPlatformPermissionConstant() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(application).denyPermissions(
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.WRITE_CALL_LOG,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.WRITE_CONTACTS,
            Manifest.permission.GET_ACCOUNTS,
        )

        val offenders = definitions.mapNotNull { definition ->
            val result = execute(definition.name, mapOf("contactId" to 1L, "confirm" to true))
            val error = result.error.orEmpty()
            if (error.contains("android.permission")) "${definition.name}: $error" else null
        }
        assertTrue("refusals quoting a platform constant: $offenders", offenders.isEmpty())
    }

    /**
     * The refusal names the permission group a person would look for, which is what
     * makes it followable. The Call log tools must say "Call log", the contacts tools
     * must say "Contacts", and neither may name the other's group.
     */
    @Test
    fun eachRefusalNamesItsOwnPermissionGroup() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(application).denyPermissions(
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.GET_ACCOUNTS,
        )

        val callLog = execute("callLogRead").error.orEmpty()
        val contacts = execute("contactsList").error.orEmpty()
        val accounts = execute("accountsList").error.orEmpty()

        println("callLogRead refusal: $callLog")
        println("contactsList refusal: $contacts")
        println("accountsList refusal: $accounts")

        assertTrue("callLogRead must name the Call log group: $callLog", callLog.contains("Call log"))
        assertTrue("contactsList must name the Contacts group: $contacts", contacts.contains("Contacts"))
        // GET_ACCOUNTS shares the Contacts permission group on Android, so the account
        // tool names Contacts too. Asserting that is asserting the platform's grouping,
        // not an accident of wording.
        assertTrue("accountsList must name the Contacts group: $accounts", accounts.contains("Contacts"))
    }

    /**
     * Every refusal sentence must be a complete sentence, because it is what a person
     * reads and what a model repeats. Checked across all eight tools rather than on the
     * two read tools, so a new tool cannot join the domain with a fragment.
     */
    @Test
    fun everyRefusalReadsAsACompleteSentence() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(application).denyPermissions(
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.WRITE_CALL_LOG,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.WRITE_CONTACTS,
            Manifest.permission.GET_ACCOUNTS,
        )

        val bad = definitions.mapNotNull { definition ->
            val error = execute(definition.name, mapOf("contactId" to 1L, "confirm" to true))
                .error.orEmpty()
            when {
                error.isBlank() -> "${definition.name} has no sentence"
                !error.trimEnd().endsWith(".") -> "${definition.name} does not end with a full stop: $error"
                error.contains("  ") -> "${definition.name} has a doubled space: $error"
                else -> null
            }
        }
        assertTrue("refusal sentences that do not read as sentences: $bad", bad.isEmpty())
    }

    /**
     * The declared-permission list on a tool and the grant its executor checks must be
     * the same permission. Checked by holding the grant the tool declares and denying
     * the rest: the tool must then get past its own guard, which it only does if the
     * guard names a permission it declared.
     *
     * This is the cheap direction that catches the copy-paste defect: a tool declaring
     * READ_CONTACTS while its executor guards on WRITE_CONTACTS would refuse a person
     * who had granted everything the catalogue asked for.
     */
    @Test
    fun theGuardMatchesTheDeclaredPermission() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val all = listOf(
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.WRITE_CALL_LOG,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.WRITE_CONTACTS,
            Manifest.permission.GET_ACCOUNTS,
        )
        shadowOf(application).denyPermissions(*all.toTypedArray())
        // Only READ_CALL_LOG held. callLogRead declares exactly that, so it must get
        // past its guard and report a data answer rather than a refusal.
        shadowOf(application).grantPermissions(Manifest.permission.READ_CALL_LOG)

        val permitted = execute("callLogRead", mapOf("limit" to 5))
        println("callLogRead with only READ_CALL_LOG held: success=${permitted.success} error=${permitted.error}")
        // The result must not be the permission refusal, which is the only thing this
        // asserts. What the query then does is the provider's business: with no call log
        // provider registered under Robolectric it correctly reports that it could not
        // answer, and that failure is a different sentence from a refusal.
        val error = permitted.error.orEmpty()
        assertFalse(
            "callLogRead refused although its declared grant was held: $error",
            error.contains("does not hold the Call log permission"),
        )

        // And the same tool must refuse again once that grant is taken away, which is
        // what proves the guard is reading the declaration rather than defaulting open.
        shadowOf(application).denyPermissions(Manifest.permission.READ_CALL_LOG)
        val refused = execute("callLogRead", mapOf("limit" to 5))
        assertFalse("callLogRead succeeded with its grant denied", refused.success)
        assertTrue(
            "callLogRead did not name the Call log group: ${refused.error}",
            refused.error.orEmpty().contains("Call log"),
        )
    }

    /**
     * An unknown tool name must be an error, never a success. The dispatcher resolves
     * names through the definitions, but the executor is public and its fallback is
     * what a hand-written call reaches.
     */
    @Test
    fun anUnknownToolNameIsAnError() {
        val result = execute("deleteEverything")
        assertFalse("an unknown tool reported success", result.success)
        assertTrue(
            "an unknown tool did not say so: ${result.error}",
            result.error.orEmpty().contains("Unknown tool"),
        )
    }

    // ------------------------------------------------------------------
    // Wiring
    // ------------------------------------------------------------------

    /**
     * The module must expose its own definitions and be constructible, because a
     * registration naming an executor that nothing binds throws only when a person
     * calls the tool, which is the worst moment to find out.
     */
    @Test
    fun theModuleExposesItsDefinitionsAndCanBuildItsExecutor() {
        val registrations: List<ToolSetRegistration> = CallLogContactsDomainModule.registrations
        assertEquals(1, registrations.size)
        assertEquals(
            CallLogContactsDomainDefinitions.definitions.map { it.name },
            registrations.single().definitions.map { it.name },
        )

        val built = runCatching { CallLogContactsDomainExecutor(context) }
        assertTrue("the executor cannot be constructed: ${built.exceptionOrNull()?.message}", built.isSuccess)
        assertEquals(
            CallLogContactsDomainExecutor::class,
            CallLogContactsDomainDefinitions.executorClass(),
        )
    }
}
