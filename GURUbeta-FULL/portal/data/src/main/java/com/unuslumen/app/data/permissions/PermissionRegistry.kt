// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.permissions

/**
 * PermissionRegistry — the single source of truth for every permission GURU declares.
 *
 * WHY THIS EXISTS. Issue 46 measured the manifest at 166 declarations, 50 of them
 * referenced in Kotlin. The reason that gap could grow unnoticed is that nothing
 * owned the list: each tool guessed its own answer, so `termuxDiagnose` reported a
 * false "not installed" and `checkAdbStatus` invented a status field, both from the
 * same missing table. This is that table.
 *
 * The contract this file keeps with the rest of the app:
 *
 * 1. Every string in the manifest appears exactly once here. A JVM unit test asserts
 *    that, so adding a permission to the manifest without describing it fails the
 *    build rather than shipping an undocumented grant.
 * 2. Every entry states how it is obtained. A permission with no mechanism is a
 *    promise the app cannot keep, which is what issue 46 called "47 permissions that
 *    look like features and are not".
 * 3. Every entry states what it unlocks, in words a model can read. Silence here is
 *    what produced tool descriptions claiming capabilities the code did not have.
 *
 * TIERS ARE HONEST, NOT ASPIRATIONAL. A signature permission is TIER PRIVILEGED even
 * though GURU declares it, because the platform will never hand it to an untrusted
 * app. The Shizuku and Device Owner mechanisms are the routes that make those real,
 * and #173 builds the Shizuku one. Until then the orchestrator says so plainly
 * rather than pretending the grant landed.
 */
object PermissionRegistry {

    private const val P = "android.permission."

    /** Convenience for a normal, install-time grant that nothing has to request. */
    private fun normal(
        name: String,
        tool: String? = null,
        skill: String? = null,
        unlocks: String
    ) = PermissionSpec(P + name, PermissionTier.NORMAL, GrantMechanism.SELF_GUARD, tool, skill, unlocks)

    /** A runtime prompt the user can answer yes to. */
    private fun runtime(
        name: String,
        tool: String? = null,
        skill: String? = null,
        unlocks: String
    ) = PermissionSpec(P + name, PermissionTier.RUNTIME, GrantMechanism.DIALOG, tool, skill, unlocks)

    /** A special app-op granted on a Settings screen, verified by reading the op back. */
    private fun appOp(
        name: String,
        tool: String? = null,
        skill: String? = null,
        unlocks: String
    ) = PermissionSpec(P + name, PermissionTier.APP_OP, GrantMechanism.SETTINGS_INTENT, tool, skill, unlocks)

    /** Signature or privileged: reachable only at shell UID or as Device Owner. */
    private fun privileged(
        name: String,
        mechanism: GrantMechanism = GrantMechanism.SHIZUKU,
        tool: String? = null,
        skill: String? = null,
        unlocks: String
    ) = PermissionSpec(P + name, PermissionTier.PRIVILEGED, mechanism, tool, skill, unlocks)

    /**
     * A BIND_ guard. It is not a capability and grants GURU nothing; it makes one of
     * GURU's own services private so no other app can bind it. Issue 46 concluded
     * these need no tool and no wiring, and leaving them declared is the correct,
     * complete state.
     */
    private fun guard(
        name: String,
        skill: String? = null,
        unlocks: String
    ) = PermissionSpec(P + name, PermissionTier.SERVICE_GUARD, GrantMechanism.SERVICE, null, skill, unlocks)

