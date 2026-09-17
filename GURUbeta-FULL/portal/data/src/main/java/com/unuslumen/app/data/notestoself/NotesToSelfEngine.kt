package com.unuslumen.app.data.notestoself

import com.unuslumen.app.database.dao.GuruNoteToSelfDao
import com.unuslumen.app.database.entity.GuruNoteToSelfEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Matches the user's message against Guru-authored notes-to-self keywords and
 * returns the content of every note that fired, updating fire stats.
 *
 * Matching rule, deliberately simple and predictable: a keyword/phrase fires
 * when it appears in the message text case-insensitively as a whole word or
 * whole phrase. Word boundaries mean "sam" fires on "Sam from Manchester"
 * but not on "same". Multi-word phrases match across normal spacing.
 */
class NotesToSelfEngine(private val dao: GuruNoteToSelfDao) {

    suspend fun matchingContent(userMessageText: String): List<String> = withContext(Dispatchers.IO) {
        val enabled = try {
            dao.getEnabled()
        } catch (e: Exception) {
            emptyList()
        }
        if (enabled.isEmpty() || userMessageText.isBlank()) return@withContext emptyList()

        val message = userMessageText.lowercase()
        val fired = mutableListOf<GuruNoteToSelfEntity>()
        for (note in enabled) {
            val keywords = parseKeywords(note.triggerKeywords)
            if (keywords.isEmpty()) continue
            if (keywords.any { keywordFires(it, message) }) {
                fired.add(note)
            }
        }
        if (fired.isEmpty()) return@withContext emptyList()

        val now = System.currentTimeMillis()
        for (note in fired) {
            try {
                dao.recordFire(note.id, now)
            } catch (_: Exception) { }
        }
        fired.map { it.content }
    }

    /** Comma-separated keyword list, trimmed, lowercased, blanks dropped. */
    private fun parseKeywords(raw: String): List<String> =
        raw.split(',')
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }

    /**
     * Whole-word / whole-phrase boundary match so a short keyword like "sam"
     * does not fire inside "same" or "salary". Builds a boundary-aware regex
     * per keyword; on a pathological keyword that escapes regex, falls back to
     * a contains() match.
     */
    private fun keywordFires(keyword: String, message: String): Boolean {
        val escaped = Regex.escape(keyword)
        val pattern = try {
            Regex("(^|[^a-z0-9])$escaped([^a-z0-9]|$)")
        } catch (e: Exception) {
            return message.contains(keyword)
        }
        return pattern.containsMatchIn(message)
    }
}