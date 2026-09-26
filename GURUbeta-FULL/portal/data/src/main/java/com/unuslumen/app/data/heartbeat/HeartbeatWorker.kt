package com.unuslumen.app.data.heartbeat

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.unuslumen.app.domain.memory.MemoryRepository
import com.unuslumen.app.domain.model.AiMessage
import com.unuslumen.app.domain.repository.AiRepository
import com.unuslumen.app.preferences.PrefsConstants
import com.unuslumen.app.preferences.domain.model.booleanPreferencesKey
import com.unuslumen.app.preferences.domain.model.intPreferencesKey
import com.unuslumen.app.preferences.domain.model.stringPreferencesKey
import com.unuslumen.app.preferences.domain.use_case.GetPreferenceUseCase
import kotlinx.coroutines.flow.first
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import kotlin.uuid.Uuid

/**
 * The heartbeat: a periodic framework message injected into GURU's most recent
 * conversation, carrying the founder-authored prompt bundled at
 * assets/heartbeat/HEARTBEAT_PROMPT.md.
 *
 * Every skip path exits with Result.success BEFORE any model call, so a quiet
 * beat costs zero tokens. A genuine beat reuses the exact same send path as a
 * human message (AiRepository.sendMessage): it streams, persists on every exit
 * path (including cancellation), and renders in the Portal the next time the
 * person opens the app — conversation restore is existing behaviour.
 *
 * Doze note (platform truth, documented): Android may defer periodic WorkManager
 * jobs under deep doze, so a beat scheduled for 03:00 can land later, when the
 * device next wakes. This is expected OS behaviour, not a bug.
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

        // 1. Enabled?
        val enabled = try {
            getPreference(
                booleanPreferencesKey(PrefsConstants.HEARTBEAT_ENABLED_KEY),
                true
            ).first()
        } catch (e: Exception) {
            Log.w(TAG, "Pref read failed, skipping beat: ${e.message}")
            return androidx.work.ListenableWorker.Result.success()
        }
        if (!enabled) {
            Log.d(TAG, "Beat skipped: heartbeat disabled")
            return androidx.work.ListenableWorker.Result.success()
        }

        // 2. Quiet hours (device local clock, wraps midnight).
        val nowHour = java.time.LocalTime.now().hour
        val quietStart = try {
            getPreference(intPreferencesKey(PrefsConstants.HEARTBEAT_QUIET_START_HOUR_KEY), 23).first()
        } catch (e: Exception) { 23 }
        val quietEnd = try {
            getPreference(intPreferencesKey(PrefsConstants.HEARTBEAT_QUIET_END_HOUR_KEY), 8).first()
        } catch (e: Exception) { 8 }
        if (isQuietHour(nowHour, quietStart, quietEnd)) {
            Log.d(TAG, "Beat skipped: quiet hours ($quietStart..$quietEnd, now $nowHour)")
            return androidx.work.ListenableWorker.Result.success()
        }

        // 3. Stand down if any send is already running (human chatting or another beat).
        if (HeartbeatGate.isSendActive() || !HeartbeatGate.tryAcquire()) {
            Log.d(TAG, "Beat skipped: send in flight (sends=${HeartbeatGate.sendsInFlight.get()})")
            return androidx.work.ListenableWorker.Result.success()
        }

        try {
            // 4. Prompt must exist and substitute cleanly.
            val humanName = try {
                getPreference(
                    stringPreferencesKey(PrefsConstants.USER_NAME_KEY),
                    ""
                ).first()
            } catch (e: Exception) { "" }
            val prompt = HeartbeatPrompts.load(appContext, humanName)
            if (prompt == null) {
                Log.d(TAG, "Beat skipped: prompt unavailable")
                return androidx.work.ListenableWorker.Result.success()
            }

            // 5. Most recent conversation — same pick as Portal restore
            // (maxByOrNull updatedDate). No conversation => person has never
            // spoken to GURU; nothing to be alive about yet.
            val conversation = try {
                memoryRepository.getAllConversations().maxByOrNull { it.updatedDate }
            } catch (e: Exception) {
                Log.w(TAG, "Conversation list failed, skipping beat: ${e.message}")
                return androidx.work.ListenableWorker.Result.success()
            }
            if (conversation == null) {
                Log.d(TAG, "Beat skipped: no conversation exists yet")
                return androidx.work.ListenableWorker.Result.success()
            }

            // 6. Freshness guard: GURU was alive moments ago.
            val persisted = try {
                memoryRepository.getMessagesByConversation(conversation.id)
            } catch (e: Exception) {
                Log.w(TAG, "History load failed, skipping beat: ${e.message}")
                return androidx.work.ListenableWorker.Result.success()
            }
            val newestMessage = persisted.lastOrNull()
            if (newestMessage != null &&
                System.currentTimeMillis() - newestMessage.timestamp < FRESHNESS_MS) {
                Log.d(TAG, "Beat skipped: conversation fresh (< ${FRESHNESS_MS / 60000}min old)")
                return androidx.work.ListenableWorker.Result.success()
            }

            // Map persisted role strings back to AiMessage — same mapping the
            // Portal uses on restore ("user"/"assistant"/"tool"). Tool calls are
            // not restored into the beat's context; they already informed the
            // assistant turns that followed them.
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
                Log.d(TAG, "Beat skipped: conversation has no user/assistant history")
                return androidx.work.ListenableWorker.Result.success()
            }

            // The beat arrives as the newest user turn — a real message like any other.
            val beat = AiMessage.UserMessage(
                uuid = Uuid.random().toString(),
                content = prompt,
                time = System.currentTimeMillis()
            )
            val fullHistory = history + beat

            // 7. Run. Persistence is handled inside the repository on every
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
            Log.w(TAG, "Beat failed (attempt $runAttemptCount): ${e.message}")
            return if (runAttemptCount < 3) {
                androidx.work.ListenableWorker.Result.retry()
            } else {
                androidx.work.ListenableWorker.Result.failure()
            }
        } finally {
            HeartbeatGate.release()
        }
    }

    /**
     * Is `now` inside the quiet window [start, end)? Handles the midnight
     * wraparound: 23..8 means 23:00 up to (excluding) 08:00. start == end
     * means no quiet hours configured.
     */
    private fun isQuietHour(now: Int, start: Int, end: Int): Boolean {
        if (start == end) return false
        return if (start < end) {
            now in start until end
        } else {
            now >= start || now < end
        }
    }

    companion object {
        private const val TAG = "guru_heartbeat"

        /** A conversation touched within this window is too fresh to beat into. */
        private const val FRESHNESS_MS = 5 * 60 * 1000L
    }
}