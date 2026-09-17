package com.unuslumen.app.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class NoteFolder(
    val name: String,
    val id: String
)
