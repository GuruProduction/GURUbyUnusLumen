package com.unuslumen.app.presentation.components

import com.unuslumen.app.domain.model.AiMessage
import org.json.JSONArray
import org.json.JSONObject

/**
 * Maps raw tool function names to human-readable labels.
 * Every tool the AI can call gets a friendly name here.
 */
val toolNameMap: Map<String, String> = mapOf(
    // Notes
    "searchNotes" to "Searching notes",
    "createNote" to "Creating note",
    "createMultipleNotes" to "Creating notes",
    "createFolder" to "Creating folder",
    "updateNote" to "Updating note",
    "updateNoteFolder" to "Updating folder",
    "deleteNote" to "Deleting note",

    // Tasks
    "searchTasks" to "Searching tasks",
    "createTask" to "Creating task",
    "createMultipleTasks" to "Creating tasks",
    "updateTask" to "Updating task",
    "updateTaskCompleted" to "Updating task status",
    "deleteTask" to "Deleting task",

    // Calendar
    "createEvent" to "Creating event",
    "createEvents" to "Creating events",
    "updateEvent" to "Updating event",
    "deleteEvent" to "Deleting event",

    // Journal
    "createJournalEntry" to "Saving journal entry",
    "updateJournalEntry" to "Updating journal entry",
    "deleteJournalEntry" to "Deleting journal entry",

    // Bookmarks
    "createBookmark" to "Saving bookmark",
    "updateBookmark" to "Updating bookmark",
    "deleteBookmark" to "Deleting bookmark",

    // Web
    "webSearch" to "Searching the web",
    "webFetch" to "Fetching webpage",
    "webSummarize" to "Summarising content",
    "urlExpand" to "Expanding URL",
    "urlShorten" to "Shortening URL",

    // Memory
    "addMemoryFact" to "Saving to memory",
    "updateMemoryFact" to "Updating memory",
    "deleteMemoryFact" to "Removing from memory",

    // Planning
    "createPlan" to "Creating plan",
    "updatePlanStep" to "Updating plan step",
    "deletePlan" to "Deleting plan",

    // Projects
    "createProject" to "Creating project",
    "updateProject" to "Updating project",
    "deleteProject" to "Deleting project",
    "addProjectDocument" to "Adding document",
    "updateProjectDocument" to "Updating document",
    "deleteProjectDocument" to "Removing document",
    "addProjectFact" to "Saving project fact",
    "deleteProjectFact" to "Removing project fact",
    "addProjectMessage" to "Saving to project",

    // Alarms
    "createAlarm" to "Setting alarm",
    "deleteAlarm" to "Removing alarm",
    "setReminder" to "Setting reminder",

    // Sound / Media
    "setVolume" to "Changing volume",
    "adjustVolume" to "Adjusting volume",
    "setRingerMode" to "Changing ringer mode",
    "speakText" to "Speaking",
    "stopSpeaking" to "Stopping speech",
    "ttsSpeak" to "Speaking (TTS)",
    "ttsStop" to "Stopping TTS",
    "ttsVoices" to "Getting voices",
    "transcribeSpeech" to "Transcribing speech",
    "transcribeAudio" to "Transcribing audio",
    "stopSound" to "Stopping sound",
    "vibrate" to "Vibrating",
    "songRecognize" to "Recognising song",
    "audioInfo" to "Getting audio info",
    "audioConvert" to "Converting audio",
    "audioTrim" to "Trimming audio",

    // Spotify
    "spotifySearch" to "Searching Spotify",
    "spotifyPlay" to "Playing Spotify",
    "spotifyPause" to "Pausing Spotify",
    "spotifyNext" to "Skipping track",
    "spotifyPrevious" to "Previous track",
    "spotifyStatus" to "Checking Spotify",
    "spotifyDevices" to "Listing Spotify devices",
    "spotifySetDevice" to "Switching Spotify device",

    // Media
    "videoFrame" to "Capturing video frame",
    "videoSheet" to "Showing video sheet",

    // Camera
    "cameraList" to "Listing cameras",
    "cameraSnapshot" to "Taking snapshot",
    "cameraRecord" to "Recording",
    "cameraCapture" to "Capturing",
    "cameraMotionDetect" to "Checking motion",

    // Weather
    "weatherCurrent" to "Checking weather",
    "weatherForecast" to "Getting forecast",
    "weatherAlert" to "Checking weather alerts",

    // Places
    "searchPlaces" to "Searching places",

    // Smart Home
    "hueLights" to "Controlling lights",
    "hueControl" to "Controlling Hue",
    "sonosDiscover" to "Discovering Sonos",
    "sonosPlayPause" to "Controlling Sonos",
    "sonosStatus" to "Checking Sonos",
    "sonosVolume" to "Sonos volume",
    "sonosGroup" to "Grouping Sonos",
    "bluetoothList" to "Listing Bluetooth",
    "bluetoothConnect" to "Connecting Bluetooth",
    "bluetoothDisconnect" to "Disconnecting Bluetooth",

    // Communication
    "whatsappSearch" to "Searching WhatsApp",
    "whatsappSend" to "Sending WhatsApp",
    "slackSend" to "Sending Slack",
    "slackRead" to "Reading Slack",
    "slackPin" to "Pinning Slack message",
    "discordSend" to "Sending Discord",
    "xPost" to "Posting on X",
    "xReply" to "Replying on X",
    "xSearch" to "Searching X",
    "xDm" to "Sending DM on X",
    "voiceCallStart" to "Starting call",
    "voiceCallStatus" to "Checking call status",

    // Email
    "listEmails" to "Checking inbox",
    "sendEmail" to "Sending email",
    "readEmail" to "Reading email",
    "deleteEmail" to "Deleting email",

    // System
    "systemHealth" to "Checking system",
    "deviceInfo" to "Getting device info",
    "sessionLogsExport" to "Exporting logs",
    "sessionLogsSearch" to "Searching logs",

    // GitHub
    "githubPRList" to "Listing pull requests",
    "githubIssueList" to "Listing issues",
    "githubRepo" to "Getting repo info",

    // Trello
    "trelloBoards" to "Listing boards",
    "trelloLists" to "Listing lists",
    "trelloCards" to "Listing cards",
    "trelloCreateCard" to "Creating Trello card",
    "trelloMoveCard" to "Moving Trello card",

    // Notion
    "notionSearch" to "Searching Notion",
    "notionPage" to "Getting Notion page",

    // File system
    "readFile" to "Reading file",
    "writeFile" to "Writing file",
    "deleteFile" to "Deleting file",
    "copyFile" to "Copying file",
    "zipDirectory" to "Zipping directory",
    "createArchive" to "Creating archive",

    // Screen / Input
    "captureScreen" to "Taking screenshot",
    "tapScreen" to "Tapping screen",
    "swipe" to "Swiping",
    "typeText" to "Typing text",
    "clickText" to "Clicking text",
    "collectAllText" to "Collecting screen text",

    // Shell
    "shellExec" to "Running command",

    // SSH
    "sshConnect" to "Connecting SSH",
    "sshDisconnect" to "Disconnecting SSH",
    "sshExec" to "Running SSH command",

    // Reverse engineering
    "decompileApk" to "Decompiling APK",
    "signApk" to "Signing APK",

    // Portal
    "openPortal" to "Opening portal",
    "updatePortal" to "Updating portal",
    "closePortal" to "Closing portal",

    // Tool management
    "defineTool" to "Creating tool",
    "updateTool" to "Updating tool",
    "approveTool" to "Approving tool",
    "approveItem" to "Approving",

    // Hooks
    "createHook" to "Creating hook",
    "deleteHook" to "Deleting hook",
    "triggerHook" to "Triggering hook",

    // Jobs
    "createJob" to "Creating job",
    "deleteJob" to "Deleting job",

    // Skills
    "createSkill" to "Creating skill",
    "deleteSkill" to "Deleting skill",

    // Thoughts
    "createThoughtCycle" to "Creating thought cycle",
    "acknowledgeInsight" to "Acknowledging insight",

    // Prompts
    "proposeAmendment" to "Proposing change",
    "approveAmendment" to "Approving change",

    // Encryption
    "encryptAes" to "Encrypting",
    "decryptAes" to "Decrypting",
    "decryptFile" to "Decrypting file",

    // HTTP
    "httpGet" to "Making GET request",
    "httpPost" to "Making POST request",

    // Clipboard
    "setClipboard" to "Copying to clipboard",
    "clearClipboard" to "Clearing clipboard",

    // Notifications
    "sendNotification" to "Sending notification",

    // Apps
    "launchApp" to "Launching app",
    "uninstallApp" to "Uninstalling app",
    "clearAppData" to "Clearing app data",

    // Widgets
    "updateWidget" to "Updating widget",

    // Contacts
    "searchContacts" to "Searching contacts",

    // Theme
    "setThemeFont" to "Changing font",
    "setThemeCornerRadius" to "Changing corners",
    "setThemeColor" to "Changing theme colour",
    "applyThemePreset" to "Applying theme",
    "applyLayoutPreset" to "Applying layout",

    // Intent
    "sendIntent" to "Sending intent",
    "sendBroadcast" to "Sending broadcast",

    // Settings
    "setPreference" to "Changing setting",
    "getPreference" to "Getting setting",

    // Shortcuts
    "createShortcut" to "Creating shortcut",

    // SQL
    "sqlGetSchema" to "Getting database schema",
    "sqlExecute" to "Running SQL",
    "sqlQuery" to "Querying database",
    "sqlListDatabases" to "Listing databases",

    // ADB
    "checkAdbStatus" to "Checking ADB",
    "autoPairAdb" to "Pairing ADB",

    // Util
    "base64Encode" to "Encoding Base64",
    "base64Decode" to "Decoding Base64",
    "setScreenCapturePermission" to "Setting screen capture",
    "buildSshCommand" to "Building SSH command",

    // Canvas
    "canvasRender" to "Rendering canvas",
    "canvasSnapshot" to "Taking canvas snapshot",

    // Blog
    "blogWatch" to "Watching blog",

    // Ask user
    "askUser" to "Asking you",

    // Skills
    "useSkill" to "Loading skill",
    "searchSkills" to "Searching skills",

    // Notes to self
    "createNoteToSelf" to "Writing a note to self",
    "listNotesToSelf" to "Reviewing notes to self",
    "updateNoteToSelf" to "Updating a note to self",
    "deleteNoteToSelf" to "Deleting a note to self",
    "enableNoteToSelf" to "Enabling a note to self",
    "disableNoteToSelf" to "Disabling a note to self",

    // WebView Browser
    "webBrowserLoad" to "Loading webpage",
    "webBrowserContent" to "Extracting content",
    "webBrowserScreenshot" to "Taking screenshot",
    "webBrowserClick" to "Clicking element",
    "webBrowserInput" to "Typing text",
    "webBrowserScroll" to "Scrolling page",
    "webBrowserConsole" to "Reading console",
    "webBrowserNetwork" to "Monitoring network",
)

