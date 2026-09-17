package com.unuslumen.app.data.tools

import com.unuslumen.app.data.tools.registry.ToolDefinition
import com.unuslumen.app.data.tools.registry.ToolExecutor
import com.unuslumen.app.data.tools.registry.ToolParameter
import com.unuslumen.app.data.tools.registry.ToolParameterType
import com.unuslumen.app.data.tools.registry.ToolResultExtractor
import com.unuslumen.app.data.tools.registry.ToolSetRegistration
import kotlin.reflect.KClass

object JobToolDefinitions : ToolSetRegistration {
    const val CREATE_JOB = "createJob"
    const val LIST_JOBS = "listJobs"
    const val GET_JOB = "getJob"
    const val DELETE_JOB = "deleteJob"
    const val ENABLE_JOB = "enableJob"
    const val DISABLE_JOB = "disableJob"
    const val RUN_JOB = "runJob"

    override val definitions = listOf(
        ToolDefinition(
            name = CREATE_JOB,
            description = "I create scheduled jobs. A job is a task I run automatically on a schedule. I can make it run once, at intervals, daily, weekly, monthly, or on a cron expression. When I create a job I store it in my database and register it with Android's WorkManager so it fires on time without anyone touching it.",
            category = "job",
            parameters = listOf(
                ToolParameter("name", ToolParameterType.String, true, "I need a unique name for this job. I use it to identify the job everywhere — when I look it up, when I run it, when I delete it. The name must start with a lowercase letter and contain only lowercase letters, numbers, and underscores. Example: 'check_price_alerts'"),
                ToolParameter("displayName", ToolParameterType.String, true, "I need a human-readable name. Something the user reads and understands what this job does. Example: 'Check Price Alerts'"),
                ToolParameter("description", ToolParameterType.String, true, "I need to know what this job does and why it exists. I read this to understand the purpose of the job when I'm deciding how to manage it."),
                ToolParameter("scheduleType", ToolParameterType.Enum, true, "I need to know what kind of schedule controls when this job runs. I support one-time execution, interval-based polling, daily, weekly, monthly, and cron expressions.", enumValues = listOf("ONE_TIME", "INTERVAL", "DAILY", "WEEKLY", "MONTHLY", "CRON")),
                ToolParameter("scheduleConfig", ToolParameterType.String, true, "I schedule jobs based on their type. The schedule config tells me when and how often to fire. I read the scheduleType first, then I read this config to figure out the timing. For ONE_TIME and INTERVAL I need {\"intervalMs\": 60000, \"initialDelayMs\": 0} — intervalMs is how many milliseconds between fires (INTERVAL minimum 60000, ONE_TIME uses intervalMs as a future epoch timestamp in milliseconds). For DAILY I need {\"hour\": 7, \"minute\": 30} — hour 0-23, minute 0-59, I fire at that time every day. For WEEKLY I need {\"daysOfWeek\": [1,3,5], \"hour\": 9, \"minute\": 0} — daysOfWeek 1-7 where 1=Monday and 7=Sunday, I fire on those days at that time. For MONTHLY I need {\"dayOfMonth\": 15, \"hour\": 9, \"minute\": 0} — dayOfMonth 1-31, I fire on that day of the month at that time. For CRON I need {\"expression\": \"0 7 * * *\"} — a standard 5-field cron expression, I parse it and fire on the next matching time."),
                ToolParameter("action", ToolParameterType.String, true, "I execute actions when a job fires. The action tells me what to do and how to do it. I need a JSON object with three parts: what kind of thing I'm running, which specific thing it is, and what parameters I pass to it. The type is either 'tool' or 'skill' — those are the only two things I know how to execute. The target is the name of the specific tool or skill, like 'webSearch' or 'rssFetch'. The params are the inputs I hand to that tool when it fires, a map of parameter names to string values. When the job fires I read the action, find the tool or skill by name, pass it the params, and let it run. Example: {\"type\": \"tool\", \"target\": \"webSearch\", \"params\": {\"query\": \"Bitcoin price\"}}"),
                ToolParameter("input", ToolParameterType.String, false, "I can accept optional input data that gets substituted into action params at execution time. I look for dollar-sign-prefixed strings in the action params and replace them with matching values from this input. If an action param has {\"query\": \"\${'$'}searchTerm\"} and I receive input {\"searchTerm\": \"Bitcoin\"}, I swap \$searchTerm for Bitcoin when the job fires. This lets me run the same job with different inputs without recreating it."),
                ToolParameter("destinationTable", ToolParameterType.String, false, "I can write execution results to a database table in addition to storing them in the job's lastResult field. If I'm given a destination table name, I insert every result into that table so the data builds up over time. The table must already exist in guru_db. I don't create tables, I write to them. If this is null I just store results in lastResult and move on."),
                ToolParameter("retentionWindowHours", ToolParameterType.Integer, false, "I keep results uncompressed in the destination table for this many hours. After that window passes, the compression worker compresses them in place. The row stays, the content gets compressed, nothing gets deleted. I only use this if compressionEnabled is true. If this is null I keep results uncompressed forever."),
                ToolParameter("compressionEnabled", ToolParameterType.Boolean, false, "I can compress old results in place on the destination table. When this is true, the compression worker finds rows older than retentionWindowHours and replaces their content with a compressed version. The row stays exactly where it is. Nothing moves. Nothing gets deleted. I just make the content smaller so the table doesn't grow forever. Default is false because I don't compress anything unless I'm told to."),
                ToolParameter("compressionCycleMs", ToolParameterType.Long, false, "I check this job's data for compression on a cycle. This tells me how many milliseconds to wait between compression checks for this specific job. If a job produces data fast and the table grows quickly, I can set a short cycle to compress more often. If data grows slowly, I can set a long cycle. I only use this if compressionEnabled is true."),
                ToolParameter("failureThreshold", ToolParameterType.Integer, false, "I watch for consecutive failures. If a job keeps failing, something is wrong and retrying forever just wastes resources. This tells me how many consecutive failures I tolerate before I auto-disable the job. When I hit the threshold I disable the job, cancel its WorkManager schedule, and fire a notification. If this is null I never auto-disable, I just keep trying."),
                ToolParameter("retryBackoffMs", ToolParameterType.Long, false, "When a job fails and I retry it, I wait this many milliseconds before trying again. This stops me from hammering a broken endpoint every second. If this is null I use WorkManager's default backoff. I respect whatever backoff I'm given because retrying too fast makes things worse, not better."),
                ToolParameter("autoDisableMessage", ToolParameterType.String, false, "When a job auto-disables from consecutive failures, I fire a notification. This is the message I show in that notification. If this is set, I use it exactly. If this is null, I generate the notification message myself at runtime based on the job's name, its failure history, and what it was trying to do. I don't hardcode a generic message because every job fails for different reasons and the user needs to know the specific context."),
                ToolParameter("historyRetentionHours", ToolParameterType.Integer, false, "I keep execution history for every job run — timestamp, success or failure, execution time, full result. History rows stay uncompressed for this many hours. After that, the compression worker compresses them in place. The row stays, the content gets compressed, nothing gets deleted. If this is null I keep history uncompressed forever. I never delete history. I compress it.")
            ),
            permissions = emptyList()
        ),
        ToolDefinition(name = LIST_JOBS, description = "I list every scheduled job I have. I show the job ID, name, display name, schedule type, whether it's enabled, how many times it's run, and when it's next due. I also show a summary of the whole job system — total jobs, how many are enabled, how many have failed. I use this to see the full picture of what I'm managing.", category = "job", parameters = emptyList(), permissions = emptyList()),
        ToolDefinition(
            name = GET_JOB,
            description = "I look up a single job by name or ID and show everything I know about it. Every field, every config option, the full action definition, the schedule, the last result, the last error, the run count, the failure count. I use this when I need the complete picture of one specific job.",
            category = "job",
            parameters = listOf(ToolParameter("name", ToolParameterType.String, true, "I need the exact job name I used when I created it, or the UUID I returned from createJob. I try the name first, then the UUID. Either one works.")),
            permissions = emptyList()
        ),
        ToolDefinition(
            name = DELETE_JOB,
            description = "I delete a job completely. I remove it from my database and cancel its WorkManager schedule so it never fires again. When a job is gone, its execution history goes with it — the history only makes sense in the context of the job that produced it.",
            category = "job",
            parameters = listOf(ToolParameter("name", ToolParameterType.String, true, "I need the exact job name I used when I created it, or the UUID I returned from createJob. I try the name first, then the UUID. Either one works.")),
            permissions = emptyList()
        ),
        ToolDefinition(
            name = ENABLE_JOB,
            description = "I enable a disabled job. I set its enabled flag back to true and register a fresh WorkManager request so it starts firing on schedule again. The job picks up where it left off — next run time is calculated from the current time and the schedule config.",
            category = "job",
            parameters = listOf(ToolParameter("name", ToolParameterType.String, true, "I need the exact job name I used when I created it, or the UUID I returned from createJob. I try the name first, then the UUID. Either one works.")),
            permissions = emptyList()
        ),
        ToolDefinition(
            name = DISABLE_JOB,
            description = "I disable a job. I set its enabled flag to false and cancel its WorkManager schedule so it stops firing. The job stays in my database with all its data intact. I can re-enable it any time and it picks up where it left off.",
            category = "job",
            parameters = listOf(ToolParameter("name", ToolParameterType.String, true, "I need the exact job name I used when I created it, or the UUID I returned from createJob. I try the name first, then the UUID. Either one works.")),
            permissions = emptyList()
        ),
        ToolDefinition(
            name = RUN_JOB,
            description = "I run a job immediately, right now, regardless of its schedule. I find the job, execute its action, record the result, and return. This doesn't affect the scheduled runs — the next scheduled fire still happens on time. I use this when I want to test a job or when the user needs results right now and can't wait for the next scheduled run.",
            category = "job",
            parameters = listOf(ToolParameter("name", ToolParameterType.String, true, "I need the exact job name I used when I created it, or the UUID I returned from createJob. I try the name first, then the UUID. Either one works.")),
            permissions = emptyList()
        )
    )
    override fun executorClass(): KClass<out ToolExecutor> = JobToolExecutor::class
    override fun extractorClass(): KClass<out ToolResultExtractor>? = null
}