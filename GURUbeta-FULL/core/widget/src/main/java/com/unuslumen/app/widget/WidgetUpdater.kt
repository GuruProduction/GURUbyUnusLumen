// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.widget


interface WidgetUpdater {
    suspend fun updateAll(type: WidgetType)

    sealed interface WidgetType {
        data object Calendar : WidgetType
        data object Tasks : WidgetType
    }
}