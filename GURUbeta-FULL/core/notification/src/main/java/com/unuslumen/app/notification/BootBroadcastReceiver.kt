package com.unuslumen.app.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.unuslumen.app.alarm.repository.AlarmScheduler
import com.unuslumen.app.alarm.use_case.GetAllAlarmsUseCase
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.koin.core.qualifier.named
import kotlin.getValue

class BootBroadcastReceiver : BroadcastReceiver(), KoinComponent {

    private val getAllAlarms: GetAllAlarmsUseCase by inject()
    private val alarmScheduler: AlarmScheduler by inject()
    private val ioDispatcher: CoroutineDispatcher by inject(named("ioDispatcher"))
    private val scope = CoroutineScope(ioDispatcher)

    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED) {
            val appContext = context ?: return

            // Start Tor persistent foreground service on boot so Tor is available
            // before the app is even opened. Uses the fully qualified class name
            // because this module does not depend on portal:data.
            val torServiceIntent = Intent().apply {
                component = android.content.ComponentName(
                    appContext.packageName,
                    "com.unuslumen.app.data.tor.TorPersistentService"
                )
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                appContext.startForegroundService(torServiceIntent)
            } else {
                appContext.startService(torServiceIntent)
            }
            android.util.Log.d("BootReceiver", "Started TorPersistentService on boot")

            val pendingResult = goAsync()
            scope.launch {
                try {
                    val alarms = getAllAlarms()
                    alarms.forEach {
                        alarmScheduler.scheduleAlarm(it)
                    }
                } finally {
                    pendingResult.finish()
                }
            }
        }

    }

}