package com.unuslumen.app.domain

object GuruReflection {

    val factExtraction: String = ""

    val threadDiscovery: String = ""

    val factDeduplication: String = ""

    val conversationTitling: String = ""

    val fullReflection: String
        get() = """
$factExtraction

---

$threadDiscovery

---

$factDeduplication

---

$conversationTitling
""".trimIndent()
}
