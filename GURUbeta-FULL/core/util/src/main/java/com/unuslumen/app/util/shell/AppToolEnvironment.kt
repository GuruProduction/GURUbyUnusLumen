// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.util.shell

import android.content.Context
import java.io.File

/**
 * The directories where the app's own executables actually live, and the
 * environment a shell launched by the app must carry so it can see them.
 *
 * THE DEFECT THIS EXISTS FOR (issue 300). A GURU-defined tool whose
 * implementation is a shell command ran through
 * `Runtime.getRuntime().exec(arrayOf("sh", "-c", command))`, which inherits the
 * app process's PATH. That PATH is Android's own
 * (`/product/bin:/apex/...:/system/bin:...`) and contains none of the app's
 * directories, so every binary the app ships was invisible:
 *
 *   PATH=/product/bin:/apex/com.android.runtime/bin:...:/system/xbin
 *   curl: MISSING     python3: MISSING     busybox: MISSING
 *
 * while `busyboxExec` on the same device resolved
 * `/data/user/0/<pkg>/files/toybox-bin/curl` from the same prefix. The binaries
 * were never missing. The environment was.
 *
 * WHY THIS IS ONE OBJECT rather than three inline strings. `busyboxExec`
 * already prepends two of these directories in three separate command strings,
 * `ToyboxProvider` owns the symlink directory, and `TermuxProvider` has its own
 * `buildEnv` for the prefix. Each is correct for its own call and none of them
 * is reachable from a different entry point, which is exactly how a new path
 * ends up with no environment at all. This is the single definition the shell
 * tool path reads, so a future path has one place to look.
 *
 * DELIBERATELY NOT A PRIVILEGE ESCALATION. This only adds search directories to
 * the same app-UID exec the tool already used. It does not route through
 * [ShellExecutor], so a GURU-defined shell tool keeps exactly the tier it had.
 */
object AppToolEnvironment {

    /**
     * The app-owned binary directories, in search order.
     *
     * `toybox-bin` comes first because it holds the friendly names — `curl`,
     * `git`, `jq`, `rsync` and every toybox applet — as symlinks onto the real
     * `libguru_*.so` in `nativeLibraryDir`. The symlink name is what makes a
     * multi-call binary dispatch correctly and what makes a plain `curl` on
     * PATH resolve at all.
     *
     * `nativeLibraryDir` is second because that is the only directory on modern
     * Android where an app-owned ELF may be executed, and it is where the
     * launcher binaries themselves sit.
     *
     * The Termux prefix is last and only when it exists, because it is the
     * optional one: a fresh install has not extracted it yet.
     */
    fun appBinaryDirs(context: Context): List<String> {
        val dirs = mutableListOf<String>()
        dirs += File(context.filesDir, "toybox-bin").absolutePath
        context.applicationInfo?.nativeLibraryDir?.let { dirs += it }
        val termuxBin = File(File(context.filesDir, "termux"), "usr/bin")
        if (termuxBin.isDirectory) dirs += termuxBin.absolutePath
        return dirs
    }

    /**
     * A complete environment for one exec, with the app's binaries on PATH.
     *
     * The current process environment is COPIED rather than replaced, because
     * `Runtime.exec` with a non-null env array discards everything not in it,
     * and Android's own `sh` needs the platform variables it started with.
     * Prepend-and-keep is also exactly what `busyboxExec` already proves works
     * on device: `PATH=<symlinks>:<nativeLib>:$PATH`.
     */
    fun env(context: Context): Map<String, String> {
        val inherited = LinkedHashMap(System.getenv())
        val existingPath = inherited["PATH"]
            ?.takeIf { it.isNotBlank() }
            ?: "/system/bin:/system/xbin"
        inherited["PATH"] = (appBinaryDirs(context) + existingPath).joinToString(":")
        // HOME and TMPDIR are stated because a shell tool that writes a temp file
        // or reads a dotfile otherwise gets whatever the app process inherited,
        // which is not a directory the app owns.
        inherited["HOME"] = context.filesDir.absolutePath
        inherited["TMPDIR"] = context.cacheDir.absolutePath
        return inherited
    }

    /** The PATH one exec would see, for reporting when a command is not found. */
    fun path(context: Context): String = env(context)["PATH"].orEmpty()
}
