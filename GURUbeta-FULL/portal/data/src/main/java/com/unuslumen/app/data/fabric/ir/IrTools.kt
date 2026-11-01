// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.fabric.ir

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
 * The three IR-specific tools the issue names. Issue #187.
 *
 * WHY ONLY THREE. The five generic fabric tools already list, control and
 * commission every device, including IR ones. A tool per device or per protocol is
 * the mistake the fabric exists to prevent. What has no generic equivalent is
 * learning a code, seeing which families the library knows, and importing a file,
 * so those are the three.
 *
 * EVERY TOOL REPORTS WHAT IT COULD NOT DO. Learn mode is unavailable on almost
 * every phone, because ConsumerIrManager transmits and does not receive. That is
 * stated as the answer, with the alternatives named, rather than offered as a
 * feature that then fails.
 */

@Serializable
data class IrDevicesResult(
    val success: Boolean,
    val families: List<String> = emptyList(),
    val count: Int = 0,
    /** What the transmitter is, and why it cannot send when it cannot. */
    val transmitter: String = "",
    val transmitterAvailable: Boolean = false,
    val problems: List<String> = emptyList(),
    val error: String? = null,
) : ToolResultData

@Serializable
data class IrLearnResult(
    val success: Boolean,
    val learnedButton: String? = null,
    /** What was actually possible, in a sentence, when learning could not happen. */
    val method: String = "",
    val error: String? = null,
) : ToolResultData

@Serializable
data class IrImportResult(
    val success: Boolean,
    val families: List<String> = emptyList(),
    val codes: Int = 0,
    val problems: List<String> = emptyList(),
    val error: String? = null,
) : ToolResultData

object IrToolDefinitions : ToolSetRegistration {

    const val IR_DEVICES = "irDevices"
    const val IR_LEARN = "irLearn"
    const val IR_IMPORT = "irImport"

    override val definitions = listOf(
        ToolDefinition(
            name = IR_DEVICES,
            description = "List the television, air-conditioner and other remote-controlled device " +
                "families the infrared code library knows, and report what transmitter this phone " +
                "has. Check this before trying to control a telly: it says whether an emitter " +
                "exists, whether a network blaster is configured, and which device names have " +
                "codes. Infrared is one-way, so a device listed here can be commanded but its " +
                "state can never be read back.",
            category = "smart_home",
            parameters = emptyList(),
            permissions = emptyList(),
        ),
        ToolDefinition(
            name = IR_LEARN,
            description = "Capture a code from a physical remote by pointing it at the phone. THIS " +
                "USUALLY CANNOT WORK: Android's ConsumerIrManager transmits and does not receive, " +
                "so most phones cannot capture at all. Where it cannot, this reports that plainly " +
                "and names the alternatives, which are a bundled code or an imported file. It " +
                "never claims a capture it did not make.",
            category = "smart_home",
            parameters = listOf(
                ToolParameter(
                    "button", ToolParameterType.String, true,
                    "What the button is called, e.g. power, vol_up, mute.",
                ),
                ToolParameter(
                    "device", ToolParameterType.String, false,
                    "Which device family to store a learned code under.",
                ),
                ToolParameter(
                    "timeoutSeconds", ToolParameterType.Integer, false,
                    "How long to wait for the press, 1 to 30 seconds. Default 10.",
                ),
            ),
            permissions = emptyList(),
        ),
        ToolDefinition(
            name = IR_IMPORT,
            description = "Import infrared codes from a file: a Flipper .ir file, a LIRC " +
                "lircd.conf, or a Pronto hex string. Returns the device families and the number of " +
                "codes read, plus every line it could not parse, so a short import is explained " +
                "rather than looking like a library that does not know the device.",
            category = "smart_home",
            parameters = listOf(
                ToolParameter(
                    "path", ToolParameterType.String, true,
                    "Absolute path to the file, or the Pronto hex string itself.",
                ),
                ToolParameter(
                    "name", ToolParameterType.String, false,
                    "What to call the device family when the file does not name one.",
                ),
            ),
            permissions = emptyList(),
        ),
    )

    override fun executorClass(): KClass<out ToolExecutor> = IrToolExecutor::class
    override fun extractorClass(): KClass<out ToolResultExtractor>? = null
}

