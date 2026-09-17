package com.unuslumen.app.data.tools

import com.unuslumen.app.data.tools.registry.ToolDefinition
import com.unuslumen.app.data.tools.registry.ToolExecutor
import com.unuslumen.app.data.tools.registry.ToolParameter
import com.unuslumen.app.data.tools.registry.ToolParameterType
import com.unuslumen.app.data.tools.registry.ToolResultExtractor
import com.unuslumen.app.data.tools.registry.ToolSetRegistration
import kotlin.reflect.KClass

object FileSystemToolDefinitions : ToolSetRegistration {
    const val FS_FIND = "fsFindFiles"
    const val FS_COPY = "fsCopyFile"
    const val FS_MOVE = "fsMoveFile"
    const val FS_DELETE = "fsDeleteFile"
    const val FS_GET_INFO = "fsGetFileInfo"
    const val ARCHIVE_CREATE = "createArchive"
    const val ARCHIVE_EXTRACT = "extractArchive"
    const val ARCHIVE_LIST = "listArchive"

    override val definitions = listOf(
        ToolDefinition(name = FS_FIND, description = "Search for files by name pattern in a directory tree. Returns matching file paths.", category = "filesystem", parameters = listOf(ToolParameter("directory", ToolParameterType.String, true, "Directory to search in"), ToolParameter("pattern", ToolParameterType.String, true, "File name pattern, e.g. '*.pdf'"), ToolParameter("maxDepth", ToolParameterType.Integer, false, "Maximum depth to search, default 3")), permissions = emptyList()),
        ToolDefinition(name = FS_COPY, description = "Copy a file or directory to a new location.", category = "filesystem", parameters = listOf(ToolParameter("source", ToolParameterType.String, true, "Source file or directory path"), ToolParameter("destination", ToolParameterType.String, true, "Destination path")), permissions = emptyList()),
        ToolDefinition(name = FS_MOVE, description = "Move a file or directory to a new location.", category = "filesystem", parameters = listOf(ToolParameter("source", ToolParameterType.String, true, "Source path"), ToolParameter("destination", ToolParameterType.String, true, "Destination path")), permissions = emptyList()),
        ToolDefinition(name = FS_DELETE, description = "Delete a file or directory. Use with caution — this is permanent.", category = "filesystem", parameters = listOf(ToolParameter("path", ToolParameterType.String, true, "Path to delete")), permissions = emptyList()),
        ToolDefinition(name = FS_GET_INFO, description = "Get detailed information about a file or directory: size, permissions, modification time, type.", category = "filesystem", parameters = listOf(ToolParameter("path", ToolParameterType.String, true, "Path")), permissions = emptyList()),
        ToolDefinition(name = ARCHIVE_CREATE, description = "Create a zip archive from files or directories.", category = "filesystem", parameters = listOf(ToolParameter("sourcePaths", ToolParameterType.String, true, "Comma-separated paths"), ToolParameter("archivePath", ToolParameterType.String, true, "Output path")), permissions = emptyList()),
        ToolDefinition(name = ARCHIVE_EXTRACT, description = "Extract a zip archive to a directory.", category = "filesystem", parameters = listOf(ToolParameter("archivePath", ToolParameterType.String, true, "Archive path"), ToolParameter("destinationPath", ToolParameterType.String, true, "Extract directory")), permissions = emptyList()),
        ToolDefinition(name = ARCHIVE_LIST, description = "List the contents of a zip archive without extracting.", category = "filesystem", parameters = listOf(ToolParameter("archivePath", ToolParameterType.String, true, "Archive path")), permissions = emptyList())
    )
    override fun executorClass(): KClass<out ToolExecutor> = FileSystemToolExecutor::class
    override fun extractorClass(): KClass<out ToolResultExtractor>? = null
}