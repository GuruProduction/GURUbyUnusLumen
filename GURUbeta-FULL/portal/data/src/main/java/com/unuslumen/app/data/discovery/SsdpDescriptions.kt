// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.discovery

/**
 * Why a device has no name, which is a fact the caller needs and never had.
 *
 * ISSUE 333. `ssdpListDevices` returned entries with `friendlyName: ""` and
 * `manufacturer: ""` for the ARRIS router and both LG sets while naming the Amazon
 * and Virgin devices correctly. The failure was invisible: the fetch threw, the throw
 * was caught, and the catch wrote one line to the log. A caller saw an empty string
 * and had no way to tell a device that declares no name from one whose description
 * was never fetched, and `netEverything` then reported every device as UNKNOWN
 * because it had nothing to classify from.
 *
 * [UNNAMED] is deliberately not merged into [FAILED]. A description that was fetched
 * and parsed but genuinely carries no friendlyName is a fact about the device; a
 * description that was never fetched is a fact about this app. Same rule as every
 * other tool in this family: never report an absence of information as a measurement.
 */
enum class DescriptionState {
    /** No LOCATION header, so there is nothing to fetch. A fact about the device. */
    NO_LOCATION,
    /** Queued but the sweep finished first. Try again rather than concluding anything. */
    NOT_FETCHED,
    /** Fetched and parsed, with a name. */
    FETCHED,
    /** Fetched and parsed, and the device declares no friendlyName. */
    UNNAMED,
    /** The device's LOCATION is not on this network, so it was refused rather than fetched. */
    REFUSED_OFF_NETWORK,
    /** The LOCATION could not be turned into a URL even after normalising it. */
    BAD_LOCATION,
    /** The request failed or answered with a non-200 status. [descriptionDetail] says which. */
    HTTP_ERROR,
    /** The response was not XML this could parse. */
    UNPARSEABLE,
}

/**
 * The three fields worth having from a UPnP device description.
 *
 * Empty strings mean "the document does not declare it", which is different from
 * "the document was never read": that difference lives on [DescriptionState].
 */
data class SsdpDescription(
    val friendlyName: String = "",
    val manufacturer: String = "",
    val modelName: String = "",
) {
    /** True when the parsed document actually named the device. */
    val named: Boolean get() = friendlyName.isNotBlank()
}

/**
 * The LOCATION and description rules for SSDP, kept pure.
 *
 * WHY PURE. The defect issue 333 reports is a parsing and normalisation rule, and a
 * rule that needs a live network and a real LG television to test is a rule that never
 * gets tested. Everything here is a function of a string, so a JVM test pins it and the
 * device only has to confirm the plumbing.
 */
object SsdpDescriptions {

    /**
     * Turn a LOCATION header into a URL a fetch can use, or null when it cannot be.
     *
     * THE DEFECT THIS FIXES. The issue's own title names it: "LG sets at a bare
     * host:port LOCATION". A UPnP LOCATION is specified to be an absolute URL, but real
     * devices on real networks send `192.0.2.5:1234/desc.xml` with no scheme at all.
     * `URL("192.0.2.5:1234/desc.xml")` treats `192.0.2.5` as a protocol, so the parse
     * either throws or produces an unusable URL, the description is never fetched, and
     * the device stays unnamed while the ones that sent a proper URL are named.
     *
     * That is exactly the split the ticket describes: ARRIS and LG unnamed, Amazon and
     * Virgin named, on the same network at the same moment.
     *
     * The repair is a scheme prefix, and it is deliberately narrow: nothing else about
     * the string is rewritten, so a URL that was already absolute passes through byte
     * for byte and cannot be broken by this function.
     */
    fun normaliseLocation(raw: String?): String? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return null

        val withScheme = when {
            // Already absolute. Passed through untouched so this cannot alter a good URL.
            trimmed.startsWith("http://", ignoreCase = true) -> trimmed
            trimmed.startsWith("https://", ignoreCase = true) -> trimmed
            // A protocol-relative URL: the device named a path on the network but no scheme.
            trimmed.startsWith("//") -> "http:$trimmed"
            // A bare host:port or host/path, which is the case in the ticket's title.
            else -> "http://$trimmed"
        }

