// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
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
import com.unuslumen.app.database.dao.GuruAgentDao
import com.unuslumen.app.database.dao.GuruDefinedToolDao
import com.unuslumen.app.database.dao.GuruAutomationDao
import com.unuslumen.app.database.dao.GuruModuleDao
import com.unuslumen.app.database.dao.GuruModuleRevisionDao
import com.unuslumen.app.database.dao.GuruAutomationRunDao
import com.unuslumen.app.database.dao.GuruTileOrderDao
import com.unuslumen.app.database.dao.LuxifyDao
import com.unuslumen.app.database.dao.GuruHookDao
import com.unuslumen.app.database.dao.GuruJobDao
import com.unuslumen.app.database.dao.GuruNoteToSelfDao
import com.unuslumen.app.database.dao.UserProfileDao
import com.unuslumen.app.database.dao.JobExecutionHistoryDao
import com.unuslumen.app.database.dao.GuruThoughtCycleDao
import com.unuslumen.app.database.dao.GuruInsightDao
import com.unuslumen.app.database.dao.TaskDao
import com.unuslumen.app.database.dao.ToolObservationDao
import com.unuslumen.app.database.dao.ToolResultDao
import com.unuslumen.app.database.dao.SeenImageDao
import com.unuslumen.app.database.dao.ProjectDao
import com.unuslumen.app.database.dao.MediaLibraryDao
import com.unuslumen.app.database.dao.MediaZoomLogDao
import com.unuslumen.app.database.dao.MemoryInjectionDao
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
import com.unuslumen.app.database.entity.GuruAgentEntity
import com.unuslumen.app.database.entity.GuruAutomationEntity
import com.unuslumen.app.database.entity.GuruModuleEntity
import com.unuslumen.app.database.entity.GuruModuleRevisionEntity
import com.unuslumen.app.database.entity.GuruAutomationRunEntity
import com.unuslumen.app.database.entity.GuruTileOrderEntity
import com.unuslumen.app.database.entity.LuxifyEntity
import com.unuslumen.app.database.entity.GuruHookEntity
import com.unuslumen.app.database.entity.GuruJobEntity
import com.unuslumen.app.database.entity.GuruNoteToSelfEntity
import com.unuslumen.app.database.entity.UserProfileEntity
import com.unuslumen.app.database.entity.JobExecutionHistoryEntity
import com.unuslumen.app.database.entity.GuruThoughtCycleEntity
import com.unuslumen.app.database.entity.GuruInsightEntity
import com.unuslumen.app.database.entity.TaskEntity
import com.unuslumen.app.database.entity.ToolObservationEntity
import com.unuslumen.app.database.entity.ToolResultEntity
import com.unuslumen.app.database.entity.SeenImageEntity
import com.unuslumen.app.database.entity.ProjectEntity
import com.unuslumen.app.database.entity.ProjectMessageEntity
import com.unuslumen.app.database.entity.ProjectDocumentEntity
import com.unuslumen.app.database.entity.ProjectFactEntity
import com.unuslumen.app.database.entity.MediaItemEntity
import com.unuslumen.app.database.entity.MediaZoomLogEntity
import com.unuslumen.app.database.entity.MemoryInjectionEntity
import com.unuslumen.app.database.entity.FabricDeviceEntity
import com.unuslumen.app.database.entity.FabricSceneEntity
import com.unuslumen.app.database.entity.HeartbeatBookingEntity
import com.unuslumen.app.database.entity.HeartbeatEntryEntity
import com.unuslumen.app.database.entity.HeartbeatPromptEntity
import com.unuslumen.app.database.entity.NetworkDeviceEntity
import com.unuslumen.app.database.entity.NetworkScanEntity
import com.unuslumen.app.database.entity.NetworkSightingEntity
import com.unuslumen.app.database.dao.AgentApprovalDao
import com.unuslumen.app.database.dao.AgentArtefactDao
import com.unuslumen.app.database.dao.AgentContextReferenceDao
import com.unuslumen.app.database.dao.AgentDefinitionVersionDao
import com.unuslumen.app.database.dao.AgentMessageDao
import com.unuslumen.app.database.dao.AgentRunCheckpointDao
import com.unuslumen.app.database.dao.AgentRunDao
import com.unuslumen.app.database.dao.AgentRunEventDao
import com.unuslumen.app.database.dao.AgentScheduleDao
import com.unuslumen.app.database.dao.FabricDao
import com.unuslumen.app.database.dao.HeartbeatBookingDao
import com.unuslumen.app.database.dao.HeartbeatPromptDao
import com.unuslumen.app.database.dao.NetworkVisionDao
import com.unuslumen.app.database.entity.AgentApprovalEntity
import com.unuslumen.app.database.entity.AgentArtefactEntity
import com.unuslumen.app.database.entity.AgentContextReferenceEntity
import com.unuslumen.app.database.entity.AgentDefinitionVersionEntity
import com.unuslumen.app.database.entity.AgentMessageEntity
import com.unuslumen.app.database.entity.AgentRunCheckpointEntity
import com.unuslumen.app.database.entity.AgentRunEntity
import com.unuslumen.app.database.entity.AgentRunEventEntity
import com.unuslumen.app.database.entity.AgentScheduleEntity

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
        GuruAgentEntity::class,
        GuruAutomationEntity::class,
        GuruModuleEntity::class,
        GuruModuleRevisionEntity::class,
        GuruAutomationRunEntity::class,
        GuruTileOrderEntity::class,
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
        ToolObservationEntity::class,
        JobExecutionHistoryEntity::class,
        SeenImageEntity::class,
        GuruNoteToSelfEntity::class,
        UserProfileEntity::class,
        MediaItemEntity::class,
        MediaZoomLogEntity::class,
        MemoryInjectionEntity::class,
        FabricDeviceEntity::class,
        FabricSceneEntity::class,
        // The heartbeat prompt, so it is the owner's to edit rather than a compiled asset.
        HeartbeatPromptEntity::class,
        // The heartbeat diary: the booking rules, and every dated half hour they produce.
        // Two tables because a rule and an occurrence answer different questions, and a
        // diary has to answer both: what is planned, and what actually happened.
        HeartbeatBookingEntity::class,
        HeartbeatEntryEntity::class,
        // The network map: what GURU has seen, when it saw it, and why it looked. Three tables
        // because the present, the history and the run are three different questions.
        NetworkDeviceEntity::class,
        NetworkSightingEntity::class,
        NetworkScanEntity::class,
        // The agent execution foundation: a run that outlives the process, an append-only
        // record of what actually happened, and recovery points that can bring a session
        // back. Three tables because an execution is three questions at once: where it is
        // now, what it did, and what it needs to carry on.
        AgentRunEntity::class,
        AgentRunEventEntity::class,
        AgentRunCheckpointEntity::class,
        // The definition history. An edit to an agent stops destroying the instructions that
        // were there before it, so a past run's configuration is reproducible rather than
        // merely numbered.
        AgentDefinitionVersionEntity::class,
        // The conversation: who said what to whom, in order, with how far it got.
        AgentMessageEntity::class,
        // What a run had to ask before it was allowed to act, and what it was told.
        AgentApprovalEntity::class,
        // What a run produced, and what it was given. The two ends of "why does this exist".
        AgentArtefactEntity::class,
        AgentContextReferenceEntity::class,
        // A standing instruction: which agent, what job, and when it should next run on its own.
        // A table rather than an alarm, because the heartbeat fires the assistant and a schedule
        // starts an agent, and one alarm cannot mean both.
        AgentScheduleEntity::class,
    ],
    version = 41
)
@TypeConverters(DBConverters::class)
abstract class guruDatabase: RoomDatabase() {

