// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.tools.permission

import android.content.Context
import com.unuslumen.app.data.tools.registry.ToolDefinition
import com.unuslumen.app.data.tools.registry.ToolExecutionResult
import com.unuslumen.app.data.tools.registry.ToolExecutor
import com.unuslumen.app.data.tools.registry.ToolParameter
import com.unuslumen.app.data.tools.registry.ToolParameterType
import com.unuslumen.app.data.tools.registry.ToolResultData
import com.unuslumen.app.data.tools.registry.ToolResultExtractor
import com.unuslumen.app.data.tools.registry.ToolSetRegistration
import com.unuslumen.app.data.tools.registry.permission.GrantCheck
import com.unuslumen.app.data.tools.registry.permission.GrantOrchestrator
import com.unuslumen.app.data.tools.registry.permission.PermissionSpec
import com.unuslumen.app.data.tools.registry.permission.PermissionTier
import com.unuslumen.app.data.tools.registry.permission.TeamThreePermissions
import com.unuslumen.app.data.tools.registry.permission.DomainToolModule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.koin.core.module.Module
import org.koin.dsl.module
import kotlin.reflect.KClass

/**
 * The permission domain: what GURU may do, what is missing, and how to get it.
 *
 * WHY THESE TOOLS EXIST. [TeamThreePermissions] holds 104 specs, [GrantOrchestrator] knows the
 * route for every tier, and until this file neither was reachable from a conversation. A
 * catalogue nothing can read and an orchestrator nothing can call are a library, not a
 * capability: GURU could not answer "why can't you read my texts" with anything but a refusal
 * written by whichever tool happened to fail.
 *
 * THREE TOOLS, THREE QUESTIONS.
 *
 * `permissionStatus` answers "do I hold this, and if not, why not" for one permission or for
 * the whole catalogue, because the two are the same question at different widths.
 *
 * `permissionRequest` acts. It walks the route the platform actually offers for that tier, and
 * it reports what landed by re-reading the platform rather than by reporting what it asked for.
 *
 * `permissionRoute` is the explainer with no side effect, for when a model needs to tell a
 * person what to do without touching anything.
 *
 * WHAT THE RESULTS NEVER DO. They never report a grant from an intent that started, never call
 * a privileged permission "not granted yet" when the platform will not grant it at all, and
 * never invent a settings screen. Every one of those three is a defect this batch exists to
 * remove, and each has its own state token so a caller can branch without parsing prose.
 */

@Serializable
data class PermissionStateEntry(
    val permission: String,
    val label: String,
    val tier: String,
    val state: String,
    val held: Boolean,
    /** The exact route a person can follow, or null when no dialog can grant it. */
    val grantRoute: String? = null,
    /** What holding it buys, in one clause about the person rather than the API. */
    val unlocks: String? = null,
)

@Serializable
data class PermissionStatusResult(
    val success: Boolean,
    /** The entries asked for, in catalogue order. */
    val permissions: List<PermissionStateEntry> = emptyList(),
    val heldCount: Int = 0,
    val deniedCount: Int = 0,
    val totalCount: Int = 0,
    /** One sentence for the case where a single permission was asked about. */
    val sentence: String? = null,
    val note: String? = null,
    val error: String? = null,
) : ToolResultData

@Serializable
data class PermissionRequestResult(
    /** True only when the platform was re-read and reports the permission held. */
    val success: Boolean,
    val permission: String,
    val label: String,
    val tier: String,
    val route: String? = null,
    /** What was actually executed, so the answer cannot claim an action nobody took. */
    val actionTaken: String? = null,
    val landed: Boolean = false,
    /** The state after the attempt, re-read from the platform. */
    val stateAfter: String? = null,
    val grantRoute: String? = null,
    val sentence: String? = null,
    val note: String? = null,
    val error: String? = null,
) : ToolResultData

@Serializable
data class PermissionRouteResult(
    val success: Boolean,
    val permission: String,
    val label: String,
    val tier: String,
    val held: Boolean = false,
    val grantRoute: String? = null,
    val canBeGranted: Boolean = false,
    /** Why it cannot be granted, when it cannot. Null when a route exists. */
    val blockedBy: String? = null,
    val sentence: String? = null,
    val error: String? = null,
) : ToolResultData

