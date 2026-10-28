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
 * GitToolDefinitions — the git family: the numen's real terminal-level git
 * hands. Backed by the bundled git 2.45.0 binary (see GitProvider) with GitHub
 * credentials bridged from the same hardware-sealed vault the github* tools
 * use, every network operation through Tor and fail-closed when Tor is down.
 *
 * Tool contract: every tool that touches an existing repository takes repoPath,
 * the local directory of the clone (returned by gitClone as the path field).
 */
object GitToolDefinitions : ToolSetRegistration {
    const val GIT_CLONE = "gitClone"
    const val GIT_PULL = "gitPull"
    const val GIT_FETCH = "gitFetch"
    const val GIT_STATUS = "gitStatus"
    const val GIT_DIFF = "gitDiff"
    const val GIT_LOG = "gitLog"
    const val GIT_ADD = "gitAdd"
    const val GIT_COMMIT = "gitCommit"
    const val GIT_PUSH = "gitPush"
    const val GIT_BRANCH = "gitBranch"
    const val GIT_CHECKOUT = "gitCheckout"
    const val GIT_VERSION = "gitVersion"

    override val definitions = listOf(
        ToolDefinition(
            name = GIT_CLONE,
            description = "Clone a GitHub repository onto the device, authenticated with this install's GitHub login. " +
                "Works for public and private repositories. Returns the local path of the clone; every other git tool takes that path. " +
                "Requires githubLogin. Clones are stored in private app storage and never leave the device except back to GitHub.",
            category = "productivity",
            parameters = listOf(
                ToolParameter("repository", ToolParameterType.String, true, "Repository in owner/repo format (e.g. 'GuruProduction/GURUbyUnusLumen')"),
                ToolParameter("branch", ToolParameterType.String, false, "Branch to clone. Default: the repository's default branch."),
                ToolParameter("depth", ToolParameterType.Integer, false, "Shallow clone depth, e.g. 1 for just the latest commit. Omit for full history.")
            ),
            permissions = emptyList()
        ),
        ToolDefinition(
            name = GIT_PULL,
            description = "Pull the latest changes from the tracked remote into an existing local clone. Same as 'git pull'. " +
                "Requires githubLogin for private repositories.",
            category = "productivity",
            parameters = listOf(
                ToolParameter("repoPath", ToolParameterType.String, true, "Local repository path returned by gitClone")
            ),
            permissions = emptyList()
        ),
        ToolDefinition(
            name = GIT_FETCH,
            description = "Fetch from the remote without changing the working tree. Same as 'git fetch'. Requires githubLogin for private repositories.",
            category = "productivity",
            parameters = listOf(
                ToolParameter("repoPath", ToolParameterType.String, true, "Local repository path returned by gitClone"),
                ToolParameter("prune", ToolParameterType.Boolean, false, "Prune deleted remote branches. Default false.")
            ),
            permissions = emptyList()
        ),
        ToolDefinition(
            name = GIT_STATUS,
            description = "Show the working tree status of a local clone: branch, upstream, staged/modified/untracked files. Same as 'git status'. Local only, no network.",
            category = "productivity",
            parameters = listOf(
                ToolParameter("repoPath", ToolParameterType.String, true, "Local repository path returned by gitClone")
            ),
            permissions = emptyList()
        ),
        ToolDefinition(
            name = GIT_DIFF,
            description = "Show changes in the working tree or the staged index. Same as 'git diff' / 'git diff --staged' / 'git diff --stat'. Local only, no network.",
            category = "productivity",
            parameters = listOf(
                ToolParameter("repoPath", ToolParameterType.String, true, "Local repository path returned by gitClone"),
                ToolParameter("staged", ToolParameterType.Boolean, false, "Show staged changes instead of working tree changes. Default false."),
                ToolParameter("stat", ToolParameterType.Boolean, false, "Show the diffstat summary instead of the full patch. Default false."),
                ToolParameter("filePath", ToolParameterType.String, false, "Limit the diff to one file path.")
            ),
            permissions = emptyList()
        ),
        ToolDefinition(
            name = GIT_LOG,
            description = "Show commit history of the current branch. Same as 'git log'. Local only, no network.",
            category = "productivity",
            parameters = listOf(
                ToolParameter("repoPath", ToolParameterType.String, true, "Local repository path returned by gitClone"),
                ToolParameter("limit", ToolParameterType.Integer, false, "Maximum commits to return. Default 20, max 100."),
                ToolParameter("oneline", ToolParameterType.Boolean, false, "One line per commit. Default true (compact).")
            ),
            permissions = emptyList()
        ),
        ToolDefinition(
            name = GIT_ADD,
            description = "Stage file changes for the next commit. Same as 'git add'. Local only, no network.",
            category = "productivity",
            parameters = listOf(
                ToolParameter("repoPath", ToolParameterType.String, true, "Local repository path returned by gitClone"),
                ToolParameter("paths", ToolParameterType.String, true, "Path or paths to stage. Use '.' to stage everything, or a space-separated list like 'src/main.kt README.md'.")
            ),
            permissions = emptyList()
        ),
        ToolDefinition(
            name = GIT_COMMIT,
            description = "Commit staged changes with a message. Same as 'git commit -m'. Author identity comes from the signed-in GitHub account. Local only, no network.",
            category = "productivity",
            parameters = listOf(
                ToolParameter("repoPath", ToolParameterType.String, true, "Local repository path returned by gitClone"),
                ToolParameter("message", ToolParameterType.String, true, "Commit message")
            ),
            permissions = emptyList()
        ),
        ToolDefinition(
            name = GIT_PUSH,
            description = "Push commits to the remote repository, authenticated with this install's GitHub login. Same as 'git push'. " +
                "Requires githubLogin. Set remoteBranch to publish a new local branch to the remote.",
            category = "productivity",
            parameters = listOf(
                ToolParameter("repoPath", ToolParameterType.String, true, "Local repository path returned by gitClone"),
                ToolParameter("remoteBranch", ToolParameterType.String, false, "Target branch name on the remote when pushing a branch that has no upstream yet, e.g. 'main'.")
            ),
            permissions = emptyList()
        ),
        ToolDefinition(
            name = GIT_BRANCH,
            description = "List branches, either local or including remotes. Add create/fromBranch to create and switch to a new branch. Local writes, no network.",
            category = "productivity",
            parameters = listOf(
                ToolParameter("repoPath", ToolParameterType.String, true, "Local repository path returned by gitClone"),
                ToolParameter("all", ToolParameterType.Boolean, false, "Include remote-tracking branches. Default false."),
                ToolParameter("create", ToolParameterType.String, false, "Name of a new branch to create and switch to."),
                ToolParameter("fromBranch", ToolParameterType.String, false, "Base branch or commit when creating. Default: current HEAD.")
            ),
            permissions = emptyList()
        ),
        ToolDefinition(
            name = GIT_CHECKOUT,
            description = "Switch to an existing branch or restore working tree files. Same as 'git checkout'. Local only, no network.",
            category = "productivity",
            parameters = listOf(
                ToolParameter("repoPath", ToolParameterType.String, true, "Local repository path returned by gitClone"),
                ToolParameter("target", ToolParameterType.String, true, "Branch name or commit to switch to.")
            ),
            permissions = emptyList()
        ),
        ToolDefinition(
            name = GIT_VERSION,
            description = "Show the version of the bundled git binary powering these tools. Diagnostics only.",
            category = "productivity",
            parameters = emptyList(),
            permissions = emptyList()
        )
    )

    override fun executorClass(): KClass<out ToolExecutor> = GitToolExecutor::class
    override fun extractorClass(): KClass<out com.unuslumen.app.data.tools.registry.ToolResultExtractor>? = null
}
