package com.unuslumen.app.presentation.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.media.MediaMetadataRetriever
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipFile

/**
 * Everything the preview sheet knows about the tapped file.
 * Resolved from the cached path alone so previews work even for
 * attachments stripped from restored conversations.
 */
data class FilePreviewInfo(
    val cachedPath: String,
    val fileName: String,
    val mimeType: String,
    val sizeBytes: Long,
)

/**
 * Resolve a tapped file path into FilePreviewInfo. Reads straight off the
 * filesystem: no message lookup needed, so old conversations preview too.
 */
fun resolveFilePreviewInfo(cachedPath: String): FilePreviewInfo {
    val file = File(cachedPath)
    val fileName = file.name
    return FilePreviewInfo(
        cachedPath = cachedPath,
        fileName = fileName,
        mimeType = mimeFromExt(fileName.substringAfterLast('.', "").lowercase()),
        sizeBytes = file.length()
    )
}

/**
 * Gold file-type badge, identical logic to ChatToHtml/AttachmentCards:
 * extension first, MIME subtype second, generic FILE last.
 */
private fun badgeLabelFor(fileName: String, mimeType: String): String {
    val ext = fileName.substringAfterLast('.', "").uppercase()
    if (ext.isNotBlank() && ext.length <= 5 && ext.all { it.isLetterOrDigit() }) return ext
    val sub = mimeType.substringAfterLast('/', "").uppercase()
    if (sub.isNotBlank() && sub != "OCTET-STREAM" && sub.length <= 5) return sub
    return "FILE"
}

private fun formatSize(sizeBytes: Long): String = when {
    sizeBytes < 0 -> ""
    sizeBytes < 1024 -> "$sizeBytes B"
    sizeBytes < 1024 * 1024 -> "%.1f KB".format(sizeBytes / 1024.0)
    sizeBytes < 1024L * 1024 * 1024 -> "%.1f MB".format(sizeBytes / (1024.0 * 1024))
    else -> "%.1f GB".format(sizeBytes / (1024.0 * 1024 * 1024))
}

/** Extensions that render as readable text, the set is deliberately huge:
 *  source code, config, data, docs, shell, markup. Anything matching goes
 *  through the text reader even with a weird or missing MIME. */
private val TEXT_EXTENSIONS = setOf(
    "txt", "md", "markdown", "log", "text", "me", "rtf",
    "kt", "kts", "java", "py", "pyx", "gradle", "groovy", "scala", "clj",
    "c", "h", "cpp", "cc", "cxx", "hpp", "hxx", "cs", "m", "mm", "swift",
    "rb", "go", "rs", "php", "pl", "pm", "lua", "r", "jl", "dart", "ex", "exs",
    "js", "jsx", "ts", "tsx", "vue", "svelte", "astro",
    "html", "htm", "xhtml", "css", "scss", "sass", "less",
    "xml", "json", "json5", "jsonc", "yml", "yaml", "toml", "ini", "cfg", "conf",
    "properties", "env", "editorconfig", "gitignore", "gitattributes", "npmrc",
    "sh", "bash", "zsh", "fish", "bat", "cmd", "ps1", "psm1",
    "sql", "csv", "tsv", "psv",
    "svg", "ld", "asm", "s", "nasm", "v", "sv", "vhd",
    "tf", "tfvars", "hcl", "nix", "cmake", "makefile", "mk", "justfile",
    "lock", "srt", "vtt", "sub", "lrc", "ics", "vcf", "gpx", "obj", "stl",
    "zig", "v", "purs", "elm", "erb", "hbs", "pug", "twig", "ejs", "proto", "graphql", "gql",
    "patch", "diff", "reg", "hosts", "htaccess", "plist", "snap", "cabal", "hs",
)

/** Zip-family containers the ZipFile reader can open natively. */
private val ZIP_EXTENSIONS = setOf(
    "zip", "jar", "apk", "ipa", "war", "ear", "aar", "krz",
    "docx", "pptx", "xlsx", "odt", "ods", "odp", "epub", "xpi", "vsix", "aab",
)

/** Archives we recognise but cannot open without extra libraries. */
private val HARD_ARCHIVE_EXTENSIONS = setOf("rar", "7z", "tar", "gz", "bz2", "xz", "zst", "tar.gz", "tgz")

/** Legacy Office binaries — recognised, shown with info; text extraction is unreliable. */
private val LEGACY_OFFICE_EXTENSIONS = setOf("doc", "xls", "ppt", "dot", "xla")

