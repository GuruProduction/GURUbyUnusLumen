// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue 345's pacing rules, with the rule that matters most first.
 */
class WebPolitenessTest {

    @Test
    fun aRateLimitIsNeverAResonToAskForANewCircuit() {
        // The ticket is explicit: a new circuit means the next request arrives from a different
        // address, so the site gets the same load from a client it has already refused and cannot
        // even count it. That turns "this client is going too fast" into "an uncountable number of
        // clients are going too fast".
        assertFalse(
            "a 429 must not trigger a new circuit",
            WebPoliteness.shouldRetryOnNewCircuit(
                WebPoliteness.RateSignals(httpStatus = 429),
                failureWasRateLimit = true,
            ),
        )
        assertFalse(
            "nor a 503",
            WebPoliteness.shouldRetryOnNewCircuit(
                WebPoliteness.RateSignals(httpStatus = 503),
                failureWasRateLimit = true,
            ),
        )
        assertFalse(
            "nor a bare Retry-After without a status we read",
            WebPoliteness.shouldRetryOnNewCircuit(
                WebPoliteness.RateSignals(retryAfterSeconds = 30),
                failureWasRateLimit = false,
            ),
        )
    }

    @Test
    fun aBrokenExitStillJustifiesANewCircuit() {
        // The opposite case, and the one the feature exists for: a connection reset through one
        // circuit is exactly what another circuit fixes, and the site asked for nothing.
        assertTrue(
            "a network failure with no rate signal should still recover",
            WebPoliteness.shouldRetryOnNewCircuit(
                WebPoliteness.RateSignals(httpStatus = 0),
                failureWasRateLimit = false,
            ),
        )
    }

    @Test
    fun retryAfterBeatsEveryOtherSignal() {
        val signals = WebPoliteness.RateSignals(
            httpStatus = 429,
            retryAfterSeconds = 30,
            resetSeconds = 5,
        )
        assertEquals(
            "the site's own instruction is the only signal that is not a guess",
            30_000L,
            WebPoliteness.backoffMillis(signals, consecutiveRefusals = 1),
        )
    }

    @Test
    fun theResetWindowIsUsedWhenThereIsNoRetryAfter() {
        val signals = WebPoliteness.RateSignals(httpStatus = 429, resetSeconds = 20)
        assertEquals(20_000L, WebPoliteness.backoffMillis(signals, consecutiveRefusals = 1))
    }

    @Test
    fun theBackoffDoublesAndIsCapped() {
        assertEquals(
            "the first refusal waits the base",
            1_000L,
            WebPoliteness.exponentialMillis(1),
        )
        assertEquals(2_000L, WebPoliteness.exponentialMillis(2))
        assertEquals(4_000L, WebPoliteness.exponentialMillis(3))
        assertEquals(
            "the cap is where a client decides it will not succeed now, rather than pretending to try",
            60_000L,
            WebPoliteness.exponentialMillis(20),
        )
    }

    @Test
    fun aBackoffIsNeverBelowTheFloor() {
        val signals = WebPoliteness.RateSignals(httpStatus = 429, retryAfterSeconds = 0)
        assertEquals(
            "a site saying retry now cannot talk us below the floor every origin gets",
            RobotsTxt.DEFAULT_FLOOR_MS,
            WebPoliteness.backoffMillis(signals, consecutiveRefusals = 1),
        )
    }

    @Test
    fun zeroRefusalsGivesTheBaseRatherThanANegativeWait() {
        assertEquals(1_000L, WebPoliteness.exponentialMillis(0))
        assertEquals(
            "a negative count must not shift the wrong way and produce a long wait",
            1_000L,
            WebPoliteness.exponentialMillis(-3),
        )
    }

    @Test
    fun anExhaustedAllowanceIsRecognised() {
        assertTrue(WebPoliteness.RateSignals(remaining = 0).exhausted)
        assertTrue(WebPoliteness.RateSignals(remaining = -1).exhausted)
        assertFalse(WebPoliteness.RateSignals(remaining = 5).exhausted)
        assertFalse(
            "a server publishing nothing is not the same as a server saying zero",
            WebPoliteness.RateSignals().exhausted,
        )
    }

    @Test
    fun only200WithABodyIsCached() {
        val ok = WebPoliteness.cacheDecision(status = 200, hasBody = true)
        assertTrue(ok.cacheable)
        assertEquals(WebPoliteness.DEFAULT_TTL_SECONDS, ok.ttlSeconds)

        assertFalse(
            "an error must never be cached, or a transient failure becomes a fixed one",
            WebPoliteness.cacheDecision(status = 500, hasBody = true).cacheable,
        )
        assertFalse(
            "a 429 must not be cached as the page",
            WebPoliteness.cacheDecision(status = 429, hasBody = true).cacheable,
        )
        assertFalse(
            "nor an empty body",
            WebPoliteness.cacheDecision(status = 200, hasBody = false).cacheable,
        )
    }

    @Test
    fun theSitesOwnMaxAgeIsHonouredAndBounded() {
        assertEquals(
            600L,
            WebPoliteness.cacheDecision(200, true, cacheControlMaxAge = 600).ttlSeconds,
        )
        assertEquals(
            "an hour is the ceiling, because nothing public stays current beyond that",
            WebPoliteness.MAX_TTL_SECONDS,
            WebPoliteness.cacheDecision(200, true, cacheControlMaxAge = 999_999).ttlSeconds,
        )
    }

    @Test
    fun aPrivatePageIsNotCached() {
        // The cache exists to stop refetching a public page. A page carrying the owner's own
        // session must not be replayed to a later request.
        assertFalse(
            WebPoliteness.cacheDecision(200, true, isPrivateData = true).cacheable,
        )
    }
}
