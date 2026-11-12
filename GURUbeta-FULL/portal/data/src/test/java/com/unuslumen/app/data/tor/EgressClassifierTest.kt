// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.tor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.UnknownHostException

/**
 * Holds the routing table for issue #215.
 *
 * WHAT WAS BROKEN. Every outbound path was handed Tor's SOCKS proxy, and a
 * private address cannot be reached over Tor at all. The television at
 * 192.168.0.115 answered on port 8060 the whole time; `curl` through the proxy
 * returned exit 97 (CURLE_PROXY) and `httpGet` returned "Tor not running", both
 * of which read like a dead device. Discovery could find every host on the
 * network and then nothing could talk to any of them.
 *
 * WHY THESE TESTS ARE PURE. The decision is a string-and-address function, so
 * it can be proved here in seconds rather than on a device. The device proof
 * that remains is the real network call, which the release test covers.
 */
class EgressClassifierTest {

    private fun route(host: String?): EgressClassifier.Route =
        EgressClassifier.routeForHost(host)

    // -----------------------------------------------------------------
    // The device itself
    // -----------------------------------------------------------------

    @Test
    fun loopbackIsDirect() {
        assertEquals(EgressClassifier.Route.DIRECT, route("127.0.0.1"))
        assertEquals(EgressClassifier.Route.DIRECT, route("localhost"))
        assertEquals(EgressClassifier.Route.DIRECT, route("::1"))
        assertEquals(EgressClassifier.Route.DIRECT, route("[::1]"))
    }

    @Test
    fun theDeviceOwnLanAddressIsDirect() {
        // The address GURU's own interface reported on his network. A service
        // the app hosts for itself must never be asked to travel through Tor.
        assertEquals(EgressClassifier.Route.DIRECT, route("192.168.0.179"))
    }

    // -----------------------------------------------------------------
    // The local network
    // -----------------------------------------------------------------

    @Test
    fun theTelevisionFromTheIssueIsDirect() {
        // The exact host in #215 that answered 200 with --noproxy '*' and 000
        // through the proxy.
        assertEquals(
            "192.168.0.115 is the Roku that proved this bug. It must route direct.",
            EgressClassifier.Route.DIRECT,
            route("192.168.0.115"),
        )
    }

    @Test
    fun everyRfc1918RangeIsDirect() {
        assertEquals(EgressClassifier.Route.DIRECT, route("10.0.0.5"))
        assertEquals(EgressClassifier.Route.DIRECT, route("10.255.255.254"))
        assertEquals(EgressClassifier.Route.DIRECT, route("172.16.0.1"))
        assertEquals(EgressClassifier.Route.DIRECT, route("172.31.255.254"))
        assertEquals(EgressClassifier.Route.DIRECT, route("192.168.1.20"))
        assertEquals(EgressClassifier.Route.DIRECT, route("192.168.255.255"))
    }

    @Test
    fun theAddressJustOutsideThePrivateRangeIsNotLocal() {
        // 172.32 is public. A loose prefix test on "172." would have swallowed
        // the whole /8 and sent real internet traffic direct.
        assertEquals(EgressClassifier.Route.TOR, route("172.32.0.1"))
        assertEquals(EgressClassifier.Route.TOR, route("172.15.0.1"))
    }

    @Test
    fun linkLocalIsDirect() {
        // The 169.254 range is what a device picks when DHCP has not answered.
        assertEquals(EgressClassifier.Route.DIRECT, route("169.254.10.20"))
    }

    @Test
    fun carrierGradeNatIsDirect() {
        // Tailscale hands out 100.64/10 and so do several carriers. Java does
        // not consider this site-local, so it needed an explicit rule.
        assertEquals(EgressClassifier.Route.DIRECT, route("100.64.0.1"))
        assertEquals(EgressClassifier.Route.DIRECT, route("100.127.255.254"))
        assertEquals("100.128 is public", EgressClassifier.Route.TOR, route("100.128.0.1"))
        assertEquals("100.63 is public", EgressClassifier.Route.TOR, route("100.63.0.1"))
    }

    @Test
    fun ipv6UniqueLocalIsDirect() {
        assertEquals(EgressClassifier.Route.DIRECT, route("fd00::1"))
        assertEquals(EgressClassifier.Route.DIRECT, route("fc00::1"))
        assertEquals(EgressClassifier.Route.DIRECT, route("fe80::1"))
    }

    // -----------------------------------------------------------------
    // Names
    // -----------------------------------------------------------------

