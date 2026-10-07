// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.tools

import android.content.Context
import com.unuslumen.app.data.security.CredentialVault
import com.unuslumen.app.data.tor.TorManager
import com.unuslumen.app.data.tools.registry.ToolExecutionResult
import com.unuslumen.app.data.tools.registry.ToolExecutor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * ProductivityToolExecutor — Trello, Notion, and diagram tools on real REST APIs,
 * through TorEgress, credentials sourced from the Keystore-sealed CredentialVault.
 * GitHub moved out whole to GitHubToolExecutor/GitHubToolDefinitions.
 *
 * Diseases this class cures relative to its previous shape:
 *  1. Environment-variable credentials. Android never hands apps a shell
 *     environment, so System.getenv("TRELLO_API_KEY") and friends were dead on
 *     every real install. Replacement: vault slots sealed once by the
 *     trelloLogin / notionLogin credential flows, validated against the real
 *     service APIs at login time.
 *  2. Shell-outs to "ntn"/"mmdc" binaries that don't exist on stock Android
 *     (the gh CLI failure class in another dress). Notion tools now call the
 *     REST API directly. diagramCreate renders via kroki.io over Tor, an open
 *     render service with an anonymous public API, so diagram production works
 *     on any device instead of dying on a missing terminal binary.
 */