private val AUDIO_EXTENSIONS = setOf("mp3", "wav", "m4a", "aac", "ogg", "oga", "opus", "flac", "wma", "amr", "mid", "midi", "aiff", "alac", "ape")

private val VIDEO_EXTENSIONS = setOf("mp4", "mov", "mkv", "avi", "webm", "wmv", "flv", "m4v", "3gp", "mpeg", "mpg", "ts", "mts")

private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "tiff", "tif", "avif", "jfif", "ico")

private fun mimeFromExt(ext: String): String = when (ext) {
    "pdf" -> "application/pdf"
    "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
    "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    "zip", "jar", "apk", "ipa", "war", "ear", "aab", "krz" -> "application/zip"
    "odt", "ods", "odp", "epub" -> "application/epub+zip"
    "csv", "tsv" -> "text/csv"
    "json", "json5", "jsonc" -> "application/json"
    "xml", "yml", "yaml" -> "application/xml"
    "js", "ts", "jsx", "tsx", "mjs", "cjs" -> "application/javascript"
    "html", "htm", "xhtml", "css", "scss" -> "text/html"
    in AUDIO_EXTENSIONS -> "audio/*"
    in VIDEO_EXTENSIONS -> "video/*"
    in IMAGE_EXTENSIONS -> "image/$ext"
    else -> "application/octet-stream"
}

/** Best-guess MIME from extension first (extensions beat broken MIMEs), then server MIME. */
private fun effectiveMime(fileName: String, mimeType: String): String {
    val ext = fileName.substringAfterLast('.', "").lowercase()
    val guess = mimeFromExt(ext)
    return if (guess != "application/octet-stream" && guess.isNotEmpty()) guess else mimeType.ifBlank { "application/octet-stream" }
}

/**
 * Result of preview extraction, rendered by FilePreviewContent.
 */
sealed interface FilePreviewResult {
    data object Loading : FilePreviewResult
    data class Text(val text: String, val truncated: Boolean) : FilePreviewResult
    data class Pages(val imagePaths: List<String>, val pageCount: Int) : FilePreviewResult
    data class Info(val lines: List<String>) : FilePreviewResult
    data class Unavailable(val reason: String) : FilePreviewResult
}

/**
 * Pulls human-readable content out of a file so the preview sheet can show
 * what's INSIDE it. Dispatch is EXTENSION-first so every imaginable file type
 * lands somewhere readable. The universal bottom rung: unknown files get a
 * content sniff, readable bytes show as text, binary bytes show as a hex
 * preview. No extension exists that renders an empty sheet.
 *
 * All heavy IO runs on Dispatchers.IO.
 */
object FilePreviewExtractor {

    private const val MAX_TEXT_CHARS = 20_000
    private const val MAX_PDF_PAGES = 5
    private const val MAX_ZIP_ENTRIES = 30
    private const val MAX_HEX_BYTES = 512