/**
 * Get the human-readable label for a tool name, falling back to the raw name.
 */
fun toolDisplayName(rawName: String): String {
    return toolNameMap[rawName] ?: rawName.replace(Regex("([A-Z])"), " \$1").trim()
        .replaceFirstChar { it.uppercase() }
}

/**
 * Format the raw JSON argument content into labelled, human-readable fields.
 * Returns a list of LabelValue pairs for display.
 */
data class LabelValue(val label: String, val value: String, val isRaw: Boolean = false)

fun formatToolArgs(rawContent: String): List<LabelValue> {
    val cleaned = rawContent.trim()
    if (cleaned.isEmpty()) return listOf(LabelValue("No arguments", "", isRaw = true))

    return try {
        when {
            cleaned.startsWith("{") -> {
                val obj = JSONObject(cleaned)
                val result = mutableListOf<LabelValue>()
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val value = obj.get(key)
                    val label = formatArgKey(key)
                    val displayValue = formatArgValue(value)
                    result.add(LabelValue(label, displayValue))
                }
                result.ifEmpty { listOf(LabelValue(cleaned, "", isRaw = true)) }
            }
            cleaned.startsWith("[") -> {
                val arr = JSONArray(cleaned)
                val items = (0 until arr.length()).joinToString(", ") { i ->
                    formatArgValue(arr.get(i))
                }
                listOf(LabelValue("Items", items))
            }
            else -> listOf(LabelValue(cleaned, "", isRaw = true))
        }
    } catch (e: Exception) {
        listOf(LabelValue(cleaned, "", isRaw = true))
    }
}

/**
 * Format the raw result content into a human-readable summary.
 * Returns a short summary string suitable for the preview at Level 2.
 */
fun formatResultPreview(resultRawContent: String, maxLength: Int = 120): String {
    val cleaned = resultRawContent.trim()
    if (cleaned.isEmpty()) return "Done"

    return try {
        when {
            cleaned.startsWith("{") -> {
                val obj = JSONObject(cleaned)
                // Only show error if the key exists AND the value is a real non-null string.
                // Android's optString converts JSON null to "null", which is wrong here.
                if (obj.has("error") && obj.opt("error") != null && obj.opt("error") is String) {
                    val error = obj.optString("error")
                    if (error.isNotBlank()) return "Error: ${error.take(maxLength - 7)}"
                }
                // Skip the error key when building the data preview
                val keys = obj.keys()
                val previewKeys = mutableListOf<String>()
                while (keys.hasNext() && previewKeys.size < 3) {
                    val key = keys.next()
                    if (key == "error") continue
                    val value = obj.get(key)
                    val shortVal = when (value) {
                        is JSONObject -> "{…}"
                        is JSONArray -> "[${value.length()} items]"
                        is String -> {
                            val displayVal = if (looksLikeHtml(value)) stripHtml(value) else value
                            if (displayVal.length > 30) "\"${displayVal.take(27)}…\"" else "\"$displayVal\""
                        }
                        else -> value.toString()
                    }
                    previewKeys.add("${formatArgKey(key)}: $shortVal")
                }
                if (previewKeys.isEmpty()) "Done" else previewKeys.joinToString(" · ")
            }
            cleaned.startsWith("[") -> {
                val arr = JSONArray(cleaned)
                val count = arr.length()
                when {
                    count == 0 -> "Empty list"
                    count <= 3 -> {
                        val items = (0 until count).joinToString(" · ") { i ->
                            val item = arr.get(i)
                            when (item) {
                                is JSONObject -> item.optString("title", item.optString("name", "Item ${i + 1}"))
                                is String -> item.take(40)
                                else -> item.toString().take(40)
                            }
                        }
                        items
                    }
                    else -> {
                        val first = arr.get(0)
                        val firstLabel = when (first) {
                            is JSONObject -> first.optString("title", first.optString("name", "Item 1"))
                            is String -> first.take(30)
                            else -> first.toString().take(30)
                        }
                        "$firstLabel + ${count - 1} more"
                    }
                }
            }
            else -> cleaned.take(maxLength) + if (cleaned.length > maxLength) "…" else ""
        }
    } catch (e: Exception) {
        cleaned.take(maxLength) + if (cleaned.length > maxLength) "…" else ""
    }
}

/**
 * Pretty-print JSON content for the raw view.
 */
fun prettyJson(raw: String): String {
    return try {
        when {
            raw.trim().startsWith("{") -> JSONObject(raw).toString(2)
            raw.trim().startsWith("[") -> JSONArray(raw).toString(2)
            else -> raw
        }
    } catch (e: Exception) {
        raw
    }
}

private fun formatArgKey(key: String): String {
    return key.replace(Regex("([A-Z])"), " \$1").trim()
        .replaceFirstChar { it.uppercase() }
        .replace("_", " ")
}

private fun formatArgValue(value: Any?): String {
    return when (value) {
        null -> "—"
        is String -> value
        is Boolean -> if (value) "Yes" else "No"
        is Number -> value.toString()
        is JSONObject -> "{…}"
        is JSONArray -> {
            val items = (0 until value.length()).map { i ->
                val item = value.get(i)
                when (item) {
                    is String -> item
                    is JSONObject -> item.optString("title", item.optString("name", "?"))
                    else -> item.toString()
                }
            }
            items.joinToString(", ")
        }
        else -> value.toString()
    }
}

// ─── Tool category assignment ──
// Each tool name maps to a category string. Categories correspond to PNG files in
// assets/tools_icons/{Category}.png. The grouping card loads the matching icon per
// tool call. Unknown tools fall back to "Misc".

