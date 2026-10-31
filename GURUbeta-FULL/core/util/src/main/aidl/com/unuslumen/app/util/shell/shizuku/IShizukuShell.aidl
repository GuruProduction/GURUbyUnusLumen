// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.

package com.unuslumen.app.util.shell.shizuku;

// The ShellOutput parcelable has to be imported by its fully qualified name
// before it can be used as a return type, because AIDL resolves types through
// explicit imports rather than by package inference.
import com.unuslumen.app.util.shell.shizuku.ShellOutput;

// The shell service's binder surface.
//
// WHY AIDL RATHER THAN A MESSENGER. The call carries two values and returns a
// structured result, and this process runs in a different UID (shell, 2000) from
// the app. AIDL is the platform's own contract for that, and it makes the boundary
// explicit rather than serialising by hand and hoping both sides agree.
//
// execute() returns a ShellOutput rather than throwing for a failed command,
// because a non-zero exit is a real answer and the caller must be able to tell it
// apart from "the command never ran".
interface IShizukuShell {
    ShellOutput execute(String command, int timeoutSeconds);
}
