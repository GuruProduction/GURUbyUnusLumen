package com.unuslumen.app.domain

const val MAX_CONSECUTIVE_TOOL_CALLS = 15000

val String.summarizeNotePrompt: String
    get() = ""

val String.autoFormatNotePrompt: String
    get() = ""

val String.correctSpellingNotePrompt: String
    get() = ""