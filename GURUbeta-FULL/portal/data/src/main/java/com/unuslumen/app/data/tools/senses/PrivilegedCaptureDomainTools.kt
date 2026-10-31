// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.tools.senses

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
import com.unuslumen.app.data.tools.registry.permission.PermissionGrants
import com.unuslumen.app.data.tools.registry.permission.PermissionSpec
import com.unuslumen.app.data.tools.registry.permission.TeamThreePermissions
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.koin.core.module.Module
import org.koin.dsl.module
import kotlin.reflect.KClass

/**
 * The three privileged capture capabilities: other apps' audio output, the raw video
 * output stream, and secure video surfaces.
 *
 * WHY THESE EXIST AS TOOLS AT ALL. The batch asked for them so that a person asking
 * "can you record what this app is playing" gets a true answer instead of nothing. An
 * absent tool reads to a model as a capability that might exist; a tool that always
 * refuses with the platform's reason reads as the truth it is.
 *
 * WHAT THEY CANNOT DO WITHOUT SHIZUKU. All three permissions are signature, privileged
 * or role tier: CAPTURE_AUDIO_OUTPUT is signature|privileged|role, CAPTURE_VIDEO_OUTPUT
 * is signature, CAPTURE_SECURE_VIDEO_OUTPUT is signature|role. Android will not grant
 * any of them to a normally installed app through any dialog, and the platform rejects
 * the underlying call even when the manifest declares them. Shizuku or Device Owner is
 * the only path, and that is stated in every refusal because that is the whole answer.
 *
 * WHAT IS NOT PRETENDED. No capture is attempted and then dressed up as working. The
 * guard runs first and its sentence is the result. Nothing here catches a platform
 * refusal into a success payload.
 */

@Serializable
data class PrivilegedCaptureResult(
    val success: Boolean,
    val capability: String,
    /** The tier the platform assigns, so a reader can see why it cannot be granted. */
    val tier: String = "PRIVILEGED",
    /** The unprivileged route to the same goal, when one genuinely exists. */
    val alternative: String? = null,
    val note: String? = null,
    val error: String? = null,
) : ToolResultData

object PrivilegedCaptureDomainDefinitions : ToolSetRegistration {
    const val CAPTURE_OUTPUT = "captureOutput"
    const val CAPTURE_VIDEO_OUTPUT = "captureVideoOutput"
    const val CAPTURE_SECURE_OUTPUT = "captureSecureOutput"

    override val definitions = listOf(
        ToolDefinition(
            name = CAPTURE_OUTPUT,
            description = "Record the sound other apps are playing, rather than what the microphone " +
                "hears. THIS CANNOT WORK ON A NORMALLY INSTALLED APP: Android reserves " +
                "CAPTURE_AUDIO_OUTPUT for system apps and refuses it through every dialog, so the tool " +
                "refuses and names Shizuku or Device Owner as the only path. For what the room hears, " +
                "use listenStart or transcribeSpeech instead.",
            category = "audio",
            parameters = listOf(
                ToolParameter(
                    "durationSeconds", ToolParameterType.Integer, false,
                    "How long the capture would run, 1 to 300 seconds. Default 30."
                ),
            ),
            permissions = listOf(TeamThreePermissions.CAPTURE_AUDIO_OUTPUT.permission),
        ),
        ToolDefinition(
            name = CAPTURE_VIDEO_OUTPUT,
            description = "Capture the raw video output stream rather than a screen recording. THIS " +
                "CANNOT WORK ON A NORMALLY INSTALLED APP: CAPTURE_VIDEO_OUTPUT is a signature-level " +
                "capability, so the tool refuses and names Shizuku or Device Owner. The unprivileged " +
                "route to a similar goal is takeScreenshot or recordScreen, which capture the " +
                "composited screen rather than the raw stream.",
            category = "screen",
            parameters = listOf(
                ToolParameter(
                    "durationSeconds", ToolParameterType.Integer, false,
                    "How long the capture would run, 1 to 300 seconds. Default 10."
                ),
            ),
            permissions = listOf(TeamThreePermissions.CAPTURE_VIDEO_OUTPUT.permission),
        ),
        ToolDefinition(
            name = CAPTURE_SECURE_OUTPUT,
            description = "Capture secure surfaces, including windows the platform marks as protected. " +
                "THIS CANNOT WORK ON A NORMALLY INSTALLED APP: CAPTURE_SECURE_VIDEO_OUTPUT is held by " +
                "system roles only, so the tool refuses and names Shizuku or Device Owner. It is also " +
                "the capability with the most obvious misuse potential: it can see content an app " +
                "deliberately hid from capture, so a person should be told plainly what it would mean " +
                "before it is ever enabled.",
            category = "screen",
            parameters = listOf(
                ToolParameter(
                    "durationSeconds", ToolParameterType.Integer, false,
                    "How long the capture would run, 1 to 300 seconds. Default 10."
                ),
            ),
            permissions = listOf(TeamThreePermissions.CAPTURE_SECURE_VIDEO_OUTPUT.permission),
        ),
    )

