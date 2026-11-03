// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.fabric.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The identification rules, which are the part of a scanner that decides what a person sees.
 *
 * WHY THESE ARE WORTH MORE THAN A SOCKET TEST. A probe that opens a socket either works or throws,
 * and there is nothing subtle to get wrong. A classification rule has branches, and one wrong
 * branch calls somebody's printer a television. These prove the branches.
 */
class DeviceIdentifierTest {

    // ------------------------------------------------------------------
    // SNMP enterprise numbers
    // ------------------------------------------------------------------

    @Test
    fun `a known enterprise number names the vendor`() {
        assertEquals(
            "Brother",
            DeviceIdentifier.vendorFromSysObjectId("1.3.6.1.4.1.2435.2.3.9.1"),
        )
        assertEquals(
            "Hewlett-Packard",
            DeviceIdentifier.vendorFromSysObjectId("1.3.6.1.4.1.11.2.3.9.1"),
        )
        assertEquals(
            "MikroTik",
            DeviceIdentifier.vendorFromSysObjectId("1.3.6.1.4.1.14988.1"),
        )
    }

    @Test
    fun `an unknown enterprise number names no vendor rather than guessing one`() {
        // A false vendor sends the reader to the wrong manual, so silence is the correct answer.
        assertNull(DeviceIdentifier.vendorFromSysObjectId("1.3.6.1.4.1.999999.1"))
    }

    @Test
    fun `an OID that is not an enterprise number names no vendor`() {
        assertNull(DeviceIdentifier.vendorFromSysObjectId("1.3.6.1.2.1.1.2.0"))
        assertNull(DeviceIdentifier.vendorFromSysObjectId(""))
        assertNull(DeviceIdentifier.vendorFromSysObjectId("not an oid"))
    }

    // ------------------------------------------------------------------
    // sysServices, which is the cheapest router-versus-printer distinction
    // ------------------------------------------------------------------

    @Test
    fun `a device claiming layer three forwarding is a router`() {
        // Bit 3 (value 4) is the network layer, which only a forwarding device claims.
        assertEquals(DeviceClass.ROUTER, DeviceIdentifier.classFromSysServices(4))
        assertEquals(DeviceClass.ROUTER, DeviceIdentifier.classFromSysServices(78))
    }

    @Test
    fun `a device claiming only layer two forwarding is a bridge`() {
        assertEquals(DeviceClass.ACCESS_POINT, DeviceIdentifier.classFromSysServices(2))
    }

    @Test
    fun `an application-only device claims no forwarding role`() {
        // A printer typically declares application bit 7 only, which says nothing about routing.
        assertNull(DeviceIdentifier.classFromSysServices(64))
        assertNull(DeviceIdentifier.classFromSysServices(72))
    }

    // ------------------------------------------------------------------
    // SNMP merging
    // ------------------------------------------------------------------

    @Test
    fun `a printer recognised from its own description is a printer even when it claims services`() {
        val identified = DeviceIdentifier.fromSnmp(
            mapOf(
                "1.3.6.1.2.1.1.1.0" to "HP ETHERNET MULTI-ENVIRONMENT, LaserJet 4000",
                "1.3.6.1.2.1.1.2.0" to "1.3.6.1.4.1.11.2.3.9.1",
                "1.3.6.1.2.1.1.5.0" to "office-printer",
                // A printer that also claims the application bit must not become a router.
                "1.3.6.1.2.1.1.7.0" to "72",
            ),
        )

        assertEquals(DeviceClass.PRINTER, identified.deviceClass)
        assertEquals("Hewlett-Packard", identified.vendor)
        assertEquals("office-printer", identified.name)
    }

    @Test
    fun `an SNMP answer with only a description still reports what it said`() {
        val identified = DeviceIdentifier.fromSnmp(
            mapOf("1.3.6.1.2.1.1.1.0" to "Some Unbranded Device"),
        )

        assertEquals("Some Unbranded Device", identified.name)
        assertNull(identified.vendor)
        // It is still evidence, so a host that answered SNMP is never reported as anonymous.
        assertTrue(identified.evidence.isNotEmpty())
        assertEquals(EvidenceKind.DECLARED, identified.evidence.first().kind)
    }

    @Test
    fun `an empty SNMP answer names nothing and does not throw`() {
        val identified = DeviceIdentifier.fromSnmp(emptyMap())
        assertNull(identified.name)
        assertNull(identified.deviceClass)
    }

    // ------------------------------------------------------------------
    // DHCP hostnames, which is the only route to an appliance that answers nothing
    // ------------------------------------------------------------------

    @Test
    fun `a washer named by the router is an appliance`() {
        val identified = DeviceIdentifier.fromHostname("Samsung-Washer")
        assertEquals(DeviceClass.APPLIANCE, identified.deviceClass)
        assertEquals("Samsung", identified.vendor)
        assertEquals("Samsung-Washer", identified.name)
    }

    @Test
    fun `a fridge named by the router is an appliance`() {
        assertEquals(DeviceClass.APPLIANCE, DeviceIdentifier.fromHostname("LG-Fridge").deviceClass)
    }

    @Test
    fun `a hostname is derived evidence rather than a declaration`() {
        // The device never said this itself: the router did. The distinction is what stops a
        // hostname being presented with the same weight as a device's own model string.
        val identified = DeviceIdentifier.fromHostname("shellyplug-s-1A2B3C")
        assertEquals(EvidenceKind.DERIVED, identified.evidence.first().kind)
        assertEquals(DeviceClass.APPLIANCE, identified.deviceClass)
        assertEquals("Shelly", identified.vendor)
    }

