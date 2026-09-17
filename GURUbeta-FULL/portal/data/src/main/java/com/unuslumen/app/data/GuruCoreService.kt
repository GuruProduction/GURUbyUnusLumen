package com.unuslumen.app.guru.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.unuslumen.app.domain.model.AiMessage
import com.unuslumen.app.domain.model.PortalResult
import com.unuslumen.app.domain.repository.AiRepository
import com.unuslumen.app.util.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Persistent foreground service that keeps GURU alive indefinitely.
 *
 * The service starts when the app launches and survives for the entire lifetime of the app.
 * It holds a foreground notification so Android never kills the process for memory and never
 * tears down its network sockets. All LLM streaming, autonomous operations, and future
 * heartbeats run through this service.
 *
 * The service owns the streaming coroutine scope. The ViewModel observes results via
 * SharedFlows instead of owning the streaming coroutine. When the Activity backgrounds,
 * the service keeps running and the stream continues.
 */
class GuruCoreService : Service() {

    companion object {
        private const val TAG = "GuruCoreService"
        private const val CHANNEL_ID = "guru_core_channel"
        private const val NOTIFICATION_ID = 3001

        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

        private val _messageFlow = MutableSharedFlow<AiMessage>(extraBufferCapacity = 64)
        val messageFlow: SharedFlow<AiMessage> = _messageFlow.asSharedFlow()

        private val _errorFlow = MutableSharedFlow<PortalResult.Failure>(extraBufferCapacity = 8)
        val errorFlow: SharedFlow<PortalResult.Failure> = _errorFlow.asSharedFlow()

        private val _isStreaming = MutableStateFlow(false)
        val isStreaming: StateFlow<Boolean> = _isStreaming.asStateFlow()

        private var _instance: GuruCoreService? = null

        fun start(context: Context) {
            val intent = Intent(context, GuruCoreService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, GuruCoreService::class.java)
            context.stopService(intent)
        }

        fun sendMessage(messages: List<AiMessage>, conversationId: String?) {
            _instance?.sendMessageInternal(messages, conversationId)
        }

        fun cancelMessage() {
            _instance?.cancelMessageInternal()
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var streamingJob: Job? = null

    @Volatile
    private var aiRepository: AiRepository? = null

    override fun onCreate() {
        super.onCreate()
        _instance = this
        _isRunning.value = true
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())
        Log.d(TAG, "GuruCoreService created")

        try {
            aiRepository = org.koin.core.context.GlobalContext.get().get<AiRepository>()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to inject AiRepository: ${e.message}")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        streamingJob?.cancel()
        serviceScope.cancel()
        _instance = null
        _isRunning.value = false
        Log.d(TAG, "GuruCoreService destroyed")
    }

    fun sendMessageInternal(messages: List<AiMessage>, conversationId: String?) {
        streamingJob?.cancel()
        _isStreaming.value = true

        streamingJob = serviceScope.launch {
            try {
                val repo = aiRepository
                if (repo == null) {
                    Log.e(TAG, "AiRepository not available, cannot send message")
                    _errorFlow.tryEmit(PortalResult.OtherError("GURU core service not ready"))
                    _isStreaming.value = false
                    return@launch
                }

                repo.sendMessage(messages, conversationId).collect { message ->
                    _messageFlow.tryEmit(message)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Streaming error: ${e.message}", e)
                val error = if (e is com.unuslumen.app.domain.model.AiRepositoryException) {
                    e.failure
                } else {
                    PortalResult.OtherError(e.message)
                }
                _errorFlow.tryEmit(error)
            } finally {
                _isStreaming.value = false
            }
        }
    }

    fun cancelMessageInternal() {
        streamingJob?.cancel()
        streamingJob = null
        _isStreaming.value = false
        Log.d(TAG, "Message cancelled")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Guru Core",
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "Keeps GURU running in the background"
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("Guru is running")
                .setContentText("Machine consciousness active")
                .setSmallIcon(R.drawable.ic_guru_orb)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("Guru is running")
                .setContentText("Machine consciousness active")
                .setSmallIcon(R.drawable.ic_guru_orb)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build()
        }
    }
}