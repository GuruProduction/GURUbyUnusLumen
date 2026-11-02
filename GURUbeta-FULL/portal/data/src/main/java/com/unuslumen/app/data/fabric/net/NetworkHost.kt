// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.fabric.net

/**
 * Everything reachable on the local network, as a model. Issue #187.
 *
 * WHY THIS IS NOT A TELEVISION LIST. Steven's requirement, 2026-11-01: "i need guru to
 * be able to see everything on the network literally everything. if its connected to
 * the same wifi guru is on guru needs to be able to see and control it." A television
 * inventory hides the very devices a hacker tool is for, so this model describes a
 * host, not a telly.
 *
 * WHAT "SEE" MEANS AND WHAT "CONTROL" MEANS, kept apart on purpose. Seeing is real:
 * every host that answers a probe is listed with what answered. Control is per
 * protocol, because a printer and a light bulb do not share a command language, and
 * claiming control of everything would be the lie this project exists to remove. So a
 * host reports what it exposes, and the control layer attaches where an adapter exists.
 */

/** What a host most plausibly is, from what it answered. */
enum class HostKind {
    ROUTER,
    TELEVISION,
    SET_TOP_BOX,
    SPEAKER,
    CAMERA,
    PRINTER,
    COMPUTER,
    PHONE,
    NAS,
    SMART_HOME_HUB,
    IOT,
    /** It answered something, but nothing that says what it is. */
    UNKNOWN,
}

/** One device on the local network. */
data class NetworkHost(
    val address: String,
    val hostname: String? = null,
    val mac: String? = null,
    /** Ports that accepted a connection. */
    val openPorts: List<Int> = emptyList(),
    /** Service names discovered over mDNS or SSDP, such as "_airplay._tcp". */
    val services: List<String> = emptyList(),
    /** Whatever the device said about itself, one line per source. */
    val evidence: List<String> = emptyList(),
    /**
     * The SSDP `LOCATION` this host published, when it answered over SSDP.
     *
     * WHY IT IS KEPT RATHER THAN FOLDED INTO EVIDENCE. It is the device's own absolute URL for
     * its description document, and it is the only field that can say the description lives on
     * a different port from everything else: the Fire TV publishes its description on 60000
     * and its app endpoint on 8009. Issue #251 was a DIAL client that ignored this and guessed
     * a path instead, so every call was a 404 and the device was blamed for it. A tool that
     * needs to describe a host now has the device's own answer to hand.
     */
    val location: String? = null,
    val kind: HostKind = HostKind.UNKNOWN,
    /** Present when the host is a television this build can actually drive. */
    val tvVendorName: String? = null,
) {
    /** Stable id for the fabric store, so a label survives a rescan. */
    val id: String get() = "net:$address"

    /** A display name that is never empty, so a list reads sensibly. */
    val displayName: String
        get() = hostname?.takeIf { it.isNotBlank() } ?: "$address"
}

/** The result of one scan. */
data class NetworkScanReport(
    val hosts: List<NetworkHost>,
    /** One line per probe that could not run, so a short list is explained. */
    val problems: List<String>,
    /** How many addresses were probed, so the coverage is visible. */
    val probedAddresses: Int,
    /** The subnet that was scanned, e.g. 192.168.0. */
    val subnet: String?,
) {
    val count: Int get() = hosts.size
}
