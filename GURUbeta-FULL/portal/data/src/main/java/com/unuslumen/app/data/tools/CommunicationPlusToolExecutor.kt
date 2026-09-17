package com.unuslumen.app.data.tools

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.unuslumen.app.data.tools.registry.ToolExecutionResult
import com.unuslumen.app.data.tools.registry.ToolExecutor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

class CommunicationPlusToolExecutor(private val context: Context) : ToolExecutor {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun execute(toolName: String, args: Map<String, Any?>): ToolExecutionResult = when (toolName) {
        CommunicationPlusToolDefinitions.DISCORD_SEND -> discordSend(args)
        CommunicationPlusToolDefinitions.DISCORD_READ -> discordRead(args)
        CommunicationPlusToolDefinitions.DISCORD_REACT -> discordReact(args)
        CommunicationPlusToolDefinitions.SLACK_SEND -> slackSend(args)
        CommunicationPlusToolDefinitions.SLACK_READ -> slackRead(args)
        CommunicationPlusToolDefinitions.SLACK_PIN -> slackPin(args)
        CommunicationPlusToolDefinitions.WHATSAPP_SEND -> whatsappSend(args)
        CommunicationPlusToolDefinitions.WHATSAPP_SEARCH -> whatsappSearch(args)
        CommunicationPlusToolDefinitions.X_POST -> xPost(args)
        CommunicationPlusToolDefinitions.X_REPLY -> xReply(args)
        CommunicationPlusToolDefinitions.X_DM -> xDm(args)
        CommunicationPlusToolDefinitions.X_SEARCH -> xSearch(args)
        CommunicationPlusToolDefinitions.VOICE_CALL_START -> voiceCallStart(args)
        CommunicationPlusToolDefinitions.VOICE_CALL_STATUS -> voiceCallStatus()
        else -> ToolExecutionResult.error("Unknown tool: $toolName")
    }

    private suspend fun discordSend(args: Map<String, Any?>): ToolExecutionResult = withContext(Dispatchers.IO) {
        val r = DiscordResult(success = false, error = "Discord integration requires Discord API configuration.")
        ToolExecutionResult.success(r, json.encodeToString(DiscordResult.serializer(), r))
    }

    private suspend fun discordRead(args: Map<String, Any?>): ToolExecutionResult = withContext(Dispatchers.IO) {
        val r = DiscordReadResult(success = false, messages = emptyList(), error = "Discord integration requires Discord API configuration.")
        ToolExecutionResult.success(r, json.encodeToString(DiscordReadResult.serializer(), r))
    }

    private suspend fun discordReact(args: Map<String, Any?>): ToolExecutionResult = withContext(Dispatchers.IO) {
        val r = DiscordResult(success = false, error = "Discord integration requires Discord API configuration.")
        ToolExecutionResult.success(r, json.encodeToString(DiscordResult.serializer(), r))
    }

    private suspend fun slackSend(args: Map<String, Any?>): ToolExecutionResult = withContext(Dispatchers.IO) {
        val r = SlackResult(success = false, error = "Slack integration requires Slack API configuration.")
        ToolExecutionResult.success(r, json.encodeToString(SlackResult.serializer(), r))
    }

    private suspend fun slackRead(args: Map<String, Any?>): ToolExecutionResult = withContext(Dispatchers.IO) {
        val r = SlackReadResult(success = false, messages = emptyList(), error = "Slack integration requires Slack API configuration.")
        ToolExecutionResult.success(r, json.encodeToString(SlackReadResult.serializer(), r))
    }

    private suspend fun slackPin(args: Map<String, Any?>): ToolExecutionResult = withContext(Dispatchers.IO) {
        val r = SlackResult(success = false, error = "Slack integration requires Slack API configuration.")
        ToolExecutionResult.success(r, json.encodeToString(SlackResult.serializer(), r))
    }

