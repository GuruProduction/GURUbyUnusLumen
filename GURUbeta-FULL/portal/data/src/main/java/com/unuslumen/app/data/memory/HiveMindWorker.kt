package com.unuslumen.app.data.memory

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.unuslumen.app.domain.memory.MemoryFact
import com.unuslumen.app.domain.memory.MemoryRepository
import com.unuslumen.app.domain.repository.AiRepository
import com.unuslumen.app.domain.model.PortalResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.util.concurrent.TimeUnit

@Serializable
data class ExtractedFactsResponse(
    val facts: List<ExtractedFact>
)

@Serializable
data class ExtractedFact(
    val category: String,
    val fact: String,
    val confidence: Float
)

class HiveMindWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params), KoinComponent {

    private val memoryRepository: MemoryRepository by inject()
    private val aiRepository: AiRepository by inject()
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun doWork(): androidx.work.ListenableWorker.Result {
        return withContext(Dispatchers.IO) {
            try {
                val unprocessedConversations = memoryRepository.getUnprocessedConversations()

                if (unprocessedConversations.isEmpty()) {
                    return@withContext androidx.work.ListenableWorker.Result.success()
                }

                var factsExtracted = 0
                var threadsDiscovered = 0

                for (conversation in unprocessedConversations) {
                    val messages = memoryRepository.getMessagesByConversation(conversation.id)
                    if (messages.isEmpty()) continue

                    val conversationContent = buildString {
                        for (msg in messages) {
                            val roleLabel = when (msg.role) {
                                "user" -> "User"
                                "assistant" -> "Assistant"
                                "tool" -> "Tool"
                                else -> msg.role
                            }
                            append("$roleLabel: ${msg.content.take(2000)}\n")
                        }
                    }

                    val extractedFacts = extractFactsWithLLM(conversationContent, conversation.id)
                    if (extractedFacts.isNotEmpty()) {
                        memoryRepository.persistFacts(extractedFacts)
                        factsExtracted += extractedFacts.size
                    }
                }

                val lastConversationId = unprocessedConversations.lastOrNull()?.id ?: ""
                memoryRepository.markHiveProcessing(
                    conversationId = lastConversationId,
                    factsExtracted = factsExtracted,
                    threadsDiscovered = threadsDiscovered
                )

                androidx.work.ListenableWorker.Result.success()
            } catch (e: Exception) {
                if (runAttemptCount < 3) {
                    androidx.work.ListenableWorker.Result.retry()
                } else {
                    androidx.work.ListenableWorker.Result.failure()
                }
            }
        }
    }

    private suspend fun extractFactsWithLLM(content: String, conversationId: String): List<MemoryFact> {
        val timestamp = System.currentTimeMillis()

        val prompt = HiveMindPrompts.FACT_EXTRACTION_PROMPT.replace("{{conversation_content}}", content)

        val result = aiRepository.sendPrompt(
            "${HiveMindPrompts.FACT_EXTRACTION_SYSTEM_PROMPT}\n\n$prompt"
        )

        return when (result) {
            is PortalResult.Success -> {
                try {
                    val responseText = result.data.trim()
                    val jsonStart = responseText.indexOf('{')
                    val jsonEnd = responseText.lastIndexOf('}') + 1
                    if (jsonStart >= 0 && jsonEnd > jsonStart) {
                        val jsonContent = responseText.substring(jsonStart, jsonEnd)
                        val extracted = json.decodeFromString<ExtractedFactsResponse>(jsonContent)
                        extracted.facts
                            .filter { it.confidence >= 0.7f }
                            .map { fact ->
                                MemoryFact(
                                    id = "fact_${timestamp}_${fact.hashCode()}",
                                    category = fact.category,
                                    fact = fact.fact,
                                    confidence = fact.confidence,
                                    sourceConversationIds = listOf(conversationId),
                                    extractedDate = timestamp
                                )
                            }
                    } else {
                        emptyList()
                    }
                } catch (e: Exception) {
                    emptyList()
                }
            }
            else -> emptyList()
        }
    }

    companion object {
        private const val WORK_NAME = "hive_mind_periodic"
        private const val ONE_TIME_NAME = "hive_mind_one_time"

        fun scheduleOneTime(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = OneTimeWorkRequestBuilder<HiveMindWorker>()
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context)
                .enqueueUniqueWork(ONE_TIME_NAME, ExistingWorkPolicy.REPLACE, request)
        }

        fun schedulePeriodic(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(true)
                .setRequiresDeviceIdle(true)
                .build()

            val request = PeriodicWorkRequestBuilder<HiveMindWorker>(6, TimeUnit.HOURS)
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            WorkManager.getInstance(context).cancelUniqueWork(ONE_TIME_NAME)
        }
    }
}
