package com.unuslumen.app.data.tools

import com.unuslumen.app.data.tools.registry.ToolResultData
import kotlinx.serialization.Serializable

@Serializable data class SpotifyStatusResult(val success: Boolean, val status: String? = null, val error: String? = null) : ToolResultData
@Serializable data class SpotifyResult(val success: Boolean, val action: String, val error: String? = null) : ToolResultData
@Serializable data class SpotifyTrack(val name: String, val artist: String, val uri: String? = null) : ToolResultData
@Serializable data class SpotifySearchResult(val success: Boolean, val tracks: List<SpotifyTrack>, val error: String? = null) : ToolResultData
@Serializable data class SpotifyDevice(val name: String, val id: String) : ToolResultData
@Serializable data class SpotifyDevicesResult(val success: Boolean, val devices: List<SpotifyDevice>, val error: String? = null) : ToolResultData
@Serializable data class GifInfo(val id: String, val url: String, val previewUrl: String, val title: String) : ToolResultData
@Serializable data class GifSearchResult(val success: Boolean, val gifs: List<GifInfo>, val error: String? = null) : ToolResultData
@Serializable data class GifDownloadResult(val success: Boolean, val path: String? = null, val error: String? = null) : ToolResultData
@Serializable data class MemeTemplate(val name: String, val description: String) : ToolResultData
@Serializable data class MemeSearchResult(val success: Boolean, val templates: List<MemeTemplate>, val error: String? = null) : ToolResultData
@Serializable data class MemeCreateResult(val success: Boolean, val path: String? = null, val error: String? = null) : ToolResultData
@Serializable data class VideoFrameResult(val success: Boolean, val path: String? = null, val error: String? = null) : ToolResultData
@Serializable data class CameraResult(val success: Boolean, val path: String? = null, val error: String? = null) : ToolResultData
@Serializable data class SongResult(val success: Boolean, val title: String? = null, val artist: String? = null, val album: String? = null, val error: String? = null) : ToolResultData