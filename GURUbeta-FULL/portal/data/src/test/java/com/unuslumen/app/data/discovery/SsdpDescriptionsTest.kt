// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.discovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Holds the LOCATION normalisation and the description parse, because issue 333 was
 * three unnamed devices out of six on one network at one moment.
 *
 * THE SPLIT THE TICKET REPORTS: ARRIS and both LG sets unnamed, Amazon and Virgin
 * named. The title names the cause, "a bare host:port LOCATION": a LOCATION with no
 * scheme makes URL() treat the IP as a protocol, the fetch never happens, and the name
 * stays empty while a neighbour sending a proper URL is named.
 */
class SsdpDescriptionsTest {

    // ---- LOCATION normalisation ----

    @Test
    fun aBareHostAndPortGetsASchemeSoItCanActuallyBeFetched() {
        // The exact shape issue 333's title names, from an LG set.
        assertEquals(
            "A UPnP LOCATION is specified to be absolute, but real devices send a bare " +
                "host:port. Without a scheme, URL() reads the IP as a protocol and the " +
                "description is never fetched, which is why the LG sets stayed unnamed.",
            "http://192.0.2.9:1234/desc.xml",
            SsdpDescriptions.normaliseLocation("192.0.2.9:1234/desc.xml"),
        )
    }

    @Test
    fun aProperUrlPassesThroughByteForByte() {
        // The function must not be able to break a LOCATION that already worked, which is
        // what the named devices on the same network prove was happening.
        val good = "http://192.0.2.20:9197/dmr"
        assertEquals(good, SsdpDescriptions.normaliseLocation(good))
    }

    @Test
    fun anHttpsUrlIsNotDowngraded() {
        assertEquals(
            "A device that serves its description over TLS must not be silently " +
                "downgraded to plain HTTP.",
            "https://192.0.2.30/rootDesc.xml",
            SsdpDescriptions.normaliseLocation("https://192.0.2.30/rootDesc.xml"),
        )
    }

    @Test
    fun aProtocolRelativeLocationGetsTheSchemeWithoutADoubleSlash() {
        assertEquals(
            "http://192.0.2.40:80/d.xml",
            SsdpDescriptions.normaliseLocation("//192.0.2.40:80/d.xml"),
        )
    }

    @Test
    fun aBlankLocationIsNotALocationAtAll() {
        assertNull(SsdpDescriptions.normaliseLocation(""))
        assertNull(SsdpDescriptions.normaliseLocation(null))
        assertNull(SsdpDescriptions.normaliseLocation("   "))
    }

    @Test
    fun aLocationWithNoHostIsRejectedRatherThanThrowingInsideTheFetch() {
        // "not a url" gains a scheme and still has no host, so it must come back null here
        // rather than throwing later in a catch that only logs.
        assertNull(
            "A LOCATION that yields no host cannot be fetched however it is spelled, and " +
                "reporting that as BAD_LOCATION is better than a log line nobody reads.",
            SsdpDescriptions.normaliseLocation("not a url"),
        )
    }

    @Test
    fun theHostComesFromTheNormalisedLocationAndFallsBackToTheSourceAddress() {
        assertEquals(
            "192.0.2.9",
            SsdpDescriptions.hostOf("http://192.0.2.9:1234/desc.xml", "192.0.2.99"),
        )
        assertEquals(
            "a device that answered on the wire is on the wire even when its header is nonsense",
            "192.0.2.99",
            SsdpDescriptions.hostOf(null, "192.0.2.99"),
        )
    }

    // ---- Description parsing ----

    private val lgDescription = """
        <?xml version="1.0"?>
        <root xmlns="urn:schemas-upnp-org:device-1-0">
          <device>
            <deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>
            <friendlyName>[LG] webOS TV OLED55</friendlyName>
            <manufacturer>LG Electronics</manufacturer>
            <manufacturerURL>http://www.lg.com</manufacturerURL>
            <modelName>OLED55C1</modelName>
          </device>
        </root>
    """.trimIndent()

