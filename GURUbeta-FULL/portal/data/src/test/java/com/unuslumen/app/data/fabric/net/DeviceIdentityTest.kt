// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.fabric.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Identity, which is the rule that decides whether a network map is an inventory or a mess.
 *
 * WHY THESE MATTER MORE THAN ANY OTHER TEST IN THE FEATURE. Get identity wrong towards splitting and
 * every DHCP renewal grows the map by a ghost. Get it wrong towards merging and a printer inherits
 * a fridge's history and the map starts asserting things that never happened. Both failures are
 * silent, both look like the map working, and both are decided by the functions below.
 */
class DeviceIdentityTest {

    // ------------------------------------------------------------------
    // MAC address normalisation
    // ------------------------------------------------------------------

    @Test
    fun `the same MAC in three spellings is one identity`() {
        // A device reports its address however its protocol happens to spell it. Treating those
        // as different devices is the defect this whole function exists to prevent.
        val a = DeviceIdentity.of("192.0.2.10", "94:B3:F7:90:92:81", null)
        val b = DeviceIdentity.of("192.0.2.11", "94-b3-f7-90-92-81", null)
        val c = DeviceIdentity.of("192.0.2.12", "94b3f7909281", null)

        assertEquals(a.id, b.id)
        assertEquals(b.id, c.id)
        assertEquals("mac:94:b3:f7:90:92:81", a.id)
    }

    @Test
    fun `a normalised MAC is lowercase and colon separated`() {
        assertEquals("aa:bb:cc:dd:ee:ff", DeviceIdentity.normaliseMac("AA-BB-CC-DD-EE-FF"))
    }

    @Test
    fun `a value that is not six octets is refused rather than stored`() {
        assertNull(DeviceIdentity.normaliseMac("not a mac"))
        assertNull(DeviceIdentity.normaliseMac("94:b3:f7"))
        assertNull(DeviceIdentity.normaliseMac(""))
    }

    // ------------------------------------------------------------------
    // Randomised addresses, which is the trap that would fill a map with ghosts
    // ------------------------------------------------------------------

    @Test
    fun `a burned-in address is recognised as hardware`() {
        // The locally-administered bit is clear on a manufacturer-assigned address.
        assertTrue(DeviceIdentity.isHardwareAddress("94:b3:f7:90:92:81"))
        assertTrue(DeviceIdentity.isHardwareAddress("00:1a:2b:3c:4d:5e"))
    }

    @Test
    fun `a randomised address is not treated as hardware identity`() {
        // Bit 1 of the first octet set means locally administered, which is what every privacy
        // feature in modern iOS and Android sets. Trusting one would create a new device every
        // time the owner walked back into the house.
        assertFalse(DeviceIdentity.isHardwareAddress("92:b3:f7:90:92:81"))
        assertFalse(DeviceIdentity.isHardwareAddress("da:1a:2b:3c:4d:5e"))
    }

    @Test
    fun `a randomised address falls back to a name rather than becoming an identity`() {
        val identity = DeviceIdentity.of("192.0.2.20", "92:b3:f7:90:92:81", "Stevens-iPhone")

        assertEquals(DeviceIdentity.Basis.NAME, identity.basis)
        assertEquals("name:stevens-iphone", identity.id)
        // The randomised address is still carried, so it is reported rather than thrown away.
        assertEquals("92:b3:f7:90:92:81", identity.mac)
        assertTrue(identity.reason.contains("randomised"))
    }

    @Test
    fun `a randomised address with no name falls back to the address`() {
        val identity = DeviceIdentity.of("192.0.2.21", "92:b3:f7:90:92:81", null)
        assertEquals(DeviceIdentity.Basis.ADDRESS, identity.basis)
        assertEquals("address:192.0.2.21", identity.id)
    }

    // ------------------------------------------------------------------
    // The fallback order
    // ------------------------------------------------------------------

    @Test
    fun `a hardware address beats a name`() {
        val identity = DeviceIdentity.of("192.0.2.30", "94:b3:f7:90:92:81", "some-name")
        assertEquals(DeviceIdentity.Basis.MAC, identity.basis)
        assertEquals("mac:94:b3:f7:90:92:81", identity.id)
    }

    @Test
    fun `a name beats a bare address`() {
        val identity = DeviceIdentity.of("192.0.2.31", null, "Office-Printer")
        assertEquals(DeviceIdentity.Basis.NAME, identity.basis)
        // Names are lowercased so two spellings of one device are one row.
        assertEquals("name:office-printer", identity.id)
    }

    @Test
    fun `no evidence at all still produces an identity, marked as the weakest`() {
        val identity = DeviceIdentity.of("192.0.2.32", null, null)
        assertEquals(DeviceIdentity.Basis.ADDRESS, identity.basis)
        // The reason is a sentence a person can read, not a code.
        assertTrue(identity.reason.contains("will appear as a second entry"))
    }

    @Test
    fun `a blank name is treated as no name`() {
        val identity = DeviceIdentity.of("192.0.2.33", null, "   ")
        assertEquals(DeviceIdentity.Basis.ADDRESS, identity.basis)
    }

    // ------------------------------------------------------------------
    // Whether two identities are certainly one device
    // ------------------------------------------------------------------

    @Test
    fun `two sightings with the same hardware address are the same device`() {
        val a = DeviceIdentity.of("192.0.2.40", "94:b3:f7:90:92:81", null)
        val b = DeviceIdentity.of("192.0.2.99", "94:B3:F7:90:92:81", null)
        assertTrue(DeviceIdentity.sameDevice(a, b))
    }

    @Test
    fun `the same name is the same device`() {
        val a = DeviceIdentity.of("192.0.2.41", null, "nas")
        val b = DeviceIdentity.of("192.0.2.42", null, "NAS")
        assertTrue(DeviceIdentity.sameDevice(a, b))
    }

    @Test
    fun `the same address is NOT proof of the same device`() {
        // A lease moves between devices. Treating an address match as identity is what gives a
        // new device the previous tenant's history, and the whole point of the ordering is that
        // this case is explicitly refused.
        val a = DeviceIdentity.of("192.0.2.43", null, null)
        val b = DeviceIdentity.of("192.0.2.43", null, null)
        assertFalse(DeviceIdentity.sameDevice(a, b))
    }

    @Test
    fun `a strong identity never matches a weak one`() {
        val strong = DeviceIdentity.of("192.0.2.44", "94:b3:f7:90:92:81", null)
        val weak = DeviceIdentity.of("192.0.2.44", null, null)
        assertFalse(DeviceIdentity.sameDevice(strong, weak))
    }

    @Test
    fun `an identity always carries a reason`() {
        val cases = listOf(
            DeviceIdentity.of("192.0.2.50", "94:b3:f7:90:92:81", null),
            DeviceIdentity.of("192.0.2.51", "92:00:00:00:00:01", "phone"),
            DeviceIdentity.of("192.0.2.52", null, null),
        )
        cases.forEach { assertNotNull(it.reason) }
        cases.forEach { assertTrue(it.reason.isNotBlank()) }
    }
}
