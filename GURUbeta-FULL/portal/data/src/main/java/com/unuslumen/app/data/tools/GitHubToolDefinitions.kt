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
 * GitHubToolDefinitions — the full GitHub tool family: auth (device flow),
 * repositories, files and atomic pushes, branches, commit history, pull
 * requests in full, issues in full, releases with asset uploads, Actions,
 * search, notifications, gists, and the universal raw API passthrough for
 * anything not named.
 *
 * Everything runs on https://api.github.com (and uploads.github.com for release
 * assets) through Tor (fail closed), authenticated with the user's own token
 * sealed in the hardware vault after one device-flow login. Parameter names of
 * the original ten tools are unchanged so existing calling patterns keep working.
 * Every endpoint maps 1:1 onto the REST v3 API the gh CLI itself calls.
 */
object GitHubToolDefinitions : ToolSetRegistration {
    // Auth
    const val GITHUB_STATUS = "githubStatus"
    const val GITHUB_LOGIN = "githubLogin"
    const val GITHUB_LOGIN_CHECK = "githubLoginCheck"
    const val GITHUB_LOGOUT = "githubLogout"
    // Repos
    const val GITHUB_REPO_INFO = "githubRepoInfo"
    const val GITHUB_REPO_LIST = "githubRepoList"
    const val GITHUB_REPO_CREATE = "githubRepoCreate"
    const val GITHUB_REPO_FORK = "githubRepoFork"
    const val GITHUB_REPO_STAR = "githubRepoStar"
    const val GITHUB_REPO_TREE = "githubRepoTree"
    const val GITHUB_REPO_DOWNLOAD = "githubRepoDownload"
    // Files
    const val GITHUB_FILE_READ = "githubFileRead"
    const val GITHUB_FILE_WRITE = "githubFileWrite"
    const val GITHUB_FILE_DELETE = "githubFileDelete"
    const val GITHUB_PUSH = "githubPush"
    // Branches
    const val GITHUB_BRANCH_LIST = "githubBranchList"
    const val GITHUB_BRANCH_CREATE = "githubBranchCreate"
    const val GITHUB_BRANCH_DELETE = "githubBranchDelete"
    // History
    const val GITHUB_COMMIT_LIST = "githubCommitList"
    const val GITHUB_COMMIT_VIEW = "githubCommitView"
    // Pull requests
    const val GITHUB_PR_LIST = "githubPrList"
    const val GITHUB_PR_VIEW = "githubPrView"
    const val GITHUB_PR_CREATE = "githubPrCreate"
    const val GITHUB_PR_MERGE = "githubPrMerge"
    const val GITHUB_PR_COMMENT = "githubPrComment"
    const val GITHUB_PR_CLOSE = "githubPrClose"
    const val GITHUB_PR_FILES = "githubPrFiles"
    // Issues
    const val GITHUB_ISSUE_LIST = "githubIssueList"
    const val GITHUB_ISSUE_VIEW = "githubIssueView"
    const val GITHUB_ISSUE_CREATE = "githubIssueCreate"
    const val GITHUB_ISSUE_COMMENT = "githubIssueComment"
    const val GITHUB_ISSUE_CLOSE = "githubIssueClose"
    const val GITHUB_ISSUE_EDIT = "githubIssueEdit"
    // Releases
    const val GITHUB_RELEASE_LIST = "githubReleaseList"
    const val GITHUB_RELEASE_CREATE = "githubReleaseCreate"
    const val GITHUB_RELEASE_UPLOAD = "githubReleaseUploadAsset"
    const val GITHUB_RELEASE_DELETE = "githubReleaseDelete"
    // Actions
    const val GITHUB_RUN_LIST = "githubRunList"
    const val GITHUB_RUN_VIEW = "githubRunView"
    const val GITHUB_RUN_DISPATCH = "githubRunDispatch"
    const val GITHUB_RUN_CANCEL = "githubRunCancel"
    // Search and meta
    const val GITHUB_SEARCH = "githubSearch"
    const val GITHUB_NOTIFICATION_LIST = "githubNotificationList"
    const val GITHUB_GIST_LIST = "githubGistList"
    const val GITHUB_GIST_CREATE = "githubGistCreate"
    const val GITHUB_API = "githubApi"

