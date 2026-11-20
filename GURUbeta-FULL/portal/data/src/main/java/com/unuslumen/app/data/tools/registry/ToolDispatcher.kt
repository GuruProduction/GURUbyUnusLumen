// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.tools.registry

import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.unuslumen.app.data.tools.registry.permission.PermissionGrants
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * ToolDispatcher — Central dispatch for all tool execution.
 *
 * Full flow:
 * 1. Resolve the tool name via ToolNameResolver (passes rawArgs for disambiguation).
 * 2. If not in registry, return UNKNOWN_TOOL.
 * 3. Get the ToolDefinition.
 * 4. Check Android runtime permissions. If any required permission is not granted,
 *    return PERMISSION_DENIED. AiRepositoryImpl handles the PermissionGateway broadcast.
 * 5. Run ToolSecurityPolicy.check(definition, args).
 * 6. Run ToolArgumentRepair.repair(definition, args).
 * 7. Get the ToolExecutor and call execute().
 * 8. If resultObject is null and extractor exists, call extractor.extract().
 * 9. Return ToolDispatchResult.
 *
 * Step 7 also hands the outcome to ToolObservationRecorder, for a call that
 * succeeded and for one that failed. Recording only failures made `success` a
 * constant zero in the observation table, which is issue 418 defect 4: a table
 * whose every row said failure could not answer "did this tool work here
 * before", and that is the one question its retrieval tool exists to answer.
 *
 * Both writes are fire-and-forget on the application scope, so neither can delay
 * or fail the dispatch and neither changes what the dispatcher returns.
 */
