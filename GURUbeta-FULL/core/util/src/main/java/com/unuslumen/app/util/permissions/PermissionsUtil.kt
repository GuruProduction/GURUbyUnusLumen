package com.unuslumen.app.util.permissions

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings

@SuppressLint("InlinedApi")
fun Permission.toAndroidPermission(): String = when (this) {
    // Calendar
    Permission.READ_CALENDAR -> Manifest.permission.READ_CALENDAR
    Permission.WRITE_CALENDAR -> Manifest.permission.WRITE_CALENDAR

    // Alarms
    Permission.SCHEDULE_ALARMS -> ""
    Permission.NOTIFICATIONS -> Manifest.permission.POST_NOTIFICATIONS

    // Network
    Permission.INTERNET -> Manifest.permission.INTERNET
    Permission.ACCESS_NETWORK_STATE -> Manifest.permission.ACCESS_NETWORK_STATE
    Permission.ACCESS_WIFI_STATE -> Manifest.permission.ACCESS_WIFI_STATE
    Permission.CHANGE_WIFI_STATE -> Manifest.permission.CHANGE_WIFI_STATE

    // Storage
    Permission.MANAGE_EXTERNAL_STORAGE -> ""
    Permission.READ_EXTERNAL_STORAGE -> Manifest.permission.READ_EXTERNAL_STORAGE
    Permission.WRITE_EXTERNAL_STORAGE -> Manifest.permission.WRITE_EXTERNAL_STORAGE
    Permission.READ_MEDIA_IMAGES -> Manifest.permission.READ_MEDIA_IMAGES
    Permission.READ_MEDIA_VIDEO -> Manifest.permission.READ_MEDIA_VIDEO
    Permission.READ_MEDIA_AUDIO -> Manifest.permission.READ_MEDIA_AUDIO
    Permission.READ_MEDIA_VISUAL_USER_SELECTED -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED else ""

    // Contacts
    Permission.READ_CONTACTS -> Manifest.permission.READ_CONTACTS
    Permission.WRITE_CONTACTS -> Manifest.permission.WRITE_CONTACTS
    Permission.GET_ACCOUNTS -> Manifest.permission.GET_ACCOUNTS

    // Phone
    Permission.READ_PHONE_STATE -> Manifest.permission.READ_PHONE_STATE
    Permission.CALL_PHONE -> Manifest.permission.CALL_PHONE
    Permission.READ_CALL_LOG -> Manifest.permission.READ_CALL_LOG
    Permission.WRITE_CALL_LOG -> Manifest.permission.WRITE_CALL_LOG
    Permission.ANSWER_PHONE_CALLS -> Manifest.permission.ANSWER_PHONE_CALLS
    Permission.READ_PHONE_NUMBERS -> Manifest.permission.READ_PHONE_NUMBERS
    Permission.PROCESS_OUTGOING_CALLS -> Manifest.permission.PROCESS_OUTGOING_CALLS
    Permission.USE_SIP -> Manifest.permission.USE_SIP
    Permission.ACCEPT_HANDOVER -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) Manifest.permission.ACCEPT_HANDOVER else ""

    // SMS
    Permission.SEND_SMS -> Manifest.permission.SEND_SMS
    Permission.RECEIVE_SMS -> Manifest.permission.RECEIVE_SMS
    Permission.READ_SMS -> Manifest.permission.READ_SMS
    Permission.RECEIVE_MMS -> Manifest.permission.RECEIVE_MMS
    Permission.RECEIVE_WAP_PUSH -> Manifest.permission.RECEIVE_WAP_PUSH
    Permission.READ_CELL_BROADCASTS -> "android.permission.READ_CELL_BROADCASTS"

    // Location
    Permission.ACCESS_FINE_LOCATION -> Manifest.permission.ACCESS_FINE_LOCATION
    Permission.ACCESS_COARSE_LOCATION -> Manifest.permission.ACCESS_COARSE_LOCATION
    Permission.ACCESS_BACKGROUND_LOCATION -> Manifest.permission.ACCESS_BACKGROUND_LOCATION
    Permission.ACCESS_MEDIA_LOCATION -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) Manifest.permission.ACCESS_MEDIA_LOCATION else ""

    // Camera & Microphone
    Permission.CAMERA -> Manifest.permission.CAMERA
    Permission.RECORD_AUDIO -> Manifest.permission.RECORD_AUDIO

    // Sensors
    Permission.BODY_SENSORS -> Manifest.permission.BODY_SENSORS
    Permission.BODY_SENSORS_BACKGROUND -> Manifest.permission.BODY_SENSORS_BACKGROUND
    Permission.ACTIVITY_RECOGNITION -> Manifest.permission.ACTIVITY_RECOGNITION
    Permission.USE_BIOMETRIC -> Manifest.permission.USE_BIOMETRIC
    Permission.USE_FINGERPRINT -> Manifest.permission.USE_FINGERPRINT

    // Bluetooth
    Permission.BLUETOOTH -> Manifest.permission.BLUETOOTH
    Permission.BLUETOOTH_CONNECT -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Manifest.permission.BLUETOOTH_CONNECT else Manifest.permission.BLUETOOTH
    Permission.BLUETOOTH_SCAN -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Manifest.permission.BLUETOOTH_SCAN else Manifest.permission.BLUETOOTH
    Permission.BLUETOOTH_ADVERTISE -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Manifest.permission.BLUETOOTH_ADVERTISE else ""

    // NFC
    Permission.NFC -> Manifest.permission.NFC

    // WiFi
    Permission.NEARBY_WIFI_DEVICES -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.NEARBY_WIFI_DEVICES else ""

    // UWB
    Permission.UWB_RANGING -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Manifest.permission.UWB_RANGING else ""

    // System (special permissions handled via Settings intents)
    Permission.WRITE_SETTINGS -> ""
    Permission.SYSTEM_ALERT_WINDOW -> ""
    Permission.REQUEST_INSTALL_PACKAGES -> ""
    Permission.PACKAGE_USAGE_STATS -> ""
    Permission.QUERY_ALL_PACKAGES -> Manifest.permission.QUERY_ALL_PACKAGES

    // Accessibility (special)
    Permission.ACCESSIBILITY_SERVICE -> ""
    Permission.NOTIFICATION_LISTENER -> ""
}

