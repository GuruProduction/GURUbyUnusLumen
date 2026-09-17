package com.unuslumen.app.guru.presentation.main

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unuslumen.app.domain.model.CalendarEvent
import com.unuslumen.app.domain.model.JournalEntry
import com.unuslumen.app.domain.model.Task
import com.unuslumen.app.domain.use_case.GetAllJournalEntriesUseCase
import com.unuslumen.app.domain.use_case.GetAllEventsUseCase
import com.unuslumen.app.domain.use_case.GetAllTasksUseCase
import com.unuslumen.app.domain.use_case.UpdateTaskCompletedUseCase
import com.unuslumen.app.preferences.PrefsConstants
import com.unuslumen.app.preferences.domain.model.Order
import com.unuslumen.app.preferences.domain.model.OrderType
import com.unuslumen.app.preferences.domain.model.booleanPreferencesKey
import com.unuslumen.app.preferences.domain.model.intPreferencesKey
import com.unuslumen.app.preferences.domain.model.stringPreferencesKey
import com.unuslumen.app.preferences.domain.model.stringSetPreferencesKey
import com.unuslumen.app.preferences.domain.model.toInt
import com.unuslumen.app.preferences.domain.model.toOrder
import com.unuslumen.app.preferences.domain.use_case.GetPreferenceUseCase
import com.unuslumen.app.preferences.domain.use_case.SavePreferenceUseCase
import com.unuslumen.app.preferences.domain.model.GuruTheme
import com.unuslumen.app.preferences.domain.model.LayoutConfig
import com.unuslumen.app.preferences.domain.repository.GuruThemeRepository
import com.unuslumen.app.ui.FontSizeSettings
import com.unuslumen.app.ui.StartUpScreenSettings
import com.unuslumen.app.ui.ThemeSettings
import com.unuslumen.app.ui.theme.Rubik
import com.unuslumen.app.ui.theme.FontRegistry
import com.unuslumen.app.ui.toInt
import com.unuslumen.app.ui.toIntList
import com.unuslumen.app.util.date.formatDateForMapping
import com.unuslumen.app.util.date.inTheLastWeek
import com.unuslumen.app.util.permissions.Permission
import com.unuslumen.app.util.permissions.PermissionGateway
import com.unuslumen.app.util.permissions.isSpecial
import com.unuslumen.app.util.permissions.standardRuntimePermissions
import com.unuslumen.app.util.permissions.specialPermissions
import com.unuslumen.app.util.permissions.toAndroidPermission
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import org.koin.android.annotation.KoinViewModel

