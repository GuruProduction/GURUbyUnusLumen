package com.unuslumen.app.util.permissions

/*
TODO: Implement when migrating to Kotlin Multiplatform

@Composable
expect fun rememberPermissionState(
    permission: Permission,
): PermissionState
 */

interface PermissionState {

    var isGranted: Boolean

    var shouldShowRationale: Boolean

    fun launchRequest()

    fun refresh()

    fun openAppSettings()
}


enum class Permission {
    // Calendar
    READ_CALENDAR,
    WRITE_CALENDAR,

    // Alarms & Boot
    SCHEDULE_ALARMS,
    NOTIFICATIONS,

    // Network
    INTERNET,
    ACCESS_NETWORK_STATE,
    ACCESS_WIFI_STATE,
    CHANGE_WIFI_STATE,

    // Storage
    MANAGE_EXTERNAL_STORAGE,
    READ_EXTERNAL_STORAGE,
    WRITE_EXTERNAL_STORAGE,
    READ_MEDIA_IMAGES,
    READ_MEDIA_VIDEO,
    READ_MEDIA_AUDIO,
    READ_MEDIA_VISUAL_USER_SELECTED,

    // Contacts
    READ_CONTACTS,
    WRITE_CONTACTS,
    GET_ACCOUNTS,

    // Phone
    READ_PHONE_STATE,
    CALL_PHONE,
    READ_CALL_LOG,
    WRITE_CALL_LOG,
    ANSWER_PHONE_CALLS,
    READ_PHONE_NUMBERS,
    PROCESS_OUTGOING_CALLS,
    USE_SIP,
    ACCEPT_HANDOVER,

    // SMS
    SEND_SMS,
    RECEIVE_SMS,
    READ_SMS,
    RECEIVE_MMS,
    RECEIVE_WAP_PUSH,
    READ_CELL_BROADCASTS,

    // Location
    ACCESS_FINE_LOCATION,
    ACCESS_COARSE_LOCATION,
    ACCESS_BACKGROUND_LOCATION,
    ACCESS_MEDIA_LOCATION,

    // Camera & Microphone
    CAMERA,
    RECORD_AUDIO,

    // Sensors
    BODY_SENSORS,
    BODY_SENSORS_BACKGROUND,
    ACTIVITY_RECOGNITION,
    USE_BIOMETRIC,
    USE_FINGERPRINT,

    // Bluetooth
    BLUETOOTH,
    BLUETOOTH_CONNECT,
    BLUETOOTH_SCAN,
    BLUETOOTH_ADVERTISE,

    // NFC
    NFC,

    // WiFi
    NEARBY_WIFI_DEVICES,

    // UWB
    UWB_RANGING,

    // System
    WRITE_SETTINGS,
    SYSTEM_ALERT_WINDOW,
    REQUEST_INSTALL_PACKAGES,
    PACKAGE_USAGE_STATS,
    QUERY_ALL_PACKAGES,

    // Accessibility
    ACCESSIBILITY_SERVICE,
    NOTIFICATION_LISTENER,
}