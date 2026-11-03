// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.fabric.net

/**
 * What identifies a device, from what it said. No sockets, no Android.
 *
 * WHY THIS IS A SEPARATE OBJECT FROM THE PROBES. Every rule here is a judgement: which OID names
 * a printer, which TXT field names a HomeKit accessory, whether a MAC prefix is enough to call a
 * host a printer. Judgements are exactly what a JVM test can pin, and the fault that makes a
 * scanner useless is a classification rule that is wrong in one branch. Keeping them here means
 * the test asserts the rule the app actually runs, not a second copy written inside the test that
 * could drift from it.
 *
 * THE THREE THINGS A DEVICE REPORT IS KEPT APART, because they carry different confidence:
 *
 *  1. What it DECLARED about itself, such as a UPnP friendlyName or a HomeKit model. It said it.
 *  2. What was INFERRED from its open ports. A guess, and labelled as one.
 *  3. What was DERIVED from a name the network gave it, such as a DHCP hostname mentioning
 *     "washer". Also a guess, but a useful one on a device that answers nothing else.
 *
 * [Evidence] carries which of the three it was, so a person reading a list can tell a fact from
 * an inference. A scanner that presents a port guess with the same weight as a declaration is
 * the fault this class exists to prevent.
 */

/** How a fact about a device was arrived at, which is what its reliability rests on. */
enum class EvidenceKind {
    /** The device itself said so, over its own protocol. */
    DECLARED,

    /** Inferred from which ports answered. A guess, and reported as one. */
    INFERRED,

    /** Derived from a name something else on the network gave it, such as a DHCP hostname. */
    DERIVED,
}

/** What a device most plausibly is, from everything that answered. */
enum class DeviceClass {
    ROUTER,
    ACCESS_POINT,
    TELEVISION,
    SET_TOP_BOX,
    SPEAKER,
    CAMERA,
    PRINTER,
    COMPUTER,
    PHONE,
    NAS,
    SMART_HOME_HUB,
    /** A mains or battery appliance: a washer, a fridge, a plug, a bulb. */
    APPLIANCE,
    /** It answered something, but nothing that says what it is. */
    UNKNOWN,
}

/** One fact about one device, with where the fact came from. */
data class Evidence(
    /** The protocol that produced it, such as "SSDP", "mDNS", "SNMP", "NBNS", "DHCP". */
    val source: String,
    /** The fact itself, in the device's own words where it had any. */
    val detail: String,
    val kind: EvidenceKind,
)

/**
 * The rules that turn protocol answers into an identified device.
 *
 * EVERY FUNCTION HERE IS PURE. That is not tidiness: it is what lets the whole classification
 * surface be tested in milliseconds on the JVM, including the branches nobody has a device for.
 */
object DeviceIdentifier {

    // ------------------------------------------------------------------
    // SNMP
    // ------------------------------------------------------------------

    /**
     * The OIDs asked in the first SNMP round, in one GET.
     *
     * WHY THESE SEVEN AND NO MORE. They are the MIB-II system group, every agent implements it,
     * and between them they name the vendor, the product, the firmware, the uptime and what
     * layer of the network the device works at. One packet per address buys all of it, and a
     * second round is only spent on a device that answered.
     *
     * WHY sysServices MATTERS. Its bitmask is the cheapest router-versus-printer distinction
     * there is: bit 2 set means the device forwards at layer 2, bit 4 means layer 3. A host that
     * answers SNMP and claims layer 3 is network equipment whatever else it says.
     */
    val SYSTEM_OIDS: List<String> = listOf(
        "1.3.6.1.2.1.1.1.0",   // sysDescr, the model and firmware in prose
        "1.3.6.1.2.1.1.2.0",   // sysObjectID, the vendor's own object identifier
        "1.3.6.1.2.1.1.3.0",   // sysUpTime, which proves it is a live agent
        "1.3.6.1.2.1.1.4.0",   // sysContact
        "1.3.6.1.2.1.1.5.0",   // sysName, the hostname it was given
        "1.3.6.1.2.1.1.6.0",   // sysLocation
        "1.3.6.1.2.1.1.7.0",   // sysServices, the layer bitmask
    )

