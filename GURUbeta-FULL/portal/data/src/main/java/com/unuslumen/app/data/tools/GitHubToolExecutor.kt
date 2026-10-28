// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.tools

import android.content.Context
import android.util.Base64
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
        // Repos
        GitHubToolDefinitions.GITHUB_REPO_INFO -> githubRepoInfo(args)
        GitHubToolDefinitions.GITHUB_REPO_LIST -> githubRepoList(args)
        GitHubToolDefinitions.GITHUB_REPO_CREATE -> githubRepoCreate(args)
        GitHubToolDefinitions.GITHUB_REPO_FORK -> githubRepoFork(args)
        GitHubToolDefinitions.GITHUB_REPO_STAR -> githubRepoStar(args)
        GitHubToolDefinitions.GITHUB_REPO_TREE -> githubRepoTree(args)
        GitHubToolDefinitions.GITHUB_REPO_DOWNLOAD -> githubRepoDownload(args)
        // Files
        GitHubToolDefinitions.GITHUB_FILE_READ -> githubFileRead(args)
        GitHubToolDefinitions.GITHUB_FILE_WRITE -> githubFileWrite(args)
        GitHubToolDefinitions.GITHUB_FILE_DELETE -> githubFileDelete(args)
        GitHubToolDefinitions.GITHUB_PUSH -> githubPush(args)
        // Branches
        GitHubToolDefinitions.GITHUB_BRANCH_LIST -> githubBranchList(args)
        GitHubToolDefinitions.GITHUB_BRANCH_CREATE -> githubBranchCreate(args)
        GitHubToolDefinitions.GITHUB_BRANCH_DELETE -> githubBranchDelete(args)
        // History
        GitHubToolDefinitions.GITHUB_COMMIT_LIST -> githubCommitList(args)
        GitHubToolDefinitions.GITHUB_COMMIT_VIEW -> githubCommitView(args)
        // Pull requests
        GitHubToolDefinitions.GITHUB_PR_LIST -> githubPrList(args)
        GitHubToolDefinitions.GITHUB_PR_VIEW -> githubPrView(args)
        GitHubToolDefinitions.GITHUB_PR_CREATE -> githubPrCreate(args)
        GitHubToolDefinitions.GITHUB_PR_MERGE -> githubPrMerge(args)
        GitHubToolDefinitions.GITHUB_PR_COMMENT -> githubPrComment(args)
        GitHubToolDefinitions.GITHUB_PR_CLOSE -> githubPrClose(args)
        GitHubToolDefinitions.GITHUB_PR_FILES -> githubPrFiles(args)
        // Issues
        GitHubToolDefinitions.GITHUB_ISSUE_LIST -> githubIssueList(args)
        GitHubToolDefinitions.GITHUB_ISSUE_VIEW -> githubIssueView(args)
        GitHubToolDefinitions.GITHUB_ISSUE_CREATE -> githubIssueCreate(args)
        GitHubToolDefinitions.GITHUB_ISSUE_COMMENT -> githubIssueComment(args)
        GitHubToolDefinitions.GITHUB_ISSUE_CLOSE -> githubIssueClose(args)
        GitHubToolDefinitions.GITHUB_ISSUE_EDIT -> githubIssueEdit(args)
        // Releases
        GitHubToolDefinitions.GITHUB_RELEASE_LIST -> githubReleaseList(args)
        GitHubToolDefinitions.GITHUB_RELEASE_CREATE -> githubReleaseCreate(args)
        GitHubToolDefinitions.GITHUB_RELEASE_UPLOAD -> githubReleaseUpload(args)
        GitHubToolDefinitions.GITHUB_RELEASE_DELETE -> githubReleaseDelete(args)
        // Actions
        GitHubToolDefinitions.GITHUB_RUN_LIST -> githubRunList(args)
        GitHubToolDefinitions.GITHUB_RUN_VIEW -> githubRunView(args)
        GitHubToolDefinitions.GITHUB_RUN_DISPATCH -> githubRunDispatch(args)
        GitHubToolDefinitions.GITHUB_RUN_CANCEL -> githubRunCancel(args)
        // Search and meta
        GitHubToolDefinitions.GITHUB_SEARCH -> githubSearch(args)
        GitHubToolDefinitions.GITHUB_NOTIFICATION_LIST -> githubNotificationList(args)
        GitHubToolDefinitions.GITHUB_GIST_LIST -> githubGistList(args)
        GitHubToolDefinitions.GITHUB_GIST_CREATE -> githubGistCreate(args)
        GitHubToolDefinitions.GITHUB_API -> githubApi(args)
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

    // ─────────────────────────── SHARED PLUMBING (EXPANSION) ───────────────────────────

    /** Encode a path segment set: spaces to %20, slashes kept (API accepts repo paths raw). */
    private fun encPath(p: String): String = p.trim().replace(" ", "%20")

    /** Encode a query parameter value completely. */
    private fun encQuery(v: String): String = java.net.URLEncoder.encode(v, "UTF-8")

    /** Where zipball downloads and asset staging live by default. */
    private fun githubDownloadsDir(): java.io.File =
        java.io.File(context.filesDir, "github-downloads").apply { mkdirs() }

    /** Standard login guard for write operations; null means proceed. */
    private fun requireLogin(): ToolExecutionResult? =
        if (!client.isLoggedIn()) ToolExecutionResult.error(
            "This needs an authenticated GitHub login; run githubLogin first."
        ) else null

    /** Resolve the repo's default branch, defaulting to 'main' on any read failure. */
    private suspend fun defaultBranchOf(repo: String): String {
        val (status, body, _) = client.apiRequest("/repos/$repo", "GET")
        if (status in 200..299) {
            return try { JSONObject(body).optString("default_branch", "main").ifBlank { "main" } } catch (_: Exception) { "main" }
        }
        return "main"
    }

    // ─────────────────────────── REPO TOOLS ───────────────────────────

    private suspend fun githubRepoList(args: Map<String, Any?>): ToolExecutionResult {
        val owner = (args["owner"] as? String)?.trim()?.takeIf { it.isNotBlank() }
        val visibility = (args["visibility"] as? String)?.takeIf { it in setOf("all", "public", "private") } ?: "all"
        val sort = (args["sort"] as? String)?.takeIf { it in setOf("updated", "created", "pushed", "full_name") } ?: "updated"
        val limit = ((args["limit"] as? Number)?.toInt() ?: 30).coerceIn(1, 100)

        val path = if (owner != null) "/users/$owner/repos?per_page=$limit&sort=$sort"
        else "/user/repos?per_page=$limit&sort=$sort&visibility=$visibility"

        val (status, body, transportError) = withAuthRetry { client.apiRequest(path, "GET") }
        if (transportError != null) return serialized(GitHubRepoListResult.serializer(), GitHubRepoListResult(false, emptyList(), transportError))
        if (status !in 200..299) return serialized(GitHubRepoListResult.serializer(), GitHubRepoListResult(false, emptyList(),
            "HTTP $status from GET $path: ${githubErrorMessage(body, "no body")}"))
        return try {
            val array = org.json.JSONArray(body)
            val repos = (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                GitHubRepoListItem(
                    fullName = o.optString("full_name"),
                    description = o.optString("description", "").ifBlank { null },
                    isPrivate = o.optBoolean("private", false),
                    language = o.optString("language", "").ifBlank { null },
                    updatedAt = o.optString("updated_at", "").ifBlank { null },
                    url = o.optString("html_url", "").ifBlank { null }
                )
            }
            serialized(GitHubRepoListResult.serializer(), GitHubRepoListResult(true, repos, null))
        } catch (e: Exception) {
            serialized(GitHubRepoListResult.serializer(), GitHubRepoListResult(false, emptyList(), "Unparseable JSON: ${body.take(200)}"))
        }
    }

    private suspend fun githubRepoCreate(args: Map<String, Any?>): ToolExecutionResult {
        requireLogin()?.let { return it }
        val name = (args["name"] as? String)?.trim().orEmpty()
        if (name.isBlank()) return ToolExecutionResult.error("Missing 'name'")
        val org = (args["org"] as? String)?.trim()?.takeIf { it.isNotBlank() }

        val payload = JSONObject().apply {
            put("name", name)
            (args["description"] as? String)?.takeIf { it.isNotBlank() }?.let { put("description", it) }
            put("private", (args["private"] as? Boolean) ?: false)
            put("auto_init", (args["autoInit"] as? Boolean) ?: false)
        }.toString()
        val path = if (org != null) "/orgs/$org/repos" else "/user/repos"
        val (status, body, transportError) = withAuthRetry { client.apiRequest(path, "POST", payload) }
        if (transportError != null) return serialized(GitHubRepoResult.serializer(), GitHubRepoResult(false, null, transportError))
        if (status !in 200..299) return serialized(GitHubRepoResult.serializer(), GitHubRepoResult(false, null,
            "HTTP $status from POST $path: ${githubErrorMessage(body, "no body")}"))
        return try {
            val o = JSONObject(body)
            serialized(GitHubRepoResult.serializer(), GitHubRepoResult(true, GitHubRepoListItem(
                fullName = o.optString("full_name", "$name"),
                description = o.optString("description", "").ifBlank { null },
                isPrivate = o.optBoolean("private", false),
                url = o.optString("html_url", "").ifBlank { null }
            ), null))
        } catch (e: Exception) {
            serialized(GitHubRepoResult.serializer(), GitHubRepoResult(true, GitHubRepoListItem(fullName = name), null))
        }
    }

    private suspend fun githubRepoFork(args: Map<String, Any?>): ToolExecutionResult {
        requireLogin()?.let { return it }
        val repo = args["repo"] as? String ?: return ToolExecutionResult.error("Missing 'repo'")
        val org = (args["org"] as? String)?.trim()?.takeIf { it.isNotBlank() }
        val payload = if (org != null) JSONObject().put("organization", org).toString() else "{}"
        val (status, body, transportError) = withAuthRetry { client.apiRequest("/repos/$repo/forks", "POST", payload) }
        if (transportError != null) return serialized(GitHubMutationResult.serializer(), GitHubMutationResult(false, error = transportError))
        if (status !in 200..299) return serialized(GitHubMutationResult.serializer(), GitHubMutationResult(false, error =
            "HTTP $status forking $repo: ${githubErrorMessage(body, "no body")}"))
        return try {
            val o = JSONObject(body)
            serialized(GitHubMutationResult.serializer(), GitHubMutationResult(true,
                message = "Forked to ${o.optString("full_name", "?")}",
                url = o.optString("html_url", "").ifBlank { null }))
        } catch (e: Exception) {
            serialized(GitHubMutationResult.serializer(), GitHubMutationResult(true, "Fork requested.", null, null, null))
        }
    }

    private suspend fun githubRepoStar(args: Map<String, Any?>): ToolExecutionResult {
        requireLogin()?.let { return it }
        val repo = args["repo"] as? String ?: return ToolExecutionResult.error("Missing 'repo'")
        val unstar = (args["unstar"] as? Boolean) ?: false
        val method = if (unstar) "DELETE" else "PUT"
        val (status, body, transportError) = withAuthRetry { client.apiRequest("/user/starred/$repo", method) }
        if (transportError != null) return serialized(GitHubMutationResult.serializer(), GitHubMutationResult(false, error = transportError))
        if (status !in 200..299) return serialized(GitHubMutationResult.serializer(), GitHubMutationResult(false, error =
            "HTTP $status from $method /user/starred/$repo: ${githubErrorMessage(body, "no body")}"))
        return serialized(GitHubMutationResult.serializer(), GitHubMutationResult(true,
            message = if (unstar) "Unstarred $repo" else "Starred $repo"))
    }

    private suspend fun githubRepoTree(args: Map<String, Any?>): ToolExecutionResult {
        val repo = args["repo"] as? String ?: return ToolExecutionResult.error("Missing 'repo'")
        val refArg = (args["ref"] as? String)?.trim()?.takeIf { it.isNotBlank() }
        val ref = refArg ?: defaultBranchOf(repo)
        val pathFilter = (args["path"] as? String)?.trim()?.takeIf { it.isNotBlank() }?.trimEnd('/')

        val (status, body, transportError) = withAuthRetry { client.apiRequest("/repos/$repo/git/trees/${encPath(ref)}?recursive=1", "GET") }
        if (transportError != null) return serialized(GitHubTreeResult.serializer(), GitHubTreeResult(false, ref, false, 0, emptyList(), transportError))
        if (status !in 200..299) return serialized(GitHubTreeResult.serializer(), GitHubTreeResult(false, ref, false, 0, emptyList(),
            "HTTP $status from tree at $ref: ${githubErrorMessage(body, "no body")}"))
        return try {
            val o = JSONObject(body)
            val truncated = o.optBoolean("truncated", false)
            val array = o.optJSONArray("tree") ?: org.json.JSONArray()
            val entries = mutableListOf<GitHubTreeEntryItem>()
            for (i in 0 until array.length()) {
                val e = array.getJSONObject(i)
                val p = e.optString("path")
                if (pathFilter != null && !p.startsWith("$pathFilter/") && p != pathFilter) continue
                entries.add(GitHubTreeEntryItem(
                    path = p,
                    type = e.optString("type", "blob"),
                    size = e.optLong("size", 0)
                ))
            }
            serialized(GitHubTreeResult.serializer(), GitHubTreeResult(true, ref, truncated, entries.size, entries, null))
        } catch (e: Exception) {
            serialized(GitHubTreeResult.serializer(), GitHubTreeResult(false, ref, false, 0, emptyList(), "Unparseable JSON: ${body.take(200)}"))
        }
    }

    private suspend fun githubRepoDownload(args: Map<String, Any?>): ToolExecutionResult {
        val repo = args["repo"] as? String ?: return ToolExecutionResult.error("Missing 'repo'")
        val ref = (args["ref"] as? String)?.trim()?.takeIf { it.isNotBlank() }
        val outputDir = (args["outputDir"] as? String)?.trim()?.takeIf { it.isNotBlank() }

        val destDir = if (outputDir != null) java.io.File(outputDir) else githubDownloadsDir()
        destDir.mkdirs()
        val safeName = repo.replace("/", "-") + if (ref != null) "-${ref.replace("/", "-")}" else ""
        val dest = java.io.File(destDir, "$safeName.zip")

        val path = if (ref != null) "/repos/$repo/zipball/${encPath(ref)}" else "/repos/$repo/zipball"
        val (bytes, error) = client.downloadToFile(path, dest)
        return if (error == null) {
            serialized(GitHubDownloadResult.serializer(), GitHubDownloadResult(true, dest.absolutePath, bytes, path))
        } else {
            serialized(GitHubDownloadResult.serializer(), GitHubDownloadResult(false, null, 0, path, error))
        }
    }

    // ─────────────────────────── FILE TOOLS ───────────────────────────

    private suspend fun githubFileRead(args: Map<String, Any?>): ToolExecutionResult {
        val repo = args["repo"] as? String ?: return ToolExecutionResult.error("Missing 'repo'")
        val rawPath = (args["path"] as? String ?: "").trim().trimStart('/')
        val ref = (args["ref"] as? String)?.trim()?.takeIf { it.isNotBlank() }

        val url = "/repos/$repo/contents/${encPath(rawPath)}" + (if (ref != null) "?ref=${encQuery(ref)}" else "")
        val (status, body, transportError) = withAuthRetry { client.apiRequest(url, "GET") }
        if (transportError != null) return serialized(GitHubFileReadResult.serializer(), GitHubFileReadResult(false, path = rawPath, error = transportError))
        if (status !in 200..299) return serialized(GitHubFileReadResult.serializer(), GitHubFileReadResult(false, path = rawPath,
            error = "HTTP $status reading $rawPath: ${githubErrorMessage(body, "no body")}"))

        return try {
            val trimmed = body.trim()
            if (trimmed.startsWith("[")) {
                // Directory listing
                val array = org.json.JSONArray(trimmed)
                val entries = (0 until array.length()).map { i ->
                    val o = array.getJSONObject(i)
                    GitHubContentEntry(
                        name = o.optString("name"),
                        path = o.optString("path"),
                        type = o.optString("type", "file"),
                        size = o.optLong("size", 0),
                        sha = o.optString("sha", "").ifBlank { null }
                    )
                }
                serialized(GitHubFileReadResult.serializer(), GitHubFileReadResult(true, path = rawPath, ref = ref, kind = "dir", entries = entries))
            } else {
                // Single file object
                val o = JSONObject(trimmed)
                val size = o.optLong("size", 0)
                val contentField = o.optString("content", "")
                val encoding = o.optString("encoding", "")
                var text: String? = null
                var truncated = false
                var note: String? = null
                if (contentField.isNotBlank() && encoding == "base64") {
                    // GitHub wraps base64 at 60 chars per line; strip newlines before decode.
                    val cleaned = contentField.replace("\n", "")
                    text = String(Base64.decode(cleaned, Base64.DEFAULT), Charsets.UTF_8)
                } else {
                    // Over 1MB: GitHub answers without inline content; the download_url carries it.
                    truncated = true
                    note = "File is larger than the API's inline limit; use the download_url via githubApi or download the repo zipball."
                }
                serialized(GitHubFileReadResult.serializer(), GitHubFileReadResult(
                    success = true,
                    path = o.optString("path", rawPath),
                    ref = ref,
                    kind = "file",
                    content = text,
                    size = size,
                    sha = o.optString("sha", "").ifBlank { null },
                    downloadUrl = o.optString("download_url", "").ifBlank { null },
                    truncated = truncated,
                    note = note
                ))
            }
        } catch (e: Exception) {
            serialized(GitHubFileReadResult.serializer(), GitHubFileReadResult(false, path = rawPath, error = "Unparseable JSON: ${body.take(200)}"))
        }
    }

    private suspend fun githubFileWrite(args: Map<String, Any?>): ToolExecutionResult {
        requireLogin()?.let { return it }
        val repo = args["repo"] as? String ?: return ToolExecutionResult.error("Missing 'repo'")
        val rawPath = (args["path"] as? String)?.trim()?.trimStart('/')?.takeIf { it.isNotBlank() }
            ?: return ToolExecutionResult.error("Missing 'path'")
        val content = args["content"] as? String ?: return ToolExecutionResult.error("Missing 'content'")
        val message = (args["message"] as? String)?.trim()?.takeIf { it.isNotBlank() }
            ?: return ToolExecutionResult.error("Missing 'message'")
        val branch = (args["branch"] as? String)?.trim()?.takeIf { it.isNotBlank() }
        val sha = (args["sha"] as? String)?.trim()?.takeIf { it.isNotBlank() }
        val createBranch = (args["createBranch"] as? Boolean) ?: false

        // Edit-on-a-new-branch flow: create the branch at default head first when asked.
        if (createBranch && branch != null) {
            val (checkStatus, _, _) = client.apiRequest("/repos/$repo/branches/${encPath(branch)}", "GET")
            if (checkStatus == 404) {
                val (headStatus, headBody, _) = client.apiRequest("/repos/$repo/git/ref/heads/${encPath(defaultBranchOf(repo))}", "GET")
                val headSha = if (headStatus in 200..299) {
                    try { JSONObject(headBody).optJSONObject("object")?.optString("sha") } catch (_: Exception) { null }
                } else null
                if (!headSha.isNullOrBlank()) {
                    val createPayload = JSONObject().put("ref", "refs/heads/$branch").put("sha", headSha).toString()
                    val (createStatus, createBody, _) = client.apiRequest("/repos/$repo/git/refs", "POST", createPayload)
                    if (createStatus !in 200..299) {
                        return serialized(GitHubMutationResult.serializer(), GitHubMutationResult(false, error =
                            "Could not create branch '$branch': ${githubErrorMessage(createBody, "HTTP $createStatus")}"))
                    }
                } else {
                    return serialized(GitHubMutationResult.serializer(), GitHubMutationResult(false, error =
                        "Could not read the base branch head to create '$branch' from."))
                }
            }
        }

        val payload = JSONObject().apply {
            put("message", message)
            put("content", Base64.encodeToString(content.toByteArray(Charsets.UTF_8), Base64.NO_WRAP))
            if (branch != null) put("branch", branch)
            if (sha != null) put("sha", sha)
        }.toString()

        val (status, body, transportError) = withAuthRetry { client.apiRequest("/repos/$repo/contents/${encPath(rawPath)}", "PUT", payload) }
        if (transportError != null) return serialized(GitHubMutationResult.serializer(), GitHubMutationResult(false, error = transportError))
        if (status !in 200..299) return serialized(GitHubMutationResult.serializer(), GitHubMutationResult(false, error =
            "HTTP $status writing $rawPath: ${githubErrorMessage(body, "no body")}"))
        return try {
            val o = JSONObject(body)
            val commit = o.optJSONObject("commit")
            serialized(GitHubMutationResult.serializer(), GitHubMutationResult(true,
                message = "Committed $rawPath${branch?.let { " to $it" } ?: ""}",
                url = commit?.optString("html_url", "")?.ifBlank { null },
                sha = commit?.optString("sha", "")?.ifBlank { null }))
        } catch (e: Exception) {
            serialized(GitHubMutationResult.serializer(), GitHubMutationResult(true, message = "Committed $rawPath"))
        }
    }

    private suspend fun githubFileDelete(args: Map<String, Any?>): ToolExecutionResult {
        requireLogin()?.let { return it }
        val repo = args["repo"] as? String ?: return ToolExecutionResult.error("Missing 'repo'")
        val rawPath = (args["path"] as? String)?.trim()?.trimStart('/')?.takeIf { it.isNotBlank() }
            ?: return ToolExecutionResult.error("Missing 'path'")
        val sha = (args["sha"] as? String)?.trim()?.takeIf { it.isNotBlank() }
            ?: return ToolExecutionResult.error("Missing 'sha' — the file's current blob sha from githubFileRead")
        val message = (args["message"] as? String)?.trim()?.takeIf { it.isNotBlank() }
            ?: return ToolExecutionResult.error("Missing 'message'")
        val branch = (args["branch"] as? String)?.trim()?.takeIf { it.isNotBlank() }

        val payload = JSONObject().apply {
            put("message", message)
            put("sha", sha)
            if (branch != null) put("branch", branch)
        }.toString()
        val (status, body, transportError) = withAuthRetry { client.apiRequest("/repos/$repo/contents/${encPath(rawPath)}", "DELETE", payload) }
        if (transportError != null) return serialized(GitHubMutationResult.serializer(), GitHubMutationResult(false, error = transportError))
        if (status !in 200..299) return serialized(GitHubMutationResult.serializer(), GitHubMutationResult(false, error =
            "HTTP $status deleting $rawPath: ${githubErrorMessage(body, "no body")}"))
        return serialized(GitHubMutationResult.serializer(), GitHubMutationResult(true, message = "Deleted $rawPath"))
    }

    /**
     * githubPush — the atomic multi-file commit over the Git Data API, the API form
     * of git add + git commit + git push in one motion with no clone anywhere.
     * Blobs for every add/update, one tree against the branch's head tree (deletes
     * ride sha:null), one commit, one ref advance.
     */
    private suspend fun githubPush(args: Map<String, Any?>): ToolExecutionResult {
        requireLogin()?.let { return it }
        val repo = args["repo"] as? String ?: return ToolExecutionResult.error("Missing 'repo'")
        val branch = (args["branch"] as? String)?.trim()?.takeIf { it.isNotBlank() }
            ?: return ToolExecutionResult.error("Missing 'branch'")
        val message = (args["message"] as? String)?.trim()?.takeIf { it.isNotBlank() }
            ?: return ToolExecutionResult.error("Missing 'message'")
        val filesJson = (args["files"] as? String)?.trim().orEmpty()
        if (filesJson.isBlank()) return ToolExecutionResult.error("Missing 'files' JSON array")

        val ops = try { org.json.JSONArray(filesJson) } catch (e: Exception) {
            return ToolExecutionResult.error("files is not a valid JSON array: ${e.message}")
        }
        if (ops.length() == 0) return ToolExecutionResult.error("files contains no operations")

        // 1. Head ref of the branch.
        val (refStatus, refBody, refErr) = withAuthRetry { client.apiRequest("/repos/$repo/git/ref/heads/${encPath(branch)}", "GET") }
        if (refErr != null) return serialized(GitHubPushResult.serializer(), GitHubPushResult(false, branch = branch, error = refErr))
        if (refStatus !in 200..299) return serialized(GitHubPushResult.serializer(), GitHubPushResult(false, branch = branch,
            error = "Branch '$branch' not found (HTTP $refStatus). Create it with githubBranchCreate first, then push."))
        val headSha = try { JSONObject(refBody).optJSONObject("object")?.optString("sha") } catch (_: Exception) { null }
        if (headSha.isNullOrBlank()) return serialized(GitHubPushResult.serializer(), GitHubPushResult(false, branch = branch,
            error = "Could not read the head commit of '$branch' from GitHub's answer."))

        // 2. Base tree of that commit.
        val (commitStatus, commitBody, _) = withAuthRetry { client.apiRequest("/repos/$repo/git/commits/$headSha", "GET") }
        val baseTreeSha = if (commitStatus in 200..299) try {
            JSONObject(commitBody).optJSONObject("tree")?.optString("sha")
        } catch (_: Exception) { null } else null

        // 3. Build the tree entries: one entry per operation.
        val treeEntries = org.json.JSONArray()
        var changedFiles = 0
        for (i in 0 until ops.length()) {
            val op = try { ops.getJSONObject(i) } catch (e: Exception) {
                return serialized(GitHubPushResult.serializer(), GitHubPushResult(false, branch = branch,
                    error = "files[$i] is not an object"))
            }
            val path = op.optString("path", "").trim().trimStart('/')
            if (path.isBlank()) return serialized(GitHubPushResult.serializer(), GitHubPushResult(false, branch = branch,
                error = "files[$i] has no path"))

            if (op.optBoolean("delete", false)) {
                treeEntries.put(JSONObject().apply {
                    put("path", path)
                    put("mode", "100644")
                    put("type", "blob")
                    put("sha", JSONObject.NULL)
                })
                changedFiles++
                continue
            }

            // Content from inline text, or raw bytes from a device file.
            val inline = op.optString("content", "")
            val sourcePath = op.optString("sourcePath", "").trim()
            val bytes: ByteArray = if (sourcePath.isNotBlank()) {
                val src = java.io.File(sourcePath)
                if (!src.exists() || !src.canRead()) return serialized(GitHubPushResult.serializer(), GitHubPushResult(false,
                    branch = branch, error = "files[$i] sourcePath unreadable: $sourcePath"))
                if (src.length() > 15L * 1024 * 1024) return serialized(GitHubPushResult.serializer(), GitHubPushResult(false,
                    branch = branch, error = "files[$i] sourcePath is larger than 15MB: $sourcePath"))
                try { src.readBytes() } catch (e: Exception) {
                    return serialized(GitHubPushResult.serializer(), GitHubPushResult(false, branch = branch,
                        error = "files[$i] sourcePath read failed: ${e.message}"))
                }
            } else if (inline.isNotEmpty() || op.has("content")) {
                inline.toByteArray(Charsets.UTF_8)
            } else {
                return serialized(GitHubPushResult.serializer(), GitHubPushResult(false, branch = branch,
                    error = "files[$i] has neither content nor sourcePath"))
            }

            // Create the blob.
            val blobPayload = JSONObject().apply {
                put("content", Base64.encodeToString(bytes, Base64.NO_WRAP))
                put("encoding", "base64")
            }.toString()
            val (blobStatus, blobBody, blobErr) = withAuthRetry { client.apiRequest("/repos/$repo/git/blobs", "POST", blobPayload) }
            if (blobErr != null || blobStatus !in 200..299) {
                return serialized(GitHubPushResult.serializer(), GitHubPushResult(false, branch = branch,
                    error = "Blob for $path failed: ${blobErr ?: "HTTP $blobStatus ${githubErrorMessage(blobBody, "")}"}"))
            }
            val blobSha = try { JSONObject(blobBody).optString("sha") } catch (_: Exception) { "" }
            if (blobSha.isBlank()) return serialized(GitHubPushResult.serializer(), GitHubPushResult(false, branch = branch,
                error = "Blob for $path answered without a sha"))

            treeEntries.put(JSONObject().apply {
                put("path", path)
                put("mode", "100644")
                put("type", "blob")
                put("sha", blobSha)
            })
            changedFiles++
        }

        // 4. The tree.
        val treePayload = JSONObject().apply {
            if (!baseTreeSha.isNullOrBlank()) put("base_tree", baseTreeSha)
            put("tree", treeEntries)
        }.toString()
        val (treeStatus, treeBody, treeErr) = withAuthRetry { client.apiRequest("/repos/$repo/git/trees", "POST", treePayload) }
        if (treeErr != null || treeStatus !in 200..299) {
            return serialized(GitHubPushResult.serializer(), GitHubPushResult(false, branch = branch,
                error = "Tree build failed: ${treeErr ?: "HTTP $treeStatus ${githubErrorMessage(treeBody, "")}"}"))
        }
        val newTreeSha = try { JSONObject(treeBody).optString("sha") } catch (_: Exception) { "" }
        if (newTreeSha.isBlank()) return serialized(GitHubPushResult.serializer(), GitHubPushResult(false, branch = branch,
            error = "Tree build answered without a sha"))

        // 5. The commit.
        val commitPayload = JSONObject().apply {
            put("message", message)
            put("tree", newTreeSha)
            put("parents", org.json.JSONArray().put(headSha))
        }.toString()
        val (newCommitStatus, newCommitBody, newCommitErr) = withAuthRetry { client.apiRequest("/repos/$repo/git/commits", "POST", commitPayload) }
        if (newCommitErr != null || newCommitStatus !in 200..299) {
            return serialized(GitHubPushResult.serializer(), GitHubPushResult(false, branch = branch,
                error = "Commit failed: ${newCommitErr ?: "HTTP $newCommitStatus ${githubErrorMessage(newCommitBody, "")}"}"))
        }
        val newCommitSha = try { JSONObject(newCommitBody).optString("sha") } catch (_: Exception) { "" }
        val newCommitUrl = try { JSONObject(newCommitBody).optString("html_url", "") } catch (_: Exception) { "" }

        // 6. Advance the branch ref to the new commit.
        val refPayload = JSONObject().apply {
            put("sha", newCommitSha)
            put("force", false)
        }.toString()
        val (updateStatus, updateBody, updateErr) = withAuthRetry {
            client.apiRequest("/repos/$repo/git/refs/heads/${encPath(branch)}", "PATCH", refPayload)
        }
        if (updateErr != null || updateStatus !in 200..299) {
            return serialized(GitHubPushResult.serializer(), GitHubPushResult(false, branch = branch,
                error = "Commit ${newCommitSha.take(10)} built, but advancing '$branch' was refused: " +
                    githubErrorMessage(updateBody, updateErr ?: "HTTP $updateStatus") +
                    ". If the branch moved since this commit was built, pull and try again."))
        }

        return serialized(GitHubPushResult.serializer(), GitHubPushResult(
            success = true,
            branch = branch,
            commitSha = newCommitSha,
            commitUrl = newCommitUrl.ifBlank { "https://github.com/$repo/commit/$newCommitSha" },
            filesChanged = changedFiles
        ))
    }

    // ─────────────────────────── BRANCH TOOLS ───────────────────────────

    private suspend fun githubBranchList(args: Map<String, Any?>): ToolExecutionResult {
        val repo = args["repo"] as? String ?: return ToolExecutionResult.error("Missing 'repo'")
        val (status, body, transportError) = withAuthRetry { client.apiRequest("/repos/$repo/branches?per_page=100", "GET") }
        if (transportError != null) return serialized(GitHubBranchListResult.serializer(), GitHubBranchListResult(false, emptyList(), transportError))
        if (status !in 200..299) return serialized(GitHubBranchListResult.serializer(), GitHubBranchListResult(false, emptyList(),
            "HTTP $status listing branches: ${githubErrorMessage(body, "no body")}"))
        return try {
            val array = org.json.JSONArray(body)
            val branches = (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                GitHubBranchItem(
                    name = o.optString("name"),
                    sha = o.optJSONObject("commit")?.optString("sha", "")?.ifBlank { null },
                    isProtected = o.optBoolean("protected", false)
                )
            }
            serialized(GitHubBranchListResult.serializer(), GitHubBranchListResult(true, branches, null))
        } catch (e: Exception) {
            serialized(GitHubBranchListResult.serializer(), GitHubBranchListResult(false, emptyList(), "Unparseable JSON: ${body.take(200)}"))
        }
    }

    private suspend fun githubBranchCreate(args: Map<String, Any?>): ToolExecutionResult {
        requireLogin()?.let { return it }
        val repo = args["repo"] as? String ?: return ToolExecutionResult.error("Missing 'repo'")
        val branch = (args["branch"] as? String)?.trim()?.takeIf { it.isNotBlank() }
            ?: return ToolExecutionResult.error("Missing 'branch'")
        val fromRef = (args["fromRef"] as? String)?.trim()?.takeIf { it.isNotBlank() } ?: defaultBranchOf(repo)

        val (headStatus, headBody, headErr) = withAuthRetry { client.apiRequest("/repos/$repo/git/ref/heads/${encPath(fromRef)}", "GET") }
        if (headErr != null || headStatus !in 200..299) {
            return serialized(GitHubMutationResult.serializer(), GitHubMutationResult(false, error =
                "Could not read '$fromRef' to branch from: ${headErr ?: githubErrorMessage(headBody, "HTTP $headStatus")}"))
        }
        val fromSha = try { JSONObject(headBody).optJSONObject("object")?.optString("sha") } catch (_: Exception) { null }
        if (fromSha.isNullOrBlank()) return serialized(GitHubMutationResult.serializer(), GitHubMutationResult(false,
            error = "GitHub answered without a commit sha for '$fromRef'"))

        val payload = JSONObject().put("ref", "refs/heads/$branch").put("sha", fromSha).toString()
        val (status, body, transportError) = withAuthRetry { client.apiRequest("/repos/$repo/git/refs", "POST", payload) }
        if (transportError != null) return serialized(GitHubMutationResult.serializer(), GitHubMutationResult(false, error = transportError))
        if (status !in 200..299) return serialized(GitHubMutationResult.serializer(), GitHubMutationResult(false, error =
            "HTTP $status creating branch '$branch': ${githubErrorMessage(body, "no body")}"))
        return serialized(GitHubMutationResult.serializer(), GitHubMutationResult(true,
            message = "Created branch '$branch' from '$fromRef' (${fromSha.take(10)})",
            sha = fromSha))
    }

    private suspend fun githubBranchDelete(args: Map<String, Any?>): ToolExecutionResult {
        requireLogin()?.let { return it }
        val repo = args["repo"] as? String ?: return ToolExecutionResult.error("Missing 'repo'")
        val branch = (args["branch"] as? String)?.trim()?.takeIf { it.isNotBlank() }
            ?: return ToolExecutionResult.error("Missing 'branch'")
        val (status, body, transportError) = withAuthRetry {
            client.apiRequest("/repos/$repo/git/refs/heads/${encPath(branch)}", "DELETE")
        }
        if (transportError != null) return serialized(GitHubMutationResult.serializer(), GitHubMutationResult(false, error = transportError))
        if (status !in 200..299) return serialized(GitHubMutationResult.serializer(), GitHubMutationResult(false, error =
            "HTTP $status deleting branch '$branch': ${githubErrorMessage(body, "no body")}"))
        return serialized(GitHubMutationResult.serializer(), GitHubMutationResult(true, message = "Deleted branch '$branch'"))
    }

    // ─────────────────────────── HISTORY TOOLS ───────────────────────────

    private suspend fun githubCommitList(args: Map<String, Any?>): ToolExecutionResult {
        val repo = args["repo"] as? String ?: return ToolExecutionResult.error("Missing 'repo'")
        val ref = (args["ref"] as? String)?.trim()?.takeIf { it.isNotBlank() }
        val path = (args["path"] as? String)?.trim()?.takeIf { it.isNotBlank() }
        val limit = ((args["limit"] as? Number)?.toInt() ?: 20).coerceIn(1, 100)

        val query = mutableListOf("per_page=$limit")
        if (ref != null) query.add("sha=${encQuery(ref)}")
        if (path != null) query.add("path=${encQuery(path)}")

        val (status, body, transportError) = withAuthRetry { client.apiRequest("/repos/$repo/commits?${query.joinToString("&")}", "GET") }
        if (transportError != null) return serialized(GitHubCommitListResult.serializer(), GitHubCommitListResult(false, emptyList(), transportError))
        if (status !in 200..299) return serialized(GitHubCommitListResult.serializer(), GitHubCommitListResult(false, emptyList(),
            "HTTP $status listing commits: ${githubErrorMessage(body, "no body")}"))
        return try {
            val array = org.json.JSONArray(body)
            val commits = (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                val commit = o.optJSONObject("commit")
                val fullMessage = commit?.optString("message", "") ?: ""
                GitHubCommitSummaryItem(
                    sha = o.optString("sha"),
                    title = fullMessage.lineSequence().firstOrNull() ?: "",
                    author = commit?.optJSONObject("author")?.optString("name", "")?.ifBlank { null },
                    date = commit?.optJSONObject("author")?.optString("date", "")?.ifBlank { null },
                    url = o.optString("html_url", "").ifBlank { null }
                )
            }
            serialized(GitHubCommitListResult.serializer(), GitHubCommitListResult(true, commits, null))
        } catch (e: Exception) {
            serialized(GitHubCommitListResult.serializer(), GitHubCommitListResult(false, emptyList(), "Unparseable JSON: ${body.take(200)}"))
        }
    }

    private suspend fun githubCommitView(args: Map<String, Any?>): ToolExecutionResult {
        val repo = args["repo"] as? String ?: return ToolExecutionResult.error("Missing 'repo'")
        val sha = (args["sha"] as? String)?.trim()?.takeIf { it.isNotBlank() } ?: return ToolExecutionResult.error("Missing 'sha'")

        val (status, body, transportError) = withAuthRetry { client.apiRequest("/repos/$repo/commits/${encPath(sha)}", "GET") }
        if (transportError != null) return serialized(GitHubCommitDetailResult.serializer(), GitHubCommitDetailResult(false, error = transportError))
        if (status !in 200..299) return serialized(GitHubCommitDetailResult.serializer(), GitHubCommitDetailResult(false,
            error = "HTTP $status viewing commit: ${githubErrorMessage(body, "no body")}"))
        return try {
            val o = JSONObject(body)
            val commit = o.optJSONObject("commit")
            val filesArray = o.optJSONArray("files")
            val files = mutableListOf<GitHubCommitFileEntry>()
            if (filesArray != null) {
                for (i in 0 until filesArray.length()) {
                    val f = filesArray.getJSONObject(i)
                    files.add(GitHubCommitFileEntry(
                        path = f.optString("filename").ifBlank { f.optString("previous_filename") },
                        status = f.optString("status", "").ifBlank { null },
                        additions = f.optInt("additions", 0),
                        deletions = f.optInt("deletions", 0),
                        changes = f.optInt("changes", 0),
                        patch = f.optString("patch", "").ifBlank { null }?.take(20000)
                    ))
                }
            }
            serialized(GitHubCommitDetailResult.serializer(), GitHubCommitDetailResult(
                success = true,
                sha = o.optString("sha", sha),
                author = commit?.optJSONObject("author")?.optString("name", "")?.ifBlank { null },
                date = commit?.optJSONObject("author")?.optString("date", "")?.ifBlank { null },
                message = commit?.optString("message", "")?.ifBlank { null },
                additions = o.optJSONObject("stats")?.optInt("additions", 0) ?: 0,
                deletions = o.optJSONObject("stats")?.optInt("deletions", 0) ?: 0,
                files = files,
                url = o.optString("html_url", "").ifBlank { null }
            ))
        } catch (e: Exception) {
            serialized(GitHubCommitDetailResult.serializer(), GitHubCommitDetailResult(false, error = "Unparseable JSON: ${body.take(200)}"))
        }
    }

    // ─────────────────────────── PULL REQUEST COMPLETION ───────────────────────────

    private suspend fun githubPrMerge(args: Map<String, Any?>): ToolExecutionResult {
        requireLogin()?.let { return it }
        val repo = args["repo"] as? String ?: return ToolExecutionResult.error("Missing 'repo'")
        val number = (args["number"] as? Number)?.toInt() ?: return ToolExecutionResult.error("Missing 'number'")
        val method = (args["method"] as? String)?.takeIf { it in setOf("merge", "squash", "rebase") } ?: "merge"

        val payload = JSONObject().apply {
            put("merge_method", method)
            (args["commitTitle"] as? String)?.takeIf { it.isNotBlank() }?.let { put("commit_title", it) }
            (args["commitMessage"] as? String)?.takeIf { it.isNotBlank() }?.let { put("commit_message", it) }
        }.toString()
        val (status, body, transportError) = withAuthRetry { client.apiRequest("/repos/$repo/pulls/$number/merge", "PUT", payload) }
        if (transportError != null) return serialized(GitHubMergeResult.serializer(), GitHubMergeResult(false, error = transportError))
        return try {
            val o = JSONObject(body)
            val merged = o.optBoolean("merged", false)
            serialized(GitHubMergeResult.serializer(), GitHubMergeResult(
                success = status in 200..299 && merged,
                merged = merged,
                sha = o.optString("sha", "").ifBlank { null },
                message = o.optString("message", "").ifBlank { null },
                error = if (status in 200..299 && merged) null else githubErrorMessage(body, "Merge refused (HTTP $status)")
            ))
        } catch (e: Exception) {
            if (status in 200..299) serialized(GitHubMergeResult.serializer(), GitHubMergeResult(true, true, message = "PR #$number merged"))
            else serialized(GitHubMergeResult.serializer(), GitHubMergeResult(false,
                error = "HTTP $status: ${githubErrorMessage(body, "no body")}"))
        }
    }

    private suspend fun githubPrComment(args: Map<String, Any?>): ToolExecutionResult {
        requireLogin()?.let { return it }
        val repo = args["repo"] as? String ?: return ToolExecutionResult.error("Missing 'repo'")
        val number = (args["number"] as? Number)?.toInt() ?: return ToolExecutionResult.error("Missing 'number'")
        val body = (args["body"] as? String)?.takeIf { it.isNotBlank() } ?: return ToolExecutionResult.error("Missing 'body'")

        val payload = JSONObject().put("body", body).toString()
        val (status, respBody, transportError) = withAuthRetry { client.apiRequest("/repos/$repo/issues/$number/comments", "POST", payload) }
        if (transportError != null) return serialized(GitHubMutationResult.serializer(), GitHubMutationResult(false, error = transportError))
        if (status !in 200..299) return serialized(GitHubMutationResult.serializer(), GitHubMutationResult(false, error =
            "HTTP $status commenting: ${githubErrorMessage(respBody, "no body")}"))
        val url = try { JSONObject(respBody).optString("html_url", "").ifBlank { null } } catch (_: Exception) { null }
        return serialized(GitHubMutationResult.serializer(), GitHubMutationResult(true, message = "Commented on PR #$number", url = url))
    }

    private suspend fun githubPrClose(args: Map<String, Any?>): ToolExecutionResult {
        requireLogin()?.let { return it }
        val repo = args["repo"] as? String ?: return ToolExecutionResult.error("Missing 'repo'")
        val number = (args["number"] as? Number)?.toInt() ?: return ToolExecutionResult.error("Missing 'number'")
        val reopen = (args["reopen"] as? Boolean) ?: false
        val payload = JSONObject().put("state", if (reopen) "open" else "closed").toString()
        val (status, body, transportError) = withAuthRetry { client.apiRequest("/repos/$repo/pulls/$number", "PATCH", payload) }
        if (transportError != null) return serialized(GitHubMutationResult.serializer(), GitHubMutationResult(false, error = transportError))
        if (status !in 200..299) return serialized(GitHubMutationResult.serializer(), GitHubMutationResult(false, error =
            "HTTP $status: ${githubErrorMessage(body, "no body")}"))
        return serialized(GitHubMutationResult.serializer(), GitHubMutationResult(true,
            message = if (reopen) "Reopened PR #$number" else "Closed PR #$number"))
    }

    private suspend fun githubPrFiles(args: Map<String, Any?>): ToolExecutionResult {
        val repo = args["repo"] as? String ?: return ToolExecutionResult.error("Missing 'repo'")
        val number = (args["number"] as? Number)?.toInt() ?: return ToolExecutionResult.error("Missing 'number'")
        val (status, body, transportError) = withAuthRetry { client.apiRequest("/repos/$repo/pulls/$number/files?per_page=100", "GET") }
        if (transportError != null) return serialized(GitHubPrFilesResult.serializer(), GitHubPrFilesResult(false, emptyList(), transportError))
        if (status !in 200..299) return serialized(GitHubPrFilesResult.serializer(), GitHubPrFilesResult(false, emptyList(),
            "HTTP $status: ${githubErrorMessage(body, "no body")}"))
        return try {
            val array = org.json.JSONArray(body)
            val files = (0 until array.length()).map { i ->
                val f = array.getJSONObject(i)
                GitHubPrFileEntry(
                    path = f.optString("filename"),
                    status = f.optString("status", "").ifBlank { null },
                    additions = f.optInt("additions", 0),
                    deletions = f.optInt("deletions", 0),
                    changes = f.optInt("changes", 0),
                    patch = f.optString("patch", "").ifBlank { null }?.take(20000)
                )
            }
            serialized(GitHubPrFilesResult.serializer(), GitHubPrFilesResult(true, files, null))
        } catch (e: Exception) {
            serialized(GitHubPrFilesResult.serializer(), GitHubPrFilesResult(false, emptyList(), "Unparseable JSON: ${body.take(200)}"))
        }
    }

    // ─────────────────────────── ISSUE COMPLETION ───────────────────────────

    private suspend fun githubIssueView(args: Map<String, Any?>): ToolExecutionResult {
        val repo = args["repo"] as? String ?: return ToolExecutionResult.error("Missing 'repo'")
        val number = (args["number"] as? Number)?.toInt() ?: return ToolExecutionResult.error("Missing 'number'")
        val (status, body, transportError) = withAuthRetry { client.apiRequest("/repos/$repo/issues/$number", "GET") }
        if (transportError != null) return serialized(GitHubIssueDetailResult.serializer(), GitHubIssueDetailResult(false, null, transportError))
        if (status !in 200..299) return serialized(GitHubIssueDetailResult.serializer(), GitHubIssueDetailResult(false, null,
            "HTTP $status: ${githubErrorMessage(body, "no body")}"))
        return try {
            val o = JSONObject(body)
            val labels = mutableListOf<String>()
            o.optJSONArray("labels")?.let { arr ->
                for (j in 0 until arr.length()) labels.add(arr.getJSONObject(j).optString("name"))
            }
            val assignees = mutableListOf<String>()
            o.optJSONArray("assignees")?.let { arr ->
                for (j in 0 until arr.length()) assignees.add(arr.getJSONObject(j).optString("login"))
            }
            serialized(GitHubIssueDetailResult.serializer(), GitHubIssueDetailResult(true, GitHubIssueDetailItem(
                number = o.optInt("number", number),
                title = o.optString("title"),
                state = o.optString("state"),
                body = o.optString("body", "").ifBlank { null },
                labels = labels,
                assignees = assignees,
                author = o.optJSONObject("user")?.optString("login", "")?.ifBlank { null },
                isPullRequest = o.has("pull_request"),
                url = o.optString("html_url", "").ifBlank { null }
            ), null))
        } catch (e: Exception) {
            serialized(GitHubIssueDetailResult.serializer(), GitHubIssueDetailResult(false, null, "Unparseable JSON: ${body.take(200)}"))
        }
    }

    private suspend fun githubIssueComment(args: Map<String, Any?>): ToolExecutionResult {
        requireLogin()?.let { return it }
        val repo = args["repo"] as? String ?: return ToolExecutionResult.error("Missing 'repo'")
        val number = (args["number"] as? Number)?.toInt() ?: return ToolExecutionResult.error("Missing 'number'")
        val body = (args["body"] as? String)?.takeIf { it.isNotBlank() } ?: return ToolExecutionResult.error("Missing 'body'")
        val payload = JSONObject().put("body", body).toString()
        val (status, respBody, transportError) = withAuthRetry { client.apiRequest("/repos/$repo/issues/$number/comments", "POST", payload) }
        if (transportError != null) return serialized(GitHubMutationResult.serializer(), GitHubMutationResult(false, error = transportError))
        if (status !in 200..299) return serialized(GitHubMutationResult.serializer(), GitHubMutationResult(false, error =
            "HTTP $status: ${githubErrorMessage(respBody, "no body")}"))
        val url = try { JSONObject(respBody).optString("html_url", "").ifBlank { null } } catch (_: Exception) { null }
        return serialized(GitHubMutationResult.serializer(), GitHubMutationResult(true, message = "Commented on issue #$number", url = url))
    }

    private suspend fun githubIssueClose(args: Map<String, Any?>): ToolExecutionResult {
        requireLogin()?.let { return it }
        val repo = args["repo"] as? String ?: return ToolExecutionResult.error("Missing 'repo'")
        val number = (args["number"] as? Number)?.toInt() ?: return ToolExecutionResult.error("Missing 'number'")
        val reopen = (args["reopen"] as? Boolean) ?: false
        val payload = JSONObject().put("state", if (reopen) "open" else "closed").toString()
        val (status, body, transportError) = withAuthRetry { client.apiRequest("/repos/$repo/issues/$number", "PATCH", payload) }
        if (transportError != null) return serialized(GitHubMutationResult.serializer(), GitHubMutationResult(false, error = transportError))
        if (status !in 200..299) return serialized(GitHubMutationResult.serializer(), GitHubMutationResult(false, error =
            "HTTP $status: ${githubErrorMessage(body, "no body")}"))
        return serialized(GitHubMutationResult.serializer(), GitHubMutationResult(true,
            message = if (reopen) "Reopened issue #$number" else "Closed issue #$number"))
    }

    private suspend fun githubIssueEdit(args: Map<String, Any?>): ToolExecutionResult {
        requireLogin()?.let { return it }
        val repo = args["repo"] as? String ?: return ToolExecutionResult.error("Missing 'repo'")
        val number = (args["number"] as? Number)?.toInt() ?: return ToolExecutionResult.error("Missing 'number'")
        val title = (args["title"] as? String)?.takeIf { it.isNotBlank() }
        val body = args["body"] as? String
        val labelsArg = (args["labels"] as? String)?.trim()?.takeIf { it.isNotBlank() }
        val assigneesArg = (args["assignees"] as? String)?.trim()?.takeIf { it.isNotBlank() }

        if (title == null && body == null && labelsArg == null && assigneesArg == null) {
            return ToolExecutionResult.error("Nothing to edit — pass title, body, labels or assignees.")
        }

        val payload = JSONObject().apply {
            if (title != null) put("title", title)
            if (body != null) put("body", body)

            // '+name,-name' style needs the current lists first; a plain list replaces.
            if (labelsArg != null) {
                if (labelsArg.contains("+") && !labelsArg.startsWith("+") || labelsArg.startsWith("+") || labelsArg.startsWith("-") || labelsArg.contains(",-")) {
                    val current = currentListField(repo, number, "labels")
                    put("labels", applyListDelta(current, labelsArg))
                } else {
                    put("labels", org.json.JSONArray(labelsArg.split(",").map { it.trim() }.filter { it.isNotEmpty() }))
                }
            }
            if (assigneesArg != null) {
                if (assigneesArg.startsWith("+") || assigneesArg.startsWith("-") || assigneesArg.contains(",+") || assigneesArg.contains(",-")) {
                    val current = currentListField(repo, number, "assignees", "login")
                    put("assignees", applyListDelta(current, assigneesArg))
                } else {
                    put("assignees", org.json.JSONArray(assigneesArg.split(",").map { it.trim() }.filter { it.isNotEmpty() }))
                }
            }
        }.toString()

        val (status, respBody, transportError) = withAuthRetry { client.apiRequest("/repos/$repo/issues/$number", "PATCH", payload) }
        if (transportError != null) return serialized(GitHubMutationResult.serializer(), GitHubMutationResult(false, error = transportError))
        if (status !in 200..299) return serialized(GitHubMutationResult.serializer(), GitHubMutationResult(false, error =
            "HTTP $status: ${githubErrorMessage(respBody, "no body")}"))
        val url = try { JSONObject(respBody).optString("html_url", "").ifBlank { null } } catch (_: Exception) { null }
        return serialized(GitHubMutationResult.serializer(), GitHubMutationResult(true, message = "Edited issue #$number", url = url))
    }

    /** Current labels (or assignee logins) of an issue as a plain list. */
    private suspend fun currentListField(repo: String, number: Int, field: String, nameKey: String = "name"): List<String> {
        val (status, body, _) = client.apiRequest("/repos/$repo/issues/$number", "GET")
        if (status !in 200..299) return emptyList()
        return try {
            val out = mutableListOf<String>()
            JSONObject(body).optJSONArray(field)?.let { arr ->
                for (i in 0 until arr.length()) out.add(arr.getJSONObject(i).optString(nameKey))
            }
            out
        } catch (_: Exception) { emptyList() }
    }

    /** Apply a '+x,-y' delta spec on a base list. */
    private fun applyListDelta(base: List<String>, spec: String): org.json.JSONArray {
        val result = base.toMutableList()
        for (piece in spec.split(",").map { it.trim() }.filter { it.isNotEmpty() }) {
            when {
                piece.startsWith("+") -> {
                    val name = piece.removePrefix("+").trim()
                    if (name.isNotEmpty() && name !in result) result.add(name)
                }
                piece.startsWith("-") -> {
                    val name = piece.removePrefix("-").trim()
                    result.remove(name)
                }
                else -> result.add(piece)
            }
        }
        return org.json.JSONArray(result)
    }

    // ─────────────────────────── RELEASE TOOLS ───────────────────────────

    private fun releaseToItem(o: JSONObject): GitHubReleaseItem {
        val assets = mutableListOf<GitHubReleaseAsset>()
        o.optJSONArray("assets")?.let { arr ->
            for (i in 0 until arr.length()) {
                val a = arr.getJSONObject(i)
                assets.add(GitHubReleaseAsset(
                    id = a.optLong("id", 0),
                    name = a.optString("name"),
                    size = a.optLong("size", 0),
                    browserDownloadUrl = a.optString("browser_download_url", "").ifBlank { null }
                ))
            }
        }
        return GitHubReleaseItem(
            id = o.optLong("id", 0),
            tagName = o.optString("tag_name"),
            name = o.optString("name", "").ifBlank { null },
            draft = o.optBoolean("draft", false),
            prerelease = o.optBoolean("prerelease", false),
            createdAt = o.optString("created_at", "").ifBlank { null },
            htmlUrl = o.optString("html_url", "").ifBlank { null },
            assets = assets
        )
    }

    private suspend fun githubReleaseList(args: Map<String, Any?>): ToolExecutionResult {
        val repo = args["repo"] as? String ?: return ToolExecutionResult.error("Missing 'repo'")
        val limit = ((args["limit"] as? Number)?.toInt() ?: 10).coerceIn(1, 50)
        val (status, body, transportError) = withAuthRetry { client.apiRequest("/repos/$repo/releases?per_page=$limit", "GET") }
        if (transportError != null) return serialized(GitHubReleaseListResult.serializer(), GitHubReleaseListResult(false, emptyList(), transportError))
        if (status !in 200..299) return serialized(GitHubReleaseListResult.serializer(), GitHubReleaseListResult(false, emptyList(),
            "HTTP $status: ${githubErrorMessage(body, "no body")}"))
        return try {
            val array = org.json.JSONArray(body)
            val releases = (0 until array.length()).map { i -> releaseToItem(array.getJSONObject(i)) }
            serialized(GitHubReleaseListResult.serializer(), GitHubReleaseListResult(true, releases, null))
        } catch (e: Exception) {
            serialized(GitHubReleaseListResult.serializer(), GitHubReleaseListResult(false, emptyList(), "Unparseable JSON: ${body.take(200)}"))
        }
    }

    private suspend fun githubReleaseCreate(args: Map<String, Any?>): ToolExecutionResult {
        requireLogin()?.let { return it }
        val repo = args["repo"] as? String ?: return ToolExecutionResult.error("Missing 'repo'")
        val tag = (args["tag"] as? String)?.trim()?.takeIf { it.isNotBlank() } ?: return ToolExecutionResult.error("Missing 'tag'")

        val payload = JSONObject().apply {
            put("tag_name", tag)
            (args["name"] as? String)?.takeIf { it.isNotBlank() }?.let { put("name", it) }
            (args["body"] as? String)?.takeIf { it.isNotBlank() }?.let { put("body", it) }
            (args["targetCommitish"] as? String)?.takeIf { it.isNotBlank() }?.let { put("target_commitish", it) }
            put("draft", (args["draft"] as? Boolean) ?: false)
            put("prerelease", (args["prerelease"] as? Boolean) ?: false)
        }.toString()
        val (status, body, transportError) = withAuthRetry { client.apiRequest("/repos/$repo/releases", "POST", payload) }
        if (transportError != null) return serialized(GitHubReleaseResult.serializer(), GitHubReleaseResult(false, null, transportError))
        if (status !in 200..299) return serialized(GitHubReleaseResult.serializer(), GitHubReleaseResult(false, null,
            "HTTP $status creating release: ${githubErrorMessage(body, "no body")}"))
        return try {
            serialized(GitHubReleaseResult.serializer(), GitHubReleaseResult(true, releaseToItem(JSONObject(body)), null))
        } catch (e: Exception) {
            serialized(GitHubReleaseResult.serializer(), GitHubReleaseResult(true, GitHubReleaseItem(0, tag), null))
        }
    }

    private suspend fun githubReleaseUpload(args: Map<String, Any?>): ToolExecutionResult {
        requireLogin()?.let { return it }
        val repo = args["repo"] as? String ?: return ToolExecutionResult.error("Missing 'repo'")
        val tag = (args["tag"] as? String)?.trim()?.takeIf { it.isNotBlank() } ?: return ToolExecutionResult.error("Missing 'tag'")
        val filePath = (args["filePath"] as? String)?.trim()?.takeIf { it.isNotBlank() } ?: return ToolExecutionResult.error("Missing 'filePath'")

        val file = java.io.File(filePath)
        if (!file.exists() || !file.canRead()) {
            return serialized(GitHubMutationResult.serializer(), GitHubMutationResult(false, error = "File does not exist or cannot be read: $filePath"))
        }
        val assetName = (args["assetName"] as? String)?.trim()?.takeIf { it.isNotBlank() } ?: file.name
        val contentType = (args["contentType"] as? String)?.trim()?.takeIf { it.isNotBlank() } ?: "application/octet-stream"

        // Resolve the release by tag for its asset upload_url.
        val (getStatus, getBody, getErr) = withAuthRetry { client.apiRequest("/repos/$repo/releases/tags/${encPath(tag)}", "GET") }
        if (getErr != null || getStatus !in 200..299) {
            return serialized(GitHubMutationResult.serializer(), GitHubMutationResult(false, error =
                "Could not find release '$tag': ${getErr ?: githubErrorMessage(getBody, "HTTP $getStatus")}"))
        }
        val uploadUrl = try { JSONObject(getBody).optString("upload_url", "").substringBefore("{") } catch (_: Exception) { null }
        if (uploadUrl.isNullOrBlank()) {
            return serialized(GitHubMutationResult.serializer(), GitHubMutationResult(false, error = "Release '$tag' answered without an upload_url"))
        }

        val (status, message) = client.uploadReleaseAsset(uploadUrl, assetName, contentType, file)
        return if (status in 200..299) {
            serialized(GitHubMutationResult.serializer(), GitHubMutationResult(true,
                message = "Uploaded $assetName (${file.length() / 1024} KB) to release '$tag'"))
        } else {
            serialized(GitHubMutationResult.serializer(), GitHubMutationResult(false, error =
                "Upload of $assetName failed (HTTP $status): $message"))
        }
    }

    private suspend fun githubReleaseDelete(args: Map<String, Any?>): ToolExecutionResult {
        requireLogin()?.let { return it }
        val repo = args["repo"] as? String ?: return ToolExecutionResult.error("Missing 'repo'")
        val tag = (args["tag"] as? String)?.trim()?.takeIf { it.isNotBlank() } ?: return ToolExecutionResult.error("Missing 'tag'")
        val deleteTag = (args["deleteTag"] as? Boolean) ?: false

        val (getStatus, getBody, getErr) = withAuthRetry { client.apiRequest("/repos/$repo/releases/tags/${encPath(tag)}", "GET") }
        if (getErr != null || getStatus !in 200..299) {
            return serialized(GitHubMutationResult.serializer(), GitHubMutationResult(false, error =
                "Could not find release '$tag': ${getErr ?: githubErrorMessage(getBody, "HTTP $getStatus")}"))
        }
        val releaseId = try { JSONObject(getBody).optLong("id", 0) } catch (_: Exception) { 0L }
        if (releaseId == 0L) return serialized(GitHubMutationResult.serializer(), GitHubMutationResult(false, error = "Release '$tag' answered without an id"))

        val (status, body, transportError) = withAuthRetry { client.apiRequest("/repos/$repo/releases/$releaseId", "DELETE") }
        if (transportError != null) return serialized(GitHubMutationResult.serializer(), GitHubMutationResult(false, error = transportError))
        if (status !in 200..299) return serialized(GitHubMutationResult.serializer(), GitHubMutationResult(false, error =
            "HTTP $status deleting release: ${githubErrorMessage(body, "no body")}"))

        var message = "Deleted release '$tag'."
        if (deleteTag) {
            val (tagStatus, tagBody, _) = withAuthRetry { client.apiRequest("/repos/$repo/git/refs/tags/${encPath(tag)}", "DELETE") }
            message += if (tagStatus in 200..299) " Tag '$tag' also deleted."
            else " The tag '$tag' was left in place: ${githubErrorMessage(tagBody, "HTTP $tagStatus")}"
        }
        return serialized(GitHubMutationResult.serializer(), GitHubMutationResult(true, message = message))
    }

    // ─────────────────────────── ACTIONS ───────────────────────────

    private fun runToItem(o: JSONObject): GitHubRunItem = GitHubRunItem(
        id = o.optLong("id", 0),
        name = o.optString("name", "").ifBlank { o.optString("display_title", "").ifBlank { null } },
        branch = o.optString("head_branch", "").ifBlank { null },
        status = o.optString("status", "").ifBlank { null },
        conclusion = o.optString("conclusion", "").ifBlank { null },
        createdAt = o.optString("created_at", "").ifBlank { null },
        url = o.optString("html_url", "").ifBlank { null }
    )

    private suspend fun githubRunList(args: Map<String, Any?>): ToolExecutionResult {
        val repo = args["repo"] as? String ?: return ToolExecutionResult.error("Missing 'repo'")
        val branch = (args["branch"] as? String)?.trim()?.takeIf { it.isNotBlank() }
        val limit = ((args["limit"] as? Number)?.toInt() ?: 10).coerceIn(1, 50)

        val query = mutableListOf("per_page=$limit")
        if (branch != null) query.add("branch=${encQuery(branch)}")
        val (status, body, transportError) = withAuthRetry { client.apiRequest("/repos/$repo/actions/runs?${query.joinToString("&")}", "GET") }
        if (transportError != null) return serialized(GitHubRunListResult.serializer(), GitHubRunListResult(false, emptyList(), transportError))
        if (status !in 200..299) return serialized(GitHubRunListResult.serializer(), GitHubRunListResult(false, emptyList(),
            "HTTP $status: ${githubErrorMessage(body, "no body")}"))
        return try {
            val o = JSONObject(body)
            val runsArray = o.optJSONArray("workflow_runs")
            val runs = mutableListOf<GitHubRunItem>()
            if (runsArray != null) for (i in 0 until runsArray.length()) runs.add(runToItem(runsArray.getJSONObject(i)))
            serialized(GitHubRunListResult.serializer(), GitHubRunListResult(true, runs, null))
        } catch (e: Exception) {
            serialized(GitHubRunListResult.serializer(), GitHubRunListResult(false, emptyList(), "Unparseable JSON: ${body.take(200)}"))
        }
    }

    private suspend fun githubRunView(args: Map<String, Any?>): ToolExecutionResult {
        val repo = args["repo"] as? String ?: return ToolExecutionResult.error("Missing 'repo'")
        val runId = (args["runId"] as? Number)?.toLong()
        val workflow = (args["workflow"] as? String)?.trim()?.takeIf { it.isNotBlank() }

        val path = when {
            runId != null -> "/repos/$repo/actions/runs/$runId"
            workflow != null -> "/repos/$repo/actions/workflows/${encPath(workflow)}/runs?per_page=1"
            else -> return ToolExecutionResult.error("Pass runId or workflow")
        }
        val (status, body, transportError) = withAuthRetry { client.apiRequest(path, "GET") }
        if (transportError != null) return serialized(GitHubRunViewResult.serializer(), GitHubRunViewResult(false, null, transportError))
        if (status !in 200..299) return serialized(GitHubRunViewResult.serializer(), GitHubRunViewResult(false, null,
            "HTTP $status: ${githubErrorMessage(body, "no body")}"))
        return try {
            val o = JSONObject(body)
            val item = if (runId != null) {
                runToItem(o)
            } else {
                val runsArray = o.optJSONArray("workflow_runs")
                if (runsArray != null && runsArray.length() > 0) runToItem(runsArray.getJSONObject(0)) else null
            }
            serialized(GitHubRunViewResult.serializer(), GitHubRunViewResult(item != null, item, if (item == null) "No runs found" else null))
        } catch (e: Exception) {
            serialized(GitHubRunViewResult.serializer(), GitHubRunViewResult(false, null, "Unparseable JSON: ${body.take(200)}"))
        }
    }

    private suspend fun githubRunDispatch(args: Map<String, Any?>): ToolExecutionResult {
        requireLogin()?.let { return it }
        val repo = args["repo"] as? String ?: return ToolExecutionResult.error("Missing 'repo'")
        val workflow = (args["workflow"] as? String)?.trim()?.takeIf { it.isNotBlank() } ?: return ToolExecutionResult.error("Missing 'workflow'")
        val ref = (args["ref"] as? String)?.trim()?.takeIf { it.isNotBlank() } ?: defaultBranchOf(repo)
        val inputsJson = (args["inputsJson"] as? String)?.trim()?.takeIf { it.isNotBlank() }

        val payload = JSONObject().apply {
            put("ref", ref)
            if (inputsJson != null) {
                try { put("inputs", JSONObject(inputsJson)) }
                catch (e: Exception) { return ToolExecutionResult.error("inputsJson is not a JSON object: ${e.message}") }
            }
        }.toString()
        val (status, body, transportError) = withAuthRetry {
            client.apiRequest("/repos/$repo/actions/workflows/${encPath(workflow)}/dispatches", "POST", payload)
        }
        if (transportError != null) return serialized(GitHubMutationResult.serializer(), GitHubMutationResult(false, error = transportError))
        if (status !in 200..299) return serialized(GitHubMutationResult.serializer(), GitHubMutationResult(false, error =
            "HTTP $status dispatching '$workflow': ${githubErrorMessage(body, "no body")}"))
        return serialized(GitHubMutationResult.serializer(), GitHubMutationResult(true,
            message = "Dispatched workflow '$workflow' on '$ref'. GitHub answers 204 with no body; check githubRunList for the new run."))
    }

    private suspend fun githubRunCancel(args: Map<String, Any?>): ToolExecutionResult {
        requireLogin()?.let { return it }
        val repo = args["repo"] as? String ?: return ToolExecutionResult.error("Missing 'repo'")
        val runId = (args["runId"] as? Number)?.toLong() ?: return ToolExecutionResult.error("Missing 'runId'")
        val (status, body, transportError) = withAuthRetry { client.apiRequest("/repos/$repo/actions/runs/$runId/cancel", "POST") }
        if (transportError != null) return serialized(GitHubMutationResult.serializer(), GitHubMutationResult(false, error = transportError))
        if (status !in 200..299) return serialized(GitHubMutationResult.serializer(), GitHubMutationResult(false, error =
            "HTTP $status cancelling run: ${githubErrorMessage(body, "no body")}"))
        return serialized(GitHubMutationResult.serializer(), GitHubMutationResult(true, message = "Cancellation requested for run $runId"))
    }

    // ─────────────────────────── SEARCH, NOTIFICATIONS, GISTS, RAW API ───────────────────────────

    private suspend fun githubSearch(args: Map<String, Any?>): ToolExecutionResult {
        val type = (args["type"] as? String)?.trim()?.lowercase()?.takeIf { it in setOf("repos", "code", "issues", "users") }
            ?: return ToolExecutionResult.error("Missing or unknown 'type' — use repos, code, issues or users")
        val query = (args["query"] as? String)?.trim()?.takeIf { it.isNotBlank() } ?: return ToolExecutionResult.error("Missing 'query'")
        val limit = ((args["limit"] as? Number)?.toInt() ?: 10).coerceIn(1, 30)

        val searchType = when (type) { "repos" -> "repositories"; else -> type }
        val encoded = encQuery(query.replace(" ", "+"))
        val (status, body, transportError) = withAuthRetry {
            client.apiRequest("/search/$searchType?q=$encoded&per_page=$limit", "GET")
        }
        if (transportError != null) return serialized(GitHubSearchResult.serializer(), GitHubSearchResult(false, 0, emptyList(), transportError))
        if (status !in 200..299) return serialized(GitHubSearchResult.serializer(), GitHubSearchResult(false, 0, emptyList(),
            "HTTP $status: ${githubErrorMessage(body, "no body")}"))
        return try {
            val o = JSONObject(body)
            val total = o.optInt("total_count", 0)
            val itemsArray = o.optJSONArray("items") ?: org.json.JSONArray()
            val items = mutableListOf<GitHubSearchItem>()
            for (i in 0 until itemsArray.length()) {
                val item = itemsArray.getJSONObject(i)
                items.add(when (type) {
                    "repos" -> GitHubSearchItem(
                        title = item.optString("full_name"),
                        subtitle = item.optString("description", "").ifBlank { null },
                        url = item.optString("html_url", "").ifBlank { null }
                    )
                    "code" -> GitHubSearchItem(
                        title = item.optString("path"),
                        subtitle = item.optJSONObject("repository")?.optString("full_name", "")?.ifBlank { null },
                        url = item.optString("html_url", "").ifBlank { null }
                    )
                    "issues" -> GitHubSearchItem(
                        title = "#${item.optInt("number")} ${item.optString("title")}",
                        subtitle = item.optString("state", "").ifBlank { null },
                        url = item.optString("html_url", "").ifBlank { null }
                    )
                    else -> GitHubSearchItem(
                        title = item.optString("login"),
                        subtitle = item.optString("html_url", "").ifBlank { null },
                        url = item.optString("html_url", "").ifBlank { null }
                    )
                })
            }
            serialized(GitHubSearchResult.serializer(), GitHubSearchResult(true, total, items, null))
        } catch (e: Exception) {
            serialized(GitHubSearchResult.serializer(), GitHubSearchResult(false, 0, emptyList(), "Unparseable JSON: ${body.take(200)}"))
        }
    }

    private suspend fun githubNotificationList(args: Map<String, Any?>): ToolExecutionResult {
        requireLogin()?.let { return it }
        val all = (args["all"] as? Boolean) ?: false
        val limit = ((args["limit"] as? Number)?.toInt() ?: 20).coerceIn(1, 50)
        val (status, body, transportError) = withAuthRetry {
            client.apiRequest("/notifications?per_page=$limit" + if (all) "&all=true" else "", "GET")
        }
        if (transportError != null) return serialized(GitHubSearchResult.serializer(), GitHubSearchResult(false, 0, emptyList(), transportError))
        if (status !in 200..299) return serialized(GitHubSearchResult.serializer(), GitHubSearchResult(false, 0, emptyList(),
            "HTTP $status: ${githubErrorMessage(body, "no body")}"))
        return try {
            val array = org.json.JSONArray(body)
            val items = mutableListOf<GitHubSearchItem>()
            for (i in 0 until array.length()) {
                val o = array.getJSONObject(i)
                val subject = o.optJSONObject("subject")
                val repoName = o.optJSONObject("repository")?.optString("full_name", "") ?: ""
                val unread = o.optBoolean("unread", false)
                items.add(GitHubSearchItem(
                    title = subject?.optString("title", "(notification)") ?: "(notification)",
                    subtitle = buildString {
                        append(repoName)
                        subject?.optString("type")?.takeIf { it.isNotBlank() }?.let { append(" · $it") }
                        o.optString("reason", "").takeIf { it.isNotBlank() }?.let { append(" · $it") }
                        if (unread) append(" · unread")
                    },
                    url = o.optJSONObject("repository")?.optString("html_url", "")?.ifBlank { null }
                ))
            }
            serialized(GitHubSearchResult.serializer(), GitHubSearchResult(true, items.size, items, null))
        } catch (e: Exception) {
            serialized(GitHubSearchResult.serializer(), GitHubSearchResult(false, 0, emptyList(), "Unparseable JSON: ${body.take(200)}"))
        }
    }

    private suspend fun githubGistList(args: Map<String, Any?>): ToolExecutionResult {
        requireLogin()?.let { return it }
        val limit = ((args["limit"] as? Number)?.toInt() ?: 20).coerceIn(1, 100)
        val (status, body, transportError) = withAuthRetry { client.apiRequest("/gists?per_page=$limit", "GET") }
        if (transportError != null) return serialized(GitHubGistListResult.serializer(), GitHubGistListResult(false, emptyList(), transportError))
        if (status !in 200..299) return serialized(GitHubGistListResult.serializer(), GitHubGistListResult(false, emptyList(),
            "HTTP $status: ${githubErrorMessage(body, "no body")}"))
        return try {
            val array = org.json.JSONArray(body)
            val gists = mutableListOf<GitHubGistItem>()
            for (i in 0 until array.length()) {
                val o = array.getJSONObject(i)
                val files = mutableListOf<String>()
                o.optJSONObject("files")?.let { filesObj ->
                    val keys = filesObj.keys()
                    while (keys.hasNext()) files.add(keys.next())
                }
                gists.add(GitHubGistItem(
                    id = o.optString("id"),
                    description = o.optString("description", "").ifBlank { null },
                    isPublic = o.optBoolean("public", false),
                    files = files,
                    htmlUrl = o.optString("html_url", "").ifBlank { null },
                    updatedAt = o.optString("updated_at", "").ifBlank { null }
                ))
            }
            serialized(GitHubGistListResult.serializer(), GitHubGistListResult(true, gists, null))
        } catch (e: Exception) {
            serialized(GitHubGistListResult.serializer(), GitHubGistListResult(false, emptyList(), "Unparseable JSON: ${body.take(200)}"))
        }
    }

    private suspend fun githubGistCreate(args: Map<String, Any?>): ToolExecutionResult {
        requireLogin()?.let { return it }
        val filename = (args["filename"] as? String)?.trim()?.takeIf { it.isNotBlank() } ?: return ToolExecutionResult.error("Missing 'filename'")
        val content = args["content"] as? String ?: return ToolExecutionResult.error("Missing 'content'")

        val payload = JSONObject().apply {
            (args["description"] as? String)?.takeIf { it.isNotBlank() }?.let { put("description", it) }
            put("public", (args["public"] as? Boolean) ?: false)
            put("files", JSONObject().put(filename, JSONObject().put("content", content)))
        }.toString()
        val (status, body, transportError) = withAuthRetry { client.apiRequest("/gists", "POST", payload) }
        if (transportError != null) return serialized(GitHubMutationResult.serializer(), GitHubMutationResult(false, error = transportError))
        if (status !in 200..299) return serialized(GitHubMutationResult.serializer(), GitHubMutationResult(false, error =
            "HTTP $status creating gist: ${githubErrorMessage(body, "no body")}. Creating gists needs the 'gist' scope on the token."))
        val url = try { JSONObject(body).optString("html_url", "").ifBlank { null } } catch (_: Exception) { null }
        return serialized(GitHubMutationResult.serializer(), GitHubMutationResult(true, message = "Created gist '$filename'", url = url))
    }

    /**
     * githubApi — the universal passthrough, the GURU form of `gh api`. Any method,
     * any path on api.github.com, JSON body in, real body out. This is what keeps
     * the family complete even when GitHub ships something new.
     */
    private suspend fun githubApi(args: Map<String, Any?>): ToolExecutionResult {
        val path = (args["path"] as? String)?.trim()?.takeIf { it.isNotBlank() }
            ?: return ToolExecutionResult.error("Missing 'path' (e.g. /user)")
        val method = (args["method"] as? String)?.trim()?.uppercase()?.takeIf { it in setOf("GET", "POST", "PATCH", "PUT", "DELETE", "HEAD") } ?: "GET"
        val body = args["body"] as? String
        val maxChars = ((args["maxChars"] as? Number)?.toInt() ?: 60_000).coerceAtLeast(1_000)

        if (body != null && method in setOf("POST", "PATCH", "PUT")) {
            try { JSONObject(body) } catch (e: Exception) {
                return ToolExecutionResult.error("body is not a valid JSON object: ${e.message}")
            }
        }

        val (status, respBody, transportError) = withAuthRetry {
            client.apiRequest(if (path.startsWith("/")) path else "/$path", method, body)
        }
        if (transportError != null) return serialized(GitHubApiResult.serializer(), GitHubApiResult(false, 0, null, false, transportError))

        val truncated = respBody.length > maxChars
        val shown = if (truncated) respBody.take(maxChars) + "\n… [truncated; ${respBody.length - maxChars} more characters. Pass a bigger maxChars in a follow-up if needed.]" else respBody
        return serialized(GitHubApiResult.serializer(), GitHubApiResult(
            success = status in 200..299,
            status = status,
            body = shown,
            truncated = truncated,
            error = if (status in 200..299) null else githubErrorMessage(respBody, "HTTP $status")
        ))
    }
}