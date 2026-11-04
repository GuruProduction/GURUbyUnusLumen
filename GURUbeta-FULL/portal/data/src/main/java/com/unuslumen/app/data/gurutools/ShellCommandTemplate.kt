// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.gurutools

/**
 * The contract for how a GURU-defined shell tool's command reads its parameters.
 *
 * THE DEFECT THIS EXISTS FOR (issue 303). The `defineTool` contract advertised
 * `shell` as an implementation type and `parameters` as a schema, and said
 * nothing at all about how a command was meant to consume an argument. A model
 * writing a shell tool naturally wrote `$1` or `${name}`, and BOTH silently
 * produced empty strings:
 *
 *   {"type":"shell","command":"echo A=[$1]"}            -> A=[]
 *   {"type":"shell","command":"echo A=[${requiredOne}]"} -> A=[]
 *   {"type":"shell","command":"echo A=[$requiredOne]"}   -> A=[first]
 *
 * Only the bare form worked, and only because the framework happened to export
 * each argument as an environment variable, which nothing documented. There was
 * no error to catch: the tool ran, exited 0, and returned a result that looked
 * like the caller had passed a blank.
 *
 * WHAT THIS SETTLES, and it accepts every form a person would reasonably write:
 *
 *  1. `$name` and `${name}` both substitute when `name` is a declared parameter.
 *  2. `$1` … `$N` and `${1}` … `${N}` substitute positionally, in the order the
 *     tool declared its parameters. That is what a shell author means by `$1`,
 *     and it is the form the issue had to work around by hardcoding a script.
 *  3. `${params.name}` keeps working, because existing tools were written
 *     against it and a contract change must not break a live tool.
 *  4. Real shell environment references are left ALONE. `$HOME`, `$PATH`, `$?`,
 *     `$$`, `$0` and a parameter name that was never declared all pass through
 *     untouched, so a command may still use the shell deliberately.
 *  5. `\$name` is an escape and is emitted as a literal `$name`.
 *
 * Values are single-quoted with embedded quotes doubled, so a parameter value
 * can never break out into shell syntax. A value of `'; rm -rf /'` arrives as
 * one literal argument, which is the only acceptable behaviour for a framework
 * that lets a model write its own tools.
 *
 * ONE LEFT-TO-RIGHT PASS, deliberately. Sequential string replaces would let a
 * parameter VALUE containing `$other` be substituted again by the next rule,
 * which turns an argument into executable command text. A single scan cannot.
 *
 * Pure, with no Android imports, so the whole contract is JVM-testable in
 * seconds — which is why [ShellCommandTemplateTest] can prove all five rules
 * plus the quoting without a device.
 */
object ShellCommandTemplate {

    /**
     * Substitute [params] into [command] under the contract above.
     *
     * @param declaredOrder the tool's parameter names in declaration order,
     *        which is what `$1`, `$2` … address. A name absent from the map is
     *        still addressable positionally and resolves to an empty value,
     *        because a shell's `$3` on a two-argument call is empty rather than
     *        an error and a tool author relies on that for optional arguments.
     */
    fun render(
        command: String,
        params: Map<String, Any?>,
        declaredOrder: List<String> = emptyList(),
    ): Rendered {
        val out = StringBuilder(command.length + 32)

        /** Parameters whose value actually substituted, in first-use order. */
        val used = LinkedHashSet<String>()
        /** Positional references that pointed past the declared list. */
        val outOfRange = LinkedHashSet<Int>()

        var i = 0
        while (i < command.length) {
            val c = command[i]

            // An escaped dollar is a literal dollar: consume both characters.
            if (c == '\\' && i + 1 < command.length && command[i + 1] == '$') {
                out.append('$')
                i += 2
                continue
            }

            if (c != '$') {
                out.append(c)
                i++
                continue
            }

            // ---- `$` at the end of the string is a literal ----
            if (i + 1 >= command.length) {
                out.append('$')
                i++
                continue
            }

            val next = command[i + 1]

            // ---- ${ … } ----
            if (next == '{') {
                val close = command.indexOf('}', i + 2)
                if (close < 0) {
                    // Unbalanced: emit as written rather than guessing.
                    out.append('$')
                    i++
                    continue
                }
                val inner = command.substring(i + 2, close)
                when (val resolved = resolveReference(inner, params, declaredOrder, used, outOfRange)) {
                    null -> out.append(command, i, close + 1)   // leave verbatim
                    else -> out.append(resolved)
                }
                i = close + 1
                continue
            }

            // ---- $$ and $? and $! are the shell's own ----
            if (!next.isLetter() && next != '_' && !next.isDigit()) {
                out.append('$')
                i++
                continue
            }

            // ---- $1 … $9, and $10 upward while the run is all digits ----
            if (next.isDigit()) {
                var end = i + 1
                while (end < command.length && command[end].isDigit()) end++
                val number = command.substring(i + 1, end).toIntOrNull()
                if (number == null) {
                    out.append(command, i, end)
                } else {
                    out.append(positional(number, params, declaredOrder, used, outOfRange))
                }
                i = end
                continue
            }

            // ---- $name ----
            var end = i + 1
            while (end < command.length &&
                (command[end].isLetterOrDigit() || command[end] == '_')
            ) {
                end++
            }
            val name = command.substring(i + 1, end)
            if (params.containsKey(name)) {
                used += name
                out.append(shellQuote(params[name]))
            } else {
                out.append(command, i, end)      // $HOME, $PATH, an undeclared name
            }
            i = end
        }

        return Rendered(
            command = out.toString(),
            substituted = used.toList(),
            positionalOutOfRange = outOfRange.toList(),
        )
    }

