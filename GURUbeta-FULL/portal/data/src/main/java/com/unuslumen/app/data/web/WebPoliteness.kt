// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.web

/**
 * How a well-behaved client reacts to being asked to slow down (issue 345).
 *
 * THE ONE THAT MATTERS MOST IS THE REFUSAL. The old code treated a `429` or a `503` as a plain
 * failure and immediately asked for a new Tor circuit, which is the worst possible response to a
 * site asking for less traffic: a new circuit means the next request arrives from a different
 * address, so the site gets the same load from a client it has already refused and cannot even
 * count it. The ticket is explicit that a rate-limit response is never a reason for a new circuit,
 * and [shouldRetryOnNewCircuit] is where that rule lives.
 *
 * Everything here is pure. The clock is an argument, the decision is a value, and the whole
 * behaviour is testable without a socket.
 */
object WebPoliteness {

    /**
     * What a response told us about pacing.
     *
     * Absent headers are null rather than a guess, because "the site said nothing" and "the site
     * said zero" are different facts and the floor applies to the first.
     */
    data class RateSignals(
        val httpStatus: Int = 0,
        /** `Retry-After` in seconds, when the server sent one. */
        val retryAfterSeconds: Int? = null,
        /** `X-RateLimit-Remaining`, when the server publishes it. */
        val remaining: Int? = null,
        /** `X-RateLimit-Reset` in seconds from now, when the server publishes it. */
        val resetSeconds: Int? = null,
    ) {
        /** True when the server explicitly asked us to back off. */
        val askedToSlowDown: Boolean
            get() = httpStatus == 429 || httpStatus == 503 || retryAfterSeconds != null

        /** True when the server says we have used its allowance up. */
        val exhausted: Boolean
            get() = remaining != null && remaining <= 0
    }

    /**
     * Whether a response is a refusal to serve us for now.
     *
     * 429 is the explicit case, and 503 is included because it is what a site uses when it is
     * protecting itself rather than when it is broken. The distinction matters because both must be
     * backed off from and neither must be retried on a new circuit.
     */
    fun isRateLimited(status: Int): Boolean = status == 429 || status == 503

    /**
     * How long to wait before the next request to an origin, in milliseconds.
     *
     * ORDER OF PRECEDENCE, and it is the whole rule:
     *  1. `Retry-After` when present, because it is the site's own instruction and it is the only
     *     signal that is not a guess.
     *  2. `X-RateLimit-Reset` when the server publishes a window, because it says when the window
     *     reopens.
     *  3. Otherwise an exponential backoff from the number of consecutive refusals.
     *
     * The result is then floored at the origin's own interval, so a site that says "retry in one
     * second" cannot talk us below the floor that every origin gets.
     */
    fun backoffMillis(
        signals: RateSignals,
        consecutiveRefusals: Int,
        floorMillis: Long = RobotsTxt.DEFAULT_FLOOR_MS,
    ): Long {
        val fromRetryAfter = signals.retryAfterSeconds?.let { it * 1000L }
        val fromReset = signals.resetSeconds?.let { it * 1000L }
        val fromBackoff = exponentialMillis(consecutiveRefusals)

        val chosen = when {
            fromRetryAfter != null -> fromRetryAfter
            fromReset != null -> fromReset
            else -> fromBackoff
        }
        return maxOf(chosen, floorMillis)
    }

    /**
     * The doubling backoff, capped.
     *
     * THE CAP IS NOT CAUTION, IT IS CORRECTNESS. Without one, a client that has been refused eight
     * times waits over four minutes, which for a tool call means a user staring at nothing. The cap
     * is where the client decides it is not going to succeed today and says so, rather than
     * pretending to still be trying.
     */
    fun exponentialMillis(consecutiveRefusals: Int, baseMillis: Long = 1_000L, capMillis: Long = 60_000L): Long {
        if (consecutiveRefusals <= 0) return baseMillis
        // Shift rather than pow, and cap the shift so the arithmetic cannot overflow into a
        // negative wait. Eight refusals is already 4 minutes 16 seconds at a one-second base.
        val shift = consecutiveRefusals.coerceAtMost(8)
        return (baseMillis shl (shift - 1)).coerceAtMost(capMillis)
    }

    /**
     * Whether a failure justifies asking for a new Tor circuit.
     *
     * THE RULE THE TICKET IS EXPLICIT ABOUT: a rate-limit response never does. A new circuit changes
     * the address a site sees, so it turns "this client is going too fast" into "an uncountable
     * number of clients are going too fast". It also wastes a NEWNYM, which is expensive and makes
     * every other Tor user's experience slightly worse.
     *
     * A genuinely broken exit is different: a connection reset or a timeout through one circuit can
     * be fixed by another, and the site has not asked for anything.
     */
    fun shouldRetryOnNewCircuit(signals: RateSignals, failureWasRateLimit: Boolean): Boolean =
        !failureWasRateLimit && !signals.askedToSlowDown && !isRateLimited(signals.httpStatus)

    /**
     * Whether a response is worth caching, and for how long.
     *
     * A RESPONSE WITH NO EXPLICIT LIFETIME IS STILL WORTH HOLDING BRIEFLY. The ticket's complaint is
     * that a repeated read inside one conversation hits the origin every time, so a short default
     * turns a conversation's worth of refetches into one. The default is deliberately small: a
     * cache that outlives its usefulness is a way of being wrong with confidence.
     *
     * Errors are never cached, because a transient failure would become a fixed one.
     */
    data class CacheDecision(val cacheable: Boolean, val ttlSeconds: Long)

    fun cacheDecision(
        status: Int,
        hasBody: Boolean,
        cacheControlMaxAge: Long? = null,
        isPrivateData: Boolean = false,
    ): CacheDecision = when {
        status != 200 -> CacheDecision(false, 0)
        !hasBody -> CacheDecision(false, 0)
        /*
         * A page fetched through the owner's own credentials is not shared with the next request,
         * even though both come from this install. The cache exists to stop refetching a public
         * page, and a private one carries a session it would be wrong to replay.
         */
        isPrivateData -> CacheDecision(false, 0)
        cacheControlMaxAge != null -> CacheDecision(true, cacheControlMaxAge.coerceIn(0, MAX_TTL_SECONDS))
        else -> CacheDecision(true, DEFAULT_TTL_SECONDS)
    }

    /**
     * Five minutes. Long enough that a conversation does not keep refetching the same page, short
     * enough that a person asking again a few minutes later gets the current version.
     */
    const val DEFAULT_TTL_SECONDS = 300L

    /** An hour. Nothing public on the web is unchanged in a way that matters beyond that. */
    const val MAX_TTL_SECONDS = 3_600L
}