class ProductivityToolExecutor(
    private val context: Context,
    private val torManager: TorManager
) : ToolExecutor {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun execute(toolName: String, args: Map<String, Any?>): ToolExecutionResult = when (toolName) {
        ProductivityToolDefinitions.TRELLO_LOGIN -> trelloLogin(args)
        ProductivityToolDefinitions.NOTION_LOGIN -> notionLogin(args)
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

    // ─────────────────────────── HTTP over Tor ───────────────────────────

    /** Status, body, transport error. Fails closed when Tor is down, reporting so. */
    private suspend fun fetch(
        url: String,
        method: String = "GET",
        headers: Map<String, String> = emptyMap(),
        bodyContent: String? = null
    ): Triple<Int, String, String?> = withContext(Dispatchers.IO) {
        if (!torManager.isReady.value) {
            return@withContext Triple(0, "", "Tor is not running. This request never rides clearnet on GURU — bring Tor up and call again.")
        }
        var connection: HttpURLConnection? = null
        try {
            connection = URL(url).openConnection(torManager.getSocksProxy()) as HttpURLConnection
            connection.requestMethod = method
            connection.setRequestProperty("User-Agent", "GURU-by-UnusLumen/1.0")
            headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            if (!bodyContent.isNullOrBlank() && method != "GET" && method != "HEAD") {
                connection.doOutput = true
                connection.outputStream.use { it.write(bodyContent.toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            val content = if (status in 200..299) {
                connection.inputStream.bufferedReader().use { it.readText() }
            } else {
                connection.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
            }
            Triple(status, content, null as String?)
        } catch (e: Exception) {
            Triple(0, "", e.message ?: "network failure")
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * Binary fetch over Tor for image payloads. Reads raw bytes (never a text
     * Reader, which corrupts binary data), decodes errors as text separately.
     */
    private suspend fun fetchBinary(
        url: String,
        headers: Map<String, String>,
        bodyContent: String
    ): Pair<ByteArray?, String?> = withContext(Dispatchers.IO) {
        if (!torManager.isReady.value) {
            return@withContext Pair(null, "Tor is not running. This request never rides clearnet on GURU — bring Tor up and call again.")
        }
        var connection: HttpURLConnection? = null
        try {
            connection = URL(url).openConnection(torManager.getSocksProxy()) as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("User-Agent", "GURU-by-UnusLumen/1.0")
            headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            connection.doOutput = true
            connection.outputStream.use { it.write(bodyContent.toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            if (status in 200..299) {
                Pair(connection.inputStream.readBytes(), null)
            } else {
                val errorBody = connection.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                Pair(null, "HTTP $status: ${errorBody.take(300)}")
            }
        } catch (e: Exception) {
            Pair(null, e.message ?: "network failure")
        } finally {
            connection?.disconnect()
        }
    }

    // ─────────────────────────── Credential flows ───────────────────────────


    /**
     * Connect this install to Trello. Two values from trello.com/power-ups/admin:
     * the Power-Up key and a member token you generate beside it or via the
     * token URL below. Both are verified with a real Trello API call before being
     * sealed. Format sealed to the vault slot: "apiKey:token".
     */
    private suspend fun trelloLogin(args: Map<String, Any?>): ToolExecutionResult = withContext(Dispatchers.IO) {
        val apiKey = args["apiKey"] as? String ?: return@withContext ToolExecutionResult.error("Missing 'apiKey' (from trello.com/power-ups/admin)")
        val memberToken = args["token"] as? String ?: return@withContext ToolExecutionResult.error("Missing 'token' (from trello.com/power-ups/admin token page)")
        if (apiKey.isBlank() || memberToken.isBlank()) {
            return@withContext ToolExecutionResult.error("Blank apiKey or token; both arrive from trello.com/power-ups/admin.")
        }
        val (status, body, transportError) = fetch("https://api.trello.com/1/members/me?key=$apiKey&token=$memberToken")
        if (status !in 200..299) {
            val verdict = if (transportError.isNullOrBlank()) "verification refused: HTTP $status ${body.take(200)}"
                          else "verification failed: $transportError"
            return@withContext ToolExecutionResult.error("Trello rejected the apiKey/token pair ($verdict). " +
                "Copy both values fresh from the Power-Up admin page and send trelloLogin again.")
        }
        val trelloUser = try { JSONObject(body).optString("username") } catch (e: Exception) { null }
        try {
            CredentialVault.store(context, CredentialVault.SERVICE_TRELLO, "$apiKey:$memberToken")
        } catch (e: Exception) {
            return@withContext ToolExecutionResult.error("Vault sealing failed: ${e.message}")
        }
        val r = TrelloLoginResult(success = true, username = trelloUser, error = null,
            message = "Trello connected as $trelloUser. apiKey/token pair validated, sealed in hardware Keystore, survives every future app restart."
        )
        ToolExecutionResult.success(r, json.encodeToString(TrelloLoginResult.serializer(), r))
    }

    /**
     * Connect Notion. One value from notion.so/my-integrations: an internal
     * integration token. Verified with a real Notion API search call before
     * sealing. Pages still need each target wiki page's "Add connections" step
     * (Notion's own permission model), reported truthfully as a note.
     */
    private suspend fun notionLogin(args: Map<String, Any?>): ToolExecutionResult = withContext(Dispatchers.IO) {
        val token = args["token"] as? String ?: return@withContext ToolExecutionResult.error("Missing 'token' (an integration token from notion.so/my-integrations)")
        if (token.isBlank()) {
            return@withContext ToolExecutionResult.error("Blank token; generate an internal integration secret at notion.so/my-integrations.")
        }
        val headers = mapOf(
            "Authorization" to "Bearer $token",
            "Notion-Version" to "2022-06-28",
            "Content-Type" to "application/json"
        )
        val (status, body, transportError) = fetch(
            "https://api.notion.com/v1/users/me", "GET",
            headers = headers
        )
        if (status !in 200..299) {
            val verdict = if (transportError.isNullOrBlank()) "verification refused: HTTP $status ${body.take(200)}"
                          else "verification failed: $transportError"
            return@withContext ToolExecutionResult.error("Notion rejected the integration token ($verdict). " +
                "Copy the secret fresh from the integration's Secrets tab and send notionLogin again.")
        }
        val notionBotName = try { JSONObject(body).optString("name") } catch (e: Exception) { null }
        try {
            CredentialVault.store(context, CredentialVault.SERVICE_NOTION, token)
        } catch (e: Exception) {
            return@withContext ToolExecutionResult.error("Vault sealing failed: ${e.message}")
        }
        val r = NotionLoginResult(success = true, ownerName = notionBotName, error = null,
            message = "Notion connected"
        )
        ToolExecutionResult.success(r, json.encodeToString(NotionLoginResult.serializer(), r))
    }

    // ─────────────────────────── Trello tools ───────────────────────────

    /** The sealed Trello api key + token, or an honest error result when absent. */
    private fun trelloCreds(): Pair<String, String>? {
        val sealed = CredentialVault.retrieve(context, CredentialVault.SERVICE_TRELLO) ?: return null
        val key = sealed.substringBefore(":", "")
        val token = sealed.substringAfter(":", "")
        if (key.isBlank() || token.isBlank()) return null
        return key to token
    }

    private suspend fun trelloBoards(): ToolExecutionResult = withContext(Dispatchers.IO) {
        val creds = trelloCreds()
            ?: return@withContext ToolExecutionResult.error(trelloStateMessage())
        val (apiKey, memberToken) = creds
        val url = "https://api.trello.com/1/members/me/boards?key=$apiKey&token=$memberToken"
        val (status, body, transportError) = fetch(url)
        if (status !in 200..299 || transportError != null) {
            return@withContext ToolExecutionResult.error(trelloHttpFail("GET /members/me/boards", status, body, transportError))
        }
        val boards = mutableListOf<TrelloBoard>()
        try {
            val array = JSONArray(body)
            for (i in 0 until array.length()) { val item = array.getJSONObject(i)
                boards.add(TrelloBoard(id = item.getString("id"), name = item.optString("name", "")))
            }
        } catch (e: Exception) {
            return@withContext ToolExecutionResult.error("Malformed boards JSON from Trello: ${body.take(200)}")
        }
        val r = TrelloBoardsResult(success = true, boards = boards, error = null)
        ToolExecutionResult.success(r, json.encodeToString(TrelloBoardsResult.serializer(), r))
    }

    private suspend fun trelloLists(args: Map<String, Any?>): ToolExecutionResult = withContext(Dispatchers.IO) {
        val boardId = args["boardId"] as? String ?: return@withContext ToolExecutionResult.error("Missing 'boardId'")
        val creds = trelloCreds() ?: return@withContext ToolExecutionResult.error(trelloStateMessage())
        val (apiKey, memberToken) = creds
        val url = "https://api.trello.com/1/boards/$boardId/lists?key=$apiKey&token=$memberToken"
        val (status, body, transportError) = fetch(url)
        if (status !in 200..299 || transportError != null) {
            return@withContext ToolExecutionResult.error(trelloHttpFail("GET /boards/:id/lists", status, body, transportError))
        }
        val lists = mutableListOf<TrelloList>()
        try {
            val array = JSONArray(body)
            for (i in 0 until array.length()) { val item = array.getJSONObject(i)
                lists.add(TrelloList(id = item.getString("id"), name = item.optString("name", "")))
            }
        } catch (e: Exception) {
            return@withContext ToolExecutionResult.error("Malformed lists JSON from Trello: ${body.take(200)}")
        }
        val r = TrelloListsResult(success = true, lists = lists, error = null)
        ToolExecutionResult.success(r, json.encodeToString(TrelloListsResult.serializer(), r))
    }

    private suspend fun trelloCards(args: Map<String, Any?>): ToolExecutionResult = withContext(Dispatchers.IO) {
        val listId = args["listId"] as? String ?: return@withContext ToolExecutionResult.error("Missing 'listId'")
        val creds = trelloCreds() ?: return@withContext ToolExecutionResult.error(trelloStateMessage())
        val (apiKey, memberToken) = creds
        val url = "https://api.trello.com/1/lists/$listId/cards?key=$apiKey&token=$memberToken"
        val (status, body, transportError) = fetch(url)
        if (status !in 200..299 || transportError != null) {
            return@withContext ToolExecutionResult.error(trelloHttpFail("GET /lists/:id/cards", status, body, transportError))
        }
        val cards = mutableListOf<TrelloCard>()
        try {
            val array = JSONArray(body)
            for (i in 0 until array.length()) { val item = array.getJSONObject(i)
                cards.add(TrelloCard(id = item.getString("id"), name = item.optString("name", "")))
            }
        } catch (e: Exception) {
            return@withContext ToolExecutionResult.error("Malformed cards JSON from Trello: ${body.take(200)}")
        }
        val r = TrelloCardsResult(success = true, cards = cards, error = null)
        ToolExecutionResult.success(r, json.encodeToString(TrelloCardsResult.serializer(), r))
    }

    private suspend fun trelloCreateCard(args: Map<String, Any?>): ToolExecutionResult = withContext(Dispatchers.IO) {
        val listId = args["listId"] as? String ?: return@withContext ToolExecutionResult.error("Missing 'listId'")
        val name = args["name"] as? String ?: return@withContext ToolExecutionResult.error("Missing 'name'")
        val description = args["description"] as? String
        val creds = trelloCreds() ?: return@withContext ToolExecutionResult.error(trelloStateMessage())

        val urlBuilder = StringBuilder("https://api.trello.com/1/cards?key=${creds.first}&token=${creds.second}&idList=$listId")
            .append("&name=")
            .append(URLEncoder.encode(name, "UTF-8"))
        description?.takeIf { it.isNotBlank() }?.let { urlBuilder.append("&desc=").append(URLEncoder.encode(it, "UTF-8")) }

        val (status, body, transportError) = fetch(urlBuilder.toString(), "POST",
            headers = mapOf("Content-Type" to "application/x-www-form-urlencoded"))
        if (status !in 200..299 || transportError != null) {
            return@withContext ToolExecutionResult.error(trelloHttpFail("POST /cards", status, body, transportError))
        }
        val cardJson = try { JSONObject(body) } catch (e: Exception) { null }
            ?: return@withContext ToolExecutionResult.error("Trello's response was unreadable: ${body.take(200)}")
        if (cardJson.optString("id").isBlank()) {
            return@withContext ToolExecutionResult.error("Trello never gave a card id: ${body.take(200)}")
        }
        val r = TrelloCardResult(success = true, cardId = cardJson.optString("id"), error = null)
        ToolExecutionResult.success(r, json.encodeToString(TrelloCardResult.serializer(), r))
    }

    private suspend fun trelloMoveCard(args: Map<String, Any?>): ToolExecutionResult = withContext(Dispatchers.IO) {
        val cardId = args["cardId"] as? String ?: return@withContext ToolExecutionResult.error("Missing 'cardId'")
        val targetListId = args["targetListId"] as? String ?: return@withContext ToolExecutionResult.error("Missing 'targetListId'")
        val creds = trelloCreds() ?: return@withContext ToolExecutionResult.error(trelloStateMessage())
        val (apiKey, memberToken) = creds

        val url = "https://api.trello.com/1/cards/$cardId?key=$apiKey&token=$memberToken&idList=$targetListId"
        val (status, body, transportError) = fetch(url, "PUT",
            headers = mapOf("Content-Type" to "application/x-www-form-urlencoded"))
        if (status !in 200..299 || transportError != null) {
            return@withContext ToolExecutionResult.error(trelloHttpFail("PUT /cards/:id", status, body, transportError))
        }
        val r = TrelloCardResult(success = true, cardId = cardId, error = null)
        ToolExecutionResult.success(r, json.encodeToString(TrelloCardResult.serializer(), r))
    }

    private fun trelloStateMessage(): String =
        "Trello is not connected on this install. Create/get your Power-Up API key + token from " +
            "trello.com/power-ups/admin (key visible there, token created or regenerated beside it), " +
            "then run the trelloLogin tool with both values once — sealed forever afterward."

    private fun trelloHttpFail(what: String, status: Int, body: String, transportError: String?): String =
        if (status != 0) "HTTP $status from $what: ${body.take(300)}" else "$what transport failed: $transportError"

    // ─────────────────────────── Notion tools ───────────────────────────

    /** The sealed Notion token, or null — callers surface a truthful state instead. */
    private fun notionSealedToken(): String? =
        CredentialVault.retrieve(context, CredentialVault.SERVICE_NOTION)?.takeIf { it.isNotBlank() }

    /** Notion Versioned header set shared by all Notion REST paths. */
    private fun notionHeaders(authToken: String): Map<String, String> = mapOf(
        "Authorization" to "Bearer $authToken",
        "Notion-Version" to "2022-06-28",
        "Content-Type" to "application/json"
    )

    private suspend fun notionSearch(args: Map<String, Any?>): ToolExecutionResult = withContext(Dispatchers.IO) {
        val queryText = args["query"] as? String ?: return@withContext ToolExecutionResult.error("Missing 'query'")
        val limit = ((args["limit"] as? Number)?.toInt() ?: 10).coerceIn(1, 100)

        val authToken = notionSealedToken()
            ?: return@withContext ToolExecutionResult.error(
                "Notion is not connected on this install. Create an integration at notion.so/my-integrations, " +
                    "copy its Internal Integration Secret, then run the notionLogin tool once with it — the " +
                    "'Add connections' step also needs running on wiki pages you want search."
            )

        val payload = JSONObject().apply {
            put("query", queryText)
            put("page_size", limit)
        }.toString()
        val url = "https://api.notion.com/v1/search"
        val (status, body, transportError) = fetch(url, "POST", notionHeaders(authToken), payload)
        if (status !in 200..299 || transportError != null) {
            return@withContext ToolExecutionResult.error("HTTP $status from POST /search: ${if (transportError.isNullOrBlank()) body.take(300) else transportError}")
        }
        val results = mutableListOf<NotionResult>()
        try {
            val resultsArray = JSONObject(body).optJSONArray("results") ?: JSONArray()
            for (i in 0 until resultsArray.length()) {
                val pageObject = resultsArray.getJSONObject(i)
                val idField = pageObject.optString("id", "")
                val title = firstNotionTitle(pageObject)
                if (idField.isBlank() || title.isBlank()) continue
                results.add(NotionResult(id = idField, title = title))
            }
        } catch (e: Exception) {
            return@withContext ToolExecutionResult.error("Malformed /search JSON from Notion: ${body.take(200)}")
        }
        val r = NotionSearchResult(success = true, results = results, error = null)
        ToolExecutionResult.success(r, json.encodeToString(NotionSearchResult.serializer(), r))
    }

    private fun firstNotionTitle(pageJson: JSONObject): String {
        val directTitle = pageJson.optJSONObject("properties")?.optJSONObject("title")
            ?.optJSONArray("title")?.takeIf { it.length() > 0 }
            ?.getJSONObject(0)?.optString("plain_text", "")
        if (!directTitle.isNullOrBlank()) return directTitle
        val inlineUrl = pageJson.optString("url", "")
        return try { inlineUrl.substringAfterLast("/") } catch (e: Exception) { inlineUrl }
    }

    private suspend fun notionGetPage(args: Map<String, Any?>): ToolExecutionResult = withContext(Dispatchers.IO) {
        val pageId = args["pageId"] as? String ?: return@withContext ToolExecutionResult.error("Missing 'pageId'")

        val authToken = notionSealedToken()
            ?: return@withContext ToolExecutionResult.error(
                "Notion is not connected on this install. Run notionLogin once with the integration secret."
            )
        val (status, body, transportError) = fetch(
            "https://api.notion.com/v1/pages/$pageId", "GET", notionHeaders(authToken)
        )
        if (status !in 200..299 || transportError != null) {
            return@withContext ToolExecutionResult.error("HTTP $status from GET /pages/$pageId: ${if (transportError.isNullOrBlank()) body.take(300) else transportError}")
        }
        val pageJson = try { JSONObject(body) } catch (e: Exception) { null }
            ?: return@withContext ToolExecutionResult.error("Unreadable /pages reply from Notion: ${body.take(200)}")
        val pageTitle = firstNotionTitle(pageJson)
        // A /pages/{id} GET returns the metadata but full readable body text is a
        // second call into /blocks/{id}/children. Both are real production paths today.
        val (bodyStatus, bodyResponse, bodyTransportError) = fetch(
            "https://api.notion.com/v1/blocks/$pageId/children", "GET", notionHeaders(authToken)
        )
        val pageText = if (bodyStatus in 200..299 && bodyTransportError.isNullOrBlank()) {
            blockChildrenToText(bodyResponse)
        } else "" // body text block is supplementary; if it fails but the meta is there, keep the meta
        val r = NotionPageResult(
            success = true,
            page = NotionPage(id = pageJson.optString("id", pageId), title = pageTitle, content = pageText),
            error = null
        )
        ToolExecutionResult.success(r, json.encodeToString(NotionPageResult.serializer(), r))
    }

    /** Notion block children → human readable text. Paragraphs and headings only,
     *  preserving document order. Other types noted and skipped. */
    private fun blockChildrenToText(responseBody: String): String {
        val sb = StringBuilder()
        try {
            val blockArray = JSONObject(responseBody).optJSONArray("results") ?: return sb.toString()
            for (i in 0 until blockArray.length()) {
                val wrapper = blockArray.getJSONObject(i)
                val hasType = when (wrapper.optString("type", "")) { "paragraph", "heading_1", "heading_2",
                    "heading_3", "quote" -> true; else -> false }
                if (!hasType) continue
                val typeKey = wrapper.optString("type")
                val richText = wrapper.optJSONObject(typeKey)?.optJSONArray("rich_text") ?: continue
                for (j in 0 until richText.length()) {
                    val piece = richText.getJSONObject(j).optString("plain_text", "")
                    if (piece.isNotBlank()) {
                        sb.append(piece)
                        sb.append(if (typeKey.startsWith("heading")) "\n\n" else "\n")
                    }
                }
            }
        } catch (_: Exception) { }
        return sb.toString().trim()
    }

    private suspend fun notionCreatePage(args: Map<String, Any?>): ToolExecutionResult = withContext(Dispatchers.IO) {
        val parentId = args["parentId"] as? String ?: return@withContext ToolExecutionResult.error("Missing 'parentId'")
        val title = args["title"] as? String ?: return@withContext ToolExecutionResult.error("Missing 'title'")
        val contentText = args["content"] as? String

        val authToken = notionSealedToken()
            ?: return@withContext ToolExecutionResult.error(
                "Notion is not connected on this install. Run notionLogin once with the integration secret."
            )

        val payload = notionCreatePagePayload(parentId, title, contentText)
        val (status, body, transportError) = fetch(
            "https://api.notion.com/v1/pages", "POST", notionHeaders(authToken), payload
        )
        if (status !in 200..299 || transportError != null) {
            return@withContext ToolExecutionResult.error("HTTP $status from POST /pages: ${if (transportError.isNullOrBlank()) body.take(300) else transportError}")
        }
        val pageObject = try { JSONObject(body) } catch (e: Exception) { null }
            ?: return@withContext ToolExecutionResult.error("Unreadable /pages POST reply: ${body.take(200)}")
        if (pageObject.optString("id").isBlank()) {
            return@withContext ToolExecutionResult.error("Notion reply gave no id: ${body.take(200)}")
        }
        val r = NotionPageResult(
            success = true,
            page = NotionPage(id = pageObject.optString("id"), title = title, content = contentText),
            error = null
        )
        ToolExecutionResult.success(r, json.encodeToString(NotionPageResult.serializer(), r))
    }

    /** Title goes into Notion's title property, content into paragraph blocks (capped at 97 blocks total, a body limit of 100 including title conversion). */
    private fun notionCreatePagePayload(parentPageId: String, title: String, contentText: String?): String {
        // Notion's title property expects the "title" nested array form.
        val titlePropertyValue = JSONArray().put(
            JSONObject().put("type", "text").put("text", JSONObject().put("content", title))
        )
        val payload = JSONObject().apply {
            put("parent", JSONObject().put("page_id", parentPageId))
            put("properties", JSONObject().put("title", titlePropertyValue))
            contentText?.takeIf { it.isNotBlank() }?.let { fullText ->
                put("children", notionParagraphBlocks(fullText))
            }
        }
        return payload.toString()
    }

    /** Whole content → paragraph blocks. Runs full text; the call caps to ~97 blocks inside the request builder. */
    private fun notionParagraphBlocks(fullText: String): JSONArray {
        val blocks = JSONArray()
        fullText.split("\n\n").map { it.trim() }.filter { it.isNotEmpty() }.take(97).forEach { piece ->
            blocks.put(
                JSONObject().put("object", "block").put("type", "paragraph")
                    .put("paragraph", JSONObject().put("rich_text",
                        JSONArray().put(
                            JSONObject().put("type", "text")
                                .put("text", JSONObject().put("content", piece.take(2000)))
                        )
                    ))
            )
        }
        return blocks
    }

    // ─────────────────────────── Diagram tool ───────────────────────────

    /**
     * diagramCreate — real on-device diagram capability.
     * kroki.io: open diagram render service, anonymous public API, supports Mermaid
     * (SVG, PNG). The source diagram text POSTs as request body, the service
     * renders through Tor, returns rendered bytes which get written to
     * context.filesDir/diagrams. No stub, no missing-binary hack: production-grade
     * diagram support that functions on any install with Tor up.
     */
    private suspend fun diagramCreate(args: Map<String, Any?>): ToolExecutionResult = withContext(Dispatchers.IO) {
        val code = args["code"] as? String ?: return@withContext ToolExecutionResult.error("Missing 'code'")
        val diagramFormat = args["format"] as? String ?: "svg"
        val formatClean = if (diagramFormat.equals("png", ignoreCase = true)) "png" else "svg"
        val filename = (args["filename"] as? String)?.takeIf { it.isNotBlank() } ?: "diagram"

        if (code.isBlank()) return@withContext ToolExecutionResult.error("Blank mermaid code passed to diagramCreate.")
        if (!torManager.isReady.value) {
            return@withContext ToolExecutionResult.error(
                "Diagram rendering rides Tor (the render service is reached only through the Tor network). Bring Tor up and retry."
            )
        }
        val rendered = fetchBinary(
            "https://kroki.io/mermaid/$formatClean",
            headers = mapOf("Content-Type" to "text/plain"),
            bodyContent = code
        )
        val (imageBytes, renderError) = rendered
        if (imageBytes == null || imageBytes.isEmpty()) {
            return@withContext ToolExecutionResult.error("Diagram render refused: ${renderError ?: "empty response from render service"}")
        }
        val outDir = File(context.filesDir, "diagrams")
        outDir.mkdirs()
        val outputFile = File(outDir, "${filename}_${System.currentTimeMillis()}.$formatClean")
        try {
            outputFile.writeBytes(imageBytes)
        } catch (e: Exception) {
            return@withContext ToolExecutionResult.error("Could not persist rendered diagram output: ${e.message}")
        }
        if (!outputFile.exists() || outputFile.length() == 0L) {
            return@withContext ToolExecutionResult.error("Rendered output file never materialised")
        }
        val r = DiagramResult(success = true, path = outputFile.absolutePath, format = formatClean, error = null)
        ToolExecutionResult.success(r, json.encodeToString(DiagramResult.serializer(), r))
    }
}