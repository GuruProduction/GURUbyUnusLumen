// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.fabric.tv

import android.content.Context
import com.unuslumen.app.data.tools.registry.ToolDefinition
import com.unuslumen.app.data.tools.registry.ToolExecutionResult
import com.unuslumen.app.data.tools.registry.ToolExecutor
import com.unuslumen.app.data.tools.registry.ToolParameter
import com.unuslumen.app.data.tools.registry.ToolParameterType
import com.unuslumen.app.data.tools.registry.ToolResultData
import com.unuslumen.app.data.tools.registry.ToolResultExtractor
import com.unuslumen.app.data.tools.registry.ToolSetRegistration
import com.unuslumen.app.data.tools.registry.permission.DomainToolModule
import com.unuslumen.app.data.tools.senses.emitPayload
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.koin.core.module.Module
import org.koin.dsl.module
import kotlin.reflect.KClass

/**
 * The two tools network television control needs. Issue #187.
 *
 * WHY ONLY TWO. Listing and pressing are the whole job, and the fabric's generic tools
 * already do both against any transport. What has no generic equivalent is asking the
 * network what televisions are on it, and pressing a named button without inventing a
 * capability for every key on a remote.
 *
 * Every result names the make and how it was identified, because a person checking
 * whether GURU found their telly needs to recognise it in the list.
 */

@Serializable
data class TvDiscoveryResult(
    val success: Boolean,
    val televisions: List<TvFound> = emptyList(),
    val count: Int = 0,
    val problems: List<String> = emptyList(),
    val error: String? = null,
) : ToolResultData

@Serializable
data class TvFound(
    val id: String,
    val name: String,
    val vendor: String,
    val host: String,
    /** Whether this build can actually drive it, and why not when it cannot. */
    val controllable: Boolean,
    val needsPairing: Boolean,
    val evidence: String,
    val note: String? = null,
)

@Serializable
data class TvPressResult(
    val success: Boolean,
    val television: String = "",
    val button: String = "",
    val sent: Boolean = false,
    /** What the set reported back, when its protocol allows a reading. */
    val observed: String? = null,
    val needsPairing: Boolean = false,
    val error: String? = null,
) : ToolResultData

object TvToolDefinitions : ToolSetRegistration {

    const val TV_DISCOVER = "tvDiscover"
    const val TV_PRESS = "tvPress"

    override val definitions = listOf(
        ToolDefinition(
            name = TV_DISCOVER,
            description = "Find the televisions on this wifi network and work out each one's make, " +
                "so GURU can drive it the way a universal remote app does. Returns the name, make, " +
                "address and whether this build can actually control it, plus what each set said " +
                "to identify itself. A set answers only while it is awake on the same network, and " +
                "several makes need their network remote feature switched on first.",
            category = "smart_home",
            parameters = emptyList(),
            permissions = emptyList(),
        ),
        ToolDefinition(
            name = TV_PRESS,
            description = "Press one named button on a television found by tvDiscover: power, " +
                "volume up or down, mute, channel up or down, the direction keys, ok, back, home, " +
                "menu, play, pause, stop, input. Reports whether the set accepted the press, and " +
                "says plainly when a make needs a pairing prompt accepted on its screen first. " +
                "A set that cannot report its own state is never claimed to have obeyed.",
            category = "smart_home",
            parameters = listOf(
                ToolParameter(
                    "television", ToolParameterType.String, true,
                    "Television name, make or id, as tvDiscover reported it.",
                ),
                ToolParameter(
                    "button", ToolParameterType.String, true,
                    "Button to press, e.g. power, volume up, mute, ok, back, home.",
                ),
            ),
            permissions = emptyList(),
        ),
    )

    override fun executorClass(): KClass<out ToolExecutor> = TvToolExecutor::class
    override fun extractorClass(): KClass<out ToolResultExtractor>? = null
}

