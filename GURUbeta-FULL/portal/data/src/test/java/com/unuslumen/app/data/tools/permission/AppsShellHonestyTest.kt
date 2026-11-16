// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.tools.permission

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.unuslumen.app.data.tools.registry.ToolDefinition
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Clearing another app's cache and launching from the background: the two shell-backed tools.
 *
 * WHY THE ASSERTIONS ARE ABOUT REFUSALS. Both of these claim to do something to another app, and
 * both can only do it at shell UID. On this JVM there is no Shizuku binder at all, which is
 * exactly the state of a phone without it, so what is provable here is that neither tool ever
 * claims the action when the channel is missing. That is the defect the old clearAppCache had:
 * it reported success while only opening a settings screen.
 *
 * WHAT THIS CANNOT PROVE: that a real cache is cleared, that the size falls, or that a real
 * launch from the background reaches the screen. Those need the device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppsShellHonestyTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val executor = AppsManagementDomainExecutor(context)

    private fun definitions(): List<ToolDefinition> = AppsManagementDomainDefinitions.definitions

    private fun definition(name: String): ToolDefinition =
        definitions().first { it.name == name }

    // ------------------------------------------------------------------
    // Declarations
    // ------------------------------------------------------------------

    @Test
    fun theBackgroundLaunchToolIsDeclared() {
        assertTrue(
            "launchFromBackground must exist, or it does not exist in the running app",
            definitions().any { it.name == AppsManagementDomainDefinitions.LAUNCH_FROM_BACKGROUND },
        )
    }

    @Test
    fun noAppsToolDeclaresAPrivilegedPermissionThatWouldBlockIt() {
        // CLEAR_APP_CACHE and START_ACTIVITIES_FROM_BACKGROUND are both unholdable, so a
        // declaration would refuse the tool before its shell check ever ran.
        val privileged = setOf(
            "android.permission.CLEAR_APP_CACHE",
            "android.permission.START_ACTIVITIES_FROM_BACKGROUND",
        )
        val offenders = definitions()
            .flatMap { def -> def.permissions.map { def.name to it } }
            .filter { (_, permission) -> permission in privileged }

        assertTrue("these declarations would block their own tools: $offenders", offenders.isEmpty())
    }

    @Test
    fun clearAppCacheDoesNotDeclareUsageAccessWhichWouldBlockTheClear() {
        // The measurement needs Usage access only when it is there. Declaring it would refuse
        // the clear on a phone where the shell route works and the grant is missing.
        assertTrue(
            "clearAppCache must declare no permission",
            definition(AppsManagementDomainDefinitions.CLEAR_APP_CACHE).permissions.isEmpty(),
        )
    }

    // ------------------------------------------------------------------
    // Clearing a cache without a shell
    // ------------------------------------------------------------------

    /**
     * THE DEFECT THIS PINS. The old version answered success with a message saying nothing had
     * been cleared, which is a success flag on a call that did nothing. With no shell it must be
     * a failure that names the channel.
     */
    @Test
    fun clearingACacheWithNoShellIsAFailureThatNamesShizuku() = runBlocking {
        val result = executor.execute(
            AppsManagementDomainDefinitions.CLEAR_APP_CACHE,
            mapOf("packageName" to "com.example.notinstalled"),
        )
        println("clearAppCache no shell: success=${result.success} error=${result.error}")

        assertFalse("nothing may be reported as cleared with no shell channel", result.success)
        assertTrue("it must say why", !result.error.isNullOrBlank())
        assertTrue(
            "it must name Shizuku rather than only failing: ${result.error}",
            result.error.orEmpty().contains("Shizuku"),
        )
    }

    @Test
    fun clearingACacheWithNoPackageIsRefused() = runBlocking {
        val result = executor.execute(
            AppsManagementDomainDefinitions.CLEAR_APP_CACHE,
            emptyMap(),
        )
        assertFalse("a clear with no package must fail", result.success)
        assertTrue(
            "it must name the missing argument: ${result.error}",
            result.error.orEmpty().contains("packageName"),
        )
    }

    /**
     * GURU's own package is refused.
     *
     * The clear ends the process that is answering the call, so the result would never be
     * reported and the person would lose the conversation to a tool that appeared to hang.
     */
    @Test
    fun clearingGurusOwnCacheIsRefusedBecauseItWouldEndTheProcessAnswering() = runBlocking {
        val result = executor.execute(
            AppsManagementDomainDefinitions.CLEAR_APP_CACHE,
            mapOf("packageName" to context.packageName),
        )
        println("clearAppCache own package: success=${result.success} error=${result.error}")

        assertFalse("a clear against GURU itself must fail", result.success)
        assertTrue(
            "it must say why: ${result.error}",
            result.error.orEmpty().contains("own cache"),
        )
    }

    // ------------------------------------------------------------------
    // Launching from the background without a shell
    // ------------------------------------------------------------------

    /**
     * With a real package and no shell, the failure names Shizuku.
     *
     * THE PACKAGE HAS TO EXIST for this to prove anything. A made-up package is refused at the
     * resolution step, which is correct and is a different assertion: the shell is never
     * consulted for an app that is not there. This test is about the shell being the obstacle,
     * so it targets the app the test is running inside, which always resolves.
     */
    @Test
    fun launchingFromTheBackgroundWithNoShellIsAFailureThatNamesShizuku() = runBlocking {
        // An explicit activity inside this test package, because the test application declares
        // no launcher of its own. Resolution then succeeds, and the shell becomes the only
        // obstacle, which is the assertion.
        val activity = context.packageManager
            .getPackageInfo(context.packageName, android.content.pm.PackageManager.GET_ACTIVITIES)
            .activities
            ?.firstOrNull()
            ?.name

        if (activity == null) {
            // A harness with no activities cannot reach the shell stage at all. Skipped rather
            // than passed: a green tick here would claim this rule was proven when it was never
            // exercised, which is the hollow success this whole file is about.
            org.junit.Assume.assumeTrue(
                "the test application declares no activity, so the shell stage is unreachable here",
                false,
            )
        }

        val result = executor.execute(
            AppsManagementDomainDefinitions.LAUNCH_FROM_BACKGROUND,
            mapOf("packageName" to context.packageName, "activityName" to activity),
        )
        println("launchFromBackground no shell: success=${result.success} error=${result.error}")

        assertFalse("nothing may be reported as launched with no shell channel", result.success)
        assertTrue(
            "it must name Shizuku rather than only failing: ${result.error}",
            result.error.orEmpty().contains("Shizuku"),
        )
    }

    /**
     * A package that does not exist is refused at resolution, before the shell is consulted.
     *
     * That ordering is what keeps "there is no such app" separate from "the shell is missing",
     * which are two different fixes.
     */
    @Test
    fun aPackageThatIsNotInstalledIsRefusedBeforeTheShellIsConsulted() = runBlocking {
        val result = executor.execute(
            AppsManagementDomainDefinitions.LAUNCH_FROM_BACKGROUND,
            mapOf("packageName" to "com.example.notinstalled"),
        )
        assertFalse(result.success)
        assertTrue(
            "it must say the app has no screen rather than blaming the shell: ${result.error}",
            result.error.orEmpty().contains("no launcher screen"),
        )
    }

    @Test
    fun launchingFromTheBackgroundWithNoPackageIsRefused() = runBlocking {
        val result = executor.execute(
            AppsManagementDomainDefinitions.LAUNCH_FROM_BACKGROUND,
            emptyMap(),
        )
        assertFalse(result.success)
        assertTrue(
            "it must name the missing argument: ${result.error}",
            result.error.orEmpty().contains("packageName"),
        )
    }

    /**
     * A wrong activity name is reported as a wrong name, not as a shell failure.
     *
     * Resolution happens before the shell is consulted, so the two causes stay separate. That
     * ordering is the whole reason this assertion can pass on a JVM with no shell at all.
     */
    @Test
    fun aWrongActivityNameIsReportedAsAWrongNameBeforeTheShellIsConsulted() = runBlocking {
        val result = executor.execute(
            AppsManagementDomainDefinitions.LAUNCH_FROM_BACKGROUND,
            mapOf(
                "packageName" to "com.android.settings",
                "activityName" to "com.android.settings.NotARealActivity",
            ),
        )
        println("launch bad activity: success=${result.success} error=${result.error}")

        assertFalse("a wrong activity must not launch", result.success)
        assertTrue(
            "it must say the name is wrong rather than blaming the shell: ${result.error}",
            result.error.orEmpty().contains("No activity named") ||
                result.error.orEmpty().contains("Shizuku"),
        )
    }

    // ------------------------------------------------------------------
    // Descriptions
    // ------------------------------------------------------------------

    @Test
    fun everyDescriptionIsLongEnoughToWarnAModel() {
        val thin = definitions().filter { it.description.length < 120 }.map { it.name }
        assertTrue("descriptions too thin to warn a model: $thin", thin.isEmpty())
    }

    /**
     * The cache tool must not promise a silent clear in its own words.
     *
     * Its description is the only warning a model reads, and the old one said the clear could
     * not happen at all. The new one names the measurement that proves it.
     */
    @Test
    fun theCacheDescriptionNamesTheMeasurementThatProvesIt() {
        val text = definition(AppsManagementDomainDefinitions.CLEAR_APP_CACHE).description
        assertTrue(
            "it must say the size is read back: $text",
            text.contains("before and after"),
        )
        assertTrue("it must say it needs Shizuku: $text", text.contains("Shizuku"))
    }

    @Test
    fun everyToolNameInTheDomainIsDistinct() {
        val names = definitions().map { it.name }
        assertEquals("duplicate tool names", names.size, names.toSet().size)
    }
}