val toolCategoryMap: Map<String, String> = mapOf(
    // Automation
    "createAutomation" to "Automation",
    "runAutomation" to "Automation",
    "listAutomations" to "Automation",
    "getAutomation" to "Automation",
    "deleteAutomation" to "Automation",
    "enableAutomation" to "Automation",
    "disableAutomation" to "Automation",

    // Clipboard
    "getClipboard" to "Clipboard",
    "setClipboard" to "Clipboard",
    "clearClipboard" to "Clipboard",

    // Network
    "mdnsDiscover" to "Network",
    "mdnsListDevices" to "Network",
    "mdnsStopDiscovery" to "Network",
    "ssdpDiscover" to "Network",
    "ssdpListDevices" to "Network",
    "ssdpStopDiscovery" to "Network",
    "arpScan" to "Network",
    "wifiScan" to "Network",
    "bluetoothScan" to "Network",
    "tcpConnect" to "Network",
    "networkScan" to "Network",
    "networkPing" to "Network",

    // Skills
    "useSkill" to "Skills",
    "searchSkills" to "Skills",
    "createSkill" to "Skills",
    "deleteSkill" to "Skills",

    // Notes to self
    "createNoteToSelf" to "Tools",
    "listNotesToSelf" to "Tools",
    "updateNoteToSelf" to "Tools",
    "deleteNoteToSelf" to "Tools",
    "enableNoteToSelf" to "Tools",
    "disableNoteToSelf" to "Tools",

    // Shell
    "executeShellCommand" to "Shell",
    "shellExec" to "Shell",
    "busyboxExec" to "Shell",
    "getBusyboxHelp" to "Shell",
    "executePythonScript" to "Shell",

    // Termux
    "termuxInit" to "Termux",
    "termuxExec" to "Termux",
    "termuxInstall" to "Termux",
    "termuxStatus" to "Termux",
    "termuxHasCommand" to "Termux",
    "termuxDiagnose" to "Termux",

    // Files
    "readFile" to "Files",
    "writeFile" to "Files",
    "listDirectory" to "Files",
    "searchFiles" to "Files",
    "searchInFiles" to "Files",
    "editFile" to "Files",
    "fsFindFiles" to "Files",
    "fsCopyFile" to "Files",
    "fsMoveFile" to "Files",
    "fsDeleteFile" to "Files",
    "fsGetFileInfo" to "Files",
    "createArchive" to "Files",
    "extractArchive" to "Files",
    "listArchive" to "Files",
    "processFile" to "Files",
    "copyFile" to "Files",
    "deleteFile" to "Files",

    // ADB
    "pairAdbDevice" to "ADB",
    "checkAdbStatus" to "ADB",
    "autoPairAdb" to "ADB",

    // System
    "checkFileSystemAccess" to "System",
    "requestAllFilesAccess" to "System",
    "getDeviceInfo" to "System",
    "deviceInfo" to "System",
    "healthCheck" to "System",
    "systemHealth" to "System",
    "sessionLogsSearch" to "System",
    "sessionLogsExport" to "System",
    "sendIntent" to "System",
    "sendBroadcast" to "System",
    "queryIntentActivities" to "System",
    "listProcesses" to "System",
    "killProcess" to "System",
    "setScreenCapturePermission" to "System",

    // Apps
    "listInstalledApps" to "Apps",
    "launchApp" to "Apps",
    "uninstallApp" to "Apps",
    "installApk" to "Apps",
    "forceStopApp" to "Apps",
    "clearAppData" to "Apps",
    "openApp" to "Apps",

    // Screen (UI automation)
    "tapScreen" to "Screen",
    "clickText" to "Screen",
    "swipe" to "Screen",
    "scroll" to "Screen",
    "typeText" to "Screen",
    "pressButton" to "Screen",
    "getScreenState" to "Screen",
    "findElements" to "Screen",
    "captureScreen" to "Screen",
    "takeScreenshot" to "Screen",
    "recordScreen" to "Screen",
    "requestScreenCapturePermission" to "Screen",

    // Keyboard
    "keyboardTypeText" to "Keyboard",
    "keyboardPressKey" to "Keyboard",
    "keyboardPressKeyCombo" to "Keyboard",
    "keyboardHideKeyboard" to "Keyboard",

    // Approvals
    "getPendingApprovals" to "Approvals",
    "getApprovalDetails" to "Approvals",
    "approveItem" to "Approvals",
    "rejectItem" to "Approvals",
    "approveTool" to "Approvals",

    // SSH
    "sshConnect" to "SSH",
    "sshExec" to "SSH",
    "sshDisconnect" to "SSH",

    // Bookmarks
    "createBookmark" to "Bookmarks",
    "searchBookmarks" to "Bookmarks",
    "updateBookmark" to "Bookmarks",
    "deleteBookmark" to "Bookmarks",
    "getAllBookmarks" to "Bookmarks",
    "getBookmark" to "Bookmarks",

    // Alarms
    "createAlarm" to "Alarms",
    "deleteAlarm" to "Alarms",
    "getAllAlarms" to "Alarms",
    "setReminder" to "Alarms",

    // Voice
    "transcribeAudio" to "Voice",
    "transcribeSpeech" to "Voice",
    "ttsSpeak" to "Voice",
    "ttsStop" to "Voice",
    "ttsVoices" to "Voice",
    "audioConvert" to "Voice",
    "audioTrim" to "Voice",
    "audioInfo" to "Voice",

    // Theme
    "getCurrentTheme" to "Theme",
    "setThemeColor" to "Theme",
    "applyThemePreset" to "Theme",
    "resetTheme" to "Theme",
    "exportTheme" to "Theme",
    "importTheme" to "Theme",
    "listThemePresets" to "Theme",
    "setThemeCornerRadius" to "Theme",
    "setThemeFont" to "Theme",
    "listAvailableFonts" to "Theme",
    "resetThemeFont" to "Theme",
    "setFontScale" to "Theme",
    "setChatColour" to "Theme",
    "resetChatColour" to "Theme",
    "getLayoutConfig" to "Theme",
    "setLayoutProperty" to "Theme",
    "applyLayoutPreset" to "Theme",
    "resetLayoutConfig" to "Theme",

    // Settings
    "getPreference" to "Settings",
    "savePreference" to "Settings",
    "getAllPreferences" to "Settings",
    "setPreference" to "Settings",

    // Contacts
    "searchContacts" to "Contacts",
    "getContact" to "Contacts",

    // Communication
    "discordSend" to "Communication",
    "discordRead" to "Communication",
    "discordReact" to "Communication",
    "slackSend" to "Communication",
    "slackRead" to "Communication",
    "slackPin" to "Communication",
    "whatsappSend" to "Communication",
    "whatsappSearch" to "Communication",
    "xPost" to "Communication",
    "xReply" to "Communication",
    "xDm" to "Communication",
    "xSearch" to "Communication",
    "voiceCallStart" to "Communication",
    "voiceCallStatus" to "Communication",

    // Jobs
    "createJob" to "Jobs",
    "listJobs" to "Jobs",
    "getJob" to "Jobs",
    "deleteJob" to "Jobs",
    "enableJob" to "Jobs",
    "disableJob" to "Jobs",
    "runJob" to "Jobs",

    // Notes
    "searchNotes" to "Notes",
    "createNote" to "Notes",
    "createMultipleNotes" to "Notes",
    "getNoteById" to "Notes",
    "searchFolders" to "Notes",
    "createFolder" to "Notes",
    "updateNote" to "Notes",
    "updateNoteFolder" to "Notes",
    "deleteNote" to "Notes",
    "deleteNoteFolder" to "Notes",
    "getAllNotes" to "Notes",
    "getNotesByFolder" to "Notes",
    "getAllNoteFolders" to "Notes",

    // Journal
    "createJournalEntry" to "Journal",
    "searchJournalEntries" to "Journal",
    "getJournalEntry" to "Journal",
    "updateJournalEntry" to "Journal",
    "deleteJournalEntry" to "Journal",
    "getAllJournalEntries" to "Journal",

    // Meta tools (tool management)
    "defineTool" to "Tools",
    "getMyTools" to "Tools",
    "getToolDetails" to "Tools",
    "updateTool" to "Tools",
    "deleteTool" to "Tools",
    "disableTool" to "Tools",
    "enableTool" to "Tools",

    // Media (Spotify + content)
    "spotifyStatus" to "Media",
    "spotifyPlay" to "Media",
    "spotifyPause" to "Media",
    "spotifyNext" to "Media",
    "spotifyPrevious" to "Media",
    "spotifySearch" to "Media",
    "spotifyDevices" to "Media",
    "spotifySetDevice" to "Media",
    "gifSearch" to "Media",
    "gifDownload" to "Media",
    "memeSearch" to "Media",
    "memeCreate" to "Media",
    "videoFrame" to "Media",
    "videoSheet" to "Media",
    "songRecognize" to "Media",
    "cameraCapture" to "Media",

    // Shortcuts / Widgets
    "createShortcut" to "Shortcuts",
    "listShortcuts" to "Shortcuts",
    "removeShortcut" to "Shortcuts",
    "updateWidget" to "Shortcuts",

    // Util
    "formatDate" to "Util",

    // Email
    "emailList" to "Email",
    "emailRead" to "Email",
    "emailSearch" to "Email",
    "emailSend" to "Email",
    "emailReply" to "Email",
    "emailForward" to "Email",
    "emailMove" to "Email",
    "emailDelete" to "Email",
    "listEmails" to "Email",
    "sendEmail" to "Email",
    "readEmail" to "Email",
    "deleteEmail" to "Email",

    // Security (reverse engineering)
    "listInstalledPackages" to "Security",
    "pmDump" to "Security",
    "pullApk" to "Security",
    "listApkContents" to "Security",
    "extractApk" to "Security",
    "readManifest" to "Security",
    "extractDex" to "Security",
    "extractNativeLibs" to "Security",
    "extractResources" to "Security",
    "decompileApk" to "Security",
    "modApk" to "Security",
    "signApk" to "Security",

    // Memory
    "searchMemoryFacts" to "Memory",
    "addMemoryFact" to "Memory",
    "deleteMemoryFact" to "Memory",
    "updateMemoryFact" to "Memory",
    "listMemoryFacts" to "Memory",
    "searchConversations" to "Memory",
    "getConversationThread" to "Memory",

    // Smart Home
    "hueListLights" to "SmartHome",
    "hueListRooms" to "SmartHome",
    "hueListScenes" to "SmartHome",
    "hueSetLight" to "SmartHome",
    "hueSetRoom" to "SmartHome",
    "hueActivateScene" to "SmartHome",
    "hueLights" to "SmartHome",
    "hueControl" to "SmartHome",
    "sonosDiscover" to "SmartHome",
    "sonosStatus" to "SmartHome",
    "sonosPlayPause" to "SmartHome",
    "sonosVolume" to "SmartHome",
    "sonosGroup" to "SmartHome",
    "bluetoothList" to "SmartHome",
    "bluetoothConnect" to "SmartHome",
    "bluetoothDisconnect" to "SmartHome",

    // Encryption
    "encryptAes" to "Encryption",
    "decryptAes" to "Encryption",
    "generateAesKey" to "Encryption",
    "hashData" to "Encryption",
    "base64Encode" to "Encryption",
    "base64Decode" to "Encryption",
    "encryptFile" to "Encryption",
    "decryptFile" to "Encryption",

    // Plans
    "createPlan" to "Plans",
    "updatePlanStep" to "Plans",
    "getActivePlans" to "Plans",
    "deletePlan" to "Plans",

    // Hooks
    "createHook" to "Hooks",
    "listHooks" to "Hooks",
    "getHook" to "Hooks",
    "deleteHook" to "Hooks",
    "enableHook" to "Hooks",
    "disableHook" to "Hooks",
    "triggerHook" to "Hooks",

    // Calendar
    "getEventsWithinRange" to "Calendar",
    "searchEventsByNameWithinRange" to "Calendar",
    "createEvent" to "Calendar",
    "createEvents" to "Calendar",
    "getAllCalendars" to "Calendar",
    "updateEvent" to "Calendar",
    "deleteEvent" to "Calendar",
    "getEventById" to "Calendar",
    "getMonthEvents" to "Calendar",

    // Thoughts
    "createThoughtCycle" to "Thoughts",
    "listThoughtCycles" to "Thoughts",
    "getThoughtCycle" to "Thoughts",
    "executeThoughtCycle" to "Thoughts",
    "deleteThoughtCycle" to "Thoughts",
    "enableThoughtCycle" to "Thoughts",
    "disableThoughtCycle" to "Thoughts",
    "getInsights" to "Thoughts",
    "acknowledgeInsight" to "Thoughts",
    "dismissInsight" to "Thoughts",

    // Database
    "sqlQuery" to "Database",
    "sqlExecute" to "Database",
    "sqlGetSchema" to "Database",
    "sqlListDatabases" to "Database",
    "queryContentProvider" to "Database",

    // HTTP
    "httpGet" to "HTTP",
    "httpPost" to "HTTP",
    "httpPut" to "HTTP",
    "httpDelete" to "HTTP",
    "httpPatch" to "HTTP",
    "httpHead" to "HTTP",
    "httpDownload" to "HTTP",
    "resolveDownloadUrl" to "HTTP",

    // Web
    "webSearch" to "Web",
    "webFetch" to "Web",
    "webSummarize" to "Web",

    // Location
    "getCurrentLocation" to "Location",
    "geocode" to "Location",
    "reverseGeocode" to "Location",

    // Camera
    "cameraList" to "Camera",
    "cameraSnapshot" to "Camera",
    "cameraRecord" to "Camera",
    "cameraMotionDetect" to "Camera",

    // Productivity (third-party integrations)
    "githubStatus" to "Productivity",
    "githubPrList" to "Productivity",
    "githubPrView" to "Productivity",
    "githubPrCreate" to "Productivity",
    "githubIssueList" to "Productivity",
    "githubIssueCreate" to "Productivity",
    "githubRepoInfo" to "Productivity",
    "githubRepo" to "Productivity",
    "trelloBoards" to "Productivity",
    "trelloLists" to "Productivity",
    "trelloCards" to "Productivity",
    "trelloCreateCard" to "Productivity",
    "trelloMoveCard" to "Productivity",
    "notionSearch" to "Productivity",
    "notionGetPage" to "Productivity",
    "notionCreatePage" to "Productivity",
    "diagramCreate" to "Productivity",

    // Tasks
    "searchTasks" to "Tasks",
    "createTask" to "Tasks",
    "createMultipleTasks" to "Tasks",
    "updateTask" to "Tasks",
    "updateTaskCompleted" to "Tasks",
    "deleteTask" to "Tasks",
    "getAllTasks" to "Tasks",

    // Notifications
    "getActiveNotifications" to "Notifications",
    "getNotification" to "Notifications",
    "dismissNotification" to "Notifications",
    "dismissAllNotifications" to "Notifications",
    "sendNotification" to "Notifications",
    "askUser" to "Notifications",

    // Weather
    "weatherCurrent" to "Weather",
    "weatherForecast" to "Weather",
    "weatherAlert" to "Weather",

    // Places
    "placesSearch" to "Places",
    "placesDetails" to "Places",
    "searchPlaces" to "Places",

    // RSS / Blog
    "rssFetch" to "RSS",
    "blogWatch" to "RSS",

    // URLs
    "urlShorten" to "URLs",
    "urlExpand" to "URLs",

    // QR codes
    "qrGenerate" to "QR",
    "qrScan" to "QR",

    // Prompts
    "proposePromptAmendment" to "Prompts",
    "getPromptSections" to "Prompts",
    "getAmendmentHistory" to "Prompts",
    "approveAmendment" to "Prompts",
    "rejectAmendment" to "Prompts",
    "rollbackAmendment" to "Prompts",
    "getPendingAmendments" to "Prompts",
    "proposeAmendment" to "Prompts",

    // Projects
    "createProject" to "Projects",
    "searchProjects" to "Projects",
    "getAllProjects" to "Projects",
    "getProject" to "Projects",
    "updateProject" to "Projects",
    "deleteProject" to "Projects",
    "addProjectMessage" to "Projects",
    "getProjectMessages" to "Projects",
    "searchProjectMessages" to "Projects",
    "addProjectDocument" to "Projects",
    "getProjectDocuments" to "Projects",
    "searchProjectDocuments" to "Projects",
    "updateProjectDocument" to "Projects",
    "deleteProjectDocument" to "Projects",
    "addProjectFact" to "Projects",
    "getProjectFacts" to "Projects",
    "searchProjectFacts" to "Projects",
    "deleteProjectFact" to "Projects",
    "getProjectSummaries" to "Projects",

    // Sound (system audio)
    "getVolume" to "Sound",
    "setVolume" to "Sound",
    "adjustVolume" to "Sound",
    "getRingerMode" to "Sound",
    "setRingerMode" to "Sound",
    "speakText" to "Sound",
    "stopSpeaking" to "Sound",
    "vibrate" to "Sound",
    "playRingtone" to "Sound",
    "playSoundFile" to "Sound",
    "stopSound" to "Sound",
    "getAudioInfo" to "Sound",

    // Portal
    "openPortal" to "Portal",
    "updatePortal" to "Portal",
    "closePortal" to "Portal",

    // Canvas
    "canvasRender" to "Canvas",
    "canvasSnapshot" to "Canvas",

    // WebView Browser
    "webBrowserLoad" to "Web",
    "webBrowserContent" to "Web",
    "webBrowserScreenshot" to "Web",
    "webBrowserClick" to "Web",
    "webBrowserInput" to "Web",
    "webBrowserScroll" to "Web",
    "webBrowserConsole" to "Web",
    "webBrowserNetwork" to "Web",
)

