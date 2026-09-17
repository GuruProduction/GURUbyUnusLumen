package com.unuslumen.app.util.orb

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * The floating chat overlay that appears when the orb is tapped.
 * Shows a full chat interface on top of whatever app the user is in.
 * Can be dragged by the header bar. Close button collapses back to orb.
 */
class GuruOrbChatView(
    context: Context
) : FrameLayout(context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var messageListener: OnMessageListener? = null

    // UI components
    private val headerBar: LinearLayout
    private val chatScrollView: ScrollView
    private val messageContainer: LinearLayout
    private val inputField: EditText
    private val sendButton: ImageView
    private val thinkingIndicator: TextView

    // Drag state
    private var initialTouchX = 0f
    private var initialTouchY = 0f

    interface OnMessageListener {
        fun onSendMessage(text: String)
    }

    init {
        setBackgroundColor(0xE0202020.toInt()) // Semi-transparent dark background

        // Main container
        val mainContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }

        // Header bar (draggable)
        headerBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(8), dp(8))
            setBackgroundColor(0xFF7C4DFF.toInt()) // Purple
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val titleText = TextView(context).apply {
            text = "🧠 Guru"
            setTextColor(Color.WHITE)
            textSize = 18f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val closeButton = TextView(context).apply {
            text = "✕"
            setTextColor(Color.WHITE)
            textSize = 22f
            setPadding(dp(12), dp(4), dp(12), dp(4))
            setOnClickListener {
                GuruOrbService.instance?.removeChat()
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        headerBar.addView(titleText)
        headerBar.addView(closeButton)

        // Chat messages area
        messageContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }

        chatScrollView = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            addView(messageContainer)
            isSmoothScrollingEnabled = true
        }

        // Thinking indicator
        thinkingIndicator = TextView(context).apply {
            text = "🧠 Guru is thinking..."
            setTextColor(0xFF7C4DFF.toInt())
            textSize = 14f
            setPadding(dp(12), dp(4), dp(12), dp(4))
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        // Input area
        val inputContainer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        inputField = EditText(context).apply {
            hint = "Ask Guru anything..."
            setTextColor(Color.WHITE)
            setHintTextColor(0xFF888888.toInt())
            textSize = 16f
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setBackgroundColor(0xFF2A2A2A.toInt())
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            imeOptions = EditorInfo.IME_ACTION_SEND
            setOnEditorActionListener { _, actionId, event ->
                if (actionId == EditorInfo.IME_ACTION_SEND ||
                    (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)) {
                    sendMessage()
                    true
                } else false
            }
        }

        sendButton = ImageView(context).apply {
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setBackgroundColor(0xFF7C4DFF.toInt())
            setContentDescription("Send")
            setOnClickListener { sendMessage() }
            layoutParams = LinearLayout.LayoutParams(
                dp(48), dp(48)
            )
            setSendButtonDrawable()
        }

        inputContainer.addView(inputField)
        inputContainer.addView(sendButton)

        // Assemble
        mainContainer.addView(headerBar)
        mainContainer.addView(chatScrollView)
        mainContainer.addView(thinkingIndicator)
        mainContainer.addView(inputContainer)

        addView(mainContainer)

        // Observe messages
        scope.launch {
            GuruOrbService.chatMessages.collect { messages ->
                updateMessages(messages)
            }
        }

        scope.launch {
            GuruOrbService.isThinking.collect { thinking ->
                thinkingIndicator.visibility = if (thinking) View.VISIBLE else View.GONE
            }
        }
    }

    private fun sendMessage() {
        val text = inputField.text.toString().trim()
        if (text.isEmpty()) return
        inputField.setText("")
        GuruOrbService.addMessage(OrbChatMessage(content = text, isFromUser = true))
        // Trigger AI handler in background
        CoroutineScope(Dispatchers.IO).launch {
            GuruOrbService.sendToAi(text)
        }
        messageListener?.onSendMessage(text)
    }

    private fun updateMessages(messages: List<OrbChatMessage>) {
        messageContainer.removeAllViews()
        for (msg in messages) {
            val bubble = TextView(context).apply {
                text = msg.content
                textSize = 15f
                setPadding(dp(12), dp(10), dp(12), dp(10))
                maxWidth = (resources.displayMetrics.widthPixels * 0.75).toInt()

                if (msg.isFromUser) {
                    setTextColor(Color.WHITE)
                    setBackgroundColor(0xFF448AFF.toInt()) // Blue bubble
                    gravity = Gravity.END
                } else {
                    setTextColor(Color.WHITE)
                    setBackgroundColor(0xFF333333.toInt()) // Dark gray bubble
                    gravity = Gravity.START
                }

                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    gravity = if (msg.isFromUser) Gravity.END else Gravity.START
                    setMargins(dp(4), dp(2), dp(4), dp(2))
                }
            }
            messageContainer.addView(bubble)
        }

        // Auto-scroll to bottom
        chatScrollView.post {
            chatScrollView.fullScroll(ScrollView.FOCUS_DOWN)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        // Let touch events pass through to the background when touching outside the chat
        return false
    }

    fun setOnMessageListener(listener: OnMessageListener) {
        messageListener = listener
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    private fun setSendButtonDrawable() {
        // Simple text-based send button
        sendButton.setImageResource(android.R.drawable.ic_menu_send)
    }
}