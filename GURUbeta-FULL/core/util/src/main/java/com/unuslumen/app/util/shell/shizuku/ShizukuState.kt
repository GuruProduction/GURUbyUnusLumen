// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.util.shell.shizuku

/**
 * What state the Shizuku channel is in, as three distinct facts.
 *
 * WHY THREE STATES AND NOT A BOOLEAN. "Shizuku is not available" is three
 * different problems with three different instructions: install it, start it, or
 * authorise GURU. Collapsing them into one false tells a person to do the wrong
 * one of the three, and the wrong one fails silently because the app looks the
 * same either way.
 *
 * [NOT_INSTALLED]  The Shizuku app is not on this device. The person must install
 *                  it from the store, then start it through wireless debugging.
 *
 * [NOT_RUNNING]    Shizuku is installed but its process is not up. Shizuku is
 *                  started by the person, either after each reboot by hand or
 *                  automatically on rooted devices. The instruction is to open it,
 *                  not to install it again.
 *
 * [NOT_AUTHORISED] Shizuku is running but has not granted GURU access. This is the
 *                  state that a single tap fixes, and the only one where the app
 *                  can ask directly.
 *
 * [AVAILABLE]      The channel is usable. Anything that runs through it works.
 */
enum class ShizukuState {
    NOT_INSTALLED,
    NOT_RUNNING,
    NOT_AUTHORISED,
    AVAILABLE;

    /**
     * Whether anything can be done through Shizuku right now.
     *
     * Deliberately narrow: this is the only test a caller should branch on before
     * choosing the shell tier, because every other state means the tier is not
     * usable and the honest answer is the sentence below.
     */
    val usable: Boolean get() = this == AVAILABLE

    /**
     * One sentence naming the state and the exact action that changes it.
     *
     * The same shape as the permission refusals, and for the same reason: a person
     * reading a tool result needs to know what to do next, not that something is
     * unavailable. An empty result here is the defect the whole batch removes.
     */
    fun sentence(): String = when (this) {
        NOT_INSTALLED ->
            "Shizuku is not installed, so GURU is limited to app-UID shell access. " +
                "Install Shizuku from the Play Store to unlock shell-level commands."
        NOT_RUNNING ->
            "Shizuku is installed but not running, so GURU is limited to app-UID shell access. " +
                "Open Shizuku and start it through wireless debugging to unlock shell-level commands."
        NOT_AUTHORISED ->
            "Shizuku is running but has not authorised GURU, so GURU is limited to app-UID " +
                "shell access. Open Shizuku and allow GURU to use it."
        AVAILABLE ->
            "Shizuku is running and GURU is authorised, so shell-level commands run at shell UID."
    }

    companion object {
        /**
         * The state from three independent readings, in the order the problems
         * have to be solved.
         *
         * WHY THIS IS A PURE FUNCTION. Every branch is a fact about the device and
         * the answer is the same every time, so it belongs on the JVM where a
         * failing case names the rule that broke. The two Android calls that feed
         * it are one line each in ShizukuBridge, and proving *those* needs a
         * device; proving the decision does not.
         *
         * The order is not arbitrary. Installed-before-running matters because
         * Shizuku's binder is absent in both cases and only the package check
         * distinguishes them. Running-before-authorised matters because an
         * unauthorised call would otherwise be reported as "not running", which
         * sends the person to restart something that is already up.
         */
        fun of(installed: Boolean, running: Boolean, authorised: Boolean): ShizukuState = when {
            !installed -> NOT_INSTALLED
            !running -> NOT_RUNNING
            !authorised -> NOT_AUTHORISED
            else -> AVAILABLE
        }
    }
}
