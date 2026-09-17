package com.unuslumen.app.data.tools

import android.content.Context
import com.unuslumen.app.data.tools.registry.ToolExecutionResult
import com.unuslumen.app.data.tools.registry.ToolExecutor
import com.unuslumen.app.util.shell.ShellExecutor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

class FileToolExecutor(
    private val context: Context,
    private val fileAccessGuard: FileAccessGuard
) : ToolExecutor {

    private val json = Json { ignoreUnknownKeys = true }
    private val shellExecutor = ShellExecutor(context)

    override suspend fun execute(toolName: String, args: Map<String, Any?>): ToolExecutionResult {
        return when (toolName) {
            FileToolDefinitions.SEARCH_FILES -> searchFiles(args)
            FileToolDefinitions.SEARCH_IN_FILES -> searchInFiles(args)
            FileToolDefinitions.EDIT_FILE -> editFile(args)
            else -> ToolExecutionResult.error("Unknown tool: $toolName")
        }
    }

    private suspend fun searchFiles(args: Map<String, Any?>): ToolExecutionResult = withContext(Dispatchers.IO) {
        val directory = args["directory"] as? String
            ?: return@withContext ToolExecutionResult.error("Missing 'directory' parameter")
        val pattern = args["pattern"] as? String
            ?: return@withContext ToolExecutionResult.error("Missing 'pattern' parameter")

        val result = shellExecutor.execute("find \"$directory\" -name \"$pattern\" -type f 2>/dev/null | head -100")
        val res = if (result.success) {
            FileSearchResult(files = result.stdout.lines().filter { it.isNotBlank() }, error = null)
        } else {
            FileSearchResult(files = emptyList(), error = result.stderr)
        }
        ToolExecutionResult.success(res, json.encodeToString(FileSearchResult.serializer(), res))
    }

    private suspend fun searchInFiles(args: Map<String, Any?>): ToolExecutionResult = withContext(Dispatchers.IO) {
        val directory = args["directory"] as? String
            ?: return@withContext ToolExecutionResult.error("Missing 'directory' parameter")
        val pattern = args["pattern"] as? String
            ?: return@withContext ToolExecutionResult.error("Missing 'pattern' parameter")
        val fileExtension = args["fileExtension"] as? String

        val extFilter = if (fileExtension != null) " --include='*.$fileExtension'" else ""
        val command = "grep -rn$extFilter \"$pattern\" \"$directory\" 2>/dev/null | head -50"
        val result = shellExecutor.execute(command)
        val res = if (result.success || result.stdout.isNotBlank()) {
            FileSearchResult(files = result.stdout.lines().filter { it.isNotBlank() }, error = null)
        } else {
            FileSearchResult(files = emptyList(), error = result.stderr.ifBlank { "No matches found" })
        }
        ToolExecutionResult.success(res, json.encodeToString(FileSearchResult.serializer(), res))
    }

    private suspend fun editFile(args: Map<String, Any?>): ToolExecutionResult = withContext(Dispatchers.IO) {
        val path = args["path"] as? String
            ?: return@withContext ToolExecutionResult.error("Missing 'path' parameter")
        val oldContent = args["oldContent"] as? String
            ?: return@withContext ToolExecutionResult.error("Missing 'oldContent' parameter")
        val newContent = args["newContent"] as? String
            ?: return@withContext ToolExecutionResult.error("Missing 'newContent' parameter")

        if (!fileAccessGuard.wasFileRead(path)) {
            val res = FileEditResult(false, path, "BLOCKED: You must use readFile to read every line of this file before editing it. Read the file first, then edit.")
            return@withContext ToolExecutionResult.success(res, json.encodeToString(FileEditResult.serializer(), res))
        }

        try {
            val file = java.io.File(path)
            if (!file.exists()) {
                val res = FileEditResult(false, path, "File not found: $path")
                return@withContext ToolExecutionResult.success(res, json.encodeToString(FileEditResult.serializer(), res))
            }
            val content = file.readText()
            if (!content.contains(oldContent)) {
                val res = FileEditResult(false, path, "Old text not found in file. Make sure the text matches exactly.")
                return@withContext ToolExecutionResult.success(res, json.encodeToString(FileEditResult.serializer(), res))
            }
            val newFileContent = content.replace(oldContent, newContent)
            file.writeText(newFileContent)
            val res = FileEditResult(true, path, null)
            ToolExecutionResult.success(res, json.encodeToString(FileEditResult.serializer(), res))
        } catch (e: Exception) {
            val res = FileEditResult(false, path, "Edit failed: ${e.message}")
            ToolExecutionResult.success(res, json.encodeToString(FileEditResult.serializer(), res))
        }
    }
}