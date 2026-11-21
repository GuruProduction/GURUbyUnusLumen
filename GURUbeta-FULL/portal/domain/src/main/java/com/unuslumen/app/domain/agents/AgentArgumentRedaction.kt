// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.domain.agents

/**
 * Decides which parts of a tool call must be withheld from the run's ordinary record.
 *
 * THE PROBLEM THIS SOLVES. Every tool call is recorded with its repaired arguments, which is right:
 * the record is evidence of what the agent actually did, and a record that omits the arguments
 * describes a call that cannot be checked. But some arguments are credentials, and a password in the
 * ledger is a password in every backup, every export and every room anybody opens of that agent.
 * Section 13.7 asks for redaction of secrets from ordinary activity views, and the ledger is the most
 * ordinary view there is.
 *
 * REDACTION IS NOT DELETION. A withheld value is recorded as withheld, with its reason, so a reader
 * can tell "this was a password and it was deliberately not kept" from "the argument was empty". The
 * `ProtectedRead.Redacted` state already exists for exactly this distinction; this is the rule that
 * decides when to use it.
 *
 * IT IS A PURE FUNCTION OF THE TOOL NAME AND THE ARGUMENT NAME. No secrets are inspected, no values
 * are compared against patterns, and nothing here reads the content of an argument. A regex over
 * values would either miss the credential in the unlabelled field or withhold half the ledger, and
 * both failures look the same from outside: a reader who cannot tell what happened.
 *
 * THE COST IS UNDER-REDACTION, AND IT IS THE RIGHT ONE. A tool nobody listed keeps its arguments in
 * full. That is a leak of a secret the rule did not know about, which is a gap to close by extending
 * the list; the alternative, withholding anything that looks unusual, hides the ordinary work the
 * record exists to prove.
 */
object AgentArgumentRedaction {

    /**
     * Argument names that are always withheld, whatever tool carries them.
     *
     * MATCHED CASE-INSENSITIVELY AND BY SUBSTRING, because a tool naming its field `apiKey`,
     * `api_key`, `x-api-key` or `apiKeyValue` means the same thing and a rule that only matches
     * exact spellings is a rule that misses the one that mattered.
     */
    private val SECRET_ARGUMENT_NAMES = listOf(
        "password",
        "passphrase",
        "secret",
        "token",
        "apikey",
        /*
         * THE HYPHENATED SPELLING IS A REAL NAME, not a variant to ignore. An HTTP tool naming its
         * field `x-api-key` is the most common shape there is, and lowercasing it produces
         * `x-api-key`, which contains neither `apikey` nor `api_key`. A test caught that this list
         * was missing it before the rule shipped.
         */
        "api-key",
        "api_key",
        "credential",
        "private-key",
        "private_key",
        "privatekey",
        "seed",
        "mnemonic",
    )

    /**
     * Tools whose whole argument set is withheld.
     *
     * A TOOL THAT EXISTS TO HANDLE A CREDENTIAL HAS ONE JOB, and its arguments are the credential
     * whether or not the field names say so. Withholding the whole call is coarser than field-level
     * redaction and it is the honest choice here, because the alternative is guessing which of an
     * unknown tool's arguments is the secret.
     */
    private val SECRET_BEARING_TOOLS = setOf(
        "walletSign",
        "walletTransfer",
        "sshExec",
        "sshConnect",
        "githubAuth",
        "saveApiKey",
        "setProviderKey",
    )

    /** One withheld field, named so a reader knows what was withheld without being told its value. */
    data class Withheld(val field: String, val reason: String)

    /** What to do with one tool call's arguments. */
    data class Verdict(
        /** The argument names to withhold. Empty means record everything. */
        val withheldFields: List<Withheld>,
    ) {
        val hasWithheld: Boolean get() = withheldFields.isNotEmpty()

        /** The reason to store beside the record, naming every withheld field. */
        val reason: String get() = when {
            withheldFields.isEmpty() -> ""
            withheldFields.size == 1 ->
                "${withheldFields.single().field} was withheld: ${withheldFields.single().reason}"
            else -> withheldFields.joinToString("; ") { "${it.field} was withheld: ${it.reason}" }
        }
    }

    /**
     * Decide for one call.
     *
     * THE TOOL CHECK COMES FIRST. A secret-bearing tool's arguments are all withheld whatever they
     * are called, so there is no point inspecting names for it; and a reader is better served by one
     * clear reason than by a list of eight fields each individually declared suspicious.
     */
    fun evaluate(toolName: String, argumentNames: Collection<String>): Verdict {
        if (toolName in SECRET_BEARING_TOOLS) {
            return Verdict(
                argumentNames.map {
                    Withheld(it, "this tool handles a credential, so its arguments are not kept")
                }
            )
        }

        val withheld = argumentNames.mapNotNull { name ->
            val normalised = name.lowercase()
            val matched = SECRET_ARGUMENT_NAMES.firstOrNull { normalised.contains(it) }
                ?: return@mapNotNull null
            Withheld(name, "a field named '$matched' is treated as a credential")
        }
        return Verdict(withheld)
    }

    /**
     * Replace a withheld field's value with a marker, keeping the argument's name.
     *
     * THE NAME STAYS BECAUSE IT IS NOT THE SECRET. A record saying a call passed `apiKey` and not
     * what it passed is evidence of the shape of the call, which is what a reader auditing a run
     * needs; stripping the name as well would leave a call that looks like it took no arguments at
     * all.
     */
    fun redactArguments(argumentsJson: String, verdict: Verdict): String {
        if (!verdict.hasWithheld) return argumentsJson
        var result = argumentsJson
        for (withheld in verdict.withheldFields) {
            /*
             * A DELIBERATELY SIMPLE REPLACEMENT. Parsing and re-serialising would reformat the whole
             * document and lose the model's own spacing, and a record that is quietly reformatted is
             * a record that no longer proves what was sent. The pattern matches the field's value up
             * to the next quote, which is exact for a JSON string and does nothing for anything else.
             */
            val pattern = Regex(""""${Regex.escape(withheld.field)}"\s*:\s*"(?:[^"\\]|\\.)*"""")
            result = result.replace(pattern) { match ->
                val name = match.value.substringBefore(':')
                "$name:\"[withheld]\""
            }
        }
        return result
    }
}