/**
 * Returns the category string for a tool name, falling back to "Misc" if unknown.
 * Categories correspond to PNG filenames in assets/tools_icons/ e.g. "Notes" → Notes.png.
 */
fun toolCategory(rawName: String): String = toolCategoryMap[rawName] ?: "Misc"

// ─── English summary for the grouped card header ──
// Each tool maps to a past-tense English phrase that reads naturally in a sentence.
// Examples: searchNotes → "Searched your notes", weatherCurrent → "Checked the weather"

val toolSummaryMap: Map<String, String> = mapOf(
    // Notes
    "searchNotes" to "Searched your notes",
    "createNote" to "Created a note",
    "createMultipleNotes" to "Created multiple notes",
    "getNoteById" to "Read a note",
    "searchFolders" to "Searched note folders",
    "createFolder" to "Created a folder",
    "updateNote" to "Updated a note",
    "updateNoteFolder" to "Updated a folder",
    "deleteNote" to "Deleted a note",
    "deleteNoteFolder" to "Deleted a folder",
    "getAllNotes" to "Listed your notes",
    "getNotesByFolder" to "Listed notes in a folder",
    "getAllNoteFolders" to "Listed your folders",

    // Tasks
    "searchTasks" to "Searched your tasks",
    "createTask" to "Created a task",
    "createMultipleTasks" to "Created multiple tasks",
    "updateTask" to "Updated a task",
    "updateTaskCompleted" to "Updated a task status",
    "deleteTask" to "Deleted a task",
    "getAllTasks" to "Listed your tasks",

    // Calendar
    "getEventsWithinRange" to "Checked your calendar",
    "searchEventsByNameWithinRange" to "Searched your calendar",
    "createEvent" to "Created a calendar event",
    "createEvents" to "Created calendar events",
    "getAllCalendars" to "Listed your calendars",
    "updateEvent" to "Updated a calendar event",
    "deleteEvent" to "Deleted a calendar event",
    "getEventById" to "Read a calendar event",
    "getMonthEvents" to "Checked your month",

    // Journal
    "createJournalEntry" to "Saved a journal entry",
    "searchJournalEntries" to "Searched your journal",
    "getJournalEntry" to "Read a journal entry",
    "updateJournalEntry" to "Updated a journal entry",
    "deleteJournalEntry" to "Deleted a journal entry",
    "getAllJournalEntries" to "Listed your journal",

    // Bookmarks
    "createBookmark" to "Saved a bookmark",
    "searchBookmarks" to "Searched your bookmarks",
    "updateBookmark" to "Updated a bookmark",
    "deleteBookmark" to "Deleted a bookmark",
    "getAllBookmarks" to "Listed your bookmarks",
    "getBookmark" to "Read a bookmark",

    // Web
    "webSearch" to "Searched the web",
    "webFetch" to "Fetched a webpage",
    "webSummarize" to "Summarised a webpage",

    // Files
    "readFile" to "Read a file",
    "writeFile" to "Wrote a file",
    "listDirectory" to "Listed a directory",
    "searchFiles" to "Searched your files",
    "searchInFiles" to "Searched inside your files",
    "editFile" to "Edited a file",
    "fsFindFiles" to "Found files",
    "fsCopyFile" to "Copied a file",
    "fsMoveFile" to "Moved a file",
    "fsDeleteFile" to "Deleted a file",
    "fsGetFileInfo" to "Got file info",
    "createArchive" to "Created an archive",
    "extractArchive" to "Extracted an archive",
    "listArchive" to "Listed an archive",
    "processFile" to "Processed a file",
    "copyFile" to "Copied a file",
    "deleteFile" to "Deleted a file",

    // Memory
    "searchMemoryFacts" to "Searched your memory",
    "addMemoryFact" to "Saved to memory",
    "deleteMemoryFact" to "Removed from memory",
    "updateMemoryFact" to "Updated your memory",
    "listMemoryFacts" to "Listed your memory",
    "searchConversations" to "Searched past conversations",
    "getConversationThread" to "Read a conversation thread",

    // Plans
    "createPlan" to "Created a plan",
    "updatePlanStep" to "Updated a plan step",
    "getActivePlans" to "Checked active plans",
    "deletePlan" to "Deleted a plan",

    // Projects
    "createProject" to "Created a project",
    "searchProjects" to "Searched your projects",
    "getAllProjects" to "Listed your projects",
    "getProject" to "Read a project",
    "updateProject" to "Updated a project",
    "deleteProject" to "Deleted a project",
    "addProjectMessage" to "Saved to a project",
    "getProjectMessages" to "Read project messages",
    "searchProjectMessages" to "Searched project messages",
    "addProjectDocument" to "Added a project document",
    "getProjectDocuments" to "Listed project documents",
    "searchProjectDocuments" to "Searched project documents",
    "updateProjectDocument" to "Updated a project document",
    "deleteProjectDocument" to "Removed a project document",
    "addProjectFact" to "Saved a project fact",
    "getProjectFacts" to "Listed project facts",
    "searchProjectFacts" to "Searched project facts",
    "deleteProjectFact" to "Removed a project fact",
    "getProjectSummaries" to "Summarised your projects",

    // Alarms / Reminders
    "createAlarm" to "Set an alarm",
    "deleteAlarm" to "Removed an alarm",
    "getAllAlarms" to "Listed your alarms",
    "setReminder" to "Set a reminder",

    // Sound
    "getVolume" to "Checked the volume",
    "setVolume" to "Changed the volume",
    "adjustVolume" to "Adjusted the volume",
    "getRingerMode" to "Checked the ringer mode",
    "setRingerMode" to "Changed the ringer mode",
    "speakText" to "Spoke text",
    "stopSpeaking" to "Stopped speech",
    "vibrate" to "Vibrated",
    "playRingtone" to "Played a ringtone",
    "playSoundFile" to "Played a sound",
    "stopSound" to "Stopped sound",
    "getAudioInfo" to "Got audio info",

    // Voice
    "transcribeAudio" to "Transcribed audio",
    "transcribeSpeech" to "Transcribed speech",
    "ttsSpeak" to "Spoke text",
    "ttsStop" to "Stopped TTS",
    "ttsVoices" to "Listed TTS voices",
    "audioConvert" to "Converted audio",
    "audioTrim" to "Trimmed audio",
    "audioInfo" to "Got audio info",

    // Media (Spotify + content)
    "spotifyStatus" to "Checked Spotify",
    "spotifyPlay" to "Played on Spotify",
    "spotifyPause" to "Paused Spotify",
    "spotifyNext" to "Skipped a track",
    "spotifyPrevious" to "Went back a track",
    "spotifySearch" to "Searched Spotify",
    "spotifyDevices" to "Listed Spotify devices",
    "spotifySetDevice" to "Switched Spotify device",
    "gifSearch" to "Searched for GIFs",
    "gifDownload" to "Downloaded a GIF",
    "memeSearch" to "Searched for memes",
    "memeCreate" to "Made a meme",
    "videoFrame" to "Captured a video frame",
    "videoSheet" to "Made a video sheet",
    "songRecognize" to "Recognised a song",
    "cameraCapture" to "Captured from the camera",

    // Camera
    "cameraList" to "Listed cameras",
    "cameraSnapshot" to "Took a snapshot",
    "cameraRecord" to "Recorded video",
    "cameraMotionDetect" to "Checked for motion",

    // Smart Home
    "hueListLights" to "Listed your lights",
    "hueListRooms" to "Listed your rooms",
    "hueListScenes" to "Listed your scenes",
    "hueSetLight" to "Adjusted a light",
    "hueSetRoom" to "Adjusted a room",
    "hueActivateScene" to "Activated a scene",
    "hueLights" to "Checked your lights",
    "hueControl" to "Adjusted your lights",
    "sonosDiscover" to "Discovered Sonos speakers",
    "sonosStatus" to "Checked Sonos",
    "sonosPlayPause" to "Toggled Sonos",
    "sonosVolume" to "Adjusted Sonos volume",
    "sonosGroup" to "Grouped Sonos speakers",
    "bluetoothList" to "Listed Bluetooth devices",
    "bluetoothConnect" to "Connected Bluetooth",
    "bluetoothDisconnect" to "Disconnected Bluetooth",

    // Communication
    "discordSend" to "Sent a Discord message",
    "discordRead" to "Read Discord messages",
    "discordReact" to "Reacted on Discord",
    "slackSend" to "Sent a Slack message",
    "slackRead" to "Read Slack messages",
    "slackPin" to "Pinned a Slack message",
    "whatsappSend" to "Sent a WhatsApp message",
    "whatsappSearch" to "Searched WhatsApp",
    "xPost" to "Posted on X",
    "xReply" to "Replied on X",
    "xDm" to "Sent a DM on X",
    "xSearch" to "Searched X",
    "voiceCallStart" to "Started a call",
    "voiceCallStatus" to "Checked a call status",

    // Email
    "emailList" to "Checked your inbox",
    "emailRead" to "Read an email",
    "emailSearch" to "Searched your inbox",
    "emailSend" to "Sent an email",
    "emailReply" to "Replied to an email",
    "emailForward" to "Forwarded an email",
    "emailMove" to "Moved an email",
    "emailDelete" to "Deleted an email",
    "listEmails" to "Checked your inbox",
    "sendEmail" to "Sent an email",
    "readEmail" to "Read an email",
    "deleteEmail" to "Deleted an email",

    // System
    "healthCheck" to "Checked system health",
    "systemHealth" to "Checked system health",
    "deviceInfo" to "Got device info",
    "getDeviceInfo" to "Got device info",
    "sessionLogsSearch" to "Searched session logs",
    "sessionLogsExport" to "Exported session logs",
    "sendIntent" to "Sent an intent",
    "sendBroadcast" to "Sent a broadcast",
    "queryIntentActivities" to "Queried intent activities",
    "listProcesses" to "Listed running processes",
    "killProcess" to "Killed a process",
    "checkFileSystemAccess" to "Checked file system access",
    "requestAllFilesAccess" to "Requested file access",
    "setScreenCapturePermission" to "Set screen capture permission",

    // Apps
    "listInstalledApps" to "Listed installed apps",
    "launchApp" to "Launched an app",
    "uninstallApp" to "Uninstalled an app",
    "installApk" to "Installed an APK",
    "forceStopApp" to "Force-stopped an app",
    "clearAppData" to "Cleared app data",
    "openApp" to "Opened an app",

    // Screen
    "tapScreen" to "Tapped the screen",
    "clickText" to "Clicked text on screen",
    "swipe" to "Swiped the screen",
    "scroll" to "Scrolled the screen",
    "typeText" to "Typed text",
    "pressButton" to "Pressed a button",
    "getScreenState" to "Read the screen state",
    "findElements" to "Found screen elements",
    "captureScreen" to "Captured the screen",
    "takeScreenshot" to "Took a screenshot",
    "recordScreen" to "Recorded the screen",
    "requestScreenCapturePermission" to "Requested screen capture",

    // Keyboard
    "keyboardTypeText" to "Typed via keyboard",
    "keyboardPressKey" to "Pressed a key",
    "keyboardPressKeyCombo" to "Pressed a key combo",
    "keyboardHideKeyboard" to "Hid the keyboard",

    // Network
    "mdnsDiscover" to "Scanned for mDNS devices",
    "mdnsListDevices" to "Listed mDNS devices",
    "mdnsStopDiscovery" to "Stopped mDNS discovery",
    "ssdpDiscover" to "Scanned for SSDP devices",
    "ssdpListDevices" to "Listed SSDP devices",
    "ssdpStopDiscovery" to "Stopped SSDP discovery",
    "arpScan" to "Scanned the ARP table",
    "wifiScan" to "Scanned Wi-Fi networks",
    "bluetoothScan" to "Scanned for Bluetooth devices",
    "tcpConnect" to "Connected via TCP",
    "networkScan" to "Scanned the network",
    "networkPing" to "Pinged a host",

    // SSH
    "sshConnect" to "Connected via SSH",
    "sshExec" to "Ran an SSH command",
    "sshDisconnect" to "Disconnected SSH",

    // Shell
    "executeShellCommand" to "Ran a command",
    "shellExec" to "Ran a command",
    "busyboxExec" to "Ran a BusyBox command",
    "getBusyboxHelp" to "Got BusyBox help",
    "executePythonScript" to "Ran a Python script",

    // Termux
    "termuxInit" to "Initialised Termux",
    "termuxExec" to "Ran a Termux command",
    "termuxInstall" to "Installed a Termux package",
    "termuxStatus" to "Checked Termux status",
    "termuxHasCommand" to "Checked Termux command availability",
    "termuxDiagnose" to "Diagnosed Termux",

    // ADB
    "pairAdbDevice" to "Paired an ADB device",
    "checkAdbStatus" to "Checked ADB status",
    "autoPairAdb" to "Auto-paired ADB",

    // Security
    "listInstalledPackages" to "Listed installed packages",
    "pmDump" to "Dumped package manager info",
    "pullApk" to "Pulled an APK",
    "listApkContents" to "Listed APK contents",
    "extractApk" to "Extracted an APK",
    "readManifest" to "Read an app manifest",
    "extractDex" to "Extracted DEX files",
    "extractNativeLibs" to "Extracted native libraries",
    "extractResources" to "Extracted resources",
    "decompileApk" to "Decompiled an APK",
    "modApk" to "Modified an APK",
    "signApk" to "Signed an APK",

    // Encryption
    "encryptAes" to "Encrypted data",
    "decryptAes" to "Decrypted data",
    "generateAesKey" to "Generated an AES key",
    "hashData" to "Hashed data",
    "base64Encode" to "Encoded Base64",
    "base64Decode" to "Decoded Base64",
    "encryptFile" to "Encrypted a file",
    "decryptFile" to "Decrypted a file",

    // Hooks
    "createHook" to "Created a hook",
    "listHooks" to "Listed your hooks",
    "getHook" to "Read a hook",
    "deleteHook" to "Deleted a hook",
    "enableHook" to "Enabled a hook",
    "disableHook" to "Disabled a hook",
    "triggerHook" to "Triggered a hook",

    // Jobs
    "createJob" to "Created a job",
    "listJobs" to "Listed your jobs",
    "getJob" to "Read a job",
    "deleteJob" to "Deleted a job",
    "enableJob" to "Enabled a job",
    "disableJob" to "Disabled a job",
    "runJob" to "Ran a job",

    // Approvals
    "getPendingApprovals" to "Checked pending approvals",
    "getApprovalDetails" to "Read an approval",
    "approveItem" to "Approved an item",
    "rejectItem" to "Rejected an item",
    "approveTool" to "Approved a tool",

    // Meta tools
    "defineTool" to "Created a tool",
    "getMyTools" to "Listed your tools",
    "getToolDetails" to "Read a tool",
    "updateTool" to "Updated a tool",
    "deleteTool" to "Deleted a tool",
    "disableTool" to "Disabled a tool",
    "enableTool" to "Enabled a tool",

    // Thoughts
    "createThoughtCycle" to "Created a thought cycle",
    "listThoughtCycles" to "Listed thought cycles",
    "getThoughtCycle" to "Read a thought cycle",
    "executeThoughtCycle" to "Executed a thought cycle",
    "deleteThoughtCycle" to "Deleted a thought cycle",
    "enableThoughtCycle" to "Enabled a thought cycle",
    "disableThoughtCycle" to "Disabled a thought cycle",
    "getInsights" to "Got insights",
    "acknowledgeInsight" to "Acknowledged an insight",
    "dismissInsight" to "Dismissed an insight",

    // Database
    "sqlQuery" to "Queried a database",
    "sqlExecute" to "Ran SQL",
    "sqlGetSchema" to "Got a database schema",
    "sqlListDatabases" to "Listed databases",
    "queryContentProvider" to "Queried a content provider",

    // HTTP
    "httpGet" to "Made a GET request",
    "httpPost" to "Made a POST request",
    "httpPut" to "Made a PUT request",
    "httpDelete" to "Made a DELETE request",
    "httpPatch" to "Made a PATCH request",
    "httpHead" to "Made a HEAD request",
    "httpDownload" to "Downloaded a file",
    "resolveDownloadUrl" to "Resolved a download URL",

    // Location
    "getCurrentLocation" to "Got your location",
    "geocode" to "Geocoded an address",
    "reverseGeocode" to "Reverse-geocoded coordinates",

    // Settings
    "getPreference" to "Got a setting",
    "savePreference" to "Saved a setting",
    "getAllPreferences" to "Listed your settings",
    "setPreference" to "Changed a setting",

    // Contacts
    "searchContacts" to "Searched your contacts",
    "getContact" to "Read a contact",

    // Clipboard
    "getClipboard" to "Read the clipboard",
    "setClipboard" to "Copied to the clipboard",
    "clearClipboard" to "Cleared the clipboard",

    // Shortcuts
    "createShortcut" to "Created a shortcut",
    "listShortcuts" to "Listed your shortcuts",
    "removeShortcut" to "Removed a shortcut",
    "updateWidget" to "Updated a widget",

    // Automation
    "createAutomation" to "Created an automation",
    "runAutomation" to "Ran an automation",
    "listAutomations" to "Listed your automations",
    "getAutomation" to "Read an automation",
    "deleteAutomation" to "Deleted an automation",
    "enableAutomation" to "Enabled an automation",
    "disableAutomation" to "Disabled an automation",

    // Skills
    "useSkill" to "Loaded a skill",
    "searchSkills" to "Searched skills",
    "createSkill" to "Created a skill",
    "deleteSkill" to "Deleted a skill",

    // Notes to self
    "createNoteToSelf" to "Wrote a note to self",
    "listNotesToSelf" to "Reviewed notes to self",
    "updateNoteToSelf" to "Updated a note to self",
    "deleteNoteToSelf" to "Deleted a note to self",
    "enableNoteToSelf" to "Enabled a note to self",
    "disableNoteToSelf" to "Disabled a note to self",

    // Theme
    "getCurrentTheme" to "Read your theme",
    "setThemeColor" to "Changed a theme colour",
    "applyThemePreset" to "Applied a theme preset",
    "resetTheme" to "Reset the theme",
    "exportTheme" to "Exported the theme",
    "importTheme" to "Imported a theme",
    "listThemePresets" to "Listed theme presets",
    "setThemeCornerRadius" to "Changed corner radius",
    "setThemeFont" to "Changed the font",
    "listAvailableFonts" to "Listed available fonts",
    "resetThemeFont" to "Reset the font",
    "setFontScale" to "Changed the font scale",
    "setChatColour" to "Changed chat colours",
    "resetChatColour" to "Reset chat colours",
    "getLayoutConfig" to "Read the layout config",
    "setLayoutProperty" to "Changed a layout property",
    "applyLayoutPreset" to "Applied a layout preset",
    "resetLayoutConfig" to "Reset the layout",

    // Notifications
    "getActiveNotifications" to "Checked active notifications",
    "getNotification" to "Read a notification",
    "dismissNotification" to "Dismissed a notification",
    "dismissAllNotifications" to "Dismissed all notifications",
    "sendNotification" to "Sent a notification",
    "askUser" to "Asked you",

    // Weather
    "weatherCurrent" to "Checked the weather",
    "weatherForecast" to "Got the forecast",
    "weatherAlert" to "Checked weather alerts",

    // Places
    "placesSearch" to "Searched for places",
    "placesDetails" to "Got place details",
    "searchPlaces" to "Searched for places",

    // RSS / Blog
    "rssFetch" to "Fetched an RSS feed",
    "blogWatch" to "Watched a blog",

    // URLs
    "urlShorten" to "Shortened a URL",
    "urlExpand" to "Expanded a URL",

    // QR
    "qrGenerate" to "Generated a QR code",
    "qrScan" to "Scanned a QR code",

    // Prompts
    "proposePromptAmendment" to "Proposed a prompt change",
    "getPromptSections" to "Read prompt sections",
    "getAmendmentHistory" to "Read amendment history",
    "approveAmendment" to "Approved a change",
    "rejectAmendment" to "Rejected a change",
    "rollbackAmendment" to "Rolled back a change",
    "getPendingAmendments" to "Checked pending changes",
    "proposeAmendment" to "Proposed a change",

    // Productivity
    "githubStatus" to "Checked GitHub",
    "githubPrList" to "Listed pull requests",
    "githubPrView" to "Read a pull request",
    "githubPrCreate" to "Created a pull request",
    "githubIssueList" to "Listed issues",
    "githubIssueCreate" to "Created an issue",
    "githubRepoInfo" to "Got repo info",
    "githubRepo" to "Got repo info",
    "trelloBoards" to "Listed Trello boards",
    "trelloLists" to "Listed Trello lists",
    "trelloCards" to "Listed Trello cards",
    "trelloCreateCard" to "Created a Trello card",
    "trelloMoveCard" to "Moved a Trello card",
    "notionSearch" to "Searched Notion",
    "notionGetPage" to "Read a Notion page",
    "notionCreatePage" to "Created a Notion page",
    "diagramCreate" to "Created a diagram",

    // Portal
    "openPortal" to "Opened a portal",
    "updatePortal" to "Updated a portal",
    "closePortal" to "Closed a portal",

    // Canvas
    "canvasRender" to "Rendered on the canvas",
    "canvasSnapshot" to "Took a canvas snapshot",

    // WebView Browser
    "webBrowserLoad" to "Loaded a webpage",
    "webBrowserContent" to "Extracted content from a page",
    "webBrowserScreenshot" to "Captured a page screenshot",
    "webBrowserClick" to "Clicked an element",
    "webBrowserInput" to "Typed into a form field",
    "webBrowserScroll" to "Scrolled the page",
    "webBrowserConsole" to "Read console output",
    "webBrowserNetwork" to "Monitored network requests",

    // Util
    "formatDate" to "Formatted a date",
)