object PermissionDomainDefinitions : ToolSetRegistration {
    const val PERMISSION_STATUS = "permissionStatus"
    const val PERMISSION_REQUEST = "permissionRequest"
    const val PERMISSION_ROUTE = "permissionRoute"

    override val definitions = listOf(
        ToolDefinition(
            name = PERMISSION_STATUS,
            description = "Report whether GURU holds a permission, and when it does not, say " +
                "exactly why and what would change it. Pass a permission name for one answer, or " +
                "omit it for the whole catalogue. The answer distinguishes four things that are " +
                "easy to confuse: a runtime grant the person can give, a Settings toggle, a " +
                "privileged permission no dialog can grant, and hardware this phone does not " +
                "have. A denial here is never an empty result.",
            category = "permissions",
            parameters = listOf(
                ToolParameter(
                    "permission", ToolParameterType.String, false,
                    "A permission string such as android.permission.READ_SMS, or a bare name such " +
                        "as READ_SMS. Omit to report every permission GURU knows about.",
                ),
                ToolParameter(
                    "deniedOnly", ToolParameterType.Boolean, false,
                    "Report only the permissions GURU does not hold. Default false.",
                ),
                ToolParameter(
                    "tier", ToolParameterType.Enum, false,
                    "Limit to one tier. Omit for all.",
                    listOf("RUNTIME", "INSTALL", "PRIVILEGED", "APPOP", "ROLE", "SERVICE", "SELF_GUARD", "HARDWARE"),
                ),
            ),
            permissions = emptyList(),
        ),
        ToolDefinition(
            name = PERMISSION_REQUEST,
            description = "Ask the platform for a permission by name, walking whichever route that " +
                "permission actually has: the runtime dialog, the Settings screen that holds the " +
                "toggle, the shell UID, or Device Owner. It reports the grant as landed only after " +
                "re-reading the platform, so an intent that merely opened proves nothing. When no " +
                "route exists on this device the result says which one is missing rather than " +
                "claiming a failure, because a privileged permission is not the person's oversight.",
            category = "permissions",
            parameters = listOf(
                ToolParameter(
                    "permission", ToolParameterType.String, true,
                    "The permission to request, such as android.permission.CAMERA or CAMERA.",
                ),
            ),
            permissions = emptyList(),
        ),
        ToolDefinition(
            name = PERMISSION_ROUTE,
            description = "Explain how a permission is obtained on this device without touching " +
                "anything. Answers three questions in one: whether it is already held, whether " +
                "any route can succeed here, and if not, what stands in the way. Use this when a " +
                "person asks what they need to do, rather than permissionRequest which acts.",
            category = "permissions",
            parameters = listOf(
                ToolParameter(
                    "permission", ToolParameterType.String, true,
                    "The permission to explain, such as android.permission.READ_SMS or READ_SMS.",
                ),
            ),
            permissions = emptyList(),
        ),
    )

    override fun executorClass(): KClass<out ToolExecutor> = PermissionDomainExecutor::class
    override fun extractorClass(): KClass<out ToolResultExtractor>? = null
}

class PermissionDomainExecutor(private val context: Context) : ToolExecutor {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    override suspend fun execute(toolName: String, args: Map<String, Any?>): ToolExecutionResult =
        when (toolName) {
            PermissionDomainDefinitions.PERMISSION_STATUS -> permissionStatus(args)
            PermissionDomainDefinitions.PERMISSION_REQUEST -> permissionRequest(args)
            PermissionDomainDefinitions.PERMISSION_ROUTE -> permissionRoute(args)
            else -> ToolExecutionResult.error("Unknown tool: $toolName")
        }

    // ------------------------------------------------------------------
    // Status
    // ------------------------------------------------------------------