class TvToolExecutor(
    private val discovery: TvDiscovery,
    private val store: TvDeviceStore,
) : ToolExecutor {

    /** A single-Context constructor, because every domain executor is built that way. */
    constructor(context: Context) : this(TvDiscovery(), TvDeviceStore())

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    override suspend fun execute(toolName: String, args: Map<String, Any?>): ToolExecutionResult =
        when (toolName) {
            TvToolDefinitions.TV_DISCOVER -> discover()
            TvToolDefinitions.TV_PRESS -> press(args)
            else -> ToolExecutionResult.error("Unknown tool: $toolName")
        }

    /**
     * Scan the network and report every set found.
     *
     * A set this build cannot drive is listed as found and not controllable, with the
     * reason, because a person needs to know their telly was found and why nothing
     * happens, rather than seeing an empty list and going looking at the television.
     */
    private suspend fun discover(): ToolExecutionResult {
        val report = discovery.discover()
        val found = mutableListOf<TvFound>()
        for (tv in report.devices) {
            val result = TvRemoteFactory.remoteFor(tv)
            store.remember(tv, result.remote, result.reason)
            found.add(
                TvFound(
                    id = tv.id,
                    name = tv.name,
                    vendor = tv.vendor.name,
                    host = tv.host,
                    controllable = result.remote != null,
                    needsPairing = tv.needsPairing,
                    evidence = tv.evidence,
                    note = result.reason,
                ),
            )
        }

        val r = TvDiscoveryResult(
            success = found.any { it.controllable },
            televisions = found,
            count = found.size,
            problems = report.problems,
            error = when {
                found.isEmpty() -> report.problems.firstOrNull()
                    ?: "No television answered on this network."
                found.none { it.controllable } ->
                    "Televisions were found but none of them can be driven by this build yet: " +
                        found.mapNotNull { it.note }.joinToString(" ")
                else -> null
            },
        )
        return emitPayload(json, json.encodeToString(TvDiscoveryResult.serializer(), r), r, r.success, r.error)
    }

    /**
     * Press a button on a named set.
     *
     * The name is resolved against what discovery stored, so a person can say "the living
     * room telly" and a partial match works, with an unambiguous-only rule so GURU never
     * presses a button on the wrong set in a house with two of them.
     */
    private suspend fun press(args: Map<String, Any?>): ToolExecutionResult {
        val query = (args["television"] as? String)?.trim()?.ifBlank { null }
            ?: return ToolExecutionResult.error("Missing 'television'")
        val buttonRaw = (args["button"] as? String)?.trim()?.ifBlank { null }
            ?: return ToolExecutionResult.error("Missing 'button'")

        val button = TvButton.fromRaw(buttonRaw)
            ?: return ToolExecutionResult.error(
                "Unknown button '$buttonRaw'. Valid: " +
                    TvButton.entries.joinToString(", ") { it.name.lowercase().replace('_', ' ') } + ".",
            )

        val matches = store.all().filter { entry ->
            entry.tv.name.contains(query, ignoreCase = true) ||
                entry.tv.vendor.name.contains(query, ignoreCase = true) ||
                entry.tv.id.contains(query, ignoreCase = true) ||
                entry.tv.host == query
        }
        if (matches.isEmpty()) {
            val r = TvPressResult(
                success = false,
                television = query,
                button = button.name,
                error = "No television matches '$query'. Run tvDiscover first to find the sets on " +
                    "this network.",
            )
            return emitPayload(json, json.encodeToString(TvPressResult.serializer(), r), r, false, r.error)
        }
        if (matches.size > 1) {
            val r = TvPressResult(
                success = false,
                television = query,
                button = button.name,
                error = "'$query' matches ${matches.size} televisions: " +
                    matches.joinToString(", ") { it.tv.name } + ". Name one exactly.",
            )
            return emitPayload(json, json.encodeToString(TvPressResult.serializer(), r), r, false, r.error)
        }

        val entry = matches.first()
        val remote = entry.remote
        if (remote == null) {
            val r = TvPressResult(
                success = false,
                television = entry.tv.name,
                button = button.name,
                error = entry.unsupportedReason
                    ?: "No adapter is installed for this television, so nothing was sent.",
            )
            return emitPayload(json, json.encodeToString(TvPressResult.serializer(), r), r, false, r.error)
        }

        return when (val outcome = remote.press(button)) {
            is TvCommandOutcome.Failed -> {
                val r = TvPressResult(
                    success = false,
                    television = entry.tv.name,
                    button = button.name,
                    needsPairing = outcome.needsPairing,
                    error = outcome.reason,
                )
                emitPayload(json, json.encodeToString(TvPressResult.serializer(), r), r, false, r.error)
            }
            is TvCommandOutcome.Sent -> {
                // Read the set back where the protocol allows it, and report the reading
                // rather than assuming the press landed.
                val state = runCatching { remote.readState() }.getOrNull()
                val observed = state?.get(TvStateKey.POWER)
                val r = TvPressResult(
                    success = true,
                    television = entry.tv.name,
                    button = button.name,
                    sent = true,
                    observed = observed,
                    error = null,
                )
                emitPayload(json, json.encodeToString(TvPressResult.serializer(), r), r, true, null)
            }
        }
    }
}

/** The television domain's wiring. */
object TvDomainModule : DomainToolModule {

    override val id: String = "tv"

    override val registrations: List<ToolSetRegistration> = listOf(TvToolDefinitions)

    override fun bindings(): Module = module {
        // One store for the whole app, so a set discovered by the tool is the same set a
        // press reaches and the token a pairing produced is not thrown away.
        single { TvDeviceStore() }
        single { TvDiscovery() }
        single { TvTransport(get(), get()) }
        factory { TvToolExecutor(get(), get()) }
    }
}