/**
 * Whether this permission is a "special" permission that cannot be requested
 * via ActivityCompat.requestPermissions and instead needs a Settings intent.
 */
val Permission.isSpecial: Boolean get() = when (this) {
    Permission.MANAGE_EXTERNAL_STORAGE,
    Permission.WRITE_SETTINGS,
    Permission.SYSTEM_ALERT_WINDOW,
    Permission.REQUEST_INSTALL_PACKAGES,
    Permission.PACKAGE_USAGE_STATS,
    Permission.ACCESSIBILITY_SERVICE,
    Permission.NOTIFICATION_LISTENER,
    Permission.SCHEDULE_ALARMS -> true
    else -> false
}

/**
 * Check if a special permission is currently granted.
 */
fun Permission.isSpecialGranted(context: Context): Boolean = when (this) {
    Permission.MANAGE_EXTERNAL_STORAGE ->
        Build.VERSION.SDK_INT < Build.VERSION_CODES.R ||
        Environment.isExternalStorageManager()
    Permission.WRITE_SETTINGS ->
        Settings.System.canWrite(context)
    Permission.SYSTEM_ALERT_WINDOW ->
        Settings.canDrawOverlays(context)
    Permission.REQUEST_INSTALL_PACKAGES ->
        context.packageManager.canRequestPackageInstalls()
    Permission.PACKAGE_USAGE_STATS ->
        try {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as android.app.AppOpsManager
            val mode = appOps.unsafeCheckOpNoThrow(
                android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(),
                context.packageName
            )
            mode == android.app.AppOpsManager.MODE_ALLOWED
        } catch (e: Exception) { false }
    Permission.ACCESSIBILITY_SERVICE ->
        try {
            val enabledServices = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: ""
            enabledServices.contains(context.packageName)
        } catch (e: Exception) { false }
    Permission.NOTIFICATION_LISTENER ->
        try {
            val enabledListeners = Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners"
            ) ?: ""
            enabledListeners.contains(context.packageName)
        } catch (e: Exception) { false }
    Permission.SCHEDULE_ALARMS ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                val am = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
                am.canScheduleExactAlarms()
            } catch (e: Exception) { false }
        } else true
    else -> false
}