// ─── HTML stripping for web tool results ──
// webFetch returns raw HTML in the "content" field. This strips tags and
// decodes entities so the Level 4 view shows readable text, not markup.

private fun looksLikeHtml(s: String): Boolean {
    return s.contains(Regex("<(div|span|p|a|ul|ol|li|h[1-6]|img|br|table|tr|td|th|script|style|nav|header|footer|section|article|aside|main|figure|figcaption)[\\s>]", RegexOption.IGNORE_CASE))
}

private fun stripHtml(html: String): String {
    var text = html
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&apos;", "'")
        .replace("&nbsp;", " ")
        .replace("&#x27;", "'")

    // Remove script and style blocks with their content entirely
    text = text.replace(Regex("<script[^>]*>[\\s\\S]*?</script>", RegexOption.IGNORE_CASE), "")
    text = text.replace(Regex("<style[^>]*>[\\s\\S]*?</style>", RegexOption.IGNORE_CASE), "")

    // Convert block-level closing tags to newlines to preserve paragraph structure
    text = text.replace(Regex("</(p|div|h[1-6]|li|tr|td|th|section|article|header|footer|nav|aside|figure|blockquote|pre)[^>]*>", RegexOption.IGNORE_CASE), "\n")
    text = text.replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")

    // Strip all remaining HTML tags
    text = text.replace(Regex("<[^>]+>"), "")

    // Decode any remaining numeric entities
    text = text.replace(Regex("&#(\\d+);")) { match ->
        match.groupValues[1].toIntOrNull()?.toChar()?.toString() ?: ""
    }

    // Collapse runs of whitespace within lines and trim each line
    val lines = text.split("\n").map { line ->
        line.replace(Regex("[ \\t]+"), " ").trim()
    }.filter { it.isNotEmpty() }

    // Collapse multiple blank lines into single paragraph breaks
    val result = StringBuilder()
    var prevBlank = false
    for (line in lines) {
        if (line.isEmpty()) {
            if (!prevBlank) result.append("\n")
            prevBlank = true
        } else {
            result.append(line).append("\n")
            prevBlank = false
        }
    }

    return result.toString().trim()
}

