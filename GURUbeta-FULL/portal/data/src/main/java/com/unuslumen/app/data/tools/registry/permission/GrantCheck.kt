// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.tools.registry.permission

/**
 * The state of one permission on this device, as far as anything can know.
 *
 * GRANTED      Held right now. A tool may proceed and the result is about data.
 * NOT_GRANTED  A normal runtime permission that has not been allowed yet. The
 *              person can grant it through a dialog and will be told how.
 * PRIVILEGED   The platform will not grant this to a normal app, but the
 *              permission does carry the `development` protection flag, so shell
 *              UID can grant it once Shizuku is set up. It is not a user mistake
 *              and must never be reported as one.
 * NOT_GRANTABLE The platform reserves this permission for system apps and does not
 *              mark it with the `development` flag, so no command, no shell UID and
 *              no Device Owner call can grant it. The honest answer is that it is
 *              out of reach on this device, not that the person has not done
 *              something yet.
 * INSTALL      protectionLevel=normal, so the app holds it from install. If a
 *              check reports it missing the manifest has lost the declaration,
 *              which is a build fault rather than a permission the person can
 *              grant. Reported as such so nobody hunts for a settings screen.
 * ABSENT       The device has no such hardware or feature. No permission
 *              involved, nothing to grant, and the answer is about the device.
 * UNKNOWN_SPEC The tool asked about a permission not in the catalogue. A tool
 *              bug, reported plainly instead of guessed at.
 */
enum class GrantState {
    GRANTED,
    NOT_GRANTED,
    PRIVILEGED,
    NOT_GRANTABLE,
    INSTALL,
    ABSENT,
    UNKNOWN_SPEC,
}

/**
 * GrantCheck — the result of asking whether a capability is allowed to run.
 *
 * ONE OBJECT, ONE SENTENCE. Every tool in this batch has to fail with, in one
 * sentence: what is missing, why it matters, and exactly how to grant it. That
 * sentence is built here, from the spec, so forty-four tools cannot produce
 * forty-four slightly different versions of the same instruction.
 *
 * THE DEFECT THIS EXISTS TO REMOVE. A denial reported as an empty list is a lie a
 * tool tells by omission: "no calls matched" and "I am not allowed to see your
 * calls" are different facts and a person acts differently on each. [state]
 * carries which one it is, and [denied] is the single test a tool branches on.
 *
 * WRITING RULE FOR THIS FILE. Every spec's `why` is a complete clause beginning
 * "GURU can ..." or "GURU could ...", so these templates state the consequence in
 * the middle of a sentence. Naming the permission at the start and then repeating
 * it reads like a machine wrote it, and this text is what a person actually sees
 * the moment a tool refuses, so it has to read like a person wrote it.
 */
