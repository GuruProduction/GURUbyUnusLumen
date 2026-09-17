package com.unuslumen.app.data.tools

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import com.unuslumen.app.util.shell.GuruAccessibilityService
import com.unuslumen.app.util.service.ScreenCaptureService
import com.unuslumen.app.data.tools.registry.ToolExecutionResult
import com.unuslumen.app.data.tools.registry.ToolExecutor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

class DeviceControlToolExecutor(private val context: Context) : ToolExecutor {
    private val json = Json { ignoreUnknownKeys = true }

    companion object {
        private const val TAG = "guru"
        private const val SCREENSHOT_DIR = "screenshots"
        private var mediaProjection: MediaProjection? = null
        private var virtualDisplay: VirtualDisplay? = null
        private var imageReader: ImageReader? = null
        /** True when mediaProjection came from ScreenCaptureService (Android 14+ path). */
        private var projectionFromService = false
        /** Sentinel is Int.MIN_VALUE — RESULT_OK is -1 and must never look "unset". */
        var screenCaptureResultCode: Int = ScreenCaptureService.RESULT_CODE_UNSET
        var screenCaptureData: Intent? = null

        /** Access for ScreenSightController's pre-Android-14 projection reuse. */
        fun getLiveProjection(): MediaProjection? = if (projectionFromService) null else mediaProjection
        fun setLiveProjection(value: MediaProjection?) {
            mediaProjection = value
            projectionFromService = value != null
        }
    }

    private fun accessibilityNotRunningError(): String =
        "Accessibility Service not running. To enable: Settings > Accessibility > Installed Apps > guru > Turn ON. Then try again."

    override suspend fun execute(toolName: String, args: Map<String, Any?>): ToolExecutionResult = when (toolName) {
        DeviceControlToolDefinitions.TAP_SCREEN -> tapScreen(args)
        DeviceControlToolDefinitions.CLICK_TEXT -> clickText(args)
        DeviceControlToolDefinitions.SWIPE -> swipe(args)
        DeviceControlToolDefinitions.SCROLL -> scroll(args)
        DeviceControlToolDefinitions.TYPE_TEXT -> typeText(args)
        DeviceControlToolDefinitions.PRESS_BUTTON -> pressButton(args)
        DeviceControlToolDefinitions.OPEN_APP -> openApp(args)
        DeviceControlToolDefinitions.GET_SCREEN_STATE -> getScreenState()
        DeviceControlToolDefinitions.FIND_ELEMENTS -> findElements(args)
        DeviceControlToolDefinitions.CAPTURE_SCREEN -> captureScreen(args)
        DeviceControlToolDefinitions.REQUEST_SCREEN_CAPTURE_PERMISSION -> requestScreenCapturePermission()
        DeviceControlToolDefinitions.AUTO_PAIR_ADB -> autoPairAdb()
        else -> ToolExecutionResult.error("Unknown tool: $toolName")
    }

