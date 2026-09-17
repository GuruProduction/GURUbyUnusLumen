package com.unuslumen.app.guru.data.repository

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.AlarmManagerCompat
import com.unuslumen.app.alarm.model.Alarm
import com.unuslumen.app.alarm.repository.AlarmScheduler
import com.unuslumen.app.util.Constants
import org.koin.core.annotation.Factory

@Factory(binds = [AlarmScheduler::class])
class AlarmSchedulerImpl(
    private val context: Context
): AlarmScheduler {

    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    private val alarmReceiverClass: Class<*> by lazy {
        Class.forName("com.unuslumen.app.notification.AlarmReceiver")
    }

    override fun scheduleAlarm(alarm: Alarm) {
        val intent = Intent(context, alarmReceiverClass)
        intent.putExtra(Constants.ALARM_ID_EXTRA, alarm.id)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            alarm.id,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        AlarmManagerCompat.setExactAndAllowWhileIdle(alarmManager, AlarmManager.RTC_WAKEUP, alarm.time, pendingIntent)

    }

    override fun cancelAlarm(schedulerId: Int) {
        val intent = Intent(context, alarmReceiverClass)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            schedulerId,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        alarmManager.cancel(pendingIntent)
    }

    override fun canScheduleExactAlarms(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()
    }
}

