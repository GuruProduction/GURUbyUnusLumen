// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.web

/**
 * robots.txt, parsed and obeyed (issue 345).
 *
 * WHY THIS EXISTS. GURU fetches on the owner's behalf and did nothing to announce itself or to
 * respect a site's wishes: no robots check, no cache, no rate limit, and a Chrome user agent. The
 * ticket's origin is concrete — Reddit closed its public RSS feed because AI bots hammered it — and
 * the honest answer today is that GURU contributes to exactly that problem.
 *
 * THE COLLISION, AND HOW IT IS RESOLVED. Honouring `Disallow` means refusing to read a page the
 * owner explicitly asked for, which collides with the principle that the person holding the phone
 * decides what the phone does. The owner's ruling of 2026-11-07 settles it: GURU obeys the site by
 * default, and the owner can override it per origin. That override does not live here — this object
 * answers what the SITE asked for, and the caller decides whether an override applies. Keeping the
 * two apart means the override cannot be implemented by quietly not checking.
 *
 * WHAT IS DELIBERATELY NOT HERE. No network, no clock, no file system. The parse is pure so the
 * whole grammar is testable in milliseconds, and the crawl-delay arithmetic takes the current time
 * as an argument for the same reason.
 */
object RobotsTxt {

    /**
     * What one origin's robots.txt says to us.
     *
     * [disallow] holds the paths this agent may not fetch, already narrowed to the group that
     * matches it rather than whatever the wildcard said. [crawlDelaySeconds] is the site's own
     * requested interval, or null when it asked for nothing. [sitemapUrls] is kept because it is
     * free and a caller may want it later.
     */
    data class Policy(
        /** True when the file was fetched and parsed. False means unknown, not "allow". */
        val known: Boolean,
        /** Disallowed path prefixes for this agent, longest-first so matching is a prefix test. */
        val disallow: List<String> = emptyList(),
        /**
         * Explicitly allowed paths.
         *
         * Not in RFC 9309, but every major crawler honours it and sites rely on it: a file that
         * disallows `/` and allows `/pub/` is saying the archive is public. Ignoring Allow would
         * refuse a page the site went out of its way to permit.
         */
        val allow: List<String> = emptyList(),
        /** The site's requested delay between requests, in seconds, or null when it asked for none. */
        val crawlDelaySeconds: Int? = null,
        /** How long the file may be cached, from its own Cache-Control, in seconds. Null means use our default. */
        val maxAgeSeconds: Long? = null,
        val sitemapUrls: List<String> = emptyList(),
    ) {
        companion object {
            /**
             * NO FILE IS NOT PERMISSION.
             *
             * A site with no robots.txt has said nothing, and RFC 9309 says a crawler may then
             * fetch. That is what `known = false` means here: unknown, and the caller may proceed
             * while still holding the conservative interval. It is NOT the same as a file that
             * allows everything, which is `known = true, disallow = emptyList()`.
             */
            val UNKNOWN = Policy(known = false)
        }
    }

    /** The product token GURU matches rules against. */
    const val AGENT_TOKEN = "guru"

    /**
     * The product token in the user agent string.
     *
     * WHY A PRODUCT TOKEN AND NOT A BROWSER STRING. A site owner currently cannot tell GURU apart
     * from a browser, so they cannot rate-limit it, contact its operator or block it selectively.
     * They can only block Chrome-shaped traffic, which is what pushes a site towards closing a feed
     * entirely.
     *
     * WHAT IT COSTS, stated rather than hidden: a site sees "a GURU user" arriving from a Tor exit
     * rather than one of thousands of identical Android Chrome strings. That is a smaller crowd,
     * so it is a real if modest reduction in anonymity, shared by every user.
     *
     * SO IT CARRIES NOTHING PER-INSTALL. No device model, no build hash, no account token, no
     * request ID. It names the software and a contact URL and stops there, which is why every
     * install sends the identical string.
     */
    const val USER_AGENT =
        "GURU/1.0 (+https://unuslumen.com/guru; AI assistant acting for its owner)"

