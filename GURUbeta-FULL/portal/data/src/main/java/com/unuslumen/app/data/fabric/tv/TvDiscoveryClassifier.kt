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
        val modelName = descriptionXml?.let { tag(it, "modelName") }
        val manufacturer = descriptionXml?.let { tag(it, "manufacturer") }

        // The manufacturer field first, because it is the only field a vendor always
        // fills in with its own name.
        val haystack = listOf(manufacturer ?: "", server, descriptionXml ?: "", friendly ?: "")
            .joinToString(" ")
            .lowercase()

        // THE MODEL PREFIX IS A STRONGER SIGNAL THAN THE MANUFACTURER WORD.
        //
        // Issue #243: a Fire TV reports `manufacturer: Amazon` and `modelName: AFTKM`.
        // There was no Amazon rule, so it fell through to a port guess, and because 8009
        // is open on it the guess said ANDROID_TV. 8009 is the DIAL App-Launch endpoint
        // and is shared by Fire TV, Chromecast, Android TV and Virgin boxes alike, so a
        // port can never decide this. Amazon's own model prefix can, and it is checked
        // before anything looser.
        //
        // `AFT` covers every Fire TV model name (AFTKM, AFTMM, AFTKA and the rest), and
        // is anchored to a word boundary so it cannot match a word that merely contains
        // those letters.
        val vendor = when {
            manufacturer?.contains("amazon", ignoreCase = true) == true -> TvVendor.AMAZON_FIRE_TV
            modelName?.let { Regex("""\bAFT[A-Z0-9]*""").containsMatchIn(it) } == true -> TvVendor.AMAZON_FIRE_TV
            haystack.contains("fire tv") || haystack.contains("firetv") -> TvVendor.AMAZON_FIRE_TV
            haystack.contains("samsung") -> TvVendor.SAMSUNG
            haystack.contains("lg electronics") || haystack.contains("webos") -> TvVendor.LG
            haystack.contains("philips") -> TvVendor.PHILIPS
            haystack.contains("vizio") -> TvVendor.VIZIO
            haystack.contains("androidtv") || haystack.contains("android tv") -> TvVendor.ANDROID_TV
            haystack.contains("roku") -> TvVendor.ROKU
            // Virgin Media's boxes announce themselves as EOSSTB with the manufacturer
            // "Virgin TV". There was no rule for either string, so the reply fell through
            // to a refusal and the set vanished while netHosts listed it (issue #234).
            haystack.contains("eosstb") || haystack.contains("virgin tv") ||
                haystack.contains("liberty global") -> TvVendor.UNKNOWN
            // A DIAL service with no maker named is still a screen, but which one is
            // unknown, and guessing a vendor would send the wrong protocol.
            st.contains("dial-multiscreen", ignoreCase = true) && friendly != null -> TvVendor.UNKNOWN
            else -> return null
        }

        // A SCREEN THIS BUILD CANNOT DRIVE IS STILL A SCREEN THAT WAS FOUND.
        //
        // This used to return null for every unidentified device, so a set that answered
        // and named itself was reported as absent. That is the worst possible answer: a
        // person looking at their own television is told there is nothing there. Issue #234
        // was exactly that, because the Virgin box had no rule of its own.
        //
        // THE GUARD IS STILL NEEDED, just narrower. A printer answers UPnP and can even
        // carry a DIAL service target, and listing it as a television offers a device that
        // refuses every command. So an unknown make is only reported when the device also
        // described itself as a screen: a MediaRenderer, a DIAL multiscreen service with a
        // friendly name, or one of the set-top-box strings. Otherwise it is not a telly and
        // this returns null, exactly as before.
        if (vendor == TvVendor.UNKNOWN && !looksLikeAScreen(st, haystack, friendly)) return null

        // THE PORT A COMMAND ACTUALLY GOES TO, per vendor.
        //
        // A Fire TV's control endpoint is DIAL on 8009, which is why it answers
        // `Application-URL: http://host:8009/apps/`. Its description document lives on
        // 60000, and that is where the manufacturer and model were read from, not where
        // a launch is sent.
        val port = when (vendor) {
            TvVendor.ROKU -> 8060
            TvVendor.SAMSUNG -> 8001
            TvVendor.LG -> 3000
            TvVendor.VIZIO -> 7345
            TvVendor.PHILIPS -> 1925
            TvVendor.ANDROID_TV -> 6466
            TvVendor.AMAZON_FIRE_TV -> 8009
            TvVendor.UNKNOWN -> 0
        }

        return DiscoveredTv(
            vendor = vendor,
            name = friendly ?: manufacturer ?: vendor.name.lowercase().replaceFirstChar { it.uppercase() },
            host = host,
            port = port,
            evidence = "answered UPnP as ${st.ifBlank { "a media device" }}" +
                if (manufacturer != null) ", made by $manufacturer" else "",
            // PAIRING IS A PROPERTY OF THE CONTROL PATH, NOT OF THE SET.
            //
            // A Fire TV accepts DIAL app launches with no pairing at all, so listing it as
            // needing one is the false requirement issue #243 reported. What genuinely needs
            // a pairing code is its button channel, and that is stated in the refusal for a
            // press where it is true, rather than as a flag saying the device is unusable.
            needsPairing = vendor != TvVendor.ROKU &&
                vendor != TvVendor.ANDROID_TV &&
                vendor != TvVendor.AMAZON_FIRE_TV,
        )
    }

    /**
     * Whether an unidentified device still described itself as something you watch.
     *
     * The bar is a MediaRenderer device type, the Liberty Global set-top-box strings, or a
     * DIAL multiscreen service WITH a friendly name. A printer that happens to answer a
     * DIAL search target but carries no screen identity fails all three, which is what
     * keeps it out of the television list.
     */
    private fun looksLikeAScreen(st: String, haystack: String, friendly: String?): Boolean = when {
        haystack.contains("mediarenderer") -> true
        haystack.contains("eosstb") || haystack.contains("virgin tv") ||
            haystack.contains("liberty global") -> true
        st.contains("dial-multiscreen", ignoreCase = true) &&
            friendly != null && friendly.contains("tv", ignoreCase = true) -> true
        else -> false
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
