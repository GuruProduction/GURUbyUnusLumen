// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.widget

import android.content.Context
import androidx.glance.appwidget.updateAll
import com.unuslumen.app.widget.calendar.CalendarWidget
import com.unuslumen.app.widget.tasks.TasksWidget
import org.koin.core.annotation.Single

@Single
class WidgetUpdaterImpl(
    private val context: Context
): WidgetUpdater {
    override suspend fun updateAll(type: WidgetUpdater.WidgetType) {
        when (type) {
            WidgetUpdater.WidgetType.Calendar -> {
                CalendarWidget().updateAll(context)
            }
            WidgetUpdater.WidgetType.Tasks -> {
                TasksWidget().updateAll(context)
            }
        }
    }

}