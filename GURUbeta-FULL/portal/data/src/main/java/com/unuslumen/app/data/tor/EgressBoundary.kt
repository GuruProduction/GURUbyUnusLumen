// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.tor

/**
 * What the egress boundary actually guarantees, stated so a model can tell a person the truth.
 *
 * WHY THIS EXISTS AT ALL (issue 336). Its own words: the architecture says all traffic routes
 * through Tor and fails closed, and that is the security boundary rather than a convenience
 * feature. It then measures two holes — Python raw sockets and the whole Termux prefix — and points
 * out that neither produces an error. A `pip install` succeeds. A `curl` returns the right document.
 * The person gets no signal at any point that their real address has just been handed to a remote
 * server.
 *
 * The issue names three routes to closing that. The first is a transparent redirect at the network
 * layer, which is real network engineering. The second needs a tier Android does not offer. The
 * third is to **document the boundary honestly**, and the ticket is explicit that this is the
 * minimum that should ship now, "because at the moment the protection is presented as
 * unconditional and it is not."
 *
 * This file is that minimum, as data rather than as prose in a document, so it can be asserted in a
 * test and read by a tool. It is not a fix and it does not pretend to be.
 *
 * THE DISTINCTION IT DRAWS, which is the whole content: a guarantee means the app's own code does
 * the routing and there is no path around it. A condition means the app asks nicely through an
 * environment variable, which a well-behaved library honours and a raw socket ignores.
 */
object EgressBoundary {

    /**
     * How much a given tool's traffic is actually protected.
     *
     * GUARANTEED: the app builds the connection itself. Tor, or a refusal while it waits. There is
     * no code path in the tool that opens a socket the classifier has not seen.
     *
     * CONDITIONAL: the app sets proxy environment variables and the program decides whether to
     * honour them. True of every interpreter and every command-line tool, and true of a raw socket
     * in none of them.
     *
     * DIRECT: the tool is meant to reach the local network, so its traffic does not leave the house.
     * Not a hole, but it must not be reported as protected either, because "in circuit" would be a
     * false statement about a router fetch.
     */
    enum class Protection { GUARANTEED, CONDITIONAL, DIRECT }

    /** One tool's entry in the boundary table. */
    data class Entry(
        /** The tool name as the model sees it. */
        val tool: String,
        val protection: Protection,
        /** Why it is what it is, in a sentence that does not use the word "proxy". */
        val reason: String,
    )

    /**
     * The table.
     *
     * SOURCES ARE THE ISSUE'S OWN AUDIT, which measured each path rather than assuming. Anything not
     * listed here is CONDITIONAL by [protectionOf], because an unlisted tool has no claim on a
     * guarantee and defaulting to GUARANTEED would be the exact overstatement this file exists to
     * remove.
     */
    val entries: List<Entry> = listOf(
        Entry(
            tool = "webSearch",
            protection = Protection.GUARANTEED,
            reason = "GURU builds the request itself and routes it through Tor.",
        ),
        Entry(
            tool = "webFetch",
            protection = Protection.GUARANTEED,
            reason = "GURU builds the request itself and routes it through Tor.",
        ),
        Entry(
            tool = "httpGet",
            protection = Protection.GUARANTEED,
            reason = "GURU builds the request itself and routes it through Tor.",
        ),
        Entry(
            tool = "httpPost",
            protection = Protection.GUARANTEED,
            reason = "GURU builds the request itself and routes it through Tor.",
        ),
        Entry(
            tool = "httpPut",
            protection = Protection.GUARANTEED,
            reason = "GURU builds the request itself and routes it through Tor.",
        ),
        Entry(
            tool = "busyboxExec",
            protection = Protection.CONDITIONAL,
            reason = "curl and wget are given the Tor settings when GURU runs them, and a program " +
                "that ignores those settings would not be routed.",
        ),
        Entry(
            tool = "executePythonScript",
            protection = Protection.CONDITIONAL,
            reason = "Python is told the Tor settings and libraries like urllib or requests honour " +
                "them. A script that opens a raw socket bypasses them completely, and there is no " +
                "signal when it does.",
        ),
        Entry(
            tool = "termuxExec",
            protection = Protection.CONDITIONAL,
            reason = "Every command in the Termux environment is given the Tor settings, which " +
                "curl, git, pip, npm and apt honour. A binary with its own network stack, or one " +
                "that resolves a name itself, is not routed and says nothing.",
        ),
        Entry(
            tool = "networkScan",
            protection = Protection.DIRECT,
            reason = "This reads this network, so it goes direct by design and leaves the house " +
                "nowhere.",
        ),
    )

