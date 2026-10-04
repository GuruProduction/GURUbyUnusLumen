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

    fun checkSedBlocked(command: String): Boolean {
        val lower = command.lowercase().trim()
        if (lower == "sed" || lower.startsWith("sed ") || lower.startsWith("sed\t")) {
            Log.w("guru", "BLOCKED sed command: " + command)
            return true
        }
        if ("| sed " in lower || "| sed\t" in lower || "|sed " in lower) {
            Log.w("guru", "BLOCKED sed command: " + command)
            return true
        }
        if ("&& sed " in lower || "&& sed\t" in lower || "&&sed " in lower) {
            Log.w("guru", "BLOCKED sed command: " + command)
            return true
        }
        if ("; sed " in lower || "; sed\t" in lower || ";sed " in lower) {
            Log.w("guru", "BLOCKED sed command: " + command)
            return true
        }
        return false
    }
}
