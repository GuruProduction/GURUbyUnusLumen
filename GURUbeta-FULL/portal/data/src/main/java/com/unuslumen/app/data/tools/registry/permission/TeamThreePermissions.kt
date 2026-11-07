// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.tools.registry.permission

import android.Manifest

/**
 * One permission GURU cares about, described so a refusal can be explained.
 *
 * WHY THIS IS DATA AND NOT A WHEN-BLOCK. Every tool in the device-senses batch
 * needs the same three things: the platform permission string, what the capability
 * actually does for the person, and the exact route to grant it. Written as data,
 * the message is generated in one place, and a tool can never invent a fourth
 * version of the same sentence.
 *
 * [tier] is verified against the device's own permission database
 * (`dumpsys package permissions`) rather than assumed from the brief, because the
 * brief's own labels were wrong in places: PROCESS_OUTGOING_CALLS and
 * ACCEPT_HANDOVER are ordinary dangerous permissions on API 37, not privileged
 * ones. A tier that disagrees with the platform produces advice that cannot work.
 */
data class PermissionSpec(
    /** The platform string, e.g. "android.permission.CALL_PHONE". */
    val permission: String,
    /** How this can be granted at all. */
    val tier: PermissionTier,
    /** Short human name: what to call this in a sentence. */
    val label: String,
    /**
     * What the capability does for the person, in one clause. This is the "why it
     * matters" half of the failure sentence and it must be about the person, not
     * about the API.
     */
    val why: String,
    /**
     * The exact route to grant it, or null when the grant is automatic or
     * impossible. Wording is a path a person can follow, never a gesture at one.
     */
    val grantRoute: String?,
) {
    /** True when this permission needs no dialog: install-time or automatic. */
    val grantedAtInstall: Boolean get() = tier == PermissionTier.INSTALL

    /**
     * True when a normal app cannot obtain this by asking. Used to decide whether
     * a tool reports "not granted yet" or "not grantable without a privileged tier".
     */
    val needsPrivilegedPath: Boolean get() = tier == PermissionTier.PRIVILEGED

    /**
     * The bare verb phrase from [why], so a sentence can say "GURU cannot <this>".
     *
     * [why] is written for a person and always starts "GURU can ..." or
     * "GURU could ...". Stripping that prefix once, here, keeps every generated
     * sentence grammatical and stops each template from re-deriving the same
     * string surgery in a slightly different way.
     */
    val consequence: String
        get() = why.removePrefix("GURU can ").removePrefix("GURU could ")
}

/**
 * The device-senses catalogue: every permission Team 3 owns, verified against the
 * platform database of a live API 37 device.
 *
 * Grouped by domain so the three agents can reason about their own set without
 * reading the other two. The grouping is not a permission boundary; a tool may
 * reference any entry here.
 */
object TeamThreePermissions {

    // ------------------------------------------------------------------
    // Phone, SMS and calls
    // ------------------------------------------------------------------

    val CALL_PHONE = PermissionSpec(
        permission = Manifest.permission.CALL_PHONE,
        tier = PermissionTier.RUNTIME,
        label = "Phone",
        why = "GURU can place a call for you instead of handing you a dialler you then have to tap",
        grantRoute = "Settings > Apps > GURU > Permissions > Phone > Allow",
    )

    val ANSWER_PHONE_CALLS = PermissionSpec(
        permission = Manifest.permission.ANSWER_PHONE_CALLS,
        tier = PermissionTier.RUNTIME,
        label = "Phone",
        why = "GURU can pick up or hang up a call that is already ringing",
        grantRoute = "Settings > Apps > GURU > Permissions > Phone > Allow",
    )

    val READ_PHONE_STATE = PermissionSpec(
        permission = Manifest.permission.READ_PHONE_STATE,
        tier = PermissionTier.RUNTIME,
        label = "Phone",
        why = "GURU can tell whether a call is ringing, active or idle, and on which SIM",
        grantRoute = "Settings > Apps > GURU > Permissions > Phone > Allow",
    )

    val READ_PHONE_NUMBERS = PermissionSpec(
        permission = Manifest.permission.READ_PHONE_NUMBERS,
        tier = PermissionTier.RUNTIME,
        label = "Phone",
        why = "GURU can read the numbers assigned to your SIMs without asking you to type them",
        grantRoute = "Settings > Apps > GURU > Permissions > Phone > Allow",
    )

    val READ_PRECISE_PHONE_STATE = PermissionSpec(
        permission = "android.permission.READ_PRECISE_PHONE_STATE",
        tier = PermissionTier.PRIVILEGED,
        label = "Precise phone state",
        why = "GURU could read per-SIM call quality and precise call state, which a normal app is never allowed to see",
        grantRoute = null,
    )

    val PROCESS_OUTGOING_CALLS = PermissionSpec(
        permission = Manifest.permission.PROCESS_OUTGOING_CALLS,
        tier = PermissionTier.RUNTIME,
        label = "Phone",
        why = "GURU can see a call being placed so it can log it or rewrite the number before it dials",
        grantRoute = "Settings > Apps > GURU > Permissions > Phone > Allow",
    )

    val USE_SIP = PermissionSpec(
        permission = Manifest.permission.USE_SIP,
        tier = PermissionTier.RUNTIME,
        label = "Phone",
        why = "GURU can register and use a SIP account for internet calls",
        grantRoute = "Settings > Apps > GURU > Permissions > Phone > Allow",
    )

