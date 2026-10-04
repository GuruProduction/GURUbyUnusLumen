// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Represents a hook defined by Guru.
 * Hooks are event-triggered actions that run before or after certain operations.
 */
@Entity(
    tableName = "guru_hooks",
    indices = [
        Index(value = ["name"], name = "index_guru_hooks_name"),
        Index(value = ["eventType"], name = "index_guru_hooks_eventType"),
        Index(value = ["enabled"], name = "index_guru_hooks_enabled")
    ]
)
data class GuruHookEntity(
    @PrimaryKey
    val id: String,
    val name: String,
    val displayName: String,
    val description: String,
    val eventType: String,
    val triggerTiming: String, // BEFORE, AFTER
    val condition: String?, // JSON condition expression
    val action: String, // JSON action definition
    val priority: Int, // Execution order (lower = higher priority)
    @ColumnInfo(defaultValue = "1")
    val enabled: Boolean = true,
    val createdAt: Long,
    val lastTriggeredAt: Long? = null,
    @ColumnInfo(defaultValue = "0")
    val triggerCount: Int = 0
)