    suspend fun extract(
        context: Context,
        cachedPath: String,
        fileName: String,
        mimeType: String,
        sizeBytes: Long
    ): FilePreviewResult = withContext(Dispatchers.IO) {
        try {
            val file = File(cachedPath)
            if (!file.exists() || !file.isFile) {
                return@withContext FilePreviewResult.Unavailable("File no longer exists on this device")
            }
            val ext = fileName.substringAfterLast('.', "").lowercase()
            val mime = effectiveMime(fileName, mimeType)

            // 1. PDF
            if (ext == "pdf" || mime == "application/pdf") return@withContext renderPdf(context, file)

            // 2. Images render the actual image in the sheet
            if (ext in IMAGE_EXTENSIONS || mime.startsWith("image/", ignoreCase = true)) {
                return@withContext FilePreviewResult.Pages(
                    imagePaths = listOf(cachedPath),
                    pageCount = 1
                )
            }

            // 3. Word documents
            if (ext == "docx" || mime == "application/vnd.openxmlformats-officedocument.wordprocessingml.document") {
                return@withContext readWordDocument(file)
            }

            // 4. Slides
            if (ext == "pptx" || mime == "application/vnd.openxmlformats-officedocument.presentationml.presentation") {
                return@withContext readSlides(file)
            }

            // 5. Spreadsheets
            if (ext == "xlsx" || mime == "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet") {
                return@withContext readSpreadsheet(file)
            }

            // 6. Any zip-family container: OpenDocument, EPUB, jars, apks, plain zips
            if (ext in ZIP_EXTENSIONS || mime == "application/zip" || mime == "application/java-archive" || mime == "application/epub+zip") {
                return@withContext when {
                    ext == "odt" || ext == "ods" || ext == "odp" -> readOpenDocument(file)
                    else -> listArchive(file)
                }
            }

            // 7. Everything text-shaped: huge extension set + text/* + structured MIME
            if (ext in TEXT_EXTENSIONS ||
                mime.startsWith("text/", ignoreCase = true) ||
                mime == "application/json" || mime == "application/xml" ||
                mime == "application/javascript" || mime == "application/x-yaml" ||
                mime == "application/sql" || mime == "image/svg+xml"
            ) {
                return@withContext readTextFile(file)
            }

            // 8. Audio / video metadata
            if (ext in AUDIO_EXTENSIONS || ext in VIDEO_EXTENSIONS ||
                mime.startsWith("audio/", ignoreCase = true) || mime.startsWith("video/", ignoreCase = true)
            ) {
                return@withContext readMediaMetadata(cachedPath)
            }

            // 9. Hard archives and legacy Office: honest info card, no fake extraction
            if (ext in HARD_ARCHIVE_EXTENSIONS) {
                return@withContext FilePreviewResult.Info(
                    listOf(
                        "${ext.uppercase()} archive",
                        "File: $fileName",
                        "Size: ${formatSize(sizeBytes)}",
                        "",
                        "This archive format can't be opened on-device. Attach it in a message and ask GURU to extract it with the bundled tools."
                    )
                )
            }
            if (ext in LEGACY_OFFICE_EXTENSIONS) {
                return@withContext FilePreviewResult.Info(
                    listOf(
                        "Legacy ${ext.uppercase()} document",
                        "File: $fileName",
                        "Size: ${formatSize(sizeBytes)}",
                        "",
                        "Old Office format. Ask GURU to extract the text — the bundled unzip path won't read this one, the processFile tool will."
                    )
                )
            }

            // 10. UNIVERSAL FALLBACK: sniff the bytes. Readable text wins;
            //     binary bytes render as a hex preview with file facts.
            return@withContext readUnknownFile(file)
        } catch (e: Exception) {
            Log.w("FilePreview", "Preview failed for $cachedPath: ${e.message}")
            FilePreviewResult.Unavailable("Preview failed: ${e.message ?: "unknown error"}")
        }
    }

    private fun readTextFile(file: File): FilePreviewResult {
        val bytes = readBoundedBytes(file)
        // Binary sniff: a nul byte means "no readable text"
        if (bytes.contains(0)) return FilePreviewResult.Unavailable("Binary file with no readable text")
        val full = String(bytes, Charsets.UTF_8)
        val truncated = full.length > MAX_TEXT_CHARS
        val shown = if (truncated) full.take(MAX_TEXT_CHARS).trimEnd() + "\n\n... truncated" else full
        return FilePreviewResult.Text(shown, truncated)
    }

    /** Manual bounded read: InputStream.readNBytes needs core library
     *  desugaring on older Androids, this loop is safe everywhere. */
    private fun readBoundedBytes(file: File): ByteArray {
        val buffer = ByteArrayOutputStream()
        val chunk = ByteArray(8192)
        var read = 0
        file.inputStream().use { input ->
            while (buffer.size() <= MAX_TEXT_CHARS + 1 && input.read(chunk).also { read = it } != -1) {
                buffer.write(chunk, 0, read)
            }
        }
        return buffer.toByteArray()
    }

    /**
     * Universal bottom rung: content sniff decides between text view and a
     * hex table. Every file on the planet lands here gracefully.
     */
    private fun readUnknownFile(file: File): FilePreviewResult {
        val bytes = readBoundedBytes(file)
        if (bytes.isEmpty()) return FilePreviewResult.Info(listOf("Empty file (0 bytes)"))
        return if (bytes.contains(0)) {
            // Binary: hex + ascii table
            val sb = StringBuilder()
            sb.append("Binary file, first $MAX_HEX_BYTES bytes:\n")
            val n = minOf(bytes.size, MAX_HEX_BYTES)
            var i = 0
            while (i < n) {
                val end = minOf(i + 16, n)
                val slice = bytes.copyOfRange(i, end)
                sb.append("%08x  ".format(i))
                sb.append(slice.joinToString(" ") { "%02x".format(it) }.padEnd(16 * 3 - 1))
                sb.append("  |")
                for (b in slice) sb.append(if (b in 32..126) b.toInt().toChar() else '.')
                sb.append("|\n")
                i += 16
            }
            if (bytes.size > n) sb.append("... ${formatSize(file.length())} total")
            FilePreviewResult.Text(sb.toString(), truncated = bytes.size > MAX_HEX_BYTES)
        } else {
            readTextFile(file)
        }
    }