    abstract fun mediaLibraryDao(): MediaLibraryDao
    abstract fun mediaZoomLogDao(): MediaZoomLogDao
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
    abstract fun guruAgentDao(): GuruAgentDao
    abstract fun guruAutomationDao(): GuruAutomationDao
    abstract fun guruModuleDao(): GuruModuleDao
    abstract fun guruModuleRevisionDao(): GuruModuleRevisionDao
    abstract fun guruAutomationRunDao(): GuruAutomationRunDao
    abstract fun guruTileOrderDao(): GuruTileOrderDao
    abstract fun luxifyDao(): LuxifyDao
    abstract fun guruHookDao(): GuruHookDao
    abstract fun guruJobDao(): GuruJobDao
    abstract fun guruNoteToSelfDao(): GuruNoteToSelfDao
    abstract fun userProfileDao(): UserProfileDao
    abstract fun guruThoughtCycleDao(): GuruThoughtCycleDao
    abstract fun guruInsightDao(): GuruInsightDao
    abstract fun projectDao(): ProjectDao
    abstract fun projectMessageDao(): ProjectMessageDao
    abstract fun projectDocumentDao(): ProjectDocumentDao
    abstract fun projectFactDao(): ProjectFactDao
    abstract fun toolResultDao(): ToolResultDao
    abstract fun toolObservationDao(): ToolObservationDao
    abstract fun jobExecutionHistoryDao(): JobExecutionHistoryDao
    abstract fun seenImageDao(): SeenImageDao
    abstract fun memoryInjectionDao(): MemoryInjectionDao
    abstract fun fabricDao(): FabricDao
    abstract fun heartbeatPromptDao(): HeartbeatPromptDao
    abstract fun heartbeatBookingDao(): HeartbeatBookingDao
    abstract fun networkVisionDao(): NetworkVisionDao
    abstract fun agentRunDao(): AgentRunDao
    abstract fun agentRunEventDao(): AgentRunEventDao
    abstract fun agentRunCheckpointDao(): AgentRunCheckpointDao
    abstract fun agentDefinitionVersionDao(): AgentDefinitionVersionDao
    abstract fun agentMessageDao(): AgentMessageDao
    abstract fun agentApprovalDao(): AgentApprovalDao
    abstract fun agentArtefactDao(): AgentArtefactDao
    abstract fun agentContextReferenceDao(): AgentContextReferenceDao
    abstract fun agentScheduleDao(): AgentScheduleDao