// ─── Full result parsing for Level 4 ──
// Parses the entire result JSON into readable field entries.
// No truncation. Every field, every value, every array item rendered fully.

sealed class ResultEntry {
    data class StringField(val key: String, val value: String) : ResultEntry()
    data class UrlField(val key: String, val url: String) : ResultEntry()
    data class NumberField(val key: String, val value: String) : ResultEntry()
    data class BooleanField(val key: String, val value: Boolean) : ResultEntry()
    data class NullField(val key: String) : ResultEntry()
    data class ArrayField(val key: String, val itemCount: Int, val items: List<List<ResultEntry>>) : ResultEntry()
    data class ObjectField(val key: String, val fields: List<ResultEntry>) : ResultEntry()
    data class RawField(val text: String) : ResultEntry()
}

// ─── Flat representation for lazy rendering ──
// Flattens the recursive ResultEntry tree into a single list so it can be
// rendered in a LazyColumn without nesting composables. Every entry is
// preserved. Nothing is truncated. The indent field tracks nesting depth
// for visual padding.

data class FlatResultItem(
    val type: FlatItemType,
    val key: String,
    val value: String = "",
    val url: String? = null,
    val indent: Int = 0,
)

enum class FlatItemType {
    STRING, URL, NUMBER, BOOLEAN, NULL, ARRAY_HEADER, OBJECT_HEADER, RAW, DIVIDER
}

