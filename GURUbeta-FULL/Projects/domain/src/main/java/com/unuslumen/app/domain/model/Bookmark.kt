// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class Bookmark(
    val url: String,
    val title: String = "",
    val description: String = "",
    val createdDate: Long = 0L,
    val updatedDate: Long = 0L,
    val id: String
)
