// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.

package com.unuslumen.app.util.shell.shizuku;

// The result of one shell command, across the binder.
//
// exitCode is carried even when success is false, because -1 meaning "never ran"
// and a real non-zero exit meaning "ran and failed" are different facts. A caller
// that only saw success=false would retry a command that cannot succeed.
//
// The Kotlin class of this name is the parcelable implementation; this declaration
// is what tells AIDL the type exists and may cross the boundary.
parcelable ShellOutput;
