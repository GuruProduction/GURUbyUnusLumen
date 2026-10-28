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
import com.unuslumen.app.util.shell.GitProvider
import com.unuslumen.app.util.shell.ShellResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import java.net.InetSocketAddress

/**
 * GitToolExecutor — the git tool family over the bundled git binary.
 *
 * Every tool funnels through GitProvider.runGit, which spawns the real bundled
 * git 2.45.0 as this app's UID with HOME set up, CA pinned to the bundle, the
 * vault credential exposed via the env-only askpass contract, and Tor routing
 * for network operations. Fail-closed: clone, fetch, pull and push refuse to
 * run when Tor is down; nothing in this family can leak clearnet.
 *
 * Identity for commits comes from the same account the github* tools use, read
 * live from /user. No credentials are written, cached, or logged anywhere.
 */
class GitToolExecutor(
    private val context: Context,
    private val torManager: TorManager
) : ToolExecutor {

    private val json = Json { ignoreUnknownKeys = true }
    private val git = GitProvider(context)
    private val github = GitHubClient(context, torManager)

    override suspend fun execute(toolName: String, args: Map<String, Any?>): ToolExecutionResult = when (toolName) {
        GitToolDefinitions.GIT_CLONE -> gitClone(args)
        GitToolDefinitions.GIT_PULL -> gitPull(args)
        GitToolDefinitions.GIT_FETCH -> gitFetch(args)
        GitToolDefinitions.GIT_STATUS -> gitStatus(args)
        GitToolDefinitions.GIT_DIFF -> gitDiff(args)
        GitToolDefinitions.GIT_LOG -> gitLog(args)
        GitToolDefinitions.GIT_ADD -> gitAdd(args)
        GitToolDefinitions.GIT_COMMIT -> gitCommit(args)
        GitToolDefinitions.GIT_PUSH -> gitPush(args)
        GitToolDefinitions.GIT_BRANCH -> gitBranch(args)
        GitToolDefinitions.GIT_CHECKOUT -> gitCheckout(args)
        GitToolDefinitions.GIT_VERSION -> gitVersion()
        else -> ToolExecutionResult.error("Unknown tool: $toolName")
    }

    // ─────────────────────── SHARED PLUMBING ───────────────────────

    /**
     * The one live Tor route git rides, as a proxy URL libcurl understands, or
     * null when no Tor listener is actually reachable.
     *
     * Two listeners can exist. Tor's HTTP CONNECT tunnel is the clean route, but
     * its port is only bound when the service was started with an HTTP tunnel —
     * TorManager's 8118 is a fallback value, not proof that anything is listening.
     * SOCKS is the listener the Tor service always opens.
     *
     * So the port is TESTED rather than assumed: prefer HTTP when it genuinely
     * accepts a connection, otherwise fall back to SOCKS. socks5h is used so DNS
     * is resolved inside Tor and the device never leaks a lookup for github.com.
     * No listener means null, and every network call then fails closed.
     */
    private suspend fun torProxyUrl(): String? = withContext(Dispatchers.IO) {
        if (!torManager.isReady.value) return@withContext null
        val httpPort = (torManager.getProxy().address() as? InetSocketAddress)?.port ?: 0
        val socksPort = (torManager.getSocksProxy().address() as? InetSocketAddress)?.port ?: 0
        if (httpPort > 0 && isListening(httpPort)) return@withContext "http://127.0.0.1:$httpPort"
        if (socksPort > 0 && isListening(socksPort)) return@withContext "socks5h://127.0.0.1:$socksPort"
        null
    }

    /** True when 127.0.0.1:port accepts a TCP connection right now. */
    private fun isListening(port: Int): Boolean = try {
        java.net.Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", port), 400)
        }
        true
    } catch (_: Exception) {
        false
    }

    /** This install's GitHub token, or null when not logged in. Never logged. */
    private fun tokenOrNull(): String? =
        CredentialVault.retrieve(context, CredentialVault.SERVICE_GITHUB)?.takeIf { it.isNotBlank() }

    /** Resolve repoPath to a safe app-storage directory or report the failure. */
    private fun requireRepo(args: Map<String, Any?>): Pair<java.io.File?, ToolExecutionResult?> {
        val repoPath = (args["repoPath"] as? String)?.trim().orEmpty()
        if (repoPath.isBlank()) {
            return null to ToolExecutionResult.error("Missing 'repoPath' (the path gitClone returned)")
        }
        // First try the literal path, then reposDir-relative names so 'owner/repo' style also resolves.
        val resolved = git.resolveRepo(repoPath)
            ?: git.resolveRepo(java.io.File(git.reposDir, repoPath).absolutePath)
        if (resolved == null) {
            return null to ToolExecutionResult.error(
                "Not a git repository in app storage: ${reposPathDisplay(repoPath)}. Use the path returned by gitClone."
            )
        }
        return resolved to null
    }

    private fun reposPathDisplay(path: String): String =
        if (path.startsWith("/")) path else path.ifBlank { "(empty)" }

    private fun <T : ToolResultData> serialized(serializer: KSerializer<T>, data: T): ToolExecutionResult =
        ToolExecutionResult.success(data, json.encodeToString(serializer, data))

    /**
     * The user-facing failure text for a git call. git prints progress chatter
     * ("Cloning into ...", "POST git-upload-pack") before the fatal line when it
     * fails, so taking the FIRST stderr line reports the noise, not the reason.
     * This scans stderr (then stdout) for git's own diagnosis: fatal:, error:,
     * remote:, "Permission denied", "Could not resolve", SSL/TLS phrases. When a
     * diagnosis exists it is returned with the exit code attached; otherwise the
     * exit code plus a capped tail of stderr, because the tail holds the failure,
     * not the head. Nothing is ever silently narrowed to a progress line again.
     */
    private fun gitFailureText(result: ShellResult, fallback: String): String {
        val stderr = result.stderr.trim()
        val stdout = result.stdout.trim()

        val signalPatterns = listOf(
            "fatal:", "error:", "remote:", "warning:",
            "permission denied", "could not resolve", "couldn't resolve",
            "ssl certificate", "certificate problem", "certificate verify",
            "authentication failed", "invalid username or password",
            "repository not found", "could not read from remote",
            "connection refused", "connection timed out", "network is unreachable",
            "proxy", "unable to", "failed to", "not found"
        )

        fun findDiagnosis(text: String): String? {
            if (text.isBlank()) return null
            val lines = text.lines().map { it.trim() }.filter { it.isNotBlank() }
            lines.firstOrNull { line -> signalPatterns.any { it in line.lowercase() } }?.let { return it }
            return null
        }

        val diagnosis = findDiagnosis(stderr) ?: findDiagnosis(stdout)

        return when {
            diagnosis != null -> "git exit ${result.exitCode}: ${diagnosis.take(500)}"
            stderr.isNotBlank() -> "git exit ${result.exitCode}: …${stderr.takeLast(500).trimStart()}"
            stdout.isNotBlank() -> "git exit ${result.exitCode}: …${stdout.takeLast(500).trimStart()}"
            else -> "git exit ${result.exitCode}: $fallback"
        }
    }

    // ─────────────────────── NETWORK TOOLS ───────────────────────

    private suspend fun gitClone(args: Map<String, Any?>): ToolExecutionResult {
        val repository = (args["repository"] as? String)?.trim().orEmpty()
        if (repository.isBlank() || !repository.contains("/")) {
            return ToolExecutionResult.error("Missing or malformed 'repository' — expected owner/repo")
        }
        val clean = repository.removeSuffix(".git")
        val target = git.cloneTargetPath(clean)
            ?: return ToolExecutionResult.error("Malformed repository name: $repository")

        if (target.exists() && java.io.File(target, ".git").exists()) {
            return serialized(GitCloneResult.serializer(), GitCloneResult(
                success = true,
                repository = clean,
                path = target.absolutePath,
                url = "https://github.com/$clean",
                message = "Already cloned at ${target.absolutePath}. Use gitPull to update it."
            ))
        }

        val proxyUrl = torProxyUrl() ?: return serialized(GitCloneResult.serializer(), GitCloneResult(
            success = false,
            repository = clean,
            error = "Tor is not running. Clones never leave GURU over clearnet. Bring Tor up and retry."
        ))

        val depth = (args["depth"] as? Number)?.toInt()?.takeIf { it > 0 }
        val branch = (args["branch"] as? String)?.trim()?.takeIf { it.isNotBlank() }

        // No --progress: git floods stderr with progress chatter the moment that
        // flag is set, drowning the fatal line when something fails. Quiet mode
        // keeps stderr to the real story.
        val gitArgs = mutableListOf("clone")
        if (depth != null) { gitArgs.add("--depth"); gitArgs.add(depth.toString()) }
        if (branch != null) { gitArgs.add("--branch"); gitArgs.add(branch) }
        gitArgs.add("https://github.com/$clean.git")
        gitArgs.add(target.absolutePath)

        val result = git.runGit(
            args = gitArgs,
            workingDir = git.reposDir,
            credential = tokenOrNull(),       // public repos clone fine without; private repos need it
            requireTor = true,
            torProxyUrl = proxyUrl,
            timeoutMs = GitProvider.NETWORK_TIMEOUT_MS
        )

        return if (result.success) {
            serialized(GitCloneResult.serializer(), GitCloneResult(
                success = true,
                repository = clean,
                path = target.absolutePath,
                url = "https://github.com/$clean",
                message = "Cloned $clean${branch?.let { " (branch $it)" } ?: ""} to ${target.absolutePath}"
            ))
        } else {
            // Clean up any half-clone so the next attempt starts fresh and honest.
            runCatching { target.deleteRecursively() }
            serialized(GitCloneResult.serializer(), GitCloneResult(
                success = false,
                repository = clean,
                error = gitFailureText(result, "git clone failed (exit ${result.exitCode})")
            ))
        }
    }

    private suspend fun gitPull(args: Map<String, Any?>): ToolExecutionResult {
        val (repo, error) = requireRepo(args)
        error?.let { return it }
        val proxyUrl = torProxyUrl() ?: return serialized(GitCommandResult.serializer(), GitCommandResult(
            success = false,
            repository = repo!!.name,
            command = "git pull",
            exitCode = -1,
            error = "Tor is not running. Pulls never leave GURU over clearnet. Bring Tor up and retry."
        ))
        val result = git.runGit(
            args = listOf("pull", "--ff-only"),
            workingDir = repo,
            credential = tokenOrNull(),
            requireTor = true,
            torProxyUrl = proxyUrl,
            timeoutMs = GitProvider.NETWORK_TIMEOUT_MS
        )
        return serialized(GitCommandResult.serializer(), GitCommandResult(
            success = result.success,
            repository = repo!!.absolutePath,
            command = "git pull",
            exitCode = result.exitCode,
            stdout = if (result.success) summarizePull(result) else result.stdout,
            stderr = result.stderr,
            error = if (result.success) null else gitFailureText(result, "git pull failed")
        ))
    }

    private fun summarizePull(result: ShellResult): String {
        // git prints progress chatter on stderr and the real summary there; prefer the summary lines.
        val interesting = result.stderr.lineSequence()
            .filter { line ->
                line.isNotBlank() &&
                    ("file" in line || "insertion" in line || "deletion" in line ||
                        "up to date" in line.lowercase() || "up-to-date" in line.lowercase() ||
                        "Fast-forward" in line || "Merge made" in line)
            }
            .joinToString("\n")
            .trim()
        return interesting.ifBlank { result.stderr.trim().ifBlank { "Pulled." } }
    }

    private suspend fun gitFetch(args: Map<String, Any?>): ToolExecutionResult {
        val (repo, error) = requireRepo(args)
        error?.let { return it }
        val proxyUrl = torProxyUrl() ?: return serialized(GitCommandResult.serializer(), GitCommandResult(
            success = false,
            repository = repo!!.name,
            command = "git fetch",
            exitCode = -1,
            error = "Tor is not running. Fetches never leave GURU over clearnet. Bring Tor up and retry."
        ))
        val prune = (args["prune"] as? Boolean) ?: false
        val gitArgs = mutableListOf("fetch")
        if (prune) gitArgs.add("--prune")
        val result = git.runGit(
            args = gitArgs,
            workingDir = repo,
            credential = tokenOrNull(),
            requireTor = true,
            torProxyUrl = proxyUrl,
            timeoutMs = GitProvider.NETWORK_TIMEOUT_MS
        )
        return serialized(GitCommandResult.serializer(), GitCommandResult(
            success = result.success,
            repository = repo!!.absolutePath,
            command = if (prune) "git fetch --prune" else "git fetch",
            exitCode = result.exitCode,
            stdout = result.stdout,
            stderr = result.stderr,
            error = if (result.success) null else gitFailureText(result, "git fetch failed")
        ))
    }

    private suspend fun gitPush(args: Map<String, Any?>): ToolExecutionResult {
        val (repo, error) = requireRepo(args)
        error?.let { return it }
        if (tokenOrNull() == null) {
            return serialized(GitCommandResult.serializer(), GitCommandResult(
                success = false,
                repository = repo!!.name,
                command = "git push",
                exitCode = -1,
                error = "Pushing needs a GitHub login; run githubLogin first."
            ))
        }
        val proxyUrl = torProxyUrl() ?: return serialized(GitCommandResult.serializer(), GitCommandResult(
            success = false,
            repository = repo!!.name,
            command = "git push",
            exitCode = -1,
            error = "Tor is not running. Pushes never leave GURU over clearnet. Bring Tor up and retry."
        ))
        val remoteBranch = (args["remoteBranch"] as? String)?.trim()?.takeIf { it.isNotBlank() }
        val gitArgs = mutableListOf("push")
        if (remoteBranch != null) { gitArgs.add("-u"); gitArgs.add("origin"); gitArgs.add(remoteBranch) }
        val result = git.runGit(
            args = gitArgs,
            workingDir = repo,
            credential = tokenOrNull(),
            requireTor = true,
            torProxyUrl = proxyUrl,
            timeoutMs = GitProvider.NETWORK_TIMEOUT_MS
        )
        return serialized(GitCommandResult.serializer(), GitCommandResult(
            success = result.success,
            repository = repo!!.absolutePath,
            command = gitArgs.joinToString(" ", prefix = "git "),
            exitCode = result.exitCode,
            stdout = result.stdout,
            stderr = result.stderr,
            error = if (result.success) null else gitFailureText(result, "git push failed — check branch protection or token scope")
        ))
    }

    // ─────────────────────── LOCAL READ TOOLS ───────────────────────

    private suspend fun gitStatus(args: Map<String, Any?>): ToolExecutionResult {
        val (repo, error) = requireRepo(args)
        error?.let { return it }
        val result = git.runGit(
            args = listOf("status", "--porcelain=v1", "--branch"),
            workingDir = repo
        )
        if (!result.success && result.exitCode != 0) {
            return serialized(GitStatusResult.serializer(), GitStatusResult(
                success = false, repository = repo!!.absolutePath, clean = false,
                stagedCount = 0, modifiedCount = 0, untrackedCount = 0, totalChanged = 0,
                error = gitFailureText(result, "git status failed")
            ))
        }

        var branch: String? = null
        var upstream: String? = null
        val files = mutableListOf<GitFileStatus>()

        for (line in result.stdout.lineSequence()) {
            if (line.isBlank()) continue
            if (line.startsWith("## ")) {
                val header = line.removePrefix("## ").trim()
                val arrowIdx = header.indexOf("...")
                branch = if (arrowIdx > 0) header.substring(0, arrowIdx).trim() else header.substringBefore(' ').trim()
                if (arrowIdx > 0) {
                    upstream = header.substring(arrowIdx + 3)
                        .substringBefore(" [")
                        .trim()
                        .takeIf { it.isNotBlank() }
                }
                if (branch == "HEAD") branch = header.substringBefore(' ').trim()
                continue
            }
            if (line.length >= 3) {
                val indexCode = line.substring(0, 1)
                val worktreeCode = line.substring(1, 2)
                val path = line.substring(3).trim().trim('"')
                files.add(GitFileStatus(path = path, indexCode = indexCode, worktreeCode = worktreeCode))
            }
        }

        val staged = files.count { it.indexCode != "?" && it.indexCode != " " }
        val modified = files.count { it.worktreeCode != "?" && it.worktreeCode != " " }
        val untracked = files.count { it.indexCode == "?" }
        val clean = files.isEmpty()

        return serialized(GitStatusResult.serializer(), GitStatusResult(
            success = true,
            repository = repo!!.absolutePath,
            branch = branch,
            upstream = upstream,
            clean = clean,
            stagedCount = staged,
            modifiedCount = modified,
            untrackedCount = untracked,
            totalChanged = files.size,
            files = files.take(200),
            output = if (clean) "Clean working tree" else null
        ))
    }

    private suspend fun gitDiff(args: Map<String, Any?>): ToolExecutionResult {
        val (repo, error) = requireRepo(args)
        error?.let { return it }
        val staged = (args["staged"] as? Boolean) ?: false
        val stat = (args["stat"] as? Boolean) ?: false
        val filePath = (args["filePath"] as? String)?.trim()?.takeIf { it.isNotBlank() }

        val gitArgs = mutableListOf("diff")
        if (staged) gitArgs.add("--cached")
        if (stat) gitArgs.add("--stat")
        if (filePath != null) { gitArgs.add("--"); gitArgs.add(filePath) }

        val result = git.runGit(args = gitArgs, workingDir = repo)
        val full = result.stdout
        return serialized(GitDiffResult.serializer(), GitDiffResult(
            success = result.success,
            repository = repo!!.absolutePath,
            staged = staged,
            stat = stat,
            diff = if (full.isBlank() && result.success) "No changes." else full,
            truncated = full.contains("[output truncated"),
            error = if (result.success) null else gitFailureText(result, "git diff failed")
        ))
    }

    private suspend fun gitLog(args: Map<String, Any?>): ToolExecutionResult {
        val (repo, error) = requireRepo(args)
        error?.let { return it }
        val limit = ((args["limit"] as? Number)?.toInt() ?: 20).coerceIn(1, 100)

        val result = git.runGit(
            args = listOf("log", "--pretty=format:%H\u001f%an\u001f%ad\u001f%s", "--date=short", "-n", limit.toString()),
            workingDir = repo
        )
        if (!result.success) {
            return serialized(GitLogResult.serializer(), GitLogResult(
                success = false, repository = repo!!.absolutePath,
                error = gitFailureText(result, "git log failed (empty repository?)")
            ))
        }

        val entries = result.stdout.lineSequence()
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                val parts = line.split('\u001f')
                if (parts.size >= 4) GitLogEntry(hash = parts[0], author = parts[1], date = parts[2], subject = parts[3])
                else null
            }
            .toList()

        return serialized(GitLogResult.serializer(), GitLogResult(
            success = true,
            repository = repo!!.absolutePath,
            entries = entries
        ))
    }

    // ─────────────────────── LOCAL WRITE TOOLS ───────────────────────

    private suspend fun gitAdd(args: Map<String, Any?>): ToolExecutionResult {
        val (repo, error) = requireRepo(args)
        error?.let { return it }
        val paths = (args["paths"] as? String)?.trim().orEmpty()
        if (paths.isBlank()) return ToolExecutionResult.error("Missing 'paths' — use '.' to stage everything")

        val pathList = paths.split(Regex("\\s+")).filter { it.isNotBlank() }
        val gitArgs = mutableListOf("add", "--")
        gitArgs.addAll(pathList)

        val result = git.runGit(args = gitArgs, workingDir = repo)
        return serialized(GitCommandResult.serializer(), GitCommandResult(
            success = result.success,
            repository = repo!!.absolutePath,
            command = "git add ${pathList.joinToString(" ")}",
            exitCode = result.exitCode,
            stdout = result.stdout,
            stderr = result.stderr,
            error = if (result.success) null else gitFailureText(result, "git add failed — check the paths exist")
        ))
    }

    private suspend fun gitCommit(args: Map<String, Any?>): ToolExecutionResult {
        val (repo, error) = requireRepo(args)
        error?.let { return it }
        val message = (args["message"] as? String)?.trim().orEmpty()
        if (message.isBlank()) return ToolExecutionResult.error("Missing 'message'")

        // Identity from the signed-in account so commits land attributed, not "unknown".
        val login = github.authenticatedUserLogin()
        val identity = mutableMapOf<String, String>()
        val accountName = login?.trim().orEmpty()
        if (accountName.isNotBlank()) {
            identity["user.name"] = accountName
            identity["user.email"] = "$accountName@users.noreply.github.com"
        }

        val result = git.runGit(
            args = listOf("commit", "-m", message),
            workingDir = repo,
            extraConfig = identity
        )
        return serialized(GitCommandResult.serializer(), GitCommandResult(
            success = result.success,
            repository = repo!!.absolutePath,
            command = "git commit -m \"$message\"",
            exitCode = result.exitCode,
            stdout = result.stdout,
            stderr = result.stderr,
            error = if (result.success) null else gitFailureText(result, "git commit failed — anything staged?")
        ))
    }

    private suspend fun gitBranch(args: Map<String, Any?>): ToolExecutionResult {
        val (repo, error) = requireRepo(args)
        error?.let { return it }
        val all = (args["all"] as? Boolean) ?: false
        val create = (args["create"] as? String)?.trim()?.takeIf { it.isNotBlank() }

        if (create != null) {
            val from = (args["fromBranch"] as? String)?.trim()?.takeIf { it.isNotBlank() }
            val gitArgs = mutableListOf("checkout", "-b", create)
            if (from != null) gitArgs.add(from)

            val created = git.runGit(args = gitArgs, workingDir = repo)
            return serialized(GitBranchResult.serializer(), GitBranchResult(
                success = created.success,
                repository = repo!!.absolutePath,
                current = if (created.success) create else null,
                output = if (created.success) "Switched to new branch '$create'" else null,
                error = if (created.success) null else gitFailureText(created, "git checkout -b failed")
            ))
        }

        val gitArgs = mutableListOf("branch", "--list", "--format=%(refname:short)")
        if (all) gitArgs.add("--all")

        val result = git.runGit(args = gitArgs, workingDir = repo)
        if (!result.success) {
            return serialized(GitBranchResult.serializer(), GitBranchResult(
                success = false, repository = repo!!.absolutePath,
                error = gitFailureText(result, "git branch failed")
            ))
        }

        val names = result.stdout.lineSequence().map { it.trim() }.filter { it.isNotBlank() }.toList()
        val current = names.firstOrNull { it.startsWith("* ") }?.removePrefix("* ")?.trim()
        val cleaned = names.map { it.removePrefix("* ").trim() }
        val local = cleaned.filter { !it.startsWith("remotes/") && "->" !in it }
        val remote = if (all) cleaned.filter { it.startsWith("remotes/") } else emptyList()

        return serialized(GitBranchResult.serializer(), GitBranchResult(
            success = true,
            repository = repo!!.absolutePath,
            current = current ?: currentBranchFresh(repo),
            branches = cleaned,
            localBranches = local,
            remoteBranches = remote,
            output = if (cleaned.isEmpty()) "No branches" else null
        ))
    }

    /** Branch list with --format drops the star marker; read the real current from HEAD. */
    private suspend fun currentBranchFresh(repo: java.io.File?): String? {
        if (repo == null) return null
        val result = git.runGit(args = listOf("rev-parse", "--abbrev-ref", "HEAD"), workingDir = repo)
        return result.stdout.trim().takeIf { result.success && it.isNotBlank() && it != "HEAD" }
    }

    private suspend fun gitCheckout(args: Map<String, Any?>): ToolExecutionResult {
        val (repo, error) = requireRepo(args)
        error?.let { return it }
        val target = (args["target"] as? String)?.trim().orEmpty()
        if (target.isBlank()) return ToolExecutionResult.error("Missing 'target'")

        val result = git.runGit(args = listOf("checkout", target), workingDir = repo)
        return serialized(GitCommandResult.serializer(), GitCommandResult(
            success = result.success,
            repository = repo!!.absolutePath,
            command = "git checkout $target",
            exitCode = result.exitCode,
            stdout = result.stdout,
            stderr = result.stderr,
            error = if (result.success) null else gitFailureText(result, "git checkout failed")
        ))
    }

    private suspend fun gitVersion(): ToolExecutionResult {
        val result = git.runGit(args = listOf("--version"), workingDir = null)
        return serialized(GitVersionResult.serializer(), GitVersionResult(
            success = result.success,
            version = if (result.success) result.stdout.trim() else null,
            binaryPath = git.gitPath(),
            error = if (result.success) null else gitFailureText(result, "bundled git not runnable")
        ))
    }
}