    /**
     * What one `${…}` reference means, or null when it should be left verbatim.
     *
     * Order matters and is intentional: the legacy `params.` prefix is checked
     * first so an existing `${params.location}` tool keeps its meaning even if
     * somebody one day declares a parameter literally called `params`.
     */
    private fun resolveReference(
        inner: String,
        params: Map<String, Any?>,
        declaredOrder: List<String>,
        used: MutableSet<String>,
        outOfRange: MutableSet<Int>,
    ): String? {
        val trimmed = inner.trim()
        if (trimmed.isEmpty()) return null

        if (trimmed.startsWith("params.")) {
            val key = trimmed.removePrefix("params.")
            if (!params.containsKey(key)) return null
            used += key
            return shellQuote(params[key])
        }

        // ${1} — positional in braced form, which is how a two-digit index is
        // written unambiguously in a shell.
        val asNumber = trimmed.toIntOrNull()
        if (asNumber != null) {
            return positional(asNumber, params, declaredOrder, used, outOfRange)
        }

        if (!isIdentifier(trimmed)) return null
        if (!params.containsKey(trimmed)) return null
        used += trimmed
        return shellQuote(params[trimmed])
    }

    /** `$0` is the shell's own name, so it is never a tool parameter. */
    private fun positional(
        index: Int,
        params: Map<String, Any?>,
        declaredOrder: List<String>,
        used: MutableSet<String>,
        outOfRange: MutableSet<Int>,
    ): String {
        if (index <= 0) return "\\\$$index"
        val name = declaredOrder.getOrNull(index - 1)
        if (name == null) {
            outOfRange += index
            return "''"
        }
        used += name
        return shellQuote(params[name])
    }

    /** A value as one literal shell word. Null becomes an empty word. */
    fun shellQuote(value: Any?): String {
        val text = value?.toString() ?: ""
        return "'" + text.replace("'", "'\\''") + "'"
    }

    private fun isIdentifier(text: String): Boolean {
        if (text.isEmpty()) return false
        if (!text[0].isLetter() && text[0] != '_') return false
        return text.all { it.isLetterOrDigit() || it == '_' }
    }

    /**
     * The rendered command plus the facts a caller needs to be honest about it.
     */
    data class Rendered(
        val command: String,
        /** Parameters that actually reached the command. */
        val substituted: List<String>,
        /** Positional references with no declared parameter at that index. */
        val positionalOutOfRange: List<Int>,
    )

    /**
     * Declared parameters the command never references, in declaration order.
     *
     * THE SECOND HALF OF ISSUE 303. Even with every form accepted, a declared
     * parameter that the command never mentions is silently dropped: the tool
     * runs, exits 0, and returns a result that ignores an argument the caller
     * supplied. Nothing about that is visible at call time, so it has to be
     * said at AUTHORING time, which is what `defineTool` and `updateTool` do
     * with this list.
     */
    fun unreferencedParameters(
        command: String,
        declaredOrder: List<String>,
    ): List<String> {
        val referenced = LinkedHashSet<String>()
        render(command, declaredOrder.associateWith { null }, declaredOrder)
            .let { rendered -> rendered.substituted.forEach { referenced += it } }
        return declaredOrder.filterNot { it in referenced }
    }
}