    /** The second-round OIDs worth asking a device that answered SNMP. */
    val ENRICHMENT_OIDS: List<String> = listOf(
        "1.3.6.1.2.1.25.3.2.1.3.1",   // hrDeviceDescr.1, a clean model string
        "1.3.6.1.2.1.43.5.1.1.16.1",  // prtGeneralPrinterName
        "1.3.6.1.2.1.43.5.1.1.17.1",  // prtGeneralSerialNumber
        "1.3.6.1.2.1.43.5.1.1.2.1",   // prtGeneralPrinterName (older agents)
        "1.3.6.1.2.1.2.2.1.6.1",      // ifPhysAddress.1, the MAC
    )

    /**
     * The enterprise number in a sysObjectID, mapped to the vendor that owns it.
     *
     * WHY THE ENTERPRISE NUMBER AND NOT THE DESCRIPTION TEXT. sysDescr is free prose that a
     * vendor writes however it likes and a rebadged device often rewrites entirely. The
     * enterprise number under `1.3.6.1.4.1.N` is allocated by IANA to one organisation and is
     * the most reliable vendor signal the protocol carries.
     */
    private val enterpriseVendors: Map<String, String> = mapOf(
        "9" to "Cisco",
        "11" to "Hewlett-Packard",
        "253" to "Xerox",
        "367" to "Ricoh",
        "641" to "Lexmark",
        "1248" to "Epson",
        "1347" to "Kyocera",
        "1602" to "Canon",
        "2435" to "Brother",
        "236" to "Samsung",
        "318" to "APC",
        "4526" to "Netgear",
        "6574" to "Synology",
        "8072" to "Net-SNMP",
        "11863" to "TP-Link",
        "14988" to "MikroTik",
        "18334" to "Konica Minolta",
        "24681" to "QNAP",
        "41112" to "Ubiquiti",
    )

    /**
     * The vendor named by a sysObjectID, or null when the enterprise is not one this build knows.
     *
     * A null here is not a failure: an unknown enterprise still proves the device is a managed
     * agent worth reporting, it just does not name the make.
     */
    fun vendorFromSysObjectId(oid: String): String? {
        val trimmed = oid.trim().trim('.')
        if (!trimmed.startsWith("1.3.6.1.4.1.")) return null
        val enterprise = trimmed.removePrefix("1.3.6.1.4.1.").substringBefore('.')
        return enterpriseVendors[enterprise]
    }

    /**
     * What class of device a sysServices bitmask claims.
     *
     * The MIB-II definition: bit 1 (value 1) is physical, bit 2 (value 2) is datalink, bit 3
     * (value 4) is network, bit 4 (value 8) is transport, bit 7 (value 64) is application.
     * A device claiming datalink or network is forwarding traffic, so it is infrastructure.
     */
    fun classFromSysServices(value: Int): DeviceClass? = when {
        // Layer 3 forwarding, which is a router regardless of what else it says.
        value and 4 != 0 -> DeviceClass.ROUTER
        // Layer 2 forwarding with no layer 3, which is a switch or a bridge.
        value and 2 != 0 -> DeviceClass.ACCESS_POINT
        else -> null
    }

