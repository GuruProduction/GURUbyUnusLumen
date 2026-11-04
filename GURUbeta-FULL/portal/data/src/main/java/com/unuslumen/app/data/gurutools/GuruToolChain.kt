// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.gurutools

/**
 * The chain of tool names currently being executed, so a composition can call
 * another GURU-defined tool without being able to call itself forever.
 *
 * THE DEFECT THIS EXISTS FOR (issue 298). `executeStep` resolved a step's
 * target only from the built-in `ToolRegistry`, and a GURU-defined tool is
 * never registered there — it reaches the model through
 * `GuruToolRegistryManager` without touching the registry. So a composition
 * could chain a built-in and could never chain another GURU tool:
 *
 *   defineTool A (steps: [bush_tv])  -> ok
 *   defineTool B (steps: [A])        -> ok
 *   call B -> IllegalArgumentException("Tool not found: A")
 *
 * Falling through to the GURU repository closes that, and the fall-through is
 * RECURSIVE: B calls A, A could call B, and a model that defines two tools in
 * terms of each other would produce a StackOverflowError instead of an answer.
 *
 * A stack overflow is the worst possible report here. It arrives as a crash
 * with no tool name in it, from a framework whose whole promise is that a tool
 * that cannot run says why. So the repeat is caught BEFORE the call and named:
 *
 *   "A -> B -> A: this composition calls itself"
 *
 * Deliberately pure and Android-free, so [GuruToolChainTest] proves the guard
 * on the JVM in milliseconds rather than needing a device to hang.
 */
object GuruToolChain {

    /** The maximum depth, so a pathological but acyclic set still terminates. */
    const val MAX_DEPTH: Int = 32

    /** What a call is about to do, or why it must not. */
    sealed class Admission {
        /** The call may proceed, carrying [next] as the new chain. */
        data class Allowed(val next: List<String>) : Admission()

        /** The call must be refused, with a sentence naming the cycle. */
        data class Refused(val reason: String) : Admission()
    }

    /**
     * Whether [toolName] may be entered with [chain] already in progress.
     *
     * @param chain the names entered so far, outermost first, NOT including
     *        [toolName]. The executor passes the chain down as it recurses, so
     *        this is always the real call path rather than a guess.
     */
    fun admit(chain: List<String>, toolName: String): Admission {
        if (toolName in chain) {
            val cycle = chain.dropWhile { it != toolName } + toolName
            return Admission.Refused(
                "This composition calls itself: ${cycle.joinToString(" -> ")}. " +
                    "A tool cannot be defined in terms of itself, directly or through a " +
                    "chain of other tools, because the call would never finish. " +
                    "Redefine one of these with updateTool so the chain is acyclic.",
            )
        }
        if (chain.size >= MAX_DEPTH) {
            return Admission.Refused(
                "This composition is ${chain.size} tools deep, which is past the limit of " +
                    "$MAX_DEPTH. The chain was ${chain.joinToString(" -> ")} -> $toolName. " +
                    "Flatten it rather than chaining this far.",
            )
        }
        return Admission.Allowed(chain + toolName)
    }
}
