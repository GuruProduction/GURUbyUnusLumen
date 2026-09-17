package com.unuslumen.app.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import com.unuslumen.app.database.entity.ConversationEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationDao {

    @Upsert
    suspend fun upsertConversation(conversation: ConversationEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertConversations(conversations: List<ConversationEntity>)

    @Update
    suspend fun updateConversation(conversation: ConversationEntity)

    @Delete
    suspend fun deleteConversation(conversation: ConversationEntity)

    @Query("DELETE FROM conversations WHERE id = :conversationId")
    suspend fun deleteConversationById(conversationId: String)

    @Query("SELECT * FROM conversations ORDER BY updated_date DESC")
    fun getAllConversations(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE id = :conversationId")
    suspend fun getConversation(conversationId: String): ConversationEntity?

    @Query("SELECT * FROM conversations WHERE title LIKE '%' || :query || '%' ORDER BY updated_date DESC")
    fun searchConversations(query: String): Flow<List<ConversationEntity>>

    @Query("""
        SELECT DISTINCT c.* FROM conversations c
        LEFT JOIN messages m ON m.conversation_id = c.id
        WHERE c.title LIKE '%' || :query || '%'
           OR m.content LIKE '%' || :query || '%'
        ORDER BY c.updated_date DESC
    """)
    fun searchConversationsWithMessages(query: String): Flow<List<ConversationEntity>>

    @Query("UPDATE conversations SET title = :title, updated_date = :updatedDate, message_count = :messageCount WHERE id = :conversationId")
    suspend fun updateConversationMeta(conversationId: String, title: String, updatedDate: Long, messageCount: Int)

    @Query("SELECT * FROM conversations WHERE id NOT IN (SELECT conversation_id FROM messages WHERE embedding IS NOT NULL AND embedding != '') AND message_count > 0 ORDER BY updated_date DESC")
    suspend fun getConversationsNeedingEmbeddings(): List<ConversationEntity>
}
