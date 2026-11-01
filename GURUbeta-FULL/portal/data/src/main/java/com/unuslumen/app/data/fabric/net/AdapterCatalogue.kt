// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.fabric.net

/**
 * Every adapter the network control goal needs, as data. Requirement 1.
 *
 * WHY THIS IS A DECLARED LIST RATHER THAN A SECTION IN A DOC. Steven asked for "a list of
 * every single adaptor then and build it out". A list that lives in this file is one the
 * code can report against, so a tool can say which classes are drivable and which are
 * still gaps, and the gap list cannot quietly stop being true.
 *
 * HOW EACH ENTRY IS SHAPED. One adapter is one protocol, not one vendor, because that is
 * where the leverage is: the same SOAP caller drives every UPnP box, the same DIAL client
 * launches an app on a Roku and a Fire TV and a Virgin box, and one Wake-on-LAN packet
 * wakes anything asleep. Vendor-specific entries are only where the vendor invented
 * something no standard covers.
 *
 * THE ORDER IS BY LEVERAGE, matching his spec: the ADB tier unlocks the most because at
 * app UID Android refuses the ARP table outright, and then the protocol adapters that
 * turn discovery into control.
 */
object AdapterCatalogue {

    /** What one adapter is for, and whether it is built. */
    data class Adapter(
        val id: String,
        /** What it drives, in words a person recognises. */
        val target: String,
        /** The protocol or mechanism. */
        val mechanism: String,
        /** Which of his eight requirements this is. */
        val requirement: Int,
        /** True when this build really drives the device, false when it is still a gap. */
        val built: Boolean,
        /** Where it lives, or what it would take. */
        val detail: String,
    )

