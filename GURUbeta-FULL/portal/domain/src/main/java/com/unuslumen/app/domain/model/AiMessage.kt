package com.unuslumen.app.domain.model

import kotlinx.serialization.Serializable

sealed interface AiMessage {

    val uuid: String

    data class UserMessage(
        override val uuid: String,
        val content: String,
        val time: Long,
        val attachments: List<AiMessageAttachment> = emptyList(),
        val attachmentsText: String = ""
    ) : AiMessage

    data class AssistantMessage(
        val content: String,
        val time: Long,
        override val uuid: String,
        val thinkingTokens: String = "",
    ) : AiMessage

    /**
     * In-progress assistant message during token streaming.
     * Emitted for each TextDelta from the streaming executor.
     * The canvas updates the corresponding DOM element with partialContent.
     * When generation completes, this is replaced by a full AssistantMessage.
     */
    data class StreamingAssistant(
        override val uuid: String,
        val partialContent: String,
        val partialThinking: String = "",
        val time: Long,
        val isComplete: Boolean = false
    ) : AiMessage

    /**
     * In-progress tool call during streaming.
     * Emitted when ToolCallDelta arrives, showing the LLM deciding which tool to call.
     * Replaced by a full ToolCall when ToolCallComplete arrives.
     */
    data class StreamingToolCall(
        override val uuid: String,
        val toolName: String,
        val partialContent: String,
        val time: Long
    ) : AiMessage

    data class ToolCall(
        override val uuid: String,
        val id: String?,
        val name: String,
        val rawContent: String,
        val resultRawContent: String,
        val time: Long,
        val isFailed: Boolean = false,
        val resultObject: ToolCallResultObject? = null,
        val thoughtSignature: String? = null
    ): AiMessage

    data class PortalMessage(
        val html: String,
        val css: String = "",
        val js: String = "",
        val height: Int? = null,
        val interactive: Boolean = false,
        val time: Long,
        override val uuid: String
    ) : AiMessage
}

sealed interface ToolCallResultObject {
    data class Notes(val notes: List<Note>): ToolCallResultObject
    data class Tasks(val tasks: List<Task>): ToolCallResultObject
    data class CalendarEvents(val events: List<CalendarEvent>): ToolCallResultObject
    data class JournalEntries(val entries: List<JournalEntry>): ToolCallResultObject
    data class Bookmarks(val bookmarks: List<Bookmark>): ToolCallResultObject
    data class Alarms(val alarms: List<AlarmInfo>): ToolCallResultObject
    data class MemoryFacts(val facts: List<String>): ToolCallResultObject
    data class Plans(val plans: List<PlanInfo>): ToolCallResultObject
    data class WebResults(val results: List<WebSearchItem>): ToolCallResultObject
    data class Settings(val settings: Map<String, String>): ToolCallResultObject
    data class FileResults(val files: List<String>): ToolCallResultObject
    data class Sound(val soundInfo: SoundInfo): ToolCallResultObject
    data class Media(val mediaInfo: MediaInfo): ToolCallResultObject
    data class SmartHome(val homeInfo: SmartHomeInfo): ToolCallResultObject
    data class Weather(val weatherInfo: WeatherInfoData): ToolCallResultObject
    data class Places(val places: List<PlaceInfoData>): ToolCallResultObject
    data class GitHub(val githubInfo: GitHubInfoData): ToolCallResultObject
    data class Trello(val trelloInfo: TrelloInfoData): ToolCallResultObject
    data class Notion(val notionInfo: NotionInfoData): ToolCallResultObject
    data class Voice(val voiceInfo: VoiceInfoData): ToolCallResultObject
    data class Communication(val commInfo: CommunicationInfo): ToolCallResultObject
    data class System(val systemInfo: SystemInfo): ToolCallResultObject
    data class Email(val emailInfo: EmailInfo): ToolCallResultObject
    data class Camera(val cameraInfo: CameraInfo): ToolCallResultObject

    data class Portal(
        val html: String,
        val css: String = "",
        val js: String = "",
        val height: Int? = null,
        val interactive: Boolean = false
    ) : ToolCallResultObject

    data class ClosePortal(
        val portalId: String
    ) : ToolCallResultObject

    data class SkillLoaded(
        val skillName: String,
        val description: String,
        val toolsAllowed: List<String>,
        val success: Boolean,
        val error: String? = null
    ) : ToolCallResultObject

    data class ToolResults(val results: List<ToolResultSummary>) : ToolCallResultObject
}

@Serializable
data class ToolResultSummary(
    val toolName: String,
    val parameters: String,
    val result: String,
    val timestamp: Long,
    val ageMinutes: Long,
    val isStale: Boolean
)

@Serializable
data class AlarmInfo(val id: Int, val time: Long)

@Serializable
data class PlanInfo(val id: String, val title: String, val stepCount: Int, val completedSteps: Int)

@Serializable
data class WebSearchItem(val title: String, val url: String, val snippet: String)

@Serializable
data class SoundInfo(
    val type: String,
    val volumes: List<VolumeInfo> = emptyList(),
    val ringerMode: String? = null,
    val isMusicActive: Boolean? = null,
    val hasVibrator: Boolean? = null,
    val error: String? = null
)