    /**
     * Every declared permission. Order follows the manifest so a human diffing the
     * two files can read them side by side.
     */
    val all: List<PermissionSpec> = listOf(
        // ===== Calendar =====
        runtime("READ_CALENDAR", "calendarRead", "calendar", "Read the device's calendars and their events."),
        runtime("WRITE_CALENDAR", "calendarWrite", "calendar", "Create, change and delete calendar events."),

        // ===== Alarms & Boot =====
        appOp("SCHEDULE_EXACT_ALARM", "createAlarm", "alarms", "Schedule an alarm or task alarm that fires at an exact time instead of a window."),
        normal("RECEIVE_BOOT_COMPLETED", null, "automation", "Receive the boot broadcast so jobs, hooks and alarms can be restored after a restart."),
        runtime("POST_NOTIFICATIONS", "notify", "notifications", "Show notifications, which is how GURU reports background work and alarms."),
        normal("USE_EXACT_ALARM", "createAlarm", "alarms", "Use exact alarms without asking, which Android grants only to alarm and clock apps."),
        privileged("SET_ALARM", tool = "setSystemAlarm", skill = "alarms", unlocks = "Set an alarm in the system clock app. setAlarmClock reaches the same result without this permission."),
        normal("VIBRATE", "vibrate", "device_control", "Vibrate the device."),
        appOp("REQUEST_IGNORE_BATTERY_OPTIMIZATIONS", null, "automation", "Ask to be exempt from battery optimisation so background work is not frozen."),

        // ===== Network =====
        normal("INTERNET", null, null, "Open network connections at all."),
        normal("ACCESS_NETWORK_STATE", "networkState", "network", "Read whether the device is online and what kind of connection it has."),
        normal("ACCESS_WIFI_STATE", "wifiInfo", "wifi", "Read the Wi-Fi connection state, SSID and link speed."),
        normal("CHANGE_WIFI_STATE", "wifiSetEnabled", "wifi", "Turn Wi-Fi on or off. Android refuses this at app UID, so a Shizuku path is required in practice."),
        normal("CHANGE_WIFI_MULTICAST_STATE", null, "network_discovery", "Hold a multicast lock so mDNS and SSDP discovery can receive replies."),
        normal("CHANGE_NETWORK_STATE", "networkSetEnabled", "network", "Request a change to the device's data connection, which Android blocks for apps."),
        runtime("NEARBY_WIFI_DEVICES", "wifiNearby", "wifi", "Scan for and connect to nearby Wi-Fi devices without needing location."),

        // ===== Storage =====
        appOp("MANAGE_EXTERNAL_STORAGE", null, "storage", "Read and write every file on shared storage, not just GURU's own directories."),
        runtime("READ_EXTERNAL_STORAGE", null, "storage", "Read shared storage on Android 12 and below."),
        runtime("WRITE_EXTERNAL_STORAGE", null, "storage", "Write shared storage on Android 10 and below."),
        runtime("READ_MEDIA_IMAGES", "mediaLibrary", "media_library", "Read image files from the shared media store."),
        runtime("READ_MEDIA_VIDEO", "mediaLibrary", "media_library", "Read video files from the shared media store."),
        runtime("READ_MEDIA_AUDIO", "mediaLibrary", "media_library", "Read audio files from the shared media store."),
        runtime("READ_MEDIA_VISUAL_USER_SELECTED", "mediaLibrary", "media_library", "Read the specific photos and videos the user selected, on Android 14 and above."),

        // ===== Contacts =====
        runtime("READ_CONTACTS", "contactsList", "contacts", "Read names, numbers, emails and photos from the address book."),
        runtime("WRITE_CONTACTS", "contactCreate", "contacts", "Create, change and delete contacts."),
        runtime("GET_ACCOUNTS", "accountsList", "accounts", "List the accounts registered on the device."),

        // ===== Phone =====
        runtime("READ_PHONE_STATE", "phoneState", "phone_state", "Read phone state: network type, SIM state and call status."),
        runtime("CALL_PHONE", "phoneCall", "phone_calls", "Place a call directly from GURU without the user tapping dial."),
        runtime("READ_CALL_LOG", "callLogRead", "call_log", "Read the call history."),
        runtime("WRITE_CALL_LOG", "callLogWrite", "call_log", "Write entries into the call history."),
        runtime("ANSWER_PHONE_CALLS", "answerCall", "call_control", "Answer and end incoming calls."),
        normal("MANAGE_OWN_CALLS", "registerCallAccount", "call_control", "Register GURU as a calling account so it can place calls through its own connection."),
        runtime("READ_PHONE_NUMBERS", "phoneNumbers", "phone_state", "Read the device's own phone number or numbers."),
        privileged("PROCESS_OUTGOING_CALLS", tool = "outgoingCallWatch", skill = "call_control", unlocks = "Observe outgoing calls before they are placed. A CallScreeningService reaches the same result through a role."),
        runtime("USE_SIP", "sipStatus", "sip_calls", "Place and receive SIP calls."),
        runtime("ACCEPT_HANDOVER", "handoverCall", "call_control", "Accept a call handed over from another app."),

        // ===== SMS =====
        runtime("SEND_SMS", "smsSend", "sms", "Send a text message."),
        runtime("RECEIVE_SMS", "smsWatch", "sms", "Receive incoming text messages."),
        runtime("READ_SMS", "smsRead", "sms", "Read stored text messages."),
        runtime("RECEIVE_MMS", null, "sms", "Receive multimedia messages."),
        privileged("RECEIVE_WAP_PUSH", tool = null, skill = "emergency_alerts", unlocks = "Receive WAP push messages, which carrier alerts use. Not reachable at app UID."),
        privileged("READ_CELL_BROADCASTS", tool = "cellBroadcasts", skill = "emergency_alerts", unlocks = "Read emergency cell broadcasts. The unprivileged path is the cell broadcast receiver."),

        // ===== Location =====
        runtime("ACCESS_FINE_LOCATION", null, "location", "Read precise GPS location."),
        runtime("ACCESS_COARSE_LOCATION", null, "location", "Read approximate location when precise is denied."),
        runtime("ACCESS_BACKGROUND_LOCATION", "locationWatch", "location", "Read location while GURU is not in the foreground."),
        runtime("ACCESS_MEDIA_LOCATION", "photoLocation", "media_library", "Read the GPS coordinates embedded in photos."),

        // ===== Camera & Microphone =====
        runtime("CAMERA", null, "camera", "Take photos and record video, and control the torch."),
        runtime("RECORD_AUDIO", null, "audio_capture", "Record audio from the microphone."),

        // ===== Sensors =====
        runtime("BODY_SENSORS", "bodySensors", "sensors", "Read heart rate and other body sensors."),
        runtime("BODY_SENSORS_BACKGROUND", null, "sensors", "Read body sensors while GURU is in the background."),
        runtime("ACTIVITY_RECOGNITION", "activityRecognition", "sensors", "Tell walking, running, cycling and driving apart."),
        normal("HIGH_SAMPLING_RATE_SENSORS", "sensorStream", "sensors", "Sample motion sensors above the default rate."),
        normal("USE_BIOMETRIC", "biometricAuth", "security", "Gate a destructive action behind the fingerprint or face prompt."),
        normal("USE_FINGERPRINT", null, "security", "The pre-Android-9 spelling of the biometric permission, kept for old devices."),

        // ===== Bluetooth =====
        normal("BLUETOOTH", null, "bluetooth", "The legacy Bluetooth permission used on Android 11 and below."),
        normal("BLUETOOTH_ADMIN", null, "bluetooth", "The legacy permission to change Bluetooth state, on Android 11 and below."),
        runtime("BLUETOOTH_CONNECT", null, "bluetooth", "Connect to paired Bluetooth devices."),
        runtime("BLUETOOTH_SCAN", null, "bluetooth", "Discover nearby Bluetooth devices."),
        runtime("BLUETOOTH_ADVERTISE", "bluetoothAdvertise", "bluetooth", "Advertise GURU to nearby Bluetooth devices."),

        // ===== NFC =====
        normal("NFC", "nfcRead", "nfc", "Use the NFC radio at all."),
        privileged("NFC_TRANSACTION_EVENT", tool = "nfcTransactions", skill = "nfc", unlocks = "Observe raw NFC transaction events, which the reader API covers without this."),

        // ===== UWB =====
        runtime("UWB_RANGING", "uwbRange", "sensors", "Measure precise distance to an ultra-wideband device."),

        // ===== System Settings =====
        appOp("WRITE_SETTINGS", "settingsWrite", "system_settings", "Change system settings such as brightness, ringtone and timeout."),
        privileged("WRITE_SECURE_SETTINGS", tool = "secureSettingsWrite", skill = "system_settings", unlocks = "Change secure settings such as location mode and developer options."),
        appOp("SYSTEM_ALERT_WINDOW", "overlayCreate", "overlays", "Draw a floating window over other apps."),
        appOp("REQUEST_INSTALL_PACKAGES", null, "apps", "Ask the user to install an APK GURU has downloaded."),
        normal("REQUEST_DELETE_PACKAGES", null, "apps", "Ask the system to uninstall a package, which shows the standard uninstall dialog."),
        privileged("INSTALL_PACKAGES", mechanism = GrantMechanism.DEVICE_OWNER, tool = "installApkSilent", skill = "apps", unlocks = "Install an APK with no user interaction, which needs Device Owner or shell UID."),
        privileged("DELETE_PACKAGES", mechanism = GrantMechanism.DEVICE_OWNER, tool = "uninstallAppSilent", skill = "apps", unlocks = "Uninstall a package with no user interaction, which needs Device Owner or shell UID."),
        normal("QUERY_ALL_PACKAGES", null, "apps", "See every installed package rather than only the ones Android deems visible."),
        normal("GET_PACKAGE_SIZE", "appSizes", "apps", "Read how much storage each app uses."),
        appOp("PACKAGE_USAGE_STATS", "appUsage", "app_usage", "Read app usage history, which is also the unprivileged replacement for seeing running tasks."),
        privileged("MANAGE_APP_OPS_MODES", tool = "appOps", skill = "permission_management", unlocks = "Read and change app-op modes for any package."),

        // ===== Power & Device =====
        normal("WAKE_LOCK", "keepAwake", "device_control", "Hold a wake lock so the CPU keeps running while GURU works."),
        privileged("DEVICE_POWER", tool = "powerControl", skill = "device_power", unlocks = "Reboot, power off and control the display state directly."),
        privileged("REBOOT", tool = "deviceReboot", skill = "device_power", unlocks = "Reboot the device without a user tap."),
        privileged("SHUTDOWN", tool = "deviceShutdown", skill = "device_power", unlocks = "Power the device off without a user tap."),
        privileged("FORCE_STOP_PACKAGES", tool = "forceStopApp", skill = "apps", unlocks = "Stop another app's processes outright."),
        normal("KILL_BACKGROUND_PROCESSES", "killBackgroundApp", "apps", "Ask the system to kill another app's background processes, which it may refuse."),
        privileged("SET_PROCESS_LIMIT", tool = "processLimit", skill = "device_power", unlocks = "Set the number of background processes the system keeps."),
        privileged("SET_TIME", tool = "timeSet", skill = "system_settings", unlocks = "Set the system clock."),
        privileged("SET_TIME_ZONE", tool = "timeSet", skill = "system_settings", unlocks = "Set the system time zone. The unprivileged AlarmManager path also exists."),
        privileged("CHANGE_CONFIGURATION", tool = "configurationChange", skill = "system_settings", unlocks = "Change global device configuration such as locale and density."),
        normal("MODIFY_AUDIO_SETTINGS", "audioRoute", "audio_control", "Change audio routing, volume and mode."),
        privileged("MASTER_CLEAR", mechanism = GrantMechanism.DEVICE_OWNER, tool = "factoryReset", skill = "device_power", unlocks = "Factory reset the device, which is a destructive Device Owner operation."),
        privileged("MOUNT_UNMOUNT_FILESYSTEMS", tool = null, skill = "storage", unlocks = "Mount and unmount filesystems, which needs system UID."),
        privileged("MOUNT_FORMAT_FILESYSTEMS", tool = null, skill = "storage", unlocks = "Format filesystems, which needs system UID."),

        // ===== Display =====
        privileged("SET_WALLPAPER", tool = "setWallpaper", skill = "device_control", unlocks = "Set the wallpaper. WallpaperManager does this with no permission at all."),
        normal("SET_WALLPAPER_HINTS", null, "wallpaper", "Set the wallpaper dimensions hint."),
        normal("EXPAND_STATUS_BAR", "expandStatusBar", "device_control", "Open and close the notification shade, which needs no privilege."),
        appOp("MEDIA_PROJECTION", null, "screen_capture", "Capture the screen. The grant is the user's consent dialog, backed by a foreground service."),

        // ===== Input =====
        privileged("INJECT_EVENTS", tool = "injectInput", skill = "ui_automation", unlocks = "Inject taps and keystrokes with no accessibility service, which needs shell UID."),

        // ===== App Management =====
        privileged("GET_TASKS", tool = "runningTasks", skill = "app_usage", unlocks = "List running tasks. UsageStatsManager is the unprivileged replacement."),
        normal("REORDER_TASKS", "reorderTasks", "app_usage", "Bring a task to the front."),
        privileged("REAL_GET_TASKS", tool = "runningTasks", skill = "app_usage", unlocks = "The unrestricted form of task listing, which needs system UID."),
        privileged("START_ANY_ACTIVITY", tool = "startAnyActivity", skill = "apps", unlocks = "Start any activity in any app without that app's cooperation."),
        privileged("START_ACTIVITIES_FROM_BACKGROUND", tool = "launchFromBackground", skill = "apps", unlocks = "Start an activity while GURU is in the background, which Android blocks by default."),
        privileged("INTERACT_ACROSS_USERS", tool = "multiUserAccess", skill = "system_introspection", unlocks = "Act in another user profile."),
        privileged("INTERACT_ACROSS_USERS_FULL", tool = "multiUserAccess", skill = "system_introspection", unlocks = "The unrestricted form of cross-profile interaction, which needs system UID."),
        privileged("MANAGE_USERS", mechanism = GrantMechanism.DEVICE_OWNER, tool = "userManage", skill = "system_introspection", unlocks = "Create, remove and configure user profiles."),
        privileged("CREATE_USERS", mechanism = GrantMechanism.DEVICE_OWNER, tool = "userManage", skill = "system_introspection", unlocks = "Create a new user profile."),
        privileged("CLEAR_APP_CACHE", tool = "clearAppCache", skill = "apps", unlocks = "Clear another app's cache, which is otherwise that app's own business."),

        // ===== Accounts & Authentication =====
        privileged("AUTHENTICATE_ACCOUNTS", tool = "accountAuthenticate", skill = "accounts", unlocks = "Act as an account authenticator, which AccountManager covers without this."),
        privileged("MANAGE_ACCOUNTS", tool = "accountsManage", skill = "accounts", unlocks = "Add and remove accounts on the device."),
        privileged("USE_CREDENTIALS", tool = "accountToken", skill = "accounts", unlocks = "Request an auth token for an account."),

        // ===== Logs & Diagnostics =====
        privileged("READ_LOGS", tool = "readLogs", skill = "diagnostics", unlocks = "Read the system log, which has been restricted to privileged callers since Jelly Bean."),
        privileged("DUMP", tool = "systemDump", skill = "system_introspection", unlocks = "Run dumpsys and read the system's own service state. This is the fix for pmDump's false success in issue 44."),
        privileged("DIAGNOSTIC", mechanism = GrantMechanism.DEVICE_OWNER, tool = "diagnosticMode", skill = "diagnostics", unlocks = "Run vendor diagnostics, which is Device Owner only."),
        privileged("BATTERY_STATS", tool = "batteryDetail", skill = "device_power", unlocks = "Read detailed battery statistics. BatteryManager covers the common cases without it."),
        privileged("READ_PRECISE_PHONE_STATE", tool = "precisePhoneState", skill = "phone_state", unlocks = "Read precise radio state such as signal strength in dBm per SIM."),

        // ===== VPN & Network =====
        privileged("CONTROL_VPN", tool = "vpnControl", skill = "network", unlocks = "Control the device's VPN connections."),
        privileged("MANAGE_IPSEC_TUNNELS", tool = "ipsecTunnels", skill = "network", unlocks = "Create and manage IPsec tunnels."),
        privileged("CAPTURE_AUDIO_OUTPUT", tool = "captureOutput", skill = "audio_capture", unlocks = "Capture audio played by other apps."),
        privileged("CAPTURE_SECURE_VIDEO_OUTPUT", tool = "captureSecureOutput", skill = "screen_capture", unlocks = "Capture video that is marked secure, such as DRM playback."),
        privileged("CAPTURE_VIDEO_OUTPUT", tool = "captureVideoOutput", skill = "screen_capture", unlocks = "Capture other apps' video output directly."),

        // ===== Factory Test =====
        privileged("FACTORY_TEST", tool = "factoryTest", skill = "diagnostics", unlocks = "Run factory test mode, which only vendor builds ever hold."),

        // ===== Accessibility =====
        guard("BIND_ACCESSIBILITY_SERVICE", skill = "ui_automation", unlocks = "Makes GURU's accessibility service bindable by nobody but the system."),
        guard("BIND_NOTIFICATION_LISTENER_SERVICE", skill = "notifications", unlocks = "Makes GURU's notification listener bindable by nobody but the system."),

        // ===== Foreground Service =====
        normal("FOREGROUND_SERVICE", null, "background_work", "Run any foreground service at all."),
        normal("FOREGROUND_SERVICE_SPECIAL_USE", null, "background_work", "Run a foreground service for a purpose the other types do not cover, which is what Tor and Cerebrum use."),
        normal("FOREGROUND_SERVICE_MEDIA_PROJECTION", null, "screen_capture", "Keep screen capture alive in a foreground service."),
        normal("FOREGROUND_SERVICE_CAMERA", null, "background_work", "Use the camera from a foreground service."),
        normal("FOREGROUND_SERVICE_MICROPHONE", null, "background_work", "Use the microphone from a foreground service."),
        normal("FOREGROUND_SERVICE_LOCATION", null, "background_work", "Read location from a foreground service."),
        normal("FOREGROUND_SERVICE_DATA_SYNC", null, "background_work", "Sync data in a foreground service, which Android 15 limits to about six hours."),
        normal("FOREGROUND_SERVICE_MEDIA_PLAYBACK", null, "background_work", "Play media from a foreground service."),
        normal("FOREGROUND_SERVICE_PHONE_CALL", null, "background_work", "Keep a call alive in a foreground service."),
        privileged("INSTANT_APP_FOREGROUND_SERVICE", tool = null, skill = "background_work", unlocks = "Run a foreground service from an instant app, which GURU is not."),

        // ===== Device Admin =====
        guard("BIND_DEVICE_ADMIN", skill = "device_admin", unlocks = "Makes GURU's device admin receiver bindable by nobody but the system. The Device Owner path in #172 depends on it."),

        // ===== Input Method =====
        guard("BIND_INPUT_METHOD", skill = "keyboard", unlocks = "Makes GURU's keyboard bindable by nobody but the system."),

        // ===== Text Service =====
        guard("BIND_TEXT_SERVICE", skill = "text_actions", unlocks = "Makes GURU's text action service bindable by nobody but the system."),

        // ===== Wallpaper =====
        guard("BIND_WALLPAPER", skill = "wallpaper", unlocks = "Makes GURU's live wallpaper bindable by nobody but the system."),

        // ===== Notification =====
        appOp("ACCESS_NOTIFICATION_POLICY", "dndControl", "notifications", "Change do-not-disturb state and notification policy, which is the 'I am going to bed' capability."),

        // ===== Dream =====
        guard("BIND_DREAM_SERVICE", skill = "wallpaper", unlocks = "Makes GURU's screensaver bindable by nobody but the system."),

        // ===== Print =====
        guard("BIND_PRINT_SERVICE", skill = "printing", unlocks = "Makes GURU's print service bindable by nobody but the system."),

        // ===== Quick Settings =====
        guard("BIND_QUICK_SETTINGS_TILE", skill = "quick_tiles", unlocks = "Makes GURU's quick settings tile bindable by nobody but the system."),

        // ===== Voice Interaction =====
        guard("BIND_VOICE_INTERACTION", skill = "assistant_role", unlocks = "Makes GURU's voice interaction service bindable by nobody but the system."),

        // ===== Remote =====
        guard("BIND_TV_INPUT", unlocks = "Makes a TV input service private to GURU. GURU is not a TV input, so this declaration buys nothing."),
        guard("BIND_REMOTEVIEWS", unlocks = "Makes a RemoteViews service private to GURU, used by app widgets."),

        // ===== Carrier =====
        guard("BIND_CARRIER_SERVICES", unlocks = "Makes GURU's carrier service bindable by nobody but the system."),
        guard("BIND_CARRIER_MESSAGING_SERVICE", unlocks = "Makes GURU's carrier messaging service bindable by nobody but the system."),
        guard("BIND_TELECOM_CONNECTION_SERVICE", skill = "call_control", unlocks = "Makes GURU's telecom connection service bindable by nobody but the system."),

        // ===== Incall =====
        guard("BIND_INCALL_SERVICE", skill = "call_control", unlocks = "Makes GURU's in-call service bindable by nobody but the system."),

        // ===== Screen =====
        guard("BIND_SCREENING_SERVICE", skill = "call_control", unlocks = "Makes GURU's call screening service bindable by nobody but the system."),

        // ===== Autofill =====
        guard("BIND_AUTOFILL_SERVICE", skill = "autofill", unlocks = "Makes GURU's autofill service bindable by nobody but the system."),

        // ===== VR =====
        guard("BIND_VR_LISTENER_SERVICE", unlocks = "Makes GURU's VR listener bindable by nobody but the system. GURU has no VR feature."),

        // ===== Visual Voicemail =====
        guard("BIND_VISUAL_VOICEMAIL_SERVICE", unlocks = "Makes GURU's visual voicemail service bindable by nobody but the system."),

        // ===== Trust Agent =====
        guard("BIND_TRUST_AGENT", unlocks = "Makes GURU's trust agent bindable by nobody but the system."),

        // ===== Companion =====
        guard("BIND_COMPANION_DEVICE_SERVICE", skill = "bluetooth", unlocks = "Makes GURU's companion device service bindable by nobody but the system."),

        // ===== Slice =====
        guard("BIND_SLICE", unlocks = "Makes GURU's slice provider bindable by nobody but the system."),

        // ===== Call Redirection =====
        guard("BIND_CALL_REDIRECTION_SERVICE", skill = "call_control", unlocks = "Makes GURU's call redirection service bindable by nobody but the system."),

        // ===== Content Capture =====
        guard("BIND_CONTENT_CAPTURE_SERVICE", skill = "ui_automation", unlocks = "Makes GURU's content capture service bindable by nobody but the system."),

        // ===== Augmented Reality =====
        guard("BIND_AUGMENTED_AUTOFILL_SERVICE", skill = "autofill", unlocks = "Makes GURU's augmented autofill service bindable by nobody but the system."),

        // ===== App Predictions =====
        guard("BIND_APP_PREDICTION_SERVICE", skill = "apps", unlocks = "Makes GURU's app prediction service bindable by nobody but the system."),

        // ===== Time Zone =====
        guard("BIND_TIME_ZONE_DETECTOR_SERVICE", unlocks = "Makes GURU's time zone detector bindable by nobody but the system."),

        // ===== Cross Profile =====
        guard("BIND_CROSS_PROFILE_APPS_SERVICE", unlocks = "Makes GURU's cross-profile apps service bindable by nobody but the system."),

        // ===== Credential Provider =====
        guard("BIND_CREDENTIAL_PROVIDER_SERVICE", skill = "autofill", unlocks = "Makes GURU's credential provider bindable by nobody but the system."),

        // ===== Device Lock =====
        guard("BIND_DEVICE_LOCK_SERVICE", unlocks = "Makes GURU's device lock service bindable by nobody but the system."),

        // ===== Health =====
        guard("BIND_HEALTH_CONNECT_SERVICE", skill = "health", unlocks = "Makes GURU's Health Connect service bindable by nobody but the system."),

        // ===== Tile Service =====
        guard("BIND_TILE_SERVICE", skill = "quick_tiles", unlocks = "Makes GURU's tile service bindable by nobody but the system."),

        // ===== Game =====
        guard("BIND_GAME_SERVICE", unlocks = "Makes GURU's game service bindable by nobody but the system. GURU has no game mode."),

        // ===== Satellite =====
        guard("BIND_SATELLITE_SERVICE", unlocks = "Makes GURU's satellite service bindable by nobody but the system."),

        // ===== Wallet =====
        guard("BIND_WALLET_SERVICE", unlocks = "Makes GURU's wallet service bindable by nobody but the system."),

        // ===== Thread Network =====
        guard("BIND_THREAD_NETWORK_SERVICE", unlocks = "Makes GURU's thread network service bindable by nobody but the system."),

        // ===== UWB =====
        guard("BIND_UWB_SERVICE", unlocks = "Makes GURU's UWB service bindable by nobody but the system."),

        // ===== Virtual Device =====
        guard("BIND_VIRTUAL_DEVICE_SERVICE", unlocks = "Makes GURU's virtual device service bindable by nobody but the system.")
    )