    private fun repoParam(desc: String = "Repository in owner/repo format") =
        ToolParameter("repo", ToolParameterType.String, true, desc)

    override val definitions = listOf(
        // ─────────────────────────── AUTH ───────────────────────────
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

        // ─────────────────────────── REPOS ───────────────────────────
        ToolDefinition(
            name = GITHUB_REPO_INFO,
            description = "Get repository information: description, stars, forks, primary language, default branch, for a public or (when logged in) private repository.",
            category = "productivity",
            parameters = listOf(repoParam()),
            permissions = emptyList()
        ),
        ToolDefinition(
            name = GITHUB_REPO_LIST,
            description = "List repositories the logged-in user can access, as gh repo list does. Default lists the authenticated user's own repositories; set owner to a username or org for theirs, or visibility to filter.",
            category = "productivity",
            parameters = listOf(
                ToolParameter("owner", ToolParameterType.String, false, "Username or organisation whose repos to list. Default: the logged-in user."),
                ToolParameter("visibility", ToolParameterType.String, false, "'all', 'public' or 'private'. Default 'all'."),
                ToolParameter("limit", ToolParameterType.Integer, false, "Maximum results, default 30, max 100."),
                ToolParameter("sort", ToolParameterType.String, false, "Sort order: 'updated', 'created', 'pushed' or 'full_name'. Default 'updated'.")
            ),
            permissions = emptyList()
        ),
        ToolDefinition(
            name = GITHUB_REPO_CREATE,
            description = "Create a new repository for the logged-in user or in an organisation.",
            category = "productivity",
            parameters = listOf(
                ToolParameter("name", ToolParameterType.String, true, "Repository name"),
                ToolParameter("description", ToolParameterType.String, false, "Description"),
                ToolParameter("private", ToolParameterType.Boolean, false, "Create as private. Default false."),
                ToolParameter("org", ToolParameterType.String, false, "Organisation account to create the repo in. Omit for the logged-in user."),
                ToolParameter("autoInit", ToolParameterType.Boolean, false, "Initialise with a README so the repo is immediately cloneable. Default false.")
            ),
            permissions = emptyList()
        ),
        ToolDefinition(
            name = GITHUB_REPO_FORK,
            description = "Fork a repository into the logged-in user's account or a given organisation.",
            category = "productivity",
            parameters = listOf(
                repoParam(),
                ToolParameter("org", ToolParameterType.String, false, "Organisation to fork into. Omit for the logged-in user.")
            ),
            permissions = emptyList()
        ),
        ToolDefinition(
            name = GITHUB_REPO_STAR,
            description = "Star or unstar a repository. Same as gh repo star / gh repo unstar.",
            category = "productivity",
            parameters = listOf(
                repoParam(),
                ToolParameter("unstar", ToolParameterType.Boolean, false, "Remove the star instead of adding one. Default false.")
            ),
            permissions = emptyList()
        ),
        ToolDefinition(
            name = GITHUB_REPO_TREE,
            description = "List every file path in a repository at a given ref, recursive. The contents-of-everything view, same data as git ls-files on a clone.",
            category = "productivity",
            parameters = listOf(
                repoParam(),
                ToolParameter("ref", ToolParameterType.String, false, "Branch/tag/commit to list at. Default: the repo's default branch."),
                ToolParameter("path", ToolParameterType.String, false, "Only include entries under this directory path.")
            ),
            permissions = emptyList()
        ),
        ToolDefinition(
            name = GITHUB_REPO_DOWNLOAD,
            description = "Download a repository as a zip archive (the zipball, like gh repo clone --archive) straight to device storage. Returns the saved file path for the archive tools to unpack.",
            category = "productivity",
            parameters = listOf(
                repoParam(),
                ToolParameter("ref", ToolParameterType.String, false, "Branch/tag/commit to download. Default: the default branch."),
                ToolParameter("outputDir", ToolParameterType.String, false, "Directory to save the zip into. Default: the app's github-downloads folder.")
            ),
            permissions = emptyList()
        ),

        // ─────────────────────────── FILES ───────────────────────────
        ToolDefinition(
            name = GITHUB_FILE_READ,
            description = "Read a file or list a directory in a repository directly over the API, no clone needed. Returns the file's UTF-8 content, or the directory entries for a folder path.",
            category = "productivity",
            parameters = listOf(
                repoParam(),
                ToolParameter("path", ToolParameterType.String, true, "Path within the repo, e.g. 'README.md' or 'src/main'. Use empty string for the repository root."),
                ToolParameter("ref", ToolParameterType.String, false, "Branch/tag/commit. Default: the default branch.")
            ),
            permissions = emptyList()
        ),
        ToolDefinition(
            name = GITHUB_FILE_WRITE,
            description = "Create a file or update an existing file in a repository with a single commit. Same as editing on github.com; no clone needed. Provide sha only when updating; get the current sha from githubFileRead. To change many files as one atomic commit, use githubPush instead.",
            category = "productivity",
            parameters = listOf(
                repoParam(),
                ToolParameter("path", ToolParameterType.String, true, "Path for the file, e.g. 'docs/note.md'"),
                ToolParameter("content", ToolParameterType.String, true, "Full new file content, UTF-8 text"),
                ToolParameter("message", ToolParameterType.String, true, "Commit message"),
                ToolParameter("branch", ToolParameterType.String, false, "Branch to commit on. Default: the default branch."),
                ToolParameter("sha", ToolParameterType.String, false, "Current blob sha when updating an existing file. Omit for new files; supply it when overwriting, so GitHub can refuse a blind overwrite if the file changed in between."),
                ToolParameter("createBranch", ToolParameterType.Boolean, false, "When set together with branch, create this branch at the current head first if it does not exist, then commit there (the edit-on-a-new-branch flow). Default false.")
            ),
            permissions = emptyList()
        ),
        ToolDefinition(
            name = GITHUB_FILE_DELETE,
            description = "Delete a file from a repository with a single commit. The file's current sha (from githubFileRead) is required, exactly as GitHub's API demands.",
            category = "productivity",
            parameters = listOf(
                repoParam(),
                ToolParameter("path", ToolParameterType.String, true, "Path of the file to delete"),
                ToolParameter("sha", ToolParameterType.String, true, "Blob sha of the file being deleted (from githubFileRead)"),
                ToolParameter("message", ToolParameterType.String, true, "Commit message"),
                ToolParameter("branch", ToolParameterType.String, false, "Branch. Default: the default branch.")
            ),
            permissions = emptyList()
        ),
        ToolDefinition(
            name = GITHUB_PUSH,
            description = "Push changes to a repository WITHOUT cloning, as one atomic commit: send a list of file adds/updates/deletes and optionally new content read from local device files, and GURU builds the whole Git Data chain (blobs, tree, commit, ref update). This is git add/commit/push in one call. Perfect for creating multi-file projects, changing several files at once, or updating anything you generated on the device.",
            category = "productivity",
            parameters = listOf(
                repoParam(),
                ToolParameter("branch", ToolParameterType.String, true, "Branch to commit onto, e.g. 'main'. The branch must already exist; use githubBranchCreate first when starting a fresh branch."),
                ToolParameter("message", ToolParameterType.String, true, "Commit message"),
                ToolParameter("files", ToolParameterType.String, true, "JSON array of file operations. Each entry: {\"path\": \"src/app.js\", \"content\": \"file text here\", \"delete\": false}. content is the FULL file text (inline). Set \"delete\": true to remove a path, or use \"sourcePath\": \"/local/device/path\" instead of content to read the bytes from this device. Example: [{\"path\":\"README.md\",\"content\":\"# Hi\"},{\"path\":\"old.txt\",\"delete\":true}]")
            ),
            permissions = emptyList()
        ),

        // ─────────────────────────── BRANCHES ───────────────────────────
        ToolDefinition(
            name = GITHUB_BRANCH_LIST,
            description = "List branches in a repository with their head commit shas. Same as git branch -r.",
            category = "productivity",
            parameters = listOf(repoParam()),
            permissions = emptyList()
        ),
        ToolDefinition(
            name = GITHUB_BRANCH_CREATE,
            description = "Create a new branch from an existing branch or commit. Same as git checkout -b and pushing it, without a clone.",
            category = "productivity",
            parameters = listOf(
                repoParam(),
                ToolParameter("branch", ToolParameterType.String, true, "New branch name"),
                ToolParameter("fromRef", ToolParameterType.String, false, "Existing branch, tag or commit to start from. Default: the repo's default branch.")
            ),
            permissions = emptyList()
        ),
        ToolDefinition(
            name = GITHUB_BRANCH_DELETE,
            description = "Delete a branch. Same as git push origin --delete.",
            category = "productivity",
            parameters = listOf(
                repoParam(),
                ToolParameter("branch", ToolParameterType.String, true, "Branch name to delete")
            ),
            permissions = emptyList()
        ),

        // ─────────────────────────── HISTORY ───────────────────────────
        ToolDefinition(
            name = GITHUB_COMMIT_LIST,
            description = "List recent commits on a branch, exactly the API view behind gh's commit history (git log without a clone).",
            category = "productivity",
            parameters = listOf(
                repoParam(),
                ToolParameter("ref", ToolParameterType.String, false, "Branch/tag/commit. Default: the default branch."),
                ToolParameter("path", ToolParameterType.String, false, "Only commits touching this file path."),
                ToolParameter("limit", ToolParameterType.Integer, false, "Maximum results, default 20, max 100.")
            ),
            permissions = emptyList()
        ),
        ToolDefinition(
            name = GITHUB_COMMIT_VIEW,
            description = "View one commit in full: author, date, message, and every file it changed with per-file patches. The git show equivalent without a clone.",
            category = "productivity",
            parameters = listOf(
                repoParam(),
                ToolParameter("sha", ToolParameterType.String, true, "Commit sha (or branch/ref name)")
            ),
            permissions = emptyList()
        ),

        // ─────────────────────────── PULL REQUESTS ───────────────────────────
        ToolDefinition(
            name = GITHUB_PR_LIST,
            description = "List pull requests in a repository. Returns PR numbers, titles, states, and authors.",
            category = "productivity",
            parameters = listOf(
                repoParam(),
                ToolParameter("state", ToolParameterType.String, false, "State filter: 'open', 'closed', or 'all'. Default 'open'."),
                ToolParameter("limit", ToolParameterType.Integer, false, "Maximum results. Default 20.")
            ),
            permissions = emptyList()
        ),
        ToolDefinition(
            name = GITHUB_PR_VIEW,
            description = "View details of a specific pull request including title, body, state and author.",
            category = "productivity",
            parameters = listOf(
                repoParam(),
                ToolParameter("number", ToolParameterType.Integer, true, "PR number")
            ),
            permissions = emptyList()
        ),
        ToolDefinition(
            name = GITHUB_PR_CREATE,
            description = "Create a new pull request. Requires this install to be logged in to GitHub (githubLogin).",
            category = "productivity",
            parameters = listOf(
                repoParam(),
                ToolParameter("title", ToolParameterType.String, true, "PR title"),
                ToolParameter("body", ToolParameterType.String, true, "PR body/description"),
                ToolParameter("base", ToolParameterType.String, false, "Base branch (target). Default 'main'."),
                ToolParameter("head", ToolParameterType.String, false, "Head branch (source). Default 'HEAD'.")
            ),
            permissions = emptyList()
        ),
        ToolDefinition(
            name = GITHUB_PR_MERGE,
            description = "Merge a pull request. The completing end of the push workflow. Merge methods differ in history shape: merge commit joins branches, squash flattens all commits into one, rebase replays them. GitHub will reject merge when there is a conflict or a branch-protection block, with the reason returned verbatim.",
            category = "productivity",
            parameters = listOf(
                repoParam(),
                ToolParameter("number", ToolParameterType.Integer, true, "PR number"),
                ToolParameter("method", ToolParameterType.String, false, "'merge', 'squash' or 'rebase'. Default 'merge'."),
                ToolParameter("commitTitle", ToolParameterType.String, false, "Title of the merge commit (merge and squash methods)."),
                ToolParameter("commitMessage", ToolParameterType.String, false, "Body of the merge commit.")
            ),
            permissions = emptyList()
        ),
        ToolDefinition(
            name = GITHUB_PR_COMMENT,
            description = "Leave a comment on a pull request (the issue-style comment thread). Same as gh pr comment.",
            category = "productivity",
            parameters = listOf(
                repoParam(),
                ToolParameter("number", ToolParameterType.Integer, true, "PR number"),
                ToolParameter("body", ToolParameterType.String, true, "Comment text")
            ),
            permissions = emptyList()
        ),
        ToolDefinition(
            name = GITHUB_PR_CLOSE,
            description = "Close or reopen a pull request. Same as gh pr close / gh pr reopen.",
            category = "productivity",
            parameters = listOf(
                repoParam(),
                ToolParameter("number", ToolParameterType.Integer, true, "PR number"),
                ToolParameter("reopen", ToolParameterType.Boolean, false, "Reopen instead of closing. Default false.")
            ),
            permissions = emptyList()
        ),
        ToolDefinition(
            name = GITHUB_PR_FILES,
            description = "List every file in a pull request with additions, deletions and the patch for each. Same as gh pr diff on a clean checkout.",
            category = "productivity",
            parameters = listOf(
                repoParam(),
                ToolParameter("number", ToolParameterType.Integer, true, "PR number")
            ),
            permissions = emptyList()
        ),

        // ─────────────────────────── ISSUES ───────────────────────────
        ToolDefinition(
            name = GITHUB_ISSUE_LIST,
            description = "List issues in a repository. Returns issue numbers, titles, labels, and states. Pull requests are excluded even though GitHub's endpoint mixes them in.",
            category = "productivity",
            parameters = listOf(
                repoParam(),
                ToolParameter("state", ToolParameterType.String, false, "State filter: 'open', 'closed', or 'all'. Default 'open'."),
                ToolParameter("label", ToolParameterType.String, false, "Label filter (optional)"),
                ToolParameter("limit", ToolParameterType.Integer, false, "Maximum results. Default 20.")
            ),
            permissions = emptyList()
        ),
        ToolDefinition(
            name = GITHUB_ISSUE_VIEW,
            description = "View one issue in full including body, labels and assignees. Same as gh issue view.",
            category = "productivity",
            parameters = listOf(
                repoParam(),
                ToolParameter("number", ToolParameterType.Integer, true, "Issue number")
            ),
            permissions = emptyList()
        ),
        ToolDefinition(
            name = GITHUB_ISSUE_CREATE,
            description = "Create a new issue in a repository. Requires this install to be logged in to GitHub (githubLogin).",
            category = "productivity",
            parameters = listOf(
                repoParam(),
                ToolParameter("title", ToolParameterType.String, true, "Issue title"),
                ToolParameter("body", ToolParameterType.String, true, "Issue body/description"),
                ToolParameter("labels", ToolParameterType.String, false, "Labels to apply (comma-separated, optional)"),
                ToolParameter("assignees", ToolParameterType.String, false, "Usernames to assign (comma-separated, optional)")
            ),
            permissions = emptyList()
        ),
        ToolDefinition(
            name = GITHUB_ISSUE_COMMENT,
            description = "Comment on an issue. Same as gh issue comment.",
            category = "productivity",
            parameters = listOf(
                repoParam(),
                ToolParameter("number", ToolParameterType.Integer, true, "Issue number"),
                ToolParameter("body", ToolParameterType.String, true, "Comment text")
            ),
            permissions = emptyList()
        ),
        ToolDefinition(
            name = GITHUB_ISSUE_CLOSE,
            description = "Close or reopen an issue. Same as gh issue close / gh issue reopen.",
            category = "productivity",
            parameters = listOf(
                repoParam(),
                ToolParameter("number", ToolParameterType.Integer, true, "Issue number"),
                ToolParameter("reopen", ToolParameterType.Boolean, false, "Reopen instead of closing. Default false.")
            ),
            permissions = emptyList()
        ),
        ToolDefinition(
            name = GITHUB_ISSUE_EDIT,
            description = "Edit an issue's title, body, labels or assignees. Same as gh issue edit.",
            category = "productivity",
            parameters = listOf(
                repoParam(),
                ToolParameter("number", ToolParameterType.Integer, true, "Issue number"),
                ToolParameter("title", ToolParameterType.String, false, "New title (optional)"),
                ToolParameter("body", ToolParameterType.String, false, "New body (optional)"),
                ToolParameter("labels", ToolParameterType.String, false, "Replacement label list (comma-separated), or '+name,-name' style additions and removals (optional)"),
                ToolParameter("assignees", ToolParameterType.String, false, "Replacement assignee list (comma-separated; '+' prefix adds, '-' prefix removes) (optional)")
            ),
            permissions = emptyList()
        ),

        // ─────────────────────────── RELEASES ───────────────────────────
        ToolDefinition(
            name = GITHUB_RELEASE_LIST,
            description = "List releases in a repository with tags, names, asset lists and draft/prerelease flags.",
            category = "productivity",
            parameters = listOf(
                repoParam(),
                ToolParameter("limit", ToolParameterType.Integer, false, "Maximum results, default 10, max 50.")
            ),
            permissions = emptyList()
        ),
        ToolDefinition(
            name = GITHUB_RELEASE_CREATE,
            description = "Create a GitHub release for a tag. Optionally marks it a draft or prerelease, sets a name and body (changelog), and can create the tag from a branch if it does not exist yet. Same as gh release create.",
            category = "productivity",
            parameters = listOf(
                repoParam(),
                ToolParameter("tag", ToolParameterType.String, true, "Tag name, e.g. 'v1.2.0'"),
                ToolParameter("name", ToolParameterType.String, false, "Release display name. Defaults to the tag."),
                ToolParameter("body", ToolParameterType.String, false, "Release notes / changelog text."),
                ToolParameter("targetCommitish", ToolParameterType.String, false, "Branch or commit to tag when the tag does not exist yet. Default: the default branch."),
                ToolParameter("draft", ToolParameterType.Boolean, false, "Create as a draft. Default false."),
                ToolParameter("prerelease", ToolParameterType.Boolean, false, "Mark as a prerelease. Default false.")
            ),
            permissions = emptyList()
        ),
        ToolDefinition(
            name = GITHUB_RELEASE_UPLOAD,
            description = "Attach a file from this device to a release as a downloadable asset — the exact flow GURU uses to publish its own APK. Upload streams directly to GitHub over Tor. Same as gh release upload.",
            category = "productivity",
            parameters = listOf(
                repoParam(),
                ToolParameter("tag", ToolParameterType.String, true, "Release tag, e.g. 'v1.2.0'"),
                ToolParameter("filePath", ToolParameterType.String, true, "Local path of the file to upload, e.g. /storage/.../app-release.apk or an app storage path"),
                ToolParameter("assetName", ToolParameterType.String, false, "Name to show on the release. Default: the file's name."),
                ToolParameter("contentType", ToolParameterType.String, false, "MIME type. Default application/octet-stream.")
            ),
            permissions = emptyList()
        ),
        ToolDefinition(
            name = GITHUB_RELEASE_DELETE,
            description = "Delete a release. Pass deleteTag true to also remove the git tag. Same as gh release delete.",
            category = "productivity",
            parameters = listOf(
                repoParam(),
                ToolParameter("tag", ToolParameterType.String, true, "Release tag to delete"),
                ToolParameter("deleteTag", ToolParameterType.Boolean, false, "Also delete the git tag itself. Default false.")
            ),
            permissions = emptyList()
        ),

        // ─────────────────────────── ACTIONS ───────────────────────────
        ToolDefinition(
            name = GITHUB_RUN_LIST,
            description = "List recent GitHub Actions workflow runs for a repository, optionally filtered by branch or workflow. Same as gh run list.",
            category = "productivity",
            parameters = listOf(
                repoParam(),
                ToolParameter("branch", ToolParameterType.String, false, "Filter to runs on this branch (optional)"),
                ToolParameter("limit", ToolParameterType.Integer, false, "Maximum results, default 10, max 50.")
            ),
            permissions = emptyList()
        ),
        ToolDefinition(
            name = GITHUB_RUN_VIEW,
            description = "View one workflow run: name, branch, status, conclusion (success/failure/cancelled) and link. Pass runId, or get the latest run for a workflow via workflow.",
            category = "productivity",
            parameters = listOf(
                repoParam(),
                ToolParameter("runId", ToolParameterType.Integer, false, "Workflow run id (required unless workflow is given)"),
                ToolParameter("workflow", ToolParameterType.String, false, "Workflow file name, e.g. 'build.yml', to fetch its latest run (used when runId is absent)")
            ),
            permissions = emptyList()
        ),
        ToolDefinition(
            name = GITHUB_RUN_DISPATCH,
            description = "Trigger a workflow_dispatch workflow run on a branch with inputs. Same as gh workflow run.",
            category = "productivity",
            parameters = listOf(
                repoParam(),
                ToolParameter("workflow", ToolParameterType.String, true, "Workflow file name, e.g. 'build.yml', with workflow_dispatch enabled"),
                ToolParameter("ref", ToolParameterType.String, false, "Branch or tag to run from. Default: the default branch."),
                ToolParameter("inputsJson", ToolParameterType.String, false, "Optional JSON object of workflow inputs, e.g. {\"debug\":\"true\"}")
            ),
            permissions = emptyList()
        ),
        ToolDefinition(
            name = GITHUB_RUN_CANCEL,
            description = "Cancel a workflow run. Same as gh run cancel.",
            category = "productivity",
            parameters = listOf(
                repoParam(),
                ToolParameter("runId", ToolParameterType.Integer, true, "Workflow run id to cancel")
            ),
            permissions = emptyList()
        ),

        // ─────────────────────────── SEARCH & META ───────────────────────────
        ToolDefinition(
            name = GITHUB_SEARCH,
            description = "Search GitHub: repositories, code, issues and pull requests, or users. Query syntax follows GitHub search, e.g. 'repo:GuruProduction/GURUbyUnusLumen TODO' in code search.",
            category = "productivity",
            parameters = listOf(
                ToolParameter("type", ToolParameterType.String, true, "What to search: 'repos', 'code', 'issues' or 'users'"),
                ToolParameter("query", ToolParameterType.String, true, "Search query"),
                ToolParameter("limit", ToolParameterType.Integer, false, "Maximum results, default 10, max 30.")
            ),
            permissions = emptyList()
        ),
        ToolDefinition(
            name = GITHUB_NOTIFICATION_LIST,
            description = "List GitHub notifications for the logged-in user (unread by default). Same as gh's notification list.",
            category = "productivity",
            parameters = listOf(
                ToolParameter("all", ToolParameterType.Boolean, false, "Include already-read notifications. Default false (unread only)."),
                ToolParameter("limit", ToolParameterType.Integer, false, "Maximum results, default 20, max 50.")
            ),
            permissions = emptyList()
        ),
        ToolDefinition(
            name = GITHUB_GIST_LIST,
            description = "List the logged-in user's gists. Same as gh gist list.",
            category = "productivity",
            parameters = listOf(
                ToolParameter("limit", ToolParameterType.Integer, false, "Maximum results, default 20, max 100.")
            ),
            permissions = emptyList()
        ),
        ToolDefinition(
            name = GITHUB_GIST_CREATE,
            description = "Create a gist from one or more text files. Same as gh gist create. Needs the gist scope; if the token lacks it GitHub will say so plainly.",
            category = "productivity",
            parameters = listOf(
                ToolParameter("description", ToolParameterType.String, false, "Gist description"),
                ToolParameter("filename", ToolParameterType.String, true, "File name shown in the gist, e.g. 'notes.md'"),
                ToolParameter("content", ToolParameterType.String, true, "File content"),
                ToolParameter("public", ToolParameterType.Boolean, false, "Create as public. Default false (secret).")
            ),
            permissions = emptyList()
        ),
        ToolDefinition(
            name = GITHUB_API,
            description = "The universal GitHub API caller: send ANY request to api.github.com directly, exactly like 'gh api'. Use this for anything the named tools do not cover, or to reach endpoints GitHub adds in the future. Method GET for reads; POST/PATCH/PUT/DELETE for writes. path is everything after api.github.com, e.g. '/repos/OWNER/REPO/actions/secrets'. body is an optional JSON object string.",
            category = "productivity",
            parameters = listOf(
                ToolParameter("path", ToolParameterType.String, true, "API path starting with '/', e.g. '/user' or '/repos/OWNER/REPO/issues'"),
                ToolParameter("method", ToolParameterType.String, false, "HTTP method: GET, POST, PATCH, PUT or DELETE. Default GET."),
                ToolParameter("body", ToolParameterType.String, false, "Optional JSON body for POST/PATCH/PUT requests, e.g. {\"state\":\"closed\"}"),
                ToolParameter("maxChars", ToolParameterType.Integer, false, "Cap on characters of JSON returned, default 60000. The full body is requested from GitHub; this only trims the display, and truncation is always marked explicitly when it happens.")
            ),
            permissions = emptyList()
        )
    )

    override fun executorClass(): KClass<out ToolExecutor> = GitHubToolExecutor::class
    override fun extractorClass(): KClass<out com.unuslumen.app.data.tools.registry.ToolResultExtractor>? = null
}
