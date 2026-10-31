// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
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

    /**
     * Send a WhatsApp message.
     *
     * Two mechanisms, and the result says which one ran. With the accessibility
     * service available this drives the compose screen, taps send, and then
     * reads the message back off the conversation — only that produces
     * `sent = true`. Without it, WhatsApp is opened with the message pre-filled
     * and the result says `drafted = true, sent = false`, because a human still
     * has to tap send.
     *
     * The old version returned `success = true` the moment the intent fired.
     * That was issue 174: it reported a send that never happened.
     */
    private suspend fun whatsappSend(args: Map<String, Any?>): ToolExecutionResult = withContext(Dispatchers.IO) {
        val recipient = (args["recipient"] as? String)?.trim()
            ?: return@withContext ToolExecutionResult.error("Missing 'recipient'")
        val message = args["message"] as? String
            ?: return@withContext ToolExecutionResult.error("Missing 'message'")

        if (message.isBlank()) {
            return@withContext failWhatsApp("Message is empty. Nothing was sent.")
        }

        if (!WhatsAppAutomation.isInstalled(context)) {
            return@withContext failWhatsApp("WhatsApp is not installed on this device.")
        }

        // Resolve a name to a number before touching WhatsApp. The parameter
        // promises to accept either and the old code honoured only one.
        val resolved = WhatsAppAutomation.resolveNumber(context, recipient)
        val digits = when (resolved) {
            is WhatsAppAutomation.ResolvedRecipient.Number -> resolved.digits
            is WhatsAppAutomation.ResolvedRecipient.NotFound -> return@withContext failWhatsApp(
                "No contact or number matched \"$recipient\". Give a phone number in international format, or a name that exists in Contacts."
            )
            is WhatsAppAutomation.ResolvedRecipient.Ambiguous -> return@withContext failWhatsApp(
                "Multiple contacts match \"$recipient\": ${resolved.candidates.joinToString(", ")}. Be more specific."
            )
            is WhatsAppAutomation.ResolvedRecipient.NoContactsAccess -> return@withContext failWhatsApp(
                "Cannot resolve \"$recipient\" to a number because READ_CONTACTS is not granted. Grant it in Settings > Apps > GURU > Permissions, or pass a phone number directly."
            )
        }

        val launched = WhatsAppAutomation.launchCompose(context, digits, message)
        if (!launched) {
            return@withContext failWhatsApp("Could not open WhatsApp. It is installed but the compose intent was refused.")
        }

        // Try to drive the send. This is the only path that can produce a real send.
        when (val attempt = WhatsAppAutomation.attemptSend(message)) {
            is WhatsAppAutomation.SendAttempt.Sent -> {
                val r = WhatsAppResult(
                    success = true,
                    sent = true,
                    drafted = true,
                    mechanism = "accessibility",
                    recipient = recipient,
                    resolvedNumber = digits,
                    action = "sent",
                    detail = "Message sent and confirmed in the conversation.",
                )
                ToolExecutionResult.success(r, json.encodeToString(WhatsAppResult.serializer(), r))
            }

            is WhatsAppAutomation.SendAttempt.NoAccessibility -> {
                val r = WhatsAppResult(
                    success = true,
                    sent = false,
                    drafted = true,
                    mechanism = "intent",
                    recipient = recipient,
                    resolvedNumber = digits,
                    action = "drafted",
                    detail = "WhatsApp is open with the message ready. Nothing has been sent — a human must tap send. To let GURU send it, enable the accessibility service in Settings > Accessibility > Installed apps > GURU.",
                )
                ToolExecutionResult.success(r, json.encodeToString(WhatsAppResult.serializer(), r))
            }

            is WhatsAppAutomation.SendAttempt.DraftNotVisible -> {
                val r = WhatsAppResult(
                    success = true,
                    sent = false,
                    drafted = false,
                    mechanism = "intent",
                    recipient = recipient,
                    resolvedNumber = digits,
                    action = "compose_opened",
                    detail = "WhatsApp opened but the message text was not found in an input field, so nothing was tapped. Check the screen and try again.",
                )
                ToolExecutionResult.success(r, json.encodeToString(WhatsAppResult.serializer(), r))
            }

            is WhatsAppAutomation.SendAttempt.SendButtonNotFound -> {
                val r = WhatsAppResult(
                    success = false,
                    sent = false,
                    drafted = true,
                    mechanism = "accessibility",
                    recipient = recipient,
                    resolvedNumber = digits,
                    action = "draft_only",
                    error = "Send control not found on the compose screen.",
                    detail = "The message is drafted but GURU could not find the send button. A human must tap send.",
                )
                ToolExecutionResult.success(r, json.encodeToString(WhatsAppResult.serializer(), r))
            }

            is WhatsAppAutomation.SendAttempt.TappedButUnconfirmed -> {
                val r = WhatsAppResult(
                    success = false,
                    sent = false,
                    drafted = true,
                    mechanism = "accessibility",
                    recipient = recipient,
                    resolvedNumber = digits,
                    action = "unconfirmed",
                    error = "Send was tapped but the message was not found in the conversation afterwards.",
                    detail = "This is not a confirmed send. Check WhatsApp before assuming it went out.",
                )
                ToolExecutionResult.success(r, json.encodeToString(WhatsAppResult.serializer(), r))
            }
        }
    }

    private fun failWhatsApp(reason: String): ToolExecutionResult {
        val r = WhatsAppResult(success = false, sent = false, drafted = false, error = reason)
        return ToolExecutionResult.success(r, json.encodeToString(WhatsAppResult.serializer(), r))
    }

    /**
     * Search WhatsApp.
     *
     * Drives WhatsApp's own search field through the accessibility service and
     * returns what is on screen. `available` distinguishes "searched, found
     * nothing" from "could not search", which the old hardcoded failure did not.
     */
    private suspend fun whatsappSearch(args: Map<String, Any?>): ToolExecutionResult = withContext(Dispatchers.IO) {
        val query = (args["query"] as? String)?.trim()
            ?: return@withContext ToolExecutionResult.error("Missing 'query'")
        val limit = (args["limit"] as? Number)?.toInt() ?: 20

        if (query.isEmpty()) {
            return@withContext failWhatsAppSearch("Query is empty. Nothing was searched.")
        }

        if (!WhatsAppAutomation.isInstalled(context)) {
            return@withContext failWhatsAppSearch("WhatsApp is not installed on this device.")
        }

        when (val attempt = WhatsAppAutomation.search(context, query, limit)) {
            is WhatsAppAutomation.SearchAttempt.Results -> {
                val messages = attempt.lines.mapIndexed { index, line ->
                    WhatsAppMessage(
                        id = "visible_$index",
                        sender = "",
                        content = line,
                        timestamp = "",
                    )
                }
                val r = WhatsAppSearchResult(
                    success = true,
                    available = true,
                    messages = messages,
                    mechanism = "accessibility",
                    detail = "Read from WhatsApp's search results on screen. Sender and timestamp are not exposed by the search surface.",
                )
                ToolExecutionResult.success(r, json.encodeToString(WhatsAppSearchResult.serializer(), r))
            }

            is WhatsAppAutomation.SearchAttempt.NoResults -> {
                val r = WhatsAppSearchResult(
                    success = true,
                    available = true,
                    messages = emptyList(),
                    mechanism = "accessibility",
                    detail = "WhatsApp searched and returned no results for \"${attempt.query}\".",
                )
                ToolExecutionResult.success(r, json.encodeToString(WhatsAppSearchResult.serializer(), r))
            }

            is WhatsAppAutomation.SearchAttempt.NoAccessibility -> failWhatsAppSearch(
                "Cannot search WhatsApp because the accessibility service is not running. Enable it in Settings > Accessibility > Installed apps > GURU. This is a grant problem, not an empty inbox."
            )

            is WhatsAppAutomation.SearchAttempt.NotInstalled -> failWhatsAppSearch(
                "WhatsApp is not installed on this device."
            )

            is WhatsAppAutomation.SearchAttempt.SearchFieldNotFound -> failWhatsAppSearch(
                "WhatsApp opened but its search field could not be found. The app layout may have changed, or a different screen is in front."
            )

            is WhatsAppAutomation.SearchAttempt.QueryNotEntered -> failWhatsAppSearch(
                "The search field was found but the query could not be typed into it."
            )
        }
    }

    private fun failWhatsAppSearch(reason: String): ToolExecutionResult {
        val r = WhatsAppSearchResult(success = false, available = false, messages = emptyList(), error = reason)
        return ToolExecutionResult.success(r, json.encodeToString(WhatsAppSearchResult.serializer(), r))
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