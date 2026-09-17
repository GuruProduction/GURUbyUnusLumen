package com.unuslumen.app.data.tools

import com.unuslumen.app.data.tools.registry.ToolDefinition
import com.unuslumen.app.data.tools.registry.ToolExecutor
import com.unuslumen.app.data.tools.registry.ToolParameter
import com.unuslumen.app.data.tools.registry.ToolParameterType
import com.unuslumen.app.data.tools.registry.ToolResultExtractor
import com.unuslumen.app.data.tools.registry.ToolSetRegistration
import kotlin.reflect.KClass

object AppManagementToolDefinitions : ToolSetRegistration {
    const val LIST_INSTALLED_APPS = "listInstalledApps"; const val LAUNCH_APP = "launchApp"
    const val UNINSTALL_APP = "uninstallApp"; const val INSTALL_APK = "installApk"
    const val FORCE_STOP_APP = "forceStopApp"; const val CLEAR_APP_DATA = "clearAppData"

    override val definitions = listOf(
        ToolDefinition(name = LIST_INSTALLED_APPS, description = "List installed apps on the device. Returns package name, app name, and version.", category = "app", parameters = listOf(ToolParameter("includeSystem", ToolParameterType.Boolean, false, "Include system apps, default false")), permissions = emptyList()),
        ToolDefinition(name = LAUNCH_APP, description = "Launch an app by package name.", category = "app", parameters = listOf(ToolParameter("packageName", ToolParameterType.String, true, "Package name")), permissions = emptyList()),
        ToolDefinition(name = UNINSTALL_APP, description = "Uninstall an app by package name. Opens the uninstall dialog.", category = "app", parameters = listOf(ToolParameter("packageName", ToolParameterType.String, true, "Package name")), permissions = emptyList()),
        ToolDefinition(name = INSTALL_APK, description = "Install an APK file. Opens the install dialog.", category = "app", parameters = listOf(ToolParameter("apkPath", ToolParameterType.String, true, "APK file path")), permissions = emptyList()),
        ToolDefinition(name = FORCE_STOP_APP, description = "Force stop an app by package name.", category = "app", parameters = listOf(ToolParameter("packageName", ToolParameterType.String, true, "Package name")), permissions = emptyList()),
        ToolDefinition(name = CLEAR_APP_DATA, description = "Clear an app's data and cache.", category = "app", parameters = listOf(ToolParameter("packageName", ToolParameterType.String, true, "Package name")), permissions = emptyList())
    )
    override fun executorClass(): KClass<out ToolExecutor> = AppManagementToolExecutor::class
    override fun extractorClass(): KClass<out ToolResultExtractor>? = null
}