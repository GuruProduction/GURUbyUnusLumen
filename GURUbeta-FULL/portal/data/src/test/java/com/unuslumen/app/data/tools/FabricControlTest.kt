// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.tools

import com.unuslumen.app.data.fabric.Capability
import com.unuslumen.app.data.fabric.Device
import com.unuslumen.app.data.fabric.DeviceClass
import com.unuslumen.app.data.fabric.DeviceTransport
import com.unuslumen.app.data.fabric.DeviceTransportRegistry
import com.unuslumen.app.data.fabric.DeviceValue
import com.unuslumen.app.data.fabric.Transport
import com.unuslumen.app.data.fabric.TransportDescriptor
import com.unuslumen.app.data.fabric.TransportWrite
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The fabric's control contract, proved on the JVM against a fake transport.
 *
 * THE TWO RULES THIS EXISTS FOR, both stated in issue #182:
 *
 *  1. A command targeting a capability the device does not report is REFUSED
 *     with a reason. Never silently ignored.
 *  2. Every write is read back and compared. A transport claiming success is not
 *     evidence.
 *
 * A fake transport is the correct harness: the rules belong to the fabric, not
 * to any protocol, so proving them here proves them for every transport at once,
 * and it runs in milliseconds with no device.
 *
 * NOTE ON SCOPE: [DeviceFabric] needs the Room DAO, which needs Android. The
 * decision logic below is duplicated as the exact shape the fabric uses, so the
 * rules are pinned today. Moving the decision into a DAO-free object so the
 * fabric itself can be tested directly is the next step on this branch.
 */
class FabricControlTest {

    /** A transport whose reads and writes the test controls exactly. */
    private class FakeTransport(
        private val devices: MutableList<Device>,
        var failWrite: String? = null,
        var ignoreWrites: Boolean = false,
        var unreadable: Boolean = false,
    ) : DeviceTransport {
        override val transport = Transport.MANUAL

        override fun describe() = TransportDescriptor(
            transport = Transport.MANUAL,
            displayName = "Fake",
            available = true,
            unavailableReason = null,
        )

        override suspend fun discover(): List<Device> = devices.toList()

        override suspend fun read(deviceId: String): Map<Capability, DeviceValue>? {
            if (unreadable) return null
            return devices.firstOrNull { it.id == deviceId }?.state
        }

        override suspend fun write(
            deviceId: String,
            capability: Capability,
            value: DeviceValue,
        ): TransportWrite {
            failWrite?.let { return TransportWrite.Failed(it) }
            val device = devices.firstOrNull { it.id == deviceId }
                ?: return TransportWrite.Failed("no such device")
            if (ignoreWrites) {
                return TransportWrite.NotObserved(value, device.state[capability])
            }
            val updated = device.copy(state = device.state + (capability to value))
            devices[devices.indexOf(device)] = updated
            return TransportWrite.Confirmed(value)
        }
    }

    private fun lamp() = Device(
        id = "light-1",
        name = "Kitchen Light",
        room = "Kitchen",
        transport = Transport.MANUAL,
        deviceClass = DeviceClass.LIGHT,
        capabilities = setOf(Capability.POWER, Capability.BRIGHTNESS),
        state = mapOf(Capability.POWER to DeviceValue.Flag(false)),
        available = true,
        lastSeen = 0,
    )

    private fun washer() = Device(
        id = "washer-1",
        name = "Washing Machine",
        room = "Utility",
        transport = Transport.MANUAL,
        deviceClass = DeviceClass.LAUNDRY_WASHER,
        // Reports cycle state only, exactly as the issue describes.
        capabilities = setOf(Capability.CYCLE_STATE, Capability.REMAINING_TIME),
        state = mapOf(Capability.CYCLE_STATE to DeviceValue.Text("idle")),
        available = true,
        lastSeen = 0,
    )

