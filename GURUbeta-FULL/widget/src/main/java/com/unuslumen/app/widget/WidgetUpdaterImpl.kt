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