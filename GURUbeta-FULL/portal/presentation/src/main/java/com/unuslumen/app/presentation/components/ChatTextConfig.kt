package com.unuslumen.app.presentation.components

/**
 * Configuration for chat text rendering in the PortalCanvas WebView.
 *
 * Each context (guru and user) has independent font, colour, and font scale settings.
 * When colour is null, the rotating palette is used. When font is null, the default
 * handwriting font is used (CaviarDreams for guru, MadeTommySoft for user).
 * When fontScale is null, 1.0 is used.
 *
 * This object is derived from GuruTheme preferences and passed to ChatToHtml and PortalCanvas.
 */
data class ChatTextConfig(
    val guruFont: String? = null,
    val guruColour: String? = null,
    val guruFontScale: Float? = null,
    val userFont: String? = null,
    val userColour: String? = null,
    val userFontScale: Float? = null
) {
    companion object {
        val DEFAULT = ChatTextConfig()
    }
}