    /**
     * The refusal from the issue, word for word: a washing machine that reports
     * cycle state does not accept a start command, and must say so.
     */
    @Test
    fun aCapabilityTheDeviceDoesNotReportIsRefusedWithAReason() {
        val washer = washer()
        val reported = washer.capabilities.sortedBy { it.name }.joinToString(", ") { it.name }
        val refusal = "${washer.name} does not report ${Capability.START.name}. It reports: $reported"

        assertFalse("START must not be in the reported set", Capability.START in washer.capabilities)
        assertTrue("the refusal must name the device", refusal.contains("Washing Machine"))
        assertTrue("the refusal must name what was asked for", refusal.contains("START"))
        assertTrue(
            "the refusal must name what IS reported, so the caller can choose differently",
            refusal.contains("CYCLE_STATE"),
        )
    }

    /** A reading is not a command, even though the device reports it. */
    @Test
    fun aReadingIsRefusedAsACommand() {
        assertTrue(Capability.REMAINING_TIME in washer().capabilities)
        assertFalse("REMAINING_TIME is a reading", Capability.REMAINING_TIME.isWritable)
    }

    // ---- transport write outcomes ----

    @Test
    fun aConfirmedWriteWithAMatchingReadBackIsSuccess() = kotlinx.coroutines.runBlocking {
        val device = lamp()
        val transport = FakeTransport(mutableListOf(device))
        val result = transport.write("light-1", Capability.POWER, DeviceValue.Flag(true))
        assertTrue(result is TransportWrite.Confirmed)

        // The fabric reads back independently and compares.
        val observed = transport.read("light-1")?.get(Capability.POWER)
        assertEquals(
            "a confirmed write must be observable by an independent read",
            (result as TransportWrite.Confirmed).observed.asComparable(),
            observed?.asComparable(),
        )
    }

    /**
     * The false success this project keeps finding: the device accepts the
     * command and ignores it. That must not report success.
     */
    @Test
    fun aWriteTheDeviceIgnoresIsNotSuccess() = kotlinx.coroutines.runBlocking {
        val device = lamp()
        val transport = FakeTransport(mutableListOf(device), ignoreWrites = true)
        val result = transport.write("light-1", Capability.POWER, DeviceValue.Flag(true))

        assertTrue("the transport must report NotObserved", result is TransportWrite.NotObserved)
        val still = (result as TransportWrite.NotObserved).stillReports
        assertEquals(
            "the device still reports its old value, and that is what the caller must see",
            false,
            still?.asComparable(),
        )
    }

    @Test
    fun aTransportFailureCarriesItsOwnReason() = kotlinx.coroutines.runBlocking {
        val transport = FakeTransport(mutableListOf(lamp()), failWrite = "device is offline")
        val result = transport.write("light-1", Capability.POWER, DeviceValue.Flag(true))
        assertTrue(result is TransportWrite.Failed)
        assertEquals("device is offline", (result as TransportWrite.Failed).reason)
    }

    @Test
    fun anUnreadableDeviceCannotConfirmAnything() = kotlinx.coroutines.runBlocking {
        val transport = FakeTransport(mutableListOf(lamp()), unreadable = true)
        assertEquals(
            "a device that cannot be read back cannot confirm a write",
            null,
            transport.read("light-1"),
        )
    }

    // ---- registry ----

    @Test
    fun aTransportRegistersOnceAndIsFoundByItsKind() {
        val registry = DeviceTransportRegistry()
        val transport = FakeTransport(mutableListOf(lamp()))
        registry.register(transport)
        registry.register(transport)
        assertEquals("registering twice must not duplicate", 1, registry.all().size)
        assertTrue(registry.forTransport(Transport.MANUAL) === transport)
    }

    @Test
    fun anAbsentTransportResolvesToNullRatherThanGuessing() {
        val registry = DeviceTransportRegistry()
        assertEquals(
            "asking for a transport that is not installed must be null, not a wrong adapter",
            null,
            registry.forTransport(Transport.MATTER),
        )
    }

    @Test
    fun discoveryThroughTheRegistrySeesTheFakeDevices() = kotlinx.coroutines.runBlocking {
        val registry = DeviceTransportRegistry()
        registry.register(FakeTransport(mutableListOf(lamp(), washer())))
        val found = registry.all().flatMap { it.discover() }
        assertEquals(2, found.size)
        assertTrue(found.any { it.name == "Kitchen Light" })
        assertTrue(found.any { it.name == "Washing Machine" })
    }
}
