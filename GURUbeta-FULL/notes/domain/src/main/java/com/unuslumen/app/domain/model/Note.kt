package com.unuslumen.app.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class Note(
    val title: String = "",
    val content: String = "",
    val createdDate: Long = 0L,
    val updatedDate: Long = 0L,
    val pinned: Boolean = false,
    val folderId: String? = null,
    val id: String = "",
)