    /**
     * The best description of a device from its SNMP system group.
     *
     * [values] is keyed by OID. A missing key is normal: an agent returns `noSuchObject` for
     * anything it does not implement, and that must read as "it did not say", never as a fault.
     */
    fun fromSnmp(values: Map<String, String>): Identified {
        val descr = values["1.3.6.1.2.1.1.1.0"]?.trim()?.ifBlank { null }
        val objectId = values["1.3.6.1.2.1.1.2.0"]?.trim()?.ifBlank { null }
        val name = values["1.3.6.1.2.1.1.5.0"]?.trim()?.ifBlank { null }
        val location = values["1.3.6.1.2.1.1.6.0"]?.trim()?.ifBlank { null }
        val services = values["1.3.6.1.2.1.1.7.0"]?.trim()?.toIntOrNull()

        val vendor = objectId?.let { vendorFromSysObjectId(it) }
            ?: descr?.let { vendorFromDescription(it) }

        val byLayer = services?.let { classFromSysServices(it) }
        val byProse = descr?.let { classFromProse(it) }

        // A printer says so in its own description even when it also claims application
        // services, so the prose wins over the bitmask for that one class.
        val resolved = when {
            byProse == DeviceClass.PRINTER -> DeviceClass.PRINTER
            byLayer != null -> byLayer
            byProse != null -> byProse
            else -> null
        }

        return Identified(
            name = name ?: descr?.take(64),
            vendor = vendor,
            model = descr?.take(120),
            location = location,
            deviceClass = resolved,
            evidence = listOfNotNull(
                descr?.let { Evidence("SNMP", "sysDescr: $it", EvidenceKind.DECLARED) },
                vendor?.let { Evidence("SNMP", "the vendor is $it", EvidenceKind.DECLARED) },
                location?.let { Evidence("SNMP", "sysLocation: $it", EvidenceKind.DECLARED) },
            ),
        )
    }

    /**
     * The vendor a sysDescr prose string names.
     *
     * Matched on distinctive tokens only. "HP" is deliberately absent because it appears inside
     * unrelated words, and a false vendor is worse than no vendor: it sends the reader to the
     * wrong manual.
     */
    fun vendorFromDescription(description: String): String? {
        val haystack = description.lowercase()
        return when {
            haystack.contains("hewlett") || haystack.contains("hp jetdirect") -> "Hewlett-Packard"
            haystack.contains("brother") -> "Brother"
            haystack.contains("xerox") -> "Xerox"
            haystack.contains("ricoh") -> "Ricoh"
            haystack.contains("kyocera") -> "Kyocera"
            haystack.contains("lexmark") -> "Lexmark"
            haystack.contains("konica") -> "Konica Minolta"
            haystack.contains("epson") -> "Epson"
            haystack.contains("canon") -> "Canon"
            haystack.contains("synology") -> "Synology"
            haystack.contains("qnap") -> "QNAP"
            haystack.contains("mikrotik") || haystack.contains("routeros") -> "MikroTik"
            haystack.contains("ubiquiti") || haystack.contains("edgeos") -> "Ubiquiti"
            haystack.contains("openwrt") -> "OpenWrt"
            haystack.contains("cisco") || haystack.contains("ios software") -> "Cisco"
            haystack.contains("netgear") -> "Netgear"
            haystack.contains("tp-link") || haystack.contains("tplink") -> "TP-Link"
            haystack.contains("linux") -> "Linux"
            else -> null
        }
    }

    /** The class a device's own description prose suggests. */
    fun classFromProse(description: String): DeviceClass? {
        val haystack = description.lowercase()
        return when {
            haystack.contains("printer") || haystack.contains("jetdirect") ||
                haystack.contains("laserjet") || haystack.contains("page count") ->
                DeviceClass.PRINTER
            haystack.contains("router") || haystack.contains("gateway") ->
                DeviceClass.ROUTER
            haystack.contains("access point") || haystack.contains("wireless ap") ->
                DeviceClass.ACCESS_POINT
            haystack.contains("switch") -> DeviceClass.ACCESS_POINT
            haystack.contains("camera") || haystack.contains("nvr") -> DeviceClass.CAMERA
            haystack.contains("nas") || haystack.contains("storage") -> DeviceClass.NAS
            haystack.contains("ups") || haystack.contains("smart-ups") -> DeviceClass.SMART_HOME_HUB
            haystack.contains("linux") -> DeviceClass.COMPUTER
            else -> null
        }
    }

    // ------------------------------------------------------------------
    // DHCP hostnames, which is how an appliance with no protocols gets a name
    // ------------------------------------------------------------------

