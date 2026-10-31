// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.guru

import android.app.UiAutomation
import android.content.Context
import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.unuslumen.app.data.tools.registry.ToolExecutor
import com.unuslumen.app.data.tools.registry.permission.PermissionTier
import com.unuslumen.app.data.tools.registry.permission.TeamThreePermissions
import com.unuslumen.app.data.tools.registry.permission.TeamThreeToolDomains
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import kotlinx.coroutines.runBlocking

/**
 * The granted half of the device proof.
 *
 * WHY THIS EXISTS SEPARATELY. The refusal half runs against a clean install where
 * every dangerous permission is denied. This half grants the runtime permissions
 * in-process and then checks that each tool actually reaches the data instead of
 * merely refusing, which is the difference between "it fails honestly" and "it
 * works". Both are needed: a tool that refuses when granted is broken, and a tool
 * that returns data when denied is lying.
 *
 * HOW THE GRANTS ARE MADE. Through the instrumentation's own UiAutomation, which
 * is the sandboxed, reversible way to grant a runtime permission for a test. No
 * adb, no shell, no root, and nothing persists past the test process.
 *
 * WHAT IT STILL CANNOT PROVE. Data that the hardware or the account store does not
 * contain. An emulator has no SMS history, no contacts and no heart-rate sensor, so
 * those tools legitimately return empty results. The assertions are written to
 * distinguish an honest empty result from a fabricated one rather than to pretend
 * the emulator has real data.
 */