    private suspend fun permissionStatus(args: Map<String, Any?>): ToolExecutionResult =
        withContext(Dispatchers.IO) {
            val requested = (args["permission"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
            val deniedOnly = args["deniedOnly"] as? Boolean ?: false
            val tierFilter = (args["tier"] as? String)?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }

            if (requested != null) {
                val spec = resolveSpec(requested)
                    ?: return@withContext failure(
                        PermissionStatusResult.serializer(),
                        PermissionStatusResult(
                            success = false,
                            error = "GURU has no catalogue entry for '$requested'. That is a tool " +
                                "defect rather than a permission you can grant, so it is reported " +
                                "rather than guessed at.",
                        ),
                    )

                val check = GrantOrchestrator.check(context, spec)
                val entry = toEntry(spec, check)
                return@withContext success(
                    PermissionStatusResult.serializer(),
                    PermissionStatusResult(
                        success = true,
                        permissions = listOf(entry),
                        heldCount = if (check.allowed) 1 else 0,
                        deniedCount = if (check.allowed) 0 else 1,
                        totalCount = 1,
                        sentence = check.sentence(),
                    ),
                )
            }

            val specs = if (tierFilter != null) {
                val tier = runCatching { PermissionTier.valueOf(tierFilter) }.getOrNull()
                    ?: return@withContext failure(
                        PermissionStatusResult.serializer(),
                        PermissionStatusResult(
                            success = false,
                            error = "'$tierFilter' is not a tier. The tiers are " +
                                PermissionTier.entries.joinToString(", ") { it.name } + ".",
                        ),
                    )
                TeamThreePermissions.all.values.filter { it.tier == tier }
            } else {
                TeamThreePermissions.all.values.toList()
            }

            val entries = specs
                .map { spec -> toEntry(spec, GrantOrchestrator.check(context, spec)) }
                .filter { !deniedOnly || !it.held }
                .sortedWith(compareBy({ it.tier }, { it.permission }))

            val held = entries.count { it.held }
            success(
                PermissionStatusResult.serializer(),
                PermissionStatusResult(
                    success = true,
                    permissions = entries,
                    heldCount = held,
                    deniedCount = entries.size - held,
                    totalCount = entries.size,
                    note = "Tiers are the platform's own distinction, not GURU's preference: a " +
                        "PRIVILEGED entry is one no dialog can grant, so it is reported as such " +
                        "rather than as something you have not done yet.",
                ),
            )
        }

    // ------------------------------------------------------------------
    // Request
    // ------------------------------------------------------------------

    private suspend fun permissionRequest(args: Map<String, Any?>): ToolExecutionResult {
        val requested = (args["permission"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
            ?: return failure(
                PermissionRequestResult.serializer(),
                PermissionRequestResult(
                    success = false,
                    permission = "",
                    label = "",
                    tier = "",
                    error = "This call needs a 'permission' to request.",
                ),
            )

        val spec = resolveSpec(requested)
            ?: return failure(
                PermissionRequestResult.serializer(),
                PermissionRequestResult(
                    success = false,
                    permission = requested,
                    label = requested,
                    tier = "",
                    error = "GURU has no catalogue entry for '$requested', so no route exists for " +
                        "it and nothing was attempted.",
                ),
            )

        val outcome = GrantOrchestrator.grant(context, spec)
        val after = outcome.check

        val result = PermissionRequestResult(
            success = outcome.landed,
            permission = spec.permission,
            label = spec.label,
            tier = spec.tier.name,
            route = outcome.plan.route?.name,
            actionTaken = outcome.actionTaken,
            landed = outcome.landed,
            stateAfter = after.state.name,
            grantRoute = spec.grantRoute,
            sentence = outcome.sentence(),
            note = buildString {
                if (outcome.landed) {
                    append("The platform was re-read after the attempt and reports the grant is ")
                    append("held, which is the only evidence this tool accepts. ")
                } else if (outcome.plan.canSucceed) {
                    append("The attempt was made and the platform does not report the grant yet, ")
                    append("so it has not landed. ")
                    if (spec.tier == PermissionTier.RUNTIME || spec.tier == PermissionTier.APPOP) {
                        append("A dialog or a Settings screen needs the person's own tap, and this ")
                        append("result is from before they gave it. ")
                    }
                } else {
                    append("No route can grant this on this device, so nothing was attempted. ")
                }
                append("The sentence above states why in full.")
            }.trim(),
        )

        return if (result.landed) {
            success(PermissionRequestResult.serializer(), result)
        } else {
            // A request that did not land is a failed call, not a successful one with bad news.
            // Marking it success would let a model report that it obtained the permission.
            failure(PermissionRequestResult.serializer(), result)
        }
    }

    // ------------------------------------------------------------------
    // Route
    // ------------------------------------------------------------------

    private suspend fun permissionRoute(args: Map<String, Any?>): ToolExecutionResult {
        val requested = (args["permission"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
            ?: return failure(
                PermissionRouteResult.serializer(),
                PermissionRouteResult(
                    success = false,
                    permission = "",
                    label = "",
                    tier = "",
                    error = "This call needs a 'permission' to explain.",
                ),
            )

        val spec = resolveSpec(requested)
            ?: return failure(
                PermissionRouteResult.serializer(),
                PermissionRouteResult(
                    success = false,
                    permission = requested,
                    label = requested,
                    tier = "",
                    error = "GURU has no catalogue entry for '$requested'.",
                ),
            )

        return withContext(Dispatchers.IO) {
            val check = GrantOrchestrator.check(context, spec)
            val plan = GrantOrchestrator.plan(context, spec)
            success(
                PermissionRouteResult.serializer(),
                PermissionRouteResult(
                    success = true,
                    permission = spec.permission,
                    label = spec.label,
                    tier = spec.tier.name,
                    held = check.allowed,
                    grantRoute = spec.grantRoute,
                    canBeGranted = plan.canSucceed,
                    blockedBy = plan.blockedBy,
                    // The sentence answers the person's actual question in one line: held, or
                    // why not, or what to do. The blocked reason wins when there is one,
                    // because "no setting changes this" is more useful than a route that
                    // cannot work.
                    sentence = if (check.allowed) check.sentence() else plan.blockedBy ?: check.sentence(),
                ),
            )
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /**
     * Find the catalogue entry for a permission the model named.
     *
     * Accepts the full platform string or the bare name, because a model reading a tool result
     * has the bare name in front of it and should not have to reconstruct the namespace. A
     * bare name that matches more than one entry is a real ambiguity: the catalogue holds
     * permissions under two namespaces, so the first match wins and the answer names the
     * permission it resolved to, which is visible in the result.
     */
    private fun resolveSpec(requested: String): PermissionSpec? {
        TeamThreePermissions.forPermission(requested)?.let { return it }
        if (!requested.contains('.')) {
            val bare = "android.permission.$requested"
            TeamThreePermissions.forPermission(bare)?.let { return it }
            // SET_ALARM lives under com.android.alarm.permission, so a bare name needs the
            // catalogue searched rather than a second guess at a prefix.
            return TeamThreePermissions.all.values.firstOrNull {
                it.permission.substringAfterLast('.') == requested
            }
        }
        return null
    }

    private fun toEntry(spec: PermissionSpec, check: GrantCheck): PermissionStateEntry =
        PermissionStateEntry(
            permission = spec.permission,
            label = spec.label,
            tier = spec.tier.name,
            state = check.state.name,
            held = check.allowed,
            grantRoute = spec.grantRoute,
            unlocks = spec.why,
        )

    private fun <T : ToolResultData> success(
        serializer: kotlinx.serialization.KSerializer<T>,
        payload: T,
    ): ToolExecutionResult = ToolExecutionResult(
        rawJson = json.encodeToString(serializer, payload),
        resultData = payload,
        resultObject = null,
        success = true,
        error = null,
    )

    private fun <T : ToolResultData> failure(
        serializer: kotlinx.serialization.KSerializer<T>,
        payload: T,
    ): ToolExecutionResult = ToolExecutionResult(
        rawJson = json.encodeToString(serializer, payload),
        resultData = payload,
        resultObject = null,
        success = false,
        // Every result type in this file is named, and there is no `else`, so adding a fourth
        // payload without teaching this function about it is a compile error rather than a
        // silent null error message on a failed call.
        error = when (payload) {
            is PermissionStatusResult -> payload.error ?: payload.sentence
            is PermissionRequestResult -> payload.sentence ?: payload.error
            is PermissionRouteResult -> payload.blockedBy ?: payload.error
            else -> "The permission call failed without a stated reason."
        },
    )
}

object PermissionDomainModule : DomainToolModule {
    override val id: String = "permissions_management"

    override val registrations: List<ToolSetRegistration> = listOf(PermissionDomainDefinitions)

    override fun bindings(): Module = module {
        factory { PermissionDomainExecutor(get()) }
    }
}
