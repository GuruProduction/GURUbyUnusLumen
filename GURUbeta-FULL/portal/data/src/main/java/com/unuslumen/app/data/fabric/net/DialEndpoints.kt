// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.fabric.net

/**
 * Where a device's DIAL description actually lives. Issue #251.
 *
 * WHY THIS EXISTS, AND IT IS THE WHOLE BUG. [DialClient] asked for
 * `/ssdp/device-desc.xml` on the port it was handed, and nothing else. No device on this
 * network serves that path. The two televisions here serve:
 *
 *  - a Roku at `/dial/dd.xml` on 8060
 *  - a Fire TV at `/dd.xml` on 60000, with its app endpoint on 8009
 *
 * So every call was a 404, and the refusal then blamed the device for not exposing DIAL.
 * It exposes it perfectly. The client was asking the wrong question and reading the 404 as
 * an answer about the device.
 *
 * WHAT THE PROTOCOL ACTUALLY SAYS. The description location is not a constant anywhere in
 * DIAL. It arrives two ways, and both are the DEVICE's answer rather than ours:
 *
 *  1. The SSDP `LOCATION` header of the discovery reply, which is an absolute URL and is
 *     authoritative when present. That is also the only field that can put the description
 *     on a different port from the app endpoint, which is exactly what the Fire TV does.
 *  2. `GET /` returning a `Application-URL` header, for a device that answers root but
 *     serves no separate description document.
 *
 * So this is an ordered fallback list: the caller's known LOCATION first, then the paths
 * that real devices on this network have been observed to use, then root. Every candidate
 * is tried and the first that answers is the answer. Nothing is assumed and nothing is
 * guessed at the HTTP layer.
 *
 * WHY A PURE OBJECT. This is the decision that was wrong, so it is the thing a JVM test can
 * pin without a network. The client does the trying; the list is here.
 */
object DialEndpoints {

    /**
     * The description paths to try on a device's DIAL port, in order.
     *
     * ORDERED BY WHAT WAS OBSERVED WORKING, with the two known-good shapes first. `/ssdp/device-desc.xml`
     * stays in the list because some UPnP stacks do serve it and trying a cheap 404 costs one
     * request; what was wrong was trying ONLY it.
     */
    val descriptionPaths: List<String> = listOf(
        // The Roku's own path, confirmed on a live set.
        "/dial/dd.xml",
        // The Fire TV's own path, confirmed on a live set.
        "/dd.xml",
        // The documented DIAL shape on many implementations.
        "/ssdp/device-desc.xml",
        // The UPnP device description, which carries friendlyName and often Application-URL.
        "/description.xml",
        // Root, which some devices answer with the Application-URL header alone.
        "/",
    )

    /**
     * The candidate description URLs for one device.
     *
     * [knownLocation] is the SSDP `LOCATION` when discovery already saw one. It goes FIRST
     * and unconditionally, because it is the device's own statement of where its description
     * is, and the one thing this bug proved is that our guesses are worse than the device's
     * word. It may name a different host or port from [host] and [port], which is correct:
     * the Fire TV publishes its description on 60000 and its apps on 8009.
     *
     * Duplicates are removed while keeping order, so a LOCATION that happens to equal one of
     * the fallbacks is not requested twice.
     */
    fun descriptionCandidates(
        host: String,
        port: Int,
        knownLocation: String? = null,
    ): List<String> {
        val candidates = mutableListOf<String>()

        knownLocation?.trim()?.takeIf { it.isNotEmpty() }?.let { candidates += it }

        for (path in descriptionPaths) {
            candidates += "http://$host:$port$path"
        }

        // THE FIRE TV'S DESCRIPTION PORT IS NOT ITS APP PORT, AND DISCOVERY RECORDS THE APP
        // PORT. A Fire TV answers its app endpoint on 8009, which is what discovery stores,
        // while its description document lives on 60000. So a caller that only knows the set
        // by its app port has to be allowed to find the description next door. These are the
        // ports DIAL devices are documented and observed to publish a description on.
        if (port != 60000) {
            for (path in descriptionPaths) {
                candidates += "http://$host:60000$path"
            }
        }

        return candidates.distinct()
    }

    /**
     * Whether a status means this candidate is worth asking.
     *
     * A 404 or 405 says this path is not the description, which is information about the
     * candidate and not a refusal of the device, so the next one is tried. Anything else,
     * including 403, is the device answering, and answering stops the search.
     */
    fun keepsSearching(status: Int): Boolean = status == 404 || status == 405

    /**
     * Whether a response is a usable DIAL description.
     *
     * A description must carry an `Application-URL`, because that is the only field naming
     * where a launch goes. A body with `<name>` entries but no application URL is a device
     * that cannot be launched from here, which is a fact to report rather than a candidate
     * to keep hunting through.
     */
    fun isUsable(applicationUrl: String?): Boolean = !applicationUrl.isNullOrBlank()

    /**
     * The app name a DIAL launch needs, from the name a person says.
     *
     * DIAL app names are the vendor's own strings, and they are case-sensitive on some
     * implementations, so this maps a spoken name onto the published spelling rather than
     * passing whatever was typed through.
     */
    fun appNameFor(spoken: String): String {
        val normalised = spoken.trim().lowercase()
            .replace("+", " plus")
            .replace("-", " ")
            .replace(Regex("\\s+"), " ")
        return when (normalised) {
            "netflix" -> "Netflix"
            "youtube" -> "YouTube"
            "prime video", "amazon", "amazon prime" -> "Amazon"
            "bbc iplayer" -> "BBC iPlayer"
            "itvx", "itv hub" -> "ITVX"
            "channel 4", "all 4" -> "Channel4"
            "disney plus", "disney+" -> "Disney"
            "spotify" -> "Spotify"
            "plex" -> "Plex"
            "apple tv" -> "AppleTV"
            "now", "now tv" -> "NOWTV"
            "britbox" -> "BritBox"
            // An unknown name is passed as typed rather than refused, because DIAL devices
            // accept app names this build has never heard of and the device is the authority.
            else -> spoken.trim()
        }
    }

    /**
     * What a launch status means.
     *
     * DIAL defines 201 for a launch that STARTED the app and 200 for a request the device
     * accepted without starting anything. Reporting the second as the first is the defect in
     * this issue: both were called success, so a launch that did nothing read as a launch.
     */
    enum class LaunchOutcome {
        /** 201: the app was started. This is the only status that is a launch. */
        STARTED,

        /** 200: the device accepted the request but did not start the app. */
        ACCEPTED_NOT_STARTED,

        /** 2xx other than 200 or 201: the device answered, and did something else. */
        OTHER_SUCCESS,

        /** 4xx or 5xx: the device refused, and its own words are the answer. */
        REFUSED,
    }

    /** Classify a launch status. Only [LaunchOutcome.STARTED] is a launch. */
    fun launchOutcome(status: Int): LaunchOutcome = when {
        status == 201 -> LaunchOutcome.STARTED
        status == 200 -> LaunchOutcome.ACCEPTED_NOT_STARTED
        status in 200..299 -> LaunchOutcome.OTHER_SUCCESS
        else -> LaunchOutcome.REFUSED
    }
}