    /**
     * The protection for any tool, defaulting to the honest answer rather than the flattering one.
     *
     * AN UNLISTED TOOL HAS MADE NO CLAIM, so CONDITIONAL is the only defensible default. Defaulting
     * to GUARANTEED would turn every tool added later into a silent overstatement, which is how the
     * boundary came to be described as unconditional in the first place.
     */
    fun protectionOf(tool: String): Protection =
        entries.firstOrNull { it.tool.equals(tool, ignoreCase = true) }?.protection
            ?: Protection.CONDITIONAL

    /**
     * One sentence about a tool, for its description or for a result.
     *
     * THE CONDITIONAL SENTENCE IS THE IMPORTANT ONE, and it has to be short enough that a tool
     * description can carry it without becoming unreadable. It says three things in one breath:
     * the settings are given, well-behaved software honours them, and something that opens its own
     * connection does not. A person reading only that sentence knows the boundary is not airtight.
     */
    fun sentenceFor(tool: String): String = when (val protection = protectionOf(tool)) {
        Protection.GUARANTEED ->
            "$tool sends everything through Tor."
        Protection.CONDITIONAL ->
            "$tool is given Tor settings that well-behaved software honours, but a program that " +
                "opens its own network connection ignores them and would leave from this phone's " +
                "own address without any error."
        Protection.DIRECT ->
            "$tool reaches only this local network and is not sent through Tor."
    }

    /**
     * Whether a result from this tool can be described as having gone through Tor.
     *
     * A TOOL MUST NOT CLAIM PRIVACY IT DOES NOT HAVE. This is the one function a result-building
     * path needs, and for a conditional tool the answer is NO regardless of the settings that were
     * exported, because the app cannot see inside the program it ran.
     */
    fun canClaimTor(tool: String): Boolean = protectionOf(tool) == Protection.GUARANTEED

    /**
     * The whole boundary in the few sentences a person would read.
     *
     * THE HONEST HEADLINE, and the one the ticket says must ship: the protection is not
     * unconditional, and a person using the app for something they would not put in a browser is
     * owed that sentence before they rely on it.
     */
    val summary: String =
        "GURU's own network tools all go through Tor. Anything it runs as a separate program — a " +
            "Python script, a command in the Termux environment — is told to use Tor and will obey " +
            "if it is well-behaved, but a program that opens its own connection would leave from " +
            "this phone's own address and would not report an error. Closing that gap needs work at " +
            "the network layer rather than more configuration."

    /**
     * The one test the ticket asks for, expressed so it can be run against every egress path.
     *
     * Its own words: every tool that can reach the network, asked for
     * `check.torproject.org/api/ip`, must return `IsTor: true` or must refuse. Any tool returning
     * false is a hole, and anything else is a tool that cannot be verified and should say so.
     *
     * This object cannot make the request — it is pure — so it supplies the endpoint and the rule
     * for reading the answer, which is the part that would otherwise be written three times.
     */
    const val TOR_CHECK_URL = "https://check.torproject.org/api/ip"

    /** What a probe against [TOR_CHECK_URL] shows. */
    enum class ProbeVerdict {
        /** The answer says the request arrived through Tor. Protected. */
        IN_CIRCUIT,
        /** The answer says it did not. This is a hole and must be reported as one. */
        LEAKED,
        /** Nothing readable came back, so nothing about the boundary is known. */
        UNVERIFIABLE,
    }

    /**
     * Read the body of a `check.torproject.org/api/ip` response.
     *
     * THE THREE-WAY ANSWER, because two would repeat the defect this whole batch removes: a tool
     * that returned nothing must not be counted as either protected or leaking. The service answers
     * `{"IsTor":true,"IP":"..."}`, and anything else is unverifiable rather than a pass.
     */
    fun readProbeResponse(body: String?): ProbeVerdict {
        if (body.isNullOrBlank()) return ProbeVerdict.UNVERIFIABLE
        val normalised = body.replace(" ", "").lowercase()
        return when {
            normalised.contains("\"istor\":true") -> ProbeVerdict.IN_CIRCUIT
            normalised.contains("\"istor\":false") -> ProbeVerdict.LEAKED
            else -> ProbeVerdict.UNVERIFIABLE
        }
    }

    /** One sentence for a probe verdict, naming a leak as a leak. */
    fun probeSentence(tool: String, verdict: ProbeVerdict): String = when (verdict) {
        ProbeVerdict.IN_CIRCUIT ->
            "$tool reached the internet through Tor."
        ProbeVerdict.LEAKED ->
            "$tool reached the internet from this phone's own address rather than through Tor. " +
                "That is the hole, not a warning about one."
        ProbeVerdict.UNVERIFIABLE ->
            "$tool could not be checked: the probe returned nothing this could read, so nothing " +
                "about its route is known either way."
    }
}
