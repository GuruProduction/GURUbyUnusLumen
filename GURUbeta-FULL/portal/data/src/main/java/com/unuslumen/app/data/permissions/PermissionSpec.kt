// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.permissions

/**
 * How a permission is obtained, which decides which path the orchestrator walks.
 *
 * Every value here corresponds to a real mechanism the platform offers. There is
 * no "unknown" bucket on purpose: a permission that fits none of these does not
 * belong in the manifest, and [PermissionRegistry] refuses to hold one.
 */
enum class GrantMechanism {
    /** A runtime prompt, or an install-time grant that is simply held. */
    DIALOG,

    /** A Settings screen plus an app-op read-back (overlay, usage stats, WRITE_SETTINGS). */
    SETTINGS_INTENT,

    /** The service must be implemented and the user enables it in Settings. */
    SERVICE,

    /** RoleManager: the user picks GURU for a system role. */
    ROLE,

    /** Shell UID 2000 via Shizuku. Implemented in #173, reported honestly until then. */
    SHIZUKU,

    /** DevicePolicyManager.setPermissionGrantState, requires Device Owner. */
    DEVICE_OWNER,

    /** Nothing to do: the platform grants it at install, or GURU holds it by being itself. */
    SELF_GUARD
}

/**
 * What a permission actually is, which decides what a denial means.
 *
 * The distinction matters because "denied" means three different things here: a
 * runtime grant the user can give, a signature permission the platform will never
 * give to an untrusted app, and a BIND_ guard that protects a service GURU owns.
 */
enum class PermissionTier {
    /** Normal user-facing runtime grant. The user can say yes. */
    RUNTIME,

    /** Special app-op. The user grants it on a Settings screen, not a dialog. */
    APP_OP,

    /** Signature or privileged. Only reachable at shell UID or as Device Owner. */
    PRIVILEGED,

    /** BIND_ guard. Not a capability; it makes GURU's own service private. */
    SERVICE_GUARD,

    /** Granted at install. There is nothing to request. */
    NORMAL
}

/**
 * One permission, fully described.
 *
 * @param permission the platform string, e.g. android.permission.CAMERA
 * @param tier what kind of thing it is
 * @param mechanism how it is obtained
 * @param owningTool the tool that consumes it, or null when nothing does yet
 * @param owningSkill the skill that documents it, or null when none does yet
 * @param unlocks one sentence a model can read to know what this buys
 */
data class PermissionSpec(
    val permission: String,
    val tier: PermissionTier,
    val mechanism: GrantMechanism,
    val owningTool: String?,
    val owningSkill: String?,
    val unlocks: String
) {
    /** The bare name without the android.permission prefix, for logs and messages. */
    val bareName: String get() = permission.substringAfterLast('.')
}
