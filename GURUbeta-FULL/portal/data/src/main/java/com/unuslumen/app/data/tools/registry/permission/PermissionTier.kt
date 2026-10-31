// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.tools.registry.permission

/**
 * How a permission can be granted on this device.
 *
 * The distinction is the difference between "you have not given me this yet" and
 * "you cannot give me this through a dialog at all". Those are two different
 * answers for the person reading the tool result, and a tool that reports the
 * wrong one sends them looking for a screen that will never appear.
 *
 * RUNTIME      A dangerous permission granted through the normal Android dialog.
 *              The path that works today on every device with a user present.
 *
 * INSTALL      protectionLevel=normal. Granted automatically at install with no
 *              dialog of any kind. A tool that tells someone to "grant" one of
 *              these sends them hunting for a screen that does not exist, so the
 *              honest report is that it is already held and a denial would mean
 *              the manifest lost the declaration.
 *
 * PRIVILEGED   signature, privileged or system-level. A normal app cannot be
 *              granted it by any dialog, and Android refuses the call underneath
 *              even when the manifest declares it.
 *
 *              THE CORRECTION THIS TIER NEEDS. The batch brief and #47 both assume
 *              Shizuku or Device Owner can grant any of these. They cannot. The
 *              platform documentation states that a permission is grantable with
 *              `pm grant` only when it carries the `development` protection flag;
 *              shell UID does not make a signature permission changeable, and
 *              setPermissionGrantState handles runtime permissions, not signature
 *              ones. So this tier carries the answer "not grantable by any
 *              command" for most of its members, and saying that plainly is the
 *              only honest report. See GrantPolicy for how the flag decides it.
 *
 * APPOP        Granted through a Settings toggle rather than a dialog, such as
 *              "Modify system settings" or "Display over other apps". The grant
 *              route is a specific settings screen and the message names it.
 *
 * ROLE         Granted by holding a platform role, such as the dialler. The
 *              person grants it in Settings and the app cannot self-assign it.
 *
 * SERVICE      Binds one of GURU's own services rather than granting a capability:
 *              the accessibility service and the notification listener. There is
 *              no dialog and no app-op; the person switches the service on in a
 *              Settings screen and the platform reports it in Settings.Secure.
 *
 * SELF_GUARD   Protects a component GURU owns, such as BIND_ACCESSIBILITY_SERVICE
 *              on GURU's own service. There is nothing to grant and nothing to
 *              check at runtime: a denial means the manifest lost the declaration,
 *              so it is reported as a build fault rather than a permission to go
 *              and allow.
 *
 * HARDWARE     No permission is involved at all. The device does not carry the
 *              feature. Reported in one sentence, never as a zero reading.
 */
enum class PermissionTier {
    RUNTIME,
    INSTALL,
    PRIVILEGED,
    APPOP,
    ROLE,
    SERVICE,
    SELF_GUARD,
    HARDWARE,
}