/**
 * The executor behind the three IR tools.
 *
 * THE SINGLE-ARGUMENT CONSTRUCTOR IS LOAD-BEARING. Every domain executor in this
 * batch is checked by a JVM test that builds it from its own Koin bindings, and the
 * established shape there is a one-argument constructor. Rather than special-case
 * this domain in that test, the graph is supplied here: [IrWiring] holds the store,
 * the loader and the transmitters, and both the tool executor and the transport take
 * it. That keeps one wiring description for the whole IR domain.
 */
class IrToolExecutor(
    private val wiring: IrWiring,
) : ToolExecutor {

    constructor(context: Context) : this(IrWiring.forContext(context))

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    override suspend fun execute(toolName: String, args: Map<String, Any?>): ToolExecutionResult =
        when (toolName) {
            IrToolDefinitions.IR_DEVICES -> devices()
            IrToolDefinitions.IR_LEARN -> learn(args)
            IrToolDefinitions.IR_IMPORT -> importFile(args)
            else -> ToolExecutionResult.error("Unknown tool: $toolName")
        }

    /**
     * List the library and describe the transmitter.
     *
     * The transmitter sentence travels with the list because an empty library and a
     * missing emitter produce the same empty answer, and a person acts differently
     * on each.
     */
    private suspend fun devices(): ToolExecutionResult {
        val devices = wiring.store.devices()
        val choice = IrTransmitterChoice.choose(wiring.transmitters)
        val status = choice.transmitter?.status()

        val r = IrDevicesResult(
            success = devices.isNotEmpty(),
            families = devices.map { "${it.name}: ${it.capabilities.sortedBy { cap -> cap.name }.joinToString(", ") { cap -> cap.name }}" },
            count = devices.size,
            transmitter = when {
                status == null -> IrTransmitterChoice.absenceSentence()
                status.carrierFrequencies.isEmpty() -> "${status.displayName}, ready."
                else -> "${status.displayName}, ready, carriers " +
                    status.carrierFrequencies.joinToString(", ") { "${it.first}-${it.last} Hz" } + "."
            },
            transmitterAvailable = status != null,
            problems = listOfNotNull(choice.reason),
            error = if (devices.isEmpty()) {
                "The infrared library knows no device families yet, so nothing can be controlled " +
                    "over infrared. Import a code file with irImport to add one."
            } else {
                null
            },
        )
        return emitPayload(json, json.encodeToString(IrDevicesResult.serializer(), r), r, r.success, r.error)
    }

    /**
     * Attempt a learn, and refuse honestly.
     *
     * A built-in emitter cannot receive, so there is no capture path on a normal
     * phone. The alternatives are named because that is the whole useful answer.
     */
    private fun learn(args: Map<String, Any?>): ToolExecutionResult {
        val button = (args["button"] as? String)?.trim()?.ifBlank { null }
            ?: return ToolExecutionResult.error("Missing 'button'")

        val r = IrLearnResult(
            success = false,
            method = "Android's ConsumerIrManager transmits only and has no receive path, so this " +
                "phone's emitter is not a capture device.",
            error = "Learning '${IrButton.normalise(button)}' is not possible on this phone. Use " +
                "irDevices to find a bundled code for the device, or irImport a Flipper .ir, LIRC " +
                "or Pronto file. A network blaster with a receiver can capture where this cannot.",
        )
        return emitPayload(json, json.encodeToString(IrLearnResult.serializer(), r), r, r.success, r.error)
    }

    /**
     * Import codes from a file path or a raw Pronto string.
     *
     * The file read happens on the caller's coroutine, which the dispatcher runs off
     * the main thread, and every problem is returned so a partial import is visible.
     */
    private suspend fun importFile(args: Map<String, Any?>): ToolExecutionResult {
        val path = (args["path"] as? String)?.trim()?.ifBlank { null }
            ?: return ToolExecutionResult.error("Missing 'path'")
        val name = (args["name"] as? String)?.trim()?.ifBlank { null } ?: "Imported"

        // A value with a slash is a path; anything else is the Pronto hex itself,
        // because a six-word hex string contains no slash and a file path does.
        val text = if (path.contains("/")) {
            val file = java.io.File(path)
            if (!file.exists()) {
                val r = IrImportResult(
                    success = false,
                    error = "There is no file at '$path'.",
                )
                return emitPayload(json, json.encodeToString(IrImportResult.serializer(), r), r, r.success, r.error)
            }
            runCatching { file.readText() }.getOrElse { error ->
                val r = IrImportResult(
                    success = false,
                    error = "Could not read '$path': ${error.message ?: error.javaClass.simpleName}.",
                )
                return emitPayload(json, json.encodeToString(IrImportResult.serializer(), r), r, r.success, r.error)
            }
        } else {
            path
        }

        val parsed = wiring.loader.parseAny(text, path, name)
        if (parsed.families.isNotEmpty()) {
            wiring.store.replaceAll(parsed.families)
        }

        val r = IrImportResult(
            success = parsed.codeCount > 0,
            families = parsed.families.map { it.name },
            codes = parsed.codeCount,
            problems = parsed.problems,
            error = if (parsed.codeCount == 0) {
                parsed.problems.firstOrNull() ?: "The file contained no codes this build can read."
            } else {
                null
            },
        )
        return emitPayload(json, json.encodeToString(IrImportResult.serializer(), r), r, r.success, r.error)
    }
}