fun flattenResults(entries: List<ResultEntry>): List<FlatResultItem> {
    val flat = mutableListOf<FlatResultItem>()
    for (entry in entries) {
        flattenEntry(entry, 0, flat)
    }
    return flat
}

private fun flattenEntry(entry: ResultEntry, indent: Int, out: MutableList<FlatResultItem>) {
    when (entry) {
        is ResultEntry.StringField -> {
            out.add(FlatResultItem(FlatItemType.STRING, entry.key, entry.value, indent = indent))
            out.add(FlatResultItem(FlatItemType.DIVIDER, "", indent = indent))
        }
        is ResultEntry.UrlField -> {
            out.add(FlatResultItem(FlatItemType.URL, entry.key, url = entry.url, indent = indent))
            out.add(FlatResultItem(FlatItemType.DIVIDER, "", indent = indent))
        }
        is ResultEntry.NumberField -> {
            out.add(FlatResultItem(FlatItemType.NUMBER, entry.key, entry.value, indent = indent))
            out.add(FlatResultItem(FlatItemType.DIVIDER, "", indent = indent))
        }
        is ResultEntry.BooleanField -> {
            out.add(FlatResultItem(FlatItemType.BOOLEAN, entry.key, value = if (entry.value) "Yes" else "No", indent = indent))
            out.add(FlatResultItem(FlatItemType.DIVIDER, "", indent = indent))
        }
        is ResultEntry.NullField -> {
            out.add(FlatResultItem(FlatItemType.NULL, entry.key, indent = indent))
            out.add(FlatResultItem(FlatItemType.DIVIDER, "", indent = indent))
        }
        is ResultEntry.RawField -> {
            out.add(FlatResultItem(FlatItemType.RAW, "", entry.text, indent = indent))
        }
        is ResultEntry.ObjectField -> {
            out.add(FlatResultItem(FlatItemType.OBJECT_HEADER, entry.key, indent = indent))
            for (field in entry.fields) {
                flattenEntry(field, indent + 1, out)
            }
        }
        is ResultEntry.ArrayField -> {
            out.add(FlatResultItem(FlatItemType.ARRAY_HEADER, entry.key, value = entry.itemCount.toString(), indent = indent))
            for ((itemIndex, itemFields) in entry.items.withIndex()) {
                for (field in itemFields) {
                    flattenEntry(field, indent + 1, out)
                }
                if (itemIndex < entry.items.size - 1) {
                    out.add(FlatResultItem(FlatItemType.DIVIDER, "", indent = indent))
                }
            }
        }
    }
}

