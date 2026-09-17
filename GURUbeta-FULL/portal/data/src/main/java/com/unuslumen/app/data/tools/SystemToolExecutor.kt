package com.unuslumen.app.data.tools

import android.content.Context
import com.unuslumen.app.data.tools.registry.ToolExecutionResult
import com.unuslumen.app.data.tools.registry.ToolExecutor
import com.unuslumen.app.domain.model.BatteryInfo
import com.unuslumen.app.domain.model.MemoryInfo
import com.unuslumen.app.domain.model.SecurityInfo
import com.unuslumen.app.domain.model.StorageInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

class SystemToolExecutor(private val context: Context) : ToolExecutor {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun execute(toolName: String, args: Map<String, Any?>): ToolExecutionResult = when (toolName) {
        SystemToolDefinitions.HEALTH_CHECK -> healthCheck()
        SystemToolDefinitions.DEVICE_INFO -> deviceInfo()
        SystemToolDefinitions.SESSION_LOGS_SEARCH -> { val r = SessionLogsResult(false, emptyList(), "Session log search requires log storage implementation."); ToolExecutionResult.success(r, json.encodeToString(SessionLogsResult.serializer(), r)) }
        SystemToolDefinitions.SESSION_LOGS_EXPORT -> { val r = SessionExportResult(false, null, "Session log export requires log storage implementation."); ToolExecutionResult.success(r, json.encodeToString(SessionExportResult.serializer(), r)) }
        else -> ToolExecutionResult.error("Unknown tool: $toolName")
    }

    private suspend fun healthCheck(): ToolExecutionResult = withContext(Dispatchers.IO) {
        try {
            val bs = android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED).let { context.registerReceiver(null, it) }
            val bp = bs?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1)?.let { l -> bs.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1)?.let { s -> if (l >= 0 && s > 0) l * 100 / s else -1 } } ?: -1
            val bstat = when (bs?.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1)) { android.os.BatteryManager.BATTERY_STATUS_CHARGING -> "Charging"; android.os.BatteryManager.BATTERY_STATUS_DISCHARGING -> "Discharging"; android.os.BatteryManager.BATTERY_STATUS_FULL -> "Full"; else -> "Unknown" }
            val stat = android.os.StatFs(android.os.Environment.getDataDirectory().path)
            val total = stat.blockCountLong * stat.blockSizeLong; val free = stat.availableBlocksLong * stat.blockSizeLong; val used = total - free
            val am = context.getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager; val mi = android.app.ActivityManager.MemoryInfo(); am.getMemoryInfo(mi)
            val issues = mutableListOf<String>(); val km = context.getSystemService(android.content.Context.KEYGUARD_SERVICE) as android.app.KeyguardManager; if (!km.isDeviceSecure) issues.add("Device not secured")
            val r = HealthResult(true, BatteryInfo(bp, bstat, bs?.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, 0)?.div(10) ?: 0), StorageInfo(total, used, free, ((used.toDouble() / total) * 100).toInt()), MemoryInfo(mi.totalMem, mi.totalMem - mi.availMem, mi.availMem, (((mi.totalMem - mi.availMem).toDouble() / mi.totalMem) * 100).toInt()), SecurityInfo(issues.isEmpty(), issues))
            ToolExecutionResult.success(r, json.encodeToString(HealthResult.serializer(), r))
        } catch (e: Exception) { val r = HealthResult(false, error = "Failed: ${e.message}"); ToolExecutionResult.success(r, json.encodeToString(HealthResult.serializer(), r)) }
    }

    private fun deviceInfo(): ToolExecutionResult {
        val r = SystemDeviceInfoResult(true, android.os.Build.MANUFACTURER, android.os.Build.MODEL, android.os.Build.DEVICE, android.os.Build.VERSION.RELEASE, android.os.Build.VERSION.SDK_INT, android.os.Build.BRAND, android.os.Build.PRODUCT, android.os.Build.BOARD, android.os.Build.HARDWARE)
        return ToolExecutionResult.success(r, json.encodeToString(SystemDeviceInfoResult.serializer(), r))
    }
}