class ToolDispatcher(
    private val registry: ToolRegistry,
    private val nameResolver: ToolNameResolver,
    private val securityPolicy: ToolSecurityPolicy,
    private val argumentRepair: ToolArgumentRepair,
    private val context: Context,
    private val observationRecorder: ToolObservationRecorder? = null,
) {

    /**
     * A tool call that has passed every check and has not yet run.
     *
     * WHY THIS EXISTS AT ALL. An agent run has to record the arguments a tool is about
     * to receive BEFORE the tool receives them, because a call whose outcome is unknown
     * must never be repeated on recovery and the record is the only proof an attempt was
     * made. But the arguments that actually reach the executor are not the model's: they
     * are the repaired ones, and the repair happens inside this dispatcher.
     *
     * So the seam is named. [prepare] does resolve, permission, security and repair, and
     * returns the exact arguments that would be dispatched. The caller writes its start
     * event with those. Then it calls [executePrepared], which runs the tool and records
     * the observation, exactly as before.
     *
     * NOTHING ABOUT THE CHECKS MOVED. `prepare` calls the same `securityPolicy.check` and
     * the same `argumentRepair.repair`. `dispatch` and `dispatchStreaming` are now
     * `prepare` followed by `executePrepared`, so every existing caller keeps precisely
     * the behaviour it had.
     */
    data class PreparedInvocation(
        val resolvedName: String,
        val definition: ToolDefinition,
        /**
         * What the executor will actually be handed, after repair.
         *
         * `ToolArgumentRepair.repair` returns a decoded `Map<String, Any?>` rather than
         * a `JsonObject`, and the executor takes that map directly. This field therefore
         * carries exactly the value the tool will receive, which is the whole point of
         * the boundary: anything else would be recording a call that did not happen.
         */
        val repairedArgs: Map<String, Any?>,
        /** What the model wrote, kept so a caller can show the difference. */
        val rawArgs: JsonObject,
    )

    sealed interface Preparation {
        data class Ready(val invocation: PreparedInvocation) : Preparation

        /**
         * The call was stopped before execution.
         *
         * [kind] distinguishes an unknown tool, a missing Android permission and a
         * security refusal, because a caller recording what happened must be able to say
         * which one it was without re-deriving it.
         */
        data class Refused(
            val reason: String,
            val kind: RefusalKind,
            val resolvedName: String? = null,
            /**
             * The catalogue's own refusal, when the refusal was a permission.
             *
             * Carried rather than rebuilt from [reason], because `permissionDenied`
             * needs the whole check to produce the structured body the interface and
             * the model both read. Rebuilding it from a sentence would lose that.
             */
            val grantCheck: com.unuslumen.app.data.tools.registry.permission.GrantCheck? = null,
        ) : Preparation
    }

    enum class RefusalKind { UNKNOWN_TOOL, PERMISSION_DENIED, SECURITY_BLOCKED, NO_EXECUTOR }

    /**
     * The repaired arguments as JSON, so the security policy can inspect them.
     *
     * `ToolArgumentRepair.repair` decodes values into `Any?` for the executor, which the
     * policy cannot read. This converts back, preserving type rather than stringifying
     * everything: a boolean that became the string "true" would be inspected as something
     * it is not.
     */
    private fun repairedArgsToJson(repaired: Map<String, Any?>): JsonObject = JsonObject(
        repaired.mapValues { (_, value) ->
            when (value) {
                null -> kotlinx.serialization.json.JsonNull
                is Boolean -> JsonPrimitive(value)
                is Number -> JsonPrimitive(value)
                is String -> JsonPrimitive(value)
                else -> JsonPrimitive(value.toString())
            }
        }
    )

    /**
     * Resolve, check, repair, and stop.
     *
     * The returned [PreparedInvocation.repairedArgs] are the arguments the tool will
     * receive. A caller that records anything else is recording a call that did not
     * happen.
     */
    suspend fun prepare(
        toolName: String,
        rawArgs: JsonObject,
        /**
         * Which run and which invocation this call belongs to, when it belongs to one.
         *
         * OPTIONAL BECAUSE MOST DISPATCHES ARE NOT IN A RUN. A tool call from the main chat
         * has no run, and inventing one would attach a permission decision to an execution
         * that never happened. Null means "this call is not inside an agent run", which is
         * the honest value for the main conversation.
         */
        runId: String? = null,
        invocationId: String? = null,
        /**
         * Where this call's permission decision is reported.
         *
         * A PARAMETER RATHER THAN A CONSTRUCTOR FIELD, BECAUSE IT IS PER RUN. The
         * dispatcher is a singleton serving every conversation in the app; a telemetry sink
         * that knew one run would write that run's decisions for every caller forever. The
         * caller with a run constructs the sink, so the run is decided at the call site that
         * actually has one.
         *
         * Defaulted to a sink that records nothing, so the main chat passes through
         * unchanged and no dispatch ever waits on a write.
         */
        telemetry: ToolTelemetry = ToolTelemetry.NONE,
    ): Preparation {
        val resolvedName = nameResolver.resolve(toolName, registry, rawArgs)
            ?: return Preparation.Refused("No tool called '$toolName'.", RefusalKind.UNKNOWN_TOOL)

        val definition = registry.getDefinition(resolvedName)
            ?: return Preparation.Refused("No tool called '$resolvedName'.", RefusalKind.UNKNOWN_TOOL)

        for (perm in definition.permissions) {
            if (ContextCompat.checkSelfPermission(context, perm) != PackageManager.PERMISSION_GRANTED) {
                // The refusal is built from the catalogue, as it always was: a bare
                // platform permission string names no route and does not say whether a
                // dialog could fix it.
                val check = PermissionGrants.refusalForPermission(context, perm)
                return Preparation.Refused(
                    check.sentence(),
                    RefusalKind.PERMISSION_DENIED,
                    resolvedName,
                    grantCheck = check,
                )
            }
        }

        if (registry.getExecutor(resolvedName) == null) {
            return Preparation.Refused("No executor exists for '$resolvedName'.", RefusalKind.NO_EXECUTOR, resolvedName)
        }

        /*
         * A REAL DECISION, REPORTED FROM THE PLACE THAT MADE IT.
         *
         * THIS IS THE ONLY LINE IN THE CODEBASE THAT CAN SAY A PERMISSION WAS GRANTED,
         * because it is the only place that has just checked every declared permission and
         * found them all granted. `permissions` may be empty, and that is still a decision:
         * the dispatcher looked, found nothing to check, and authorised the call.
         *
         * REPORTED BEFORE THE SECURITY POLICY RUNS AND BEFORE EXECUTION. The permission
         * question is answered at this point; whether the arguments then pass the policy is
         * a different question with a different event. Reporting it later would make a call
         * that failed the policy look like it was never permitted, which is a different and
         * false claim.
         */
        runCatching {
            telemetry.permissionGranted(
                toolName = toolName,
                resolvedName = resolvedName,
                permissions = definition.permissions,
                runId = runId,
                invocationId = invocationId,
            )
        }

        /*
         * REPAIR FIRST, THEN CHECK, BECAUSE THE CHECK MUST SEE WHAT WILL ACTUALLY RUN.
         *
         * The previous order ran the security policy against the model's raw arguments and
         * then repaired them. That left a hole: a value the repair rewrites — a string
         * coerced, a key renamed, an orphan positional-matched into a parameter — reached
         * the executor never having been inspected in the form it would execute in. The
         * policy's own comment says it inspects by parameter type, so a parameter that
         * only becomes a ShellCommand after repair was never examined at all.
         *
         * The repaired map is converted back to JSON so the policy inspects the same values
         * the executor receives. Both checks still run; only their order changed, and the
         * order is now the safe one.
         */
        val repaired = argumentRepair.repair(definition, rawArgs)
        val repairedJson = repairedArgsToJson(repaired)

        val securityCheck = securityPolicy.check(definition, repairedJson)
        if (securityCheck.blocked) {
            return Preparation.Refused(
                securityCheck.reason,
                RefusalKind.SECURITY_BLOCKED,
                resolvedName,
            )
        }

        return Preparation.Ready(
            PreparedInvocation(
                resolvedName = resolvedName,
                definition = definition,
                repairedArgs = repaired,
                rawArgs = rawArgs,
            )
        )
    }

    /**
     * Run a prepared call and write its observation.
     *
     * THE EXECUTOR IS LOOKED UP AGAIN, not carried in the prepared object, so a
     * prepared invocation is a plain value that can be logged, compared and held in a
     * checkpoint without pinning a live object.
     */
    suspend fun executePrepared(
        invocation: PreparedInvocation,
        conversationId: String? = null,
        progress: ToolProgressSink = ToolProgressSink.NONE,
        agentRunId: String? = null,
        /**
         * The agent whose run is dispatching, when an agent is the caller.
         *
         * INSTALLED ON THE COROUTINE HERE, BECAUSE THIS IS WHERE THE AGENT PATH ACTUALLY RUNS. The
         * agent loop calls this directly rather than going through [dispatchStreaming], so a scope
         * set only there would never reach an agent's tool call. A tool that writes memory reads the
         * agent from that scope, which is what stops a model naming an agent in its arguments and
         * writing into somebody else's memory.
         *
         * NULL IS THE MAIN ASSISTANT, which is a real value rather than an unknown one: a memory write
         * from the portal belongs to GURU's own memory, not to any agent's.
         */
        agentName: String? = null,
        agentInvocationId: String? = null,
    ): ToolDispatchResult = com.unuslumen.app.data.agents.withDispatchScope(
        conversationId = conversationId,
        agentName = agentName,
    ) {
        executePreparedInScope(
            invocation = invocation,
            conversationId = conversationId,
            progress = progress,
            agentRunId = agentRunId,
            agentInvocationId = agentInvocationId,
        )
    }

    /** The execution itself, run inside the scope the entry point installed. */
    private suspend fun executePreparedInScope(
        invocation: PreparedInvocation,
        conversationId: String?,
        progress: ToolProgressSink,
        agentRunId: String?,
        agentInvocationId: String?,
    ): ToolDispatchResult {
        val repairedArgs = invocation.repairedArgs
        val resolvedName = invocation.resolvedName
        val executor = registry.getExecutor(resolvedName)
            ?: return ToolDispatchResult.unknownTool(resolvedName)

        val startedAt = System.currentTimeMillis()
        val execResult = executor.executeWithProgress(resolvedName, repairedArgs, progress)
        val durationMs = System.currentTimeMillis() - startedAt

        val didFail = failed(execResult)
        if (didFail) {
            observationRecorder?.recordFailure(
                packageName = null,
                activityClass = null,
                toolName = resolvedName,
                argumentsJson = invocation.rawArgs.toString(),
                resultSummary = execResult.error ?: execResult.rawJson.take(500),
                conversationId = conversationId,
                durationMs = durationMs,
                targetValues = observationRecorder.targetValuesFrom(repairedArgs),
                agentRunId = agentRunId,
                agentInvocationId = agentInvocationId,
            )
        } else {
            observationRecorder?.recordSuccess(
                packageName = null,
                activityClass = null,
                toolName = resolvedName,
                argumentsJson = invocation.rawArgs.toString(),
                resultSummary = execResult.rawJson.take(500),
                conversationId = conversationId,
                durationMs = durationMs,
                agentRunId = agentRunId,
                agentInvocationId = agentInvocationId,
            )
        }

        val resultObject = execResult.resultObject
            ?: registry.getExtractor(resolvedName)?.extract(resolvedName, execResult.rawJson, execResult.resultData)

        return ToolDispatchResult(
            resolvedName = resolvedName,
            rawJson = execResult.rawJson,
            resultData = execResult.resultData,
            resultObject = resultObject,
            success = execResult.success,
            error = execResult.error,
            status = if (execResult.success) DispatchStatus.SUCCESS else DispatchStatus.EXECUTION_ERROR
        )
    }

    suspend fun dispatch(
        toolName: String,
        rawArgs: JsonObject,
        conversationId: String? = null
    ): ToolDispatchResult = dispatchStreaming(toolName, rawArgs, conversationId, ToolProgressSink.NONE)

    /**
     * Dispatch a tool and forward whatever it reports while it runs.
     *
     * [progress] is handed straight to the executor's [ToolExecutor.executeWithProgress],
     * which every tool inherits as a plain call. A tool with something to say calls
     * the sink as it finds things; one with nothing to say never touches it. Nothing
     * about the returned result changes.
     */
    /**
     * Dispatch a tool and forward whatever it reports while it runs.
     *
     * PREPARE, THEN EXECUTE, WHICH IS THE SAME TWO STEPS AN AGENT USES. This used to
     * inline all four checks and the execution. It now delegates, so there is one
     * implementation of the permission, security and repair rules rather than two that
     * can drift. Every behaviour is identical to what it was; the only change is that the
     * boundary between checking and running is now a nameable point rather than a line in
     * the middle of a method.
     */
    suspend fun dispatchStreaming(
        toolName: String,
        rawArgs: JsonObject,
        conversationId: String? = null,
        progress: ToolProgressSink = ToolProgressSink.NONE,
    ): ToolDispatchResult {
        /*
         * THE CONVERSATION RIDES THE COROUTINE, INSTALLED HERE.
         *
         * This is the one place every tool call passes through and the one place the
         * conversation id is already in hand, so it is where the dispatch scope is set. A
         * tool that starts an agent — spawnAgent — reads it from the coroutine rather than
         * from a field on the runtime, which means two conversations dispatching at once
         * hold two different values and neither can overwrite the other's.
         *
         * An id of null is installed deliberately rather than skipped: the spawn path then
         * finds nothing and refuses with a sentence naming the cause, instead of inheriting
         * whatever the last dispatch happened to leave behind.
         */
        return com.unuslumen.app.data.agents.withDispatchScope(conversationId) {
            val prepared = prepare(toolName, rawArgs)
            when (prepared) {
                is Preparation.Refused -> when (prepared.kind) {
                    RefusalKind.UNKNOWN_TOOL -> ToolDispatchResult.unknownTool(toolName)
                    RefusalKind.PERMISSION_DENIED ->
                        prepared.grantCheck
                            ?.let { ToolDispatchResult.permissionDenied(it) }
                            // No check carried means no structured body to build, so the
                            // sentence stands alone rather than inventing a body shape.
                            ?: ToolDispatchResult.blocked(prepared.reason)
                    RefusalKind.SECURITY_BLOCKED -> ToolDispatchResult.blocked(prepared.reason)
                    RefusalKind.NO_EXECUTOR ->
                        ToolDispatchResult.unknownTool(prepared.resolvedName ?: toolName)
                }
                is Preparation.Ready -> executePrepared(
                    invocation = prepared.invocation,
                    conversationId = conversationId,
                    progress = progress,
                )
            }
        }
    }

    /**
     * Did this call actually fail?
     *
     * Two shapes of failure reach the dispatcher. An executor that lets the
     * error out returns success=false directly. An executor that catches its own
     * error — which is every accessibility tool, because a failed tap is a real
     * answer rather than an exception — returns success=true and reports the
     * failure in its payload as a boolean `success` field. The payload case is
     * the one that matters most for the log: it is precisely a "clicked the
     * wrong thing" outcome that the next attempt needs the recorded tree for.
     *
     * The payload test parses the JSON rather than searching for the substring,
     * so a tool that legitimately returns text mentioning `"success": false`
     * (reading a config file, echoing a log) is not mistaken for a failure. The
     * cheap substring gate runs first so the parse is skipped on nearly every
     * result.
     *
     * This decides only whether a row is written. Nothing about what the
     * dispatcher returns changes.
     */
    private fun failed(result: ToolExecutionResult): Boolean {
        if (!result.success) return true
        val body = result.rawJson
        if (body.isBlank() || !body.contains("success")) return false
        val reported = runCatching {
            (Json.parseToJsonElement(body) as? JsonObject)
                ?.get("success")
                ?.let { it as? JsonPrimitive }
                ?.takeIf { !it.isString }
                ?.content
        }.getOrNull() ?: return false
        return reported == "false"
    }

}