fun formatFullResult(resultRawContent: String): List<ResultEntry> {
    val cleaned = resultRawContent.trim()
    if (cleaned.isEmpty()) return listOf(ResultEntry.RawField("No result data"))

    return try {
        when {
            cleaned.startsWith("{") -> {
                val obj = JSONObject(cleaned)
                parseObjectFields(obj)
            }
            cleaned.startsWith("[") -> {
                val arr = JSONArray(cleaned)
                val items = (0 until arr.length()).map { i ->
                    parseValueFields(arr.get(i))
                }
                listOf(ResultEntry.ArrayField("Results", arr.length(), items))
            }
            else -> listOf(ResultEntry.RawField(cleaned))
        }
    } catch (e: Exception) {
        listOf(ResultEntry.RawField(cleaned))
    }
}

private fun parseObjectFields(obj: JSONObject): List<ResultEntry> {
    val result = mutableListOf<ResultEntry>()
    val keys = obj.keys()
    while (keys.hasNext()) {
        val key = keys.next()
        val value = obj.get(key)
        val label = formatArgKey(key)
        result.add(parseSingleValue(label, value))
    }
    return result
}

private fun parseValueFields(value: Any?): List<ResultEntry> {
    return when (value) {
        is JSONObject -> parseObjectFields(value)
        is JSONArray -> {
            val items = (0 until value.length()).map { i ->
                parseValueFields(value.get(i))
            }
            listOf(ResultEntry.ArrayField("Items", value.length(), items))
        }
        else -> listOf(parseSingleValue("Value", value))
    }
}

private fun isUrl(s: String): Boolean {
    return s.startsWith("http://") || s.startsWith("https://")
}

private fun parseSingleValue(key: String, value: Any?): ResultEntry {
    return when (value) {
        null -> ResultEntry.NullField(key)
        is Boolean -> ResultEntry.BooleanField(key, value)
        is Number -> ResultEntry.NumberField(key, value.toString())
        is String -> {
            if (isUrl(value)) {
                ResultEntry.UrlField(key, value)
            } else if (looksLikeHtml(value)) {
                ResultEntry.StringField(key, stripHtml(value))
            } else {
                ResultEntry.StringField(key, value)
            }
        }
        is JSONObject -> ResultEntry.ObjectField(key, parseObjectFields(value))
        is JSONArray -> {
            val items = (0 until value.length()).map { i ->
                parseValueFields(value.get(i))
            }
            ResultEntry.ArrayField(key, value.length(), items)
        }
        else -> ResultEntry.StringField(key, value.toString())
    }
}

/**
 * Build a natural English summary for a group of tool calls.
 * Identical verbs collapse to one mention. Multiple verbs join with "and"
 * and Oxford commas. Failed calls append a separator and count.
 */
fun summariseGroup(toolCalls: List<AiMessage.ToolCall>): String {
    if (toolCalls.isEmpty()) return ""

    val summaries = toolCalls.map { call ->
        toolSummaryMap[call.name] ?: toolDisplayName(call.name).replaceFirstChar { it.uppercase() }
    }
    val distinct = summaries.distinct()

    val main = when {
        distinct.size == 1 -> distinct[0]
        distinct.size == 2 -> "${distinct[0]} and ${distinct[1]}"
        distinct.size == 3 -> "${distinct[0]}, ${distinct[1]}, and ${distinct[2]}"
        else -> "${distinct[0]}, ${distinct[1]}, ${distinct[2]}, and ${distinct.size - 3} more"
    }

    val failedCount = toolCalls.count { it.isFailed }
    val failedSuffix = when {
        failedCount == 0 -> ""
        failedCount == 1 -> " — 1 failed"
        else -> " — $failedCount failed"
    }

    return main + failedSuffix
}
