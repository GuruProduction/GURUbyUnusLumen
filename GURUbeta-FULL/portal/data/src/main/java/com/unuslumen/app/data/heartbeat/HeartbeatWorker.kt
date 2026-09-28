package com.unuslumen.app.data.heartbeat

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.unuslumen.app.domain.memory.MemoryRepository
import com.unuslumen.app.domain.model.AiMessage
import com.unuslumen.app.domain.repository.AiRepository
import com.unuslumen.app.preferences.PrefsConstants
import com.unuslumen.app.preferences.domain.model.stringPreferencesKey
import com.unuslumen.app.preferences.domain.use_case.GetPreferenceUseCase
import kotlinx.coroutines.flow.first
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import kotlin.uuid.Uuid

/**
 * The heartbeat: a framework message injected into GURU's most recent
 * conversation every interval, carrying the founder-authored prompt bundled at
 * assets/heartbeat/HEARTBEAT_PROMPT.md.
 *
 * NO GATES. By founder direction the beat fires unconditionally: no enabled
 * check, no quiet hours, no send-in-flight guard, no freshness window. Every
 * interval the prompt is loaded, the newest conversation's history is loaded,
 * the beat is appended as a real user message, and the standard send path runs.
 * What the model then does with the beat is the prompt's business alone.
 *
 * The send path (AiRepository.sendMessage) persists every message on every
 * exit path including cancellation and failure, so the beat and GURU's reply
 * land in the thread exactly like a human exchange. The user sees the beat
 * message and the reply next time they open the Portal.
 *
 * Doze note (platform truth, documented): Android may defer periodic WorkManager
 * jobs under deep doze, so a beat scheduled for 03:00 can land later, when the
 * device next wakes. This is expected OS behaviour, not a bug, and not a gate:
 * the worker always fires when Android runs it.
 */
class HeartbeatWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params), KoinComponent {

    private val aiRepository: AiRepository by inject()
    private val memoryRepository: MemoryRepository by inject()
    private val getPreference: GetPreferenceUseCase by inject()

    override suspend fun doWork(): androidx.work.ListenableWorker.Result {
        val appContext = applicationContext

        try {
            // 1. Load the founder's prompt. Failure here is an environmental
            // fault, not a decision: log and report failure for retry.
            val humanName = try {
                getPreference(
                    stringPreferencesKey(PrefsConstants.USER_NAME_KEY),
                    ""
                ).first()
            } catch (e: Exception) { "" }
            val prompt = HeartbeatPrompts.load(appContext, humanName)
            if (prompt == null) {
                Log.e(TAG, "Prompt unavailable — retrying")
                return androidx.work.ListenableWorker.Result.retry()
            }

            // 2. Most recent conversation — same pick as Portal restore
            // (maxByOrNull updatedDate). No conversation means the person has
            // never spoken to GURU; there is nowhere to inject a beat yet.
            val conversation = try {
                memoryRepository.getAllConversations().maxByOrNull { it.updatedDate }
            } catch (e: Exception) {
                Log.e(TAG, "Conversation list failed: ${e.message}")
                return androidx.work.ListenableWorker.Result.retry()
            }
            if (conversation == null) {
                Log.d(TAG, "No conversation exists yet — nothing to wake into")
                return androidx.work.ListenableWorker.Result.success()
            }

            // 3. Load history and map persisted role strings back to AiMessage —
            // the same mapping the Portal uses on restore ("user"/"assistant").
            // Tool results are not re-sent; their effect already informed the
            // assistant turns that followed them.
            val persisted = try {
                memoryRepository.getMessagesByConversation(conversation.id)
            } catch (e: Exception) {
                Log.e(TAG, "History load failed: ${e.message}")
                return androidx.work.ListenableWorker.Result.retry()
            }
            val history = persisted.mapNotNull { msg ->
                when (msg.role) {
                    "user" -> AiMessage.UserMessage(
                        uuid = msg.id,
                        content = msg.content,
                        time = msg.timestamp
                    )
                    "assistant" -> AiMessage.AssistantMessage(
                        content = msg.content,
                        time = msg.timestamp,
                        uuid = msg.id
                    )
                    else -> null
                }
            }
            if (history.isEmpty()) {
                Log.d(TAG, "Conversation has no user/assistant history — nothing to wake into")
                return androidx.work.ListenableWorker.Result.success()
            }

            // 4. The beat arrives as the newest user turn — a real message, no gating.
            val beat = AiMessage.UserMessage(
                uuid = Uuid.random().toString(),
                content = prompt,
                time = System.currentTimeMillis()
            )
            val fullHistory = history + beat

            // 5. Run. Persistence is handled inside the repository on every
            // exit path; the worker adds nothing and cancels nothing.
            Log.d(TAG, "Beat live: conversation=${conversation.id} history=${fullHistory.size} msgs")
            aiRepository.sendMessage(fullHistory, conversation.id).collect { msg ->
                when (msg) {
                    is AiMessage.AssistantMessage ->
                        Log.d(TAG, "GURU: ${msg.content.take(200)}")
                    is AiMessage.ToolCall ->
                        Log.d(TAG, "GURU tool: ${msg.name}${if (msg.isFailed) " (failed)" else ""}")
                    else -> Unit
                }
            }
            Log.d(TAG, "Beat complete: ${conversation.id}")
            return androidx.work.ListenableWorker.Result.success()
        } catch (e: kotlinx.coroutines.CancellationException) {
            Log.w(TAG, "Beat cancelled: ${e.message}")
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Beat failed (attempt $runAttemptCount): ${e.message}")
            return if (runAttemptCount < 3) {
                androidx.work.ListenableWorker.Result.retry()
            } else {
                androidx.work.ListenableWorker.Result.failure()
            }
        }
    }

    companion object {
        private const val TAG = "guru_heartbeat"
    }
}