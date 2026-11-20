// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.tools.registry

/**
 * A tool executor that needs to know WHO called it.
 *
 * WHY AN OPTIONAL INTERFACE RATHER THAN A PARAMETER ON [ToolExecutor]. Adding a
 * third parameter to `execute` would have meant editing every executor in the app
 * to thread through an argument that almost all of them ignore, which is exactly
 * the churn [ToolExecutor.executeWithProgress] was introduced to avoid. Kotlin's
 * default arguments do not help here: an interface method with a default value
 * still has to be re-declared with the wider signature by every implementer, so
 * "defaulted parameter" and "no churn" are not the same thing.
 *
 * WHO IMPLEMENTS IT. Only executors that write something carrying an author.
 * Today that is the memory tool family, because a fact's provenance is the one
 * place in the app where "which intelligence wrote this" is a real question with
 * a real answer. A camera tool has no such question to answer.
 *
 * WHERE THE ACTOR COMES FROM. The dispatcher, which was told by the caller. The
 * main chat path passes [ToolActor.guru] with the conversation it is answering
 * in; an agent passes [ToolActor.agent] with its own id and name. A caller that
 * says nothing passes [ToolActor.UNATTRIBUTED], which writes exactly the
 * provenance the app wrote before attribution existed, so an untaught call site
 * degrades to the old behaviour instead of guessing an identity.
 */
interface ActorAwareToolExecutor {

    /**
     * Run a tool knowing who asked.
     *
     * [progress] is carried for the same reason [ToolExecutor.executeWithProgress]
     * carries it: the dispatcher has one call site and the executor must receive
     * everything the dispatcher holds, actor and progress both.
     */
    suspend fun executeAsActor(
        toolName: String,
        args: Map<String, Any?>,
        actor: ToolActor,
        progress: ToolProgressSink,
    ): ToolExecutionResult
}
