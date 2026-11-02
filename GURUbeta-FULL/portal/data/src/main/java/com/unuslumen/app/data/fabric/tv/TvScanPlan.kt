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
        TvVendor.UNKNOWN -> 0
    }

    /**
     * Which vendor a Bonjour service set names, or null when it names none this build can
     * drive. A Chromecast and an Apple TV announce themselves and both have no adapter, so
     * they return null here rather than appearing as a set that refuses.
     */
    fun vendorFromMdns(services: Set<String>): TvVendor? = when {
        services.any { it.startsWith("_androidtvremote2") } -> TvVendor.ANDROID_TV
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
            // A cast port plus the Android debug bridge is a streaming box, not a telly.
            8009 in has && 5555 in has -> TvVendor.ANDROID_TV
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
            .find(xml)?.groupValues?.getOrNull(1)?.trim()?.ifBlank { null }
}