@KoinViewModel
class MainViewModel(
    private val getPreference: GetPreferenceUseCase,
    private val savePreference: SavePreferenceUseCase,
    private val getAllTasks: GetAllTasksUseCase,
    private val getAllEntriesUseCase: GetAllJournalEntriesUseCase,
    private val completeTask: UpdateTaskCompletedUseCase,
    private val getAllEventsUseCase: GetAllEventsUseCase,
    private val guruThemeRepository: GuruThemeRepository
) : ViewModel() {

    var uiState by mutableStateOf(UiState())
    private set

    private var refreshTasksJob : Job? = null

    val lockApp = getPreference(booleanPreferencesKey(PrefsConstants.LOCK_APP_KEY), false)
    val themeMode = getPreference(intPreferencesKey(PrefsConstants.SETTINGS_THEME_KEY), ThemeSettings.AUTO.value)
    val defaultStartUpScreen = getPreference(intPreferencesKey(PrefsConstants.DEFAULT_START_UP_SCREEN_KEY), StartUpScreenSettings.LOBBY.value)

    /**
     * Current font selection, name-keyed. Read here once per boot: if the
     * name key has never been set but the legacy int key has, the old value
     * migrates across and the int key retires. After that the name key is
     * the only source of truth for the font setting.
     */
    val fontName: StateFlow<String> = combine(
        getPreference(stringPreferencesKey(PrefsConstants.APP_FONT_NAME_KEY), ""),
        getPreference(intPreferencesKey(PrefsConstants.APP_FONT_KEY), Rubik.toInt())
    ) { name, legacyInt ->
        if (name.isNotBlank()) name
        else FontRegistry.legacyIntToLabel(legacyInt)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, "Rubik (default)")

    val fontSize = getPreference(intPreferencesKey(PrefsConstants.FONT_SIZE_KEY), FontSizeSettings.NORMAL.value)
    val blockScreenshots = getPreference(booleanPreferencesKey(PrefsConstants.BLOCK_SCREENSHOTS_KEY), false)
    val useMaterialYou = getPreference(booleanPreferencesKey(PrefsConstants.SETTINGS_MATERIAL_YOU), false)
    val guruTheme: StateFlow<GuruTheme> = guruThemeRepository.getGuruTheme()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), GuruTheme.DEFAULT)

    val layoutConfig: StateFlow<LayoutConfig> = guruThemeRepository.getLayoutConfig()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), LayoutConfig.DEFAULT)

    fun onDashboardEvent(event: DashboardEvent) {
        when(event) {
            is DashboardEvent.ReadPermissionChanged -> {
                if (event.hasPermission)
                    getCalendarEvents()
            }
            is DashboardEvent.CompleteTask -> viewModelScope.launch {
                completeTask(event.task, event.isCompleted)
            }
            DashboardEvent.InitAll -> collectDashboardData()
        }
    }

    data class UiState(
        val dashBoardTasks: List<Task> = emptyList(),
        val dashBoardEvents: Map<String, List<CalendarEvent>> = emptyMap(),
        val summaryTasks: List<Task> = emptyList(),
        val dashBoardEntries: List<JournalEntry> = emptyList()
    )

    private fun getCalendarEvents() = viewModelScope.launch {
        val excluded = getPreference(
            stringSetPreferencesKey(PrefsConstants.EXCLUDED_CALENDARS_KEY),
            emptySet()
        ).first()
        val events = getAllEventsUseCase(excluded.toIntList()) {
            it.start.formatDateForMapping()
        }
        uiState = uiState.copy(
            dashBoardEvents = events
        )
    }

    private fun collectDashboardData() = viewModelScope.launch {
        combine(
            getPreference(
                intPreferencesKey(PrefsConstants.TASKS_ORDER_KEY),
                Order.DateModified(OrderType.ASC).toInt()
            ),
            getPreference(
                booleanPreferencesKey(PrefsConstants.SHOW_COMPLETED_TASKS_KEY),
                false
            ),
            getAllEntriesUseCase(Order.DateCreated(OrderType.ASC))
        ) { order, showCompleted, entries ->
            uiState = uiState.copy(
                dashBoardEntries = entries,
            )
            refreshTasks(order.toOrder(), showCompleted)
        }.collect()
    }

    private fun refreshTasks(order: Order, showCompleted: Boolean) {
        refreshTasksJob?.cancel()
        refreshTasksJob = getAllTasks(order).onEach { tasks ->
                uiState = uiState.copy(
                    dashBoardTasks = if (showCompleted) tasks else tasks.filter { !it.isCompleted },
                    summaryTasks = tasks.filter { it.createdDate.inTheLastWeek() }
                )
            }.launchIn(viewModelScope)
    }

    fun disableAppLock() = viewModelScope.launch {
        savePreference(booleanPreferencesKey(PrefsConstants.LOCK_APP_KEY), false)
    }

    fun enableDefaultAiProvider() = viewModelScope.launch {
        savePreference(intPreferencesKey(PrefsConstants.AI_PROVIDER_KEY), com.unuslumen.app.preferences.domain.model.AiProvider.UnusLumen.id)
    }

    fun saveDisplayName(name: String) = viewModelScope.launch {
        if (name.isNotBlank()) {
            savePreference(stringPreferencesKey(PrefsConstants.USER_NAME_KEY), name)
        }
    }

    // ===== Permission Gate =====

    data class PermissionGateState(
        val allGranted: Boolean = false,
        val dismissed: Boolean = false,
        val permissions: List<PermissionToggleState> = emptyList()
    )

    data class PermissionToggleState(
        val permission: Permission,
        val label: String,
        val category: String,
        val isGranted: Boolean = false,
        val isSpecial: Boolean = false,
        val isHardwareDependent: Boolean = false,
        val isEnabled: Boolean = true,
        val hasHardware: Boolean = true,
        val minSdkVersion: Int = 26,
        val existsOnCurrentApi: Boolean = true
    )

    private val _permissionGateState = MutableStateFlow(PermissionGateState(dismissed = true))
    val permissionGateState = _permissionGateState.asStateFlow()

    fun refreshPermissionState(context: Context) {
        val toggles = buildPermissionToggles(context)
        val allGranted = toggles.all { it.isGranted || !it.existsOnCurrentApi || !it.hasHardware || !it.isEnabled }
        val wasGateShown = getGateShownSync()
        val shouldShow = !wasGateShown && !allGranted
        _permissionGateState.value = PermissionGateState(
            allGranted = allGranted,
            dismissed = !shouldShow,
            permissions = toggles
        )
        if (shouldShow) {
            markGateShown()
        }
    }

    fun dismissPermissionGate() {
        _permissionGateState.value = _permissionGateState.value.copy(dismissed = true)
    }

    fun showPermissionGate(context: Context) {
        val toggles = buildPermissionToggles(context)
        val allGranted = toggles.all { it.isGranted || !it.existsOnCurrentApi || !it.hasHardware || !it.isEnabled }
        _permissionGateState.value = PermissionGateState(
            allGranted = allGranted,
            dismissed = false,
            permissions = toggles
        )
    }

    fun refreshGatePermissions(context: Context) {
        val toggles = buildPermissionToggles(context)
        val allGranted = toggles.all { it.isGranted || !it.existsOnCurrentApi || !it.hasHardware || !it.isEnabled }
        _permissionGateState.value = _permissionGateState.value.copy(
            allGranted = allGranted,
            permissions = toggles
        )
    }

    private fun getGateShownSync(): Boolean {
        return try {
            val flow = getPreference(booleanPreferencesKey(PrefsConstants.PERMISSION_GATE_SHOWN_KEY), false)
            kotlinx.coroutines.runBlocking { flow.first() }
        } catch (e: Exception) {
            false
        }
    }

    private fun markGateShown() {
        viewModelScope.launch {
            savePreference(booleanPreferencesKey(PrefsConstants.PERMISSION_GATE_SHOWN_KEY), true)
        }
    }

    fun requestAllStandardPermissions(context: Context) {
        val ungrantedStandard = standardRuntimePermissions.filter {
            !PermissionGateway.isGranted(context, it) &&
            !isHardwareAbsent(context, it) &&
            !isApiLevelAbsent(it) &&
            it.toAndroidPermission().isNotEmpty()
        }
        if (ungrantedStandard.isNotEmpty()) {
            val permStrings = ungrantedStandard.mapNotNull { it.toAndroidPermission().takeIf { p -> p.isNotEmpty() } }.toTypedArray()
            if (permStrings.isNotEmpty()) {
                val activity = context.findActivity()
                androidx.core.app.ActivityCompat.requestPermissions(activity, permStrings, 2000)
            }
        }
        val ungrantedSpecial = specialPermissions.filter {
            !PermissionGateway.isGranted(context, it) &&
            !isApiLevelAbsent(it)
        }
        ungrantedSpecial.forEach { permission ->
            PermissionGateway.requestPermission(context, permission)
        }
        val ungrantedBackgroundLocation = listOf(Permission.ACCESS_BACKGROUND_LOCATION).filter {
            !PermissionGateway.isGranted(context, it) &&
            PermissionGateway.isGranted(context, Permission.ACCESS_FINE_LOCATION) &&
            !isApiLevelAbsent(it)
        }
        ungrantedBackgroundLocation.forEach { permission ->
            PermissionGateway.requestPermission(context, permission)
        }
    }

    fun requestPermission(context: Context, permission: Permission) {
        if (PermissionGateway.isGranted(context, permission)) return
        if (permission.isSpecial) {
            PermissionGateway.requestPermission(context, permission)
        } else {
            val perm = permission.toAndroidPermission()
            if (perm.isNotEmpty()) {
                val activity = context.findActivity()
                androidx.core.app.ActivityCompat.requestPermissions(activity, arrayOf(perm), 2000)
            }
        }
    }

    fun togglePermission(context: Context, permission: Permission) {
        if (PermissionGateway.isGranted(context, permission)) {
            PermissionGateway.revokePermission(context, permission)
            refreshGatePermissions(context)
        } else {
            requestPermission(context, permission)
        }
    }

    private fun buildPermissionToggles(context: Context): List<PermissionToggleState> {
        val pm = context.packageManager
        return allGatePermissions.map { (permission, label, category, hwFeature, minApi) ->
            val hasHardware = hwFeature == null || pm.hasSystemFeature(hwFeature)
            val existsOnApi = Build.VERSION.SDK_INT >= minApi
            val isGranted = if (!hasHardware || !existsOnApi) true else PermissionGateway.isGranted(context, permission)
            val isEnabled = when {
                permission == Permission.ACCESS_BACKGROUND_LOCATION ->
                    PermissionGateway.isGranted(context, Permission.ACCESS_FINE_LOCATION)
                permission == Permission.BODY_SENSORS_BACKGROUND ->
                    PermissionGateway.isGranted(context, Permission.BODY_SENSORS)
                else -> true
            }
            PermissionToggleState(
                permission = permission,
                label = label,
                category = category,
                isGranted = isGranted,
                isSpecial = permission.isSpecial,
                isHardwareDependent = hwFeature != null,
                isEnabled = isEnabled,
                hasHardware = hasHardware,
                minSdkVersion = minApi,
                existsOnCurrentApi = existsOnApi
            )
        }
    }

    private fun isHardwareAbsent(context: Context, permission: Permission): Boolean {
        val pm = context.packageManager
        return when (permission) {
            Permission.UWB_RANGING -> !pm.hasSystemFeature(PackageManager.FEATURE_UWB)
            Permission.BODY_SENSORS -> !pm.hasSystemFeature(PackageManager.FEATURE_SENSOR_HEART_RATE) && !pm.hasSystemFeature(PackageManager.FEATURE_WATCH)
            Permission.BODY_SENSORS_BACKGROUND -> !pm.hasSystemFeature(PackageManager.FEATURE_SENSOR_HEART_RATE) && !pm.hasSystemFeature(PackageManager.FEATURE_WATCH)
            Permission.ACTIVITY_RECOGNITION -> !pm.hasSystemFeature(PackageManager.FEATURE_SENSOR_STEP_COUNTER)
            Permission.BLUETOOTH_CONNECT, Permission.BLUETOOTH_SCAN, Permission.BLUETOOTH_ADVERTISE -> !pm.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH)
            Permission.CAMERA -> !pm.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)
            Permission.ACCEPT_HANDOVER, Permission.USE_SIP, Permission.READ_CELL_BROADCASTS,
            Permission.CALL_PHONE, Permission.ANSWER_PHONE_CALLS, Permission.PROCESS_OUTGOING_CALLS,
            Permission.READ_CALL_LOG, Permission.WRITE_CALL_LOG, Permission.READ_PHONE_STATE,
            Permission.READ_PHONE_NUMBERS, Permission.SEND_SMS, Permission.RECEIVE_SMS,
            Permission.READ_SMS, Permission.RECEIVE_MMS, Permission.RECEIVE_WAP_PUSH ->
                !pm.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)
            else -> false
        }
    }

    private fun isApiLevelAbsent(permission: Permission): Boolean {
        return when (permission) {
            Permission.NOTIFICATIONS -> Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
            Permission.NEARBY_WIFI_DEVICES -> Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
            Permission.READ_MEDIA_IMAGES -> Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
            Permission.READ_MEDIA_VIDEO -> Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
            Permission.READ_MEDIA_AUDIO -> Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
            Permission.READ_MEDIA_VISUAL_USER_SELECTED -> Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE
            Permission.BLUETOOTH_SCAN, Permission.BLUETOOTH_CONNECT, Permission.BLUETOOTH_ADVERTISE -> Build.VERSION.SDK_INT < Build.VERSION_CODES.S
            Permission.UWB_RANGING -> Build.VERSION.SDK_INT < Build.VERSION_CODES.S
            Permission.BODY_SENSORS_BACKGROUND -> Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE
            Permission.ACCESS_BACKGROUND_LOCATION -> Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
            Permission.ACTIVITY_RECOGNITION -> Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
            Permission.ACCEPT_HANDOVER -> Build.VERSION.SDK_INT < Build.VERSION_CODES.P
            Permission.ACCESS_MEDIA_LOCATION -> Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
            Permission.READ_EXTERNAL_STORAGE -> Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
            Permission.WRITE_EXTERNAL_STORAGE -> Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
            else -> false
        }
    }

    private fun Context.findActivity(): android.app.Activity {
        var ctx = this
        while (ctx is android.content.ContextWrapper) {
            if (ctx is android.app.Activity) return ctx
            ctx = ctx.baseContext
        }
        throw IllegalStateException("Could not find activity from context")
    }

    companion object {
        private data class GatePermission(
            val permission: Permission,
            val label: String,
            val category: String,
            val hwFeature: String?,
            val minApi: Int
        )

        private val allGatePermissions = listOf(
            GatePermission(Permission.READ_CALENDAR, "Read Calendar", "Calendar", null, 26),
            GatePermission(Permission.WRITE_CALENDAR, "Write Calendar", "Calendar", null, 26),
            GatePermission(Permission.ACCESS_FINE_LOCATION, "Fine Location", "Location", null, 26),
            GatePermission(Permission.ACCESS_COARSE_LOCATION, "Coarse Location", "Location", null, 26),
            GatePermission(Permission.ACCESS_BACKGROUND_LOCATION, "Background Location", "Location", null, 29),
            GatePermission(Permission.ACCESS_MEDIA_LOCATION, "Media Location", "Location", null, 29),
            GatePermission(Permission.CAMERA, "Camera", "Camera & Mic", PackageManager.FEATURE_CAMERA_ANY, 26),
            GatePermission(Permission.RECORD_AUDIO, "Microphone", "Camera & Mic", null, 26),
            GatePermission(Permission.BODY_SENSORS, "Body Sensors", "Sensors", PackageManager.FEATURE_SENSOR_HEART_RATE, 26),
            GatePermission(Permission.BODY_SENSORS_BACKGROUND, "Body Sensors Background", "Sensors", PackageManager.FEATURE_SENSOR_HEART_RATE, 34),
            GatePermission(Permission.ACTIVITY_RECOGNITION, "Activity Recognition", "Sensors", PackageManager.FEATURE_SENSOR_STEP_COUNTER, 29),
            GatePermission(Permission.USE_BIOMETRIC, "Biometric", "Sensors", null, 26),
            GatePermission(Permission.READ_CONTACTS, "Read Contacts", "Contacts", null, 26),
            GatePermission(Permission.WRITE_CONTACTS, "Write Contacts", "Contacts", null, 26),
            GatePermission(Permission.GET_ACCOUNTS, "Get Accounts", "Contacts", null, 26),
            GatePermission(Permission.READ_PHONE_STATE, "Read Phone State", "Phone", PackageManager.FEATURE_TELEPHONY, 26),
            GatePermission(Permission.CALL_PHONE, "Call Phone", "Phone", PackageManager.FEATURE_TELEPHONY, 26),
            GatePermission(Permission.READ_CALL_LOG, "Read Call Log", "Phone", PackageManager.FEATURE_TELEPHONY, 26),
            GatePermission(Permission.WRITE_CALL_LOG, "Write Call Log", "Phone", PackageManager.FEATURE_TELEPHONY, 26),
            GatePermission(Permission.ANSWER_PHONE_CALLS, "Answer Calls", "Phone", PackageManager.FEATURE_TELEPHONY, 26),
            GatePermission(Permission.READ_PHONE_NUMBERS, "Read Phone Numbers", "Phone", PackageManager.FEATURE_TELEPHONY, 26),
            GatePermission(Permission.PROCESS_OUTGOING_CALLS, "Process Outgoing Calls", "Phone", PackageManager.FEATURE_TELEPHONY, 26),
            GatePermission(Permission.USE_SIP, "Use SIP", "Phone", PackageManager.FEATURE_TELEPHONY, 26),
            GatePermission(Permission.ACCEPT_HANDOVER, "Accept Handover", "Phone", PackageManager.FEATURE_TELEPHONY, 28),
            GatePermission(Permission.SEND_SMS, "Send SMS", "SMS", PackageManager.FEATURE_TELEPHONY, 26),
            GatePermission(Permission.RECEIVE_SMS, "Receive SMS", "SMS", PackageManager.FEATURE_TELEPHONY, 26),
            GatePermission(Permission.READ_SMS, "Read SMS", "SMS", PackageManager.FEATURE_TELEPHONY, 26),
            GatePermission(Permission.RECEIVE_MMS, "Receive MMS", "SMS", PackageManager.FEATURE_TELEPHONY, 26),
            GatePermission(Permission.RECEIVE_WAP_PUSH, "Receive WAP Push", "SMS", PackageManager.FEATURE_TELEPHONY, 26),
            GatePermission(Permission.READ_CELL_BROADCASTS, "Read Cell Broadcasts", "SMS", PackageManager.FEATURE_TELEPHONY, 26),
            GatePermission(Permission.READ_EXTERNAL_STORAGE, "Read Storage", "Storage", null, 26),
            GatePermission(Permission.WRITE_EXTERNAL_STORAGE, "Write Storage", "Storage", null, 26),
            GatePermission(Permission.READ_MEDIA_IMAGES, "Read Media Images", "Storage", null, 33),
            GatePermission(Permission.READ_MEDIA_VIDEO, "Read Media Video", "Storage", null, 33),
            GatePermission(Permission.READ_MEDIA_AUDIO, "Read Media Audio", "Storage", null, 33),
            GatePermission(Permission.READ_MEDIA_VISUAL_USER_SELECTED, "Read Media Visual User Selected", "Storage", null, 34),
            GatePermission(Permission.NOTIFICATIONS, "Post Notifications", "Notifications", null, 33),
            GatePermission(Permission.BLUETOOTH_SCAN, "Bluetooth Scan", "Bluetooth", PackageManager.FEATURE_BLUETOOTH, 31),
            GatePermission(Permission.BLUETOOTH_CONNECT, "Bluetooth Connect", "Bluetooth", PackageManager.FEATURE_BLUETOOTH, 31),
            GatePermission(Permission.BLUETOOTH_ADVERTISE, "Bluetooth Advertise", "Bluetooth", PackageManager.FEATURE_BLUETOOTH, 31),
            GatePermission(Permission.NEARBY_WIFI_DEVICES, "Nearby WiFi Devices", "WiFi", null, 33),
            GatePermission(Permission.UWB_RANGING, "UWB Ranging", "UWB", PackageManager.FEATURE_UWB, 31),
            GatePermission(Permission.MANAGE_EXTERNAL_STORAGE, "Manage External Storage", "System", null, 26),
            GatePermission(Permission.SYSTEM_ALERT_WINDOW, "Draw Over Other Apps", "System", null, 26),
            GatePermission(Permission.WRITE_SETTINGS, "Write Settings", "System", null, 26),
            GatePermission(Permission.REQUEST_INSTALL_PACKAGES, "Install Packages", "System", null, 26),
            GatePermission(Permission.PACKAGE_USAGE_STATS, "Usage Stats", "System", null, 26),
            GatePermission(Permission.SCHEDULE_ALARMS, "Schedule Exact Alarms", "System", null, 26),
            GatePermission(Permission.ACCESSIBILITY_SERVICE, "Accessibility Service", "System", null, 26),
            GatePermission(Permission.NOTIFICATION_LISTENER, "Notification Access", "System", null, 26),
        )
    }
}