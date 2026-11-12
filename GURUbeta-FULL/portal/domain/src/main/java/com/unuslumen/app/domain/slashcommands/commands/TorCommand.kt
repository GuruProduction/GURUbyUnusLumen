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
 * /tor on|off|status — the egress posture, in the owner's hands.
 *
 * WHAT `on` MEANS, AND WHY IT IS NOT A DAEMON SWITCH. Tor is a persistent foreground service
 * that runs until the app is uninstalled, so there is nothing for a command to start. A
 * switch claiming otherwise would be lying about what it did. What `on` sets is the posture:
 * public traffic rides Tor and the clearnet override is REFUSED. That lock is the whole
 * difference between this and plain AUTO, which is why it is a separate posture.
 *
 * `off` returns the posture to AUTO. It does not enable clearnet, because releasing a lock
 * and opening an override are two different acts, and a command that did both would leave
 * him more exposed than he asked to be.
 *
 * The one thing this will not claim is whether Tor is running. That is the service's own
 * state; the posture is the part he controls, and a request that fails for want of a circuit
 * says exactly that.
 */
class TorCommand(
    private val getPreference: GetPreferenceUseCase,
    private val savePreference: SavePreferenceUseCase,
) : SlashCommandAction {

    override val key: String = "tor"

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
                savePreference(key, EgressMode.TOR_ONLY.id)
                SlashCommandResult.Handled(
                    "Tor is locked ON. Every public request rides Tor, and one with no circuit " +
                        "fails with a clear reason rather than falling back to a direct connection.\n\n" +
                        "The clearnet override is refused while this is active, so a later " +
                        "/clearnet on cannot widen egress. Release the lock with /tor off."
                )
            }

            "off" -> {
                savePreference(key, EgressMode.AUTO.id)
                SlashCommandResult.Handled(
                    "The Tor lock is released. The posture is back to Automatic: a public request " +
                        "rides Tor, and it fails rather than going direct when Tor is not running.\n\n" +
                        "This did not switch clearnet on. If you need a public request to go direct, " +
                        "run /clearnet on."
                )
            }

            else -> SlashCommandResult.Handled(report(current))
        }
    }

    /** The current posture, described by what it does to a public request. */
    private fun report(current: EgressMode): String = buildString {
        append("Egress posture: ${current.label}.\n\n")
        append(current.publicBehaviour)
        append("\n\n")
        append(
            when (current) {
                EgressMode.CLEARNET ->
                    "/tor on locks public traffic onto Tor and refuses the override. " +
                        "/clearnet off returns to Automatic."
                EgressMode.TOR_ONLY ->
                    "The override is locked out. /tor off releases it back to Automatic."
                EgressMode.AUTO ->
                    "/tor on locks public traffic onto Tor and refuses the override. " +
                        "/clearnet on allows a public request to go direct."
            }
        )
        append(
            "\n\nRequests to this device or to the local network always go direct, because Tor " +
                "cannot reach a private address."
        )
    }
}
