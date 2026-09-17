package com.unuslumen.app.data.tools

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import com.unuslumen.app.data.tools.registry.ToolExecutionResult
import com.unuslumen.app.data.tools.registry.ToolExecutor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

class AppManagementToolExecutor(private val context: Context) : ToolExecutor {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun execute(toolName: String, args: Map<String, Any?>): ToolExecutionResult = when (toolName) {
        AppManagementToolDefinitions.LIST_INSTALLED_APPS -> listApps(args)
        AppManagementToolDefinitions.LAUNCH_APP -> launchApp(args)
        AppManagementToolDefinitions.UNINSTALL_APP -> uninstallApp(args)
        AppManagementToolDefinitions.INSTALL_APK -> installApk(args)
        AppManagementToolDefinitions.FORCE_STOP_APP -> forceStopApp(args)
        AppManagementToolDefinitions.CLEAR_APP_DATA -> clearAppData(args)
        else -> ToolExecutionResult.error("Unknown tool: $toolName")
    }

    private suspend fun listApps(args: Map<String, Any?>): ToolExecutionResult = withContext(Dispatchers.IO) {
        val includeSystem = (args["includeSystem"] as? Boolean) ?: false
        try { val pm = context.packageManager; val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA).filter { includeSystem || (it.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) == 0 }.map { app -> AppInfo(app.packageName, pm.getApplicationLabel(app).toString(), try { pm.getPackageInfo(app.packageName, 0).versionName ?: "unknown" } catch (e: Exception) { "unknown" }, (app.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0) }.sortedBy { it.name.lowercase() }; val r = AppListResult(apps, null); ToolExecutionResult.success(r, json.encodeToString(AppListResult.serializer(), r)) } catch (e: Exception) { val r = AppListResult(emptyList(), "Failed: ${e.message}"); ToolExecutionResult.success(r, json.encodeToString(AppListResult.serializer(), r)) }
    }

    private suspend fun launchApp(args: Map<String, Any?>): ToolExecutionResult = withContext(Dispatchers.Main) {
        val pkg = args["packageName"] as? String ?: return@withContext ToolExecutionResult.error("Missing 'packageName'")
        try { val intent = context.packageManager.getLaunchIntentForPackage(pkg) ?: return@withContext ToolExecutionResult.error("App not found: $pkg"); intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); context.startActivity(intent); val r = AppActionResult(true, null); ToolExecutionResult.success(r, json.encodeToString(AppActionResult.serializer(), r)) } catch (e: Exception) { val r = AppActionResult(false, "Failed: ${e.message}"); ToolExecutionResult.success(r, json.encodeToString(AppActionResult.serializer(), r)) }
    }

    private suspend fun uninstallApp(args: Map<String, Any?>): ToolExecutionResult = withContext(Dispatchers.Main) {
        val pkg = args["packageName"] as? String ?: return@withContext ToolExecutionResult.error("Missing 'packageName'")
        try { val intent = Intent(Intent.ACTION_DELETE, Uri.parse("package:$pkg")); intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); context.startActivity(intent); val r = AppActionResult(true, null); ToolExecutionResult.success(r, json.encodeToString(AppActionResult.serializer(), r)) } catch (e: Exception) { val r = AppActionResult(false, "Failed: ${e.message}"); ToolExecutionResult.success(r, json.encodeToString(AppActionResult.serializer(), r)) }
    }

    private suspend fun installApk(args: Map<String, Any?>): ToolExecutionResult = withContext(Dispatchers.Main) {
        val apkPath = args["apkPath"] as? String ?: return@withContext ToolExecutionResult.error("Missing 'apkPath'")
        try { val file = File(apkPath); if (!file.exists()) return@withContext ToolExecutionResult.error("APK not found: $apkPath"); val intent = Intent(Intent.ACTION_VIEW).apply { setDataAndType(Uri.fromFile(file), "application/vnd.android.package-archive"); addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }; context.startActivity(intent); val r = AppActionResult(true, null); ToolExecutionResult.success(r, json.encodeToString(AppActionResult.serializer(), r)) } catch (e: Exception) { val r = AppActionResult(false, "Failed: ${e.message}"); ToolExecutionResult.success(r, json.encodeToString(AppActionResult.serializer(), r)) }
    }

    private suspend fun forceStopApp(args: Map<String, Any?>): ToolExecutionResult = withContext(Dispatchers.IO) {
        val pkg = args["packageName"] as? String ?: return@withContext ToolExecutionResult.error("Missing 'packageName'")
        try { val p = Runtime.getRuntime().exec(arrayOf("am", "force-stop", pkg)); p.waitFor(); val r = AppActionResult(p.exitValue() == 0, null); ToolExecutionResult.success(r, json.encodeToString(AppActionResult.serializer(), r)) } catch (e: Exception) { val r = AppActionResult(false, "Failed: ${e.message}"); ToolExecutionResult.success(r, json.encodeToString(AppActionResult.serializer(), r)) }
    }

    private suspend fun clearAppData(args: Map<String, Any?>): ToolExecutionResult = withContext(Dispatchers.IO) {
        val pkg = args["packageName"] as? String ?: return@withContext ToolExecutionResult.error("Missing 'packageName'")
        try { val p = Runtime.getRuntime().exec(arrayOf("pm", "clear", pkg)); val output = p.inputStream.bufferedReader().readText(); p.waitFor(); val success = output.contains("Success") || p.exitValue() == 0; val r = AppActionResult(success, if (!success) output.trim() else null); ToolExecutionResult.success(r, json.encodeToString(AppActionResult.serializer(), r)) } catch (e: Exception) { val r = AppActionResult(false, "Failed: ${e.message}"); ToolExecutionResult.success(r, json.encodeToString(AppActionResult.serializer(), r)) }
    }
}