    private fun decodeXmlEntities(text: String): String = text
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&amp;", "&")

    /** DOCX: paragraphs live in word/document.xml inside <w:p> blocks. */
    private fun readWordDocument(file: File): FilePreviewResult {
        ZipFile(file).use { zip ->
            val entry = zip.getEntry("word/document.xml") ?: return listArchive(file)
            val xml = zip.getInputStream(entry).bufferedReader().readText()
            val paragraphs = xml.replace("</w:p>", "\n")
                .replace(Regex("<w:tab[^>]*>"), "\t")
            val extracted = decodeXmlEntities(
                Regex("<[^>]*>").replace(paragraphs, "").replace("\n{3,}", "\n\n").trim()
            )
            if (extracted.isNotBlank()) {
                return capText(extracted)
            }
        }
        // Blank document or missing document.xml — fall back to the archive listing
        return listArchive(file)
    }

    /** PPTX: each slide is ppt/slides/slideN.xml; read in numeric order. */
    private fun readSlides(file: File): FilePreviewResult {
        val sb = StringBuilder()
        var slideCount = 0
        ZipFile(file).use { zip ->
            val slideEntries = zip.entries().asSequence()
                .filter { !it.isDirectory && it.name.matches(Regex("ppt/slides/slide\\d+\\.xml")) }
                .sortedBy { entry ->
                    Regex("\\d+").find(entry.name)?.value?.toIntOrNull() ?: 0
                }
                .toList()
            slideCount = slideEntries.size
            for (entry in slideEntries) {
                val xml = zip.getInputStream(entry).bufferedReader().readText()
                val text = decodeXmlEntities(Regex("<[^>]*>").replace(xml, "")).replace("\n{3,}", "\n\n").trim()
                sb.append("Slide ${entry.name.substringAfterLast("slide").removeSuffix(".xml")}:\n")
                sb.append(text)
                sb.append("\n\n")
                if (sb.length > MAX_TEXT_CHARS) break
            }
        }
        if (sb.isBlank()) return listArchive(file)
        return capText("$slideCount slides\n\n" + sb.toString())
    }

    /** XLSX: cell text is pooled in xl/sharedStrings.xml as <si> items. */
    private fun readSpreadsheet(file: File): FilePreviewResult {
        ZipFile(file).use { zip ->
            val entry = zip.getEntry("xl/sharedStrings.xml") ?: return listArchive(file)
            val xml = zip.getInputStream(entry).bufferedReader().readText()
            val items = Regex("<si>(.*?)</si>", RegexOption.DOT_MATCHES_ALL)
                .findAll(xml)
                .mapNotNull { m ->
                    decodeXmlEntities(Regex("<[^>]*>").replace(m.groupValues[1], "")).trim().takeIf { it.isNotBlank() }
                }
                .toList()
            if (items.isEmpty()) {
                return listArchive(file)
            }
            return capText(items.joinToString("\n"))
        }
    }

    /** ODT/ODS/ODP and other OpenDocument: text lives in content.xml. */
    private fun readOpenDocument(file: File): FilePreviewResult {
        ZipFile(file).use { zip ->
            val entry = zip.getEntry("content.xml") ?: return listArchive(file)
            val xml = zip.getInputStream(entry).bufferedReader().readText()
            val paragraphs = xml.replace("</text:p>", "\n").replace("</text:h>", "\n")
            val extracted = decodeXmlEntities(
                Regex("<[^>]*>").replace(paragraphs, "").replace("\n{3,}", "\n\n").trim()
            )
            if (extracted.isNotBlank()) {
                return capText(extracted)
            }
        }
        return listArchive(file)
    }

