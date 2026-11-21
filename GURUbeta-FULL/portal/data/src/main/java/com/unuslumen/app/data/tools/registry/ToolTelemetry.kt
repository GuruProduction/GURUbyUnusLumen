// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.tools.registry

/**
 * Where a permission decision and a progress report become ledger events.
 *
 * ONE SOURCE OF TRUTH, WHICH IS THE POINT OF THIS INTERFACE. The permission decision is
 * made by [ToolDispatcher.prepare] and by nothing else. Two callers each writing their own
 * `TOOL_PERMISSION_GRANTED` would be two claims about one decision, and the first time they
 * disagreed the ledger would hold two answers to a question that has one.
 *
 * So the dispatcher reports here, exactly once per prepared call, and the implementation
 * decides how that becomes an event. The agent paths do not write permission events at all.
 *
 * EVERY METHOD IS A NO-OP BY DEFAULT IN THE SENSE THAT MATTERS: an implementation that has
 * nothing to record writes nothing, and a call with no sink configured behaves exactly as
 * it did before this existed. Nothing about dispatch waits on telemetry, and nothing about
 * dispatch fails because of it.
 */
interface ToolTelemetry {

    /**
     * A call passed every permission check, and here is what was checked.
     *
     * CALLED ONLY WHEN A DECISION WAS ACTUALLY MADE. This is not "the dispatcher ran". It is
     * reported from the branch that has just confirmed every declared permission is granted,
     * which is the only place in the codebase where that fact is true.
     *
     * [permissions] is empty for a tool that declares none, and that is still a real
     * decision: the dispatcher checked, found nothing to check, and the call is authorised.
     */
    fun permissionGranted(
        toolName: String,
        resolvedName: String,
        permissions: List<String>,
        runId: String?,
        invocationId: String?,
    )

    /**
     * A running tool reported something.
     *
     * CARRIED, NOT INVENTED. This is handed the executor's own [ToolProgressEvent] and
     * records what it says. A tool that reports nothing produces no event, which is the
     * honest outcome rather than a fabricated tick.
     */
    fun toolProgress(
        toolName: String,
        invocationId: String?,
        runId: String?,
        event: ToolProgressEvent,
    )

    companion object {
        /**
         * A sink that records nothing.
         *
         * THE DEFAULT, DELIBERATELY. Most dispatches happen for the main chat rather than
         * inside an agent run, and those have no run to attach an event to. Choosing this
         * over a nullable field means every call site reads the same and no caller has to
         * null-check before reporting.
         */
        val NONE: ToolTelemetry = object : ToolTelemetry {
            override fun permissionGranted(
                toolName: String,
                resolvedName: String,
                permissions: List<String>,
                runId: String?,
                invocationId: String?,
            ) = Unit

            override fun toolProgress(
                toolName: String,
                invocationId: String?,
                runId: String?,
                event: ToolProgressEvent,
            ) = Unit
        }
    }
}
