// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.tools

import android.content.Context
import com.unuslumen.app.data.security.CredentialVault
import com.unuslumen.app.data.tools.github.GitHubClient
import com.unuslumen.app.data.tools.registry.ToolExecutionResult
import com.unuslumen.app.data.tools.registry.ToolExecutor
import com.unuslumen.app.data.tools.registry.ToolResultData
import com.unuslumen.app.data.tor.TorManager
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import org.json.JSONObject

/**
 * GitHubToolExecutor — the GitHub tool family. Never shells out.
 *
 * Login design (the fix version): device-flow login is STAGED, no blocking loops
 * inside any tool call.
 *   Call 1 — githubLogin: asks GitHub for a device code. Returns the human code,
 *            the https://github.com/login/device URL, and a pollToken. State
 *            persists in an in-memory map keyed by pollToken.
 *   Call 2..n — githubLoginCheck(pollToken): ONE poll cycle each call. Returns
 *            pending / already_authorized / expired / denied. Guru the numen calls
 *            githubLoginCheck again each user message turn (or on the human saying
 *            "done"/"approved") instead of the executor hanging in a blocking loop.
 *   Terminal — success stores + seals tokens, reports authenticated as whom.
 *
 * All HTTP through Tor via GitHubClient; all secrets via CredentialVault.
 * Error contract: failures always carry a non-empty, real message (GitHub's body
 * or the transport error). Successes serialize the genuine state.
 */
