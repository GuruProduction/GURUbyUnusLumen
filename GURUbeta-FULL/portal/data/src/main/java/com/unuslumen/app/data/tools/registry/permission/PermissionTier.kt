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
 *              even when the manifest declares it. GURU names the tier honestly;
 *              Shizuku (#173) and Device Owner are the only paths that can
 *              satisfy it, and this code must never pretend otherwise.
 *
 * APPOP        Granted through a Settings toggle rather than a dialog. The grant
 *              route is a specific settings screen and the message names it.
 *
 * ROLE         Granted by holding a platform role, such as the dialler. The
 *              person grants it in Settings and the app cannot self-assign it.
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
    HARDWARE,
}
