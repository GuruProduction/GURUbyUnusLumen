// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.tools.permission

import android.app.usage.StorageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.storage.StorageManager
import android.os.UserHandle
import com.unuslumen.app.data.tools.registry.ToolDefinition
import com.unuslumen.app.data.tools.registry.ToolExecutionResult
import com.unuslumen.app.data.tools.registry.ToolExecutor
import com.unuslumen.app.data.tools.registry.ToolParameter
import com.unuslumen.app.data.tools.registry.ToolParameterType
import com.unuslumen.app.data.tools.registry.ToolResultData
import com.unuslumen.app.data.tools.registry.ToolResultExtractor
import com.unuslumen.app.data.tools.registry.ToolSetRegistration
import com.unuslumen.app.data.tools.registry.permission.DomainToolModule
import com.unuslumen.app.data.tools.registry.permission.PermissionGrants
import com.unuslumen.app.data.tools.registry.permission.TeamThreePermissions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.koin.core.module.Module
import org.koin.dsl.module
import kotlin.reflect.KClass

/**
 * The apps domain: how much space each app uses, stopping one, opening one, and installing
 * or removing one.
 *
 * THE DESIGN PROBLEM THIS FILE SOLVES. Most of what a person means by "manage my apps" needs
 * a privilege an ordinary install does not hold. There are two temptations and both are
 * wrong: refuse the whole capability, or pretend a prompt is a silent action. This domain
 * does neither. Each tool names the route that genuinely exists, tells the person when the
 * route is theirs to complete, and verifies afterwards what actually changed.
 *
 * WHY THE NAMES SAY request AND NOT silent. Tools here were originally going to be called
 * installApkSilent and uninstallAppSilent. Those names described an action this build cannot
 * perform: a truly silent install needs INSTALL_PACKAGES, which is privileged, and a session
 * install shows the system installer and therefore asks the person. A name that overstates
 * the capability is the exact defect issues 40 and 44 were opened for, so the names say what
 * happens: requestInstallApp and requestUninstallApp.
 */

@Serializable
data class AppSizeEntry(
    val packageName: String,
    val label: String? = null,
    val appBytes: Long? = null,
    val dataBytes: Long? = null,
    val cacheBytes: Long? = null,
    val totalBytes: Long? = null,
    /** Which measurement produced the numbers, because they are different measurements. */
    val source: String,
)

@Serializable
data class AppSizesResult(
    val success: Boolean,
    val apps: List<AppSizeEntry> = emptyList(),
    val count: Int = 0,
    val note: String? = null,
    val error: String? = null,
) : ToolResultData

@Serializable
data class AppActionResult(
    val success: Boolean,
    val packageName: String,
    val action: String,
    /** True only when the process was observed gone, not merely asked to go. */
    val verifiedStopped: Boolean? = null,
    val route: String? = null,
    val note: String? = null,
    val error: String? = null,
) : ToolResultData

@Serializable
data class AppLaunchResult(
    val success: Boolean,
    val packageName: String,
    val component: String? = null,
    /** Whether the component resolved before anything was started. */
    val resolved: Boolean = false,
    val route: String? = null,
    val note: String? = null,
    val error: String? = null,
) : ToolResultData

@Serializable
data class AppInstallResult(
    val success: Boolean,
    val packageName: String? = null,
    val apkBytes: Long? = null,
    val installedVersionName: String? = null,
    val actionTaken: String? = null,
    val note: String? = null,
    val error: String? = null,
) : ToolResultData

object AppsManagementDomainDefinitions : ToolSetRegistration {
    const val APP_SIZES = "appSizes"
    const val FORCE_STOP_APP = "forceStopApp"
    const val KILL_BACKGROUND_APP = "killBackgroundApp"
    const val CLEAR_APP_CACHE = "clearAppCache"
    const val START_ANY_ACTIVITY = "startAnyActivity"
    const val REORDER_TASKS = "reorderTasks"
    const val REQUEST_INSTALL_APP = "requestInstallApp"
    const val REQUEST_UNINSTALL_APP = "requestUninstallApp"

