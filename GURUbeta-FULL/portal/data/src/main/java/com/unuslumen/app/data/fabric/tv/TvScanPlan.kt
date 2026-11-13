// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.fabric.tv

/**
 * The rules that decide what a device on this network is, with no sockets and no Android.
 *
 * WHY THIS IS SEPARATE FROM [TvDiscovery]. These are the decisions that make a universal
 * remote universal: get one wrong and a person's Samsung is sent LG commands, or a set
 * that was answering perfectly well never appears at all. They are pure text and list
 * matching, so a JVM test pins the exact rule the app runs rather than a second copy
 * written inside the test that could drift from it.
 */
object TvScanPlan {

    /** The Bonjour services that identify a screen, shared with the network scanner. */
    val mdnsServices: List<String>
        get() = com.unuslumen.app.data.fabric.net.MdnsQuery.screenServices

    /** The port a vendor's remote protocol answers on. */
    fun defaultPortFor(vendor: TvVendor): Int = when (vendor) {
        TvVendor.ROKU -> 8060
        TvVendor.SAMSUNG -> 8001
        TvVendor.LG -> 3000
        TvVendor.VIZIO -> 7345
        TvVendor.PHILIPS -> 1925
        TvVendor.ANDROID_TV -> 6466
        // DIAL, which is where a Fire TV accepts an app launch. Its description document
        // is on 60000, but that is where the make is read from, not where commands go.
        TvVendor.AMAZON_FIRE_TV -> 8009
        TvVendor.UNKNOWN -> 0
    }

    /**
     * Which vendor a Bonjour service set names, or null when it names none.
     *
     * AN AIRPLAY ANNOUNCEMENT USED TO RETURN NULL HERE, AND THAT WAS ISSUE 331. The service
     * names are queried, so every AirPlay receiver on the network spends power answering,
     * and this function then said "not a screen" and the reply was discarded. Asking a
     * network a question and throwing the answer away is worse than never asking.
     *
     * The vendor is UNKNOWN because the service type names no make. Guessing which box it is
     * would send the wrong protocol, and UNKNOWN is the value that already means "found, and
     * this build has no adapter for it", which is the truth about AirPlay here.
     *
     * A Chromecast still returns null: `_googlecast._tcp` is a media protocol with no screen
     * identity of its own, and it is present on speakers and displays alike.
     */
    fun vendorFromMdns(services: Set<String>): TvVendor? = when {
        services.any { it.startsWith("_androidtvremote2") } -> TvVendor.ANDROID_TV
        services.any { it.startsWith("_airplay") || it.startsWith("_raop") } -> TvVendor.UNKNOWN
        else -> null
    }

    /** Which vendor a banner names, or null when it names none of them. */
    fun vendorFromBanner(banner: String): TvVendor? {
        val haystack = banner.lowercase()
        return when {
            haystack.contains("roku") -> TvVendor.ROKU
            haystack.contains("samsung") || haystack.contains("tizen") -> TvVendor.SAMSUNG
            haystack.contains("webos") || haystack.contains("lg electronics") -> TvVendor.LG
            haystack.contains("vizio") || haystack.contains("smartcast") -> TvVendor.VIZIO
            haystack.contains("philips") || haystack.contains("jointspace") -> TvVendor.PHILIPS
            haystack.contains("virgin tv") || haystack.contains("eosstb") ||
                haystack.contains("liberty global") -> TvVendor.UNKNOWN
            haystack.contains("androidtv") || haystack.contains("android tv") ||
                haystack.contains("google tv") -> TvVendor.ANDROID_TV
            else -> null
        }
    }