    private suspend fun tapScreen(args: Map<String, Any?>): ToolExecutionResult = withContext(Dispatchers.Main) {
        val x = (args["x"] as? Number)?.toInt() ?: return@withContext ToolExecutionResult.error("Missing 'x'")
        val y = (args["y"] as? Number)?.toInt() ?: return@withContext ToolExecutionResult.error("Missing 'y'")
        val service = GuruAccessibilityService.instance ?: return@withContext run {
            val r = TapResult(success = false, error = accessibilityNotRunningError())
            ToolExecutionResult.success(r, json.encodeToString(TapResult.serializer(), r))
        }
        try {
            val path = android.graphics.Path()
            path.moveTo(x.toFloat(), y.toFloat())
            val gesture = android.accessibilityservice.GestureDescription.Builder()
                .addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 50))
                .build()
            val result = service.dispatchGesture(gesture, null, null)
            val r = TapResult(success = result, error = if (!result) "Gesture cancelled" else null)
            ToolExecutionResult.success(r, json.encodeToString(TapResult.serializer(), r))
        } catch (e: Exception) {
            val r = TapResult(success = false, error = "Tap failed: ${e.message}")
            ToolExecutionResult.success(r, json.encodeToString(TapResult.serializer(), r))
        }
    }

    private suspend fun clickText(args: Map<String, Any?>): ToolExecutionResult = withContext(Dispatchers.Main) {
        val text = args["text"] as? String ?: return@withContext ToolExecutionResult.error("Missing 'text'")
        val exact = args["exact"] as? Boolean ?: true
        val service = GuruAccessibilityService.instance ?: return@withContext run {
            val r = ClickTextResult(success = false, error = accessibilityNotRunningError())
            ToolExecutionResult.success(r, json.encodeToString(ClickTextResult.serializer(), r))
        }
        val result = service.clickText(text, exact)
        val r = ClickTextResult(success = result, error = if (!result) "Text '$text' not found or not clickable" else null)
        ToolExecutionResult.success(r, json.encodeToString(ClickTextResult.serializer(), r))
    }

    private suspend fun swipe(args: Map<String, Any?>): ToolExecutionResult = withContext(Dispatchers.Main) {
        val startX = (args["startX"] as? Number)?.toInt() ?: return@withContext ToolExecutionResult.error("Missing 'startX'")
        val startY = (args["startY"] as? Number)?.toInt() ?: return@withContext ToolExecutionResult.error("Missing 'startY'")
        val endX = (args["endX"] as? Number)?.toInt() ?: return@withContext ToolExecutionResult.error("Missing 'endX'")
        val endY = (args["endY"] as? Number)?.toInt() ?: return@withContext ToolExecutionResult.error("Missing 'endY'")
        val durationMs = (args["durationMs"] as? Number)?.toLong() ?: 300L
        val service = GuruAccessibilityService.instance ?: return@withContext run {
            val r = SwipeResult(success = false, error = accessibilityNotRunningError())
            ToolExecutionResult.success(r, json.encodeToString(SwipeResult.serializer(), r))
        }
        val result = service.swipe(startX, startY, endX, endY, durationMs)
        val r = SwipeResult(success = result, error = if (!result) "Swipe gesture failed" else null)
        ToolExecutionResult.success(r, json.encodeToString(SwipeResult.serializer(), r))
    }

    private suspend fun scroll(args: Map<String, Any?>): ToolExecutionResult = withContext(Dispatchers.Main) {
        val direction = args["direction"] as? String ?: return@withContext ToolExecutionResult.error("Missing 'direction'")
        val service = GuruAccessibilityService.instance ?: return@withContext run {
            val r = ScrollResult(success = false, error = accessibilityNotRunningError())
            ToolExecutionResult.success(r, json.encodeToString(ScrollResult.serializer(), r))
        }
        val result = when (direction.lowercase()) {
            "down" -> service.scrollDown()
            "up" -> service.scrollUp()
            else -> return@withContext run {
                val r = ScrollResult(success = false, error = "Invalid direction: $direction. Use 'up' or 'down'.")
                ToolExecutionResult.success(r, json.encodeToString(ScrollResult.serializer(), r))
            }
        }
        val r = ScrollResult(success = result, error = if (!result) "Scroll failed" else null)
        ToolExecutionResult.success(r, json.encodeToString(ScrollResult.serializer(), r))
    }

    private suspend fun typeText(args: Map<String, Any?>): ToolExecutionResult = withContext(Dispatchers.Main) {
        val text = args["text"] as? String ?: return@withContext ToolExecutionResult.error("Missing 'text'")
        val service = GuruAccessibilityService.instance ?: return@withContext run {
            val r = TypeTextResult(success = false, error = accessibilityNotRunningError())
            ToolExecutionResult.success(r, json.encodeToString(TypeTextResult.serializer(), r))
        }
        val result = service.typeText(text)
        val r = TypeTextResult(success = result, error = if (!result) "Failed to type text. Make sure a text field is focused." else null)
        ToolExecutionResult.success(r, json.encodeToString(TypeTextResult.serializer(), r))
    }

    private suspend fun pressButton(args: Map<String, Any?>): ToolExecutionResult = withContext(Dispatchers.Main) {
        val button = args["button"] as? String ?: return@withContext ToolExecutionResult.error("Missing 'button'")
        val service = GuruAccessibilityService.instance ?: return@withContext run {
            val r = ButtonResult(success = false, error = accessibilityNotRunningError())
            ToolExecutionResult.success(r, json.encodeToString(ButtonResult.serializer(), r))
        }
        val result = when (button.lowercase()) {
            "back" -> service.pressBack()
            "home" -> service.pressHome()
            "recents" -> service.pressRecents()
            "power" -> service.openPowerDialog()
            "quick_settings" -> service.openQuickSettings()
            else -> return@withContext run {
                val r = ButtonResult(success = false, error = "Unknown button: $button. Use: back, home, recents, power, quick_settings")
                ToolExecutionResult.success(r, json.encodeToString(ButtonResult.serializer(), r))
            }
        }
        val r = ButtonResult(success = result, error = if (!result) "Button press failed" else null)
        ToolExecutionResult.success(r, json.encodeToString(ButtonResult.serializer(), r))
    }

    private suspend fun openApp(args: Map<String, Any?>): ToolExecutionResult = withContext(Dispatchers.Main) {
        val packageName = args["packageName"] as? String ?: return@withContext ToolExecutionResult.error("Missing 'packageName'")
        try {
            val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            if (intent == null) {
                val r = OpenAppResult(success = false, error = "App not found: $packageName")
                return@withContext ToolExecutionResult.success(r, json.encodeToString(OpenAppResult.serializer(), r))
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            val r = OpenAppResult(success = true, error = null)
            ToolExecutionResult.success(r, json.encodeToString(OpenAppResult.serializer(), r))
        } catch (e: Exception) {
            val r = OpenAppResult(success = false, error = "Failed to open app: ${e.message}")
            ToolExecutionResult.success(r, json.encodeToString(OpenAppResult.serializer(), r))
        }
    }

    private suspend fun getScreenState(): ToolExecutionResult = withContext(Dispatchers.Main) {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            metrics.widthPixels = bounds.width()
            metrics.heightPixels = bounds.height()
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getMetrics(metrics)
        }
        val service = GuruAccessibilityService.instance
        val rootNode = service?.rootInActiveWindow
        // On Android 11+ we build metrics from window bounds, which leaves density at
        // 0.0 on a fresh DisplayMetrics. Pull the real density from resources, which is
        // the same fallback captureScreenInternal already uses.
        val density = if (metrics.density > 0f) metrics.density else context.resources.displayMetrics.density
        val r = ScreenStateResult(
            screenWidth = metrics.widthPixels,
            screenHeight = metrics.heightPixels,
            density = density,
            accessibilityRunning = service != null,
            currentPackage = rootNode?.packageName?.toString(),
            currentClass = rootNode?.className?.toString()
        )
        ToolExecutionResult.success(r, json.encodeToString(ScreenStateResult.serializer(), r))
    }

    private suspend fun findElements(args: Map<String, Any?>): ToolExecutionResult = withContext(Dispatchers.Main) {
        val textFilter = args["textFilter"] as? String
        val service = GuruAccessibilityService.instance ?: return@withContext run {
            val r = FindElementsResult(elements = emptyList(), error = accessibilityNotRunningError())
            ToolExecutionResult.success(r, json.encodeToString(FindElementsResult.serializer(), r))
        }
        val rootNode = service.rootInActiveWindow
        if (rootNode == null) {
            val r = FindElementsResult(elements = emptyList(), error = "No active window")
            return@withContext ToolExecutionResult.success(r, json.encodeToString(FindElementsResult.serializer(), r))
        }
        val elements = mutableListOf<ElementInfo>()
        findClickableElements(rootNode, elements, textFilter)
        val r = FindElementsResult(elements = elements, error = null)
        ToolExecutionResult.success(r, json.encodeToString(FindElementsResult.serializer(), r))
    }

    @Suppress("DEPRECATION")
    private fun findClickableElements(node: AccessibilityNodeInfo, results: MutableList<ElementInfo>, textFilter: String?) {
        val text = node.text?.toString()
        val contentDesc = node.contentDescription?.toString()
        val viewId = node.viewIdResourceName
        val matchesFilter = textFilter == null ||
            text?.contains(textFilter, ignoreCase = true) == true ||
            contentDesc?.contains(textFilter, ignoreCase = true) == true ||
            viewId?.contains(textFilter, ignoreCase = true) == true
        if ((node.isClickable || node.isCheckable || node.isFocusable) && matchesFilter) {
            val bounds = android.graphics.Rect()
            node.getBoundsInScreen(bounds)
            results.add(ElementInfo(
                text = text, contentDescription = contentDesc, viewId = viewId,
                clickable = node.isClickable, checkable = node.isCheckable, checked = node.isChecked, enabled = node.isEnabled,
                boundsLeft = bounds.left, boundsTop = bounds.top, boundsRight = bounds.right, boundsBottom = bounds.bottom,
                centerX = bounds.centerX(), centerY = bounds.centerY()
            ))
        }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { findClickableElements(it, results, textFilter) }
        }
    }

    private suspend fun captureScreen(args: Map<String, Any?>): ToolExecutionResult = withContext(Dispatchers.IO) {
        val filename = args["filename"] as? String ?: "screenshot"
        try {
            if (screenCaptureData == null) {
                val r = ScreenCaptureResult(success = false, error = "Screen capture permission not granted. Use requestScreenCapturePermission tool first.", filePath = null)
                return@withContext ToolExecutionResult.success(r, json.encodeToString(ScreenCaptureResult.serializer(), r))
            }
            // Ensure a live projection. All of this must stay off the main thread:
            // startForegroundService from background throws
            // ForegroundServiceStartNotAllowedException (which used to crash the whole
            // app), and getMediaProjection throws SecurityException when the consent
            // token has already been consumed by a dead projection.
            val projectionReady = ensureProjection()
            if (!projectionReady) {
                val r = ScreenCaptureResult(
                    success = false,
                    error = "Screen capture session is not active. Screen consent needs re-granting: ask your human to run requestScreenCapturePermission and allow the dialog, then try again.",
                    filePath = null
                )
                // The token is stale or the service refused to start. Clear the dead
                // statics so the next consent round-trip starts clean.
                invalidateProjection()
                return@withContext ToolExecutionResult.success(r, json.encodeToString(ScreenCaptureResult.serializer(), r))
            }
            val screenshot = captureScreenInternal()
                ?: return@withContext run {
                    val r = ScreenCaptureResult(success = false, error = "Failed to capture screen frame.", filePath = null)
                    ToolExecutionResult.success(r, json.encodeToString(ScreenCaptureResult.serializer(), r))
                }
            val dir = File(context.filesDir, SCREENSHOT_DIR)
            dir.mkdirs()
            val file = File(dir, "${filename}_${System.currentTimeMillis()}.png")
            val outputStream = java.io.FileOutputStream(file)
            screenshot.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
            outputStream.close()
            screenshot.recycle()
            val r = ScreenCaptureResult(success = true, error = null, filePath = file.absolutePath)
            ToolExecutionResult.success(r, json.encodeToString(ScreenCaptureResult.serializer(), r))
        } catch (e: Exception) {
            Log.e(TAG, "captureScreen failed", e)
            val r = ScreenCaptureResult(success = false, error = "Screen capture failed: ${e.message}", filePath = null)
            ToolExecutionResult.success(r, json.encodeToString(ScreenCaptureResult.serializer(), r))
        }
    }

    /**
     * Make sure a usable MediaProjection exists, starting the foreground service when
     * needed. Returns false (instead of throwing/crashing) when the session cannot be
     * established. Bounded wait — never an infinite busy-loop.
     */
    private suspend fun ensureProjection(): Boolean = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            if (ScreenCaptureService.mediaProjection != null) {
                mediaProjection = ScreenCaptureService.mediaProjection
                projectionFromService = true
                return@withContext true
            }
            try {
                val serviceIntent = Intent(context, ScreenCaptureService::class.java).apply {
                    action = ScreenCaptureService.ACTION_START
                    putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, screenCaptureResultCode)
                    putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, screenCaptureData)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
            } catch (e: Exception) {
                // ForegroundServiceStartNotAllowedException or similar — the app is in
                // the background and Android 14+ blocks FGS start. Do not crash.
                Log.e(TAG, "ensureProjection: failed to start ScreenCaptureService: ${e.message}")
                return@withContext false
            }
            // Bounded wait for the service to create its projection
            var waited = 0
            while (ScreenCaptureService.mediaProjection == null && waited < 3000) {
                Thread.sleep(50)
                waited += 50
            }
            val projection = ScreenCaptureService.mediaProjection
            mediaProjection = projection
            projectionFromService = true
            projection != null
        } else {
            if (mediaProjection == null) {
                try {
                    val manager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                    mediaProjection = manager.getMediaProjection(screenCaptureResultCode, screenCaptureData!!)
                    projectionFromService = false
                } catch (e: Exception) {
                    // SecurityException — consent token already consumed by a dead projection
                    Log.e(TAG, "ensureProjection: token reuse failed: ${e.message}")
                    return@withContext false
                }
            }
            mediaProjection != null
        }
    }

    /** Tear down dead projection state so a fresh consent flow can start clean. */
    private fun invalidateProjection() {
        try {
            virtualDisplay?.release()
        } catch (_: Exception) {}
        virtualDisplay = null
        try {
            imageReader?.close()
        } catch (_: Exception) {}
        imageReader = null
        try {
            mediaProjection?.stop()
        } catch (_: Exception) {}
        mediaProjection = null
        projectionFromService = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && ScreenCaptureService.isRunning) {
            val stopIntent = Intent(context, ScreenCaptureService::class.java).apply {
                action = ScreenCaptureService.ACTION_STOP
            }
            try { context.startService(stopIntent) } catch (_: Exception) {}
        }
        screenCaptureData = null
        screenCaptureResultCode = ScreenCaptureService.RESULT_CODE_UNSET
    }

    private fun captureScreenInternal(): Bitmap? {
        val projection = mediaProjection ?: return null
        // If the user or system stopped the projection, tear down so the next consent
        // flow starts clean instead of throwing on a dead object.
        if (projectionFromService &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            ScreenCaptureService.mediaProjection == null) {
            invalidateProjection()
            return null
        }
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            metrics.widthPixels = bounds.width()
            metrics.heightPixels = bounds.height()
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getMetrics(metrics)
        }
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        // A fresh DisplayMetrics has densityDpi = 0 and createVirtualDisplay rejects
        // non-positive density. Pull the real DPI from resources as fallback.
        val density = if (metrics.densityDpi > 0) metrics.densityDpi else {
            val resMetrics = context.resources.displayMetrics
            if (resMetrics.densityDpi > 0) resMetrics.densityDpi
            else (metrics.density * 160f).toInt().coerceAtLeast(120)
        }
        if (imageReader == null || imageReader?.width != width || imageReader?.height != height) {
            imageReader?.close()
            imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        }
        if (virtualDisplay == null) {
            virtualDisplay = projection.createVirtualDisplay("ScreenCapture", width, height, density, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, imageReader!!.surface, null, null)
        }
        val image: Image? = imageReader?.acquireLatestImage()
        if (image == null) return null
        val planes = image.planes
        val buffer = planes[0].buffer
        val pixelStride = planes[0].pixelStride
        val rowStride = planes[0].rowStride
        val rowPadding = rowStride - pixelStride * width
        val bitmap = Bitmap.createBitmap(width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888)
        bitmap.copyPixelsFromBuffer(buffer)
        image.close()
        val croppedBitmap = Bitmap.createBitmap(bitmap, 0, 0, width, height)
        bitmap.recycle()
        return croppedBitmap
    }

    private suspend fun requestScreenCapturePermission(): ToolExecutionResult = withContext(Dispatchers.Main) {
        val serviceAlive = Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE || ScreenCaptureService.mediaProjection != null
        if (screenCaptureData != null && mediaProjection != null && serviceAlive) {
            val r = RequestPermissionResult(success = true, error = "Permission already granted")
            return@withContext ToolExecutionResult.success(r, json.encodeToString(RequestPermissionResult.serializer(), r))
        }
        return@withContext try {
            val intent = Intent("com.unuslumen.app.guru.REQUEST_SCREEN_CAPTURE").apply { setPackage(context.packageName) }
            context.sendBroadcast(intent)
            val r = RequestPermissionResult(success = true, error = "Permission dialog should appear. Tap 'Allow' on the system dialog, then try captureScreen again.")
            ToolExecutionResult.success(r, json.encodeToString(RequestPermissionResult.serializer(), r))
        } catch (e: Exception) {
            val r = RequestPermissionResult(success = false, error = "Could not request permission: ${e.message}. Open the app manually, go to Settings > Integrations, and grant screen capture permission when prompted.")
            ToolExecutionResult.success(r, json.encodeToString(RequestPermissionResult.serializer(), r))
        }
    }

    private suspend fun autoPairAdb(): ToolExecutionResult = withContext(Dispatchers.Main) {
        val service = GuruAccessibilityService.instance ?: return@withContext run {
            val r = AutoPairAdbResult(success = false, error = accessibilityNotRunningError(), step = "check_accessibility")
            ToolExecutionResult.success(r, json.encodeToString(AutoPairAdbResult.serializer(), r))
        }
        try {
            Log.d(TAG, "AutoPairAdb: Opening Wireless Debugging settings")
            val settingsIntent = Intent(Intent.ACTION_MAIN).apply {
                component = android.content.ComponentName("com.android.settings", "com.android.settings.Settings\$DevelopmentSettingsActivity")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(settingsIntent)

            Log.d(TAG, "AutoPairAdb: Looking for Wireless Debugging option")
            var clicked = service.clickText("Wireless debugging", exact = false)
            if (!clicked) {
                service.scrollDown()
                Thread.sleep(500)
                clicked = service.clickText("Wireless debugging", exact = false)
            }
            if (!clicked) {
                val r = AutoPairAdbResult(success = false, error = "Could not find 'Wireless debugging' in Developer Options. Make sure Developer Options is enabled.", step = "find_wireless_debugging")
                return@withContext ToolExecutionResult.success(r, json.encodeToString(AutoPairAdbResult.serializer(), r))
            }
            Thread.sleep(1500)

            Log.d(TAG, "AutoPairAdb: Looking for 'Pair device' option")
            clicked = service.clickText("Pair device", exact = false)
            if (!clicked) {
                val r = AutoPairAdbResult(success = false, error = "Could not find 'Pair device with pairing code' option. Wireless Debugging may not be enabled.", step = "find_pair_device")
                return@withContext ToolExecutionResult.success(r, json.encodeToString(AutoPairAdbResult.serializer(), r))
            }
            Thread.sleep(1000)

            Log.d(TAG, "AutoPairAdb: Reading pairing code from dialog")
            val rootNode = service.rootInActiveWindow
            if (rootNode == null) {
                val r = AutoPairAdbResult(success = false, error = "Could not read screen. Accessibility Service may have lost focus.", step = "read_pairing_code")
                return@withContext ToolExecutionResult.success(r, json.encodeToString(AutoPairAdbResult.serializer(), r))
            }

            var pairingCode: String? = null
            var pairingPort: Int? = null
            val allText = mutableListOf<String>()
            collectAllText(rootNode, allText)

            for (text in allText) {
                val codeMatch = Regex("\\b(\\d{6})\\b").find(text)
                if (codeMatch != null && pairingCode == null) {
                    pairingCode = codeMatch.groupValues[1]
                }
                val portMatch = Regex(":(\\d{4,5})\\b").find(text)
                if (portMatch != null && pairingPort == null) {
                    pairingPort = portMatch.groupValues[1].toIntOrNull()
                }
                val standalonePortMatch = Regex("\\b(\\d{4,5})\\b").find(text)
                if (standalonePortMatch != null && pairingPort == null && text.length <= 6) {
                    val port = standalonePortMatch.groupValues[1].toIntOrNull()
                    if (port != null && port > 1024 && port < 65536) {
                        pairingPort = port
                    }
                }
            }

            if (pairingCode == null) {
                val r = AutoPairAdbResult(success = false, error = "Could not read pairing code from dialog. The dialog may have closed. Text found: ${allText.joinToString(", ").take(200)}", step = "read_pairing_code")
                return@withContext ToolExecutionResult.success(r, json.encodeToString(AutoPairAdbResult.serializer(), r))
            }

            Log.d(TAG, "AutoPairAdb: Found pairing code: $pairingCode, port: $pairingPort")

            val shellExecutor = com.unuslumen.app.util.shell.ShellExecutor(context)
            val result = if (pairingPort != null) {
                shellExecutor.pairWithAdb(pairingCode, pairingPort)
            } else {
                shellExecutor.autoPairWithAdb(pairingCode)
            }

            if (result.success) {
                Log.d(TAG, "AutoPairAdb: Pairing successful!")
                service.pressBack()
                Thread.sleep(300)
                service.pressBack()
                Thread.sleep(300)
                Log.d(TAG, "AutoPairAdb: Reopening guru app")
                val appIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
                if (appIntent != null) {
                    appIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    context.startActivity(appIntent)
                }
            }

            val r = AutoPairAdbResult(success = result.success, error = result.error, pairingCode = pairingCode, pairingPort = pairingPort, step = "pair")
            ToolExecutionResult.success(r, json.encodeToString(AutoPairAdbResult.serializer(), r))
        } catch (e: Exception) {
            Log.e(TAG, "AutoPairAdb: failed", e)
            val r = AutoPairAdbResult(success = false, error = "Auto-pair failed: ${e.message}", step = "exception")
            ToolExecutionResult.success(r, json.encodeToString(AutoPairAdbResult.serializer(), r))
        }
    }

    private fun collectAllText(node: AccessibilityNodeInfo, results: MutableList<String>) {
        node.text?.toString()?.let { results.add(it) }
        node.contentDescription?.toString()?.let { results.add(it) }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { collectAllText(it, results) }
        }
    }

    fun setScreenCapturePermission(resultCode: Int, data: Intent) {
        screenCaptureResultCode = resultCode
        screenCaptureData = data
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val serviceIntent = Intent(context, ScreenCaptureService::class.java).apply {
                action = ScreenCaptureService.ACTION_START
                putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode)
                putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, data)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(serviceIntent) else context.startService(serviceIntent)
        } else {
            val manager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = manager.getMediaProjection(resultCode, data)
        }
    }
}