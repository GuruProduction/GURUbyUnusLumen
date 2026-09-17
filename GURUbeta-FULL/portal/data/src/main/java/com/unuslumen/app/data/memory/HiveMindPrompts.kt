package com.unuslumen.app.data.memory

import com.unuslumen.app.domain.GuruReflection

object HiveMindPrompts {

    val FACT_EXTRACTION_SYSTEM_PROMPT: String
        get() = GuruReflection.factExtraction

    val FACT_EXTRACTION_PROMPT: String = ""

    val THREAD_TITLING_PROMPT: String = ""

    val THREAD_DISCOVERY_PROMPT: String
        get() = GuruReflection.threadDiscovery

    val FACT_DEDUPLICATION_PROMPT: String
        get() = GuruReflection.factDeduplication
}
