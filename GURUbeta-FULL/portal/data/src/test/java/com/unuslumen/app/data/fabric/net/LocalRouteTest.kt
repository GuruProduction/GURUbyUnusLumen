// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.fabric.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

/**
 * The local-versus-internet routing rule. Issue 215.
 *
 * WHY THIS IS THE MOST IMPORTANT TEST IN THE NETWORK LAYER. Every outbound path in this app is
 * injected with a Tor SOCKS proxy, and Tor will not route to a private address. So a local
 * request sent through it fails with a bare 000 that looks exactly like a device that is
 * switched off.
 *
 * That is not theoretical: it lost a real Roku. Discovery found port 8060 with a raw socket,
 * which needs no proxy, then fetched its device-info over HTTP, which went through Tor and died.
 * The set was found and then silently discarded, and the only television that appeared was one
 * that announced itself over SSDP because SSDP never touches HTTP.
 */
class LocalRouteTest {

    /** Private ranges must go direct, because a Tor circuit cannot reach them at all. */
    @Test
    fun privateAddressesGoDirect() {
        for (literal in listOf(
            "192.168.0.115",   // his Roku
            "10.1.2.3",
            "172.16.5.9",
            "172.31.255.254",
            "127.0.0.1",
            "169.254.1.1",     // link-local
            "0.0.0.0",
        )) {
            val address = InetAddress.getByName(literal)
            assertTrue(
                "$literal must be treated as local, so the request goes direct",
                LocalRoute.isLocalAddress(address),
            )
        }
    }

    /** A public address must still go through Tor, because that is the whole point of the proxy. */
    @Test
    fun publicAddressesGoThroughTor() {
        for (literal in listOf("8.8.8.8", "1.1.1.1", "93.184.216.34", "142.250.187.206")) {
            val address = InetAddress.getByName(literal)
            assertFalse("$literal must not be treated as local", LocalRoute.isLocalAddress(address))
            assertEquals(
                "$literal must route through Tor",
                LocalRoute.Route.TOR,
                LocalRoute.routeFor(literal).route,
            )
        }
    }

    /**
     * CGNAT must count as local, because Tailscale and some carriers put peers there.
     *
     * 100.64.0.0/10 is not RFC1918 but it is equally unroutable on the public internet, so
     * sending it to Tor fails in exactly the same silent way.
     */
    @Test
    fun cgnatIsTreatedAsLocal() {
        for (literal in listOf("100.64.0.1", "100.100.100.100", "100.127.255.254")) {
            assertTrue(
                "$literal is CGNAT and must go direct",
                LocalRoute.isLocalAddress(InetAddress.getByName(literal)),
            )
        }
        assertFalse(
            "100.128.0.1 is outside the CGNAT block and is public",
            LocalRoute.isLocalAddress(InetAddress.getByName("100.128.0.1")),
        )
    }

    /** A bare machine name is local, because a name with no dot never leaves the lan. */
    @Test
    fun aBareNameGoesDirect() {
        for (name in listOf("nas", "printer", "laptop")) {
            assertEquals(
                "'$name' has no dot, so it is a machine on this network",
                LocalRoute.Route.DIRECT,
                LocalRoute.routeFor(name).route,
            )
        }
    }

    /** A dot-local name is mDNS, which only ever resolves on the lan. */
    @Test
    fun anMdnsNameGoesDirect() {
        for (name in listOf("roku-1234.local", "appletv.local", "nas.lan", "printer.home")) {
            assertEquals(
                "'$name' is a local name",
                LocalRoute.Route.DIRECT,
                LocalRoute.routeFor(name).route,
            )
        }
    }

    /** A private literal resolves and its own address decides the route. */
    @Test
    fun aPrivateLiteralResolvesAndRoutesDirect() {
        val resolution = LocalRoute.routeFor("192.168.0.115")
        assertEquals(LocalRoute.Route.DIRECT, resolution.route)
        assertEquals("192.168.0.115", resolution.address?.hostAddress)
        assertEquals(null, resolution.problem)
    }

    /** An empty host is refused rather than sent somewhere. */
    @Test
    fun anEmptyHostIsRefused() {
        val resolution = LocalRoute.routeFor("   ")
        assertEquals(LocalRoute.Route.DIRECT, resolution.route)
        assertTrue("the problem must be stated", resolution.problem != null)
    }

    /** Bracketed IPv6 loopback is local, which is how a URL writes it. */
    @Test
    fun bracketedLoopbackIsLocal() {
        assertTrue(LocalRoute.isLocalAddress(InetAddress.getByName("::1")))
        assertEquals(LocalRoute.Route.DIRECT, LocalRoute.routeFor("[::1]").route)
    }

    /**
     * The four failure kinds must be distinguishable, because they mean different things.
     *
     * A proxy refusal and an offline device produced the same empty answer before this, which is
     * how a working television looked broken.
     */
    @Test
    fun theFailureKindsAreDistinct() {
        val kinds = LocalRoute.Failure.entries.map { it.name }
        assertEquals("four causes, four names", 4, kinds.size)
        assertTrue("a proxy refusal is one", "PROXY_REFUSED" in kinds)
        assertTrue("an unreachable host is another", "HOST_UNREACHABLE" in kinds)
        assertTrue("a closed port is another", "PORT_CLOSED" in kinds)
        assertTrue("and a failed lookup is the fourth", "DNS_FAILED" in kinds)
    }

    /** A local fetch must refuse a public address rather than reaching the internet. */
    @Test
    fun aLocalFetchRefusesTheInternet() = kotlinx.coroutines.runBlocking {
        val result = LocalRoute.fetchLocal("http://8.8.8.8/", timeoutMillis = 500)
        assertTrue("a public host must not be fetched by a local tool", result.failure != null)
        assertEquals("", result.body)
    }
}