    /**
     * The class a DHCP hostname suggests, and the make inside it.
     *
     * WHY A HOSTNAME IS WORTH THIS MUCH. A washing machine, a fridge and a smart plug join the
     * wifi, take an address, and then answer nothing at all on any port. But most of them send a
     * DHCP hostname on the way in, and the router remembers it. Asking the router's own DNS for
     * the reverse record is the only route to a name for those devices, and a name is the
     * difference between "a device at .14" and "the Samsung washer".
     *
     * THE PAIRS BELOW ARE MAKES AND THE WORDS THEY PUT IN HOSTNAMES, gathered from how the
     * vendors actually name their own products on a home network. A miss is cheap, because the
     * hostname is still reported verbatim; a wrong match is the fault, so the tokens are
     * distinctive rather than generic.
     */
    fun fromHostname(hostname: String): Identified {
        val raw = hostname.trim()
        if (raw.isEmpty()) return Identified()
        val lower = raw.lowercase()

        val vendor = when {
            lower.startsWith("samsung") || lower.contains("tizen") -> "Samsung"
            lower.startsWith("lg") || lower.contains("lgwebos") -> "LG"
            lower.contains("sonos") -> "Sonos"
            lower.contains("philips") || lower.contains("hue") -> "Philips"
            lower.contains("shelly") -> "Shelly"
            lower.contains("tasmota") -> "Tasmota"
            lower.contains("espressif") || lower.startsWith("esp_") || lower.startsWith("esp-") ->
                "Espressif"
            lower.contains("tuya") || lower.contains("smartlife") -> "Tuya"
            lower.contains("roborock") || lower.contains("xiaomi") || lower.contains("miio") ->
                "Xiaomi"
            lower.contains("nest") -> "Google"
            lower.contains("echoshow") || lower.contains("echo-") || lower.contains("amazon") ->
                "Amazon"
            lower.contains("raspberrypi") || lower.contains("raspberry") -> "Raspberry Pi"
            lower.contains("synology") -> "Synology"
            lower.contains("reolink") || lower.contains("hikvision") || lower.contains("dahua") ->
                "Hikvision"
            lower.contains("brother") -> "Brother"
            lower.contains("hp-") || lower.contains("deskjet") || lower.contains("officejet") ->
                "Hewlett-Packard"
            lower.contains("epson") -> "Epson"
            lower.contains("canon") -> "Canon"
            lower.contains("android") -> "Android"
            lower.contains("iphone") || lower.contains("ipad") -> "Apple"
            else -> null
        }

        val deviceClass = when {
            lower.contains("washer") || lower.contains("washing") -> DeviceClass.APPLIANCE
            lower.contains("fridge") || lower.contains("refrigerat") -> DeviceClass.APPLIANCE
            lower.contains("dishwasher") -> DeviceClass.APPLIANCE
            lower.contains("dryer") || lower.contains("tumbledryer") -> DeviceClass.APPLIANCE
            lower.contains("oven") || lower.contains("hob") -> DeviceClass.APPLIANCE
            lower.contains("thermostat") || lower.contains("boiler") -> DeviceClass.APPLIANCE
            lower.contains("printer") || lower.contains("print") -> DeviceClass.PRINTER
            lower.contains("tv") || lower.contains("television") || lower.contains("webos") ->
                DeviceClass.TELEVISION
            lower.contains("roku") || lower.contains("firetv") || lower.contains("chromecast") ->
                DeviceClass.SET_TOP_BOX
            lower.contains("sonos") || lower.contains("speaker") -> DeviceClass.SPEAKER
            lower.contains("camera") || lower.contains("doorbell") -> DeviceClass.CAMERA
            lower.contains("nas") || lower.contains("diskstation") -> DeviceClass.NAS
            lower.contains("router") || lower.contains("gateway") -> DeviceClass.ROUTER
            lower.startsWith("esp") || lower.contains("shelly") || lower.contains("tasmota") ||
                lower.contains("tuya") || lower.contains("plug") || lower.contains("bulb") ->
                DeviceClass.APPLIANCE
            lower.contains("iphone") || lower.contains("android") -> DeviceClass.PHONE
            lower.contains("laptop") || lower.contains("desktop") || lower.contains("macbook") ->
                DeviceClass.COMPUTER
            else -> null
        }

        return Identified(
            name = raw,
            vendor = vendor,
            deviceClass = deviceClass,
            evidence = listOf(
                Evidence(
                    "DHCP",
                    "the network named it \"$raw\"",
                    EvidenceKind.DERIVED,
                ),
            ),
        )
    }

