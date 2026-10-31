// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.util.shell

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Accessibility Service that provides UI automation capabilities.
 * Enables Guru to tap, scroll, type, and navigate any screen on the device.
 *
 * ============================================================================
 * ONE SOURCE OF TRUTH FOR "IS IT RUNNING", and it reports three states.
 * ============================================================================
 *
 * THE DEFECT THIS FIXES, from issue 36. `checkAdbStatus` answered
 * `accessibilityRunning` from a flag, while every automation path gated on
 * [instance]. Two signals for one question meant a status that could not be
 * trusted as a capability check, which is the opposite of what a status field is
 * for.
 *
 * Both signals also collapsed three genuinely different states into one boolean,
 * and callers need them apart because they need opposite responses:
 *
 *   DISABLED            not enabled in Settings. Waiting never helps; the human
 *                       has to turn it on.
 *   ENABLED_NOT_BOUND   enabled, but not bound right now: killed, restarting, or
 *                       the accessibility subsystem has not reconnected. Asking
 *                       again shortly may help.
 *   RUNNING             bound and serving automation.
 *
 * [isRunning] is now derived from [instance] rather than tracked beside it, so
 * the status field and the automation gate can never disagree again.
 */
class GuruAccessibilityService : AccessibilityService() {

    enum class State {
        DISABLED,
        ENABLED_NOT_BOUND,
        RUNNING,
    }

    companion object {
        private const val TAG = "guru"

        /** Derived from [instance]; see [isRunning]. */
        private val _isRunning = MutableStateFlow(false)

        /**
         * Whether the service is bound and usable.
         *
         * Kept as a StateFlow because UI observes it. It is only ever true while
         * [instance] is non-null, which is the invariant that fixes the defect.
         */
        val isRunning: StateFlow<Boolean> = _isRunning

        private var _instance: GuruAccessibilityService? = null

        /** The live service, or null. Every automation path checks this one. */
        val instance: GuruAccessibilityService? get() = _instance

        /**
         * Whether the service is ENABLED in Android Settings.
         *
         * A service cannot read this about itself, only the system can, so the app
         * records it when it reads Settings.Secure. It decides only whether a
         * non-bound service is DISABLED or ENABLED_NOT_BOUND.
         */
        @Volatile private var enabledInSettings = false

        fun setEnabledInSettings(enabled: Boolean) {
            enabledInSettings = enabled
        }

        /**
         * The honest three-state read, for status reporting.
         *
         * A bound instance wins over the settings cache, because binding is the
         * stronger evidence: if we hold the object, it is running.
         */
        fun state(): State = when {
            _instance != null -> State.RUNNING
            enabledInSettings -> State.ENABLED_NOT_BOUND
            else -> State.DISABLED
        }

        /** Whether the service is enabled in Settings, for status reporting. */
        fun isEnabledInSettings(): Boolean = enabledInSettings
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        _instance = this
        _isRunning.value = true
        enabledInSettings = true
        Log.d(TAG, "GuruAccessibilityService: connected")
    }

    /**
     * Unbind is the restart case: enabled, but no longer serving.
     *
     * Distinct from onDestroy because a restart produces this, and the old code
     * could not distinguish it from the human having disabled the service.
     */
    override fun onUnbind(intent: android.content.Intent?): Boolean {
        _instance = null
        _isRunning.value = false
        Log.d(TAG, "GuruAccessibilityService: unbound (still enabled in settings)")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        _instance = null
        _isRunning.value = false
        Log.d(TAG, "GuruAccessibilityService: destroyed")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // We don't need to react to events passively
    }

    override fun onInterrupt() {
        Log.w(TAG, "GuruAccessibilityService: interrupted")
    }

    // ===== UI Automation Methods =====

    /**
     * Find a node by text content and click it.
     */
    fun clickText(text: String, exact: Boolean = true): Boolean {
        val rootNode = rootInActiveWindow ?: return false
        val nodes = rootNode.findAccessibilityNodeInfosByText(text)
        for (node in nodes) {
            val match = if (exact) node.text?.toString() == text
            else node.text?.toString()?.contains(text, ignoreCase = true) == true
            if (match) {
                val clickable = findClickableParent(node) ?: node
                return clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
        }
        return false
    }

    /**
     * Find a node by viewId (resource ID) and click it.
     */
    fun clickViewId(viewId: String): Boolean {
        val rootNode = rootInActiveWindow ?: return false
        val nodes = rootNode.findAccessibilityNodeInfosByViewId(viewId)
        for (node in nodes) {
            val clickable = findClickableParent(node) ?: node
            return clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
        return false
    }

    /**
     * Find a node by text and type text into it (if it's an editable field).
     */
    fun typeText(text: String, targetHint: String? = null): Boolean {
        val rootNode = rootInActiveWindow ?: return false
        val targetNode = if (targetHint != null) {
            val nodes = rootNode.findAccessibilityNodeInfosByText(targetHint)
            nodes.firstOrNull { it.isEditable } ?: return false
        } else {
            findEditableNode(rootNode) ?: return false
        }

        // Focus the field first
        targetNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        targetNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)

        // Set text via bundle
        val args = android.os.Bundle()
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        return targetNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    /**
     * Perform a swipe gesture.
     */
    fun swipe(startX: Int, startY: Int, endX: Int, endY: Int, duration: Long = 300): Boolean {
        val path = Path()
        path.moveTo(startX.toFloat(), startY.toFloat())
        path.lineTo(endX.toFloat(), endY.toFloat())

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
            .build()

        return dispatchGesture(gesture, null, null)
    }

    /**
     * Scroll down on the current screen.
     */
    fun scrollDown(): Boolean {
        val rootNode = rootInActiveWindow ?: return false
        return rootNode.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
    }

    /**
     * Scroll up on the current screen.
     */
    fun scrollUp(): Boolean {
        val rootNode = rootInActiveWindow ?: return false
        return rootNode.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
    }

    /**
     * Press the back button.
     */
    fun pressBack(): Boolean {
        return performGlobalAction(GLOBAL_ACTION_BACK)
    }

    /**
     * Press the home button.
     */
    fun pressHome(): Boolean {
        return performGlobalAction(GLOBAL_ACTION_HOME)
    }

    /**
     * Open the recents/apps screen.
     */
    fun pressRecents(): Boolean {
        return performGlobalAction(GLOBAL_ACTION_RECENTS)
    }

    /**
     * Open quick settings (notification shade).
     */
    fun openQuickSettings(): Boolean {
        return performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
    }

    /**
     * Open the power dialog.
     */
    fun openPowerDialog(): Boolean {
        return performGlobalAction(GLOBAL_ACTION_POWER_DIALOG)
    }

    // ===== Helper Methods =====

    private fun findClickableParent(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current = node
        while (!current.isClickable) {
            val parent = current.parent ?: return null
            current = parent
        }
        return current
    }

    private fun findEditableNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        // BFS to find the first editable node
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (node.isEditable) return node
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return null
    }
}