        // The host is what the fetch needs, and a LOCATION with no host is not a URL this
        // can use however it is spelled. Parsing here rather than at the call site means a
        // malformed value is reported as BAD_LOCATION instead of throwing inside a fetch
        // that logs and forgets.
        return runCatching {
            val uri = java.net.URI(withScheme)
            if (uri.host.isNullOrBlank()) null else withScheme
        }.getOrNull()
    }

    /**
     * The host of a normalised LOCATION, for the on-this-network check.
     *
     * Falls back to [sourceIp] only when the LOCATION yields no host, because a device
     * that answered on the wire is on the wire even if its header is nonsense. That is
     * the same fallback the old code had, kept deliberately.
     */
    fun hostOf(normalisedLocation: String?, sourceIp: String): String {
        if (normalisedLocation.isNullOrBlank()) return sourceIp
        return runCatching { java.net.URI(normalisedLocation).host }.getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: sourceIp
    }

    /**
     * Extract the three name fields from a device description document.
     *
     * WHY A REAL PARSER AND NOT A REGEX. The old extraction was
     * `"<[^>]*$tagName[^>]*>([^<]*)<[^>]*$tagName[^>]*>"`, which has three faults that
     * all produce a silent empty string:
     *
     *  - it requires the tag to be closed with the same spelling, so a prefix on one side
     *    or a self-closing tag yields nothing;
     *  - `[^<]*` refuses any value containing markup, including an escaped entity that a
     *    device encoded differently;
     *  - matching on a substring means `<manufacturerURL>` satisfies a search for
     *    `manufacturer` whenever it happens to come first in the document, which fills
     *    the field with a URL.
     *
     * A namespace-aware DOM parse answers the real question — find the element whose
     * local name is `friendlyName` — and decodes entities on the way. It is a few more
     * lines and it cannot be wrong in those three ways.
     *
     * Android ships both the parser and the namespace handling, so this costs no
     * dependency.
     */
    fun parse(xml: String): SsdpDescription? {
        if (xml.isBlank()) return null

        return runCatching {
            val factory = javax.xml.parsers.DocumentBuilderFactory.newInstance()
            // Namespace-aware so a `urn:schemas-upnp-org:device-1-0` document with no prefix
            // and one with a `d:` prefix are both read the same way.
            factory.isNamespaceAware = true
            // A device description is a local document from a device on the same wifi, but it
            // is still untrusted input, so external entity resolution is off: a device must
            // not be able to make GURU fetch anything by declaring it in its own XML.
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            factory.isExpandEntityReferences = false

            val builder = factory.newDocumentBuilder()
            val document = builder.parse(xml.byteInputStream(Charsets.UTF_8))
            document.documentElement?.normalize()

            SsdpDescription(
                friendlyName = firstTextOf(document, "friendlyName"),
                manufacturer = firstTextOf(document, "manufacturer"),
                modelName = firstTextOf(document, "modelName"),
            )
        }.getOrNull()
    }

    /**
     * The text of the first element whose LOCAL name matches, ignoring namespace prefixes.
     *
     * Compared with `equals` on the local name rather than a substring, so
     * `manufacturerURL` can never satisfy a search for `manufacturer`.
     */
    private fun firstTextOf(
        document: org.w3c.dom.Document,
        localName: String,
    ): String {
        val nodes = document.getElementsByTagNameNS("*", localName)
        for (i in 0 until nodes.length) {
            val text = nodes.item(i)?.textContent?.trim().orEmpty()
            if (text.isNotEmpty()) return text
        }
        // Fallback for a document that declares no namespace at all: getElementsByTagNameNS
        // with "*" still matches unqualified names on most parsers, but not on all, so the
        // plain form is tried rather than reporting a named device as unnamed.
        val plain = document.getElementsByTagName(localName)
        for (i in 0 until plain.length) {
            val text = plain.item(i)?.textContent?.trim().orEmpty()
            if (text.isNotEmpty()) return text
        }
        return ""
    }

    /**
     * The state a fetched description should be recorded as.
     *
     * A document that parsed and named nothing is [DescriptionState.UNNAMED], which is a
     * statement about the device. A document that did not parse is [UNPARSEABLE], which is
     * a statement about us. Collapsing the two is the mistake this whole family removes.
     */
    fun stateOf(parsed: SsdpDescription?): DescriptionState = when {
        parsed == null -> DescriptionState.UNPARSEABLE
        parsed.named -> DescriptionState.FETCHED
        else -> DescriptionState.UNNAMED
    }

    /**
     * One sentence for a device whose name is missing, naming the reason.
     *
     * This is what goes in `problems[]`, because a device that answered SSDP and could not
     * be named is exactly the "discovered-but-dropped" case the tracking issue asks to be
     * made visible.
     */
    fun problemSentence(host: String, usn: String, state: DescriptionState, detail: String?): String {
        val what = if (host.isBlank()) usn else host
        return when (state) {
            DescriptionState.FETCHED -> "$what was named from its description."
            DescriptionState.NO_LOCATION ->
                "$what answered SSDP with no LOCATION header, so there is no description to " +
                    "read and its name is genuinely absent rather than unfetched."
            DescriptionState.NOT_FETCHED ->
                "$what was found and its description was still being fetched when the list was " +
                    "read, so an empty name here is not a fact about the device. Read again."
            DescriptionState.REFUSED_OFF_NETWORK ->
                "$what advertised a description outside this network, so it was not fetched. " +
                    "That is a refusal, not a device with no name."
            DescriptionState.BAD_LOCATION ->
                "$what sent a LOCATION that is not a usable address even after adding a scheme, " +
                    "so its description could not be requested."
            DescriptionState.HTTP_ERROR ->
                "$what does not serve its description over HTTP right now" +
                    (detail?.let { ": $it" } ?: ".") +
                    " The device is on the network and unnamed because of that, not because " +
                    "it has no name."
            DescriptionState.UNPARSEABLE ->
                "$what served a description that is not XML this could read, so its name is " +
                    "unknown rather than absent" + (detail?.let { ": $it" } ?: ".")
            DescriptionState.UNNAMED ->
                "$what served a readable description that declares no friendlyName, so the " +
                    "device genuinely has no name to report."
        }
    }
}
