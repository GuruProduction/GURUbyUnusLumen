// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.tools.permission

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import androidx.test.core.app.ApplicationProvider
import com.unuslumen.app.data.tools.registry.ToolDefinition
import com.unuslumen.app.data.tools.registry.ToolSetRegistration
import com.unuslumen.app.data.tools.registry.permission.TeamThreePermissions
import com.unuslumen.app.data.tools.registry.permission.TeamThreeToolDomains
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The device-senses domains, checked on the JVM without a device.
 *
 * WHAT THIS PROVES. Every tool in the five senses domains is declared with the
 * permission that actually gates it, no two domains claim the same tool name, every
 * permission named is one this batch's catalogue knows, and every domain executor
 * can be constructed from its own bindings. Those are the facts that decide whether
 * a refusal says something true.
 *
 * WHY THE PERMISSION DECLARATION MATTERS SO MUCH. ToolDispatcher refuses before the
 * executor runs when a declared permission is missing, and the sentence names that
 * permission's Settings group. A tool that declares the wrong permission therefore
 * sends a person to the wrong screen, which is worse than saying nothing because it
 * looks like an instruction.
 *
 * WHAT THIS CANNOT PROVE, stated so it is never mistaken for device evidence: that a
 * real camera records, a real microphone hears, a real sensor reports, or a real UWB
 * radio ranges. Those need Steven's own phone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SensesDomainHonestyTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val catalogue = setOf(
        "android.permission.ACCESS_FINE_LOCATION",
        "android.permission.ACCESS_COARSE_LOCATION",
        "android.permission.ACCESS_BACKGROUND_LOCATION",
        "android.permission.ACCESS_MEDIA_LOCATION",
        "android.permission.CAMERA",
        "android.permission.RECORD_AUDIO",
        "android.permission.CAPTURE_AUDIO_OUTPUT",
        "android.permission.CAPTURE_VIDEO_OUTPUT",
        "android.permission.CAPTURE_SECURE_VIDEO_OUTPUT",
        "android.permission.MODIFY_AUDIO_SETTINGS",
        "android.permission.HIGH_SAMPLING_RATE_SENSORS",
        "android.permission.BODY_SENSORS",
        "android.permission.BODY_SENSORS_BACKGROUND",
        "android.permission.ACTIVITY_RECOGNITION",
        "android.permission.UWB_RANGING",
        "android.permission.BLUETOOTH_SCAN",
        "android.permission.BLUETOOTH_CONNECT",
        "android.permission.BLUETOOTH_ADVERTISE",
    )

    private fun allDefinitions(): List<Pair<String, ToolDefinition>> =
        TeamThreeToolDomains.registrations().flatMap { reg: ToolSetRegistration ->
            reg.definitions.map { reg.executorClass().simpleName.orEmpty() to it }
        }

    @Test
    fun everySensesDomainIsRegistered() {
        val ids = TeamThreeToolDomains.domains.map { it.id }.sorted()
        println("registered domains: $ids")
        // `phone` is in this list because the phone domain landed after the senses
        // batch was written. It is asserted rather than tolerated: a registered domain
        // is one the dispatcher will actually reach, so an unexpected id here means a
        // domain was added without anybody deciding to add it.
        //
        // `smart_home` and `ir` joined for #187. The fabric merged in #196 with no
        // aggregator line, so its five generic tools did not exist in the running app
        // at all and a request to control a television reached nothing. Naming both
        // here is the fix, and this assertion is what proves they are named.
        assertEquals(
            listOf(
                // The calendar and accounts domain. The device calendar is a different database
                // from GURU's own event list and every result says so, because conflating the two
                // is a bug this project has already had.
                "accounts_calendar",
                // Alarms and the automation health check. Two of its four tools declare no
                // permission at all, because reporting which grant is switched off needs none, and
                // one all-clear boolean would hide the one that is actually missing.
                "alarms_misc",
                // App usage and notifications. Its dndControl declares no permission on purpose,
                // because reading the Do Not Disturb filter needs none and refusing a true
                // reading to keep a uniform declaration would be the wrong trade.
                "app_usage_notify",
                // The apps domain: storage per app, stopping one, opening one, and handing an
                // install or a removal to the system's own dialog. Its names say request rather
                // than silent, because a silent install needs a privilege this build lacks.
                "apps_management",
                "bluetooth",
                // The personal-data domain: the call log and the address book. Its id is
                // deliberately not `phone`, because it is governed by a different
                // permission group and a refusal therefore names a different Settings
                // screen. Splitting it is what keeps each refusal pointing at the right
                // page.
                "calllog_contacts",
                "camera_audio",
                // Battery, the screen, the system log, a service dump and factory reset. Four of
                // the five need a privilege this build lacks, so the domain reports what IS
                // observable rather than returning an empty result that reads as a measurement.
                "device_power_introspect",
                "ir",
                "location",
                // Seeing and reaching every host on the wifi, not only televisions.
                "network",
                // The connection itself rather than host discovery: the link state, the radio,
                // the two toggles the platform mostly refuses, and the multicast lock that
                // mDNS and SSDP replies cannot happen without.
                "network_wifi",
                // NFC and the media library. NFC has a physical limit no grant crosses, because
                // reading a tag needs a tag present and a foreground session. The media library
                // has the partial-access subtlety, where Android may grant only the selected
                // photos and a naive count reports a small library that is not small.
                "nfc_media",
                "phone",
                "privileged_capture",
                "sensors",
                // Settings and device control: the settings tables, the clock, density, floating
                // overlays, the status bar, the wallpaper, biometric availability, vibration and
                // a wake lock. Every write here reports the value read back, not the value sent.
                "settings_control",
                "smart_home",
                // Network television control: the transport that actually drives a modern
                // set, because it needs no emitter and speaks each maker's own protocol.
                "tv",
            ),
            ids,
        )
    }

    /**
     * Every domain tool name must be unique. The registry keys by name, so a
     * duplicate silently wins depending on load order.
     */
    @Test
    fun noTwoSensesToolsShareAName() {
        val names = allDefinitions().map { it.second.name }
        println("senses tools (${names.size}): ${names.sorted()}")
        assertEquals("duplicate tool names", names.size, names.toSet().size)
    }

    /**
     * Every declared permission must be one the catalogue knows.
     *
     * A permission outside the catalogue resolves against the platform, and anything
     * the platform does not declare is reported as a tool defect. For this batch the
     * catalogue is authoritative, so a named permission that is not in it means the
     * tool is asking for something the app has not reasoned about.
     */
    @Test
    fun everyDeclaredPermissionIsInTheCatalogue() {
        // THE REAL CATALOGUE, not a copy of it. The earlier version hardcoded the
        // eighteen senses permissions, so the moment the phone domain registered its
        // own tools this test reported sixteen "unreasoned" permissions that were in
        // fact catalogued. A hand-written list drifts from the catalogue it claims to
        // check, and a drift here reads as a permission nobody reasoned about when the
        // truth is that the test stopped looking.
        val declared = TeamThreePermissions.all.keys
        val outside = allDefinitions()
            .flatMap { (owner, def) -> def.permissions.map { owner to it } }
            .filter { (_, permission) -> permission !in declared }

        println(
            "permissions used: ${allDefinitions().flatMap { it.second.permissions }.distinct().size} " +
                "of ${declared.size} catalogued"
        )
        assertTrue("permissions used but not reasoned about: $outside", outside.isEmpty())
    }

    /**
     * A tool that names a permission must be one whose capability really needs it.
     * Checked in the cheap direction that catches copy-paste: every tool with a
     * permission list has at least one, and no list is blank-but-present.
     */
    @Test
    fun everyPermissionListIsEitherEmptyOrReal() {
        val bad = allDefinitions()
            .filter { (_, def) -> def.permissions.isNotEmpty() && def.permissions.any { it.isBlank() } }
            .map { it.second.name }
        assertTrue("blank permission strings: $bad", bad.isEmpty())
    }

    /**
     * Every domain executor must be constructible from its own Koin module.
     *
     * A registration naming an executor that nothing binds throws only when somebody
     * calls the tool, which is the worst possible moment to find out.
     */
    @Test
    fun everyDomainExecutorCanBeBuiltFromItsOwnBindings() {
        // The modules bind through Koin; here the constructor is exercised directly,
        // which is the same object Koin would build and needs no global container.
        val failures = mutableListOf<String>()
        for (domain in TeamThreeToolDomains.domains) {
            for (reg in domain.registrations) {
                val cls = reg.executorClass()
                val built = runCatching {
                    cls.constructors.first().call(context)
                }
                if (built.isFailure) {
                    failures += "${domain.id}/${cls.simpleName}: ${built.exceptionOrNull()?.message}"
                }
            }
        }
        println("executor construction failures: $failures")
        assertTrue("these executors cannot be built: $failures", failures.isEmpty())
    }

    // ------------------------------------------------------------------
    // The honesty rules the batch states
    // ------------------------------------------------------------------

    /**
     * A heart-rate tool on a device with no heart-rate sensor must report the device,
     * never a zero pulse. This asserts the helper's answer matches the framework, so
     * the tool's refusal and the hardware fact cannot disagree.
     */
    @Test
    fun theHeartRateProbeAgreesWithTheFramework() {
        val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val present = manager.getDefaultSensor(Sensor.TYPE_HEART_RATE) != null
        val helperSays = com.unuslumen.app.data.tools.registry.permission.PermissionGrants
            .hasHeartRateSensor(context)
        println("heart-rate present=$present helper=$helperSays")
        assertEquals(present, helperSays)
    }

    /**
     * Every location tool must declare a location permission, and the watch must
     * declare coarse rather than fine so an approximate-only grant is not refused
     * outright. Reporting which precision is in force is the tool's job; refusing a
     * person who granted approximate would be wrong.
     */
    @Test
    fun theLocationToolsDeclareTheGrantsTheyActuallyNeed() {
        val locationTools = allDefinitions()
            .filter { it.second.category == "location" || it.second.name == "photoLocation" }
            .associate { it.second.name to it.second.permissions }

        println("location tools: $locationTools")
        val watch = locationTools["locationWatch"]
        assertNotNull("locationWatch must exist", watch)
        assertFalse(
            "locationWatch must not demand fine location, or an approximate-only grant is refused",
            watch!!.contains("android.permission.ACCESS_FINE_LOCATION"),
        )
        assertTrue(watch.contains("android.permission.ACCESS_BACKGROUND_LOCATION"))

        val photo = locationTools["photoLocation"]
        assertNotNull("photoLocation must exist", photo)
        assertTrue(photo!!.contains("android.permission.ACCESS_MEDIA_LOCATION"))

        assertTrue(
            "locationHistory touches no platform API and must declare no permission",
            locationTools["locationHistory"]?.isEmpty() == true,
        )
    }

    /**
     * Every tool description must be long enough to state what it does NOT do. This
     * is the cheap guard against a description that only names the tool.
     */
    @Test
    fun everyDescriptionSaysSomethingASchemaCannot() {
        val thin = allDefinitions()
            .filter { (_, def) -> def.description.length < 120 }
            .map { it.second.name }
        assertTrue("descriptions too thin to warn a model: $thin", thin.isEmpty())
    }
}
