package com.unuslumen.app.data.tools

import com.unuslumen.app.data.tools.registry.ToolDefinition
import com.unuslumen.app.data.tools.registry.ToolExecutor
import com.unuslumen.app.data.tools.registry.ToolParameter
import com.unuslumen.app.data.tools.registry.ToolParameterType
import com.unuslumen.app.data.tools.registry.ToolResultExtractor
import com.unuslumen.app.data.tools.registry.ToolSetRegistration
import kotlin.reflect.KClass

object MediaToolDefinitions : ToolSetRegistration {
    const val SPOTIFY_STATUS = "spotifyStatus"
    const val SPOTIFY_PLAY = "spotifyPlay"
    const val SPOTIFY_PAUSE = "spotifyPause"
    const val SPOTIFY_NEXT = "spotifyNext"
    const val SPOTIFY_PREVIOUS = "spotifyPrevious"
    const val SPOTIFY_SEARCH = "spotifySearch"
    const val SPOTIFY_DEVICES = "spotifyDevices"
    const val SPOTIFY_SET_DEVICE = "spotifySetDevice"
    const val GIF_SEARCH = "gifSearch"
    const val GIF_DOWNLOAD = "gifDownload"
    const val MEME_SEARCH = "memeSearch"
    const val MEME_CREATE = "memeCreate"
    const val VIDEO_FRAME = "videoFrame"
    const val VIDEO_SHEET = "videoSheet"
    const val CAMERA_CAPTURE = "cameraCapture"
    const val SONG_RECOGNIZE = "songRecognize"

    override val definitions = listOf(
        ToolDefinition(name = SPOTIFY_STATUS, description = "Get current Spotify playback status - what's playing, device, progress. Returns track info, artist, album, and playback state.", category = "media", parameters = emptyList(), permissions = emptyList()),
        ToolDefinition(name = SPOTIFY_PLAY, description = "Resume Spotify playback on the current device.", category = "media", parameters = emptyList(), permissions = emptyList()),
        ToolDefinition(name = SPOTIFY_PAUSE, description = "Pause Spotify playback.", category = "media", parameters = emptyList(), permissions = emptyList()),
        ToolDefinition(name = SPOTIFY_NEXT, description = "Skip to next track in Spotify.", category = "media", parameters = emptyList(), permissions = emptyList()),
        ToolDefinition(name = SPOTIFY_PREVIOUS, description = "Go back to previous track in Spotify.", category = "media", parameters = emptyList(), permissions = emptyList()),
        ToolDefinition(name = SPOTIFY_SEARCH, description = "Search for tracks, artists, or albums on Spotify. Returns search results with track names, artists, and URIs.", category = "media", parameters = listOf(ToolParameter("query", ToolParameterType.String, true, "Search query - song name, artist, album, or any combination"), ToolParameter("limit", ToolParameterType.Integer, false, "Result limit. Default 10.")), permissions = emptyList()),
        ToolDefinition(name = SPOTIFY_DEVICES, description = "List available Spotify Connect devices.", category = "media", parameters = emptyList(), permissions = emptyList()),
        ToolDefinition(name = SPOTIFY_SET_DEVICE, description = "Set the active Spotify playback device by name or ID.", category = "media", parameters = listOf(ToolParameter("device", ToolParameterType.String, true, "Device name or ID to connect to")), permissions = emptyList()),
        ToolDefinition(name = GIF_SEARCH, description = "Search for GIFs on Tenor or Giphy. Returns URLs for preview and full GIF. Use this when your human wants a funny reaction GIF or to express something visually.", category = "media", parameters = listOf(ToolParameter("query", ToolParameterType.String, true, "Search query - what kind of GIF to find"), ToolParameter("limit", ToolParameterType.Integer, false, "Maximum results to return. Default 5."), ToolParameter("provider", ToolParameterType.String, false, "Provider: 'tenor' or 'giphy'. Default 'tenor'.")), permissions = emptyList()),
        ToolDefinition(name = GIF_DOWNLOAD, description = "Download a GIF to the device through Tor. Provide the URL from a previous GIF search.", category = "media", parameters = listOf(ToolParameter("url", ToolParameterType.String, true, "URL of the GIF to download"), ToolParameter("filename", ToolParameterType.String, false, "Filename to save as (without extension). Default 'gif'.")), permissions = emptyList()),
        ToolDefinition(name = MEME_SEARCH, description = "Search for meme templates by name or use case. Returns template names and descriptions.", category = "media", parameters = listOf(ToolParameter("query", ToolParameterType.String, true, "Search query - meme name or use case")), permissions = emptyList()),
        ToolDefinition(name = MEME_CREATE, description = "Create a meme from a template with custom text. Returns the path to the generated meme image.", category = "media", parameters = listOf(ToolParameter("template", ToolParameterType.String, true, "Template name (e.g., 'drake', 'distracted_boyfriend', 'two_buttons')"), ToolParameter("topText", ToolParameterType.String, true, "Top text or first panel text"), ToolParameter("bottomText", ToolParameterType.String, false, "Bottom text or second panel text (optional)")), permissions = emptyList()),
        ToolDefinition(name = VIDEO_FRAME, description = "Extract a single frame from a video file at a specific timestamp. Returns the path to the extracted frame image.", category = "media", parameters = listOf(ToolParameter("videoPath", ToolParameterType.String, true, "Path to the video file"), ToolParameter("timestamp", ToolParameterType.String, true, "Timestamp to extract (e.g., '00:01:30' for 1 minute 30 seconds, or '1.5' for 1.5 seconds)"), ToolParameter("outputName", ToolParameterType.String, false, "Output filename without extension. Default 'frame'.")), permissions = emptyList()),
        ToolDefinition(name = VIDEO_SHEET, description = "Create a contact sheet (grid of frames) from a video. Useful for previewing video content.", category = "media", parameters = listOf(ToolParameter("videoPath", ToolParameterType.String, true, "Path to the video file"), ToolParameter("frames", ToolParameterType.Integer, false, "Number of frames to extract. Default 9."), ToolParameter("columns", ToolParameterType.Integer, false, "Number of columns in the grid. Default 3."), ToolParameter("outputName", ToolParameterType.String, false, "Output filename without extension. Default 'sheet'.")), permissions = emptyList()),
        ToolDefinition(name = CAMERA_CAPTURE, description = "Capture a photo from the device camera. Returns the path to the captured image.", category = "media", parameters = listOf(ToolParameter("camera", ToolParameterType.String, false, "Camera to use: 'back' for rear camera, 'front' for selfie camera. Default 'back'."), ToolParameter("filename", ToolParameterType.String, false, "Filename without extension. Default 'photo'.")), permissions = emptyList()),
        ToolDefinition(name = SONG_RECOGNIZE, description = "Identify a song playing nearby using audio fingerprinting. Returns song title, artist, and album if found.", category = "media", parameters = emptyList(), permissions = emptyList())
    )
    override fun executorClass(): KClass<out ToolExecutor> = MediaToolExecutor::class
    override fun extractorClass(): KClass<out ToolResultExtractor>? = null
}