    /**
     * Parse a robots.txt body for our agent.
     *
     * RFC 9309 GROUPS BY USER-AGENT, AND THE MOST SPECIFIC MATCH WINS. A file may carry
     * `User-agent: *` and `User-agent: guru` blocks, and the `guru` block is the one that applies
     * to us — including when the specific block says LESS than the wildcard. Choosing the wildcard
     * because it happens to disallow more would be obeying a rule addressed to somebody else.
     *
     * Rules that matter, in the order the file's own grammar defines them:
     *  - `Disallow: /path` blocks anything beginning with that prefix. An empty value allows all.
     *  - `Allow:` is not in every parser, but it is common and it overrides Disallow when it is
     *    MORE SPECIFIC, which is the interpretation every major crawler uses.
     *  - `Crawl-delay:` is not in RFC 9309 but is widely honoured, and ignoring it is one of the
     *    behaviours the ticket exists to fix.
     *  - `*` and `$` in a path are wildcards; `*` matches any run of characters and a trailing `$`
     *    anchors the end.
     */
    fun parse(body: String): Policy {
        val lines = body.lineSequence()
            .map { it.substringBefore('#').trim() }
            .filter { it.isNotEmpty() }
            .toList()

        // Group the file into (agents -> rules) blocks. A group ends when a new User-agent line
        // follows at least one rule.
        data class Group(val agents: MutableList<String>, val rules: MutableList<Pair<String, String>>)

        val groups = mutableListOf<Group>()
        var current: Group? = null
        var sawRuleInCurrent = false

        for (line in lines) {
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            val field = line.substring(0, colon).trim().lowercase()
            val value = line.substring(colon + 1).trim()

            when (field) {
                "user-agent" -> {
                    // A new group starts only when the previous one already has a rule, so several
                    // User-agent lines stacked together all share the same rule set.
                    if (current == null || sawRuleInCurrent) {
                        current = Group(mutableListOf(), mutableListOf())
                        groups.add(current)
                        sawRuleInCurrent = false
                    }
                    current.agents.add(value.lowercase())
                }
                "disallow", "allow", "crawl-delay" -> {
                    current?.rules?.add(field to value)
                    sawRuleInCurrent = true
                }
                "sitemap" -> {
                    // Sitemap is file-level rather than group-level, so it is collected separately
                    // below rather than attaching to whichever group happens to be open.
                }
            }
        }

        val sitemaps = lines.mapNotNull {
            val colon = it.indexOf(':')
            if (colon <= 0) return@mapNotNull null
            if (!it.substring(0, colon).trim().equals("sitemap", ignoreCase = true)) return@mapNotNull null
            it.substring(colon + 1).trim().takeIf { url -> url.isNotEmpty() }
        }

        // The most specific group that names us, else the wildcard group, else nothing.
        val specific = groups.filter { group -> group.agents.any { agentNames(it, AGENT_TOKEN) } }
        val wildcard = groups.filter { group -> group.agents.any { it == "*" } }
        val chosen = when {
            specific.isNotEmpty() -> specific
            wildcard.isNotEmpty() -> wildcard
            else -> emptyList()
        }

        val disallow = mutableListOf<String>()
        val allow = mutableListOf<String>()
        var crawlDelay: Int? = null
        for (group in chosen) {
            for ((field, value) in group.rules) {
                when (field) {
                    "disallow" -> if (value.isNotEmpty()) disallow.add(value)
                    "allow" -> if (value.isNotEmpty()) allow.add(value)
                    "crawl-delay" -> value.toIntOrNull()?.let { delay ->
                        // A file that names the same delay twice is taken at its strictest.
                        crawlDelay = maxOf(crawlDelay ?: 0, delay)
                    }
                }
            }
        }

        return Policy(
            known = true,
            disallow = disallow.sortedByDescending { it.length },
            allow = allow.sortedByDescending { it.length },
            crawlDelaySeconds = crawlDelay,
            maxAgeSeconds = null,
            sitemapUrls = sitemaps,
        )
    }