    /**
     * The catalogue.
     *
     * `built` is the honest flag: a row marked false is a real gap and is refused by name
     * rather than pretended. It is the same discipline the fabric uses for a transport.
     */
    val all: List<Adapter> = listOf(
        // ---- Requirement 1: the ADB tier, which unlocks the most ----
        Adapter(
            id = "adb-wireless",
            target = "The phone itself at shell UID",
            mechanism = "Wireless debugging pair, then an adb shell over 5555",
            requirement = 1,
            built = true,
            detail = "autoPairAdb already drives the whole pairing flow through the accessibility " +
                "service. This is what opens the ARP table, dumpsys, settings and other apps' " +
                "data, all of which Android refuses at app UID.",
        ),
        Adapter(
            id = "adb-network-device",
            target = "Any device exposing adb on 5555",
            mechanism = "ADB over TCP to a Fire TV, an Android TV box or a developer-mode set",
            requirement = 1,
            built = false,
            detail = "Needs an adb client library in the app rather than a shell binary. A Fire TV " +
                "with developer options on accepts it, which is arbitrary command execution on " +
                "the television rather than remote control of it.",
        ),

        // ---- Requirement 2: the privilege-free scanner ----
        Adapter(
            id = "active-scan",
            target = "Every host on the local subnet",
            mechanism = "Concurrent TCP probes across a port set, with a cap and a hard timeout",
            requirement = 2,
            built = true,
            detail = "This is the scanner that works at app UID, because /proc/net/arp is denied " +
                "and ICMP needs a raw socket. Concurrency is capped because a hand-rolled loop " +
                "was OOM-killed at exit 137 on his phone.",
        ),

        // ---- Requirement 3: UPnP SOAP, the biggest discovery-to-control gap ----
        Adapter(
            id = "upnp-soap",
            target = "Every UPnP device on the network, including ONVIF cameras",
            mechanism = "SOAP action invocation over HTTP against a service's control URL",
            requirement = 3,
            built = true,
            detail = "Reads the device description, walks its serviceList, and builds the XML " +
                "envelope so any action on any service is callable. A UPnP fault is reported with " +
                "the device's own error code rather than as a success.",
        ),

        // ---- Requirement 4: DIAL ----
        Adapter(
            id = "dial-launch",
            target = "Roku, Fire TV, Virgin boxes and any DIAL device",
            mechanism = "DIAL: POST to the apps endpoint to launch, DELETE to stop",
            requirement = 4,
            built = true,
            detail = "Reads the description for the Application-URL, then launches or stops a named " +
                "app. This is the app-level control that makes putting a film on a set real rather " +
                "than a sequence of D-pad presses.",
        ),

        // ---- Requirement 5: Wake-on-LAN ----
        Adapter(
            id = "wake-on-lan",
            target = "Any sleeping device with a MAC",
            mechanism = "Magic packet to 255.255.255.255 on 9 and 7",
            requirement = 5,
            built = true,
            detail = "Six 0xFF bytes then the target MAC sixteen times, broadcast on both ports " +
                "because firmware disagrees about which it honours. A set asleep on the network " +
                "cannot be controlled until it is woken, so this precedes every attempt.",
        ),

        // ---- Requirement 6: the persistent map ----
        Adapter(
            id = "network-map",
            target = "The house itself",
            mechanism = "Stored hosts, ports, fingerprints, vendor and last-seen",
            requirement = 6,
            built = false,
            detail = "Makes GURU know the house instead of rescanning it. Needs a Room table, " +
                "which is a migration, so it lands with its own issue rather than folded in.",
        ),

        // ---- Requirement 8: no IR on this hardware ----
        Adapter(
            id = "ir-network-blaster",
            target = "Sets with no network of their own",
            mechanism = "A Broadlink or SwitchBot blaster over the network",
            requirement = 8,
            built = true,
            detail = "The built-in emitter is refused honestly on a phone with no consumer IR " +
                "service, which the SM-A336B does not have. Infrared routes through a network " +
                "blaster or it is fantasy.",
        ),

        // ---- The vendor remotes, which are what actually drives a television ----
        Adapter(
            id = "roku-ecp",
            target = "Roku televisions, and Hisense, TCL, Sharp, Philips and ONN sets running Roku",
            mechanism = "External Control Protocol over HTTP on 8060",
            requirement = 0,
            built = true,
            detail = "No pairing at all. device-info gives power-mode and the active app, and " +
                "keypress drives every button. This is the reference implementation of what " +
                "control should feel like.",
        ),
        Adapter(
            id = "samsung-tizen",
            target = "Samsung televisions",
            mechanism = "WebSocket on 8001 with a token handshake",
            requirement = 0,
            built = true,
            detail = "Asks the person to accept an Allow prompt on the screen once, then keeps " +
                "the token so it never asks again.",
        ),
        Adapter(
            id = "lg-webos",
            target = "LG televisions",
            mechanism = "SSAP over a WebSocket on 3000, with a client-key handshake",
            requirement = 0,
            built = true,
            detail = "Power, volume, mute and channel. Its D-pad goes over a second pointer socket " +
                "whose message format is not verified, so those refuse rather than guess.",
        ),

        // ---- Still gaps, named rather than hidden ----
        Adapter(
            id = "android-tv-remote",
            target = "Android TV and Google TV, including Fire TV's Android side",
            mechanism = "The Remote v2 pairing protocol on 6466, with an on-screen code",
            requirement = 0,
            built = false,
            detail = "Needs a protobuf implementation and the pairing exchange. His own scan found " +
                "an Amazon Fire TV at 192.168.0.71, so this is a live gap on his network.",
        ),
        Adapter(
            id = "vizio-smartcast",
            target = "Vizio televisions",
            mechanism = "HTTPS API on 7345 after a four-digit PIN pairing",
            requirement = 0,
            built = false,
            detail = "Pair, then send an AUTH header with the token it issues.",
        ),
        Adapter(
            id = "philips-jointspace",
            target = "Philips televisions",
            mechanism = "JointSpace API on 1925, with a pairing request",
            requirement = 0,
            built = false,
            detail = "The pairing endpoint grants a key the remote then uses.",
        ),
        Adapter(
            id = "virgin-tivo",
            target = "The Virgin TV boxes at 192.168.0.50 and 192.168.0.154",
            mechanism = "Their own box API, once the real endpoints are identified",
            requirement = 0,
            built = false,
            detail = "His scan proved these answer HTTP 200 with status=ok to every path, " +
                "including invented ones, so their real API has to be found by comparing against " +
                "a nonsense baseline rather than by trusting a 200.",
        ),
        Adapter(
            id = "chromecast",
            target = "Chromecasts and Cast-enabled sets",
            mechanism = "The Cast protocol on 8009",
            requirement = 0,
            built = false,
            detail = "A TLS channel carrying protobuf. It is how a stream is pushed to a set rather " +
                "than typed at it.",
        ),
        Adapter(
            id = "airplay",
            target = "Apple TVs and AirPlay speakers",
            mechanism = "AirPlay on 7000",
            requirement = 0,
            built = false,
            detail = "An Apple TV answers on 7000 and advertises _airplay._tcp over mDNS, so it is " +
                "found already. Driving it needs the AirPlay handshake and its pairing exchange, " +
                "which is a real implementation rather than a request call.",
        ),
        Adapter(
            id = "printer-ipp",
            target = "Printers on 631, 9100 and 515",
            mechanism = "IPP for status and jobs, raw 9100 for a document",
            requirement = 0,
            built = false,
            detail = "IPP reports supply levels, queue state and accepts a print job, so a printer " +
                "becomes something GURU can genuinely report on.",
        ),
        Adapter(
            id = "camera-rtsp",
            target = "Cameras on 554",
            mechanism = "RTSP for the stream, and ONVIF for pan, tilt and zoom",
            requirement = 0,
            built = false,
            detail = "ONVIF is SOAP, so the UPnP SOAP caller gets most of the way there first.",
        ),
        Adapter(
            id = "nas-smb",
            target = "A NAS or a computer sharing files",
            mechanism = "SMB on 445, AFP on 548, NFS on 2049",
            requirement = 0,
            built = false,
            detail = "Listing and reading shares. Needs an SMB client, which is a real dependency " +
                "rather than a hundred lines.",
        ),
        Adapter(
            id = "router-admin",
            target = "The router",
            mechanism = "Its own admin interface, and UPnP IGD port mapping",
            requirement = 0,
            built = false,
            detail = "IGD is UPnP SOAP, so the SOAP caller covers port forwarding. The admin page " +
                "is vendor-specific and needs credentials the person supplies.",
        ),
        Adapter(
            id = "mqtt-iot",
            target = "Smart plugs, bulbs and sensors",
            mechanism = "MQTT on 1883 and 8883",
            requirement = 0,
            built = false,
            detail = "This is the transport issue #185 already covers, and it belongs there rather " +
                "than here so the smart home fabric owns it once.",
        ),
        Adapter(
            id = "wol-and-ping",
            target = "Anything asleep or unresponsive",
            mechanism = "ICMP echo where permitted, and Wake-on-LAN",
            requirement = 5,
            built = false,
            detail = "ICMP needs a raw socket, which Android denies at app UID, so reachability is " +
                "a TCP connect instead until the ADB tier is up.",
        ),
    )

    /** The adapters this build really drives. */
    val built: List<Adapter> get() = all.filter { it.built }

    /** The gaps, by requirement, so the work ahead is countable rather than vague. */
    val gaps: List<Adapter> get() = all.filterNot { it.built }

    /** One line per gap, for a report that has to state what is missing. */
    fun gapSentence(): String {
        val missing = gaps
        if (missing.isEmpty()) return "Every adapter in the catalogue is built."
        return "${missing.size} of ${all.size} adapters are still gaps: " +
            missing.joinToString(", ") { it.id } + "."
    }
}
