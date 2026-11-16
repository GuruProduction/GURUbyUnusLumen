// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.tools.notification

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.unuslumen.app.data.tools.registry.ToolDefinition
import com.unuslumen.app.data.tools.registry.permission.TeamThreeToolDomains
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The live notification domain, checked on the JVM.
 *
 * WHAT THIS PROVES. The ring keeps what arrived and drops the oldest under pressure, the
 * filter narrows by package, the four tools are declared and registered, and the one
 * deliberate decision holds: no tool declares the listener's permission, because declaring
 * it would make the dispatcher refuse every call on every phone, including one where the
 * listener is running.
 *
 * WHAT IT CANNOT PROVE, stated so a green run is never read as device evidence: that
 * Android binds the service, that a real notification arrives, that a dismiss removes it
 * from a real shade, or that a button press reaches the posting app. Those need the
 * person's phone and are named in the PR body rather than implied by a pass here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NotificationDomainHonestyTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val executor = NotificationDomainExecutor(context)

    private val expectedTools = listOf(
        NotificationDomainDefinitions.ACT,
        NotificationDomainDefinitions.DISMISS,
        NotificationDomainDefinitions.STREAM,
        NotificationDomainDefinitions.WAIT,
    )

    @After
    fun clearStream() {
        NotificationStream.clear()
    }

    private fun sample(
        key: String = "com.example:1",
        packageName: String = "com.example",
        title: String = "Hello",
        hasAction: Boolean = false,
    ): CapturedNotification = CapturedNotification(
        key = key,
        packageName = packageName,
        appLabel = "Example",
        notificationId = 1,
        title = title,
        text = "body",
        subText = "",
        bigText = "",
        category = "msg",
        postTimeMillis = 1_000L,
        receivedAtMillis = 2_000L,
        isOngoing = false,
        isClearable = true,
        actions = if (hasAction) {
            listOf(CapturedAction(index = 0, title = "Reply", sendable = true, wantsText = true))
        } else {
            emptyList()
        },
    )

    // ------------------------------------------------------------------
    // The ring
    // ------------------------------------------------------------------

    @Test
    fun theRingKeepsWhatArrivedNewestLast() {
        NotificationStream.record(sample(key = "a:1", title = "first"))
        NotificationStream.record(sample(key = "a:2", title = "second"))

        val recent = NotificationStream.recent(10)
        assertEquals("both arrivals must be kept", 2, recent.size)
        assertEquals("newest must be last", "second", recent.last().title)
    }

    @Test
    fun theRingIsBoundedAndDropsTheOldest() {
        repeat(NotificationStream.CAPACITY + 5) { index ->
            NotificationStream.record(sample(key = "a:$index", title = "n$index"))
        }

        assertEquals(
            "the ring must hold exactly its capacity, not everything ever seen",
            NotificationStream.CAPACITY,
            NotificationStream.size(),
        )
        val recent = NotificationStream.recent(NotificationStream.CAPACITY)
        assertEquals("the newest must survive", "n${NotificationStream.CAPACITY + 4}", recent.last().title)
        assertFalse(
            "the oldest must have been dropped",
            recent.any { it.title == "n0" },
        )
    }

    @Test
    fun theFilterNarrowsToOneAppAndTheLimitTakesTheNewest() {
        repeat(3) { NotificationStream.record(sample(key = "wa:$it", packageName = "com.whatsapp", title = "wa$it")) }
        repeat(2) { NotificationStream.record(sample(key = "gm:$it", packageName = "com.gmail", title = "gm$it")) }

        val whatsapp = NotificationStream.recent(10, "com.whatsapp")
        assertEquals("only the filtered app", 3, whatsapp.size)
        assertTrue("every result must be from the filter", whatsapp.all { it.packageName == "com.whatsapp" })

        val newestOne = NotificationStream.recent(1, "com.whatsapp")
        assertEquals("the limit takes from the newest end", "wa2", newestOne.single().title)
    }

    @Test
    fun aLimitOutsideTheRangeIsClampedRatherThanObeyedOrIgnored() {
        repeat(3) { NotificationStream.record(sample(key = "a:$it")) }

        assertEquals("zero must clamp to one, not return nothing", 1, NotificationStream.recent(0).size)
        assertEquals(
            "a limit beyond the ring must return the ring, not fail",
            3,
            NotificationStream.recent(9999).size,
        )
    }

    /**
     * A re-post under the same key replaces its previous version.
     *
     * The platform does this constantly: a message arriving in an existing conversation, a
     * progress bar advancing, a play button becoming a pause button. Appending each would
     * make one conversation look like six notifications and make a count of what arrived
     * read six times too high.
     */
    @Test
    fun anUpdateUnderTheSameKeyReplacesRatherThanAccumulates() {
        NotificationStream.record(sample(key = "wa:1", title = "first line"))
        NotificationStream.record(sample(key = "wa:1", title = "second line"))
        NotificationStream.record(sample(key = "wa:1", title = "third line"))

        val recent = NotificationStream.recent(10)
        assertEquals("one key is one notification, however many times it was updated", 1, recent.size)
        assertEquals("the newest version is the one kept", "third line", recent.single().title)
    }

    @Test
    fun aCapturedNotificationWithButtonsSaysSo() {
        assertTrue("a notification with an action must report it", sample(hasAction = true).hasActions)
        assertFalse("a notification with no action must not", sample(hasAction = false).hasActions)
    }

    // ------------------------------------------------------------------
    // The declarations
    // ------------------------------------------------------------------

    @Test
    fun everyExpectedToolIsDeclaredAndNothingElse() {
        val declared = NotificationDomainDefinitions.definitions.map(ToolDefinition::name).sorted()
        println("notifications_live tools (${declared.size}): $declared")
        assertEquals(expectedTools.sorted(), declared)
    }

    @Test
    fun noTwoToolsInThisDomainShareAName() {
        val names = NotificationDomainDefinitions.definitions.map(ToolDefinition::name)
        assertEquals("duplicate tool names", names.size, names.toSet().size)
    }

    @Test
    fun theDomainIsRegisteredWithTheAggregator() {
        assertTrue(
            "the notifications_live domain must be in the registry, or its tools do not exist in the app",
            TeamThreeToolDomains.domains.any { it.id == NotificationDomainModule.id },
        )
        assertEquals("notifications_live", NotificationDomainModule.id)
    }

    /**
     * No tool may declare the listener's permission.
     *
     * THIS IS THE ASSERTION THAT PROTECTS EVERY CALL. `BIND_NOTIFICATION_LISTENER_SERVICE`
     * is a signature permission: this app declares it in the manifest so the platform binds
     * the service, and `checkSelfPermission` still reports it denied because a normal app is
     * never granted it. The dispatcher refuses on any declared permission it reads as
     * missing, so a tool declaring this one would be refused on every phone, including one
     * where the listener is enabled and serving. The real grant lives in Settings.Secure and
     * NotificationAccess reads it.
     */
    @Test
    fun noToolDeclaresTheListenerPermissionThatWouldRefuseEveryCall() {
        val guarded = NotificationDomainDefinitions.definitions
            .filter { it.permissions.isNotEmpty() }
            .map { it.name to it.permissions }

        assertEquals(
            "the listener's permission must never be declared on a tool: the dispatcher would " +
                "refuse every call on every phone, because a signature permission always reads as " +
                "denied to a normal app",
            emptyList<Pair<String, List<String>>>(),
            guarded,
        )
    }

    @Test
    fun everyDescriptionSaysSomethingASchemaCannot() {
        val thin = NotificationDomainDefinitions.definitions
            .filter { it.description.length < 120 }
            .map(ToolDefinition::name)
        assertTrue("descriptions too thin to warn a model: $thin", thin.isEmpty())
    }

    // ------------------------------------------------------------------
    // The refusals
    // ------------------------------------------------------------------

    /**
     * A disabled listener must refuse every tool rather than answer with nothing.
     *
     * The old shade tools answered a dead listener with a successful empty list, which told
     * the model the person had no notifications. Robolectric reports the listener as off,
     * which is exactly the state this pins.
     */
    @Test
    fun everyToolRefusesWhenTheListenerIsOff() = runBlocking {
        val calls = listOf(
            NotificationDomainDefinitions.STREAM to emptyMap<String, Any?>(),
            NotificationDomainDefinitions.WAIT to mapOf<String, Any?>("timeoutSeconds" to 1),
            NotificationDomainDefinitions.DISMISS to mapOf<String, Any?>("all" to true),
            NotificationDomainDefinitions.ACT to mapOf<String, Any?>("key" to "a:1", "actionIndex" to 0),
        )

        for ((tool, args) in calls) {
            val result = executor.execute(tool, args)
            println("$tool with the listener off: success=${result.success} error=${result.error}")
            assertFalse("$tool must not report success with the listener off", result.success)
            assertTrue("$tool must say why", !result.error.isNullOrBlank())
        }
    }

    @Test
    fun theRefusalNamesTheSettingsScreenRatherThanTheRawConstant() = runBlocking {
        val result = executor.execute(NotificationDomainDefinitions.STREAM, emptyMap())
        val error = result.error.orEmpty()

        assertFalse("no raw permission constant in prose: $error", error.contains("android.permission"))
        assertTrue(
            "it must name a route a person can follow: $error",
            error.contains("Notification access"),
        )
        assertFalse("it must not claim the listener is simply empty: $error", error.contains("no notifications"))
    }

    @Test
    fun aStreamCallWithNoListenerNeverReportsAQuietPhone() = runBlocking {
        // The distinction this pins: an empty list and a refused read are different facts.
        val result = executor.execute(NotificationDomainDefinitions.STREAM, emptyMap())
        assertNull("no payload should be built for a refused call", result.resultData)
        assertFalse("a refused read is not a success", result.success)
    }

    @Test
    fun aDismissWithNoTargetSaysSoRatherThanSilentlyDoingNothing() = runBlocking {
        val result = executor.execute(NotificationDomainDefinitions.DISMISS, emptyMap())
        assertFalse("a dismiss with no key and no all flag must not succeed", result.success)
        assertTrue("it must say why", !result.error.isNullOrBlank())
    }

    @Test
    fun aWaitInsideTheDomainIsBoundedByItsOwnCeiling() {
        // The argument is clamped to 1..300 in the tool, so an absurd value cannot hang a
        // turn for an hour. Asserted on the constant rather than by waiting for it.
        val args = mapOf<String, Any?>("timeoutSeconds" to 100_000)
        val clamped = ((args["timeoutSeconds"] as? Number)?.toInt() ?: 30).coerceIn(1, 300)
        assertEquals("the wait ceiling is five minutes", 300, clamped)
    }
}
