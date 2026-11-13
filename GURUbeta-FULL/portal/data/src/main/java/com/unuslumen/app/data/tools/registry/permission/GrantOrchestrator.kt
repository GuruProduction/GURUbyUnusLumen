// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.tools.registry.permission

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.unuslumen.app.util.permissions.Permission
import com.unuslumen.app.util.permissions.PermissionGateway
import com.unuslumen.app.util.permissions.isSpecial
import com.unuslumen.app.util.permissions.isSpecialGranted
import com.unuslumen.app.util.permissions.toAndroidPermission
import com.unuslumen.app.util.permissions.toSettingsIntent
import com.unuslumen.app.util.shell.shizuku.ShizukuState
import kotlinx.coroutines.withTimeoutOrNull

/**
 * GrantOrchestrator — the doing half of #172.
 *
 * IT NEVER REQUESTS ANYTHING WITHOUT SAYING SO. Every method returns a
 * [GrantOutcome] which states whether the grant landed, and the grant is only
 * reported as landed after a fresh read of the platform. A method that fired an
 * intent and returned success would be the exact defect this batch exists to
 * remove, one layer up.
 *
 * VERIFICATION IS PER ROUTE, NOT PER CALL. A dialog is verified by re-reading the
 * permission. A settings toggle is verified by reading the app-op, because the
 * screen closing means nothing. A role is verified with isRoleHeld. A service is
 * verified by reading the enabled-services list. Owner grants are verified with
 * getPermissionGrantState. Each verification below states why the obvious cheaper
 * check would be a lie.
 *
 * WHAT IT DELIBERATELY DOES NOT DO. It does not request a permission through
 * ActivityCompat, because that needs an Activity and a tool does not have one. The
 * dialog is reached by the broadcast that MainActivity already answers, so the
 * orchestrator composes with the existing gateway rather than replacing it.
 */
object GrantOrchestrator {

    /**
     * What happened when a grant was attempted.
     *
     * [check] is the post-attempt truth, re-read from the platform. [actionTaken]
     * records what was actually executed so a caller can tell "nothing needed
     * doing" from "a screen was opened" without inferring it from the message.
     */
    data class GrantOutcome(
        val plan: GrantPlan,
        val check: GrantCheck,
        val actionTaken: String,
        /** True only when the platform was re-read and reports the grant is held. */
        val landed: Boolean,
    ) {
        /** One sentence for the model: what happened, and what is true now. */
        fun sentence(): String = when {
            landed -> check.spec.let { "Granted, so ${it.why}." }
            plan.blockedBy != null -> plan.blockedBy!!
            else -> check.sentence()
        }
    }

    // ------------------------------------------------------------------
    // Environment
    // ------------------------------------------------------------------

    /**
     * Read the device facts the policy needs.
     *
     * WHY PER PERMISSION. [hasDevelopmentFlag] is a property of one permission in
     * the platform's database, not of the app or the build, so it cannot be read
     * once and cached across a list. The other three are app-level and cheap, but
     * they are read in the same place so a caller cannot forget one and get a plan
     * built on a stale assumption.
     */
    fun environment(context: Context, spec: PermissionSpec): GrantEnvironment {
        val packageManager = context.packageManager
        return GrantEnvironment(
            isDeviceOwner = isDeviceOwner(context),
            // READ, NOT ASSUMED. This was hardcoded false while the bridge that
            // answers it already worked and was already in use by the shell ladder,
            // so every privileged permission was told "there is no route" on a phone
            // where the route was sitting there authorised. A constant that says
            // "not shipped yet" outlives the work it was waiting for, and it fails in
            // the worst direction: it reports impossibility rather than an attempt.
            shizukuAvailable = shizukuAvailable(context),
            hasDevelopmentFlag = hasDevelopmentFlag(packageManager, spec.permission),
            // The role path needs a component declared for the role. Reading the
            // package manager is the only honest answer to whether one exists.
            roleRequestable = roleRequestable(context, spec),
        )
    }