    companion object {
        const val DATABASE_NAME = "guru_db"

        /**
         * The schema version this build writes, as a literal.
         *
         * IT USED TO BE READ OFF THE @Database ANNOTATION BY REFLECTION, AND THAT ALWAYS
         * RETURNED ZERO. The annotation is CLASS-retained, which means it is present in the
         * compiled bytecode and invisible at runtime, so `getAnnotation(...)` answered null
         * on every build and the `?: 0` produced 0. Two things then failed silently:
         *
         *  - `AgentConfigurationValidator` compares an agent's declared `minimumRuntimeSchemaVersion`
         *    against this value, so every agent whose manifest asked for schema 1 was refused with
         *    "this agent needs runtime schema 1 and this build is 0, so it was made for a newer
         *    app". Nothing could run at all, and the refusal blamed the agent rather than the
         *    reading.
         *  - the snapshot restore gate compares a snapshot's version against this one to refuse a
         *    newer schema, so with 0 every snapshot looked newer than the build and restore was
         *    blocked in the opposite direction from the one the comment describes.
         *
         * WHY A LITERAL RATHER THAN A DIFFERENT REFLECTION TRICK. Kotlin cannot read its own
         * annotation at compile time either, and any second mechanism would be another thing to
         * get wrong. So this is the number, and `guruDatabaseSchemaVersionMatchesTheDatabase`
         * fails the build if it drifts from the @Database annotation beside it. A test is the
         * guard that reflection was pretending to be.
         */
        const val SCHEMA_VERSION: Int = 41
    }
}
