// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.tools

import android.content.Context
import com.unuslumen.app.data.tools.registry.ToolExecutionResult
import com.unuslumen.app.data.tools.registry.ToolExecutor
import com.unuslumen.app.data.tor.TorEgress
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Executes icon tool verbs. All outbound fetches ride TorEgress — Tor or
 * fail-closed, exactly like every other HTTP path in the app.
 *
 * Validation is load-bearing: an icon becomes part of the app's face, so a
 * 40MB raw photo or a disguised non-image can never land in the icon store.
 * Size cap 2MB, web image formats only, magic-byte sniffing over the
 * extension (extensions lie).
 */
class IconToolExecutor(private val context: Context) : ToolExecutor {

    private val json = Json { ignoreUnknownKeys = true }

    companion object {
        private const val ICONS_DIR = "guru_module_icons"
        private const val MAX_ICON_BYTES = 2L * 1024 * 1024 // 2MB
        /** Valid image magic prefixes (first 12 bytes) per format. */
        private val MAGIC_BYTES = listOf(
            "png" to byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47),
            "gif" to byteArrayOf(0x47, 0x49, 0x46, 0x38),
            "jpg" to byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()),
            "webp" to byteArrayOf(0x52, 0x49, 0x46, 0x46) // RIFF container; 8..12 "WEBP"
        )
        /** Filename-extension to format map; svg is special-cased text. */
        private val VALID_EXTENSIONS = setOf("png", "gif", "jpg", "jpeg", "webp", "svg")
    }

    private fun iconsDir(): File = File(context.filesDir, ICONS_DIR).apply { mkdirs() }

    private fun sanitizeName(name: String): String = name.lowercase()
        .replace(Regex("[^a-z0-9_-]"), "_")
        .replace(Regex("^_+|_+$"), "")

    /** Sniff the magic bytes: does this look like the format its bytes claim? */
    private fun detectImageFormat(bytes: ByteArray): String? {
        if (bytes.size < 12) return null
        for ((format, magic) in MAGIC_BYTES) {
            if (bytes.size >= magic.size && bytes.copyOfRange(0, magic.size).contentEquals(magic)) {
                if (format == "webp") {
                    // RIFF....WEBP — confirm the container is actually WebP
                    val kind = bytes.copyOfRange(8, 12)
                    if (kind.contentEquals(byteArrayOf(0x57, 0x45, 0x42, 0x50))) return "webp"
                    continue
                }
                return format
            }
        }
        // SVG: text-based, starts with XML or SVG markup
        val head = String(bytes.copyOfRange(0, minOf(256, bytes.size))).trim()
        if (head.startsWith("<?xml") && head.contains("<svg", ignoreCase = true) ||
            head.startsWith("<svg", ignoreCase = true)
        ) return "svg"
        return null
    }

    private fun extensionFor(url: String): String {
        val path = url.substringBefore('?').substringBefore('#')
        return path.substringAfterLast('.', "").lowercase()
    }

    override suspend fun execute(toolName: String, args: Map<String, Any?>): ToolExecutionResult = when (toolName) {
        IconToolDefinitions.DOWNLOAD_ICON -> downloadIcon(args)
        IconToolDefinitions.IMPORT_ICON_FROM_FILE -> importIcon(args)
        IconToolDefinitions.LIST_SAVED_ICONS -> listIcons()
        IconToolDefinitions.DELETE_SAVED_ICON -> deleteIcon(args)
        else -> ToolExecutionResult.error("Unknown tool: $toolName")
    }

    private suspend fun downloadIcon(args: Map<String, Any?>): ToolExecutionResult = withContext(Dispatchers.IO) {
        val url = args["url"] as? String ?: return@withContext ToolExecutionResult.error("Missing 'url'")
        val rawName = args["name"] as? String ?: return@withContext ToolExecutionResult.error("Missing 'name'")
        val name = sanitizeName(rawName)
        if (name.isBlank()) return@withContext ToolExecutionResult.error("Icon name cannot be empty")

        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return@withContext ToolExecutionResult.error("URL must be http or https")
        }

        val ext = extensionFor(url)
        if (ext.isNotEmpty() && ext !in VALID_EXTENSIONS) {
            return@withContext ToolExecutionResult.error("Unsupported format '.$ext'. Use png, jpg, gif, webp or svg.")
        }

        try {
            // TorEgress.httpClient() installs the fail-closed proxy selector:
            // Tor ready = through the circuit, Tor down = clear refusal.
            val client = TorEgress.httpClient()
            val bytes = client.use { c ->
                c.get(url).bodyAsBytes()
            }

            if (bytes.isEmpty()) return@withContext ToolExecutionResult.error("Download came back empty")
            if (bytes.size > MAX_ICON_BYTES) {
                return@withContext ToolExecutionResult.error("Icon is ${bytes.size} bytes, over the 2MB cap. A door icon should be light.")
            }

            val format = detectImageFormat(bytes)
                ?: return@withContext ToolExecutionResult.error("The downloaded bytes are not a recognised image format")
            if (ext.isNotEmpty() && format != ext.removeSuffix("jpeg")) {
                // Extension lied; trust the bytes. Warn-through: the icon still saves under its true format.
                val fileName = "$name.$format"
                File(iconsDir(), fileName).writeBytes(bytes)
                val r = DownloadIconResult(success = true, iconPath = File(iconsDir(), fileName).absolutePath, name = fileName, sizeBytes = bytes.size.toLong())
                return@withContext ToolExecutionResult.success(r, json.encodeToString(DownloadIconResult.serializer(), r))
            }

            val fileName = "$name.$format"
            File(iconsDir(), fileName).writeBytes(bytes)
            val r = DownloadIconResult(success = true, iconPath = File(iconsDir(), fileName).absolutePath, name = fileName, sizeBytes = bytes.size.toLong())
            ToolExecutionResult.success(r, json.encodeToString(DownloadIconResult.serializer(), r))
        } catch (e: Exception) {
            // Fail closed surfaces as a plain IOException from the selector;
            // both cases read honestly here.
            val r = DownloadIconResult(success = false, name = name, error = "Download failed (Tor down or bad URL): ${e.message}")
            ToolExecutionResult.error("Download failed: ${e.message}")
        }
    }

    private suspend fun importIcon(args: Map<String, Any?>): ToolExecutionResult = withContext(Dispatchers.IO) {
        val filePath = args["filePath"] as? String ?: return@withContext ToolExecutionResult.error("Missing 'filePath'")
        val rawName = args["name"] as? String ?: return@withContext ToolExecutionResult.error("Missing 'name'")
        val name = sanitizeName(rawName)
        if (name.isBlank()) return@withContext ToolExecutionResult.error("Icon name cannot be empty")

        val source = File(filePath)
        if (!source.exists() || !source.isFile) {
            return@withContext ToolExecutionResult.error("File not found: $filePath")
        }
        if (source.length() > MAX_ICON_BYTES) {
            return@withContext ToolExecutionResult.error("File is ${source.length()} bytes, over the 2MB cap")
        }

        val bytes = source.readBytes()
        val format = detectImageFormat(bytes)
            ?: return@withContext ToolExecutionResult.error("Not a recognised image format")

        val fileName = "$name.$format"
        val target = File(iconsDir(), fileName)
        target.writeBytes(bytes)
        val r = ImportIconResult(success = true, iconPath = target.absolutePath, name = fileName, sizeBytes = bytes.size.toLong())
        ToolExecutionResult.success(r, json.encodeToString(ImportIconResult.serializer(), r))
    }

    private suspend fun listIcons(): ToolExecutionResult = withContext(Dispatchers.IO) {
        val files = iconsDir().listFiles()?.filter { it.isFile } ?: emptyList()
        val icons = files.map {
            SavedIconsResult.SavedIcon(it.name, it.absolutePath, it.length())
        }
        val r = SavedIconsResult(icons)
        ToolExecutionResult.success(r, json.encodeToString(SavedIconsResult.serializer(), r))
    }

    private suspend fun deleteIcon(args: Map<String, Any?>): ToolExecutionResult = withContext(Dispatchers.IO) {
        val rawName = args["name"] as? String ?: return@withContext ToolExecutionResult.error("Missing 'name'")
        val name = sanitizeName(rawName)
        val files = iconsDir().listFiles()?.filter { it.nameWithoutExtension == name } ?: emptyList()
        var deleted = false
        for (f in files) deleted = f.delete() || deleted
        val r = DeleteIconResult(name, deleted)
        ToolExecutionResult.success(r, json.encodeToString(DeleteIconResult.serializer(), r))
    }
}