package com.unuslumen.app.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.unuslumen.app.database.converters.DBConverters
import com.unuslumen.app.database.dao.AlarmDao
import com.unuslumen.app.database.dao.BookmarkDao
import com.unuslumen.app.database.dao.ConversationDao
import com.unuslumen.app.database.dao.ConversationThreadDao
import com.unuslumen.app.database.dao.JournalDao
import com.unuslumen.app.database.dao.HiveMindStateDao
import com.unuslumen.app.database.dao.MemoryFactDao
import com.unuslumen.app.database.dao.MemoryEdgeDao
import com.unuslumen.app.database.dao.MemoryEventDao
import com.unuslumen.app.database.dao.MemoryCrossReferenceDao
import com.unuslumen.app.database.dao.MessageDao
import com.unuslumen.app.database.dao.NoteDao
import com.unuslumen.app.database.dao.PromptAmendmentDao
import com.unuslumen.app.database.dao.PromptSectionDao
import com.unuslumen.app.database.dao.GuruDefinedToolDao
import com.unuslumen.app.database.dao.GuruAutomationDao
import com.unuslumen.app.database.dao.LuxifyDao
import com.unuslumen.app.database.dao.GuruHookDao
import com.unuslumen.app.database.dao.GuruJobDao
import com.unuslumen.app.database.dao.GuruNoteToSelfDao
import com.unuslumen.app.database.dao.JobExecutionHistoryDao
import com.unuslumen.app.database.dao.GuruThoughtCycleDao
import com.unuslumen.app.database.dao.GuruInsightDao
import com.unuslumen.app.database.dao.TaskDao
import com.unuslumen.app.database.dao.ToolResultDao
import com.unuslumen.app.database.dao.SeenImageDao
import com.unuslumen.app.database.dao.ProjectDao
import com.unuslumen.app.database.dao.ProjectMessageDao
import com.unuslumen.app.database.dao.ProjectDocumentDao
import com.unuslumen.app.database.dao.ProjectFactDao
import com.unuslumen.app.database.entity.AlarmEntity
import com.unuslumen.app.database.entity.BookmarkEntity
import com.unuslumen.app.database.entity.ConversationEntity
import com.unuslumen.app.database.entity.ConversationThreadEntity
import com.unuslumen.app.database.entity.JournalEntryEntity
import com.unuslumen.app.database.entity.HiveMindStateEntity
import com.unuslumen.app.database.entity.MemoryFactEntity
import com.unuslumen.app.database.entity.MemoryEdgeEntity
import com.unuslumen.app.database.entity.MemoryEventEntity
import com.unuslumen.app.database.entity.MemoryCrossReferenceEntity
import com.unuslumen.app.database.entity.MessageEntity
import com.unuslumen.app.database.entity.NoteEntity
import com.unuslumen.app.database.entity.NoteFolderEntity
import com.unuslumen.app.database.entity.PromptAmendmentEntity
import com.unuslumen.app.database.entity.PromptSectionEntity
import com.unuslumen.app.database.entity.GuruDefinedToolEntity
import com.unuslumen.app.database.entity.GuruAutomationEntity
import com.unuslumen.app.database.entity.LuxifyEntity
import com.unuslumen.app.database.entity.GuruHookEntity
import com.unuslumen.app.database.entity.GuruJobEntity
import com.unuslumen.app.database.entity.GuruNoteToSelfEntity
import com.unuslumen.app.database.entity.JobExecutionHistoryEntity
import com.unuslumen.app.database.entity.GuruThoughtCycleEntity
import com.unuslumen.app.database.entity.GuruInsightEntity
import com.unuslumen.app.database.entity.TaskEntity
import com.unuslumen.app.database.entity.ToolResultEntity
import com.unuslumen.app.database.entity.SeenImageEntity
import com.unuslumen.app.database.entity.ProjectEntity
import com.unuslumen.app.database.entity.ProjectMessageEntity
import com.unuslumen.app.database.entity.ProjectDocumentEntity
import com.unuslumen.app.database.entity.ProjectFactEntity

@Database(
    entities = [
        NoteEntity::class,
        TaskEntity::class,
        JournalEntryEntity::class,
        BookmarkEntity::class,
        AlarmEntity::class,
        NoteFolderEntity::class,
        ConversationEntity::class,
        MessageEntity::class,
        MemoryFactEntity::class,
        MemoryEdgeEntity::class,
        MemoryEventEntity::class,
        MemoryCrossReferenceEntity::class,
        ConversationThreadEntity::class,
        HiveMindStateEntity::class,
        PromptSectionEntity::class,
        PromptAmendmentEntity::class,
        GuruDefinedToolEntity::class,
        GuruAutomationEntity::class,
        LuxifyEntity::class,
        GuruHookEntity::class,
        GuruJobEntity::class,
        GuruThoughtCycleEntity::class,
        GuruInsightEntity::class,
        ProjectEntity::class,
        ProjectMessageEntity::class,
        ProjectDocumentEntity::class,
        ProjectFactEntity::class,
        ToolResultEntity::class,
        JobExecutionHistoryEntity::class,
        SeenImageEntity::class,
        GuruNoteToSelfEntity::class
    ],
    version = 23
)
@TypeConverters(DBConverters::class)
abstract class guruDatabase: RoomDatabase() {

    abstract fun noteDao(): NoteDao
    abstract fun taskDao(): TaskDao
    abstract fun journalDao(): JournalDao
    abstract fun bookmarkDao(): BookmarkDao
    abstract fun alarmDao(): AlarmDao
    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao
    abstract fun memoryFactDao(): MemoryFactDao
    abstract fun memoryEdgeDao(): MemoryEdgeDao
    abstract fun memoryEventDao(): MemoryEventDao
    abstract fun memoryCrossReferenceDao(): MemoryCrossReferenceDao
    abstract fun conversationThreadDao(): ConversationThreadDao
    abstract fun hiveMindStateDao(): HiveMindStateDao
    abstract fun promptSectionDao(): PromptSectionDao
    abstract fun promptAmendmentDao(): PromptAmendmentDao
    abstract fun guruDefinedToolDao(): GuruDefinedToolDao
    abstract fun guruAutomationDao(): GuruAutomationDao
    abstract fun luxifyDao(): LuxifyDao
    abstract fun guruHookDao(): GuruHookDao
    abstract fun guruJobDao(): GuruJobDao
    abstract fun guruNoteToSelfDao(): GuruNoteToSelfDao
    abstract fun guruThoughtCycleDao(): GuruThoughtCycleDao
    abstract fun guruInsightDao(): GuruInsightDao
    abstract fun projectDao(): ProjectDao
    abstract fun projectMessageDao(): ProjectMessageDao
    abstract fun projectDocumentDao(): ProjectDocumentDao
    abstract fun projectFactDao(): ProjectFactDao
    abstract fun toolResultDao(): ToolResultDao
    abstract fun jobExecutionHistoryDao(): JobExecutionHistoryDao
    abstract fun seenImageDao(): SeenImageDao

    companion object {
        const val DATABASE_NAME = "guru_db"
    }
}