    /** Index by platform string, for O(1) lookup from the dispatcher. */
    private val byPermission: Map<String, PermissionSpec> = all.associateBy { it.permission }

    /** Index by bare name, so a message can say CAMERA rather than the full string. */
    private val byBareName: Map<String, PermissionSpec> = all.associateBy { it.bareName }

    fun specFor(permission: String): PermissionSpec? =
        byPermission[permission] ?: byBareName[permission]

    fun specForOrNull(permission: String): PermissionSpec? = specFor(permission)

    /**
     * Every permission whose declared owning tool is non-null. This is the set that
     * has an actual consumer, which is what issue 46 called the 50 referenced ones.
     */
    fun wired(): List<PermissionSpec> = all.filter { it.owningTool != null }

    /**
     * Permissions with no consumer. Not automatically a problem: a BIND_ guard or a
     * permission held for a system service legitimately has no tool.
     */
    fun unwired(): List<PermissionSpec> = all.filter { it.owningTool == null }

    fun byTier(tier: PermissionTier): List<PermissionSpec> = all.filter { it.tier == tier }

    fun byMechanism(mechanism: GrantMechanism): List<PermissionSpec> =
        all.filter { it.mechanism == mechanism }

    /** The count, asserted by a unit test against the manifest. */
    val size: Int get() = all.size
}
