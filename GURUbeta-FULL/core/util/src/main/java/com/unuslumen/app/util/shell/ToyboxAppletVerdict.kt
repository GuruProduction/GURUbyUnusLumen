// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.util.shell

/**
 * Whether an advertised toybox applet may stay on PATH (issue 463).
 *
 * THE DEFECT. The symlink directory carried around two hundred applet names, and
 * `command -v awk` reported one of them, so every capability check in the codebase
 * believed awk was usable. Running it gave:
 *
 *     toybox: Unknown command awk (see "toybox --help")
 *     exitCode 127
 *
 * The measured split is arbitrary and not guessable: curl, cut, tar, sqlite3, find,
 * xargs, base64, xxd and nc run, while awk, tr, grep, sort, wget, hexdump and unzip
 * do not. `wget` and `vi` had no symlink at all.
 *
 * WHY THE PRESENCE CHECK WAS NEVER ENOUGH. A file existing at a path says the file
 * exists. It does not say the multi-call binary will answer to that name, and it
 * certainly does not say the applet is compiled into this build. The only honest
 * test is to run it.
 *
 * THE RULE THIS ENCODES. An applet that fails at start-up is removed from the
 * directory, so `command -v` reports absence and an agent's own capability check is
 * correct. A PATH entry that resolves to a file which cannot serve that name is worse
 * than no entry, because it defeats every check written against it and turns a
 * missing tool into a mystery.
 */
object ToyboxAppletVerdict {

    /** The exit code toybox uses when it does not know the applet name. */
    const val UNKNOWN_APPLET_EXIT = 127

    /**
     * Keep or drop one applet, given what running it produced.
     *
     * `exitCode` is null when the process could not be started at all, which is a
     * dangling or unreadable link and is dropped for the same reason: nothing can
     * serve that name.
     *
     * `stderr` is used only to make the drop legible in the log, never to decide. The
     * exit code is the fact; the wording is decoration.
     */
    fun decide(exitCode: Int?, stderr: String?): Verdict {
        if (exitCode == null) {
            return Verdict.Drop("the process could not be started, so the link is dangling")
        }
        return when {
            exitCode == UNKNOWN_APPLET_EXIT ->
                Verdict.Drop(
                    "toybox refused the name with 127" +
                        (stderr?.trim()?.takeIf { it.isNotEmpty() }
                            ?.let { ": ${it.take(120)}" } ?: ""),
                )
            // EVERY OTHER EXIT CODE MEANS THE APPLET RAN. `awk` with no program exits
            // 2, `grep` with no pattern exits 2, `sed` with no script exits 1, and all
            // three are working applets complaining about their arguments. Dropping on
            // any non-zero exit would remove every tool that has required arguments,
            // which is most of them.
            else -> Verdict.Keep
        }
    }

    /** What to do with one applet's symlink. */
    sealed interface Verdict {
        /** The applet runs; the symlink stays and may be offered on PATH. */
        data object Keep : Verdict

        /** The applet cannot serve its name; the symlink is removed. */
        data class Drop(val reason: String) : Verdict
    }
}
