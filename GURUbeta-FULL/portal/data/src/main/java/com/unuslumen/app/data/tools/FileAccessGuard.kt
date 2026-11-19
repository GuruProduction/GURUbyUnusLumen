// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.tools

import android.util.Log
import org.koin.core.annotation.Single
import java.io.File
import java.security.MessageDigest

@Single
class FileAccessGuard {
    companion object {
        private const val TAG = "FileAccessGuard"

        /**
         * Characters that put the following word in command position.
         *
         * The shell operators are the first five. The quotes, the dollar and the
         * equals matter for the script case: a Python script that writes
         * `subprocess.run("sed -i ...", shell=True)` has the command inside a
         * string literal, and a multi-line shell command starts a new command
         * after every newline.
         */
        private val COMMAND_POSITION_PRECEDERS: Set<Char> =
            setOf('|', '&', ';', '(', '\n', '"', '\'', '=', '$')
    }

    private val readFiles = mutableMapOf<String, String>()
    private val fullyReadFiles = mutableSetOf<String>()

    fun recordFileRead(path: String, content: String) {
        val normalized = File(path).absolutePath
        val hash = hashContent(content)
        readFiles[normalized] = hash
        fullyReadFiles.add(normalized)
        Log.d(TAG, "Recorded read: $normalized (hash: $hash, lines: ${content.lines().size})")
    }

    fun wasFileRead(path: String): Boolean {
        val normalized = File(path).absolutePath
        return fullyReadFiles.contains(normalized)
    }

    fun clearAll() {
        readFiles.clear()
        fullyReadFiles.clear()
    }

    fun clearFile(path: String) {
        val normalized = File(path).absolutePath
        readFiles.remove(normalized)
        fullyReadFiles.remove(normalized)
    }

    private fun hashContent(content: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val bytes = md.digest(content.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }.take(16)
    }

    /**
     * Whether a command INVOKES sed, and where.
     *
     * THE RULE IS "IS THIS WORD BEING RUN AS A COMMAND", NOT "DOES THIS STRING
     * CONTAIN THREE LETTERS" (issue 467).
     *
     * The previous implementation was four hand-written phrase tests aimed at
     * the same question and they were both too narrow and too wide:
     *
     *   - too narrow because they only knew `sed ` at the start of the string,
     *     `| sed `, `&& sed ` and `; sed `. A pipe with no trailing space, a
     *     newline-separated command, a `$(sed ...)` substitution or a
     *     subshell `(sed ...)` all passed straight through.
     *   - too wide in the one place it mattered elsewhere: the Python check in
     *     `ShellToolExecutor` used a bare `contains("sed")`, so a script that
     *     merely said "used", "passed", "based" or "closed" next to a
     *     `subprocess` call was refused as if it were stream-editing a file.
     *
     * The honest question has two halves, and both are needed:
     *
     *   1. the three letters are a whole token, so `sed` is followed by
     *      whitespace or the end of the command, which excludes `sed_config`,
     *      `sedscript` and the tail of `used`;
     *   2. that token sits where a command is allowed to start: at the
     *      beginning, or after an operator (`|`, `&`, `;`, `(`, newline), or
     *      after a quote in a script that is building a command string.
     *
     * Returns the index of the match, or null. The index is returned rather than
     * a bare boolean because the refusal then names where it matched, which is
     * what lets a caller rewrite one segment instead of abandoning the command.
     */
    fun findSedInvocation(command: String): Int? {
        val lower = command.lowercase()
        var from = 0
        while (from <= lower.length - 3) {
            val found = lower.indexOf("sed", from)
            if (found < 0) return null
            from = found + 1

            // Half one: a whole token. `sed -i`, `sed`, `sed\t...` all pass;
            // `sed_config` and `used` do not.
            val after = lower.getOrNull(found + 3)
            if (after != null && !after.isWhitespace()) continue

            // Half two: in command position. Skip backwards over whitespace to
            // the nearest significant character.
            var probe = found - 1
            while (probe >= 0 && lower[probe].isWhitespace() && lower[probe] != '\n') probe--
            val matched = when {
                probe < 0 -> true
                lower[probe] in COMMAND_POSITION_PRECEDERS -> true
                else -> false
            }
            if (matched) return found
        }
        return null
    }

    /** Whether the command invokes sed at all. Thin wrapper over the index. */
    fun checkSedBlocked(command: String): Boolean {
        val at = findSedInvocation(command)
        if (at == null) return false
        Log.w("guru", "BLOCKED sed invoked at index $at: $command")
        return true
    }

}
