// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.util.shell.shizuku

import android.os.Parcel
import android.os.Parcelable
import com.unuslumen.app.util.shell.ShellResult

/**
 * One shell command's outcome, as it crosses the binder back to the app.
 *
 * WHY THIS IS NOT [ShellResult] ITSELF. ShellResult carries the tier that served
 * the command, which is a fact about the app's own routing rather than about the
 * shell service. The service cannot know which tier it is; the caller decides that
 * after the result arrives. Keeping them separate stops the service asserting
 * something it has no way to observe.
 */
data class ShellOutput(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
) : Parcelable {

    /** True only when the command ran to completion and returned zero. */
    val success: Boolean get() = exitCode == 0

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeInt(exitCode)
        dest.writeString(stdout)
        dest.writeString(stderr)
    }

    override fun describeContents(): Int = 0

    /** The app-side form, with the tier stamped by whoever ran the command. */
    fun toShellResult(): ShellResult = ShellResult(
        exitCode = exitCode,
        stdout = stdout,
        stderr = stderr,
        success = success,
    )

    companion object CREATOR : Parcelable.Creator<ShellOutput> {
        override fun createFromParcel(parcel: Parcel): ShellOutput = ShellOutput(
            exitCode = parcel.readInt(),
            stdout = parcel.readString().orEmpty(),
            stderr = parcel.readString().orEmpty(),
        )

        override fun newArray(size: Int): Array<ShellOutput?> = arrayOfNulls(size)
    }
}
