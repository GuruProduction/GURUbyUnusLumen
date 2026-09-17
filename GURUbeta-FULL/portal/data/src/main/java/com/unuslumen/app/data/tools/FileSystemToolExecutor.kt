package com.unuslumen.app.data.tools

import android.content.Context
import com.unuslumen.app.data.tools.registry.ToolExecutionResult
import com.unuslumen.app.data.tools.registry.ToolExecutor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class FileSystemToolExecutor(private val context: Context) : ToolExecutor {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun execute(toolName: String, args: Map<String, Any?>): ToolExecutionResult = when (toolName) {
        FileSystemToolDefinitions.FS_FIND -> { val r = findFiles(args); ToolExecutionResult.success(r, json.encodeToString(FsSearchResult.serializer(), r)) }
        FileSystemToolDefinitions.FS_COPY -> { val r = copyFile(args); ToolExecutionResult.success(r, json.encodeToString(FsOperationResult.serializer(), r)) }
        FileSystemToolDefinitions.FS_MOVE -> { val r = moveFile(args); ToolExecutionResult.success(r, json.encodeToString(FsOperationResult.serializer(), r)) }
        FileSystemToolDefinitions.FS_DELETE -> { val r = deleteFile(args); ToolExecutionResult.success(r, json.encodeToString(FsOperationResult.serializer(), r)) }
        FileSystemToolDefinitions.FS_GET_INFO -> { val r = getFileInfo(args); ToolExecutionResult.success(r, json.encodeToString(FsInfoResult.serializer(), r)) }
        FileSystemToolDefinitions.ARCHIVE_CREATE -> { val r = createArchive(args); ToolExecutionResult.success(r, json.encodeToString(ArchiveResult.serializer(), r)) }
        FileSystemToolDefinitions.ARCHIVE_EXTRACT -> { val r = extractArchive(args); ToolExecutionResult.success(r, json.encodeToString(ArchiveResult.serializer(), r)) }
        FileSystemToolDefinitions.ARCHIVE_LIST -> { val r = listArchive(args); ToolExecutionResult.success(r, json.encodeToString(ArchiveListResult.serializer(), r)) }
        else -> ToolExecutionResult.error("Unknown tool: $toolName")
    }

    private suspend fun findFiles(args: Map<String, Any?>): FsSearchResult = withContext(Dispatchers.IO) {
        val dir = args["directory"] as? String ?: return@withContext FsSearchResult(emptyList(), "Missing 'directory'")
        val pattern = args["pattern"] as? String ?: return@withContext FsSearchResult(emptyList(), "Missing 'pattern'")
        val maxDepth = (args["maxDepth"] as? Number)?.toInt() ?: 3
        try { val results = mutableListOf<String>(); searchRecursive(File(dir), pattern, maxDepth, 0, results); FsSearchResult(results, null) }
        catch (e: Exception) { FsSearchResult(emptyList(), "Search failed: ${e.message}") }
    }

    private suspend fun copyFile(args: Map<String, Any?>): FsOperationResult = withContext(Dispatchers.IO) {
        val src = args["source"] as? String ?: return@withContext FsOperationResult(false, "Missing 'source'")
        val dst = args["destination"] as? String ?: return@withContext FsOperationResult(false, "Missing 'destination'")
        try { File(dst).parentFile?.mkdirs(); if (File(src).isDirectory) File(src).copyRecursively(File(dst), true) else File(src).copyTo(File(dst), true); FsOperationResult(true, null) }
        catch (e: Exception) { FsOperationResult(false, "Copy failed: ${e.message}") }
    }

    private suspend fun moveFile(args: Map<String, Any?>): FsOperationResult = withContext(Dispatchers.IO) {
        val src = args["source"] as? String ?: return@withContext FsOperationResult(false, "Missing 'source'")
        val dst = args["destination"] as? String ?: return@withContext FsOperationResult(false, "Missing 'destination'")
        try { File(dst).parentFile?.mkdirs(); if (!File(src).renameTo(File(dst))) { if (File(src).isDirectory) File(src).copyRecursively(File(dst), true) else File(src).copyTo(File(dst), true); File(src).deleteRecursively() }; FsOperationResult(true, null) }
        catch (e: Exception) { FsOperationResult(false, "Move failed: ${e.message}") }
    }

    private suspend fun deleteFile(args: Map<String, Any?>): FsOperationResult = withContext(Dispatchers.IO) {
        val path = args["path"] as? String ?: return@withContext FsOperationResult(false, "Missing 'path'")
        try { val f = File(path); if (!f.exists()) return@withContext FsOperationResult(false, "Not found"); val ok = if (f.isDirectory) f.deleteRecursively() else f.delete(); FsOperationResult(ok, if (ok) null else "Delete failed") }
        catch (e: Exception) { FsOperationResult(false, "Delete failed: ${e.message}") }
    }

    private suspend fun getFileInfo(args: Map<String, Any?>): FsInfoResult = withContext(Dispatchers.IO) {
        val path = args["path"] as? String ?: return@withContext FsInfoResult(false, "", error = "Missing 'path'")
        val f = File(path); if (!f.exists()) return@withContext FsInfoResult(false, path, error = "File not found")
        FsInfoResult(true, f.absolutePath, f.name, f.isDirectory, f.isFile, f.length(), f.lastModified(), f.canRead(), f.canWrite(), f.canExecute(), f.isHidden, f.parent ?: "", null)
    }

    private suspend fun createArchive(args: Map<String, Any?>): ArchiveResult = withContext(Dispatchers.IO) {
        val sourcePaths = args["sourcePaths"] as? String ?: return@withContext ArchiveResult(false, "", 0, 0, "Missing 'sourcePaths'")
        val archivePath = args["archivePath"] as? String ?: return@withContext ArchiveResult(false, "", 0, 0, "Missing 'archivePath'")
        try { val paths = sourcePaths.split(",").map { it.trim() }; ZipOutputStream(FileOutputStream(archivePath)).use { zip -> for (p in paths) { val f = File(p); if (f.isDirectory) zipDir(f, f.name, zip) else if (f.isFile) { zip.putNextEntry(ZipEntry(f.name)); FileInputStream(f).use { it.copyTo(zip) }; zip.closeEntry() } } }; val af = File(archivePath); ArchiveResult(true, archivePath, af.length(), paths.size, null) }
        catch (e: Exception) { ArchiveResult(false, archivePath, 0, 0, "Archive failed: ${e.message}") }
    }

    private suspend fun extractArchive(args: Map<String, Any?>): ArchiveResult = withContext(Dispatchers.IO) {
        val archivePath = args["archivePath"] as? String ?: return@withContext ArchiveResult(false, "", 0, 0, "Missing 'archivePath'")
        val destPath = args["destinationPath"] as? String ?: return@withContext ArchiveResult(false, "", 0, 0, "Missing 'destinationPath'")
        try { val destDir = File(destPath); destDir.mkdirs(); var count = 0; ZipInputStream(FileInputStream(archivePath)).use { zip -> var e = zip.nextEntry; while (e != null) { val out = File(destDir, e.name); if (e.isDirectory) out.mkdirs() else { out.parentFile?.mkdirs(); FileOutputStream(out).use { zip.copyTo(it) }; count++ }; zip.closeEntry(); e = zip.nextEntry } }; ArchiveResult(true, destPath, destDir.length(), count, null) }
        catch (e: Exception) { ArchiveResult(false, destPath, 0, 0, "Extract failed: ${e.message}") }
    }

    private suspend fun listArchive(args: Map<String, Any?>): ArchiveListResult = withContext(Dispatchers.IO) {
        val archivePath = args["archivePath"] as? String ?: return@withContext ArchiveListResult(emptyList(), "Missing 'archivePath'")
        try { val entries = mutableListOf<ArchiveEntry>(); ZipInputStream(FileInputStream(archivePath)).use { zip -> var e = zip.nextEntry; while (e != null) { entries.add(ArchiveEntry(e.name, e.isDirectory, e.size, e.compressedSize)); zip.closeEntry(); e = zip.nextEntry } }; ArchiveListResult(entries, null) }
        catch (e: Exception) { ArchiveListResult(emptyList(), "Error: ${e.message}") }
    }

    private fun searchRecursive(dir: File, pattern: String, maxDepth: Int, currentDepth: Int, results: MutableList<String>) {
        if (currentDepth > maxDepth) return; val files = dir.listFiles() ?: return; val regex = pattern.replace(".", "\\.").replace("*", ".*").replace("?", ".").toRegex(RegexOption.IGNORE_CASE)
        for (file in files) { if (file.isFile && regex.matches(file.name)) results.add(file.absolutePath); if (file.isDirectory) searchRecursive(file, pattern, maxDepth, currentDepth + 1, results) }
    }

    private fun zipDir(dir: File, baseName: String, zip: ZipOutputStream) {
        val files = dir.listFiles() ?: return
        for (file in files) { if (file.isDirectory) zipDir(file, "$baseName/${file.name}", zip) else { zip.putNextEntry(ZipEntry("$baseName/${file.name}")); FileInputStream(file).use { it.copyTo(zip) }; zip.closeEntry() } }
    }
}