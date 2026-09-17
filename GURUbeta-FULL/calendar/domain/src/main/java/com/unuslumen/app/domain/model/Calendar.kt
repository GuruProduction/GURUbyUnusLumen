package com.unuslumen.app.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class Calendar(
    val id: Long,
    val name: String,
    val account: String,
    val color: Int,
    val included: Boolean = true
)