class GitHubToolExecutor(
    private val context: Context,
    private val torManager: TorManager
) : ToolExecutor {

    private val json = Json { ignoreUnknownKeys = true }
    private val client = GitHubClient(context, torManager)

    /** Live device-flow sessions awaiting human authorisation, keyed by pollToken. */
    private data class PendingLogin(
        val deviceCode: String,
        val userCode: String,
        val verificationUri: String,
        val intervalSeconds: Int,
        val expiresAtMillis: Long,
        var currentInterval: Int
    )
    private val pendingLogins = HashMap<String, PendingLogin>()

    override suspend fun execute(toolName: String, args: Map<String, Any?>): ToolExecutionResult = when (toolName) {
        GitHubToolDefinitions.GITHUB_STATUS -> githubStatus()
        GitHubToolDefinitions.GITHUB_LOGIN -> githubLogin()
        GitHubToolDefinitions.GITHUB_LOGIN_CHECK -> githubLoginCheck(args)
        GitHubToolDefinitions.GITHUB_LOGOUT -> githubLogout()
        GitHubToolDefinitions.GITHUB_PR_LIST -> githubPrList(args)
        GitHubToolDefinitions.GITHUB_PR_VIEW -> githubPrView(args)
        GitHubToolDefinitions.GITHUB_PR_CREATE -> githubPrCreate(args)
        GitHubToolDefinitions.GITHUB_ISSUE_LIST -> githubIssueList(args)
        GitHubToolDefinitions.GITHUB_ISSUE_CREATE -> githubIssueCreate(args)
        GitHubToolDefinitions.GITHUB_REPO_INFO -> githubRepoInfo(args)
        else -> ToolExecutionResult.error("Unknown tool: $toolName")
    }

    // ───────────────────────── AUTH TOOLS ─────────────────────────

    private suspend fun githubStatus(): ToolExecutionResult {
        val hasCredential = CredentialVault.has(context, CredentialVault.SERVICE_GITHUB)
        if (!hasCredential) {
            val r = GitHubStatusResult(
                success = true,
                isLoggedIn = false,
                username = null,
                error = "Not logged in to GitHub. Run githubLogin in chat to connect this install to your GitHub account."
            )
            return serialized(GitHubStatusResult.serializer(), r)
        }
        val loginName = client.authenticatedUserLogin()
        if (loginName.isNullOrBlank()) {
            val r = GitHubStatusResult(
                success = true,
                isLoggedIn = false,
                username = null,
                error = "GitHub will not verify this install's stored credential (revoked or expired). Run githubLogin to reconnect."
            )
            return serialized(GitHubStatusResult.serializer(), r)
        }
        val r = GitHubStatusResult(success = true, isLoggedIn = true, username = loginName, error = null)
        return serialized(GitHubStatusResult.serializer(), r)
    }

    /**
     * Stage 1: issue a device+user code or report "already logged in".
     * Never blocks. Poll state is in-memory under pollToken.
     */
    private suspend fun githubLogin(): ToolExecutionResult {
        if (client.isLoggedIn()) {
            val who = client.authenticatedUserLogin()
            val r = if (who != null) {
                GitHubLoginResult(
                    success = true,
                    stage = "already_authenticated",
                    userCode = null,
                    verificationUri = null,
                    authenticatedAs = who,
                    pollToken = null,
                    message = "This install is already logged in to GitHub as $who."
                )
            } else {
                GitHubLoginResult(
                    success = false,
                    stage = "stale_credential",
                    userCode = null,
                    verificationUri = null,
                    authenticatedAs = null,
                    pollToken = null,
                    message = "A stored credential is declined by GitHub. Run githubLogout, then githubLogin again."
                )
            }
            return serialized(GitHubLoginResult.serializer(), r)
        }

        val offer = client.startDeviceFlow()
        if (offer.deviceCode.isBlank()) {
            val r = GitHubLoginResult(
                success = false,
                stage = "setup_failed",
                userCode = null,
                verificationUri = null,
                authenticatedAs = null,
                pollToken = null,
                message = offer.error ?: "GitHub did not return a device code."
            )
            return serialized(GitHubLoginResult.serializer(), r)
        }

        val pollToken = java.util.UUID.randomUUID().toString()
        pendingLogins[pollToken] = PendingLogin(
            deviceCode = offer.deviceCode,
            userCode = offer.userCode,
            verificationUri = offer.verificationUri,
            intervalSeconds = offer.intervalSeconds,
            expiresAtMillis = System.currentTimeMillis() + offer.expiresIn * 1000L,
            currentInterval = offer.intervalSeconds
        )

        val r = GitHubLoginResult(
            success = true,
            stage = "awaiting_human_authorisation",
            userCode = offer.userCode,
            verificationUri = offer.verificationUri,
            authenticatedAs = null,
            pollToken = pollToken,
            message = "Open ${offer.verificationUri} in any browser, log in to GitHub, and enter the code ${offer.userCode}. " +
                "It lives ${offer.expiresIn / 60} minutes. Approve 'GURU' for your account; then run githubLoginCheck."
        )
        return serialized(GitHubLoginResult.serializer(), r)
    }

    /**
     * One poll cycle for a pending login. Called repeatedly by the numen (or by
     * itself when the human says "I entered the code"), never blocking in-call:
     * it asks the token endpoint once and maps the answer faithfully.
     */
    private suspend fun githubLoginCheck(args: Map<String, Any?>): ToolExecutionResult {
        val pollToken = args["pollToken"] as? String
            ?: return ToolExecutionResult.error("Missing 'pollToken' — resend it exactly as githubLogin emitted.")
        val pending = pendingLogins[pollToken]
            ?: return ToolExecutionResult.error(
                "Unknown pollToken — the login attempt expired on-device or the install restarted. Run githubLogin for a fresh code."
            )
        if (System.currentTimeMillis() > pending.expiresAtMillis) {
            pendingLogins.remove(pollToken)
            val r = GitHubAuthCheckResult(
                state = "code_expired",
                authenticatedAs = null,
                message = "The code ${pending.userCode} expired. Run githubLogin for a new one."
            )
            return serialized(GitHubAuthCheckResult.serializer(), r)
        }

        val outcome = try {
            client.pollOnce(pending.deviceCode, pending.currentInterval)
        } catch (e: Exception) {
            null // transport blip: keep pending state intact, advise a retry
        }
        if (outcome == null) {
            val r = GitHubAuthCheckResult(
                state = "transport_failed",
                authenticatedAs = null,
                message = "The token endpoint was unreachable just now — usually Tor hiccup. Run githubLoginCheck again in a few seconds."
            )
            return serialized(GitHubAuthCheckResult.serializer(), r)
        }
        if (outcome.success) {
            pendingLogins.remove(pollToken)
            val who = client.authenticatedUserLogin()
            val r = GitHubAuthCheckResult(
                state = "authenticated",
                authenticatedAs = who,
                message = "GitHub connected as $who. Credential sealed in device hardware storage; no further logins needed."
            )
            return serialized(GitHubAuthCheckResult.serializer(), r)
        }
        if (outcome.pending) {
            if (outcome.error?.contains("too fast", ignoreCase = true) == true) {
                pending.currentInterval = (pending.currentInterval + 5).coerceAtMost(60)
            }
            val r = GitHubAuthCheckResult(
                state = "still_pending",
                authenticatedAs = null,
                message = "GitHub has not approved the code yet. Check in browser and run githubLoginCheck again."
            )
            return serialized(GitHubAuthCheckResult.serializer(), r)
        }
        pendingLogins.remove(pollToken)
        val r = GitHubAuthCheckResult(
            state = "denied_or_error",
            authenticatedAs = null,
            message = outcome.error ?: "Authorisation did not complete."
        )
        return serialized(GitHubAuthCheckResult.serializer(), r)
    }

    private fun githubLogout(): ToolExecutionResult {
        val wasLoggedIn = client.isLoggedIn()
        val cleared = client.logout()
        val r = GitHubLogoutResult(
            success = cleared,
            wasLoggedIn = wasLoggedIn,
            message = if (wasLoggedIn && cleared)
                "Logged out on this install. The old authorisation stays listed on github.com/settings/security until revoked there."
            else if (!wasLoggedIn) "Never logged in; nothing stored."
            else "Logout ran; vault state unclear — re-run githubLogout."
        )
        return serialized(GitHubLogoutResult.serializer(), r)
    }

    // ─────────────────────── SHARED PLUMBING ───────────────────────

    /** On 401 with a sealed refresh token, mint and replay once. Else result untouched. */
    private suspend fun withAuthRetry(call: suspend () -> Triple<Int, String, String?>): Triple<Int, String, String?> {
        val first = call()
        if (first.first == 401 && CredentialVault.has(context, CredentialVault.SERVICE_GITHUB_REFRESH)) {
            val refreshed = try { client.refreshAccessToken() } catch (e: Exception) { false }
            if (refreshed) return call()
        }
        return first
    }

    private fun githubErrorMessage(response: String?, fallback: String): String {
        val trimmed = response?.trim() ?: ""
        if (trimmed.isEmpty()) return fallback
        val jsonBody = try { JSONObject(trimmed) } catch (e: Exception) { null }
        if (jsonBody == null) return trimmed.take(500)
        val message = jsonBody.optString("message", "")
        if (message.isNotBlank()) {
            val doc = jsonBody.optString("documentation_url", "")
            return if (doc.isNotBlank()) "$message (docs: $doc)" else message
        }
        return trimmed.take(500)
    }

    private fun <T : ToolResultData> serialized(
        serializer: kotlinx.serialization.KSerializer<T>,
        data: T
    ): ToolExecutionResult = ToolExecutionResult.success(data, json.encodeToString(serializer, data))

    // ─────────────────────── READ TOOLS ───────────────────────

    private suspend fun githubRepoInfo(args: Map<String, Any?>): ToolExecutionResult {
        val repo = args["repo"] as? String
            ?: return ToolExecutionResult.error("Missing 'repo' (owner/repo)")
        val (status, body, transportError) = withAuthRetry { client.apiRequest("/repos/$repo", "GET") }
        if (transportError != null) {
            return serialized(GitHubRepoInfoResult.serializer(), GitHubRepoInfoResult(success = false, repo = null, error = transportError))
        }
        val jsonBody = try { JSONObject(body) } catch (e: Exception) { null }
        if (status in 200..299 && jsonBody != null) {
            val directLanguageRaw = jsonBody.optString("language", "")
            val info = GitHubRepo(
                name = jsonBody.optString("full_name", repo),
                description = jsonBody.optString("description", ""),
                stars = jsonBody.optInt("stargazers_count", 0),
                forks = jsonBody.optInt("forks_count", 0),
                language = directLanguageRaw,
                url = jsonBody.optString("html_url", "")
            )
            val r = GitHubRepoInfoResult(success = true, repo = info, error = null)
            return serialized(GitHubRepoInfoResult.serializer(), r)
        }
        return serialized(GitHubRepoInfoResult.serializer(), GitHubRepoInfoResult(success = false, repo = null,
            error = "HTTP $status from GET /repos/$repo: ${githubErrorMessage(body, "no body") }"))
    }

    private suspend fun githubPrList(args: Map<String, Any?>): ToolExecutionResult {
        val repo = args["repo"] as? String ?: return ToolExecutionResult.error("Missing 'repo'")
        val state = (args["state"] as? String)?.takeIf { it in setOf("open","closed","all") } ?: "open"
        val limit = ((args["limit"] as? Number)?.toInt() ?: 20).coerceIn(1, 100)
        val (status, body, transportError) = withAuthRetry {
            client.apiRequest("/repos/$repo/pulls?state=$state&per_page=$limit", "GET")
        }
        return when {
            transportError != null -> serialized(GitHubPrListResult.serializer(),
                GitHubPrListResult(false, emptyList(), transportError))
            status in 200..299 -> try {
                val array = org.json.JSONArray(body)
                val prs = (0 until array.length()).map { i ->
                    val entry = array.getJSONObject(i)
                    GitHubPr(
                        number = entry.optInt("number"),
                        title = entry.optString("title"),
                        state = entry.optString("state"),
                        author = entry.optJSONObject("user")?.optString("login") ?: "",
                        url = entry.optString("html_url")
                    )
                }
                serialized(GitHubPrListResult.serializer(), GitHubPrListResult(true, prs, null))
            } catch (e: Exception) {
                serialized(GitHubPrListResult.serializer(),
                    GitHubPrListResult(false, emptyList(), "Unparseable JSON: ${body.take(200)}"))
            }
            else -> serialized(GitHubPrListResult.serializer(),
                GitHubPrListResult(false, emptyList(), "HTTP $status from GET /repos/$repo/pulls: ${githubErrorMessage(body, "no body")}"))
        }
    }

    private suspend fun githubPrView(args: Map<String, Any?>): ToolExecutionResult {
        val repo = args["repo"] as? String ?: return ToolExecutionResult.error("Missing 'repo'")
        val number = (args["number"] as? Number)?.toInt() ?: return ToolExecutionResult.error("Missing 'number'")
        val (status, body, transportError) = withAuthRetry { client.apiRequest("/repos/$repo/pulls/$number", "GET") }
        return when {
            transportError != null -> serialized(GitHubPrViewResult.serializer(), GitHubPrViewResult(false, null, transportError))
            status in 200..299 -> try {
                val jsonBody = JSONObject(body)
                val details = GitHubPrDetails(
                    number = jsonBody.optInt("number", number),
                    title = jsonBody.optString("title"),
                    body = jsonBody.optString("body"),
                    author = jsonBody.optJSONObject("user")?.optString("login") ?: "",
                    state = jsonBody.optString("state"),
                    url = jsonBody.optString("html_url"),
                    files = emptyList(),
                    reviews = emptyList()
                )
                serialized(GitHubPrViewResult.serializer(), GitHubPrViewResult(true, details, null))
            } catch (e: Exception) {
                serialized(GitHubPrViewResult.serializer(),
                    GitHubPrViewResult(false, null, "Unparseable PR body: ${body.take(200)}"))
            }
            else -> serialized(GitHubPrViewResult.serializer(),
                GitHubPrViewResult(false, null, "HTTP $status from GET /repos/$repo/pulls/$number: ${githubErrorMessage(body, "no body")}"))
        }
    }

    private suspend fun githubIssueList(args: Map<String, Any?>): ToolExecutionResult {
        val repo = args["repo"] as? String ?: return ToolExecutionResult.error("Missing 'repo'")
        val state = (args["state"] as? String)?.takeIf { it in setOf("open","closed","all") } ?: "open"
        val label = args["label"] as? String
        val limit = ((args["limit"] as? Number)?.toInt() ?: 20).coerceIn(1, 100)

        val query = mutableListOf("state=$state", "per_page=$limit")
        label?.takeIf { it.isNotBlank() }?.let { query.add("labels=" + java.net.URLEncoder.encode(it, "UTF-8")) }
        val (status, body, transportError) = withAuthRetry { client.apiRequest("/repos/$repo/issues?${query.joinToString("&")}", "GET") }

        return when {
            transportError != null -> serialized(GitHubIssueListResult.serializer(),
                GitHubIssueListResult(false, emptyList(), transportError))
            status in 200..299 -> try {
                val array = org.json.JSONArray(body)
                val issues = mutableListOf<GitHubIssue>()
                for (i in 0 until array.length()) {
                    val entry = array.getJSONObject(i)
                    if (entry.has("pull_request")) continue
                    val labels = mutableListOf<String>()
                    entry.optJSONArray("labels")?.let { arr ->
                        for (j in 0 until arr.length()) labels.add(arr.getJSONObject(j).optString("name"))
                    }
                    issues.add(GitHubIssue(
                        number = entry.optInt("number"),
                        title = entry.optString("title"),
                        state = entry.optString("state"),
                        labels = labels,
                        url = entry.optString("html_url")
                    ))
                }
                serialized(GitHubIssueListResult.serializer(), GitHubIssueListResult(true, issues, null))
            } catch (e: Exception) {
                serialized(GitHubIssueListResult.serializer(),
                    GitHubIssueListResult(false, emptyList(), "Unparseable JSON: ${body.take(200)}"))
            }
            else -> serialized(GitHubIssueListResult.serializer(),
                GitHubIssueListResult(false, emptyList(), "HTTP $status: ${githubErrorMessage(body, "no body")}"))
        }
    }

    // ─────────────────────── WRITE TOOLS ───────────────────────

    private suspend fun githubPrCreate(args: Map<String, Any?>): ToolExecutionResult {
        val repo = args["repo"] as? String ?: return ToolExecutionResult.error("Missing 'repo'")
        val title = args["title"] as? String ?: return ToolExecutionResult.error("Missing 'title'")
        val body = args["body"] as? String ?: return ToolExecutionResult.error("Missing 'body'")
        val base = args["base"] as? String ?: "main"
        val head = args["head"] as? String ?: "HEAD"

        if (!client.isLoggedIn()) {
            return serialized(GitHubPrCreateResult.serializer(),
                GitHubPrCreateResult(false, null, null, "Creating PRs requires an authenticated GitHub login; run githubLogin first."))
        }
        val payload = JSONObject().apply {
            put("title", title)
            if (body.isNotBlank()) put("body", body)
            put("base", base)
            put("head", head)
        }.toString()
        val (status, responseBody, transportError) = withAuthRetry {
            client.apiRequest("/repos/$repo/pulls", "POST", payload)
        }
        return when {
            transportError != null -> serialized(GitHubPrCreateResult.serializer(),
                GitHubPrCreateResult(false, null, null, transportError))
            status in 200..299 -> try {
                val jsonBody = JSONObject(responseBody)
                GitHubPrCreateResult(true, jsonBody.optInt("number", 0), jsonBody.optString("html_url"), null)
                    .let { serialized(GitHubPrCreateResult.serializer(), it) }
            } catch (e: Exception) {
                serialized(GitHubPrCreateResult.serializer(),
                    GitHubPrCreateResult(false, null, null, "Unparseable PR create response: ${responseBody.take(200)}"))
            }
            else -> serialized(GitHubPrCreateResult.serializer(),
                GitHubPrCreateResult(false, null, null, "HTTP $status from POST /repos/$repo/pulls: ${githubErrorMessage(responseBody, "no body")}"))
        }
    }

    private suspend fun githubIssueCreate(args: Map<String, Any?>): ToolExecutionResult {
        val repo = args["repo"] as? String ?: return ToolExecutionResult.error("Missing 'repo'")
        val title = args["title"] as? String ?: return ToolExecutionResult.error("Missing 'title'")
        val body = args["body"] as? String
        val labelsArg = args["labels"] as? String

        if (!client.isLoggedIn()) {
            return serialized(GitHubIssueCreateResult.serializer(),
                GitHubIssueCreateResult(false, null, null, "Creating issues requires an authenticated login; run githubLogin first."))
        }
        val payload = JSONObject().apply {
            put("title", title)
            if (!body.isNullOrBlank()) put("body", body)
            if (!labelsArg.isNullOrBlank()) {
                put("labels", org.json.JSONArray(labelsArg.split(",").map { it.trim() }.filter { it.isNotEmpty() }))
            }
        }.toString()
        val (status, responseBody, transportError) = withAuthRetry {
            client.apiRequest("/repos/$repo/issues", "POST", payload)
        }
        return when {
            transportError != null -> serialized(GitHubIssueCreateResult.serializer(),
                GitHubIssueCreateResult(false, null, null, transportError))
            status in 200..299 -> try {
                val jsonBody = JSONObject(responseBody)
                GitHubIssueCreateResult(true, jsonBody.optInt("number", 0), jsonBody.optString("html_url"), null)
                    .let { serialized(GitHubIssueCreateResult.serializer(), it) }
            } catch (e: Exception) {
                serialized(GitHubIssueCreateResult.serializer(),
                    GitHubIssueCreateResult(false, null, null, "Unparseable issue create response: ${responseBody.take(200)}"))
            }
            else -> serialized(GitHubIssueCreateResult.serializer(),
                GitHubIssueCreateResult(false, null, null, "HTTP $status from POST /repos/$repo/issues: ${githubErrorMessage(responseBody, "no body")}"))
        }
    }
}