    val MANAGE_OWN_CALLS = PermissionSpec(
        permission = Manifest.permission.MANAGE_OWN_CALLS,
        tier = PermissionTier.INSTALL,
        label = "Manage own calls",
        why = "GURU can run its own calling account without borrowing the system dialler",
        grantRoute = null,
    )

    val ACCEPT_HANDOVER = PermissionSpec(
        permission = Manifest.permission.ACCEPT_HANDOVER,
        tier = PermissionTier.RUNTIME,
        label = "Phone",
        why = "GURU can pick up a call that another device handed over to this phone",
        grantRoute = "Settings > Apps > GURU > Permissions > Phone > Allow",
    )

    val READ_SMS = PermissionSpec(
        permission = Manifest.permission.READ_SMS,
        tier = PermissionTier.RUNTIME,
        label = "SMS",
        why = "GURU can find the text you are talking about instead of asking you to paste it",
        grantRoute = "Settings > Apps > GURU > Permissions > SMS > Allow",
    )

    val SEND_SMS = PermissionSpec(
        permission = Manifest.permission.SEND_SMS,
        tier = PermissionTier.RUNTIME,
        label = "SMS",
        why = "GURU can send the text you dictated, which cannot be undone once it leaves",
        grantRoute = "Settings > Apps > GURU > Permissions > SMS > Allow",
    )

    val RECEIVE_SMS = PermissionSpec(
        permission = Manifest.permission.RECEIVE_SMS,
        tier = PermissionTier.RUNTIME,
        label = "SMS",
        why = "GURU can notice a message arriving and act on it without you relaying it",
        grantRoute = "Settings > Apps > GURU > Permissions > SMS > Allow",
    )

    val RECEIVE_MMS = PermissionSpec(
        permission = Manifest.permission.RECEIVE_MMS,
        tier = PermissionTier.RUNTIME,
        label = "SMS",
        why = "GURU can receive a picture message rather than only text",
        grantRoute = "Settings > Apps > GURU > Permissions > SMS > Allow",
    )

    val RECEIVE_WAP_PUSH = PermissionSpec(
        permission = Manifest.permission.RECEIVE_WAP_PUSH,
        tier = PermissionTier.RUNTIME,
        label = "SMS",
        why = "GURU can receive the carrier service messages that arrive over WAP push",
        grantRoute = "Settings > Apps > GURU > Permissions > SMS > Allow",
    )

    val READ_CELL_BROADCASTS = PermissionSpec(
        permission = "android.permission.READ_CELL_BROADCASTS",
        tier = PermissionTier.RUNTIME,
        label = "SMS",
        why = "GURU can read the emergency alerts your carrier broadcasts",
        grantRoute = "Settings > Apps > GURU > Permissions > SMS > Allow",
    )

    // ------------------------------------------------------------------
    // Personal data: call log, contacts, accounts, calendar
    // ------------------------------------------------------------------

    val READ_CALL_LOG = PermissionSpec(
        permission = Manifest.permission.READ_CALL_LOG,
        tier = PermissionTier.RUNTIME,
        label = "Call log",
        why = "GURU can answer who called and when instead of guessing",
        grantRoute = "Settings > Apps > GURU > Permissions > Call logs > Allow",
    )

    val WRITE_CALL_LOG = PermissionSpec(
        permission = Manifest.permission.WRITE_CALL_LOG,
        tier = PermissionTier.RUNTIME,
        label = "Call log",
        why = "GURU can record a call it handled so your history stays complete",
        grantRoute = "Settings > Apps > GURU > Permissions > Call logs > Allow",
    )

    val READ_CONTACTS = PermissionSpec(
        permission = Manifest.permission.READ_CONTACTS,
        tier = PermissionTier.RUNTIME,
        label = "Contacts",
        why = "GURU can find the right person when you say a name",
        grantRoute = "Settings > Apps > GURU > Permissions > Contacts > Allow",
    )

    val WRITE_CONTACTS = PermissionSpec(
        permission = Manifest.permission.WRITE_CONTACTS,
        tier = PermissionTier.RUNTIME,
        label = "Contacts",
        why = "GURU can add, update or remove a contact you asked it to",
        grantRoute = "Settings > Apps > GURU > Permissions > Contacts > Allow",
    )

    val GET_ACCOUNTS = PermissionSpec(
        permission = Manifest.permission.GET_ACCOUNTS,
        tier = PermissionTier.RUNTIME,
        label = "Contacts",
        why = "GURU can list the accounts on this phone so it knows what is already connected",
        grantRoute = "Settings > Apps > GURU > Permissions > Contacts > Allow",
    )

    // MANAGE_ACCOUNTS, AUTHENTICATE_ACCOUNTS and USE_CREDENTIALS are deprecated
    // constants in recent SDKs, so Manifest.permission no longer exposes them
    // even though the platform still declares all three. Written literally
    // because the string is what the platform matches.
    val MANAGE_ACCOUNTS = PermissionSpec(
        permission = "android.permission.MANAGE_ACCOUNTS",
        tier = PermissionTier.INSTALL,
        label = "Manage accounts",
        why = "GURU can add or remove an account when you ask it to",
        grantRoute = null,
    )