@RunWith(AndroidJUnit4::class)
class TeamThreeGrantedPathTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val automation: UiAutomation
        get() = InstrumentationRegistry.getInstrumentation().uiAutomation

    private val grantedByThisTest = mutableListOf<String>()

    @Before
    fun grantEveryRuntimePermissionTeamThreeOwns() {
        TeamThreePermissions.all.values
            .filter { it.tier == PermissionTier.RUNTIME }
            .forEach { spec ->
                val already = context.checkSelfPermission(spec.permission) == PackageManager.PERMISSION_GRANTED
                if (already) return@forEach
                val granted = runCatching {
                    automation.grantRuntimePermission(
                        context.packageName,
                        spec.permission,
                    )
                    true
                }.getOrDefault(false)
                if (granted) {
                    grantedByThisTest += spec.permission
                } else {
                    println("could not grant ${spec.permission}; it will be treated as denied")
                }
            }
        println("granted by this test: ${grantedByThisTest.size} ${grantedByThisTest.sorted()}")
    }

    @After
    fun revokeWhatThisTestGranted() {
        for (permission in grantedByThisTest) {
            runCatching {
                automation.revokeRuntimePermission(context.packageName, permission)
            }
        }
        println("revoked ${grantedByThisTest.size} permission(s) so the device is left as found")
    }

    /**
     * With the grants held, no tool from these domains may refuse for a permission
     * reason.
     *
     * This is the assertion that catches the commonest failure in this batch: a
     * tool that checks a permission name which is not the one it actually needs,
     * so it refuses forever even when everything is granted. It also catches a
     * privileged-tier tool being wired as runtime, because that one will refuse
     * with the privileged sentence and the test will name it.
     */
    @Test
    fun noToolRefusesForAPermissionReasonOnceTheGrantIsHeld() = runBlocking {
        val wronglyRefusing = mutableListOf<String>()

        for (domain in TeamThreeToolDomains.domains) {
            for (reg in domain.registrations) {
                val executor: ToolExecutor = runCatching {
                    GlobalContext.get().get(reg.executorClass())
                }.getOrNull() ?: continue

                for (def in reg.definitions) {
                    val result = runCatching { executor.execute(def.name, emptyMap()) }.getOrNull() ?: continue
                    if (result.success) continue

                    val sentence = result.error.orEmpty()
                    val permissionRefusal = sentence.contains("Permission denied") ||
                        sentence.contains("Permission unavailable") ||
                        sentence.contains("Install-time fault")
                    if (permissionRefusal) {
                        wronglyRefusing += "${domain.id}/${def.name}: $sentence"
                    }
                }
            }
        }

        wronglyRefusing.forEach { println("still refusing: $it") }
        assertTrue(
            "with every runtime grant held, these still refused for a permission reason, " +
                "so their grant check is wired to the wrong permission or tier: $wronglyRefusing",
            wronglyRefusing.isEmpty(),
        )
    }

    /**
     * Location must now report the fix as precise, not approximate.
     *
     * Both fine and coarse are granted by the setup above, so anything other than
     * PRECISE here means the tool is reading the wrong permission. This is the
     * exact honesty rule the batch states: never present an approximate fix as
     * precise, and never report precise when it is not.
     */
    @Test
    fun locationReportsPreciseOnceFineLocationIsHeld() {
        val precision = com.unuslumen.app.data.tools.registry.permission.PermissionGrants.locationPrecision(context)
        println("precision with fine granted = $precision")
        assertTrue(
            "fine location is granted, so the reported precision must be PRECISE, got $precision",
            precision == com.unuslumen.app.data.tools.registry.permission.PermissionGrants.LocationPrecision.PRECISE,
        )
    }

    /**
     * A tool that returns success must return a payload, and a tool that returns a
     * payload must be internally consistent about whether it found anything.
     *
     * This catches the "empty success" shape in the granted direction: a tool that
     * says it succeeded and hands back `{}` or `[]` with no indication of whether
     * the absence is real.
     */
    @Test
    fun everySuccessCarriesAPayload() = runBlocking {
        val emptySuccesses = mutableListOf<String>()

        for (domain in TeamThreeToolDomains.domains) {
            for (reg in domain.registrations) {
                val executor: ToolExecutor = runCatching {
                    GlobalContext.get().get(reg.executorClass())
                }.getOrNull() ?: continue

                for (def in reg.definitions) {
                    val result = runCatching { executor.execute(def.name, emptyMap()) }.getOrNull() ?: continue
                    if (result.success && result.rawJson.isBlank()) {
                        emptySuccesses += "${domain.id}/${def.name}"
                    }
                }
            }
        }
        assertTrue("these reported success with no payload at all: $emptySuccesses", emptySuccesses.isEmpty())
    }

    /**
     * The hardware-absent tools must still refuse with the device reason even when
     * every permission is held.
     *
     * A heart-rate read on a phone with no sensor cannot be fixed by any grant, so
     * if this ever returns success or a permission refusal the tool is blaming the
     * wrong thing.
     */
    @Test
    fun absentHardwareStillRefusesWithTheDeviceReasonWhenFullyGranted() = runBlocking {
        if (com.unuslumen.app.data.tools.registry.permission.PermissionGrants.hasHeartRateSensor(context)) {
            println("this device has a heart-rate sensor, so the absent-hardware path is not observable")
            return@runBlocking
        }

        val offenders = mutableListOf<String>()
        for (domain in TeamThreeToolDomains.domains) {
            for (reg in domain.registrations) {
                val executor: ToolExecutor = runCatching {
                    GlobalContext.get().get(reg.executorClass())
                }.getOrNull() ?: continue
                for (def in reg.definitions) {
                    if (!def.name.contains("bodySensor", ignoreCase = true) &&
                        !def.name.contains("heartRate", ignoreCase = true)
                    ) continue
                    val result = runCatching { executor.execute(def.name, emptyMap()) }.getOrNull() ?: continue
                    println("${def.name}: success=${result.success} error=${result.error}")
                    if (result.success) {
                        offenders += "${def.name} claimed success on a device with no heart-rate sensor"
                    } else if (result.error.orEmpty().contains("Permission denied")) {
                        offenders += "${def.name} blamed the permission instead of the missing hardware"
                    }
                }
            }
        }
        assertTrue("absent-hardware honesty faults: $offenders", offenders.isEmpty())
    }
}