    /** PDF: render pages to cached PNGs and return their paths. */
    private fun renderPdf(context: Context, file: File): FilePreviewResult {
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            PdfRenderer(pfd).use { r ->
                val pageCount = r.pageCount
                val outDir = File(context.cacheDir, "file_preview").apply { mkdirs() }
                val paths = mutableListOf<String>()
                val n = minOf(pageCount, MAX_PDF_PAGES)
                for (i in 0 until n) {
                    r.openPage(i).use { page ->
                        val scale = 2 // 144dpi-ish for readable phone preview
                        val bitmap = Bitmap.createBitmap(page.width * scale, page.height * scale, Bitmap.Config.ARGB_8888)
                        bitmap.eraseColor(android.graphics.Color.WHITE)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        val outFile = File(outDir, "pdf_${file.absolutePath.hashCode()}_p$i.png")
                        outFile.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 90, it) }
                        bitmap.recycle()
                        paths.add(outFile.absolutePath)
                    }
                }
                return FilePreviewResult.Pages(paths, pageCount)
            }
        }
    }

    /** ZIP listing: first N entries with their uncompressed sizes. */
    private fun listArchive(file: File): FilePreviewResult {
        val lines = mutableListOf<String>()
        var total = 0
        ZipFile(file).use { zip ->
            for (entry in zip.entries()) {
                if (entry.isDirectory) continue
                total++
                if (lines.size < MAX_ZIP_ENTRIES) {
                    lines.add("${entry.name}  (${formatSize(entry.size)})")
                }
            }
        }
        if (total > MAX_ZIP_ENTRIES) {
            lines.add("... ${total - MAX_ZIP_ENTRIES} more files inside")
        }
        return FilePreviewResult.Info(listOf("$total files inside this archive") + lines)
    }

    /** Audio/video: pull what MediaMetadataRetriever can offer. */
    private fun readMediaMetadata(path: String): FilePreviewResult {
        val retriever = android.media.MediaMetadataRetriever()
        try {
            retriever.setDataSource(path)
            fun meta(key: Int): String? =
                retriever.extractMetadata(key)?.takeIf { it.isNotBlank() }
            val lines = buildList {
                meta(MediaMetadataRetriever.METADATA_KEY_TITLE)?.let { add("Title: $it") }
                meta(MediaMetadataRetriever.METADATA_KEY_ARTIST)?.let { add("Artist: $it") }
                meta(MediaMetadataRetriever.METADATA_KEY_ALBUM)?.let { add("Album: $it") }
                meta(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.let { ms ->
                    val s = ms / 1000
                    add("Duration: ${s / 60}:${"%02d".format(s % 60)}")
                }
                meta(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)?.let { add("Format: $it") }
            }
            return if (lines.isEmpty()) {
                FilePreviewResult.Unavailable("No metadata available for this file")
            } else {
                FilePreviewResult.Info(lines)
            }
        } finally {
            retriever.release()
        }
    }

    private fun capText(text: String): FilePreviewResult {
        val truncated = text.length > MAX_TEXT_CHARS
        val shown = if (truncated) text.take(MAX_TEXT_CHARS).trimEnd() + "\n\n... truncated" else text
        return FilePreviewResult.Text(shown, truncated)
    }
}

/**
 * Preview body for the file chip sheet: gold badge header with name and
 * size, then the extracted content. Extraction state is driven by
 * FilePreviewExtractor and rendered per result type.
 */
@Composable
fun FilePreviewContent(
    context: Context,
    info: FilePreviewInfo,
) {
    var result by remember(info.cachedPath) { mutableStateOf<FilePreviewResult>(FilePreviewResult.Loading) }

    LaunchedEffect(info.cachedPath, info.mimeType, info.sizeBytes) {
        result = FilePreviewResult.Loading
        result = FilePreviewExtractor.extract(
            context = context,
            cachedPath = info.cachedPath,
            fileName = info.fileName,
            mimeType = info.mimeType,
            sizeBytes = info.sizeBytes
        )
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 8.dp)
    ) {
        // Header: gold badge + filename + size
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = badgeLabelFor(info.fileName, info.mimeType),
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                ),
                color = Color(0xFFB8860B),
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .background(Color(0x2EDAA520), RoundedCornerShape(6.dp))
                    .padding(horizontal = 6.dp, vertical = 5.dp)
            )
            Spacer(Modifier.width(8.dp))
            Column {
                Text(
                    text = info.fileName,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight(600)),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = formatSize(info.sizeBytes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }
        }

        Spacer(Modifier.size(12.dp))

        when (val r = result) {
            is FilePreviewResult.Loading -> {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }

            is FilePreviewResult.Text -> {
                Text(
                    text = r.text,
                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = if (r.text.startsWith("Binary file")) FontFamily.Monospace else FontFamily.Default),
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                        .padding(12.dp)
                )
            }

            is FilePreviewResult.Pages -> {
                Text(
                    text = if (r.pageCount == 1) "Preview" else "Page 1 of ${r.pageCount}" + if (r.pageCount > r.imagePaths.size) " (showing ${r.imagePaths.size})" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.size(8.dp))
                r.imagePaths.forEach { path ->
                    AsyncImage(
                        model = File(path),
                        contentDescription = "File preview",
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .clip(RoundedCornerShape(10.dp))
                    )
                }
            }

            is FilePreviewResult.Info -> {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    r.lines.forEach { line ->
                        Text(
                            text = line,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }

            is FilePreviewResult.Unavailable -> {
                Text(
                    text = r.reason,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 20.dp),
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}