data class GrantCheck(
    val spec: PermissionSpec,
    val state: GrantState,
    /**
     * Present only when a privileged permission was checked and the platform-level
     * reason is worth stating. Null otherwise, because a normal grant needs the
     * route, not an explanation.
     */
    val detail: String? = null,
) {
    /** True only when the capability may run and a result is about real data. */
    val allowed: Boolean get() = state == GrantState.GRANTED || state == GrantState.INSTALL

    /** True when the capability must not run and the caller must report the reason. */
    val denied: Boolean get() = !allowed

    /**
     * The one sentence a denied tool returns.
     *
     * Shape: what is missing, why it matters, how to grant it. No exclamation, no
     * apology, no hedging: the person needs the route, not sympathy.
     */
    fun sentence(): String = when (state) {
        GrantState.GRANTED -> "Granted, so ${spec.why}."

        GrantState.INSTALL -> {
            "Install-time fault: ${spec.label} needs no dialog and should be held automatically, " +
                "so a denial means the manifest has lost the declaration and this build cannot " +
                spec.consequence + "."
        }

        // "GURU does not hold the X permission" is written this way because the
        // label is a Settings permission-group name ("Phone", "Call log",
        // "Body sensors") and plural labels read as broken grammar when followed
        // by "is not granted". This shape is correct for every label in the
        // catalogue, which the grammatical-sentence test enforces.
        GrantState.NOT_GRANTED -> buildString {
            append("Permission denied: GURU does not hold the ")
            append(spec.label)
            append(" permission, so GURU cannot ")
            append(spec.consequence)
            append(". ")
            append(spec.grantRoute?.let { "Grant it in $it." } ?: "It cannot be granted on this build.")
        }

        // Written as "reaches it through Shizuku" rather than "Shizuku is the only
        // path", because the platform will not hand this permission out by any
        // other means: there is no dialog and no owner call that applies. The
        // `development` flag is what makes the shell-UID route possible at all,
        // and this branch is only reached when the flag is present.
        GrantState.PRIVILEGED -> buildString {
            append("Permission unavailable: GURU cannot hold the ")
            append(spec.label)
            append(" permission, because Android reserves it for system apps, so GURU cannot ")
            append(spec.consequence)
            append(". GURU can reach it through Shizuku once that is set up")
            append(detail?.let { ", and $it" } ?: "")
            append(".")
        }

        // The correction to #47's framing, stated in the words the person sees.
        // Signature and privileged permissions that lack the `development` flag
        // cannot be granted by `pm grant`, which means no Shizuku setup and no
        // Device Owner provisioning changes the answer. Saying "set up Shizuku"
        // here would send somebody through a flow that cannot succeed.
        GrantState.NOT_GRANTABLE -> buildString {
            append("Permission unavailable: Android reserves the ")
            append(spec.label)
            append(" permission for system apps and does not allow any app to be granted it, ")
            append("so GURU cannot ")
            append(spec.consequence)
            append(". No setting and no setup on this phone changes that.")
        }

        GrantState.ABSENT -> buildString {
            append("The ")
            append(spec.label)
            append(" is unavailable because this device does not have it, so GURU cannot ")
            append(spec.consequence)
            append(" here.")
        }

        GrantState.UNKNOWN_SPEC -> buildString {
            append("Tool defect: the ")
            append(spec.label)
            append(" permission is not in GURU's catalogue, so this is a fault in the tool ")
            append("rather than something you can grant.")
        }
    }

    companion object {
        /**
         * Build a check from a resolved spec and a granted flag.
         *
         * Tiers that cannot be satisfied by a dialog are reported with their own
         * state even when [granted] is true, because a privilege tier that reads
         * as granted is more likely a stale manifest entry than a working call.
         * The tool then attempts the real call and reports what Android actually
         * did, which is the only evidence that counts.
         */
        fun of(spec: PermissionSpec, granted: Boolean): GrantCheck =
            of(spec, granted, developable = true)

        /**
         * The same check, told whether the platform marks this permission with the
         * `development` protection flag.
         *
         * WHY THIS PARAMETER EXISTS. A privileged permission without that flag
         * cannot be granted by any command at all, and reporting it as "Shizuku
         * can satisfy it" sends somebody through a setup that cannot work. The
         * caller reads the flag from the device, because the flag is a property of
         * the platform's own permission database rather than of this build.
         *
         * [developable] defaults to true in the overload above so an existing call
         * site that has not been taught about the flag keeps its previous
         * behaviour rather than silently changing what it tells the person.
         */
        fun of(spec: PermissionSpec, granted: Boolean, developable: Boolean): GrantCheck = when {
            // Granted is granted, whatever the tier says. A permission that reads
            // as held is held, so the tool runs and reports real data rather than
            // refusing on a tier that was decided before the grant was read.
            granted -> GrantCheck(spec, GrantState.GRANTED)
            spec.tier == PermissionTier.PRIVILEGED && !developable ->
                GrantCheck(spec, GrantState.NOT_GRANTABLE)
            spec.tier == PermissionTier.PRIVILEGED ->
                GrantCheck(spec, GrantState.PRIVILEGED)
            spec.tier == PermissionTier.INSTALL ->
                GrantCheck(spec, GrantState.INSTALL)
            else -> GrantCheck(spec, GrantState.NOT_GRANTED)
        }

        /**
         * A check for a permission the catalogue has never heard of.
         *
         * The spec is synthesised from the raw string so the sentence can still
         * name it, and the state makes clear this is not something to go and
         * grant. Guessing a route here is how a tool ends up telling someone to
         * open a settings page that has nothing to do with the problem.
         */
        fun unknown(permission: String): GrantCheck = GrantCheck(
            spec = PermissionSpec(
                permission = permission,
                tier = PermissionTier.RUNTIME,
                label = permission.substringAfterLast('.'),
                why = "GURU could use it if the tool were wired correctly",
                grantRoute = null,
            ),
            state = GrantState.UNKNOWN_SPEC,
        )

        /**
         * A check for hardware the device does not have.
         *
         * No permission is involved and nothing can be granted. The spec exists so
         * the sentence has a label and a consequence; the state prevents any
         * caller from treating it as a grant problem.
         */
        fun absent(permission: String, label: String, why: String): GrantCheck = GrantCheck(
            spec = PermissionSpec(
                permission = permission,
                tier = PermissionTier.HARDWARE,
                label = label,
                why = why,
                grantRoute = null,
            ),
            state = GrantState.ABSENT,
        )
    }
}
