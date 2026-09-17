package com.unuslumen.app.data.tools

import com.unuslumen.app.data.tools.registry.ToolDefinition
import com.unuslumen.app.data.tools.registry.ToolExecutor
import com.unuslumen.app.data.tools.registry.ToolParameter
import com.unuslumen.app.data.tools.registry.ToolParameterType
import com.unuslumen.app.data.tools.registry.ToolResultExtractor
import com.unuslumen.app.data.tools.registry.ToolSetRegistration
import kotlin.reflect.KClass

object FileProcessingToolDefinitions : ToolSetRegistration {

    const val PROCESS_FILE = "processFile"

    override val definitions = listOf(
        ToolDefinition(
            name = PROCESS_FILE,
            description = "Process any attached file and extract usable content. Handles images (OCR + metadata), PDFs (text extraction), documents (docx/xlsx/pptx via Python unpacking), audio (metadata), video (metadata), and plain text files. Returns extracted text, metadata, and suggested next steps.",
            category = "file_processing",
            parameters = listOf(
                ToolParameter("path", ToolParameterType.String, required = true, description = "Full path to the file to process (use the cachedPath from attached file info)")
            ),
            permissions = emptyList()
        )
    )

    override fun executorClass(): KClass<out ToolExecutor> = FileProcessingToolExecutor::class
    override fun extractorClass(): KClass<out ToolResultExtractor>? = null
}