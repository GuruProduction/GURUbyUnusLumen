// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.util.permissions

import android.app.AppOpsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process

/**
 * Whether Android is refusing to let GURU's own accessibility service and
 * notification listener be switched on, and what to tell the person about it.
 *
 * THE DEFECT THIS EXISTS FOR, measured on an API 37 emulator on 2026-10-31.
 * Sideloading an app sets the `ACCESS_RESTRICTED_SETTINGS` app-op to `default`,
 * and the platform then refuses to grant a restricted capability (accessibility,
 * notification listener, and several others) from the Settings UI at all. The
 * screen simply refuses, or the toggle snaps back, with a dialog reading "App was
 * denied access".
 *
 * GURU reported that as a plain permission denial. The person then went looking
 * for a permission to grant and there was nothing on the screen to grant, because
 * the block is not that kind of permission.
 *
 * WHY THE ANSWER IS "ALLOW RESTRICTED SETTINGS", NOT "GRANT THE PERMISSION".
 * The app-op is not a grant the app can perform or request. Only the person can
 * clear it, from the app's own App info screen, via the three-dot menu item
 * "Allow restricted settings". An app cannot set its own app-op and must not
 * pretend it can.
 *
 * THE SIGNAL, and why it is the app-op rather than a guess. On the emulator the
 * release build reported `ACCESS_RESTRICTED_SETTINGS: default; rejectTime=+11h…`,
 * where `rejectTime` is the platform recording that it refused a request. A
 * sideloaded install is what puts it there: `installerPackageName=null` with
 * `initiatingPackageName=com.android.shell`. The app-op therefore says exactly
 * what happened, and reading it costs one query.
 *
 * Deliberately split so the decision is pure: [decide] takes plain facts and
 * returns the answer, and [of] gathers those facts from the device. The rule is
 * then JVM-testable with no Robolectric, which matters because it decides what
 * someone is told about a permission they cannot grant.
 */
object RestrictedSettings {

    /**
     * What the app-op says about this app.
     *
     * [MODE_DEFAULT] is the restricted case on a sideloaded install: the platform
     * has not been told to allow it, so it refuses. [MODE_ALLOWED] means the person
     * has already cleared it. [MODE_IGNORED] means the platform explicitly denies,
     * which is a fleet policy rather than a sideload, so the answer names that.
     * [UNKNOWN] is kept distinct because a query that failed must never be
     * reported as a restricted app: that would tell someone to go and fix
     * something that is not broken.
     */
    enum class AppOpState {
        ALLOWED,
        DEFAULT,
        IGNORED,
        UNKNOWN,
    }

    /**
     * The answer for one restricted capability.
     *
     * [blockedByRestrictedSettings] is the flag callers branch on. [sentence] is
     * the one thing the person is told, and it is never a bare denial: it names
     * the menu item and where it is.
     */
    data class Verdict(
        val blockedByRestrictedSettings: Boolean,
        val state: AppOpState,
        /** True when the capability itself is already granted, whatever the app-op says. */
        val alreadyGranted: Boolean,
        val sentence: String,
    )

    /**
     * The pure decision. No Android, so the rules are provable on the JVM.
     *
     * A capability that is genuinely already on is reported as allowed no matter
     * what the app-op says, because the grant is the fact and the app-op is the
     * mechanism. Getting that order wrong would tell someone to clear a block on
     * something that is already working.
     */
    fun decide(
        capabilityLabel: String,
        alreadyGranted: Boolean,
        state: AppOpState,
        settingsMenuItemKnown: Boolean = true,
    ): Verdict {
        if (alreadyGranted) {
            return Verdict(
                blockedByRestrictedSettings = false,
                state = state,
                alreadyGranted = true,
                sentence = "$capabilityLabel is on.",
            )
        }

        // Only DEFAULT is the sideload block. ALLOWED with the capability still off
        // means they simply have not switched it on yet, which is an ordinary denial
        // and must not be dressed up as a block.
        val blocked = state == AppOpState.DEFAULT || state == AppOpState.IGNORED

        if (!blocked) {
            return Verdict(
                blockedByRestrictedSettings = false,
                state = state,
                alreadyGranted = false,
                sentence = "$capabilityLabel is off. Switch it on in Settings.",
            )
        }

        // IGNORED is a deliberate device policy, not the sideload default, so the
        // menu item will not be there and telling someone to look for it would send
        // them hunting for something that does not exist on their phone.
        val sentence = if (state == AppOpState.IGNORED) {
            "Android is blocking $capabilityLabel for GURU, and the block on this phone " +
                "was set deliberately rather than by the installer, so the Allow restricted " +
                "settings menu item will not be present. A device policy on this phone has " +
                "to allow it."
        } else if (settingsMenuItemKnown) {
            "Android is blocking $capabilityLabel for GURU because the app was installed " +
                "outside the Play Store, which switches on a protection called restricted " +
                "settings. Nothing on this screen can grant it. Open Settings > Apps > GURU, " +
                "tap the three-dot menu at the top right, choose Allow restricted settings, " +
                "then switch $capabilityLabel on again."
        } else {
            "Android is blocking $capabilityLabel for GURU because the app was installed " +
                "outside the Play Store, which switches on a protection called restricted " +
                "settings. It can only be cleared from GURU's App info screen in Settings: " +
                "open the three-dot menu at the top right and choose Allow restricted settings."
        }

        return Verdict(
            blockedByRestrictedSettings = true,
            state = state,
            alreadyGranted = false,
            sentence = sentence,
        )
    }

