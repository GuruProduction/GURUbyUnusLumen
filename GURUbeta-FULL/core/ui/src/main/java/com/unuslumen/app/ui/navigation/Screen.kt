package com.unuslumen.app.ui.navigation

import kotlinx.serialization.Serializable

@Serializable
sealed class Screen {
    @Serializable
    data object Main : Screen()

    @Serializable
    data object LobbyScreen : Screen()

    @Serializable
    data object DashboardScreen : Screen()

    @Serializable
    data object SettingsScreen : Screen()

    @Serializable
    data class TaskDetailScreen(
        val taskId: String
    ): Screen()

    @Serializable
    data object NotesScreen : Screen()

    @Serializable
    data class NoteDetailsScreen(
        val noteId: String? = null,
        val folderId: String? = null
    ): Screen()

    @Serializable
    data object NoteSearchScreen : Screen()

    @Serializable
    data object JournalScreen : Screen()

    @Serializable
    data class JournalDetailScreen(
        val entryId: String? = null,
    ): Screen()

    @Serializable
    data object JournalSearchScreen : Screen()

    @Serializable
    data object JournalChartScreen : Screen()

    @Serializable
    data object BookmarksScreen : Screen()

    @Serializable
    data class BookmarkDetailScreen(
        val bookmarkId: String? = null,
    ): Screen()

    @Serializable
    data object BookmarkSearchScreen : Screen()

    @Serializable
    data object CalendarScreen : Screen()
    @Serializable
    data class CalendarEventDetailsScreen(
        val eventId: Long? = null,
        val initialStartMillis: Long? = null
    ) : Screen()
    @Serializable
    data class NoteFolderDetailsScreen(
        val folderId: String
    ): Screen()
    @Serializable
    data object ImportExportScreen : Screen()

    @Serializable
    data object IntegrationsScreen : Screen()

    @Serializable
    data object PortalScreen : Screen()
    @Serializable
    data object ConversationHistoryScreen : Screen()
    @Serializable
    data object MemoryScreen : Screen()

    // Projects (replacing Bookmarks conceptually)
    @Serializable
    data object ProjectsScreen : Screen()
    @Serializable
    data class ProjectDetailScreen(
        val projectId: String
    ) : Screen()

    @Serializable
    data object OtioComingSoon : Screen()

    @Serializable
    data object SkillsScreen : Screen()
}