    val AUTHENTICATE_ACCOUNTS = PermissionSpec(
        permission = "android.permission.AUTHENTICATE_ACCOUNTS",
        tier = PermissionTier.INSTALL,
        label = "Authenticate accounts",
        why = "GURU can sign in to an account it manages on your behalf",
        grantRoute = null,
    )

    val USE_CREDENTIALS = PermissionSpec(
        permission = "android.permission.USE_CREDENTIALS",
        tier = PermissionTier.INSTALL,
        label = "Use credentials",
        why = "GURU can fetch an auth token for an account instead of asking you for a password",
        grantRoute = null,
    )

    val READ_CALENDAR = PermissionSpec(
        permission = Manifest.permission.READ_CALENDAR,
        tier = PermissionTier.RUNTIME,
        label = "Calendar",
        why = "GURU can see the device's real calendars, which is a different store from its own event list",
        grantRoute = "Settings > Apps > GURU > Permissions > Calendar > Allow",
    )

    val WRITE_CALENDAR = PermissionSpec(
        permission = Manifest.permission.WRITE_CALENDAR,
        tier = PermissionTier.RUNTIME,
        label = "Calendar",
        why = "GURU can put an event in the calendar your other apps read",
        grantRoute = "Settings > Apps > GURU > Permissions > Calendar > Allow",
    )

    // ------------------------------------------------------------------
    // Location, camera, audio, sensors
    // ------------------------------------------------------------------

    val ACCESS_FINE_LOCATION = PermissionSpec(
        permission = Manifest.permission.ACCESS_FINE_LOCATION,
        tier = PermissionTier.RUNTIME,
        label = "Precise location",
        why = "GURU can report where you are to within a few metres rather than a neighbourhood",
        grantRoute = "Settings > Apps > GURU > Permissions > Location > Allow precise",
    )

    val ACCESS_COARSE_LOCATION = PermissionSpec(
        permission = Manifest.permission.ACCESS_COARSE_LOCATION,
        tier = PermissionTier.RUNTIME,
        label = "Approximate location",
        why = "GURU can still answer roughly where you are when precise location is switched off",
        grantRoute = "Settings > Apps > GURU > Permissions > Location > Allow approximate",
    )

    val ACCESS_BACKGROUND_LOCATION = PermissionSpec(
        permission = Manifest.permission.ACCESS_BACKGROUND_LOCATION,
        tier = PermissionTier.RUNTIME,
        label = "Background location",
        why = "GURU can notice a place change when you are not in the app, which Android only allows after precise location",
        grantRoute = "Settings > Apps > GURU > Permissions > Location > Allow all the time (after precise is granted)",
    )

    val ACCESS_MEDIA_LOCATION = PermissionSpec(
        permission = Manifest.permission.ACCESS_MEDIA_LOCATION,
        tier = PermissionTier.RUNTIME,
        label = "Photos and videos",
        why = "GURU can read the real GPS in a photo's metadata instead of stripping it",
        grantRoute = "Settings > Apps > GURU > Permissions > Photos and videos > Allow",
    )

    val CAMERA = PermissionSpec(
        permission = Manifest.permission.CAMERA,
        tier = PermissionTier.RUNTIME,
        label = "Camera",
        why = "GURU can take a photo or record video for you",
        grantRoute = "Settings > Apps > GURU > Permissions > Camera > Allow",
    )

    val RECORD_AUDIO = PermissionSpec(
        permission = Manifest.permission.RECORD_AUDIO,
        tier = PermissionTier.RUNTIME,
        label = "Microphone",
        why = "GURU can hear you speak and transcribe it",
        grantRoute = "Settings > Apps > GURU > Permissions > Microphone > Allow",
    )

    val CAPTURE_AUDIO_OUTPUT = PermissionSpec(
        permission = Manifest.permission.CAPTURE_AUDIO_OUTPUT,
        tier = PermissionTier.PRIVILEGED,
        label = "Capture audio output",
        why = "GURU could record the sound other apps are playing, which a normal app is never allowed to capture",
        grantRoute = null,
    )

    // The two video capture permissions are also absent from Manifest.permission
    // in recent SDKs while remaining live in the platform database, so the literal
    // strings are used for the same reason as the account trio above.
    val CAPTURE_VIDEO_OUTPUT = PermissionSpec(
        permission = "android.permission.CAPTURE_VIDEO_OUTPUT",
        tier = PermissionTier.PRIVILEGED,
        label = "Capture video output",
        why = "GURU could read a raw video stream from another app, which is a signature-level capability",
        grantRoute = null,
    )

    val CAPTURE_SECURE_VIDEO_OUTPUT = PermissionSpec(
        permission = "android.permission.CAPTURE_SECURE_VIDEO_OUTPUT",
        tier = PermissionTier.PRIVILEGED,
        label = "Capture secure video output",
        why = "GURU could read even the protected video surfaces, which the platform reserves for system roles",
        grantRoute = null,
    )

    val MODIFY_AUDIO_SETTINGS = PermissionSpec(
        permission = Manifest.permission.MODIFY_AUDIO_SETTINGS,
        tier = PermissionTier.INSTALL,
        label = "Modify audio settings",
        why = "GURU can choose whether sound plays through the speaker, headset or a connected device",
        grantRoute = null,
    )