    /**
     * Read the app-op for this app and decide.
     *
     * [capabilityLabel] is what the person recognises ("Accessibility",
     * "Notification access") and [alreadyGranted] comes from the caller's own
     * check, so this adds the restricted-settings question rather than replacing
     * the ordinary one.
     */
    fun of(
        context: Context,
        capabilityLabel: String,
        alreadyGranted: Boolean,
    ): Verdict {
        val state = when {
            // The op does not exist before Android 13, so no app can be restricted
            // by it and claiming otherwise would be wrong on every older phone.
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU -> AppOpState.ALLOWED
            else -> readAppOp(context)
        }
        return decide(
            capabilityLabel = capabilityLabel,
            alreadyGranted = alreadyGranted,
            state = state,
            settingsMenuItemKnown = isMenuLikelyPresent(context),
        )
    }

    /**
     * Whether the "Allow restricted settings" menu item is likely to be offered.
     *
     * The platform shows it only for an app whose installer is unrecognised, which
     * in practice means a sideload. A build installed from the Play Store, or one
     * installed by the platform itself, is not restricted and has no menu item, so
     * telling someone to look for one would be wrong.
     *
     * Reads the installer package name because that is the fact that decides it.
     * The shell installer counts as sideloaded, which is exactly how a debug build
     * and an `adb install` arrive.
     */
    private fun isMenuLikelyPresent(context: Context): Boolean = try {
        val installer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            context.packageManager.getInstallSourceInfo(context.packageName).installingPackageName
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getInstallerPackageName(context.packageName)
        }
        // Null installer and com.android.shell are both sideloads; anything else is a
        // real store and is not restricted.
        installer == null || installer == "com.android.shell"
    } catch (e: Exception) {
        // A failed install-source query must not change the advice, so assume the
        // menu is there: the sentence it produces is the one that helps if it is,
        // and the IGNORED branch already covers the case where it is not.
        true
    }

    /**
     * The app-op name, as a raw string because there is NO public constant for it.
     *
     * `javap` against android-37's `AppOpsManager` lists every public `OPSTR_*`
     * constant and `OPSTR_ACCESS_RESTRICTED_SETTINGS` is not among them, so writing
     * `AppOpsManager.OPSTR_ACCESS_RESTRICTED_SETTINGS` does not compile on any SDK
     * level. The op itself exists and responds to this name: measured on an API 37
     * emulator, both `cmd appops get <pkg> ACCESS_RESTRICTED_SETTINGS` and
     * `... android:access_restricted_settings` returned the op with its rejectTime,
     * and the platform's own dumpsys prints it without the `android:` prefix.
     *
     * The platform-namespaced form is the one `unsafeCheckOpNoThrow` resolves, and
     * the bare name is accepted by the shell tool only, which is why the namespaced
     * string is the one used here.
     */
    private const val OP_ACCESS_RESTRICTED_SETTINGS = "android:access_restricted_settings"

    private fun readAppOp(context: Context): AppOpState {
        val opName = OP_ACCESS_RESTRICTED_SETTINGS

        return try {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
                ?: return AppOpState.UNKNOWN
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appOps.unsafeCheckOpNoThrow(opName, Process.myUid(), context.packageName)
            } else {
                @Suppress("DEPRECATION")
                appOps.checkOpNoThrow(opName, Process.myUid(), context.packageName)
            }
            when (mode) {
                AppOpsManager.MODE_ALLOWED -> AppOpState.ALLOWED
                AppOpsManager.MODE_IGNORED -> AppOpState.IGNORED
                AppOpsManager.MODE_DEFAULT -> AppOpState.DEFAULT
                else -> AppOpState.UNKNOWN
            }
        } catch (e: Exception) {
            // UNKNOWN rather than DEFAULT on failure. Reporting a failed probe as a
            // block is the same defect as issue 30's apt probe: the probe measuring
            // its own missing environment and calling the binary absent.
            AppOpState.UNKNOWN
        }
    }
}