    /**
     * Whether a `User-agent` value names us.
     *
     * A robots file may write `guru`, `GURU`, or a longer product token like `guru/1.0`. The match
     * is on the leading product token with any version suffix removed, which is what RFC 9309
     * specifies, so `guru/1.0` and `guru` are the same agent and `guru-other` is not.
     */
    fun agentNames(agentValue: String, token: String = AGENT_TOKEN): Boolean {
        val cleaned = agentValue.trim().lowercase().substringBefore('/').trim()
        return cleaned == token.lowercase()
    }

    /**
     * Whether this policy permits fetching a path.
     *
     * THE ALLOW/DISALLOW DECISION IS BY SPECIFICITY, which is what every major crawler does and
     * what a site author expects: if `Allow: /pub/` and `Disallow: /` both exist, `/pub/x` is
     * allowed because the allow rule is longer. Ties go to Allow, because a file that contradicts
     * itself on the same path is more plausibly trying to permit it.
     *
     * An UNKNOWN policy permits, which is RFC 9309's answer for an absent file. It is the caller's
     * job to decide whether to proceed, and the conservative interval still applies.
     */
    fun permits(policy: Policy, path: String): Boolean {
        if (!policy.known) return true
        val target = if (path.startsWith("/")) path else "/$path"

        val matchingDisallow = policy.disallow.filter { pattern -> matches(pattern, target) }
        val matchingAllow = policy.allow.filter { pattern -> matches(pattern, target) }

        if (matchingDisallow.isEmpty()) return true
        if (matchingAllow.isEmpty()) return false

        // Longest pattern wins. A tie goes to Allow.
        val longestDisallow = matchingDisallow.maxOf { it.length }
        val longestAllow = matchingAllow.maxOf { it.length }
        return longestAllow >= longestDisallow
    }

    /**
     * Path-pattern matching with the two wildcards robots.txt actually supports.
     *
     * `*` matches any run of characters including the empty run, and a trailing `$` anchors the
     * pattern to the end of the path. Everything else is literal, including a `?`, because a
     * robots pattern is not a regular expression and treating it as one is how a `+` in a query
     * string becomes a quantifier.
     *
     * Absent a `$`, the pattern is a PREFIX test, not a full match: `Disallow: /private` blocks
     * `/private/page` as well as `/private`.
     */
    fun matches(pattern: String, path: String): Boolean {
        if (pattern.isEmpty()) return false
        val anchored = pattern.endsWith("$")
        val body = if (anchored) pattern.dropLast(1) else pattern

        val regex = buildString {
            append("^")
            for (ch in body) {
                when (ch) {
                    '*' -> append(".*")
                    // Every other character is literal, including regex metacharacters.
                    else -> append(Regex.escape(ch.toString()))
                }
            }
            if (anchored) append("$") else append(".*")
        }
        return Regex(regex).matches(path)
    }

    /**
     * The delay to wait before the next request to this origin, in milliseconds.
     *
     * THREE TIERS, which is the honest scope the ticket names: obey the signals that exist, hold a
     * conservative floor where a site says nothing, and never let the floor be lower than what the
     * site asked for.
     *
     * A site's own Crawl-delay always wins when it is longer than the floor. A shorter one does not
     * lower the floor, because the floor exists for the sites that publish nothing and a site
     * asking for a fast rate is not evidence that hammering is safe.
     */
    fun intervalMillis(
        policy: Policy,
        floorMillis: Long = DEFAULT_FLOOR_MS,
    ): Long {
        val requested = policy.crawlDelaySeconds?.let { it * 1000L } ?: 0L
        return maxOf(requested, floorMillis)
    }

    /**
     * The conservative floor: one request per origin per second.
     *
     * WHY ONE SECOND AND NOT MORE. The ticket asks for a floor so a loop in the model cannot become
     * a burst. A second is long enough to turn an accidental loop into a trickle and short enough
     * that a person waiting for a page does not notice, which matters because a delay a user can
     * feel is a delay they will work around.
     */
    const val DEFAULT_FLOOR_MS = 1_000L

    /** How long to cache a robots file when it does not say. One hour, RFC 9309's guidance. */
    const val DEFAULT_MAX_AGE_SECONDS = 3_600L
}
