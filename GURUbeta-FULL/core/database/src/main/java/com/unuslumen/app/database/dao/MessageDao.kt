package com.unuslumen.app.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.SkipQueryVerification
import androidx.room.Transaction
import com.unuslumen.app.database.entity.MessageEntity

@Dao
interface MessageDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: MessageEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessages(messages: List<MessageEntity>)

    @Query("SELECT * FROM messages WHERE conversation_id = :conversationId ORDER BY timestamp ASC")
    suspend fun getMessagesByConversation(conversationId: String): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE id = :messageId")
    suspend fun getMessage(messageId: String): MessageEntity?

    @Query("SELECT * FROM messages WHERE conversation_id = :conversationId AND embedding IS NOT NULL AND embedding != ''")
    suspend fun getMessagesWithEmbeddings(conversationId: String): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE embedding IS NULL OR embedding = ''")
    suspend fun getMessagesWithoutEmbeddings(): List<MessageEntity>

    @Query("UPDATE messages SET embedding = :embedding WHERE id = :messageId")
    suspend fun updateEmbedding(messageId: String, embedding: String)

    @Query("DELETE FROM messages WHERE conversation_id = :conversationId")
    suspend fun deleteMessagesByConversation(conversationId: String)

    @Query("SELECT * FROM messages ORDER BY timestamp DESC")
    suspend fun getAllMessages(): List<MessageEntity>

    @Query("SELECT COUNT(*) FROM messages")
    suspend fun getMessageCount(): Int

    @SkipQueryVerification
    @Query("""
        SELECT m.* FROM messages m
        WHERE m.rowid IN (SELECT rowid FROM messages_fts WHERE messages_fts MATCH :query)
        ORDER BY m.timestamp DESC
    """)
    suspend fun searchMessagesFts(query: String): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE content LIKE '%' || :query || '%' ORDER BY timestamp DESC")
    suspend fun searchMessagesLike(query: String): List<MessageEntity>

    @Transaction
    suspend fun replaceMessagesForConversation(conversationId: String, messages: List<MessageEntity>) {
        deleteMessagesByConversation(conversationId)
        if (messages.isNotEmpty()) {
            insertMessages(messages)
        }
    }
}
