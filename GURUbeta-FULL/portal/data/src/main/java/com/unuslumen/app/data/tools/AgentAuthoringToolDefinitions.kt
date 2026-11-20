// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.tools

import com.unuslumen.app.data.tools.registry.ToolDefinition
import com.unuslumen.app.data.tools.registry.ToolExecutor
import com.unuslumen.app.data.tools.registry.ToolParameter
import com.unuslumen.app.data.tools.registry.ToolParameterType
import com.unuslumen.app.data.tools.registry.ToolResultExtractor
import com.unuslumen.app.data.tools.registry.ToolSetRegistration
import kotlin.reflect.KClass

/**
 * The agent authoring verbs, and the two registry queries that make routing possible.
 *
 * WHY THIS SET EXISTS. The Agents room lets a person write an agent, give it tools,
 * switch it on and publish it, and until now none of that was reachable by the model.
 * GURU could call an agent it had been told the name of, and could install one from
 * the catalogue, but it could not build one. That made the roster a fixed list that
 * only a human could grow, in an app whose whole claim is that it extends itself.
 *
 * WHY CREATE AND EDIT ARE SEPARATE. They fail differently. A create can be refused
 * because the name is taken; an edit can be refused because the agent is publisher
 * delivered and may not be rewritten. One merged tool would report both as "could not
 * save", and the caller would not know whether to pick another name or leave it alone.
 *
 * WHY PUBLISH IS NOT PART OF SAVE. Saving keeps an agent on this phone. Publishing
 * sends it to a catalogue, a review queue and a public listing, and takes a price and
 * a category. Binding them would publish work on the way past, which is the same fault
 * the creator screen already fixed by giving them two buttons.
 *
 * WHY THE AGENT VERBS ARE EXCLUDED FROM EVERY GRANT. An agent may never be granted
 * spawnAgent, createAgent or any other verb in this set, exactly as the creator's
 * picker already refuses them. An agent that can make an agent is a recursion nobody
 * asked for, and one that can grant itself tools has escaped its own permission set.
 */
object AgentAuthoringToolDefinitions : ToolSetRegistration {

    const val CREATE_AGENT = "createAgent"
    const val EDIT_AGENT = "editAgent"
    const val DELETE_AGENT = "deleteAgent"
    const val SET_AGENT_ENABLED = "setAgentEnabled"
    const val PUBLISH_AGENT = "publishAgent"
    const val SEARCH_AGENTS = "searchAgents"
    const val LIST_TOOLS = "listTools"

    /** Stand an agent up to work on its own: a job, an interval, and when it first runs. */
    const val SCHEDULE_AGENT = "scheduleAgent"

    /** Every schedule and when each one next runs, so a caller can see what is armed. */
    const val LIST_SCHEDULES = "listSchedules"

    /** Arm or disarm a schedule without losing its job. */
    const val SET_SCHEDULE_ENABLED = "setScheduleEnabled"

    /** Remove a schedule. The runs it already started stay. */
    const val DELETE_SCHEDULE = "deleteSchedule"

    /**
     * The categories the market browses by.
     *
     * A closed set of six, mirroring EditableAgent.CATEGORIES. A free-text box would
     * produce forty spellings of "coding" and an aisle strip nobody can use, which is
     * the reason the creator asks for one from a fixed row.
     */
    private val CATEGORIES = listOf("code", "memory", "planning", "life", "creative", "other")