    val HIGH_SAMPLING_RATE_SENSORS = PermissionSpec(
        permission = Manifest.permission.HIGH_SAMPLING_RATE_SENSORS,
        tier = PermissionTier.INSTALL,
        label = "High sampling rate sensors",
        why = "GURU can sample motion sensors above the default rate, which is what makes gesture detection work",
        grantRoute = null,
    )

    val BODY_SENSORS = PermissionSpec(
        permission = Manifest.permission.BODY_SENSORS,
        tier = PermissionTier.RUNTIME,
        label = "Body sensors",
        why = "GURU can read heart rate and other body readings when a sensor is present",
        grantRoute = "Settings > Apps > GURU > Permissions > Body sensors > Allow",
    )

    val BODY_SENSORS_BACKGROUND = PermissionSpec(
        permission = Manifest.permission.BODY_SENSORS_BACKGROUND,
        tier = PermissionTier.RUNTIME,
        label = "Background body sensors",
        why = "GURU can keep sampling body readings in the background, which Android only allows after body sensors",
        grantRoute = "Settings > Apps > GURU > Permissions > Body sensors > Allow all the time (after body sensors is granted)",
    )

    val ACTIVITY_RECOGNITION = PermissionSpec(
        permission = Manifest.permission.ACTIVITY_RECOGNITION,
        tier = PermissionTier.RUNTIME,
        label = "Physical activity",
        why = "GURU can tell whether you are walking, running or still, instead of inferring it from an accelerometer",
        grantRoute = "Settings > Apps > GURU > Permissions > Physical activity > Allow",
    )

    val UWB_RANGING = PermissionSpec(
        permission = Manifest.permission.UWB_RANGING,
        tier = PermissionTier.RUNTIME,
        label = "UWB ranging",
        why = "GURU can measure distance to a UWB device to within centimetres",
        grantRoute = "Settings > Apps > GURU > Permissions > Nearby devices > Allow",
    )

    val BLUETOOTH_SCAN = PermissionSpec(
        permission = Manifest.permission.BLUETOOTH_SCAN,
        tier = PermissionTier.RUNTIME,
        label = "Nearby devices",
        why = "GURU can find nearby Bluetooth devices instead of asking you to pair one by hand",
        grantRoute = "Settings > Apps > GURU > Permissions > Nearby devices > Allow",
    )

    val BLUETOOTH_CONNECT = PermissionSpec(
        permission = Manifest.permission.BLUETOOTH_CONNECT,
        tier = PermissionTier.RUNTIME,
        label = "Nearby devices",
        why = "GURU can connect to and talk to a paired Bluetooth device",
        grantRoute = "Settings > Apps > GURU > Permissions > Nearby devices > Allow",
    )

    val BLUETOOTH_ADVERTISE = PermissionSpec(
        permission = Manifest.permission.BLUETOOTH_ADVERTISE,
        tier = PermissionTier.RUNTIME,
        label = "Nearby devices",
        why = "GURU can announce itself to nearby Bluetooth devices so they can find it",
        grantRoute = "Settings > Apps > GURU > Permissions > Nearby devices > Allow",
    )

    // ------------------------------------------------------------------
    // Permissions other tool sets already declare
    // ------------------------------------------------------------------

    /*
     * These two are not part of Team 3's issue range, but they ARE declared by
     * existing tool definitions elsewhere in the app, and the dispatcher's
     * pre-flight refusal now goes through this catalogue. Leaving them out would
     * make an alarm or notification tool report a permission it genuinely needs as
     * unknown, which reads to a person as a broken app.
     *
     * SCHEDULE_EXACT_ALARM is a special permission granted through its own settings
     * screen rather than a dialog, so its tier is APPOP and its route names the
     * screen. That distinction matters: a runtime-tier label would send somebody to
     * a dialog that never appears.
     */
    val POST_NOTIFICATIONS = PermissionSpec(
        permission = Manifest.permission.POST_NOTIFICATIONS,
        tier = PermissionTier.RUNTIME,
        label = "Notifications",
        why = "GURU can show you a notification when something it was asked to watch has happened",
        grantRoute = "Settings > Apps > GURU > Permissions > Notifications > Allow",
    )

    val SCHEDULE_EXACT_ALARM = PermissionSpec(
        permission = Manifest.permission.SCHEDULE_EXACT_ALARM,
        tier = PermissionTier.APPOP,
        label = "Exact alarms",
        why = "GURU can fire an alarm at the exact minute you asked for rather than roughly then",
        grantRoute = "Settings > Apps > GURU > Alarms & reminders > Allow",
    )

    // ------------------------------------------------------------------
    // Network, wifi and VPN (#95 to #103)
    // ------------------------------------------------------------------

    val ACCESS_NETWORK_STATE = PermissionSpec(
        permission = Manifest.permission.ACCESS_NETWORK_STATE,
        tier = PermissionTier.INSTALL,
        label = "Network state",
        why = "GURU can tell whether the phone is online and on what kind of connection",
        grantRoute = null,
    )

    val CHANGE_NETWORK_STATE = PermissionSpec(
        permission = Manifest.permission.CHANGE_NETWORK_STATE,
        tier = PermissionTier.INSTALL,
        label = "Network state",
        why = "GURU can ask the phone to bring its data connection up or down, which the platform usually refuses to an app",
        grantRoute = null,
    )

