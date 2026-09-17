package com.unuslumen.app.data.tools

import com.unuslumen.app.data.tools.registry.ToolDefinition
import com.unuslumen.app.data.tools.registry.ToolExecutor
import com.unuslumen.app.data.tools.registry.ToolParameter
import com.unuslumen.app.data.tools.registry.ToolParameterType
import com.unuslumen.app.data.tools.registry.ToolResultExtractor
import com.unuslumen.app.data.tools.registry.ToolSetRegistration
import kotlin.reflect.KClass

object FileToolDefinitions : ToolSetRegistration {

    const val SEARCH_FILES = "searchFiles"
    const val SEARCH_IN_FILES = "searchInFiles"
    const val EDIT_FILE = "editFile"

    override val definitions = listOf(
        ToolDefinition(
            name = SEARCH_FILES,
            description = "Search for files by name pattern in a directory. Uses find command under the hood. Returns matching file paths.",
            category = "file",
            parameters = listOf(
                ToolParameter("directory", ToolParameterType.String, required = true, description = "The directory to search in, e.g. /sdcard/Download"),
                ToolParameter("pattern", ToolParameterType.String, required = true, description = "The file name pattern to match, e.g. '*.pdf' or 'photo*'")
            ),
            permissions = emptyList()
        ),
        ToolDefinition(
            name = SEARCH_IN_FILES,
            description = "Search for text content inside files. Uses grep under the hood. Returns matching lines with file paths and line numbers.",
            category = "file",
            parameters = listOf(
                ToolParameter("directory", ToolParameterType.String, required = true, description = "The directory to search in"),
                ToolParameter("pattern", ToolParameterType.String, required = true, description = "The text pattern to search for"),
                ToolParameter("fileExtension", ToolParameterType.String, required = false, description = "File extension filter, e.g. 'txt' or 'kt'. Null to search all files.")
            ),
            permissions = emptyList()
        ),
        ToolDefinition(
            name = EDIT_FILE,
            description = "Edit a file by replacing specific text. The old text must match exactly. Works with any path the app has permission to access. If you have All Files Access (MANAGE_EXTERNAL_STORAGE) granted, /sdcard paths work directly.",
            category = "file",
            parameters = listOf(
                ToolParameter("path", ToolParameterType.String, required = true, description = "The full path to the file to edit"),
                ToolParameter("oldContent", ToolParameterType.String, required = true, description = "The exact text to find and replace"),
                ToolParameter("newContent", ToolParameterType.String, required = true, description = "The new text to replace it with")
            ),
            permissions = emptyList()
        )
    )

    override fun executorClass(): KClass<out ToolExecutor> = FileToolExecutor::class
    override fun extractorClass(): KClass<out ToolResultExtractor>? = null
}