    private suspend fun whatsappSend(args: Map<String, Any?>): ToolExecutionResult = withContext(Dispatchers.IO) {
        val recipient = args["recipient"] as? String ?: return@withContext ToolExecutionResult.error("Missing 'recipient'")
        val message = args["message"] as? String ?: return@withContext ToolExecutionResult.error("Missing 'message'")
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse("https://api.whatsapp.com/send?phone=$recipient&text=${Uri.encode(message)}")
            }
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            val r = WhatsAppResult(success = true)
            ToolExecutionResult.success(r, json.encodeToString(WhatsAppResult.serializer(), r))
        } catch (e: Exception) {
            val r = WhatsAppResult(success = false, error = "WhatsApp not installed: ${e.message}")
            ToolExecutionResult.success(r, json.encodeToString(WhatsAppResult.serializer(), r))
        }
    }

    private suspend fun whatsappSearch(args: Map<String, Any?>): ToolExecutionResult = withContext(Dispatchers.IO) {
        val r = WhatsAppSearchResult(success = false, messages = emptyList(), error = "WhatsApp message search requires ContentProvider access.")
        ToolExecutionResult.success(r, json.encodeToString(WhatsAppSearchResult.serializer(), r))
    }

    private suspend fun xPost(args: Map<String, Any?>): ToolExecutionResult = withContext(Dispatchers.IO) {
        val r = XResult(success = false, error = "X/Twitter integration requires API configuration.")
        ToolExecutionResult.success(r, json.encodeToString(XResult.serializer(), r))
    }

    private suspend fun xReply(args: Map<String, Any?>): ToolExecutionResult = withContext(Dispatchers.IO) {
        val r = XResult(success = false, error = "X/Twitter integration requires API configuration.")
        ToolExecutionResult.success(r, json.encodeToString(XResult.serializer(), r))
    }

    private suspend fun xDm(args: Map<String, Any?>): ToolExecutionResult = withContext(Dispatchers.IO) {
        val r = XResult(success = false, error = "X/Twitter integration requires API configuration.")
        ToolExecutionResult.success(r, json.encodeToString(XResult.serializer(), r))
    }

    private suspend fun xSearch(args: Map<String, Any?>): ToolExecutionResult = withContext(Dispatchers.IO) {
        val r = XSearchResult(success = false, tweets = emptyList(), error = "X/Twitter integration requires API configuration.")
        ToolExecutionResult.success(r, json.encodeToString(XSearchResult.serializer(), r))
    }

    private suspend fun voiceCallStart(args: Map<String, Any?>): ToolExecutionResult = withContext(Dispatchers.IO) {
        val recipient = args["recipient"] as? String ?: return@withContext ToolExecutionResult.error("Missing 'recipient'")
        try {
            if (context.checkSelfPermission(android.Manifest.permission.CALL_PHONE) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                val dialIntent = Intent(Intent.ACTION_DIAL).apply {
                    data = Uri.parse("tel:$recipient")
                }
                context.startActivity(dialIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                val r = VoiceCallResult(success = true, status = "dialing", error = "CALL_PHONE permission not granted. Opened dialer instead — user must press call button.")
                return@withContext ToolExecutionResult.success(r, json.encodeToString(VoiceCallResult.serializer(), r))
            }
            val intent = Intent(Intent.ACTION_CALL).apply {
                data = Uri.parse("tel:$recipient")
            }
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            val r = VoiceCallResult(success = true, status = "initiated")
            ToolExecutionResult.success(r, json.encodeToString(VoiceCallResult.serializer(), r))
        } catch (e: Exception) {
            val r = VoiceCallResult(success = false, status = "failed", error = e.message)
            ToolExecutionResult.success(r, json.encodeToString(VoiceCallResult.serializer(), r))
        }
    }

    private suspend fun voiceCallStatus(): ToolExecutionResult = withContext(Dispatchers.IO) {
        try {
            val telephonyManager = context.getSystemService(android.content.Context.TELEPHONY_SERVICE) as? android.telephony.TelephonyManager
            if (telephonyManager == null) {
                val r = VoiceCallResult(success = false, status = "unknown", error = "TelephonyManager not available")
                return@withContext ToolExecutionResult.success(r, json.encodeToString(VoiceCallResult.serializer(), r))
            }
            val callState = when (getCallStateForSdk(telephonyManager)) {
                android.telephony.TelephonyManager.CALL_STATE_IDLE -> "idle"
                android.telephony.TelephonyManager.CALL_STATE_RINGING -> "ringing"
                android.telephony.TelephonyManager.CALL_STATE_OFFHOOK -> "in_call"
                else -> "unknown"
            }
            val r = VoiceCallResult(success = true, status = callState)
            ToolExecutionResult.success(r, json.encodeToString(VoiceCallResult.serializer(), r))
        } catch (e: SecurityException) {
            val r = VoiceCallResult(success = false, status = "unknown", error = "Permission denied: ${e.message}")
            ToolExecutionResult.success(r, json.encodeToString(VoiceCallResult.serializer(), r))
        } catch (e: Exception) {
            val r = VoiceCallResult(success = false, status = "unknown", error = e.message)
            ToolExecutionResult.success(r, json.encodeToString(VoiceCallResult.serializer(), r))
        }
    }

    private fun getCallStateForSdk(tm: android.telephony.TelephonyManager): Int {
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            tm.getCallState()
        } else {
            @Suppress("DEPRECATION")
            tm.callState
        }
    }
}