    val ACCESS_WIFI_STATE = PermissionSpec(
        permission = Manifest.permission.ACCESS_WIFI_STATE,
        tier = PermissionTier.INSTALL,
        label = "Wi-Fi state",
        why = "GURU can name the network you are on and how strong the link is",
        grantRoute = null,
    )

    val CHANGE_WIFI_STATE = PermissionSpec(
        permission = Manifest.permission.CHANGE_WIFI_STATE,
        tier = PermissionTier.INSTALL,
        label = "Wi-Fi state",
        why = "GURU can ask for Wi-Fi to be switched on or off, which Android blocks for an ordinary app since version 10",
        grantRoute = null,
    )

    val NEARBY_WIFI_DEVICES = PermissionSpec(
        permission = Manifest.permission.NEARBY_WIFI_DEVICES,
        tier = PermissionTier.RUNTIME,
        label = "Nearby devices",
        why = "GURU can see the Wi-Fi networks around you without needing your location",
        grantRoute = "Settings > Apps > GURU > Permissions > Nearby devices > Allow",
    )

    val CONTROL_VPN = PermissionSpec(
        permission = "android.permission.CONTROL_VPN",
        tier = PermissionTier.PRIVILEGED,
        label = "Control VPN",
        why = "GURU could bring the system VPN up and down for you",
        grantRoute = null,
    )

    val MANAGE_IPSEC_TUNNELS = PermissionSpec(
        permission = "android.permission.MANAGE_IPSEC_TUNNELS",
        tier = PermissionTier.PRIVILEGED,
        label = "IPsec tunnels",
        why = "GURU could set up a private network tunnel the rest of the phone uses",
        grantRoute = null,
    )

    // ------------------------------------------------------------------
    // Media library (#107)
    // ------------------------------------------------------------------

    val READ_MEDIA_IMAGES = PermissionSpec(
        permission = Manifest.permission.READ_MEDIA_IMAGES,
        tier = PermissionTier.RUNTIME,
        label = "Photos and videos",
        why = "GURU can find and open the photos on your phone when you ask it to",
        grantRoute = "Settings > Apps > GURU > Permissions > Photos and videos > Allow",
    )

    val READ_MEDIA_VIDEO = PermissionSpec(
        permission = Manifest.permission.READ_MEDIA_VIDEO,
        tier = PermissionTier.RUNTIME,
        label = "Photos and videos",
        why = "GURU can find and open the videos on your phone when you ask it to",
        grantRoute = "Settings > Apps > GURU > Permissions > Photos and videos > Allow",
    )

    val READ_MEDIA_AUDIO = PermissionSpec(
        permission = Manifest.permission.READ_MEDIA_AUDIO,
        tier = PermissionTier.RUNTIME,
        label = "Music and audio",
        why = "GURU can find and play the audio files on your phone",
        grantRoute = "Settings > Apps > GURU > Permissions > Music and audio > Allow",
    )

    val READ_MEDIA_VISUAL_USER_SELECTED = PermissionSpec(
        permission = Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
        tier = PermissionTier.RUNTIME,
        label = "Photos and videos",
        why = "GURU can read the specific photos you picked for it rather than your whole library",
        grantRoute = "Settings > Apps > GURU > Permissions > Photos and videos > Allow limited access",
    )

    // ------------------------------------------------------------------
    // NFC (#93, #94)
    // ------------------------------------------------------------------

    val NFC = PermissionSpec(
        permission = Manifest.permission.NFC,
        tier = PermissionTier.INSTALL,
        label = "NFC",
        why = "GURU can read a tag or talk to a contactless device when you hold the phone to it",
        grantRoute = null,
    )

    val NFC_TRANSACTION_EVENT = PermissionSpec(
        permission = "android.permission.NFC_TRANSACTION_EVENT",
        tier = PermissionTier.PRIVILEGED,
        label = "NFC transaction events",
        why = "GURU could watch raw contactless transactions as they happen",
        grantRoute = null,
    )

    // ------------------------------------------------------------------
    // Apps and packages (#108 to #121)
    // ------------------------------------------------------------------

    val GET_PACKAGE_SIZE = PermissionSpec(
        permission = Manifest.permission.GET_PACKAGE_SIZE,
        tier = PermissionTier.INSTALL,
        label = "App sizes",
        why = "GURU can tell you how much space each app is using",
        grantRoute = null,
    )

    val PACKAGE_USAGE_STATS = PermissionSpec(
        permission = Manifest.permission.PACKAGE_USAGE_STATS,
        tier = PermissionTier.APPOP,
        label = "Usage access",
        why = "GURU can see which apps you have been using and for how long",
        grantRoute = "Settings > Apps > GURU > Usage access > Allow",
    )

    val GET_TASKS = PermissionSpec(
        permission = Manifest.permission.GET_TASKS,
        tier = PermissionTier.PRIVILEGED,
        label = "Running tasks",
        why = "GURU could list every task running on the phone, which a normal app is not allowed to see",
        grantRoute = null,
    )

    val REAL_GET_TASKS = PermissionSpec(
        permission = "android.permission.REAL_GET_TASKS",
        tier = PermissionTier.PRIVILEGED,
        label = "Running tasks",
        why = "GURU could see every running task without the platform filtering the list",
        grantRoute = null,
    )

