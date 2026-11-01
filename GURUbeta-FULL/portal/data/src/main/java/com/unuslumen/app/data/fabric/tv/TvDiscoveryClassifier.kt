// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.fabric.tv

/**
 * Working out which make a device is, with no network and no Android. Issue #187.
 *
 * WHY THIS IS SEPARATE FROM [TvDiscovery]. Classification is the decision that makes a
 * universal remote universal: get it wrong and a person's Samsung is sent LG commands.
 * It is pure text matching, so pulling it out here means a JVM test proves the exact
 * rule the app runs, rather than a second copy written inside a test that could drift.
 *
 * WHAT IT REFUSES TO DO. It returns null for a device it cannot place. A printer and a
 * router answer UPnP too, and a device in the list that cannot be controlled reads as a
 * broken app rather than as a device that is not a television.
 *
 * THE MATCH ORDER MATTERS. A Samsung set's description mentions UPnP and DLNA, and so
 * does LG's, so the manufacturer field is the strongest signal and is checked before
 * the looser server string.
 */
object TvDiscoveryClassifier {

    /**
     * Classify one device.
     *
     * [ssdpBody] is the raw reply, whose SERVER and ST headers carry the stack name.
     * [descriptionXml] is the device description document when it could be fetched,
     * which is where the make and the friendly name actually live.
     */
    fun classify(host: String, ssdpBody: String, descriptionXml: String?): DiscoveredTv? {
        val headers = ssdpBody.lines()
            .mapNotNull { line ->
                val colon = line.indexOf(':')
                if (colon <= 0) null
                else line.substring(0, colon).trim().lowercase() to line.substring(colon + 1).trim()
            }
            .toMap()

        val server = headers["server"] ?: ""
        val st = headers["st"] ?: headers["nt"] ?: ""

        val friendly = descriptionXml?.let { tag(it, "friendlyName") }
            ?: descriptionXml?.let { tag(it, "modelName") }
        val manufacturer = descriptionXml?.let { tag(it, "manufacturer") }

        // The manufacturer field first, because it is the only field a vendor always
        // fills in with its own name.
        val haystack = listOf(manufacturer ?: "", server, descriptionXml ?: "", friendly ?: "")
            .joinToString(" ")
            .lowercase()

        val vendor = when {
            haystack.contains("samsung") -> TvVendor.SAMSUNG
            haystack.contains("lg electronics") || haystack.contains("webos") -> TvVendor.LG
            haystack.contains("philips") -> TvVendor.PHILIPS
            haystack.contains("vizio") -> TvVendor.VIZIO
            haystack.contains("androidtv") || haystack.contains("android tv") -> TvVendor.ANDROID_TV
            haystack.contains("roku") -> TvVendor.ROKU
            // A DIAL service with no maker named is still a screen, but which one is
            // unknown, and guessing a vendor would send the wrong protocol.
            st.contains("dial-multiscreen", ignoreCase = true) && friendly != null -> TvVendor.UNKNOWN
            else -> return null
        }

        // An unidentified screen is not reported at all: it cannot be driven by any
        // adapter this build has, so listing it would offer a device that refuses.
        if (vendor == TvVendor.UNKNOWN) return null

        val port = when (vendor) {
            TvVendor.ROKU -> 8060
            TvVendor.SAMSUNG -> 8001
            TvVendor.LG -> 3000
            TvVendor.VIZIO -> 7345
            TvVendor.PHILIPS -> 1925
            TvVendor.ANDROID_TV -> 6466
            TvVendor.UNKNOWN -> 0
        }

        return DiscoveredTv(
            vendor = vendor,
            name = friendly ?: manufacturer ?: vendor.name.lowercase().replaceFirstChar { it.uppercase() },
            host = host,
            port = port,
            evidence = "answered UPnP as ${st.ifBlank { "a media device" }}" +
                if (manufacturer != null) ", made by $manufacturer" else "",
            needsPairing = vendor != TvVendor.ROKU && vendor != TvVendor.ANDROID_TV,
        )
    }

    /** The text of one XML tag, or null when it is absent. */
    private fun tag(xml: String, name: String): String? =
        Regex("<$name[^>]*>([^<]*)</$name>", RegexOption.IGNORE_CASE)
            .find(xml)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            ?.ifBlank { null }
}
