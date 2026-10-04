// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
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
