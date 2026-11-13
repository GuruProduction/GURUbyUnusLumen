// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.fabric.net

import java.io.ByteArrayOutputStream

/**
 * The one mDNS query builder, shared by every scanner that asks Bonjour a question.
 *
 * WHY THIS IS ONE OBJECT RATHER THAN TWO PRIVATE METHODS. The network scanner and the
 * television discovery both need to ask mDNS, and both previously built their own packet.
 * Two copies of a byte layout drift the moment one is edited, and the copy the app runs
 * would be the one nothing tests.
 *
 * THE BIT THAT MATTERS. A standard mDNS responder multicasts its answer to
 * 224.0.0.251:5353. A phone app holds an ephemeral socket, is not bound to that port, and
 * therefore never receives the reply. That is why Bonjour reported an empty network on a
 * network full of devices. RFC 6762 section 5.4 defines the unicast-response bit, the top
 * bit of the question's class field, so a class of 0x8001 asks the responder to answer
 * straight back to the address and port that asked.
 */
object MdnsQuery {

    /**
     * Build a DNS PTR query for one service name such as `_googlecast._tcp.local`.
     *
     * A DNS packet is a 12-byte header, then one question made of length-prefixed labels,
     * then QTYPE and QCLASS. Built by hand because it is twenty-odd bytes and a dependency
     * for one packet would be a build change for no gain.
     */
    fun ptrQuery(name: String): ByteArray {
        val out = ByteArrayOutputStream()

        out.write(byteArrayOf(0, 0))  // transaction id 0
        out.write(byteArrayOf(0, 0))  // flags: a query, not a response
        out.write(byteArrayOf(0, 1))  // one question
        out.write(byteArrayOf(0, 0))  // no answers
        out.write(byteArrayOf(0, 0))  // no authority records
        out.write(byteArrayOf(0, 0))  // no additional records

        for (label in name.split('.')) {
            if (label.isEmpty()) continue
            val bytes = label.toByteArray(Charsets.US_ASCII)
            out.write(bytes.size)
            out.write(bytes)
        }
        out.write(0)  // end of name

        // QTYPE 12 is PTR. QCLASS 0x8001 is IN with the unicast-response bit set.
        out.write(byteArrayOf(0, 12, 0x80.toByte(), 0x01))
        return out.toByteArray()
    }

    /**
     * The service names a screen announces itself under.
     *
     * WHY AIRPLAY IS QUERIED AND THEN USED. Issue 331: `tvDiscover` found one of three DIAL
     * sets and none of two AirPlay receivers, because `_airplay._tcp` was asked for and the
     * replies were then mapped to no vendor, so they were dropped a line after arriving.
     * Querying a service and discarding its answers is worse than not asking at all, because
     * the question makes every receiver on the network spend power answering it.
     *
     * `_raop._tcp` is the Remote Audio Output Protocol an AirPlay receiver also publishes. A
     * set that offers one almost always offers both, and listening for it costs one more
     * question in the same pass.
     */
    val screenServices: List<String> = listOf(
        "_androidtvremote2._tcp.local",
        "_googlecast._tcp.local",
        "_airplay._tcp.local",
        "_raop._tcp.local",
    )
}
