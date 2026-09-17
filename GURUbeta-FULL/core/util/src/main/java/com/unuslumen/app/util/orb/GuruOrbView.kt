package com.unuslumen.app.util.orb

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import kotlin.math.abs
import kotlin.math.sin

/**
 * A draggable floating bubble (chat head) that stays on top of all apps.
 * Tap to expand the chat overlay. Drag to reposition.
 * Snaps to the nearest screen edge when released.
 */
class GuruOrbView(
    context: Context,
    private val onTap: () -> Unit
) : View(context) {

    companion object {
        private const val ORB_SIZE_DP = 56
        private const val SNAP_ANIMATION_DURATION = 250L
        private const val TAP_THRESHOLD = 10f // pixels
    }

    private val orbSizePx = (ORB_SIZE_DP * resources.displayMetrics.density).toInt()

    // Drawing
    private val orbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0x40000000 // semi-transparent black
    }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFFFFFFFF.toInt()
        textSize = orbSizePx * 0.45f
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }

    // Drag state
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var initialX = 0
    private var initialY = 0
    private var isDragging = false

    // Thinking pulse animation
    private var pulsePhase = 0f
    private val pulseRunnable = object : Runnable {
        override fun run() {
            pulsePhase += 0.05f
            if (GuruOrbService.isThinking.value) {
                invalidate()
                postDelayed(this, 16)
            }
        }
    }

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null) // Required for shadow
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val radius = orbSizePx / 2f

        // Shadow
        canvas.drawCircle(cx, cy + 4f, radius + 2f, shadowPaint)

        // Orb gradient (gurupurple/blue)
        val gradient = RadialGradient(
            cx - radius * 0.3f, cy - radius * 0.3f, radius * 1.2f,
            0xFF7C4DFF.toInt(), // Purple
            0xFF448AFF.toInt(), // Blue
            Shader.TileMode.CLAMP
        )
        orbPaint.shader = gradient

        // Pulse effect when thinking
        val currentRadius = if (GuruOrbService.isThinking.value) {
            radius + (sin(pulsePhase) * 4f)
        } else {
            radius
        }

        canvas.drawCircle(cx, cy, currentRadius, orbPaint)

        // guruemoji / icon text
        val textY = cy - (iconPaint.descent() + iconPaint.ascent()) / 2
        canvas.drawText("🧠", cx, textY, iconPaint)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val service = GuruOrbService.instance ?: return false
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                initialTouchX = event.rawX
                initialTouchY = event.rawY
                val params = layoutParams as? WindowManager.LayoutParams
                initialX = params?.x ?: 0
                initialY = params?.y ?: 0
                isDragging = false
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - initialTouchX
                val dy = event.rawY - initialTouchY

                if (!isDragging && (abs(dx) > TAP_THRESHOLD || abs(dy) > TAP_THRESHOLD)) {
                    isDragging = true
                }

                if (isDragging) {
                    val newX = initialX + dx.toInt()
                    val newY = initialY + dy.toInt()
                    service.updateOrbPosition(newX, newY)
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                if (!isDragging) {
                    // It's a tap!
                    onTap()
                } else {
                    // Snap to nearest edge
                    snapToEdge(wm)
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun snapToEdge(wm: WindowManager) {
        val service = GuruOrbService.instance ?: return
        val params = layoutParams as? WindowManager.LayoutParams ?: return
        val screenWidth = resources.displayMetrics.widthPixels

        // Snap to left or right edge
        val snapX = if (params.x < screenWidth / 2) 0 else screenWidth - orbSizePx
        service.updateOrbPosition(snapX, params.y)
    }

    fun startPulseAnimation() {
        pulsePhase = 0f
        post(pulseRunnable)
    }

    fun stopPulseAnimation() {
        removeCallbacks(pulseRunnable)
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(orbSizePx, orbSizePx)
    }
}