@Serializable
data class VolumeInfo(
    val stream: String,
    val currentVolume: Int,
    val maxVolume: Int,
    val percentage: Int,
    val isMuted: Boolean
)

// Media result types
@Serializable
data class MediaInfo(
    val type: String,
    val title: String? = null,
    val artist: String? = null,
    val status: String? = null,
    val path: String? = null,
    val error: String? = null
)

// Smart Home result types
@Serializable
data class SmartHomeInfo(
    val type: String,
    val device: String? = null,
    val state: String? = null,
    val devices: List<DeviceInfoData> = emptyList(),
    val error: String? = null
)

@Serializable
data class DeviceInfoData(
    val id: String,
    val name: String,
    val type: String? = null,
    val state: String? = null
)

// Weather result types
@Serializable
data class WeatherInfoData(
    val location: String,
    val temperature: Double? = null,
    val condition: String? = null,
    val humidity: Int? = null,
    val wind: Double? = null,
    val forecast: List<ForecastDayData> = emptyList(),
    val alerts: List<WeatherAlertData> = emptyList(),
    val error: String? = null
)

@Serializable
data class ForecastDayData(
    val date: String,
    val maxTemp: Double? = null,
    val minTemp: Double? = null,
    val condition: String
)

@Serializable
data class WeatherAlertData(
    val headline: String,
    val severity: String
)

// Places result types
@Serializable
data class PlaceInfoData(
    val id: String,
    val name: String,
    val address: String,
    val rating: Double? = null,
    val isOpen: Boolean? = null
)

// GitHub result types
@Serializable
data class GitHubInfoData(
    val type: String,
    val number: Int? = null,
    val title: String? = null,
    val state: String? = null,
    val author: String? = null,
    val url: String? = null,
    val items: List<GitHubItemData> = emptyList(),
    val error: String? = null
)

@Serializable
data class GitHubItemData(
    val number: Int,
    val title: String,
    val state: String,
    val author: String? = null,
    val url: String? = null
)

// Trello result types
@Serializable
data class TrelloInfoData(
    val type: String,
    val id: String? = null,
    val name: String? = null,
    val items: List<TrelloItemData> = emptyList(),
    val error: String? = null
)

@Serializable
data class TrelloItemData(
    val id: String,
    val name: String,
    val type: String
)

// Notion result types
@Serializable
data class NotionInfoData(
    val type: String,
    val id: String? = null,
    val title: String? = null,
    val content: String? = null,
    val items: List<NotionItemData> = emptyList(),
    val error: String? = null
)

@Serializable
data class NotionItemData(
    val id: String,
    val title: String
)

// Voice result types
@Serializable
data class VoiceInfoData(
    val type: String,
    val text: String? = null,
    val language: String? = null,
    val duration: Double? = null,
    val error: String? = null
)

// Communication result types
@Serializable
data class CommunicationInfo(
    val type: String,
    val platform: String? = null,
    val action: String? = null,
    val recipient: String? = null,
    val messageId: String? = null,
    val status: String? = null,
    val error: String? = null
)

// System result types
@Serializable
data class SystemInfo(
    val type: String,
    val battery: BatteryInfo? = null,
    val storage: StorageInfo? = null,
    val memory: MemoryInfo? = null,
    val security: SecurityInfo? = null,
    val device: DeviceInfo? = null,
    val error: String? = null
)

@Serializable
data class BatteryInfo(
    val level: Int,
    val status: String,
    val temperature: Int
)

@Serializable
data class StorageInfo(
    val total: Long,
    val used: Long,
    val free: Long,
    val percentUsed: Int
)

@Serializable
data class MemoryInfo(
    val total: Long,
    val used: Long,
    val available: Long,
    val percentUsed: Int
)

@Serializable
data class SecurityInfo(
    val isSecure: Boolean,
    val issues: List<String>
)

@Serializable
data class DeviceInfo(
    val manufacturer: String,
    val model: String,
    val androidVersion: String,
    val sdkVersion: Int
)

// Email result types
@Serializable
data class EmailInfo(
    val type: String,
    val emails: List<EmailSummary> = emptyList(),
    val email: EmailDetail? = null,
    val action: String? = null,
    val error: String? = null
)

@Serializable
data class EmailSummary(
    val id: String,
    val from: String,
    val subject: String,
    val preview: String,
    val date: String,
    val isRead: Boolean
)

@Serializable
data class EmailDetail(
    val id: String,
    val from: String,
    val to: String,
    val subject: String,
    val body: String,
    val date: String
)

// Camera result types
@Serializable
data class CameraInfo(
    val type: String,
    val cameras: List<CameraDevice> = emptyList(),
    val imagePath: String? = null,
    val videoPath: String? = null,
    val error: String? = null
)

@Serializable
data class CameraDevice(
    val id: String,
    val name: String,
    val url: String,
    val status: String
)

sealed interface AiMessageAttachment {
    data class Note(val note: com.unuslumen.app.domain.model.Note) : AiMessageAttachment
    data class Task(val task: com.unuslumen.app.domain.model.Task) : AiMessageAttachment
    data object CalenderEvents : AiMessageAttachment
    data class File(
        val originalUri: String,
        val fileName: String,
        val mimeType: String,
        val cachedPath: String,
        val sizeBytes: Long,
        val thumbnailPath: String? = null
    ) : AiMessageAttachment
}