    val REORDER_TASKS = PermissionSpec(
        permission = Manifest.permission.REORDER_TASKS,
        tier = PermissionTier.INSTALL,
        label = "Reorder tasks",
        why = "GURU can bring an app back to the front instead of asking you to find it",
        grantRoute = null,
    )

    // FORCE_STOP_PACKAGES, MANAGE_USERS and CREATE_USERS are absent from
    // Manifest.permission in recent SDKs while remaining live in the platform's own
    // permission database, so the literal strings are used for the same reason the
    // account trio and the capture pair above use them.
    val FORCE_STOP_PACKAGES = PermissionSpec(
        permission = "android.permission.FORCE_STOP_PACKAGES",
        tier = PermissionTier.PRIVILEGED,
        label = "Force stop apps",
        why = "GURU could stop another app outright when it is misbehaving",
        grantRoute = null,
    )

    val KILL_BACKGROUND_PROCESSES = PermissionSpec(
        permission = Manifest.permission.KILL_BACKGROUND_PROCESSES,
        tier = PermissionTier.INSTALL,
        label = "Background processes",
        why = "GURU can ask the system to close another app's background work, which it may decline",
        grantRoute = null,
    )

    val CLEAR_APP_CACHE = PermissionSpec(
        permission = "android.permission.CLEAR_APP_CACHE",
        tier = PermissionTier.PRIVILEGED,
        label = "Clear app cache",
        why = "GURU could clear another app's cache to free space",
        grantRoute = null,
    )

    val INSTALL_PACKAGES = PermissionSpec(
        permission = "android.permission.INSTALL_PACKAGES",
        tier = PermissionTier.PRIVILEGED,
        label = "Install apps",
        why = "GURU could install an app without you tapping through the installer",
        grantRoute = null,
    )

    val DELETE_PACKAGES = PermissionSpec(
        permission = "android.permission.DELETE_PACKAGES",
        tier = PermissionTier.PRIVILEGED,
        label = "Uninstall apps",
        why = "GURU could remove an app without you tapping through the uninstaller",
        grantRoute = null,
    )

    val START_ANY_ACTIVITY = PermissionSpec(
        permission = "android.permission.START_ANY_ACTIVITY",
        tier = PermissionTier.PRIVILEGED,
        label = "Start any screen",
        why = "GURU could open any screen inside any app on the phone",
        grantRoute = null,
    )

    val START_ACTIVITIES_FROM_BACKGROUND = PermissionSpec(
        permission = "android.permission.START_ACTIVITIES_FROM_BACKGROUND",
        tier = PermissionTier.PRIVILEGED,
        label = "Start screens in the background",
        why = "GURU could open an app while GURU itself is not on screen",
        grantRoute = null,
    )

    val MANAGE_APP_OPS_MODES = PermissionSpec(
        permission = "android.permission.MANAGE_APP_OPS_MODES",
        tier = PermissionTier.PRIVILEGED,
        label = "App operations",
        why = "GURU could read and change what any app is permitted to do",
        grantRoute = null,
    )

    // ------------------------------------------------------------------
    // Settings and system (#122 to #125)
    // ------------------------------------------------------------------

    val WRITE_SETTINGS = PermissionSpec(
        permission = Manifest.permission.WRITE_SETTINGS,
        tier = PermissionTier.APPOP,
        label = "Modify system settings",
        why = "GURU can change brightness, ringtone and screen timeout for you",
        grantRoute = "Settings > Apps > GURU > Modify system settings > Allow",
    )

    val WRITE_SECURE_SETTINGS = PermissionSpec(
        permission = "android.permission.WRITE_SECURE_SETTINGS",
        tier = PermissionTier.PRIVILEGED,
        label = "Secure settings",
        why = "GURU could change protected settings such as the location mode",
        grantRoute = null,
    )

    val SET_TIME = PermissionSpec(
        permission = "android.permission.SET_TIME",
        tier = PermissionTier.PRIVILEGED,
        label = "Clock",
        why = "GURU could set the phone's clock",
        grantRoute = null,
    )

    val SET_TIME_ZONE = PermissionSpec(
        permission = "android.permission.SET_TIME_ZONE",
        tier = PermissionTier.PRIVILEGED,
        label = "Time zone",
        why = "GURU could change the phone's time zone when you travel",
        grantRoute = null,
    )

    val CHANGE_CONFIGURATION = PermissionSpec(
        permission = "android.permission.CHANGE_CONFIGURATION",
        tier = PermissionTier.PRIVILEGED,
        label = "Device configuration",
        why = "GURU could change the phone's locale or display density",
        grantRoute = null,
    )

    val SYSTEM_ALERT_WINDOW = PermissionSpec(
        permission = Manifest.permission.SYSTEM_ALERT_WINDOW,
        tier = PermissionTier.APPOP,
        label = "Display over other apps",
        why = "GURU can keep a small window on screen while you use something else",
        grantRoute = "Settings > Apps > GURU > Display over other apps > Allow",
    )

    val EXPAND_STATUS_BAR = PermissionSpec(
        permission = Manifest.permission.EXPAND_STATUS_BAR,
        tier = PermissionTier.INSTALL,
        label = "Status bar",
        why = "GURU can open the notification shade for you",
        grantRoute = null,
    )

    val SET_WALLPAPER = PermissionSpec(
        permission = "android.permission.SET_WALLPAPER",
        tier = PermissionTier.INSTALL,
        label = "Wallpaper",
        why = "GURU can change your wallpaper",
        grantRoute = null,
    )