    @Test
    fun `a desktop hostname is named and called a computer`() {
        // Windows names its machines DESKTOP-<suffix>, which is a real signal rather than noise:
        // it narrows a host to a computer and the name is kept verbatim either way.
        val identified = DeviceIdentifier.fromHostname("DESKTOP-4F2K9L")
        assertEquals("DESKTOP-4F2K9L", identified.name)
        assertEquals(DeviceClass.COMPUTER, identified.deviceClass)
    }

    @Test
    fun `an unremarkable hostname keeps its name and claims no class`() {
        val identified = DeviceIdentifier.fromHostname("a4f9c2")
        assertEquals("a4f9c2", identified.name)
        assertNull(identified.deviceClass)
    }

    @Test
    fun `a blank hostname identifies nothing`() {
        assertNull(DeviceIdentifier.fromHostname("   ").name)
    }

    // ------------------------------------------------------------------
    // Ports, the last resort
    // ------------------------------------------------------------------

    @Test
    fun `printer languages decide a printer`() {
        assertEquals(DeviceClass.PRINTER, DeviceIdentifier.classFromPorts(listOf(631)))
        assertEquals(DeviceClass.PRINTER, DeviceIdentifier.classFromPorts(listOf(9100)))
        assertEquals(DeviceClass.PRINTER, DeviceIdentifier.classFromPorts(listOf(515)))
    }

    @Test
    fun `a bare web port decides nothing`() {
        // Port 80 is on a router, a printer, a television and a phone. Calling it any one of
        // them is the guess that makes a scanner untrustworthy.
        assertNull(DeviceIdentifier.classFromPorts(listOf(80)))
        assertNull(DeviceIdentifier.classFromPorts(listOf(443)))
        assertNull(DeviceIdentifier.classFromPorts(emptyList()))
    }

    @Test
    fun `DIAL plus adb is a streaming box rather than a named make`() {
        assertEquals(
            DeviceClass.SET_TOP_BOX,
            DeviceIdentifier.classFromPorts(listOf(8009, 5555)),
        )
    }

    @Test
    fun `a printer opening a web port is still a printer`() {
        assertEquals(
            DeviceClass.PRINTER,
            DeviceIdentifier.classFromPorts(listOf(80, 631, 9100)),
        )
    }

    // ------------------------------------------------------------------
    // Merging several protocols into one device
    // ------------------------------------------------------------------

    @Test
    fun `a declared name beats a hostname and a port guess`() {
        val merged = DeviceMerge.merge(
            address = "192.0.2.40",
            reports = listOf(
                Identified(
                    name = "guessed-from-hostname",
                    deviceClass = DeviceClass.APPLIANCE,
                    evidence = listOf(Evidence("DHCP", "named", EvidenceKind.DERIVED)),
                ),
                Identified(
                    name = "Office LaserJet",
                    vendor = "Hewlett-Packard",
                    deviceClass = DeviceClass.PRINTER,
                    evidence = listOf(Evidence("SNMP", "sysName", EvidenceKind.DECLARED)),
                ),
            ),
        )

        assertEquals("Office LaserJet", merged.name)
        assertEquals("Hewlett-Packard", merged.vendor)
        assertEquals(DeviceClass.PRINTER, merged.deviceClass)
    }

    @Test
    fun `an address with no reports is still a device that is there`() {
        // Hiding a host because nothing named it would be the lie this whole file exists to
        // prevent: the device answered, it just had nothing to say.
        val merged = DeviceMerge.merge("192.0.2.41", emptyList())
        assertEquals("192.0.2.41", merged.displayName)
        assertEquals(DeviceClass.UNKNOWN, merged.deviceClass)
    }

    @Test
    fun `a vendor with no name still produces a readable label`() {
        val merged = DeviceMerge.merge(
            "192.0.2.42",
            listOf(Identified(vendor = "Brother", evidence = listOf(Evidence("SNMP", "vendor", EvidenceKind.DECLARED)))),
        )
        assertEquals("Brother device", merged.displayName)
    }

    @Test
    fun `evidence from every protocol is kept rather than only the winner`() {
        // Provenance is what lets a person tell how a device was found, and a merged record that
        // keeps only the winning source cannot be debugged when a device appears mislabelled.
        val merged = DeviceMerge.merge(
            "192.0.2.43",
            listOf(
                Identified(name = "tv", evidence = listOf(Evidence("SSDP", "answered", EvidenceKind.DECLARED))),
                Identified(evidence = listOf(Evidence("SNMP", "sysDescr", EvidenceKind.DECLARED))),
                Identified(evidence = listOf(Evidence("DHCP", "named", EvidenceKind.DERIVED))),
            ),
        )

        assertEquals(3, merged.evidence.size)
        assertEquals(
            listOf("SSDP", "SNMP", "DHCP"),
            merged.evidence.map { it.source },
        )
    }

    // ------------------------------------------------------------------
    // Access point names, which is all a neighbour's router can tell you
    // ------------------------------------------------------------------

    @Test
    fun `a printer advertising its own setup network is recognised`() {
        assertEquals(
            DeviceClass.PRINTER,
            DeviceIdentifier.classFromAccessPointName("DIRECT-5F-HP-OfficeJet"),
        )
    }

    @Test
    fun `an ordinary network name claims nothing`() {
        assertNull(DeviceIdentifier.classFromAccessPointName("HOME-2A4F"))
    }
}