    /**
     * Which vendor a UPnP description document names.
     *
     * THIS IS HOW A VIRGIN MEDIA BOX IS FOUND. A set announced itself over UPnP with the
     * manufacturer string "Virgin TV" and a model beginning "000378-EOSSTB-". The
     * classifier had no rule for it, so the reply fell through to a refusal and the set
     * vanished while `netHosts` listed it. EOSSTB is the Liberty Global set-top-box platform
     * the Virgin boxes run, so it is matched directly rather than only through the brand.
     */
    fun vendorFromDescription(description: String): TvVendor? {
        val haystack = description.lowercase()
        return when {
            // AMAZON FIRST, BECAUSE ITS DOCUMENT IS UNAMBIGUOUS AND A PORT IS NOT.
            //
            // Issue #243: `manufacturer: Amazon` and a `modelName` beginning AFT are what a
            // Fire TV publishes, and no other make does. The field the device declared about
            // itself must beat any guess made from an open port, so this is checked before
            // everything else, and `androidtv` is checked after it — a Fire TV's description
            // mentions Android nowhere, but a document that mentions both is still an Amazon
            // device when Amazon is the manufacturer.
            haystack.contains("amazon") || Regex("""\baft[a-z0-9]*""").containsMatchIn(haystack) ->
                TvVendor.AMAZON_FIRE_TV
            haystack.contains("roku") -> TvVendor.ROKU
            haystack.contains("samsung") || haystack.contains("tizen") -> TvVendor.SAMSUNG
            haystack.contains("webos") || haystack.contains("lg electronics") -> TvVendor.LG
            haystack.contains("vizio") || haystack.contains("smartcast") -> TvVendor.VIZIO
            haystack.contains("philips") || haystack.contains("jointspace") -> TvVendor.PHILIPS
            haystack.contains("androidtv") || haystack.contains("android tv") ||
                haystack.contains("google tv") -> TvVendor.ANDROID_TV
            // Recognised as a set, with no adapter in this build. Reported as found and not
            // drivable rather than dropped, because a person needs to know their telly was
            // seen and why nothing happens.
            haystack.contains("eosstb") || haystack.contains("virgin tv") ||
                haystack.contains("liberty global") -> TvVendor.UNKNOWN
            else -> null
        }
    }

    /** The manufacturer string a description reports, for the evidence line. */
    fun manufacturerOf(description: String): String? =
        tag(description, "manufacturer")

    /**
     * Which vendor a SET OF OPEN PORTS suggests, when nothing on the device named itself.
     *
     * THE COMBINATION IS THE EVIDENCE, NOT A SINGLE PORT. This is the rule that finds a
     * streaming box answering on 5555 (adb) and 8009 (Cast v2) that published no service
     * description and served no banner. That combination is an Android or Fire TV device and
     * nothing else on a home network.
     *
     * A single ambiguous port decides nothing: 8009 alone is a Chromecast that this build
     * cannot drive, so it is not called a television on that basis.
     */
    fun vendorFromPorts(ports: List<Int>): TvVendor? {
        val has = ports.toSet()
        return when {
            // 8009 PLUS 5555 IS DELIBERATELY AMBIGUOUS AND THEREFORE ABSENT. Issue #243.
            //
            // 8009 is DIAL, which Fire TV, Chromecast, Android TV and Virgin boxes all open,
            // and 5555 is the Android debug bridge. The pair proves the host is an Android-
            // based streaming device; it does NOT prove which make, and a make is what decides
            // the adapter and the control story. Naming Amazon here would be exactly the
            // defect #243 filed, one guess further along: the set had already declared
            // `manufacturer: Amazon` and was being overruled by a port count.
            //
            // A host in this state has no vendor this build can name, so it is reported as
            // UNKNOWN with the ports it exposes as evidence, and the description read in
            // [TvDiscovery.identifyHost] has already had first refusal on naming it.
            8009 in has && 5555 in has -> TvVendor.UNKNOWN
            // Roku's own port is already decisive elsewhere; included for completeness.
            8060 in has -> TvVendor.ROKU
            // Samsung, LG, Vizio and Philips all answer on their own remote port.
            8001 in has || 8002 in has -> TvVendor.SAMSUNG
            3000 in has || 3001 in has -> TvVendor.LG
            7345 in has -> TvVendor.VIZIO
            1925 in has || 1926 in has || 9000 in has -> TvVendor.PHILIPS
            // The Android TV Remote v2 port, which needs no second port to be decisive.
            6466 in has || 6467 in has -> TvVendor.ANDROID_TV
            else -> null
        }
    }

    /**
     * Build a DNS-SD PTR query, delegated to the one shared builder.
     *
     * The unicast-response bit that makes Bonjour answers reachable at all is explained in
     * [com.unuslumen.app.data.fabric.net.MdnsQuery], and this delegates rather than keeping
     * a second copy of the byte layout that could drift from it.
     */
    fun dnsPtrQuery(name: String): ByteArray =
        com.unuslumen.app.data.fabric.net.MdnsQuery.ptrQuery(name)

    private fun tag(xml: String, name: String): String? =
        Regex("<$name[^>]*>([^<]*)</$name>", RegexOption.IGNORE_CASE)
            // DECODED AT EXTRACTION, NOT AT THE RESOLVER (#216, #223). A set whose real name
        // contains an apostrophe serves it as `&apos;`, and leaving it encoded made the name
        // impossible to address while looking like a string the person could type.
        .find(xml)?.groupValues?.getOrNull(1)?.let { XmlText.decodeAndTrim(it) }?.ifBlank { null }
}