    val INJECT_EVENTS = PermissionSpec(
        permission = "android.permission.INJECT_EVENTS",
        tier = PermissionTier.PRIVILEGED,
        label = "Simulate input",
        why = "GURU could tap and type on your behalf without the accessibility service",
        grantRoute = null,
    )

    val ACCESS_NOTIFICATION_POLICY = PermissionSpec(
        permission = Manifest.permission.ACCESS_NOTIFICATION_POLICY,
        tier = PermissionTier.APPOP,
        label = "Do Not Disturb access",
        why = "GURU can silence the phone for you at bedtime or in a meeting",
        grantRoute = "Settings > Apps > GURU > Do Not Disturb access > Allow",
    )

    val USE_BIOMETRIC = PermissionSpec(
        permission = Manifest.permission.USE_BIOMETRIC,
        tier = PermissionTier.INSTALL,
        label = "Biometrics",
        why = "GURU can ask for your fingerprint or face before it does something you cannot undo",
        grantRoute = null,
    )

    val VIBRATE = PermissionSpec(
        permission = Manifest.permission.VIBRATE,
        tier = PermissionTier.INSTALL,
        label = "Vibration",
        why = "GURU can vibrate the phone to get your attention",
        grantRoute = null,
    )

    val WAKE_LOCK = PermissionSpec(
        permission = Manifest.permission.WAKE_LOCK,
        tier = PermissionTier.INSTALL,
        label = "Wake lock",
        why = "GURU can keep working while the screen is off instead of being frozen mid-task",
        grantRoute = null,
    )

    // ------------------------------------------------------------------
    // Power, users and diagnostics (#126 to #136)
    // ------------------------------------------------------------------

    val DEVICE_POWER = PermissionSpec(
        permission = "android.permission.DEVICE_POWER",
        tier = PermissionTier.PRIVILEGED,
        label = "Device power",
        why = "GURU could control the screen state and power the phone down directly",
        grantRoute = null,
    )

    val REBOOT = PermissionSpec(
        permission = Manifest.permission.REBOOT,
        tier = PermissionTier.PRIVILEGED,
        label = "Reboot",
        why = "GURU could restart the phone for you",
        grantRoute = null,
    )

    val SHUTDOWN = PermissionSpec(
        permission = "android.permission.SHUTDOWN",
        tier = PermissionTier.PRIVILEGED,
        label = "Power off",
        why = "GURU could switch the phone off for you",
        grantRoute = null,
    )

    val SET_PROCESS_LIMIT = PermissionSpec(
        permission = "android.permission.SET_PROCESS_LIMIT",
        tier = PermissionTier.PRIVILEGED,
        label = "Process limit",
        why = "GURU could limit how many apps the system keeps alive in the background",
        grantRoute = null,
    )

    val BATTERY_STATS = PermissionSpec(
        permission = "android.permission.BATTERY_STATS",
        tier = PermissionTier.PRIVILEGED,
        label = "Battery statistics",
        why = "GURU could read the detailed battery breakdown rather than the summary",
        grantRoute = null,
    )

    val READ_LOGS = PermissionSpec(
        permission = Manifest.permission.READ_LOGS,
        tier = PermissionTier.PRIVILEGED,
        label = "System log",
        why = "GURU could read the system log to work out why something on the phone failed",
        grantRoute = null,
    )

    val DUMP = PermissionSpec(
        permission = "android.permission.DUMP",
        tier = PermissionTier.PRIVILEGED,
        label = "System dump",
        why = "GURU could read the system's own internal state to diagnose a fault properly",
        grantRoute = null,
    )

    val DIAGNOSTIC = PermissionSpec(
        permission = "android.permission.DIAGNOSTIC",
        tier = PermissionTier.PRIVILEGED,
        label = "Vendor diagnostics",
        why = "GURU could run the manufacturer's own hardware diagnostics",
        grantRoute = null,
    )

    val FACTORY_TEST = PermissionSpec(
        permission = "android.permission.FACTORY_TEST",
        tier = PermissionTier.PRIVILEGED,
        label = "Factory test",
        why = "GURU could run factory test mode, which only a manufacturer build ever holds",
        grantRoute = null,
    )

    val MASTER_CLEAR = PermissionSpec(
        permission = "android.permission.MASTER_CLEAR",
        tier = PermissionTier.PRIVILEGED,
        label = "Factory reset",
        why = "GURU could wipe the phone back to factory state, which nothing but Device Owner should ever do",
        grantRoute = null,
    )

    val INTERACT_ACROSS_USERS = PermissionSpec(
        permission = "android.permission.INTERACT_ACROSS_USERS",
        tier = PermissionTier.PRIVILEGED,
        label = "Other user profiles",
        why = "GURU could act inside a second profile such as a work account",
        grantRoute = null,
    )

    val INTERACT_ACROSS_USERS_FULL = PermissionSpec(
        permission = "android.permission.INTERACT_ACROSS_USERS_FULL",
        tier = PermissionTier.PRIVILEGED,
        label = "Other user profiles",
        why = "GURU could act inside another profile without the platform's normal restrictions",
        grantRoute = null,
    )

    val MANAGE_USERS = PermissionSpec(
        permission = "android.permission.MANAGE_USERS",
        tier = PermissionTier.PRIVILEGED,
        label = "Manage profiles",
        why = "GURU could create and remove user profiles on the phone",
        grantRoute = null,
    )