/**
 * Get the Settings intent to request a special permission.
 */
fun Permission.toSettingsIntent(context: Context): Intent {
    return when (this) {
        Permission.MANAGE_EXTERNAL_STORAGE ->
            Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                data = Uri.parse("package:" + context.packageName)
            }
        Permission.WRITE_SETTINGS ->
            Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                data = Uri.parse("package:" + context.packageName)
            }
        Permission.SYSTEM_ALERT_WINDOW ->
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                data = Uri.parse("package:" + context.packageName)
            }
        Permission.REQUEST_INSTALL_PACKAGES ->
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:" + context.packageName)
            }
        Permission.PACKAGE_USAGE_STATS ->
            Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
        Permission.ACCESSIBILITY_SERVICE ->
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        Permission.NOTIFICATION_LISTENER ->
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        Permission.SCHEDULE_ALARMS ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                    data = Uri.parse("package:" + context.packageName)
                }
            else Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:" + context.packageName)
            }
        else -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:" + context.packageName)
        }
    }
}

/**
 * All standard runtime permissions that GURU should request.
 * These can be requested in a single batch via ActivityCompat.requestPermissions.
 */
val standardRuntimePermissions: List<Permission> = listOf(
    Permission.READ_CALENDAR,
    Permission.WRITE_CALENDAR,
    Permission.NOTIFICATIONS,
    Permission.READ_MEDIA_IMAGES,
    Permission.READ_MEDIA_VIDEO,
    Permission.READ_MEDIA_AUDIO,
    Permission.READ_MEDIA_VISUAL_USER_SELECTED,
    Permission.READ_CONTACTS,
    Permission.WRITE_CONTACTS,
    Permission.GET_ACCOUNTS,
    Permission.READ_PHONE_STATE,
    Permission.CALL_PHONE,
    Permission.READ_CALL_LOG,
    Permission.WRITE_CALL_LOG,
    Permission.ANSWER_PHONE_CALLS,
    Permission.READ_PHONE_NUMBERS,
    Permission.PROCESS_OUTGOING_CALLS,
    Permission.USE_SIP,
    Permission.ACCEPT_HANDOVER,
    Permission.SEND_SMS,
    Permission.RECEIVE_SMS,
    Permission.READ_SMS,
    Permission.RECEIVE_MMS,
    Permission.RECEIVE_WAP_PUSH,
    Permission.READ_CELL_BROADCASTS,
    Permission.ACCESS_FINE_LOCATION,
    Permission.ACCESS_COARSE_LOCATION,
    Permission.ACCESS_MEDIA_LOCATION,
    Permission.CAMERA,
    Permission.RECORD_AUDIO,
    Permission.BODY_SENSORS,
    Permission.BODY_SENSORS_BACKGROUND,
    Permission.ACTIVITY_RECOGNITION,
    Permission.USE_BIOMETRIC,
    Permission.USE_FINGERPRINT,
    Permission.BLUETOOTH_CONNECT,
    Permission.BLUETOOTH_SCAN,
    Permission.BLUETOOTH_ADVERTISE,
    Permission.NEARBY_WIFI_DEVICES,
    Permission.UWB_RANGING,
    Permission.QUERY_ALL_PACKAGES,
)

/**
 * All special permissions that need Settings intents.
 * These must be requested one at a time by opening the relevant Settings screen.
 */
val specialPermissions: List<Permission> = listOf(
    Permission.MANAGE_EXTERNAL_STORAGE,
    Permission.SYSTEM_ALERT_WINDOW,
    Permission.REQUEST_INSTALL_PACKAGES,
    Permission.PACKAGE_USAGE_STATS,
    Permission.WRITE_SETTINGS,
    Permission.ACCESSIBILITY_SERVICE,
    Permission.NOTIFICATION_LISTENER,
    Permission.SCHEDULE_ALARMS,
)

/**
 * Background location is special: on API 29+ it must be requested AFTER
 * fine/coarse location is granted, as a separate request.
 */
val backgroundLocationPermissions: List<Permission> = listOf(
    Permission.ACCESS_BACKGROUND_LOCATION,
)