    /**
     * Whether the shell-UID channel is genuinely usable right now.
     *
     * THREE STATES, ONE BOOLEAN, AND THE BOOLEAN IS THE NARROW ONE. The bridge
     * distinguishes not-installed, not-running and not-authorised because each needs a
     * different instruction, and only the last of those can be fixed by a tap. For a
     * grant decision the question is only "can a command run at shell UID this second",
     * so the state collapses to [ShizukuState.usable] here and the full sentence is
     * left to whoever reports the state to a person.
     */
    fun shizukuAvailable(context: Context): Boolean =
        runCatching { ShellAccess.available(context) }.getOrDefault(false)

    /**
     * Whether the app declares a component that could hold [spec]'s role.
     *
     * A role is not granted to a package, it is granted to a named component, and the
     * platform refuses a request from an app that declares none. Asking the package
     * manager means the answer comes from the installed manifest rather than from a
     * build flag somebody has to remember to flip.
     */
    fun roleRequestable(context: Context, spec: PermissionSpec): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val roleManager = context.getSystemService(Context.ROLE_SERVICE) as? RoleManager
            ?: return false
        val roleName = roleFor(spec.permission) ?: return false
        return runCatching { roleManager.isRoleAvailable(roleName) }.getOrDefault(false)
    }

    /**
     * Whether this app is Device Owner, which is the only condition under which
     * setPermissionGrantState can run.
     *
     * isDeviceOwnerApp is checked rather than isAdminActive because a plain device
     * administrator keeps no grant powers; reporting it as owner would lead the
     * policy to plan an owner grant that the platform then refuses.
     */
    fun isDeviceOwner(context: Context): Boolean = runCatching {
        val manager = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
            ?: return false
        manager.isDeviceOwnerApp(context.packageName)
    }.getOrDefault(false)

    /**
     * Whether the platform marks a permission with the `development` protection
     * flag, which is the flag that makes `pm grant` work.
     *
     * THE FACT THIS RESTS ON, from the platform's own permission documentation: a
     * permission is grantable with `pm grant` only when it carries the
     * `development` flag, and "any user can grant them permanently via adb". Shell
     * UID does not make a signature permission changeable on its own, which is why
     * #47's assumption that Shizuku can grant all 47 privileged permissions is
     * wrong for most of them.
     *
     * A permission the platform does not know returns false, which is the safe
     * direction: it reports "out of reach" rather than promising a route that
     * would fail on the device.
     */
    fun hasDevelopmentFlag(packageManager: PackageManager, permission: String): Boolean = runCatching {
        val info = packageManager.getPermissionInfo(permission, 0)
        // getProtectionFlags is API 28+. Below that the flag bits sit in
        // protectionLevel and masking is still correct, so the mask is used
        // directly rather than gating on the SDK and losing the answer.
        (info.protectionLevel and android.content.pm.PermissionInfo.PROTECTION_FLAG_DEVELOPMENT) != 0
    }.getOrDefault(false)

    // ------------------------------------------------------------------
    // Reading the truth
    // ------------------------------------------------------------------

    /**
     * The current state of one permission, routed through the correct read.
     *
     * A special permission's grant lives in an app-op or a Settings value, not in
     * checkSelfPermission, so reading the platform flag alone would report a
     * granted "Modify system settings" as missing. The bridge to the existing
     * Permission enum is what avoids a second table of special cases.
     */
    fun check(context: Context, spec: PermissionSpec): GrantCheck {
        val developable = hasDevelopmentFlag(context.packageManager, spec.permission)
        val held = isHeld(context, spec)
        return GrantCheck.of(spec, held, developable)
    }

    /**
     * Whether the platform reports this permission as held, by the read that suits
     * its tier.
     */
    fun isHeld(context: Context, spec: PermissionSpec): Boolean {
        toLegacyPermission(spec.permission)?.let { legacy ->
            if (legacy.isSpecial) return legacy.isSpecialGranted(context)
        }
        return ContextCompat.checkSelfPermission(context, spec.permission) ==
            PackageManager.PERMISSION_GRANTED
    }

    /**
     * The matching entry in the existing [Permission] enum, or null when this
     * permission has none.
     *
     * WHY BRIDGE RATHER THAN DUPLICATE. `PermissionsUtil` already holds the special
     * cases — which Settings screen, which app-op to read — and a second copy here
     * would drift from it. Matching on the platform string keeps one table.
     */
    fun toLegacyPermission(permission: String): Permission? =
        Permission.entries.firstOrNull { it.toAndroidPermission() == permission }

    // ------------------------------------------------------------------
    // The seven routes
    // ------------------------------------------------------------------

    /**
     * Attempt the grant for one permission and report what actually happened.
     *
     * A route that is already held short-circuits to a landed outcome with no
     * action, because opening a settings screen for a permission that is already
     * granted is an interruption with nothing to gain.
     */
    suspend fun grant(context: Context, spec: PermissionSpec): GrantOutcome {
        val plan = GrantPolicy.plan(spec, environment(context, spec))
        val before = check(context, spec)

        if (before.allowed) {
            return GrantOutcome(plan, before, actionTaken = "none, already held", landed = true)
        }
        if (!plan.canSucceed) {
            return GrantOutcome(plan, before, actionTaken = "none, no route can succeed", landed = false)
        }

        val action = when (plan.route) {
            GrantRoute.DEVICE_OWNER -> grantAsDeviceOwner(context, spec)
            GrantRoute.RUNTIME_DIALOG -> requestThroughGateway(context, spec)
            GrantRoute.APP_OP_SETTINGS -> openSettings(context, spec)
            GrantRoute.SERVICE_SETTINGS -> openSettings(context, spec)
            GrantRoute.ROLE -> GrantRouteReport.RoleNotAttempted
            GrantRoute.SHIZUKU -> grantThroughShellUid(context, spec)
            GrantRoute.SELF_GUARD -> GrantRouteReport.NothingToDo
            null -> GrantRouteReport.NothingToDo
        }

        // THE POINT OF THE WHOLE OBJECT. The platform is re-read after the attempt,
        // and only that read decides what is reported. An intent that started
        // proves the screen opened; it proves nothing about the grant.
        val after = check(context, spec)
        return GrantOutcome(
            plan = plan,
            check = after,
            actionTaken = action.describe(spec),
            landed = after.allowed,
        )
    }

    /**
     * What an attempt did, kept as a value so the report cannot claim an action
     * that was not taken.
     */
    sealed interface GrantRouteReport {
        /** The grant was set through DevicePolicyManager and returns its own result. */
        data class OwnerSet(val applied: Boolean) : GrantRouteReport

        /** A broadcast was sent for MainActivity to answer; the dialog is pending. */
        data object DialogRequested : GrantRouteReport

        /** A Settings screen was opened for the person to toggle. */
        data class SettingsOpened(val screen: String) : GrantRouteReport

        /** The role path needs a component this build does not declare. */
        data object RoleNotAttempted : GrantRouteReport

        /**
         * The shell command ran, and this is exactly what it said.
         *
         * The command, its exit code and its output are carried rather than reduced to a
         * boolean because the three ways this fails need different answers: a `pm grant`
         * that exits non-zero because the permission carries no development flag, one that
         * exits zero and changed nothing, and one that never returned at all. A boolean
         * would call all three "did not work" and leave the person guessing which.
         */
        data class ShellUidRan(
            val command: String,
            val exitCode: Int,
            val output: String,
        ) : GrantRouteReport

        /** Nothing needed doing, which is a real answer for the self-guard tier. */
        data object NothingToDo : GrantRouteReport

        fun describe(spec: PermissionSpec): String = when (this) {
            is OwnerSet -> if (applied) {
                "set the ${spec.label} grant through Device Owner"
            } else {
                "asked Device Owner to set the ${spec.label} grant and the platform refused"
            }
            DialogRequested -> "opened the ${spec.label} permission dialog"
            is SettingsOpened -> "opened $screen and left the toggle to you"
            RoleNotAttempted -> "did not request the ${spec.label} role, because this build declares no component for it"
            is ShellUidRan -> if (exitCode == 0) {
                "ran $command through the shell UID, which exited 0" +
                    (if (output.isNotBlank()) " saying: $output" else "")
            } else {
                "ran $command through the shell UID, which exited $exitCode" +
                    (if (output.isNotBlank()) " saying: $output" else "")
            }
            NothingToDo -> "took no action for ${spec.label}, because nothing needs granting"
        }
    }

    /**
     * Grant through Device Owner, which is silent and needs no dialog.
     *
     * setPermissionGrantState applies to runtime permissions only. That is why the
     * policy sends only RUNTIME here: an owner call for a signature permission
     * throws or is ignored, and reporting its attempted value as a success would be
     * a manufactured green.
     */
    private fun grantAsDeviceOwner(context: Context, spec: PermissionSpec): GrantRouteReport {
        val manager = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
            ?: return GrantRouteReport.OwnerSet(applied = false)
        if (!manager.isDeviceOwnerApp(context.packageName)) {
            return GrantRouteReport.OwnerSet(applied = false)
        }
        // setPermissionGrantState returns true when the request was applied, not a
        // grant-state constant. Comparing its boolean against the int constant is
        // the mistake this comment exists to stop somebody repeating.
        val applied = runCatching {
            val admin = ComponentName(context.packageName, DEVICE_ADMIN_RECEIVER)
            manager.setPermissionGrantState(
                admin,
                context.packageName,
                spec.permission,
                DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED,
            )
        }.getOrDefault(false)
        return GrantRouteReport.OwnerSet(applied)
    }

    /**
     * Grant through the shell UID, which is the only route for a `development`-flagged
     * privileged permission.
     *
     * SUSPEND RATHER THAN BLOCK. The bridge's execute is a suspend function, and the
     * obvious shortcut is runBlocking to keep this signature. That would freeze whatever
     * thread called it, and the caller is a tool running on a background dispatcher today
     * but a settings screen tomorrow. Making the grant path suspend costs one `suspend`
     * keyword on two functions and removes the possibility entirely.
     *
     * THE COMMAND IS BUILT FROM THE APP'S OWN PACKAGE AND THE PERMISSION STRING. Both come
     * from the platform rather than from a model, so there is no injection surface: the
     * worst a corrupt value can do is make `pm grant` fail, and the verification afterwards
     * then reports that it did not land.
     *
     * THE VERDICT IS NEVER TAKEN FROM THIS COMMAND. A zero exit proves the shell ran the
     * binary, not that the platform accepted the grant, so [grant] re-reads the permission
     * afterwards and that read is what decides `landed`.
     */
    private suspend fun grantThroughShellUid(
        context: Context,
        spec: PermissionSpec,
    ): GrantRouteReport {
        val command = "pm grant ${context.packageName} ${spec.permission}"
        val result = withTimeoutOrNull(SHELL_GRANT_TIMEOUT_MS) {
            ShellAccess.bridge(context).execute(command)
        } ?: return GrantRouteReport.ShellUidRan(
            command = command,
            exitCode = -1,
            output = "the shell command did not return within ${SHELL_GRANT_TIMEOUT_MS / 1000} seconds",
        )

        return GrantRouteReport.ShellUidRan(
            command = command,
            exitCode = result.exitCode,
            output = (result.stdout.ifBlank { result.stderr }).trim().take(300),
        )
    }

    /**
     * Ask MainActivity to fire the dialog, through the gateway that already exists.
     *
     * The broadcast is not the grant. MainActivity answers it, the person taps, and
     * the result comes back on a separate broadcast that the caller is watching.
     * This method therefore reports "dialog opened" and the caller re-checks.
     */
    private fun requestThroughGateway(context: Context, spec: PermissionSpec): GrantRouteReport {
        val legacy = toLegacyPermission(spec.permission)
            ?: return GrantRouteReport.NothingToDo
        PermissionGateway.requestPermission(context, legacy)
        return GrantRouteReport.DialogRequested
    }

    /**
     * Open the Settings screen that holds the toggle, named so a report can say
     * which one. Falls back to the app's own settings page when the specific screen
     * is missing on a stripped build, which is the same fallback the gateway uses.
     */
    private fun openSettings(context: Context, spec: PermissionSpec): GrantRouteReport {
        val legacy = toLegacyPermission(spec.permission)
            ?: return GrantRouteReport.NothingToDo
        val intent: Intent = legacy.toSettingsIntent(context).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val opened = runCatching {
            context.startActivity(intent)
            true
        }.getOrDefault(false)
        if (opened) {
            return GrantRouteReport.SettingsOpened(screenFor(spec.permission))
        }
        val fallback = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = android.net.Uri.parse("package:" + context.packageName)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        runCatching { context.startActivity(fallback) }
        return GrantRouteReport.SettingsOpened(screenFor(spec.permission))
    }

    /**
     * The human name of the Settings screen a permission lives on, for the report.
     *
     * Kept beside the intent rather than derived from the permission string,
     * because "APP_OPS" and "the App ops screen" are different things to a person
     * and only the second one is useful in a sentence.
     */
    fun screenFor(permission: String): String = when (permission) {
        Manifest.permission.WRITE_SETTINGS -> "the Modify system settings screen"
        Manifest.permission.SYSTEM_ALERT_WINDOW -> "the Display over other apps screen"
        "android.permission.MANAGE_EXTERNAL_STORAGE" -> "the All files access screen"
        "android.permission.PACKAGE_USAGE_STATS" -> "the Usage access screen"
        "android.permission.REQUEST_INSTALL_PACKAGES" -> "the Install unknown apps screen"
        "android.permission.ACCESSIBILITY_SERVICE" -> "the Accessibility screen"
        "android.permission.NOTIFICATION_LISTENER" -> "the Notification access screen"
        "android.permission.SCHEDULE_EXACT_ALARM" -> "the Alarms and reminders screen"
        else -> "the GURU app permissions screen"
    }

    /**
     * The device-admin receiver component name.
     *
     * Declared here as a constant rather than looked up, because the orchestrator
     * must name the admin component exactly and a typo would surface as a grant
     * that silently does nothing. The receiver itself lands with the owner path in
     * this same issue; until it exists, [isDeviceOwner] returns false on every
     * device and the owner branch is never taken.
     */
    private const val DEVICE_ADMIN_RECEIVER = "com.unuslumen.app.admin.GuruDeviceAdminReceiver"

    /**
     * How long a `pm grant` may take before the attempt is reported as unanswered.
     *
     * The bridge already has its own 30-second command timeout. This is deliberately shorter
     * and exists for the case the bridge cannot cover: a bind that never connects, where no
     * command was ever sent and therefore no command-level timeout can fire.
     */
    private const val SHELL_GRANT_TIMEOUT_MS = 45_000L

    /**
     * The platform role that carries a permission, or null when it carries none.
     *
     * A role is the only route by which some capabilities reach an ordinary app, and the
     * role name is a platform string rather than an app string, so the mapping belongs
     * beside the policy that consumes it.
     */
    fun roleFor(permission: String): String? = when (permission) {
        Manifest.permission.READ_SMS,
        Manifest.permission.SEND_SMS,
        Manifest.permission.RECEIVE_SMS -> RoleManager.ROLE_SMS

        Manifest.permission.CALL_PHONE -> RoleManager.ROLE_DIALER

        // The three role-gated capabilities GURU's own manifest can plausibly hold. The
        // platform role ids are used directly rather than as string literals, so a
        // platform rename is a compile error rather than a role request that silently
        // matches nothing.
        "android.permission.ROLE_CALL_SCREENING" -> RoleManager.ROLE_CALL_SCREENING
        "android.permission.ROLE_ASSISTANT" -> RoleManager.ROLE_ASSISTANT

        else -> null
    }
}