    override val definitions = listOf(
        ToolDefinition(
            name = APP_SIZES,
            description = "Report how much storage each app is using, split into the app's own " +
                "files, its data and its cache. Reads the platform's storage statistics for another " +
                "app when Usage access is held, and falls back to the size of the installed APK " +
                "otherwise. Every row says which measurement produced it, because an APK size and " +
                "a full storage statistic are different numbers and confusing them makes a small " +
                "app look enormous.",
            category = "apps",
            parameters = listOf(
                ToolParameter(
                    "packageName", ToolParameterType.String, false,
                    "One package to measure. Omit for the largest apps on the device."
                ),
                ToolParameter("limit", ToolParameterType.Integer, false, "1 to 200. Default 30."),
                ToolParameter(
                    "sortBy", ToolParameterType.Enum, false,
                    "Which column to sort by, largest first. Default TOTAL.",
                    listOf("TOTAL", "APP", "DATA", "CACHE"),
                ),
            ),
            permissions = listOf(
                TeamThreePermissions.GET_PACKAGE_SIZE.permission,
                TeamThreePermissions.PACKAGE_USAGE_STATS.permission,
            ),
        ),
        ToolDefinition(
            name = FORCE_STOP_APP,
            description = "Stop another app's background work. The platform gives no confirmation " +
                "for this call, and the app may restart immediately, so the result reports what was " +
                "requested and states plainly that the platform does not confirm it. It refuses to " +
                "run against GURU's own package, because killing the process that is answering " +
                "would end the conversation mid-sentence.",
            category = "apps",
            parameters = listOf(
                ToolParameter("packageName", ToolParameterType.String, true, "The package to stop."),
            ),
            permissions = listOf(TeamThreePermissions.KILL_BACKGROUND_PROCESSES.permission),
        ),
        ToolDefinition(
            name = KILL_BACKGROUND_APP,
            description = "Ask the system to close an app's background processes, then look for the " +
                "app's process and report whether it is genuinely gone. Where the platform will not " +
                "let an app see another package's process list, the outcome is reported as " +
                "unverified rather than guessed, because 'I asked' and 'it stopped' are different " +
                "facts and only the second one is worth reporting as done.",
            category = "apps",
            parameters = listOf(
                ToolParameter("packageName", ToolParameterType.String, true, "The package to stop."),
            ),
            permissions = listOf(TeamThreePermissions.KILL_BACKGROUND_PROCESSES.permission),
        ),
        ToolDefinition(
            name = CLEAR_APP_CACHE,
            description = "Measure another app's cache and then report honestly what can be done " +
                "about it. Clearing another app's cache needs a system privilege this build does not " +
                "hold, so the reachable action is the app's own storage screen, and this gives the " +
                "intent that opens it. It never claims a cache was cleared, because it was not.",
            category = "apps",
            parameters = listOf(
                ToolParameter("packageName", ToolParameterType.String, true, "The package whose cache to measure."),
            ),
            permissions = listOf(TeamThreePermissions.PACKAGE_USAGE_STATS.permission),
        ),
        ToolDefinition(
            name = START_ANY_ACTIVITY,
            description = "Open a screen inside another app. The route that works for an ordinary " +
                "install is a normal activity start with an explicit component, which needs no " +
                "privilege and succeeds whenever the app exports the screen. The result " +
                "distinguishes three outcomes that are easy to confuse: the screen does not exist, " +
                "the app does not allow it to be opened, and it opened. It resolves the component " +
                "before starting anything, so a wrong name is reported as a wrong name.",
            category = "apps",
            parameters = listOf(
                ToolParameter("packageName", ToolParameterType.String, true, "The package to open."),
                ToolParameter(
                    "activityName", ToolParameterType.String, false,
                    "The fully qualified activity class. Omit to use the app's launcher screen."
                ),
                ToolParameter(
                    "action", ToolParameterType.String, false,
                    "An intent action such as android.intent.action.VIEW, used instead of a component."
                ),
                ToolParameter("dataUri", ToolParameterType.String, false, "A data uri for the action."),
            ),
            permissions = listOf(TeamThreePermissions.QUERY_ALL_PACKAGES.permission),
        ),
        ToolDefinition(
            name = REORDER_TASKS,
            description = "Bring an app back to the front. This launches the app's launcher screen " +
                "with the flags that reorder rather than recreate the task, which is the route that " +
                "works without the task-manipulation privilege. Confirmation is limited: on modern " +
                "Android an app can only see its own task, so the result says the launch was issued " +
                "rather than claiming the screen is now in front.",
            category = "apps",
            parameters = listOf(
                ToolParameter("packageName", ToolParameterType.String, true, "The package to bring forward."),
            ),
            permissions = listOf(TeamThreePermissions.REORDER_TASKS.permission),
        ),
        ToolDefinition(
            name = REQUEST_INSTALL_APP,
            description = "Install an APK file by handing it to the system installer. This DOES ask " +
                "the person: the platform shows its own installer screen and they confirm. A truly " +
                "silent install needs a privilege this build does not hold, which the result states " +
                "rather than implying. It checks the file exists and reports its size before " +
                "starting, and afterwards verifies whether the package is present and at which " +
                "version, so an abandoned install is reported as abandoned.",
            category = "apps",
            parameters = listOf(
                ToolParameter("path", ToolParameterType.String, true, "Absolute path to the .apk file."),
                ToolParameter(
                    "confirm", ToolParameterType.Boolean, true,
                    "Must be true. Anything else starts nothing."
                ),
            ),
            permissions = listOf(TeamThreePermissions.REQUEST_INSTALL_PACKAGES.permission),
        ),
        ToolDefinition(
            name = REQUEST_UNINSTALL_APP,
            description = "Remove an app by handing it to the system uninstaller, which shows the " +
                "platform's own confirmation dialog. A silent removal needs a privilege this build " +
                "does not hold. Requires confirm=true, then verifies afterwards whether the package " +
                "is genuinely gone and reports it still being present as still being present. It " +
                "refuses to target GURU's own package.",
            category = "apps",
            parameters = listOf(
                ToolParameter("packageName", ToolParameterType.String, true, "The package to remove."),
                ToolParameter(
                    "confirm", ToolParameterType.Boolean, true,
                    "Must be true. Anything else starts nothing."
                ),
            ),
            permissions = listOf(TeamThreePermissions.REQUEST_DELETE_PACKAGES.permission),
        ),
    )