    val CREATE_USERS = PermissionSpec(
        permission = "android.permission.CREATE_USERS",
        tier = PermissionTier.PRIVILEGED,
        label = "Create profiles",
        why = "GURU could set up a new user profile",
        grantRoute = null,
    )

    // ------------------------------------------------------------------
    // Alarms (#146, #147)
    // ------------------------------------------------------------------

    // SET_ALARM is the one permission in this catalogue that does not live under
    // android.permission: the platform declares it as com.android.alarm.permission.
    // Written literally so the namespace is visible, because a reader who assumes
    // the usual prefix would "fix" this into a permission that does not exist.
    val SET_ALARM = PermissionSpec(
        permission = "com.android.alarm.permission.SET_ALARM",
        tier = PermissionTier.PRIVILEGED,
        label = "Set alarms",
        why = "GURU could put an alarm into the system clock app, which setAlarmClock already reaches without this",
        grantRoute = null,
    )

    /**
     * Every spec in this team's scope, keyed by platform permission string.
     *
     * A tool asks for a spec by string; a string with no entry is a bug in the
     * tool, not a missing permission, and [GrantCheck] reports it as such rather
     * than inventing a grant route.
     */
    val all: Map<String, PermissionSpec> = listOf(
        CALL_PHONE, ANSWER_PHONE_CALLS, READ_PHONE_STATE, READ_PHONE_NUMBERS,
        READ_PRECISE_PHONE_STATE, PROCESS_OUTGOING_CALLS, USE_SIP, MANAGE_OWN_CALLS,
        ACCEPT_HANDOVER, READ_SMS, SEND_SMS, RECEIVE_SMS, RECEIVE_MMS, RECEIVE_WAP_PUSH,
        READ_CELL_BROADCASTS,
        READ_CALL_LOG, WRITE_CALL_LOG, READ_CONTACTS, WRITE_CONTACTS, GET_ACCOUNTS,
        MANAGE_ACCOUNTS, AUTHENTICATE_ACCOUNTS, USE_CREDENTIALS, READ_CALENDAR, WRITE_CALENDAR,
        ACCESS_FINE_LOCATION, ACCESS_COARSE_LOCATION, ACCESS_BACKGROUND_LOCATION,
        ACCESS_MEDIA_LOCATION, CAMERA, RECORD_AUDIO, CAPTURE_AUDIO_OUTPUT,
        CAPTURE_VIDEO_OUTPUT, CAPTURE_SECURE_VIDEO_OUTPUT, MODIFY_AUDIO_SETTINGS,
        HIGH_SAMPLING_RATE_SENSORS, BODY_SENSORS, BODY_SENSORS_BACKGROUND,
        ACTIVITY_RECOGNITION, UWB_RANGING, BLUETOOTH_SCAN, BLUETOOTH_CONNECT,
        BLUETOOTH_ADVERTISE,
        // Declared by other tool sets, catalogued so the dispatcher's refusal can
        // explain them instead of calling them unknown.
        POST_NOTIFICATIONS, SCHEDULE_EXACT_ALARM,
        // The second wave: the permission groups that had a registry entry naming
        // an owning tool but no catalogue entry, so every tool built for #95 to #147
        // would have refused with "unknown permission" and read as a broken app.
        ACCESS_NETWORK_STATE, CHANGE_NETWORK_STATE, ACCESS_WIFI_STATE, CHANGE_WIFI_STATE,
        NEARBY_WIFI_DEVICES, CONTROL_VPN, MANAGE_IPSEC_TUNNELS,
        READ_MEDIA_IMAGES, READ_MEDIA_VIDEO, READ_MEDIA_AUDIO, READ_MEDIA_VISUAL_USER_SELECTED,
        NFC, NFC_TRANSACTION_EVENT,
        GET_PACKAGE_SIZE, PACKAGE_USAGE_STATS, GET_TASKS, REAL_GET_TASKS, REORDER_TASKS,
        FORCE_STOP_PACKAGES, KILL_BACKGROUND_PROCESSES, CLEAR_APP_CACHE, INSTALL_PACKAGES,
        DELETE_PACKAGES, START_ANY_ACTIVITY, START_ACTIVITIES_FROM_BACKGROUND,
        MANAGE_APP_OPS_MODES,
        WRITE_SETTINGS, WRITE_SECURE_SETTINGS, SET_TIME, SET_TIME_ZONE, CHANGE_CONFIGURATION,
        SYSTEM_ALERT_WINDOW, EXPAND_STATUS_BAR, SET_WALLPAPER, INJECT_EVENTS,
        ACCESS_NOTIFICATION_POLICY, USE_BIOMETRIC, VIBRATE, WAKE_LOCK,
        DEVICE_POWER, REBOOT, SHUTDOWN, SET_PROCESS_LIMIT, BATTERY_STATS, READ_LOGS, DUMP,
        DIAGNOSTIC, FACTORY_TEST, MASTER_CLEAR, INTERACT_ACROSS_USERS,
        INTERACT_ACROSS_USERS_FULL, MANAGE_USERS, CREATE_USERS,
        SET_ALARM,
    ).associateBy { it.permission }

    fun forPermission(permission: String): PermissionSpec? = all[permission]
}
