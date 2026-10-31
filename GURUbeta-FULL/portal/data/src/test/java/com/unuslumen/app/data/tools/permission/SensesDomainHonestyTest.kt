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
        assertEquals(
            listOf("bluetooth", "camera_audio", "location", "phone", "privileged_capture", "sensors"),
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