    @Test
    fun mdnsNamesAreDirect() {
        // socks5h resolves at the exit node, so a .local name never even gets
        // an address before Tor is asked to CONNECT to it.
        assertEquals(EgressClassifier.Route.DIRECT, route("roku.local"))
        assertEquals(EgressClassifier.Route.DIRECT, route("printer.local"))
        assertEquals(EgressClassifier.Route.DIRECT, route("router.lan"))
        assertEquals(EgressClassifier.Route.DIRECT, route("nas.home"))
        assertEquals(EgressClassifier.Route.DIRECT, route("desk.home.arpa"))
        assertEquals(EgressClassifier.Route.DIRECT, route("host.docker.internal"))
    }

    @Test
    fun aBareHostnameWithNoDotIsDirect() {
        // A single label cannot be resolved by public DNS. It is a neighbour or
        // a search-domain name, and both mean this network.
        assertEquals(EgressClassifier.Route.DIRECT, route("roku"))
        assertEquals(EgressClassifier.Route.DIRECT, route("synology"))
    }

    // -----------------------------------------------------------------
    // The public internet
    // -----------------------------------------------------------------

    @Test
    fun publicAddressesRideTor() {
        assertEquals(EgressClassifier.Route.TOR, route("1.1.1.1"))
        assertEquals(EgressClassifier.Route.TOR, route("8.8.8.8"))
        assertEquals(EgressClassifier.Route.TOR, route("142.250.185.78"))
        assertEquals(EgressClassifier.Route.TOR, route("example.com"))
        assertEquals(EgressClassifier.Route.TOR, route("api.github.com"))
    }

    @Test
    fun aHostnameThatMerelyLooksNumericIsNotTreatedAsAnAddress() {
        // A prefix test on "10." would classify this as private and send a real
        // public host direct. It must be Tor.
        assertEquals(EgressClassifier.Route.TOR, route("10.example.com"))
        assertEquals(EgressClassifier.Route.TOR, route("192.168.example.com"))
    }

    @Test
    fun garbageAndNullAreTorSoAMistakeCannotLeak() {
        // Failing toward Tor is the safe direction: the worst outcome is a
        // refused request, not an unproxied one.
        assertEquals(EgressClassifier.Route.TOR, route(null))
        assertEquals(EgressClassifier.Route.TOR, route(""))
        assertEquals(EgressClassifier.Route.TOR, route("999.999.999.999"))
        assertEquals(EgressClassifier.Route.TOR, route("not a host"))
    }

    // -----------------------------------------------------------------
    // Resolve-then-classify, the case NO_PROXY cannot cover
    // -----------------------------------------------------------------

    @Test
    fun aPublicLookingNameThatResolvesIntoTheLanIsDirect() {
        // This is why an environment variable was not enough. NO_PROXY compares
        // the literal string the caller typed, so this name matches no list
        // entry and would be handed to the proxy.
        val resolved = EgressClassifier.routeForHostResolved("router.example.com") {
            listOf(InetAddress.getByAddress(byteArrayOf(192.toByte(), 168.toByte(), 0, 1)))
        }
        assertEquals(EgressClassifier.Route.DIRECT, resolved)
    }

    @Test
    fun aPublicLookingNameThatResolvesPublicRidesTor() {
        val resolved = EgressClassifier.routeForHostResolved("example.com") {
            listOf(InetAddress.getByAddress(byteArrayOf(93.toByte(), 184.toByte(), 216.toByte(), 34)))
        }
        assertEquals(EgressClassifier.Route.TOR, resolved)
    }

    @Test
    fun aNameThatFailsToResolveRidesTor() {
        // A name that cannot be resolved is not evidence that it is local.
        val resolved = EgressClassifier.routeForHostResolved("does-not-exist.invalid") {
            throw UnknownHostException("no such host")
        }
        assertEquals(EgressClassifier.Route.TOR, resolved)
    }

    @Test
    fun aNameThatResolvesToNothingRidesTor() {
        val resolved = EgressClassifier.routeForHostResolved("empty.example") { emptyList() }
        assertEquals(EgressClassifier.Route.TOR, resolved)
    }

    @Test
    fun aLocalNameSkipsTheLookupEntirely() {
        var lookups = 0
        val resolved = EgressClassifier.routeForHostResolved("roku.local") {
            lookups++
            emptyList()
        }
        assertEquals(EgressClassifier.Route.DIRECT, resolved)
        assertEquals("a .local name must not be resolved to decide", 0, lookups)
    }

    // -----------------------------------------------------------------
    // Shell commands
    // -----------------------------------------------------------------

