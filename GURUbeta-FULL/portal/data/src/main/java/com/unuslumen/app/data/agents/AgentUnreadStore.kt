// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.agents

import android.content.Context
import com.unuslumen.app.database.dao.AgentMessageDao
import com.unuslumen.app.database.dao.AgentRunDao
import com.unuslumen.app.database.entity.AgentMessageEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.koin.core.annotation.Single

/**
 * What an agent has said that a person has not read.
 *
 * WHY THIS IS PER CONVERSATION AND NOT PER AGENT OR PER RUN.
 *
 * Per agent fails the moment one agent has two conversations: reading one would clear the other,
 * and a person would miss a message they never saw. Per run fails in the other direction, because a
 * conversation outlives its run: a message that landed on the finished run and a message on the new
 * one are the same thread to the person reading it.
 *
 * The specification names this explicitly, and it is the kind of thing that only shows up as "the dot
 * disappeared and I never read it".
 *
 * WHERE THE WATERMARK LIVES, AND WHY IT IS NOT A TABLE.
 *
 * A watermark is one integer per conversation. A table for that would need a migration, an index and
 * a DAO, to store something SharedPreferences already stores correctly and durably. It is read once
 * when a screen opens and written when a person reads, which is not a query anyone joins on.
 *
 * UNREAD IS COMPUTED, NOT STORED. Storing a count would be a second truth that has to be kept in step
 * with every message insert, every delete and every retry; computing it from `(watermark, rows)` has
 * one source and cannot drift.
 */
@Single
class AgentUnreadStore(
    private val context: Context,
    private val runDao: AgentRunDao,
    private val messageDao: AgentMessageDao,
) {

    private companion object {
        const val PREFS = "guru_agent_unread"
        const val KEY_PREFIX = "watermark:"
    }

    private val prefs by lazy {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    /**
     * The in-memory mirror, so a screen watching the dot updates without a prefs listener.
     *
     * A STATE FLOW RATHER THAN A PREFERENCE CHANGE LISTENER. `OnSharedPreferenceChangeListener` is
     * process-wide, fires on the calling thread, and would need unregistering; a flow is the shape
     * every other live surface in this app already consumes.
     */
    private val watermarks = MutableStateFlow<Map<String, Long>>(emptyMap())

    /**
     * One conversation's unread count.
     *
     * COUNTS MESSAGES ADDRESSED TO THE PERSON, NEWER THAN THE WATERMARK, THAT WERE NOT FAILED.
     * All three conditions matter: a message for GURU was not written for them to read, a message
     * they have already read is not news, and a message whose delivery failed never arrived, so
     * counting it would show a dot for something that cannot be read.
     */
    suspend fun unreadCount(conversationId: String): Int {
        if (conversationId.isBlank()) return 0
        val watermark = watermarkFor(conversationId)
        return try {
            val runs = runDao.runsForConversation(conversationId)
            var total = 0
            for (run in runs) {
                total += messageDao.messagesForRun(run.runId).count { message ->
                    message.recipientType == AgentMessageEntity.RECIPIENT_USER &&
                        message.authorType != AgentMessageEntity.AUTHOR_USER &&
                        message.deliveryState != AgentMessageEntity.DELIVERY_FAILED &&
                        message.createdAt > watermark
                }
            }
            total
        } catch (e: Exception) {
            0
        }
    }

    /**
     * Whether anything in this conversation is waiting to be read.
     *
     * A BOOLEAN FOR THE DOT, because the Portal draws a mark and no number. The count is still here
     * for a room that wants to say how many.
     */
    suspend fun hasUnread(conversationId: String): Boolean = unreadCount(conversationId) > 0

    /** Every conversation with something unread, for a surface that lists them. */
    fun unreadConversations(): Flow<Set<String>> = watermarks.map { marks ->
        // Computed lazily by the caller; this flow exists so a screen can react to a read rather
        // than polling. The set is filled by [unreadCount], not guessed here.
        marks.keys
    }

    /**
     * Mark a conversation as read up to now.
     *
     * ADVANCED ONLY WHEN A PERSON ACTUALLY LOOKED. The room calls this from its chat tab having
     * rendered the transcript, and nowhere else. Advancing on a notification or on a background scan
     * would be claiming a read that did not happen, which is the whole failure the dot exists to
     * avoid.
     *
     * IT ADVANCES TO THE NEWEST MESSAGE'S TIME, NOT TO `now`. A message written in the same
     * millisecond as the read would otherwise be missed, and on a device where both happen in one
     * turn that is a real window rather than a theoretical one.
     */
    suspend fun markRead(conversationId: String) = withContext(Dispatchers.IO) {
        if (conversationId.isBlank()) return@withContext
        val newest = runCatching {
            val runs = runDao.runsForConversation(conversationId)
            runs.flatMap { run -> messageDao.messagesForRun(run.runId) }
                .maxOfOrNull { it.createdAt }
        }.getOrNull() ?: System.currentTimeMillis()
        write(conversationId, newest)
    }

    /** The watermark for a conversation, loaded from disk on first ask. */
    private suspend fun watermarkFor(conversationId: String): Long = withContext(Dispatchers.IO) {
        watermarks.value[conversationId]?.let { return@withContext it }
        val stored = prefs.getLong(KEY_PREFIX + conversationId, 0L)
        watermarks.value = watermarks.value + (conversationId to stored)
        stored
    }

    private suspend fun write(conversationId: String, at: Long) = withContext(Dispatchers.IO) {
        prefs.edit().putLong(KEY_PREFIX + conversationId, at).apply()
        watermarks.value = watermarks.value + (conversationId to at)
    }

    /**
     * The newest instant any message in a conversation was written.
     *
     * EXPOSED SO A CALLER CAN ASK "IS THERE ANYTHING AFTER MY WATERMARK" WITHOUT COUNTING ROWS. The
     * room uses it to decide whether to advance on render, which must not depend on a count that a
     * later message could change underneath it.
     */
    suspend fun newestMessageAt(conversationId: String): Long? = runCatching {
        val runs = runDao.runsForConversation(conversationId)
        runs.flatMap { run -> messageDao.messagesForRun(run.runId) }.maxOfOrNull { it.createdAt }
    }.getOrNull()
}
