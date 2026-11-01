// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.fabric.ir

import android.content.Context
import android.util.Log

/**
 * Loading the bundled code library and a person's own import. Issue #187.
 *
 * THE BUNDLE IS AN ASSET, NOT A TABLE IN CODE. A code library is data that changes
 * with the world's televisions, and shipping it as an asset means updating it is a
 * file change rather than a code change, and a person can inspect exactly what was
 * shipped.
 *
 * WHAT IS LOADED AND WHEN. The bundled files are read once on first use, because a
 * tool call must not parse megabytes of text. A failed parse of one file does not
 * stop the others: each file's problems are collected and reported together, so a
 * malformed third file does not silently remove the first two.
 *
 * FORMAT DETECTION IS BY CONTENT, NOT BY EXTENSION. The three formats are told
 * apart by what the text starts with, because people rename files and an extension
 * is not evidence.
 */
class IrLibraryLoader(
    private val context: Context,
    private val assetDir: String = "ir",
) {

    private companion object {
        const val TAG = "guru_ir"
    }

    /** The asset names this build ships, so a missing file is a named fault. */
    private val assetNames = listOf("flipper_tv.ir", "flipper_ac.ir")

    /**
     * Parse every bundled asset plus any imported text.
     *
     * Returns the families and every problem together, because reporting only the
     * families would hide a file that failed to parse and a person would see a
     * library that is mysteriously short.
     */
    fun load(importedTexts: List<Pair<String, String>> = emptyList()): IrParseResult {
        val families = mutableListOf<IrDeviceFamily>()
        val problems = mutableListOf<String>()

        for (name in assetNames) {
            val text = runCatching {
                context.assets.open("$assetDir/$name").bufferedReader().use { it.readText() }
            }.getOrNull()
            if (text == null) {
                // A missing bundled asset is a build fault, and it is reported as
                // one rather than skipped, because a short library looks like a
                // database that simply does not know the device.
                problems.add("The bundled code library '$assetDir/$name' is missing from this build.")
                continue
            }
            val parsed = parseAny(text, "$assetDir/$name")
            families.addAll(parsed.families)
            problems.addAll(parsed.problems)
        }

        for ((name, text) in importedTexts) {
            val parsed = parseAny(text, name)
            families.addAll(parsed.families)
            problems.addAll(parsed.problems)
        }

        Log.d(TAG, "IR library loaded: ${families.size} family(ies), ${problems.size} problem(s)")
        return IrParseResult(families, problems)
    }

    /**
     * Parse text in whichever of the three formats it is written.
     *
     * Detection by first meaningful content:
     *  - "Filetype:" or a "name:" key line is Flipper.
     *  - "begin remote" is LIRC.
     *  - four-hex-digit words are Pronto.
     *
     * Anything else is reported as unrecognised rather than being fed to a parser
     * that would produce nonsense.
     */
    fun parseAny(text: String, source: String, familyName: String = source): IrParseResult {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            return IrParseResult(emptyList(), listOf("$source is empty."))
        }

        val firstMeaningful = trimmed.lines()
            .asSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
            ?: return IrParseResult(emptyList(), listOf("$source has no content beyond comments."))

        return when {
            firstMeaningful.startsWith("Filetype:", ignoreCase = true) ||
                firstMeaningful.startsWith("name:", ignoreCase = true) ||
                firstMeaningful.startsWith("type:", ignoreCase = true) ->
                IrFlipperParser.parse(text, source)

            firstMeaningful.startsWith("begin", ignoreCase = true) ->
                IrLircParser.parse(text, source)

            looksLikePronto(trimmed) ->
                IrProntoParser.parse(text, source, familyName)

            else -> IrParseResult(
                emptyList(),
                listOf(
                    "$source is not in a format this build reads. Its first line is " +
                        "'${firstMeaningful.take(60)}', which is neither a Flipper .ir file, a LIRC " +
                        "lircd.conf, nor a Pronto hex code.",
                ),
            )
        }
    }

    /** Whether the text is hex words and nothing else, which is what Pronto is. */
    private fun looksLikePronto(text: String): Boolean {
        val words = text.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.size < 4) return false
        return words.all { word ->
            word.length in 1..4 && word.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }
        }
    }
}
