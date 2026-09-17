package com.unuslumen.app.domain

object GuruContext {

    val usingContext: String = ""

    val continuity: String = ""

    val contextPreambleFormat: String = ""

    val fullContext: String
        get() = """
$usingContext

$continuity

$contextPreambleFormat
""".trimIndent()
}
