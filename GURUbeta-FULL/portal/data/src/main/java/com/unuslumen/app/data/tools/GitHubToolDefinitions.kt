// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.tools

import com.unuslumen.app.data.tools.registry.ToolDefinition
import com.unuslumen.app.data.tools.registry.ToolExecutor
import com.unuslumen.app.data.tools.registry.ToolParameter
import com.unuslumen.app.data.tools.registry.ToolParameterType
import com.unuslumen.app.data.tools.registry.ToolSetRegistration
import kotlin.reflect.KClass

/**
 * GitHubToolDefinitions — the GitHub tool family: the auth trio plus the original
 * seven, all backed by the real REST v3 API (see GitHubToolExecutor) with the
 * human's own credential sealed in the Keystore vault after a one-time login.
 *
 * Parameter names identical to the pre-existing GitHub tool set so the numen's
 * known calling convention keeps working unchanged.
 */
object GitHubToolDefinitions : ToolSetRegistration {
    const val GITHUB_STATUS = "githubStatus"
    const val GITHUB_LOGIN = "githubLogin"
    const val GITHUB_LOGIN_CHECK = "githubLoginCheck"
    const val GITHUB_LOGOUT = "githubLogout"
    const val GITHUB_PR_LIST = "githubPrList"
    const val GITHUB_PR_VIEW = "githubPrView"
    const val GITHUB_PR_CREATE = "githubPrCreate"
    const val GITHUB_ISSUE_LIST = "githubIssueList"
    const val GITHUB_ISSUE_CREATE = "githubIssueCreate"
    const val GITHUB_REPO_INFO = "githubRepoInfo"

    override val definitions = listOf(
        ToolDefinition(
            name = GITHUB_STATUS,
            description = "Check GitHub login state for this install: reports 'logged in' with the account name GitHub confirms, or 'not logged in'.",
            category = "productivity",
            parameters = emptyList(),
            permissions = emptyList()
        ),
        ToolDefinition(
            name = GITHUB_LOGIN,
            description = "Connect this install to your own GitHub account, step 1. Shows the user code to type at github.com/login/device in any browser. After entering it there, run githubLoginCheck (with the pollToken this returns) to complete the connection. After approval succeeds the credential is sealed in device hardware permanently.",
            category = "productivity",
            parameters = emptyList(),
            permissions = emptyList()
        ),
        ToolDefinition(
            name = GITHUB_LOGIN_CHECK,
            description = "Complete or check a GitHub login in progress, step 2. Call after your human has entered the user code at github.com/login/device once already returned by githubLogin. Each call runs one check: still_pending, authenticated, denied, code_expired, or transport retry guidance. The pollToken returned from githubLogin is required.",
            category = "productivity",
            parameters = listOf(
                ToolParameter("pollToken", ToolParameterType.String, true, "The pollToken string returned by the githubLogin call that showed the user code")
            ),

            permissions = emptyList()
        ),
        ToolDefinition(
            name = GITHUB_LOGOUT,
            description = "Disconnect this install's GitHub credential: destroys the stored token and its hardware sealing key. Also describes revoking the old authorisation from the account's GitHub web settings.",
            category = "productivity",
            parameters = emptyList(),
            permissions = emptyList()
        ),
        ToolDefinition(
            name = GITHUB_PR_LIST,
            description = "List pull requests in a repository. Returns PR numbers, titles, states, and authors.",
            category = "productivity",
            parameters = listOf(
                ToolParameter("repo", ToolParameterType.String, true, "Repository in owner/repo format (e.g., 'facebook/react')"),
                ToolParameter("state", ToolParameterType.String, false, "State filter: 'open', 'closed', or 'all'. Default 'open'."),
                ToolParameter("limit", ToolParameterType.Integer, false, "Maximum results. Default 20.")
            ),
            permissions = emptyList()
        ),
        ToolDefinition(
            name = GITHUB_PR_VIEW,
            description = "View details of a specific pull request including title, body, and author.",
            category = "productivity",
            parameters = listOf(
                ToolParameter("repo", ToolParameterType.String, true, "Repository in owner/repo format"),
                ToolParameter("number", ToolParameterType.Integer, true, "PR number")
            ),
            permissions = emptyList()
        ),
        ToolDefinition(
            name = GITHUB_PR_CREATE,
            description = "Create a new pull request. Requires this install to be logged in to GitHub (githubLogin).",
            category = "productivity",
            parameters = listOf(
                ToolParameter("repo", ToolParameterType.String, true, "Repository in owner/repo format"),
                ToolParameter("title", ToolParameterType.String, true, "PR title"),
                ToolParameter("body", ToolParameterType.String, true, "PR body/description"),
                ToolParameter("base", ToolParameterType.String, false, "Base branch (target). Default 'main'."),
                ToolParameter("head", ToolParameterType.String, false, "Head branch (source). Default 'HEAD'.")
            ),
            permissions = emptyList()
        ),
        ToolDefinition(
            name = GITHUB_ISSUE_LIST,
            description = "List issues in a repository. Returns issue numbers, titles, labels, and states. Pull requests are excluded even though GitHub's endpoint mixes them in.",
            category = "productivity",
            parameters = listOf(
                ToolParameter("repo", ToolParameterType.String, true, "Repository in owner/repo format"),
                ToolParameter("state", ToolParameterType.String, false, "State filter: 'open', 'closed', or 'all'. Default 'open'."),
                ToolParameter("label", ToolParameterType.String, false, "Label filter (optional)"),
                ToolParameter("limit", ToolParameterType.Integer, false, "Maximum results. Default 20.")
            ),
            permissions = emptyList()
        ),
        ToolDefinition(
            name = GITHUB_ISSUE_CREATE,
            description = "Create a new issue in a repository. Requires this install to be logged in to GitHub (githubLogin).",
            category = "productivity",
            parameters = listOf(
                ToolParameter("repo", ToolParameterType.String, true, "Repository in owner/repo format"),
                ToolParameter("title", ToolParameterType.String, true, "Issue title"),
                ToolParameter("body", ToolParameterType.String, true, "Issue body/description"),
                ToolParameter("labels", ToolParameterType.String, false, "Labels to apply (comma-separated, optional)")
            ),
            permissions = emptyList()
        ),
        ToolDefinition(
            name = GITHUB_REPO_INFO,
            description = "Get repository information: description, stars, forks, primary language for a public or (when logged in) private repository.",
            category = "productivity",
            parameters = listOf(
                ToolParameter("repo", ToolParameterType.String, true, "Repository in owner/repo format")
            ),
            permissions = emptyList()
        )
    )

    override fun executorClass(): KClass<out ToolExecutor> = GitHubToolExecutor::class
    override fun extractorClass(): KClass<out com.unuslumen.app.data.tools.registry.ToolResultExtractor>? = null
}