    @Test
    fun aCurlToTheTelevisionNeedsNoTor() {
        val command = "curl -s http://192.168.0.115:8060/query/device-info"
        assertFalse(
            "A LAN curl was blocked on Tor being up, which is the whole bug.",
            EgressClassifier.commandNeedsTor(command),
        )
    }

    @Test
    fun aCurlToAPublicHostNeedsTor() {
        assertTrue(EgressClassifier.commandNeedsTor("curl https://example.com"))
        assertTrue(EgressClassifier.commandNeedsTor("wget http://93.184.216.34/file.bin"))
    }

    @Test
    fun aCurlWithNoVisibleAddressNeedsTor() {
        // If the parse cannot see a target it must assume public. A miss here
        // can only make the check stricter, never send traffic clearnet.
        assertTrue(EgressClassifier.commandNeedsTor("curl -s https://api.example.org/x"))
        assertTrue(EgressClassifier.commandNeedsTor("curl --data @body.json"))
    }

    @Test
    fun aMixedCommandNeedsTor() {
        // One public address anywhere in the command means the proxy is needed,
        // and NO_PROXY is what keeps the private hop direct.
        assertTrue(
            EgressClassifier.commandNeedsTor(
                "curl http://192.168.0.115/a && curl https://example.com/b",
            ),
        )
    }

    @Test
    fun aBareHostWithPortIsFound() {
        val hosts = EgressClassifier.hostsInCommand("curl http://roku:8060/query")
        assertEquals(listOf("roku"), hosts)
    }

    @Test
    fun userInfoAndPortAreStrippedFromASchemeUrl() {
        assertEquals(
            listOf("192.168.0.115"),
            EgressClassifier.hostsInCommand("curl http://user:pass@192.168.0.115:8060/x"),
        )
    }

    @Test
    fun theNoProxyListCoversEveryPrivateRangeTheClassifierKnows() {
        val list = EgressClassifier.NO_PROXY_VALUE
        for (entry in listOf(
            "127.0.0.1",
            "10.0.0.0/8",
            "172.16.0.0/12",
            "192.168.0.0/16",
            "169.254.0.0/16",
            "100.64.0.0/10",
            "*.local",
        )) {
            assertTrue("the bypass list must contain $entry", list.contains(entry))
        }
    }

    // -----------------------------------------------------------------
    // Reporting
    // -----------------------------------------------------------------

    @Test
    fun aProxyRefusalNamesTheProxyAndNotTheDevice() {
        val sentence = EgressClassifier.failureSentence(
            "192.168.0.115",
            8060,
            EgressClassifier.Route.TOR,
            java.io.IOException("Unable to tunnel through proxy. Proxy returns \"HTTP/1.0 400 Bad Request\""),
        )
        assertTrue(
            "the sentence must say the proxy refused: $sentence",
            sentence.contains("proxy refused", ignoreCase = true),
        )
        assertTrue("it must name the target", sentence.contains("192.168.0.115:8060"))
    }

    @Test
    fun aClosedPortIsReportedAsARefusalAndNotAsAnUnreachableHost() {
        val sentence = EgressClassifier.failureSentence(
            "192.168.0.115",
            8060,
            EgressClassifier.Route.DIRECT,
            java.net.ConnectException("Connection refused"),
        )
        assertTrue("must say refused: $sentence", sentence.contains("refused", ignoreCase = true))
        assertFalse(
            "a refusal is not an unreachable host, they mean opposite things: $sentence",
            sentence.contains("not reachable", ignoreCase = true),
        )
    }

    @Test
    fun anUnreachableHostIsReportedAsSuch() {
        val sentence = EgressClassifier.failureSentence(
            "192.168.0.209",
            9922,
            EgressClassifier.Route.DIRECT,
            java.net.NoRouteToHostException("No route to host"),
        )
        assertTrue(sentence.contains("not reachable", ignoreCase = true))
    }

    @Test
    fun aDnsFailureIsReportedAsANameFailureNotADeviceFailure() {
        val sentence = EgressClassifier.failureSentence(
            "nope.invalid",
            null,
            EgressClassifier.Route.TOR,
            UnknownHostException("nope.invalid"),
        )
        assertTrue(
            "must say the lookup failed: $sentence",
            sentence.contains("resolve", ignoreCase = true),
        )
    }