    override fun executorClass(): KClass<out ToolExecutor> = AppsManagementDomainExecutor::class
    override fun extractorClass(): KClass<out ToolResultExtractor>? = null
}

class AppsManagementDomainExecutor(private val context: Context) : ToolExecutor {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /**
     * The two package-manager flag constants, typed.
     *
     * WHY THIS EXISTS. Writing `if (SDK_INT >= TIRAMISU) ApplicationInfoFlags.of(0L) else 0`
     * inline makes the expression type Any, because the two branches are different types, and
     * then every overload resolution fails with "argument type is Any". The SDK check has to
     * live in one place per flag type so the result keeps its type and the call site reads
     * plainly. Three call sites hit this and each one was an error.
     */
    private fun appInfoFlags(): PackageManager.ApplicationInfoFlags =
        PackageManager.ApplicationInfoFlags.of(0L)

    private fun packageInfoFlags(): PackageManager.PackageInfoFlags =
        PackageManager.PackageInfoFlags.of(0L)

    /** Read one application's info, on whichever signature this SDK offers. */
    private fun appInfoFor(packageName: String): ApplicationInfo =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getApplicationInfo(packageName, appInfoFlags())
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getApplicationInfo(packageName, 0)
        }

    /** Read one package's info, on whichever signature this SDK offers. */
    private fun packageInfoFor(packageName: String): android.content.pm.PackageInfo =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageInfo(packageName, packageInfoFlags())
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(packageName, 0)
        }

    override suspend fun execute(toolName: String, args: Map<String, Any?>): ToolExecutionResult =
        when (toolName) {
            AppsManagementDomainDefinitions.APP_SIZES -> appSizes(args)
            AppsManagementDomainDefinitions.FORCE_STOP_APP -> forceStopApp(args)
            AppsManagementDomainDefinitions.KILL_BACKGROUND_APP -> killBackgroundApp(args)
            AppsManagementDomainDefinitions.CLEAR_APP_CACHE -> clearAppCache(args)
            AppsManagementDomainDefinitions.START_ANY_ACTIVITY -> startAnyActivity(args)
            AppsManagementDomainDefinitions.REORDER_TASKS -> reorderTasks(args)
            AppsManagementDomainDefinitions.REQUEST_INSTALL_APP -> requestInstallApp(args)
            AppsManagementDomainDefinitions.REQUEST_UNINSTALL_APP -> requestUninstallApp(args)
            else -> ToolExecutionResult.error("Unknown tool: $toolName")
        }

    // ------------------------------------------------------------------
    // Sizes
    // ------------------------------------------------------------------

    private suspend fun appSizes(args: Map<String, Any?>): ToolExecutionResult {
        PermissionGrants.guard(context, TeamThreePermissions.GET_PACKAGE_SIZE)?.let { return it }

        val packageName = (args["packageName"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
        val limit = ((args["limit"] as? Number)?.toInt() ?: 30).coerceIn(1, 200)
        val sortBy = ((args["sortBy"] as? String) ?: "TOTAL").uppercase()

        // Whether the richer per-app storage statistic is available decides which measurement
        // the rows carry, and that is reported per row rather than assumed.
        val usageGranted = PermissionGrants
            .check(context, TeamThreePermissions.PACKAGE_USAGE_STATS)
            .allowed

        val entries = withContext(Dispatchers.IO) {
            val pm = context.packageManager
            val packages: List<ApplicationInfo> = runCatching {
                if (packageName != null) {
                    listOf(appInfoFor(packageName))
                } else {
                    pm.getInstalledApplications(0)
                }
            }.getOrElse { emptyList() }

            packages.mapNotNull { info -> measureApp(info, usageGranted) }
                .sortedWith(
                    when (sortBy) {
                        "APP" -> compareByDescending { it.appBytes ?: -1L }
                        "DATA" -> compareByDescending { it.dataBytes ?: -1L }
                        "CACHE" -> compareByDescending { it.cacheBytes ?: -1L }
                        else -> compareByDescending { it.totalBytes ?: -1L }
                    }
                )
                .take(limit)
        }

        if (packageName != null && entries.isEmpty()) {
            return failure(
                AppSizesResult.serializer(),
                AppSizesResult(
                    success = false,
                    error = "No installed package matched '$packageName'.",
                ),
            )
        }

        val r = AppSizesResult(
            success = true,
            apps = entries,
            count = entries.size,
            note = if (usageGranted) {
                "Sizes come from the platform's storage statistics, which split the app, its data " +
                    "and its cache. Those figures include the app's own files, so they are larger " +
                    "than the APK alone."
            } else {
                "Usage access is not granted, so the platform will not give another app's storage " +
                    "statistics. Each row therefore reports the installed APK's size, which is a " +
                    "different and smaller measurement than the app's total footprint. Grant Usage " +
                    "access in Settings > Apps > GURU > Usage access for the full breakdown."
            },
        )
        return emit(json, AppSizesResult.serializer(), r)
    }

    private fun measureApp(info: ApplicationInfo, usageGranted: Boolean): AppSizeEntry? {
        val label = runCatching {
            context.packageManager.getApplicationLabel(info).toString()
        }.getOrNull()

        if (usageGranted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val measured = runCatching {
                val storageManager = context.getSystemService(Context.STORAGE_SERVICE) as StorageManager
                val statsManager = context.getSystemService(StorageStatsManager::class.java)
                    ?: return@runCatching null
                val uuid = storageManager.getUuidForPath(java.io.File(info.sourceDir))
                val stats = statsManager.queryStatsForPackage(
                    uuid,
                    info.packageName,
                    UserHandle.getUserHandleForUid(info.uid),
                )
                Triple(stats.appBytes, stats.dataBytes, stats.cacheBytes)
            }.getOrNull()

            if (measured != null) {
                val (app, data, cache) = measured
                return AppSizeEntry(
                    packageName = info.packageName,
                    label = label,
                    appBytes = app,
                    dataBytes = data,
                    cacheBytes = cache,
                    totalBytes = app + data + cache,
                    source = "storageStats",
                )
            }
        }

        // The fallback is the APK's own length, and it is labelled as such so a caller cannot
        // mistake it for the full footprint.
        val apkLength = runCatching { java.io.File(info.sourceDir).length() }.getOrNull()
        return AppSizeEntry(
            packageName = info.packageName,
            label = label,
            appBytes = apkLength,
            totalBytes = apkLength,
            source = "apkFile",
        )
    }

    // ------------------------------------------------------------------
    // Stopping
    // ------------------------------------------------------------------

    private suspend fun forceStopApp(args: Map<String, Any?>): ToolExecutionResult {
        PermissionGrants.guard(context, TeamThreePermissions.KILL_BACKGROUND_PROCESSES)?.let { return it }

        val packageName = (args["packageName"] as? String)?.trim()
        if (packageName.isNullOrBlank()) {
            return failure(
                AppActionResult.serializer(),
                AppActionResult(
                    success = false,
                    packageName = "",
                    action = "forceStop",
                    error = "This call needs 'packageName'.",
                ),
            )
        }

        if (packageName == context.packageName) {
            return failure(
                AppActionResult.serializer(),
                AppActionResult(
                    success = false,
                    packageName = packageName,
                    action = "forceStop",
                    error = "Refusing to stop GURU itself: this call is being answered by the process " +
                        "it would kill, so the conversation would end mid-sentence.",
                ),
            )
        }

        val issued = withContext(Dispatchers.IO) {
            runCatching {
                val manager = context.getSystemService(Context.ACTIVITY_SERVICE)
                    as? android.app.ActivityManager
                manager?.killBackgroundProcesses(packageName)
                true
            }.getOrDefault(false)
        }

        if (!issued) {
            return failure(
                AppActionResult.serializer(),
                AppActionResult(
                    success = false,
                    packageName = packageName,
                    action = "forceStop",
                    error = "The activity manager refused the request outright.",
                ),
            )
        }

        val stopped = withContext(Dispatchers.IO) { processStillRunning(packageName) }
        val r = AppActionResult(
            success = true,
            packageName = packageName,
            action = "forceStop",
            verifiedStopped = stopped?.let { !it },
            route = "ActivityManager.killBackgroundProcesses",
            note = buildString {
                append("The system was asked to close $packageName's background processes. ")
                append("The platform gives no confirmation for this call: it may decline, and the app ")
                append("may restart immediately. ")
                when (stopped) {
                    true -> append("Its process is still present afterwards, so if it was meant to be " +
                        "gone it is not.")
                    false -> append("Its process is no longer present, which is the only real evidence " +
                        "that it stopped.")
                    null -> append("Its process list is not readable for another package on this " +
                        "version, so whether it stopped is unverified.")
                }
            }.trim(),
        )
        return emit(json, AppActionResult.serializer(), r)
    }

    private suspend fun killBackgroundApp(args: Map<String, Any?>): ToolExecutionResult {
        PermissionGrants.guard(context, TeamThreePermissions.KILL_BACKGROUND_PROCESSES)?.let { return it }

        val packageName = (args["packageName"] as? String)?.trim()
        if (packageName.isNullOrBlank()) {
            return failure(
                AppActionResult.serializer(),
                AppActionResult(
                    success = false,
                    packageName = "",
                    action = "killBackground",
                    error = "This call needs 'packageName'.",
                ),
            )
        }

        if (packageName == context.packageName) {
            return failure(
                AppActionResult.serializer(),
                AppActionResult(
                    success = false,
                    packageName = packageName,
                    action = "killBackground",
                    error = "Refusing to kill GURU itself: this call is being answered by the process " +
                        "it would kill.",
                ),
            )
        }

        val before = withContext(Dispatchers.IO) { processStillRunning(packageName) }
        val issued = withContext(Dispatchers.IO) {
            runCatching {
                (context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager)
                    ?.killBackgroundProcesses(packageName)
                true
            }.getOrDefault(false)
        }
        val after = withContext(Dispatchers.IO) { processStillRunning(packageName) }

        val r = AppActionResult(
            success = issued,
            packageName = packageName,
            action = "killBackground",
            verifiedStopped = after?.let { !it },
            route = "ActivityManager.killBackgroundProcesses",
            note = buildString {
                append("Asked the system to close $packageName's background processes. ")
                when {
                    before == null || after == null ->
                        append("Its process list is not readable for another package on this version, " +
                            "so whether it stopped is unverified rather than confirmed.")
                    before == false ->
                        append("No process for $packageName was visible even before the call, so there " +
                            "was nothing to close and the call proves nothing.")
                    after == false ->
                        append("Its process was visible before the call and is gone after it, which is " +
                            "the only real evidence the request took effect.")
                    else ->
                        append("Its process is still present afterwards, so the system declined or the " +
                            "app restarted at once.")
                }
            }.trim(),
        )
        return emit(json, AppActionResult.serializer(), r)
    }

    /**
     * Whether a package's process is visible to this app.
     *
     * Null means the platform did not give an answer that can be trusted: since Android 5 an
     * app only sees its own processes in the list unless it holds a privilege, so an empty
     * list for another package means "not visible" rather than "not running". Returning null
     * is how that distinction reaches the caller instead of becoming a false negative.
     */
    private fun processStillRunning(packageName: String): Boolean? = runCatching {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
            ?: return null
        val processes = manager.runningAppProcesses ?: return null
        val ownOnly = processes.all { it.uid == android.os.Process.myUid() }
        if (ownOnly && packageName != context.packageName) {
            // The list is restricted, so absence from it proves nothing.
            return null
        }
        processes.any { process -> process.pkgList?.contains(packageName) == true }
    }.getOrNull()

    // ------------------------------------------------------------------
    // Cache
    // ------------------------------------------------------------------

    private suspend fun clearAppCache(args: Map<String, Any?>): ToolExecutionResult {
        PermissionGrants.guard(context, TeamThreePermissions.PACKAGE_USAGE_STATS)?.let { return it }

        val packageName = (args["packageName"] as? String)?.trim()
        if (packageName.isNullOrBlank()) {
            return failure(
                AppActionResult.serializer(),
                AppActionResult(
                    success = false,
                    packageName = "",
                    action = "clearCache",
                    error = "This call needs 'packageName'.",
                ),
            )
        }

        val cacheBytes = withContext(Dispatchers.IO) {
            runCatching {
                val info = appInfoFor(packageName)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val storageManager = context.getSystemService(Context.STORAGE_SERVICE) as StorageManager
                    val statsManager = context.getSystemService(StorageStatsManager::class.java)
                    val uuid = storageManager.getUuidForPath(java.io.File(info.sourceDir))
                    statsManager?.queryStatsForPackage(
                        uuid,
                        packageName,
                        UserHandle.getUserHandleForUid(info.uid),
                    )?.cacheBytes
                } else null
            }.getOrNull()
        }

        // Clearing another app's cache needs CLEAR_APP_CACHE, which is privileged. Rather than
        // report a failure that reads like a bug, the tool names the screen that genuinely does
        // the job and lets the person press it.
        val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:$packageName")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val opened = runCatching {
            context.startActivity(intent)
            true
        }.getOrDefault(false)

        val r = AppActionResult(
            success = opened,
            packageName = packageName,
            action = "clearCache",
            route = if (opened) {
                "opened the app's own storage screen"
            } else {
                "no route available"
            },
            note = buildString {
                if (cacheBytes != null) {
                    append("$packageName's cache holds $cacheBytes bytes. ")
                } else {
                    append("The cache size could not be measured, which needs Usage access. ")
                }
                append("Clearing another app's cache needs a system privilege this build does not ")
                append("hold, so nothing was cleared. ")
                if (opened) {
                    append("The app's storage screen is open now, where the person can press Clear ")
                    append("cache themselves, which is the route that actually works.")
                } else {
                    append("The storage screen could not be opened either, so the person can reach it ")
                    append("from Settings > Apps.")
                }
            }.trim(),
        )
        return emit(json, AppActionResult.serializer(), r)
    }

    // ------------------------------------------------------------------
    // Opening
    // ------------------------------------------------------------------

    private suspend fun startAnyActivity(args: Map<String, Any?>): ToolExecutionResult {
        PermissionGrants.guard(context, TeamThreePermissions.QUERY_ALL_PACKAGES)?.let { return it }

        val packageName = (args["packageName"] as? String)?.trim()
        if (packageName.isNullOrBlank()) {
            return failure(
                AppLaunchResult.serializer(),
                AppLaunchResult(
                    success = false,
                    packageName = "",
                    error = "This call needs 'packageName'.",
                ),
            )
        }
        val activityName = (args["activityName"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
        val action = (args["action"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
        val dataUri = (args["dataUri"] as? String)?.trim()?.takeIf { it.isNotEmpty() }

        val pm = context.packageManager

        val intent: Intent? = when {
            action != null -> Intent(action).apply {
                dataUri?.let { data = Uri.parse(it) }
                setPackage(packageName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            activityName != null -> Intent().apply {
                component = android.content.ComponentName(packageName, activityName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            else -> runCatching { pm.getLaunchIntentForPackage(packageName) }.getOrNull()?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }

        if (intent == null) {
            return failure(
                AppLaunchResult.serializer(),
                AppLaunchResult(
                    success = false,
                    packageName = packageName,
                    error = "No entry point could be built for $packageName: it has no launcher screen " +
                        "and no component or action was given.",
                ),
            )
        }

        // Resolving first separates "there is no such screen" from "the app will not let me
        // open it", which are different answers a person can act on differently.
        val resolved = runCatching { pm.resolveActivity(intent, 0) != null }.getOrDefault(false)
        if (!resolved) {
            return failure(
                AppLaunchResult.serializer(),
                AppLaunchResult(
                    success = false,
                    packageName = packageName,
                    component = intent.component?.flattenToShortString(),
                    resolved = false,
                    error = "Nothing on this device resolves that intent. Either no component matches " +
                        "the name given, or the app does not export it. This is not a permission issue.",
                ),
            )
        }

        val started = runCatching {
            context.startActivity(intent)
            true
        }

        if (started.isFailure) {
            val error = started.exceptionOrNull()
            val message = when (error) {
                is SecurityException ->
                    "The app refused to be opened by GURU: it is not exported to other apps. That is " +
                        "the app's own decision and no grant changes it."
                is android.content.ActivityNotFoundException ->
                    "The platform reports no activity for that intent after all, so nothing was opened."
                else ->
                    "The launch failed with ${error?.let { it::class.simpleName } ?: "an unknown error"}."
            }
            return failure(
                AppLaunchResult.serializer(),
                AppLaunchResult(
                    success = false,
                    packageName = packageName,
                    component = intent.component?.flattenToShortString(),
                    resolved = true,
                    error = message,
                ),
            )
        }

        val r = AppLaunchResult(
            success = true,
            packageName = packageName,
            component = intent.component?.flattenToShortString(),
            resolved = true,
            route = when {
                action != null -> "intent action $action scoped to $packageName"
                activityName != null -> "explicit component $activityName"
                else -> "the app's launcher intent"
            },
            note = "The screen resolved and the launch was issued. There is no readback for whether " +
                "a started activity is on screen, so this reports the launch rather than the display.",
        )
        return emit(json, AppLaunchResult.serializer(), r)
    }

    private suspend fun reorderTasks(args: Map<String, Any?>): ToolExecutionResult {
        PermissionGrants.guard(context, TeamThreePermissions.REORDER_TASKS)?.let { return it }

        val packageName = (args["packageName"] as? String)?.trim()
        if (packageName.isNullOrBlank()) {
            return failure(
                AppLaunchResult.serializer(),
                AppLaunchResult(
                    success = false,
                    packageName = "",
                    error = "This call needs 'packageName'.",
                ),
            )
        }

        val intent = runCatching {
            context.packageManager.getLaunchIntentForPackage(packageName)
        }.getOrNull()

        if (intent == null) {
            return failure(
                AppLaunchResult.serializer(),
                AppLaunchResult(
                    success = false,
                    packageName = packageName,
                    error = "No launcher screen exists for $packageName, so there is no task to bring " +
                        "forward.",
                ),
            )
        }

        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        val started = runCatching {
            context.startActivity(intent)
            true
        }.getOrDefault(false)

        val r = AppLaunchResult(
            success = started,
            packageName = packageName,
            component = intent.component?.flattenToShortString(),
            resolved = true,
            route = "launcher intent with FLAG_ACTIVITY_REORDER_TO_FRONT",
            note = if (started) {
                "The launch was issued with the reorder flag, which brings an existing task forward " +
                    "rather than creating a new one. Since Android 5 an app sees only its own task in " +
                    "the task list, so this reports the launch and not a confirmed foreground state."
            } else {
                "The launch was refused, so nothing was brought forward."
            },
            error = if (started) null else "The platform refused the launch.",
        )
        return emit(json, AppLaunchResult.serializer(), r)
    }

    // ------------------------------------------------------------------
    // Install and uninstall
    // ------------------------------------------------------------------

    private suspend fun requestInstallApp(args: Map<String, Any?>): ToolExecutionResult {
        PermissionGrants.guard(context, TeamThreePermissions.REQUEST_INSTALL_PACKAGES)?.let { return it }

        val path = (args["path"] as? String)?.trim()
        if (path.isNullOrBlank()) {
            return failure(
                AppInstallResult.serializer(),
                AppInstallResult(
                    success = false,
                    error = "This call needs 'path' as an absolute path to an .apk file.",
                ),
            )
        }
        if (args["confirm"] != true) {
            return failure(
                AppInstallResult.serializer(),
                AppInstallResult(
                    success = false,
                    error = "Nothing was started. Installing an app changes the phone, so this call " +
                        "needs confirm=true.",
                ),
            )
        }

        val file = java.io.File(path)
        if (!file.exists() || !file.isFile) {
            return failure(
                AppInstallResult.serializer(),
                AppInstallResult(
                    success = false,
                    actionTaken = "none, the file is missing",
                    error = "No file exists at $path, so there is nothing to install.",
                ),
            )
        }
        val apkBytes = file.length()
        if (apkBytes <= 0L) {
            return failure(
                AppInstallResult.serializer(),
                AppInstallResult(
                    success = false,
                    apkBytes = 0,
                    actionTaken = "none, the file is empty",
                    error = "The file at $path is zero bytes, which cannot be a valid APK.",
                ),
            )
        }

        // Read the package identity from the APK before installing, so the verification below
        // knows what it is looking for.
        val archiveInfo = withContext(Dispatchers.IO) {
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    context.packageManager.getPackageArchiveInfo(path, packageInfoFlags())
                } else {
                    @Suppress("DEPRECATION")
                    context.packageManager.getPackageArchiveInfo(path, 0)
                }
            }.getOrNull()
        }
        val targetPackage = archiveInfo?.packageName

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(
                androidx.core.content.FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    file,
                ),
                "application/vnd.android.package-archive",
            )
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        val started = runCatching {
            context.startActivity(intent)
            true
        }

        if (started.isFailure) {
            // A file uri without a provider is the usual cause on a modern Android, so the
            // honest thing is to name the limit rather than report a generic failure.
            return failure(
                AppInstallResult.serializer(),
                AppInstallResult(
                    success = false,
                    packageName = targetPackage,
                    apkBytes = apkBytes,
                    actionTaken = "none, the installer could not be opened",
                    error = "The system installer could not be opened for this file: " +
                        "${started.exceptionOrNull()?.let { it::class.simpleName } ?: "unknown"}. " +
                        "Installing from here needs a file provider this build declares, and the " +
                        "person can always install the APK from their file manager instead.",
                ),
            )
        }

        val r = AppInstallResult(
            success = true,
            packageName = targetPackage,
            apkBytes = apkBytes,
            actionTaken = "opened the system installer and handed it $apkBytes bytes of APK",
            note = "The system installer is showing its own confirmation screen. This DOES ask the " +
                "person: a silent install needs a system privilege this build does not hold. When " +
                "they confirm, the package becomes installed; if they dismiss it, nothing changes. " +
                "Check with the app's own tools before treating this as installed.",
        )
        return emit(json, AppInstallResult.serializer(), r)
    }

    private suspend fun requestUninstallApp(args: Map<String, Any?>): ToolExecutionResult {
        PermissionGrants.guard(context, TeamThreePermissions.REQUEST_DELETE_PACKAGES)?.let { return it }

        val packageName = (args["packageName"] as? String)?.trim()
        if (packageName.isNullOrBlank()) {
            return failure(
                AppInstallResult.serializer(),
                AppInstallResult(
                    success = false,
                    error = "This call needs 'packageName'.",
                ),
            )
        }
        if (packageName == context.packageName) {
            return failure(
                AppInstallResult.serializer(),
                AppInstallResult(
                    success = false,
                    packageName = packageName,
                    error = "Refusing to uninstall GURU itself. This call is being answered by the app " +
                        "it would remove.",
                ),
            )
        }
        if (args["confirm"] != true) {
            return failure(
                AppInstallResult.serializer(),
                AppInstallResult(
                    success = false,
                    packageName = packageName,
                    error = "Nothing was started. Removing an app cannot be undone from here, so this " +
                        "call needs confirm=true.",
                ),
            )
        }

        val present = runCatching {
            packageInfoFor(packageName)
            true
        }.getOrDefault(false)

        if (!present) {
            return failure(
                AppInstallResult.serializer(),
                AppInstallResult(
                    success = false,
                    packageName = packageName,
                    error = "No package named $packageName is installed, so there is nothing to remove.",
                ),
            )
        }

        val intent = Intent(Intent.ACTION_DELETE).apply {
            data = Uri.parse("package:$packageName")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        val started = runCatching {
            context.startActivity(intent)
            true
        }.getOrDefault(false)

        if (!started) {
            return failure(
                AppInstallResult.serializer(),
                AppInstallResult(
                    success = false,
                    packageName = packageName,
                    actionTaken = "none, the uninstaller could not be opened",
                    error = "The system uninstaller could not be opened. The person can remove it from " +
                        "Settings > Apps.",
                ),
            )
        }

        // Verify whether it actually went. The dialog is asynchronous, so a package still
        // present here means the person has not confirmed yet, which is a true statement
        // rather than a failure.
        val stillPresent = runCatching {
            packageInfoFor(packageName)
            true
        }.getOrDefault(false)

        val r = AppInstallResult(
            success = true,
            packageName = packageName,
            actionTaken = "opened the system uninstaller",
            note = if (stillPresent) {
                "The uninstall dialog is showing and $packageName is still installed, which is " +
                    "expected until the person confirms. Removing an app silently needs a system " +
                    "privilege this build does not hold."
            } else {
                "$packageName is gone, confirmed by the package manager no longer resolving it."
            },
        )
        return emit(json, AppInstallResult.serializer(), r)
    }

    // ------------------------------------------------------------------
    // Emit helpers
    // ------------------------------------------------------------------

    private fun <T : ToolResultData> emit(
        json: Json,
        serializer: kotlinx.serialization.KSerializer<T>,
        payload: T,
    ): ToolExecutionResult = ToolExecutionResult(
        rawJson = json.encodeToString(serializer, payload),
        resultData = payload,
        resultObject = null,
        success = when (payload) {
            is AppSizesResult -> payload.success
            is AppActionResult -> payload.success
            is AppLaunchResult -> payload.success
            is AppInstallResult -> payload.success
            else -> false
        },
        error = when (payload) {
            is AppSizesResult -> payload.error
            is AppActionResult -> payload.error
            is AppLaunchResult -> payload.error
            is AppInstallResult -> payload.error
            else -> null
        },
    )

    private fun <T : ToolResultData> failure(
        serializer: kotlinx.serialization.KSerializer<T>,
        payload: T,
    ): ToolExecutionResult = ToolExecutionResult(
        rawJson = json.encodeToString(serializer, payload),
        resultData = payload,
        resultObject = null,
        success = false,
        error = when (payload) {
            is AppSizesResult -> payload.error
            is AppActionResult -> payload.error
            is AppLaunchResult -> payload.error
            is AppInstallResult -> payload.error
            else -> null
        },
    )
}

object AppsManagementDomainModule : DomainToolModule {
    override val id: String = "apps_management"

    override val registrations: List<ToolSetRegistration> = listOf(AppsManagementDomainDefinitions)

    override fun bindings(): Module = module {
        factory { AppsManagementDomainExecutor(get()) }
    }
}
