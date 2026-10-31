// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.admin

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent

/**
 * GuruDeviceAdminReceiver — the component Android needs before GURU can hold any
 * Device Owner power at all.
 *
 * WHY IT EXISTS BEFORE THE POWER IS USED. `setPermissionGrantState` names an admin
 * component, and Device Owner is a property of that component rather than of the
 * application id. Without a declared receiver there is no component to name, so
 * the owner route cannot run however the provisioning flow is built. This is the
 * piece that makes the seventh route real rather than a branch that is always
 * false.
 *
 * WHAT IT DOES NOT DO. It does not request ownership. Becoming Device Owner needs
 * managed provisioning, which cannot be started from inside the app while it is
 * already installed: the owner is assigned during setup of a freshly provisioned
 * device or through `adb shell dpm set-device-owner`. So this receiver is passive,
 * and [isOwner] answers truthfully about whether the power is held.
 *
 * The callbacks below are deliberately quiet. A device-admin receiver that logs
 * loudly on every enable and disable turns a Settings visit into log noise, and
 * there is nothing here worth recording that `DevicePolicyManager.isDeviceOwnerApp`
 * cannot report on demand.
 */
class GuruDeviceAdminReceiver : DeviceAdminReceiver() {

    companion object {
        /**
         * Whether GURU is currently the Device Owner.
         *
         * Reads `isDeviceOwnerApp` rather than `isAdminActive`, because a plain
         * active administrator holds no grant powers. Returning true for one would
         * plan an owner grant the platform then refuses, which is exactly the kind
         * of manufactured success this batch exists to remove.
         */
        fun isOwner(context: Context): Boolean = runCatching {
            val manager = context.getSystemService(Context.DEVICE_POLICY_SERVICE)
                as? android.app.admin.DevicePolicyManager ?: return false
            manager.isDeviceOwnerApp(context.packageName)
        }.getOrDefault(false)
    }

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
    }
}