    // ------------------------------------------------------------------
    // Ports, which is the last resort and is labelled as a guess
    // ------------------------------------------------------------------

    /**
     * The class a set of open ports suggests, when nothing on the device named itself.
     *
     * THE COMBINATION IS THE EVIDENCE, NOT ONE PORT. 80 is on a router, a printer and a
     * television alike, so a single web port decides nothing. The rules below only fire where
     * the set is distinctive of one class, and the result is always reported as INFERRED.
     */
    fun classFromPorts(ports: Collection<Int>): DeviceClass? {
        val open = ports.toSet()
        return when {
            // Printer languages, which nothing else on a home network speaks.
            open.any { it in setOf(631, 9100, 515) } -> DeviceClass.PRINTER
            // A camera's stream, or an NVR's discovery.
            open.contains(554) -> DeviceClass.CAMERA
            // A remote desktop or a file share is a computer and not an appliance.
            open.any { it in setOf(445, 3389, 22) } &&
                !open.contains(8060) -> DeviceClass.COMPUTER
            open.any { it in setOf(5000, 2049, 32400) } -> DeviceClass.NAS
            // Streaming endpoints, which on a home network mean a screen of some kind.
            open.any { it in setOf(8060, 8001, 7345, 6466, 1925, 9000) } ->
                DeviceClass.TELEVISION
            // DIAL alone is ambiguous across every streaming box, so it stays unnamed.
            open.contains(8009) && open.contains(5555) -> DeviceClass.SET_TOP_BOX
            open.contains(7000) -> DeviceClass.SPEAKER
            open.contains(62078) -> DeviceClass.PHONE
            open.contains(53) || open.contains(49152) -> DeviceClass.ROUTER
            else -> null
        }
    }

    // ------------------------------------------------------------------
    // Wi-Fi access points, which are not hosts but are still broadcasting
    // ------------------------------------------------------------------

    /**
     * What an access point's own advertised name says about who owns it.
     *
     * WHY A NEIGHBOUR'S ROUTER IS WORTH CLASSIFYING. "Everything broadcasting near me" includes
     * the access points themselves, and a name is most of what one of those can ever tell you:
     * a radio carries no model string. The name is also the only hint at what kind of premises
     * it is, which is what makes a list of five SSIDs readable rather than a wall of hex.
     */
    fun classFromAccessPointName(ssid: String): DeviceClass? {
        val lower = ssid.lowercase()
        return when {
            lower.contains("guest") -> DeviceClass.ACCESS_POINT
            lower.startsWith("vm") && lower.length <= 10 -> DeviceClass.ROUTER
            lower.contains("sky") || lower.contains("bt-") || lower.contains("talktalk") ||
                lower.contains("virgin") || lower.contains("ee-") || lower.contains("plusnet") ||
                lower.contains("vodafone") -> DeviceClass.ROUTER
            lower.contains("printer") || lower.contains("direct-") -> DeviceClass.PRINTER
            lower.contains("chromecast") || lower.contains("roku") || lower.contains("firetv") ->
                DeviceClass.SET_TOP_BOX
            lower.contains("tuya") || lower.contains("smartlife") || lower.contains("shelly") ||
                lower.contains("esp") -> DeviceClass.APPLIANCE
            else -> null
        }
    }
}

/** What one source said about one device, before it is merged with everything else. */
data class Identified(
    val name: String? = null,
    val vendor: String? = null,
    val model: String? = null,
    val location: String? = null,
    val deviceClass: DeviceClass? = null,
    val evidence: List<Evidence> = emptyList(),
)