    @Test
    fun aRealDescriptionIsReadWithItsNamespace() {
        val parsed = SsdpDescriptions.parse(lgDescription)

        assertNotNull("a well-formed description must parse", parsed)
        assertEquals("[LG] webOS TV OLED55", parsed!!.friendlyName)
        assertEquals("LG Electronics", parsed.manufacturer)
        assertEquals("OLED55C1", parsed.modelName)
        assertTrue(parsed.named)
    }

    @Test
    fun manufacturerUrlNeverSatisfiesASearchForManufacturer() {
        // The old regex matched on a substring, so <manufacturerURL> could fill the
        // manufacturer field with a URL. Matching the local name exactly is the fix.
        val parsed = SsdpDescriptions.parse(lgDescription)!!
        assertEquals(
            "The manufacturer is LG Electronics, not the URL. A substring match would " +
                "return http://www.lg.com whenever manufacturerURL came first.",
            "LG Electronics",
            parsed.manufacturer,
        )
    }

    @Test
    fun aPrefixedNamespaceIsReadTheSameWay() {
        val prefixed = """
            <?xml version="1.0"?>
            <d:root xmlns:d="urn:schemas-upnp-org:device-1-0">
              <d:device>
                <d:friendlyName>ARRIS Router</d:friendlyName>
                <d:manufacturer>ARRIS</d:manufacturer>
                <d:modelName>TG3492LG</d:modelName>
              </d:device>
            </d:root>
        """.trimIndent()

        val parsed = SsdpDescriptions.parse(prefixed)
        assertEquals(
            "A prefixed namespace is the same document as an unprefixed one and must " +
                "read identically.",
            "ARRIS Router",
            parsed?.friendlyName,
        )
        assertEquals("ARRIS", parsed?.manufacturer)
    }

    @Test
    fun aDocumentThatDeclaresNoNameIsUnnamedRatherThanUnfetched() {
        val nameless = """
            <?xml version="1.0"?>
            <root xmlns="urn:schemas-upnp-org:device-1-0">
              <device><deviceType>urn:schemas-upnp-org:device:Basic:1</deviceType></device>
            </root>
        """.trimIndent()

        val parsed = SsdpDescriptions.parse(nameless)
        assertNotNull("the document parsed, so this is not a parse failure", parsed)
        assertEquals(
            "A device that served a readable description with no friendlyName genuinely " +
                "has no name. That is a fact about the device, which is why it is UNNAMED " +
                "and not UNPARSEABLE.",
            DescriptionState.UNNAMED,
            SsdpDescriptions.stateOf(parsed),
        )
    }

    @Test
    fun aDocumentThatDoesNotParseIsReportedAsOurFailure() {
        assertNull(SsdpDescriptions.parse("this is not xml at all"))
        assertEquals(
            DescriptionState.UNPARSEABLE,
            SsdpDescriptions.stateOf(SsdpDescriptions.parse("this is not xml at all")),
        )
    }

    @Test
    fun aParsedAndNamedDocumentIsFetched() {
        assertEquals(
            DescriptionState.FETCHED,
            SsdpDescriptions.stateOf(SsdpDescriptions.parse(lgDescription)),
        )
    }

    @Test
    fun everyMissingNameReasonReadsDifferentlySoAReaderCanActOnIt() {
        // The whole point of the change: an empty name must never be ambiguous. Each state
        // produces a different sentence, and the ones that are not the device's fault say so.
        val sentences = DescriptionState.entries.map { state ->
            SsdpDescriptions.problemSentence("192.0.2.9", "uuid:abc", state, null)
        }
        assertEquals(
            "Two states sharing a sentence would make the distinction invisible again.",
            DescriptionState.entries.size,
            sentences.toSet().size,
        )
    }

    @Test
    fun aNotFetchedDeviceSaysToReadAgainRatherThanClaimingItHasNoName() {
        val sentence = SsdpDescriptions.problemSentence(
            "192.0.2.9",
            "uuid:abc",
            DescriptionState.NOT_FETCHED,
            null,
        )
        assertTrue(
            "A device still being fetched must not be reported as nameless. Got: $sentence",
            sentence.contains("not a fact about the device"),
        )
    }
}
