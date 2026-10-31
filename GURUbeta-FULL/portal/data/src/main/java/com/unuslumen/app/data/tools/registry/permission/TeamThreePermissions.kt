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
    ).associateBy { it.permission }

    fun forPermission(permission: String): PermissionSpec? = all[permission]
}