/**
 * The IR domain's wiring: one file, plus the one line in the aggregator.
 *
 * The transmitter list is built here so both backends exist whatever the hardware,
 * and [IrTransmitterChoice] decides which one answers.
 */
object IrDomainModule : DomainToolModule {

    override val id: String = "ir"

    override val registrations: List<ToolSetRegistration> = listOf(IrToolDefinitions)

    override fun bindings(): Module = module {
        // ONE wiring object for the whole domain. The tool executor, the transport and
        // the smart home module all take it, so there is exactly one store and one
        // transmitter list in the app. Two of either would mean a device imported
        // through a tool was invisible to the transport that has to press its buttons.
        single { IrWiring.forContext(get()) }
        factory { IrToolExecutor(get<IrWiring>()) }
        single { IrTransport(get<IrWiring>().transmitters, get<IrWiring>().store) }
    }
}

/**
 * The IR domain's whole dependency graph, in one object.
 *
 * WHY A PLAIN OBJECT AND NOT FOUR KOIN BINDINGS. Every domain executor is checked by
 * a JVM test that builds it from a single Context argument, and the transport needs
 * the same store the tools write to. Bundling them here makes that single-argument
 * shape possible, makes the sharing explicit, and makes the whole graph constructible
 * in a test with no Koin container at all.
 */
class IrWiring(
    val loader: IrLibraryLoader,
    val store: InMemoryIrCodeStore,
    val transmitters: List<IrTransmitter>,
) {
    companion object {
        /** Build the graph for one context, with the bundled library loaded lazily. */
        fun forContext(context: Context): IrWiring = IrWiring(
            loader = IrLibraryLoader(context),
            store = InMemoryIrCodeStore(),
            transmitters = IrTransmitters(context).all,
        )
    }
}

/**
 * Both transmitters, in the order [IrTransmitterChoice] should try them.
 *
 * A single value rather than a list binding, because Koin resolving two separate
 * list-shaped bindings would silently keep the last one and a phone with an
 * emitter would quietly start using the network instead.
 */
class IrTransmitters(context: Context) {
    val all: List<IrTransmitter> = buildList {
        add(BuiltInIrTransmitter(context))
        // A network blaster is only added when an address has been configured,
        // because an unconfigured one would report itself unavailable on every call
        // and push a misleading sentence into every devices listing.
        val configured = IrBlasterConfig.address(context)
        if (configured != null) add(BroadlinkIrTransmitter(configured))
    }
}

/** Where a configured blaster's address lives until the settings surface exists. */
object IrBlasterConfig {

    private const val PREFS = "guru_ir"
    private const val KEY_HOST = "blaster_host"

    /** The configured address, or null when nobody has set one. */
    fun address(context: Context): String? = runCatching {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_HOST, null)
            ?.trim()
            ?.ifBlank { null }
    }.getOrNull()

    /** Set the address, so a person can point GURU at their own blaster. */
    fun setAddress(context: Context, host: String?) {
        runCatching {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_HOST, host?.trim()?.ifBlank { null })
                .apply()
        }
    }
}
