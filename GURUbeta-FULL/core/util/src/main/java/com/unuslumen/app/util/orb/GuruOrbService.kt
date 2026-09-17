package com.unuslumen.app.util.orb

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import com.unuslumen.app.util.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Foreground service that manages the Guru floating orb (chat head).
 * Shows a draggable bubble that expands into a chat overlay when tapped.
 * Guru is always on top — always available regardless of what app you're in.
 */
class GuruOrbService : Service() {

    companion object {
        private const val TAG = "GuruOrb"
        private const val CHANNEL_ID = "guru_orb_channel"
        private const val NOTIFICATION_ID = 2001

        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning

        private var _instance: GuruOrbService? = null
        val instance: GuruOrbService? get() = _instance

        // Chat state shared between service and UI
        private val _chatExpanded = MutableStateFlow(false)
        val chatExpanded: StateFlow<Boolean> = _chatExpanded

        private val _chatMessages = MutableStateFlow<List<OrbChatMessage>>(emptyList())
        val chatMessages: StateFlow<List<OrbChatMessage>> = _chatMessages

        private val _isThinking = MutableStateFlow(false)
        val isThinking: StateFlow<Boolean> = _isThinking

        fun startOrb(context: android.content.Context) {
            val intent = Intent(context, GuruOrbService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopOrb(context: android.content.Context) {
            val intent = Intent(context, GuruOrbService::class.java)
            context.stopService(intent)
        }

        fun addMessage(message: OrbChatMessage) {
            _chatMessages.value = _chatMessages.value + message
        }

        fun setThinking(thinking: Boolean) {
            _isThinking.value = thinking
        }

        fun toggleChat() {
            _chatExpanded.value = !_chatExpanded.value
        }

        fun collapseChat() {
            _chatExpanded.value = false
        }

        /** Callback interface for wiring the orb chat to an AI backend. Set from the app module. */
        interface OrbAiHandler {
            suspend fun onUserMessage(text: String)
        }

        private var aiHandler: OrbAiHandler? = null

        fun setAiHandler(handler: OrbAiHandler) {
            aiHandler = handler
        }
        
        /** Called by GuruOrbChatView to send a user message to the AI. */
        suspend fun sendToAi(text: String) {
            val handler = aiHandler ?: return
            setThinking(true)
            try {
                handler.onUserMessage(text)
            } catch (e: Exception) {
                addMessage(OrbChatMessage(content = "⚠️ Error: ${e.message}", isFromUser = false))
            } finally {
                setThinking(false)
            }
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var windowManager: WindowManager? = null
    private var orbView: GuruOrbView? = null
    private var chatView: GuruOrbChatView? = null

    override fun onCreate() {
        super.onCreate()
        _instance = this
        _isRunning.value = true
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())
        Log.d(TAG, "GuruOrbService: created")

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        showOrb()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        removeOrb()
        removeChat()
        _instance = null
        _isRunning.value = false
        Log.d(TAG, "GuruOrbService: destroyed")
    }

    // ===== Orb Management =====

    @SuppressLint("ClickableViewAccessibility")
    private fun showOrb() {
        if (orbView != null) return

        val wm = windowManager ?: return
        orbView = GuruOrbView(this) {
            // Tap callback — toggle chat overlay
            if (_chatExpanded.value) {
                removeChat()
                _chatExpanded.value = false
            } else {
                showChat()
                _chatExpanded.value = true
            }
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 200 // Start near top-left
        }

        wm.addView(orbView, params)
        Log.d(TAG, "Orb view added")
    }

    private fun removeOrb() {
        orbView?.let {
            try { windowManager?.removeView(it) } catch (_: Exception) { }
            orbView = null
        }
    }

    // ===== Chat Overlay Management =====

    @SuppressLint("ClickableViewAccessibility")
    private fun showChat() {
        if (chatView != null) return

        val wm = windowManager ?: return
        chatView = GuruOrbChatView(this)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
        }

        wm.addView(chatView, params)
        Log.d(TAG, "Chat overlay added")
    }

    fun removeChat() {
        chatView?.let {
            try { windowManager?.removeView(it) } catch (_: Exception) { }
            chatView = null
        }
        _chatExpanded.value = false
    }

    // ===== Update orb position from drag =====

    fun updateOrbPosition(x: Int, y: Int) {
        val view = orbView ?: return
        val wm = windowManager ?: return
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = x
            this.y = y
        }
        try {
            wm.updateViewLayout(view, params)
        } catch (_: Exception) { }
    }

    // ===== Notification =====

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Guru Floating Orb",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps Guru floating orb visible"
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
                .setContentTitle("Guru is active")
                .setContentText("Tap to open chat")
                .setSmallIcon(R.drawable.ic_guru_orb)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("Guru is active")
                .setContentText("Tap to open chat")
                .setSmallIcon(R.drawable.ic_guru_orb)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build()
        }
    }
}

/**
 * Simple data class for chat messages in the floating orb.
 */
data class OrbChatMessage(
    val id: String = java.util.UUID.randomUUID().toString(),
    val content: String,
    val isFromUser: Boolean,
    val timestamp: Long = System.currentTimeMillis()
)