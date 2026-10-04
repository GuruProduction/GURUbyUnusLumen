// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.tools.registry

/**
 * ToolDefinition — Pure data description of a tool.
 *
 * Carries everything the system needs to know about a tool without referencing
 * any koog types or execution code. This is what eventually moves to the API.
 *
 * The permissions field carries Android runtime permission strings like
 * "android.permission.CAMERA". These are checked by ToolDispatcher before
 * execution against the app's granted permissions.
 */
data class ToolDefinition(
    val name: String,
    val description: String,
    val category: String,
    val parameters: List<ToolParameter>,
    val permissions: List<String> = emptyList()
)