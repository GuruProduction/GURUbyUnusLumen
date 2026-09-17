package com.unuslumen.app.data.tools

import android.content.Context
import com.unuslumen.app.data.tor.TorManager
import com.unuslumen.app.util.shell.ShellExecutor
import com.unuslumen.app.data.tools.registry.ToolExecutionResult
import com.unuslumen.app.data.tools.registry.ToolExecutor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

class ProductivityToolExecutor(
    private val context: Context,
    private val torManager: TorManager
) : ToolExecutor {
    private val json = Json { ignoreUnknownKeys = true }
    private val shellExecutor = ShellExecutor(context)

    private fun fetchThroughTor(url: String, method: String = "GET", headers: Map<String, String> = emptyMap(), body: String? = null): Pair<String?, String?> {
        if (!torManager.isReady.value) return Pair(null, "Tor is not running. Cannot make request over clearnet.")
        var conn: HttpURLConnection? = null
        return try {
            val proxy = torManager.getSocksProxy()
            conn = (URL(url).openConnection(proxy) as HttpURLConnection)
            conn.requestMethod = method
            conn.instanceFollowRedirects = true
            headers.forEach { (key, value) -> conn.setRequestProperty(key, value) }
            if (body != null && method != "GET" && method != "HEAD") {
                conn.doOutput = true
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val responseBody = if (conn.responseCode in 200..299) conn.inputStream.bufferedReader().use { it.readText() }
            else conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
            Pair(responseBody, null)
        } catch (e: Exception) { Pair(null, "Request failed: ${e.message}") }
        finally { conn?.disconnect() }
    }

    override suspend fun execute(toolName: String, args: Map<String, Any?>): ToolExecutionResult = when (toolName) {
        ProductivityToolDefinitions.GITHUB_STATUS -> githubStatus()
        ProductivityToolDefinitions.GITHUB_PR_LIST -> githubPrList(args)
        ProductivityToolDefinitions.GITHUB_PR_VIEW -> githubPrView(args)
        ProductivityToolDefinitions.GITHUB_PR_CREATE -> githubPrCreate(args)
        ProductivityToolDefinitions.GITHUB_ISSUE_LIST -> githubIssueList(args)
        ProductivityToolDefinitions.GITHUB_ISSUE_CREATE -> githubIssueCreate(args)
        ProductivityToolDefinitions.GITHUB_REPO_INFO -> githubRepoInfo(args)
        ProductivityToolDefinitions.TRELLO_BOARDS -> trelloBoards()
        ProductivityToolDefinitions.TRELLO_LISTS -> trelloLists(args)
        ProductivityToolDefinitions.TRELLO_CARDS -> trelloCards(args)
        ProductivityToolDefinitions.TRELLO_CREATE_CARD -> trelloCreateCard(args)
        ProductivityToolDefinitions.TRELLO_MOVE_CARD -> trelloMoveCard(args)
        ProductivityToolDefinitions.NOTION_SEARCH -> notionSearch(args)
        ProductivityToolDefinitions.NOTION_GET_PAGE -> notionGetPage(args)
        ProductivityToolDefinitions.NOTION_CREATE_PAGE -> notionCreatePage(args)
        ProductivityToolDefinitions.DIAGRAM_CREATE -> diagramCreate(args)
        else -> ToolExecutionResult.error("Unknown tool: $toolName")
    }

    private suspend fun githubStatus(): ToolExecutionResult = withContext(Dispatchers.IO) {
        val result = shellExecutor.execute("gh auth status 2>&1")
        val isLoggedIn = result.stdout.contains("logged in", ignoreCase = true) || result.stdout.contains("active", ignoreCase = true)
        val r = if (isLoggedIn) {
            val userResult = shellExecutor.execute("gh api user --jq '.login' 2>/dev/null")
            GitHubStatusResult(success = true, isLoggedIn = true, username = userResult.stdout.trim(), error = null)
        } else GitHubStatusResult(success = false, isLoggedIn = false, username = null, error = "Not logged in to GitHub. Run 'gh auth login' first.")
        ToolExecutionResult.success(r, json.encodeToString(GitHubStatusResult.serializer(), r))
    }

    private suspend fun githubPrList(args: Map<String, Any?>): ToolExecutionResult = withContext(Dispatchers.IO) {
        val repo = args["repo"] as? String ?: return@withContext ToolExecutionResult.error("Missing 'repo'")
        val state = args["state"] as? String ?: "open"
        val limit = (args["limit"] as? Number)?.toInt() ?: 20
        val result = shellExecutor.execute("gh pr list --repo $repo --state $state --limit $limit --json number,title,state,author,url 2>/dev/null")
        val r = if (result.success && result.stdout.isNotBlank()) GitHubPrListResult(success = true, prs = parsePrs(result.stdout), error = null)
        else GitHubPrListResult(success = false, prs = emptyList(), error = "Failed to list PRs: ${result.stderr}")
        ToolExecutionResult.success(r, json.encodeToString(GitHubPrListResult.serializer(), r))
    }

    private suspend fun githubPrView(args: Map<String, Any?>): ToolExecutionResult = withContext(Dispatchers.IO) {
        val repo = args["repo"] as? String ?: return@withContext ToolExecutionResult.error("Missing 'repo'")
        val number = (args["number"] as? Number)?.toInt() ?: return@withContext ToolExecutionResult.error("Missing 'number'")
        val result = shellExecutor.execute("gh pr view $number --repo $repo --json title,body,author,files,commits,reviews,reviewDecision,state,url 2>/dev/null")
        val r = if (result.success && result.stdout.isNotBlank()) GitHubPrViewResult(success = true, pr = parsePrDetails(result.stdout), error = null)
        else GitHubPrViewResult(success = false, pr = null, error = "Failed to view PR: ${result.stderr}")
        ToolExecutionResult.success(r, json.encodeToString(GitHubPrViewResult.serializer(), r))
    }

    private suspend fun githubPrCreate(args: Map<String, Any?>): ToolExecutionResult = withContext(Dispatchers.IO) {
        val repo = args["repo"] as? String ?: return@withContext ToolExecutionResult.error("Missing 'repo'")
        val title = args["title"] as? String ?: return@withContext ToolExecutionResult.error("Missing 'title'")
        val body = args["body"] as? String ?: return@withContext ToolExecutionResult.error("Missing 'body'")
        val base = args["base"] as? String ?: "main"
        val head = args["head"] as? String ?: "HEAD"
        val escapedTitle = title.replace("\"", "\\\"").replace("'", "\\'")
        val escapedBody = body.replace("\"", "\\\"").replace("'", "\\'")
        val result = shellExecutor.execute("gh pr create --repo $repo --title \"$escapedTitle\" --body \"$escapedBody\" --base $base --head $head 2>/dev/null")
        val r = if (result.success && result.stdout.contains("pull/")) {
            val prUrl = result.stdout.trim(); val prNumber = prUrl.substringAfterLast("/").toIntOrNull() ?: 0
            GitHubPrCreateResult(success = true, prNumber = prNumber, prUrl = prUrl, error = null)
        } else GitHubPrCreateResult(success = false, prNumber = null, prUrl = null, error = "Failed to create PR: ${result.stderr}")
        ToolExecutionResult.success(r, json.encodeToString(GitHubPrCreateResult.serializer(), r))
    }

    private suspend fun githubIssueList(args: Map<String, Any?>): ToolExecutionResult = withContext(Dispatchers.IO) {
        val repo = args["repo"] as? String ?: return@withContext ToolExecutionResult.error("Missing 'repo'")
        val state = args["state"] as? String ?: "open"
        val label = args["label"] as? String
        val limit = (args["limit"] as? Number)?.toInt() ?: 20
        val labelArg = label?.let { "--label \"$it\"" } ?: ""
        val result = shellExecutor.execute("gh issue list --repo $repo --state $state $labelArg --limit $limit --json number,title,labels,state,url 2>/dev/null")
        val r = if (result.success && result.stdout.isNotBlank()) GitHubIssueListResult(success = true, issues = parseIssues(result.stdout), error = null)
        else GitHubIssueListResult(success = false, issues = emptyList(), error = "Failed to list issues: ${result.stderr}")
        ToolExecutionResult.success(r, json.encodeToString(GitHubIssueListResult.serializer(), r))
    }

    private suspend fun githubIssueCreate(args: Map<String, Any?>): ToolExecutionResult = withContext(Dispatchers.IO) {
        val repo = args["repo"] as? String ?: return@withContext ToolExecutionResult.error("Missing 'repo'")
        val title = args["title"] as? String ?: return@withContext ToolExecutionResult.error("Missing 'title'")
        val body = args["body"] as? String ?: return@withContext ToolExecutionResult.error("Missing 'body'")
        val labels = args["labels"] as? String
        val escapedTitle = title.replace("\"", "\\\"").replace("'", "\\'")
        val escapedBody = body.replace("\"", "\\\"").replace("'", "\\'")
        val labelArg = labels?.let { "--label \"$it\"" } ?: ""
        val result = shellExecutor.execute("gh issue create --repo $repo --title \"$escapedTitle\" --body \"$escapedBody\" $labelArg 2>/dev/null")
        val r = if (result.success && result.stdout.contains("issues/")) {
            val issueUrl = result.stdout.trim(); val issueNumber = issueUrl.substringAfterLast("/").toIntOrNull() ?: 0
            GitHubIssueCreateResult(success = true, issueNumber = issueNumber, issueUrl = issueUrl, error = null)
        } else GitHubIssueCreateResult(success = false, issueNumber = null, issueUrl = null, error = "Failed to create issue: ${result.stderr}")
        ToolExecutionResult.success(r, json.encodeToString(GitHubIssueCreateResult.serializer(), r))
    }

    private suspend fun githubRepoInfo(args: Map<String, Any?>): ToolExecutionResult = withContext(Dispatchers.IO) {
        val repo = args["repo"] as? String ?: return@withContext ToolExecutionResult.error("Missing 'repo'")
        val result = shellExecutor.execute("gh repo view $repo --json name,description,stargazerCount,forkCount,primaryLanguage,url 2>/dev/null")
        val r = if (result.success && result.stdout.isNotBlank()) GitHubRepoInfoResult(success = true, repo = parseRepoInfo(result.stdout), error = null)
        else GitHubRepoInfoResult(success = false, repo = null, error = "Failed to get repo info: ${result.stderr}")
        ToolExecutionResult.success(r, json.encodeToString(GitHubRepoInfoResult.serializer(), r))
    }

    private suspend fun trelloBoards(): ToolExecutionResult = withContext(Dispatchers.IO) {
        val apiKey = System.getenv("TRELLO_API_KEY") ?: ""; val token = System.getenv("TRELLO_TOKEN") ?: ""
        if (apiKey.isBlank() || token.isBlank()) {
            val r = TrelloBoardsResult(success = false, boards = emptyList(), error = "TRELLO_API_KEY and TRELLO_TOKEN not set")
            return@withContext ToolExecutionResult.success(r, json.encodeToString(TrelloBoardsResult.serializer(), r))
        }
        val (body, error) = fetchThroughTor("https://api.trello.com/1/members/me/boards?key=$apiKey&token=$token")
        val r = if (body != null && body.isNotBlank()) TrelloBoardsResult(success = true, boards = parseTrelloBoards(body), error = null)
        else TrelloBoardsResult(success = false, boards = emptyList(), error = error ?: "Unknown error")
        ToolExecutionResult.success(r, json.encodeToString(TrelloBoardsResult.serializer(), r))
    }

    private suspend fun trelloLists(args: Map<String, Any?>): ToolExecutionResult = withContext(Dispatchers.IO) {
        val boardId = args["boardId"] as? String ?: return@withContext ToolExecutionResult.error("Missing 'boardId'")
        val apiKey = System.getenv("TRELLO_API_KEY") ?: ""; val token = System.getenv("TRELLO_TOKEN") ?: ""
        val (body, error) = fetchThroughTor("https://api.trello.com/1/boards/$boardId/lists?key=$apiKey&token=$token")
        val r = if (body != null && body.isNotBlank()) TrelloListsResult(success = true, lists = parseTrelloLists(body), error = null)
        else TrelloListsResult(success = false, lists = emptyList(), error = error ?: "Unknown error")
        ToolExecutionResult.success(r, json.encodeToString(TrelloListsResult.serializer(), r))
    }

    private suspend fun trelloCards(args: Map<String, Any?>): ToolExecutionResult = withContext(Dispatchers.IO) {
        val listId = args["listId"] as? String ?: return@withContext ToolExecutionResult.error("Missing 'listId'")
        val apiKey = System.getenv("TRELLO_API_KEY") ?: ""; val token = System.getenv("TRELLO_TOKEN") ?: ""
        val (body, error) = fetchThroughTor("https://api.trello.com/1/lists/$listId/cards?key=$apiKey&token=$token")
        val r = if (body != null && body.isNotBlank()) TrelloCardsResult(success = true, cards = parseTrelloCards(body), error = null)
        else TrelloCardsResult(success = false, cards = emptyList(), error = error ?: "Unknown error")
        ToolExecutionResult.success(r, json.encodeToString(TrelloCardsResult.serializer(), r))
    }

    private suspend fun trelloCreateCard(args: Map<String, Any?>): ToolExecutionResult = withContext(Dispatchers.IO) {
        val listId = args["listId"] as? String ?: return@withContext ToolExecutionResult.error("Missing 'listId'")
        val name = args["name"] as? String ?: return@withContext ToolExecutionResult.error("Missing 'name'")
        val description = args["description"] as? String
        val apiKey = System.getenv("TRELLO_API_KEY") ?: ""; val token = System.getenv("TRELLO_TOKEN") ?: ""
        val escapedName = URLEncoder.encode(name, StandardCharsets.UTF_8.name())
        val descArg = description?.let { "&desc=${URLEncoder.encode(it, StandardCharsets.UTF_8.name())}" } ?: ""
        val (body, error) = fetchThroughTor("https://api.trello.com/1/cards?key=$apiKey&token=$token&idList=$listId&name=$escapedName$descArg", "POST")
        val r = if (body != null && body.contains("\"id\"")) {
            val cardId = Regex("\"id\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.get(1) ?: ""
            TrelloCardResult(success = true, cardId = cardId, error = null)
        } else TrelloCardResult(success = false, cardId = null, error = error ?: "Failed to create card")
        ToolExecutionResult.success(r, json.encodeToString(TrelloCardResult.serializer(), r))
    }

    private suspend fun trelloMoveCard(args: Map<String, Any?>): ToolExecutionResult = withContext(Dispatchers.IO) {
        val cardId = args["cardId"] as? String ?: return@withContext ToolExecutionResult.error("Missing 'cardId'")
        val targetListId = args["targetListId"] as? String ?: return@withContext ToolExecutionResult.error("Missing 'targetListId'")
        val apiKey = System.getenv("TRELLO_API_KEY") ?: ""; val token = System.getenv("TRELLO_TOKEN") ?: ""
        val (body, error) = fetchThroughTor("https://api.trello.com/1/cards/$cardId?key=$apiKey&token=$token&idList=$targetListId", "PUT")
        val r = if (body != null) TrelloCardResult(success = true, cardId = cardId, error = null)
        else TrelloCardResult(success = false, cardId = null, error = error ?: "Failed to move card")
        ToolExecutionResult.success(r, json.encodeToString(TrelloCardResult.serializer(), r))
    }

    private suspend fun notionSearch(args: Map<String, Any?>): ToolExecutionResult = withContext(Dispatchers.IO) {
        val query = args["query"] as? String ?: return@withContext ToolExecutionResult.error("Missing 'query'")
        val limit = (args["limit"] as? Number)?.toInt() ?: 10
        val cliResult = shellExecutor.execute("ntn api v1/search query=\"$query\" page_size:=$limit --json 2>/dev/null")
        if (cliResult.success && cliResult.stdout.isNotBlank()) {
            val r = NotionSearchResult(success = true, results = parseNotionResults(cliResult.stdout), error = null)
            return@withContext ToolExecutionResult.success(r, json.encodeToString(NotionSearchResult.serializer(), r))
        }
        val apiToken = System.getenv("NOTION_API_TOKEN") ?: ""
        if (apiToken.isBlank()) {
            val r = NotionSearchResult(success = false, results = emptyList(), error = "NOTION_API_TOKEN not set")
            return@withContext ToolExecutionResult.success(r, json.encodeToString(NotionSearchResult.serializer(), r))
        }
        val requestBody = """{"query":"$query","page_size":$limit}"""
        val (body, error) = fetchThroughTor("https://api.notion.com/v1/search", "POST", mapOf("Authorization" to "Bearer $apiToken", "Notion-Version" to "2022-06-28", "Content-Type" to "application/json"), requestBody)
        val r = if (body != null && body.isNotBlank()) NotionSearchResult(success = true, results = parseNotionResults(body), error = null)
        else NotionSearchResult(success = false, results = emptyList(), error = error ?: "Unknown error")
        ToolExecutionResult.success(r, json.encodeToString(NotionSearchResult.serializer(), r))
    }

    private suspend fun notionGetPage(args: Map<String, Any?>): ToolExecutionResult = withContext(Dispatchers.IO) {
        val pageId = args["pageId"] as? String ?: return@withContext ToolExecutionResult.error("Missing 'pageId'")
        val cliResult = shellExecutor.execute("ntn pages get $pageId --json 2>/dev/null")
        if (cliResult.success && cliResult.stdout.isNotBlank()) {
            val r = NotionPageResult(success = true, page = parseNotionPage(cliResult.stdout), error = null)
            return@withContext ToolExecutionResult.success(r, json.encodeToString(NotionPageResult.serializer(), r))
        }
        val apiToken = System.getenv("NOTION_API_TOKEN") ?: ""
        if (apiToken.isBlank()) {
            val r = NotionPageResult(success = false, page = null, error = "NOTION_API_TOKEN not set")
            return@withContext ToolExecutionResult.success(r, json.encodeToString(NotionPageResult.serializer(), r))
        }
        val (body, error) = fetchThroughTor("https://api.notion.com/v1/pages/$pageId", "GET", mapOf("Authorization" to "Bearer $apiToken", "Notion-Version" to "2022-06-28"))
        val r = if (body != null && body.isNotBlank()) NotionPageResult(success = true, page = parseNotionPage(body), error = null)
        else NotionPageResult(success = false, page = null, error = error ?: "Failed to get page")
        ToolExecutionResult.success(r, json.encodeToString(NotionPageResult.serializer(), r))
    }

    private suspend fun notionCreatePage(args: Map<String, Any?>): ToolExecutionResult = withContext(Dispatchers.IO) {
        val parentId = args["parentId"] as? String ?: return@withContext ToolExecutionResult.error("Missing 'parentId'")
        val title = args["title"] as? String ?: return@withContext ToolExecutionResult.error("Missing 'title'")
        val content = args["content"] as? String
        val escapedTitle = title.replace("\"", "\\\"")
        val contentArg = content?.let { "--content \"$it\"" } ?: ""
        val result = shellExecutor.execute("ntn pages create --parent page:$parentId --title \"$escapedTitle\" $contentArg --json 2>/dev/null")
        val r = if (result.success && result.stdout.contains("\"id\"")) {
            val pageId = Regex("\"id\"\\s*:\\s*\"([^\"]+)\"").find(result.stdout)?.groupValues?.get(1) ?: ""
            NotionPageResult(success = true, page = NotionPage(id = pageId, title = title, content = content), error = null)
        } else NotionPageResult(success = false, page = null, error = "Failed to create page: ${result.stderr}")
        ToolExecutionResult.success(r, json.encodeToString(NotionPageResult.serializer(), r))
    }

    private suspend fun diagramCreate(args: Map<String, Any?>): ToolExecutionResult = withContext(Dispatchers.IO) {
        val code = args["code"] as? String ?: return@withContext ToolExecutionResult.error("Missing 'code'")
        val format = args["format"] as? String ?: "svg"
        val filename = args["filename"] as? String ?: "diagram"
        val dir = java.io.File(context.filesDir, "diagrams"); dir.mkdirs()
        val outputFile = java.io.File(dir, "${filename}_${System.currentTimeMillis()}.$format")
        val tempFile = java.io.File(context.cacheDir, "diagram_code_${System.currentTimeMillis()}.mmd")
        tempFile.writeText(code)
        val result = shellExecutor.execute("mmdc -i ${tempFile.absolutePath} -o ${outputFile.absolutePath} -b white 2>/dev/null || npx -y @mermaid-js/mermaid-cli -i ${tempFile.absolutePath} -o ${outputFile.absolutePath} 2>/dev/null")
        tempFile.delete()
        val r = if (result.success && outputFile.exists()) DiagramResult(success = true, path = outputFile.absolutePath, format = format, error = null)
        else DiagramResult(success = false, path = null, format = format, error = "Diagram generation failed. Install mermaid-cli.")
        ToolExecutionResult.success(r, json.encodeToString(DiagramResult.serializer(), r))
    }

    private fun parsePrs(json: String): List<GitHubPr> {
        return Regex("\\{\"number\":(\\d+),\"title\":\"([^\"]+)\",\"state\":\"([^\"]+)\",\"author\":\\{\"login\":\"([^\"]+)\"\\},\"url\":\"([^\"]+)\"\\}").findAll(json).map { match ->
            GitHubPr(number = match.groupValues[1].toInt(), title = match.groupValues[2], state = match.groupValues[3], author = match.groupValues[4], url = match.groupValues[5])
        }.toList()
    }

    private fun parsePrDetails(json: String): GitHubPrDetails {
        val titleMatch = Regex("\"title\":\"([^\"]+)\"").find(json)
        val bodyMatch = Regex("\"body\":\"([^\"]*)\"").find(json)
        val authorMatch = Regex("\"author\":\\{\"login\":\"([^\"]+)\"").find(json)
        val stateMatch = Regex("\"state\":\"([^\"]+)\"").find(json)
        val urlMatch = Regex("\"url\":\"([^\"]+)\"").find(json)
        return GitHubPrDetails(number = 0, title = titleMatch?.groupValues?.get(1) ?: "", body = bodyMatch?.groupValues?.get(1), author = authorMatch?.groupValues?.get(1) ?: "", state = stateMatch?.groupValues?.get(1) ?: "", url = urlMatch?.groupValues?.get(1) ?: "", files = emptyList(), reviews = emptyList())
    }

    private fun parseIssues(json: String): List<GitHubIssue> {
        return Regex("\\{\"number\":(\\d+),\"title\":\"([^\"]+)\",\"state\":\"([^\"]+)\"").findAll(json).map { match ->
            GitHubIssue(number = match.groupValues[1].toInt(), title = match.groupValues[2], state = match.groupValues[3], labels = emptyList(), url = "")
        }.toList()
    }

    private fun parseRepoInfo(json: String): GitHubRepo {
        val nameMatch = Regex("\"name\":\"([^\"]+)\"").find(json)
        val descMatch = Regex("\"description\":\"([^\"]*)\"").find(json)
        val starsMatch = Regex("\"stargazerCount\":(\\d+)").find(json)
        val forksMatch = Regex("\"forkCount\":(\\d+)").find(json)
        val langMatch = Regex("\"primaryLanguage\":\\{\"name\":\"([^\"]+)\"").find(json)
        val urlMatch = Regex("\"url\":\"([^\"]+)\"").find(json)
        return GitHubRepo(name = nameMatch?.groupValues?.get(1) ?: "", description = descMatch?.groupValues?.get(1), stars = starsMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0, forks = forksMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0, language = langMatch?.groupValues?.get(1), url = urlMatch?.groupValues?.get(1) ?: "")
    }

    private fun parseTrelloBoards(json: String): List<TrelloBoard> = Regex("\"id\":\"([^\"]+)\",\"name\":\"([^\"]+)\"").findAll(json).map { TrelloBoard(id = it.groupValues[1], name = it.groupValues[2]) }.toList()
    private fun parseTrelloLists(json: String): List<TrelloList> = Regex("\"id\":\"([^\"]+)\",\"name\":\"([^\"]+)\"").findAll(json).map { TrelloList(id = it.groupValues[1], name = it.groupValues[2]) }.toList()
    private fun parseTrelloCards(json: String): List<TrelloCard> = Regex("\"id\":\"([^\"]+)\",\"name\":\"([^\"]+)\"").findAll(json).map { TrelloCard(id = it.groupValues[1], name = it.groupValues[2]) }.toList()
    private fun parseNotionResults(json: String): List<NotionResult> = Regex("\"id\":\"([^\"]+)\",.*\"title\":\\[\\{\"text\":\\{\"content\":\"([^\"]+)\"").findAll(json).map { NotionResult(id = it.groupValues[1], title = it.groupValues[2]) }.toList()

    private fun parseNotionPage(json: String): NotionPage {
        val idMatch = Regex("\"id\":\"([^\"]+)\"").find(json)
        val titleMatch = Regex("\"title\":\\[\\{\"text\":\\{\"content\":\"([^\"]+)\"").find(json)
        return NotionPage(id = idMatch?.groupValues?.get(1) ?: "", title = titleMatch?.groupValues?.get(1) ?: "", content = null)
    }
}