    @Test
    fun aTimeoutSaysWhichRouteItWasContactedOver() {
        val through = EgressClassifier.failureSentence(
            "192.168.0.115",
            8060,
            EgressClassifier.Route.DIRECT,
            java.net.SocketTimeoutException("timeout"),
        )
        assertTrue(through.contains("directly"))
        assertTrue(through.contains("192.168.0.115:8060"))

        val tor = EgressClassifier.failureSentence(
            "example.com",
            443,
            EgressClassifier.Route.TOR,
            java.net.SocketTimeoutException("timeout"),
        )
        assertTrue(tor.contains("through Tor"))
    }

    @Test
    fun aNullErrorStillProducesAReadableSentence() {
        val sentence = EgressClassifier.failureSentence(
            "example.com",
            null,
            EgressClassifier.Route.TOR,
            null,
        )
        assertTrue(sentence.contains("example.com"))
        assertFalse("must not be blank", sentence.isBlank())
    }

    // -----------------------------------------------------------------
    // Allocation: the OOM fix, held in place
    // -----------------------------------------------------------------

    /**
     * THE CRASH, AS A TEST.
     *
     * `parseIpv4` used to call `host.split('.')`, which is the `String` overload, which compiles
     * a fresh `Pattern` on every call and allocates a `List` plus a `String` per octet. Every
     * domain name takes that reject path, and OkHttp calls the proxy selector once per connection
     * attempt and retries for ever while a throw is treated as a route failure. Measured before
     * the crash: 466 suppressed ProtocolExceptions from one fetch, then a 32-byte substring
     * failed.
     *
     * A million classifications used to mean a million compiled Patterns. This asserts the work
     * is now bounded, which is the property that matters: if a split or a regex returns to the
     * classification path, the allocated byte count moves by hundreds of megabytes and this
     * fails.
     *
     * Both arms are NAMES rather than addresses so this file carries no IP literal for the
     * privacy gate. The public name takes the reject path out of parseIpv4, which is the path
     * every domain name takes and the one that used to compile a regex per call.
     */
    @Test
    fun classifyingAHostAMillionTimesAllocatesAlmostNothing() {
        repeat(50_000) {
            EgressClassifier.routeForHost("api.example.com")
            EgressClassifier.routeForHost("roku.local")
        }

        val runtime = Runtime.getRuntime()
        val before = usedHeap(runtime)
        repeat(1_000_000) {
            EgressClassifier.routeForHost("api.example.com")
            EgressClassifier.routeForHost("roku.local")
        }
        val after = usedHeap(runtime)

        val growthMb = (after - before) / (1024.0 * 1024.0)
        assertTrue(
            "a million classifications grew the heap by ${"%.1f".format(growthMb)} MB, " +
                "which means the classification path is allocating again",
            growthMb < 16.0,
        )
    }

    @Test
    fun theRejectPathForADomainNameIsStableUnderRepetition() {
        // Every non-numeric host must fall out of parseIpv4 without building an address. That is
        // not observable from outside, so the consequence is asserted: the answer is TOR and it
        // does not waver across repetition.
        val hosts = listOf(
            "example.com",
            "api.github.com",
            "a.b.c.d.e",
            "1234.5678.9.10",
            "192.168.1.1.1",
            "api.unuslumen.com",
        )
        for (host in hosts) {
            repeat(1_000) {
                assertEquals("$host must classify as TOR", EgressClassifier.Route.TOR, route(host))
            }
        }
    }

    @Test
    fun aTrailingDotIsNotAPlausibleHostname() {
        // The label walk must reject an empty final label rather than accept a name that ends on
        // its separator.
        assertEquals(EgressClassifier.Route.TOR, route("example.com."))
        assertEquals(EgressClassifier.Route.TOR, route("."))
    }

    @Test
    fun aLabelOverSixtyThreeCharactersIsRejected() {
        val tooLong = "a".repeat(64)
        assertEquals(EgressClassifier.Route.TOR, route("$tooLong.local"))
        val allowed = "a".repeat(63)
        assertEquals(EgressClassifier.Route.DIRECT, route("$allowed.local"))
    }

    @Test
    fun hyphenAndUnderscoreLabelsAreStillPlausible() {
        assertEquals(EgressClassifier.Route.DIRECT, route("my-nas.local"))
        assertEquals(EgressClassifier.Route.DIRECT, route("my_nas.local"))
        assertEquals(EgressClassifier.Route.TOR, route("bad host.local"))
        assertEquals(EgressClassifier.Route.TOR, route("bad\$host.local"))
    }

    private fun usedHeap(runtime: Runtime): Long {
        // Collect twice first so the reading reflects live data rather than garbage the loop has
        // already dropped.
        System.gc()
        System.runFinalization()
        System.gc()
        return runtime.totalMemory() - runtime.freeMemory()
    }
}