/**
 * Merging everything several protocols said about one address into one device.
 *
 * WHY THIS IS A PURE FUNCTION AND NOT PART OF THE SCAN. The scan is a mass of sockets and
 * timeouts, and the merge rule is the part that decides what a person actually sees. A rule
 * that lets a port guess overwrite a declared name is the fault that makes a scanner untrustworthy,
 * and it must be testable without a network. So it lives here.
 *
 * THE ONE RULE: a DECLARED fact always beats a DERIVED one, and both beat an INFERRED one. Within
 * the same rank, first writer wins, so the order sources are gathered in decides ties rather than
 * luck.
 */
object DeviceMerge {

    /**
     * Merge the reports for one address.
     *
     * [reports] may be empty, in which case a device that only proved it exists comes back with
     * no name: it is still a device that is there, and hiding it would be the lie.
     */
    fun merge(
        address: String,
        reports: List<Identified>,
        openPorts: List<Int> = emptyList(),
        services: List<String> = emptyList(),
    ): MergedDevice {
        if (reports.isEmpty()) {
            return MergedDevice(
                address = address,
                name = null,
                vendor = null,
                deviceClass = DeviceClass.UNKNOWN,
                openPorts = openPorts.sorted(),
                services = services.distinct(),
            )
        }

        // Rank by the best evidence each report carries, so a device that declared itself is
        // sorted ahead of one that was merely guessed at from its ports.
        val ranked = reports.sortedByDescending { bestRank(it.evidence) }

        val name = ranked.firstNotNullOfOrNull { it.name }
        val vendor = ranked.firstNotNullOfOrNull { it.vendor }
        val model = ranked.firstNotNullOfOrNull { it.model }
        val location = ranked.firstNotNullOfOrNull { it.location }
        val deviceClass = ranked.firstNotNullOfOrNull { it.deviceClass } ?: DeviceClass.UNKNOWN
        val allEvidence = reports.flatMap { it.evidence }.distinct()

        // A class that no protocol declared falls back to the port inference, and the evidence
        // records that it was a guess so a reader can tell the two apart.
        val inferred = if (ranked.none { it.deviceClass != null }) {
            DeviceIdentifier.classFromPorts(openPorts)?.let { guessed ->
                allEvidence + Evidence(
                    "ports",
                    "inferred from the open ports rather than from anything the device said",
                    EvidenceKind.INFERRED,
                ) to guessed
            }
        } else {
            null
        }

        return MergedDevice(
            address = address,
            name = name,
            vendor = vendor,
            model = model,
            location = location,
            deviceClass = deviceClass.takeIf { it != DeviceClass.UNKNOWN }
                ?: inferred?.second
                ?: DeviceClass.UNKNOWN,
            evidence = inferred?.first ?: allEvidence,
            openPorts = openPorts.sorted(),
            services = services.distinct(),
        )
    }

    /** The best evidence a report carries, used only for ordering. */
    private fun bestRank(evidence: List<Evidence>): Int = evidence.maxOfOrNull { rank(it.kind) } ?: 0

    private fun rank(kind: EvidenceKind): Int = when (kind) {
        EvidenceKind.DECLARED -> 3
        EvidenceKind.DERIVED -> 2
        EvidenceKind.INFERRED -> 1
    }
}

/** One device, after everything that answered about it has been merged. */
data class MergedDevice(
    val address: String,
    val name: String?,
    val vendor: String?,
    val model: String? = null,
    val location: String? = null,
    val deviceClass: DeviceClass = DeviceClass.UNKNOWN,
    val evidence: List<Evidence> = emptyList(),
    /**
     * Ports that accepted a connection on this sighting.
     *
     * CARRIED ON THE DEVICE RATHER THAN RE-DERIVED LATER. A map store that had to re-scan to learn
     * what ports a device had would write empty port lists into history, and a port that opened
     * between two sweeps is one of the changes a network map exists to show. The sweep knows this
     * at the moment it finds the device, so it travels with the device.
     */
    val openPorts: List<Int> = emptyList(),
    /** Services the device announced on this sighting. */
    val services: List<String> = emptyList(),
) {
    /** What to show in a list, which is never blank because an address is always something. */
    val displayName: String
        get() = name?.takeIf { it.isNotBlank() }
            ?: vendor?.takeIf { it.isNotBlank() }?.let { "$it device" }
            ?: address
}
