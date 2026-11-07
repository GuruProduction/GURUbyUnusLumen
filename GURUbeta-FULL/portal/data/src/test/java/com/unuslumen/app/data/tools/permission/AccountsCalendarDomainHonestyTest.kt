// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.tools.permission

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.unuslumen.app.data.tools.registry.ToolDefinition
import com.unuslumen.app.data.tools.registry.permission.GrantCheck
import com.unuslumen.app.data.tools.registry.permission.GrantState
import com.unuslumen.app.data.tools.registry.permission.TeamThreePermissions
import com.unuslumen.app.data.tools.registry.permission.TeamThreeToolDomains
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The calendar and accounts domain, checked on the JVM without a device.
 *
 * WHAT THIS PROVES. The three tools are declared with the permissions that gate them, the
 * domain is registered, the calendar results say they read the DEVICE store, and the tools
 * that can validate their input purely refuse a bad call without touching the provider.
 *
 * THE RULE THAT CARRIES THE MOST WEIGHT HERE. A deleted calendar event is not recoverable
 * from the phone, so a DELETE without confirm must fail without deleting. That is asserted
 * directly, and it is the same shape as the factoryReset rule in the power domain: a
 * destructive operation fails closed.
 *
 * WHAT THIS CANNOT PROVE, stated so a green run is never mistaken for device evidence: that
 * a real calendar provider returns real events, that an insert lands, that an account
 * sign-in completes, or that a write is adjusted by the provider. Those need Steven's phone
 * with a real signed-in account.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AccountsCalendarDomainHonestyTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val executor = AccountsCalendarDomainExecutor(context)

    private val expectedTools = listOf(
        "accountsManage",
        "calendarRead",
        "calendarWrite",
    )

    private fun definitions(): List<ToolDefinition> = AccountsCalendarDomainDefinitions.definitions

    @Test
    fun everyExpectedToolIsDeclaredAndNothingElse() {
        val declared = definitions().map { it.name }.sorted()
        println("accounts_calendar tools (${declared.size}): $declared")
        assertEquals(expectedTools.sorted(), declared)
    }

    @Test
    fun noTwoToolsInThisDomainShareAName() {
        val names = definitions().map { it.name }
        assertEquals("duplicate tool names", names.size, names.toSet().size)
    }

    @Test
    fun theDomainIsRegisteredWithTheAggregator() {
        assertTrue(
            "the accounts_calendar domain must be in the registry",
            TeamThreeToolDomains.domains.any { it.id == AccountsCalendarDomainModule.id },
        )
        assertEquals("accounts_calendar", AccountsCalendarDomainModule.id)
    }

    @Test
    fun everyDeclaredPermissionIsCatalogued() {
        val catalogue = TeamThreePermissions.all.keys
        val unreasoned = definitions()
            .flatMap { def -> def.permissions.map { def.name to it } }
            .filter { (_, permission) -> permission !in catalogue }

        println("permissions used: ${definitions().flatMap { it.permissions }.distinct()}")
        assertTrue("permissions used but not reasoned about: $unreasoned", unreasoned.isEmpty())
    }

    @Test
    fun everyToolDeclaresTheGrantItNeeds() {
        val missing = definitions().filter { it.permissions.isEmpty() }.map { it.name }
        assertTrue("tools declaring no permission but needing one: $missing", missing.isEmpty())
    }

    @Test
    fun everyPermissionThisDomainDeclaresHasAUsableRefusal() {
        val specs = definitions()
            .flatMap { it.permissions }
            .distinct()
            .mapNotNull { TeamThreePermissions.forPermission(it) }

        assertTrue("expected at least four catalogued permissions", specs.size >= 4)

        val problems = mutableListOf<String>()
        for (spec in specs) {
            val check = GrantCheck.of(spec, granted = false)
            val sentence = check.sentence()
            if (!sentence.trim().endsWith(".")) problems += "${spec.permission}: no full stop"
            if (sentence.contains("  ")) problems += "${spec.permission}: doubled space"
            if (sentence.contains("android.permission")) {
                problems += "${spec.permission}: raw constant in prose"
            }
            if (check.state == GrantState.NOT_GRANTED && !sentence.contains("Grant it in")) {
                problems += "${spec.permission}: a denial with no route"
            }
        }
        assertTrue("unusable refusals: $problems", problems.isEmpty())
    }

    // ------------------------------------------------------------------
    // Fail-closed and validation rules
    // ------------------------------------------------------------------

    /**
     * Grant before testing validation.
     *
     * WHY THIS IS NEEDED. Every tool guards its permission before reading an argument, which
     * is correct: a tool that cannot run must not validate and then fail on a grant it never
     * checked. But it means a test asserting "a bad argument is refused" gets the permission
     * refusal instead when the grant is absent. The grant is therefore applied explicitly, so
     * the assertion reaches the validation it is actually about.
     */
    private fun grant(vararg permissions: String) {
        org.robolectric.Shadows.shadowOf(
            ApplicationProvider.getApplicationContext<android.app.Application>(),
        ).grantPermissions(*permissions)
    }

    /**
     * A deleted event is not recoverable from the phone, so a DELETE without confirm must
     * fail without removing anything.
     */
    @Test
    fun calendarDeleteWithoutConfirmRefuses() = runBlocking {
        grant(
            TeamThreePermissions.READ_CALENDAR.permission,
            TeamThreePermissions.WRITE_CALENDAR.permission,
        )

        val result = executor.execute(
            AccountsCalendarDomainDefinitions.CALENDAR_WRITE,
            mapOf("action" to "DELETE", "eventId" to 42L),
        )
        val error = result.error.orEmpty()
        println("delete without confirm: success=${result.success} error=$error")

        assertFalse("a delete with no confirmation must not succeed", result.success)
        assertTrue("it must say why", error.isNotBlank())
        assertTrue(
            "the refusal must name the confirmation requirement: $error",
            error.contains("confirm", ignoreCase = true),
        )
    }

    @Test
    fun calendarCreateWithoutRequiredFieldsIsRefused() = runBlocking {
        grant(
            TeamThreePermissions.READ_CALENDAR.permission,
            TeamThreePermissions.WRITE_CALENDAR.permission,
        )

        val noCalendar = executor.execute(
            AccountsCalendarDomainDefinitions.CALENDAR_WRITE,
            mapOf("action" to "CREATE", "title" to "a thing"),
        )
        val noTitle = executor.execute(
            AccountsCalendarDomainDefinitions.CALENDAR_WRITE,
            mapOf("action" to "CREATE", "calendarId" to 1L),
        )

        assertFalse("a create with no calendar must not succeed", noCalendar.success)
        assertTrue("it must say why", !noCalendar.error.isNullOrBlank())
        assertFalse("a create with no title must not succeed", noTitle.success)
        assertTrue("it must say why", !noTitle.error.isNullOrBlank())
    }

    @Test
    fun anUnknownWriteActionIsRefused() = runBlocking {
        grant(
            TeamThreePermissions.READ_CALENDAR.permission,
            TeamThreePermissions.WRITE_CALENDAR.permission,
        )

        val result = executor.execute(
            AccountsCalendarDomainDefinitions.CALENDAR_WRITE,
            mapOf("action" to "ARCHIVE"),
        )
        assertFalse("an unknown action must not succeed", result.success)
        assertTrue("it must say why", !result.error.isNullOrBlank())
        assertTrue(
            "it must name the actions it accepts: ${result.error}",
            result.error.orEmpty().contains("CREATE"),
        )
    }

    /**
     * A window that runs backwards must be refused rather than queried, because an empty
     * result would be indistinguishable from an empty calendar.
     */
    @Test
    fun aBackwardsWindowIsRefusedRatherThanQueried() = runBlocking {
        grant(TeamThreePermissions.READ_CALENDAR.permission)

        val result = executor.execute(
            AccountsCalendarDomainDefinitions.CALENDAR_READ,
            mapOf("fromMillis" to 2_000_000L, "toMillis" to 1_000_000L),
        )
        val error = result.error.orEmpty()
        println("backwards window: success=${result.success} error=$error")

        assertFalse("a backwards window must not report success", result.success)
        assertTrue("it must say why", error.isNotBlank())
        assertTrue(
            "it must distinguish this from finding no events: $error",
            error.contains("No", ignoreCase = true) || error.contains("window", ignoreCase = true),
        )
    }

    @Test
    fun anUnknownAccountActionIsRefused() = runBlocking {
        grant(
            TeamThreePermissions.MANAGE_ACCOUNTS.permission,
            TeamThreePermissions.GET_ACCOUNTS.permission,
        )

        val result = executor.execute(
            AccountsCalendarDomainDefinitions.ACCOUNTS_MANAGE,
            mapOf("action" to "SYNC", "accountName" to "someone@example.com"),
        )
        assertFalse("an unknown account action must not succeed", result.success)
        assertTrue("it must say why", !result.error.isNullOrBlank())
    }

    @Test
    fun accountsManageWithoutAnAccountNameIsRefused() = runBlocking {
        grant(
            TeamThreePermissions.MANAGE_ACCOUNTS.permission,
            TeamThreePermissions.GET_ACCOUNTS.permission,
        )

        val result = executor.execute(
            AccountsCalendarDomainDefinitions.ACCOUNTS_MANAGE,
            mapOf("action" to "ADD", "accountType" to "com.google"),
        )
        assertFalse("an add with no account name must not succeed", result.success)
        assertTrue("it must say why", !result.error.isNullOrBlank())
        assertTrue(
            "it must name the missing field: ${result.error}",
            result.error.orEmpty().contains("accountName"),
        )
    }

    /**
     * The description is what stops a model confusing the device calendar with GURU's own
     * event list, which is a bug this project has already had. The word has to be in there.
     */
    @Test
    fun theCalendarDescriptionsDistinguishTheDeviceStore() {
        val read = definitions().first { it.name == "calendarRead" }
        val write = definitions().first { it.name == "calendarWrite" }

        assertTrue(
            "calendarRead must say it reads the device store: ${read.description}",
            read.description.contains("DEVICE", ignoreCase = true) ||
                read.description.contains("phone's own calendar", ignoreCase = true),
        )
        assertTrue(
            "calendarWrite must say it writes the store other apps read: ${write.description}",
            write.description.contains("other apps", ignoreCase = true) ||
                write.description.contains("calendar your other apps", ignoreCase = true),
        )
    }

    @Test
    fun everyDescriptionSaysSomethingASchemaCannot() {
        val thin = definitions().filter { it.description.length < 120 }.map { it.name }
        assertTrue("descriptions too thin to warn a model: $thin", thin.isEmpty())
    }
}
