// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.domain.slashcommands.commands

import com.unuslumen.app.domain.slashcommands.SlashCommandAction
import com.unuslumen.app.domain.slashcommands.SlashCommandResult
import com.unuslumen.app.domain.slashcommands.SlashCommandSession
import com.unuslumen.app.domain.slashcommands.SlashInvocation
import com.unuslumen.app.preferences.PrefsConstants
import com.unuslumen.app.preferences.domain.model.EgressMode
import com.unuslumen.app.preferences.domain.model.stringPreferencesKey
import com.unuslumen.app.preferences.domain.use_case.GetPreferenceUseCase
import com.unuslumen.app.preferences.domain.use_case.SavePreferenceUseCase
import kotlinx.coroutines.flow.first

/**
 * /clearnet on|off|status — the owner's override for public requests.
 *
 * WHAT IT DOES AND WHAT IT CANNOT TOUCH. On means a PUBLIC address may travel on the device's
 * own connection instead of Tor. It never touches a request aimed at the local network,
 * because those go direct already and Tor cannot reach them at all. It never touches a
 * request carrying a credential: a GitHub token or an API key only ever rides Tor, so this
 * switch cannot expose him to a service that would then know who he is.
 *
 * `off` means back to AUTO, not "Tor only". AUTO classifies each host, rides Tor for public
 * and fails closed with no circuit. That is a different posture from the lock /tor on sets,
 * and keeping them apart matters because turning an override off and locking a device down
 * are different intentions, and only one of them should refuse a later /clearnet.
 */
class ClearnetCommand(
    private val getPreference: GetPreferenceUseCase,
    private val savePreference: SavePreferenceUseCase,
) : SlashCommandAction {

    override val key: String = "clearnet"

    override suspend fun execute(
        invocation: SlashInvocation,
        session: SlashCommandSession,
    ): SlashCommandResult {
        val key = stringPreferencesKey(PrefsConstants.EGRESS_MODE_KEY)
        val current = EgressMode.parse(getPreference(key, EgressMode.DEFAULT.id).first())

        val requested = (invocation.values["mode"] ?: invocation.rawArgs)
            ?.trim()
            ?.lowercase()
            ?.takeIf { it.isNotEmpty() }
            ?: "status"

        return when (requested) {
            "on" -> {
                if (current == EgressMode.TOR_ONLY) {
                    // The lock is not something this command may undo, or /tor on would mean
                    // nothing. Naming the way out is the honest answer rather than a silent
                    // refusal.
                    SlashCommandResult.Handled(
                        "Clearnet cannot be turned on while the posture is locked with /tor on. " +
                            "Release the lock with /tor off first, which returns the posture to " +
                            "Automatic, then run /clearnet on."
                    )
                } else {
                    savePreference(key, EgressMode.CLEARNET.id)
                    SlashCommandResult.Handled(
                        "Clearnet is ON. A public request now goes direct on this device's own " +
                            "connection instead of through Tor. Requests to your own network are " +
                            "unaffected, and anything carrying a credential still only rides Tor.\n\n" +
                            "Every result that went this way says so, so nothing unproxied can be " +
                            "reported as private. Turn it off with /clearnet off."
                    )
                }
            }

            "off" -> {
                savePreference(key, EgressMode.AUTO.id)
                SlashCommandResult.Handled(
                    "Clearnet is OFF. The posture is back to Automatic: a public request rides Tor, " +
                        "and it fails with a clear reason rather than going direct when Tor is not " +
                        "running. Requests to your own network go direct and are unaffected."
                )
            }

            else -> SlashCommandResult.Handled(report(current))
        }
    }

    /** The current posture, in the terms this command controls. */
    private fun report(current: EgressMode): String = when (current) {
        EgressMode.CLEARNET ->
            "Clearnet is ON. Public requests go direct on the device's own connection.\n\n" +
                "Turn it off with /clearnet off."
        EgressMode.TOR_ONLY ->
            "Clearnet is locked OFF. A public request only ever rides Tor, and the override is " +
                "refused while /tor on is active.\n\nRelease the lock with /tor off."
        EgressMode.AUTO ->
            "Clearnet is OFF, which is the default. A public request rides Tor and fails rather " +
                "than going direct when Tor is not running.\n\n" +
                "Turn it on with /clearnet on."
    }
}
