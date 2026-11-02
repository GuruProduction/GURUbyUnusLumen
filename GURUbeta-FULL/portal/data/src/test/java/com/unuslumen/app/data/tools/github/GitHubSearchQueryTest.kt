// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.tools.github

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the two faults in issue #235, using the queries from the report.
 *
 * THE FIRST FAULT was an encoding order. The tool turned spaces into `+` and then
 * URL-encoded, which escaped that plus to `%2B`, so a `repo:` qualifier followed by
 * any second term became one unresolvable token and GitHub answered 422. Every
 * failing query in the report fits: the qualifier alone has no space, and a bare
 * term has no colon.
 *
 * THE SECOND FAULT was a response with neither `total_count` nor `items` being
 * reported as `success: true`, which a caller reads as "nothing matched".
 */
class GitHubSearchQueryTest {

    // ------------------------------------------------------------------
    // Encoding
    // ------------------------------------------------------------------

    /**
     * The exact failing query from the report must produce a separator, not a literal plus.
     *
     * This is the assertion that would have caught the original defect: `%2B` is a
     * plus character inside a search term, and `+` is the separator between terms.
     */
    @Test
    fun aRepoQualifierFollowedByATermUsesASeparatorNotALiteralPlus() {
        val encoded = GitHubSearchQuery.encode("repo:GuruProduction/GURUbyUnusLumen bug")

        assertEquals(
            "expected the separator form GitHub accepts, not the escaped literal plus that " +
                "produced HTTP 422 on every one of these queries",
            "repo%3AGuruProduction%2FGURUbyUnusLumen+bug",
            encoded,
        )
        assertFalse(
            "an encoded literal plus (%2B) is data, not a separator, and GitHub rejects the query",
            encoded.contains("%2B"),
        )
    }

    /** Every query the report listed as failing must now encode without a literal plus. */
    @Test
    fun everyQueryFromTheReportEncodesWithoutALiteralPlus() {
        val failing = listOf(
            "repo:GuruProduction/GURUbyUnusLumen MediaProbe",
            "repo:GuruProduction/GURUbyUnusLumen bug",
            "repo:GuruProduction/GURUbyUnusLumen is:issue",
            "repo:GuruProduction/GURUbyUnusLumen tvDiscover",
            "repo:GuruProduction/GURUbyUnusLumen label:bug",
            "repo:microsoft/vscode bug",
        )
        for (query in failing) {
            val encoded = GitHubSearchQuery.encode(query)
            assertFalse("'$query' encoded to '$encoded', which still carries a literal plus", encoded.contains("%2B"))
            assertTrue("'$query' lost its separator entirely: '$encoded'", encoded.contains("+"))
        }
    }

    /** A qualifier on its own worked before and must keep working. */
    @Test
    fun aQualifierAloneStillEncodes() {
        assertEquals(
            "repo%3AGuruProduction%2FGURUbyUnusLumen",
            GitHubSearchQuery.encode("repo:GuruProduction/GURUbyUnusLumen"),
        )
    }

    /** A bare term with a space must keep the space as a separator. */
    @Test
    fun aBareTermWithASpaceUsesASeparator() {
        assertEquals("media+probe", GitHubSearchQuery.encode("media probe"))
    }

    /**
     * A plus the caller actually typed is data and must survive as %2B.
     *
     * This is the distinction the old code destroyed: it could not tell a separator
     * from a plus in the search text, so it escaped both.
     */
    @Test
    fun aPlusTheCallerTypedSurvivesAsData() {
        assertEquals("c%2B%2B", GitHubSearchQuery.encode("c++"))
    }

    /** A colon qualifier is escaped as data, which is what GitHub expects. */
    @Test
    fun theColonInAQualifierIsEscapedAsData() {
        assertTrue(GitHubSearchQuery.encode("is:issue").startsWith("is%3Aissue"))
    }

    /** The path is built from the endpoint name GitHub uses, not the tool's own word. */
    @Test
    fun theRepoTypeMapsToTheRepositoriesEndpoint() {
        assertEquals("repositories", GitHubSearchQuery.endpointFor("repos"))
        assertEquals("issues", GitHubSearchQuery.endpointFor("issues"))
        assertEquals(
            "/search/repositories?q=repo%3Atorvalds%2Flinux+rust&per_page=5",
            GitHubSearchQuery.pathFor("repos", "repo:torvalds/linux rust", 5),
        )
    }

    // ------------------------------------------------------------------
    // Response shape
    // ------------------------------------------------------------------

    /** A real answer with results is a success. */
    @Test
    fun aRealSearchAnswerIsAResult() {
        val body = """{"total_count":27,"incomplete_results":false,"items":[{"number":1}]}"""
        assertTrue(GitHubSearchQuery.interpret(body) is GitHubSearchQuery.Response.Results)
    }

    /**
     * A genuine empty search stays a success.
     *
     * `total_count: 0` with an items array means "nothing matched", which is a real
     * answer. It must not be turned into a failure by a fix for the other case.
     */
    @Test
    fun aGenuineEmptySearchIsStillAResult() {
        val body = """{"total_count":0,"incomplete_results":false,"items":[]}"""
        assertTrue(GitHubSearchQuery.interpret(body) is GitHubSearchQuery.Response.Results)
    }

    /**
     * The exact fault: a body with neither key must be malformed, never an empty success.
     *
     * `{"success":true}` and `{}` are the shapes the report saw. A caller reading
     * either as "no matches" is being told something false.
     */
    @Test
    fun aBodyWithNoTotalAndNoItemsIsMalformed() {
        val malformed = listOf("{}", """{"success":true}""", """{"message":"something"}""")
        for (body in malformed) {
            val result = GitHubSearchQuery.interpret(body)
            assertTrue("'$body' was accepted as a search result", result is GitHubSearchQuery.Response.Malformed)
        }
    }

    /** An empty or non-object body is malformed, and says so rather than reading as empty. */
    @Test
    fun anEmptyOrNonObjectBodyIsMalformed() {
        assertTrue(GitHubSearchQuery.interpret("") is GitHubSearchQuery.Response.Malformed)
        assertTrue(GitHubSearchQuery.interpret(null) is GitHubSearchQuery.Response.Malformed)
        assertTrue(GitHubSearchQuery.interpret("<!doctype html>") is GitHubSearchQuery.Response.Malformed)
    }

    /** Either key on its own is enough to know this is a search answer. */
    @Test
    fun eitherKeyAloneIdentifiesASearchAnswer() {
        assertTrue(
            GitHubSearchQuery.interpret("""{"total_count":0}""") is GitHubSearchQuery.Response.Results,
        )
        assertTrue(
            GitHubSearchQuery.interpret("""{"items":[]}""") is GitHubSearchQuery.Response.Results,
        )
    }

    /** A malformed answer must explain itself, because that sentence is all the caller sees. */
    @Test
    fun aMalformedAnswerExplainsItself() {
        val result = GitHubSearchQuery.interpret("""{"success":true}""")
        assertTrue(result is GitHubSearchQuery.Response.Malformed)
        val reason = (result as GitHubSearchQuery.Response.Malformed).reason
        assertTrue("the reason must name what was missing", reason.contains("total_count"))
        assertTrue("the reason must say this is not a search result", reason.contains("not a search result"))
        assertTrue("the reason must quote the body so it can be diagnosed", reason.contains("success"))
    }
}