    override val definitions = listOf(
        ToolDefinition(
            name = CREATE_AGENT,
            description = "Create a new agent on this phone: a name, the instructions it runs on, and the tools " +
                "it may use. The agent starts switched on and can be called with spawnAgent straight away. " +
                "Instructions are the whole of what it is, so write them as a brief to somebody starting cold: " +
                "the job, the standard, and what to do when something is unclear. Tools are names from listTools; " +
                "an agent may never be granted the ability to spawn or create another agent, and a name that is " +
                "already taken is refused with that reason rather than silently replaced.",
            category = "agents",
            parameters = listOf(
                ToolParameter("name", ToolParameterType.String, true, "A name nobody else on this phone has used"),
                ToolParameter("instructions", ToolParameterType.String, true, "The complete brief the agent runs on"),
                ToolParameter("description", ToolParameterType.String, false, "One line saying what it is for, as another caller would search for it"),
                ToolParameter("tools", ToolParameterType.String, false, "JSON array of tool names it may use. Empty or omitted means it reasons without touching anything."),
            ),
            permissions = emptyList()
        ),
        ToolDefinition(
            name = SCHEDULE_AGENT,
            description = "Arrange for an agent to run a job on its own, every so often, without anybody " +
                "asking it to at the time. This is real unattended work: it starts an ordinary run, with " +
                "the same permissions and the same record as any other, at each due time. THREE THINGS " +
                "MUST BE TRUE OR IT REFUSES. The agent must exist and be switched on; it must have been " +
                "granted scheduled operation in its autonomy settings, because a run nobody is watching " +
                "needs that permission explicitly; and the interval must be at least a minute, because " +
                "work due faster than a model answers would queue faster than it drains. A job that fell " +
                "due more than fifteen minutes ago is counted as missed rather than run late, so a phone " +
                "that was off does not start a backlog.",
            category = "agents",
            parameters = listOf(
                ToolParameter("agent", ToolParameterType.String, true, "Slug or exact name of the agent to run"),
                ToolParameter("title", ToolParameterType.String, true, "A short name for this schedule, shown in its runs' conversation"),
                ToolParameter("job", ToolParameterType.String, true, "The complete instruction, sent afresh each time. It cannot see anything you said when you set this up."),
                ToolParameter("intervalMinutes", ToolParameterType.Integer, true, "How often to run it, in minutes. At least 1."),
                ToolParameter("firstRunInMinutes", ToolParameterType.Integer, false, "How long until the first run. Omitted means at the next sweep, within fifteen minutes."),
            ),
            permissions = emptyList()
        ),
        ToolDefinition(
            name = LIST_SCHEDULES,
            description = "List the standing instructions on this phone: which agent each one starts, how " +
                "often, when it next runs, and whether it is armed. Also reports how many windows each one " +
                "has missed, because a schedule that has been failing says so here rather than looking like " +
                "work that simply did not happen. Pass an agent to see only that agent's schedules.",
            category = "agents",
            parameters = listOf(
                ToolParameter("agent", ToolParameterType.String, false, "Slug or exact name, to list only this agent's schedules"),
            ),
            permissions = emptyList()
        ),
        ToolDefinition(
            name = SET_SCHEDULE_ENABLED,
            description = "Arm or disarm a schedule. Disarming stops it running without losing the job it was " +
                "set up with, so it can be armed again later. Arming one that has no next due time gives it " +
                "one interval from now.",
            category = "agents",
            parameters = listOf(
                ToolParameter("scheduleId", ToolParameterType.String, true, "The id listSchedules reported"),
                ToolParameter("enabled", ToolParameterType.Boolean, true, "True to arm it, false to disarm it"),
            ),
            permissions = emptyList()
        ),
        ToolDefinition(
            name = DELETE_SCHEDULE,
            description = "Remove a schedule for good. Runs it already started are untouched and stay in that " +
                "agent's history, because the work happened; what stops is any future run. Use it when a " +
                "schedule is no longer wanted, and disarm it instead when it is only being paused.",
            category = "agents",
            parameters = listOf(
                ToolParameter("scheduleId", ToolParameterType.String, true, "The id listSchedules reported"),
            ),
            permissions = emptyList()
        ),
        ToolDefinition(
            name = EDIT_AGENT,
            description = "Change an agent that already exists on this phone: its name, its instructions, or the " +
                "tools it may use. Anything omitted is left exactly as it was, so changing one line does not " +
                "mean resending the rest. An agent delivered by the catalogue cannot be edited, because what a " +
                "buyer downloaded is what was reviewed; that refusal names the agent so a caller can tell it " +
                "apart from a missing one.",
            category = "agents",
            parameters = listOf(
                ToolParameter("agent", ToolParameterType.String, true, "Slug or exact name, as listAgents reports it"),
                ToolParameter("name", ToolParameterType.String, false, "New name, if it is changing"),
                ToolParameter("instructions", ToolParameterType.String, false, "New instructions, if they are changing"),
                ToolParameter("description", ToolParameterType.String, false, "New one-line description"),
                ToolParameter("tools", ToolParameterType.String, false, "JSON array of the complete tool set. Omitted leaves the grants untouched; [] revokes every tool."),
            ),
            permissions = emptyList()
        ),
        ToolDefinition(
            name = DELETE_AGENT,
            description = "Delete an agent you made on this phone, by slug or exact name. Catalogue agents cannot " +
                "be deleted here, because withdrawal of a publisher copy is the sync worker's decision and not a " +
                "button. Deleting is permanent, so only do it when the person has said that agent should go.",
            category = "agents",
            parameters = listOf(
                ToolParameter("agent", ToolParameterType.String, true, "Slug or exact name of the agent to delete"),
            ),
            permissions = emptyList()
        ),
        ToolDefinition(
            name = SET_AGENT_ENABLED,
            description = "Switch an agent on or off. An agent that is off still exists, keeps its instructions and " +
                "its grants, and cannot be spawned until it is switched back on. Use it to stop an agent without " +
                "losing it, and check the answer: it reports the state the store now holds rather than the state " +
                "that was asked for.",
            category = "agents",
            parameters = listOf(
                ToolParameter("agent", ToolParameterType.String, true, "Slug or exact name"),
                ToolParameter("enabled", ToolParameterType.Boolean, true, "True to switch it on, false to switch it off"),
            ),
            permissions = emptyList()
        ),
        ToolDefinition(
            name = PUBLISH_AGENT,
            description = "Send an agent you made to the catalogue for review, so other people can install it. " +
                "It publishes under the slug the agent already carries, so the identity on this phone is the " +
                "identity the catalogue knows. A price of zero is free; a price in pounds travels with the " +
                "submission and survives review, because an agent that quietly promotes as free is worse than a " +
                "refusal. This is a public listing and a network call, so use it when the person has asked for " +
                "that agent to be published, never speculatively.",
            category = "agents",
            parameters = listOf(
                ToolParameter("agent", ToolParameterType.String, true, "Slug or exact name of your own agent"),
                ToolParameter("price", ToolParameterType.String, false, "Price in pounds, like 2.50. Empty or 0 is free."),
                ToolParameter("category", ToolParameterType.Enum, false, "What it is for, as the market browses", CATEGORIES),
            ),
            permissions = emptyList()
        ),
        ToolDefinition(
            name = SEARCH_AGENTS,
            description = "Find the agents on this phone that could do a job, by capability rather than by name. " +
                "It searches names, descriptions, instructions and the tools each agent holds, and reports why " +
                "each one matched and how strongly, because a name match and a tool match are different kinds of " +
                "evidence. Use it before choosing who to hand work to; listAgents is for when the name is already " +
                "known and the question is what it may touch.",
            category = "agents",
            parameters = listOf(
                ToolParameter("query", ToolParameterType.String, true, "What the job needs, in words. An empty query lists every agent."),
                ToolParameter("enabledOnly", ToolParameterType.Boolean, false, "True to leave out agents that are switched off. Default false."),
            ),
            permissions = emptyList()
        ),
        ToolDefinition(
            name = LIST_TOOLS,
            description = "List the tools this phone actually holds, with the category and the permission each one " +
                "needs. getMyTools reports only the tools you defined yourself, so it cannot answer what you have; " +
                "this can, and it is the list an agent's grants are chosen from. Filter by category, by a word in " +
                "the name or description, or by the permission a tool needs, and it says whether a filter was " +
                "applied so a short list is never mistaken for a short registry.",
            category = "agents",
            parameters = listOf(
                ToolParameter("category", ToolParameterType.String, false, "Only tools in this category, like shell or media"),
                ToolParameter("query", ToolParameterType.String, false, "Only tools whose name or description contains this word"),
                ToolParameter("permission", ToolParameterType.String, false, "Only tools that need this permission"),
            ),
            permissions = emptyList()
        )
    )

    override fun executorClass(): KClass<out ToolExecutor> = AgentAuthoringToolExecutor::class
    override fun extractorClass(): KClass<out ToolResultExtractor>? = null
}