    override fun executorClass(): KClass<out ToolExecutor> = PrivilegedCaptureDomainExecutor::class
    override fun extractorClass(): KClass<out ToolResultExtractor>? = null
}

/**
 * The executor behind the three privileged capture tools.
 *
 * Each one guards with its real spec, and because all three are PRIVILEGED tier the
 * guard's sentence already names the platform reason and Shizuku. What this executor
 * adds on top is the unprivileged alternative, in the tool's own result shape, so the
 * model has somewhere to go rather than only a wall.
 *
 * THE GUARD IS UNCONDITIONAL. If somebody later makes the app a system app, the guard
 * still runs first and reads granted, and only then would a real implementation matter.
 * Until then there is nothing behind the guard, and saying so is the honest shape.
 */
class PrivilegedCaptureDomainExecutor(private val context: Context) : ToolExecutor {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    override suspend fun execute(toolName: String, args: Map<String, Any?>): ToolExecutionResult = when (toolName) {
        PrivilegedCaptureDomainDefinitions.CAPTURE_OUTPUT -> refused(
            TeamThreePermissions.CAPTURE_AUDIO_OUTPUT,
            capability = "audio output capture",
            alternative = "listenStart or transcribeSpeech for what the microphone hears.",
        )
        PrivilegedCaptureDomainDefinitions.CAPTURE_VIDEO_OUTPUT -> refused(
            TeamThreePermissions.CAPTURE_VIDEO_OUTPUT,
            capability = "raw video output capture",
            alternative = "takeScreenshot or recordScreen for the composited screen.",
        )
        PrivilegedCaptureDomainDefinitions.CAPTURE_SECURE_OUTPUT -> refused(
            TeamThreePermissions.CAPTURE_SECURE_VIDEO_OUTPUT,
            capability = "secure surface capture",
            alternative = "recordScreen, which cannot see protected windows by design.",
        )
        else -> ToolExecutionResult.error("Unknown tool: $toolName")
    }

    /**
     * Refuse through the catalogue, then attach the unprivileged alternative.
     *
     * The refusal body is built by PermissionGrants so the sentence is identical to
     * every other privileged refusal in the app. The alternative is appended to the
     * structured payload rather than spliced into that sentence: the sentence is the
     * batch's contract and rewriting it here would make it a second, drifting copy.
     */
    private fun refused(
        spec: PermissionSpec,
        capability: String,
        alternative: String,
    ): ToolExecutionResult {
        val check = PermissionGrants.check(context, spec)
        val refusal = PermissionGrants.refusal(check)

        val body = PrivilegedCaptureResult(
            success = false,
            capability = capability,
            tier = spec.tier.name,
            alternative = alternative,
            note = "Even with this capability there is a real implementation gap: no privileged " +
                "transport such as Shizuku is wired into this build, so enabling the permission alone " +
                "would still not make this tool produce a file.",
            error = refusal.error,
        )
        // The refusal is returned as both the payload and the failure, so a caller that
        // reads the typed result and a caller that reads `error` see the same truth.
        return refusal.copy(
            rawJson = json.encodeToString(PrivilegedCaptureResult.serializer(), body),
            resultData = body,
        )
    }
}

/**
 * The privileged capture domain's wiring.
 */
object PrivilegedCaptureDomainModule : DomainToolModule {
    override val id: String = "privileged_capture"

    override val registrations: List<ToolSetRegistration> = listOf(PrivilegedCaptureDomainDefinitions)

    override fun bindings(): Module = module {
        factory { PrivilegedCaptureDomainExecutor(get()) }
    }
}
