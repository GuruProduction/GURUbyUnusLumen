// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.tools.phone

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.provider.CallLog
import android.provider.Telephony
import android.telecom.TelecomManager
import android.telephony.SmsManager
import android.telephony.SmsMessage
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import com.unuslumen.app.data.hooks.HookEventBus
import com.unuslumen.app.data.tools.registry.ToolDefinition
import com.unuslumen.app.data.tools.registry.ToolExecutionResult
import com.unuslumen.app.data.tools.registry.ToolExecutor
import com.unuslumen.app.data.tools.registry.ToolParameter
import com.unuslumen.app.data.tools.registry.ToolParameterType
import com.unuslumen.app.data.tools.registry.ToolResultData
import com.unuslumen.app.data.tools.registry.ToolResultExtractor
import com.unuslumen.app.data.tools.registry.ToolSetRegistration
import com.unuslumen.app.data.tools.registry.permission.DomainToolModule
import com.unuslumen.app.data.tools.registry.permission.PermissionGrants
import com.unuslumen.app.data.tools.registry.permission.TeamThreePermissions
import com.unuslumen.app.domain.model.HookEventType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.koin.core.module.Module
import org.koin.dsl.module
import java.util.concurrent.atomic.AtomicReference
import kotlin.reflect.KClass

/**
 * Phone, SMS and calls: the fifteen permissions and thirteen tools in issues #49
 * to #63.
 *
 * THE RULE THIS DOMAIN IS BUILT ON. A call that was not placed, a message that was
 * not sent, and a broadcast that was not read are all reported as failures naming
 * the exact thing that is missing. Nothing here returns success because an intent
 * was fired or because an API accepted a request; only what the platform confirms
 * counts, and where a platform cannot confirm, the result says what it does know.
 *
 * THE THREE PRIVILEGED TOOLS ARE HONEST ABOUT IT. READ_PRECISE_PHONE_STATE is
 * signature|privileged and no dialog can grant it. ACCEPT_HANDOVER needs a
 * ConnectionService GURU does not declare. RECEIVE_WAP_PUSH is delivered only to the
 * default messaging app. Each reports that in one sentence rather than attempting a
 * call that Android will refuse underneath.
 */

// ----------------------------------------------------------------------
// Results
// ----------------------------------------------------------------------

@Serializable
data class CallPlacedResult(
    val success: Boolean,
    /** PENDING_CONFIRMATION, DIALED, or FAILED. Never "connected": a call takes time to answer. */
    val status: String,
    val number: String? = null,
    /** True only when ACTION_CALL was really issued with CALL_PHONE held. */
    val dialled: Boolean = false,
    /** True when no call was placed and the dialler was opened for the person instead. */
    val dialerOpened: Boolean = false,
    val needsConfirmation: Boolean = false,
    val callStateAfter: String? = null,
    val note: String? = null,
    val error: String? = null,
) : ToolResultData

@Serializable
data class CallControlResult(
    val success: Boolean,
    val action: String,
    /** The platform's call state read back after the action. */
    val callStateAfter: String? = null,
    /** Whether the role the platform requires for this action is held. */
    val roleHeld: Boolean = false,
    val note: String? = null,
    val error: String? = null,
) : ToolResultData

@Serializable
data class SimState(
    val slotIndex: Int = 0,
    val simState: String = "UNKNOWN",
    val carrier: String? = null,
    val number: String? = null,
    val roaming: Boolean = false,
) : ToolResultData

@Serializable
data class PhoneStateResult(
    val success: Boolean,
    val callState: String = "UNKNOWN",
    /** True when the platform reports an active call rather than merely a phone service. */
    val inCall: Boolean = false,
    val sims: List<SimState> = emptyList(),
    val simCount: Int = 0,
    val networkType: String? = null,
    val dataEnabled: Boolean? = null,
    val signalLevel: Int? = null,
    /** Why a field is absent, when it is absent for a reason worth stating. */
    val note: String? = null,
    val error: String? = null,
) : ToolResultData

@Serializable
data class PhoneNumbersResult(
    val success: Boolean,
    val numbers: List<SimState> = emptyList(),
    /** How many SIMs the platform reports, whether or not a number is stored on them. */
    val simCount: Int = 0,
    val note: String? = null,
    val error: String? = null,
) : ToolResultData

@Serializable
data class PrecisePhoneStateResult(
    val success: Boolean,
    val available: Boolean = false,
    val note: String? = null,
    val error: String? = null,
) : ToolResultData

@Serializable
data class OutgoingCallRecord(
    val number: String? = null,
    val cachedName: String? = null,
    val startedAtMillis: Long = 0,
    val durationSeconds: Long = 0,
) : ToolResultData

@Serializable
data class OutgoingCallWatchResult(
    val success: Boolean,
    val observed: List<OutgoingCallRecord> = emptyList(),
    val count: Int = 0,
    val windowSeconds: Int = 0,
    /** LIVE when the screening role is held and calls are intercepted as placed. */
    val mode: String = "OBSERVATION",
    val roleHeld: Boolean = false,
    val note: String? = null,
    val error: String? = null,
) : ToolResultData

@Serializable
data class SipStatusResult(
    val success: Boolean,
    val sipApiPresent: Boolean = false,
    val accounts: List<String> = emptyList(),
    val note: String = "",
    val error: String? = null,
) : ToolResultData

@Serializable
data class CallAccountResult(
    val success: Boolean,
    val registered: Boolean = false,
    val note: String = "",
    val error: String? = null,
) : ToolResultData

@Serializable
data class SmsMessageView(
    val id: Long = 0,
    val address: String = "",
    val body: String = "",
    val dateMillis: Long = 0,
    val type: String = "unknown",
    val read: Boolean = false,
    val threadId: Long = 0,
) : ToolResultData

@Serializable
data class SmsReadResult(
    val success: Boolean,
    val messages: List<SmsMessageView> = emptyList(),
    val count: Int = 0,
    /** Says plainly whether an empty list means no messages or a window with none. */
    val scope: String = "",
    val error: String? = null,
) : ToolResultData

@Serializable
data class SmsSendResult(
    val success: Boolean,
    /** PENDING_CONFIRMATION, SENT, or FAILED. */
    val status: String,
    val recipient: String? = null,
    /** How many SMS parts the platform split the body into. Zero when nothing was sent. */
    val parts: Int = 0,
    val needsConfirmation: Boolean = false,
    val note: String? = null,
    val error: String? = null,
) : ToolResultData

@Serializable
data class SmsWatchResult(
    val success: Boolean,
    val watching: Boolean = false,
    val registeredForSms: Boolean = false,
    val registeredForMms: Boolean = false,
    val received: List<SmsMessageView> = emptyList(),
    val windowSeconds: Int = 0,
    val note: String = "",
    val error: String? = null,
) : ToolResultData

@Serializable
data class WapPushResult(
    val success: Boolean,
    val available: Boolean = false,
    val note: String = "",
    val error: String? = null,
) : ToolResultData

@Serializable
data class CellBroadcastResult(
    val success: Boolean,
    val available: Boolean = false,
    val messages: List<String> = emptyList(),
    val note: String = "",
    val error: String? = null,
) : ToolResultData

// ----------------------------------------------------------------------
// Shared call placement, so voiceCallStart and phoneCall are one implementation
// ----------------------------------------------------------------------

/**
 * Placing a call, in one place.
 *
 * Issue #49 asks that voiceCallStart be extended rather than duplicated, so both
 * tools come through here. A second copy of "check the grant, fire the intent" is a
 * second place for the grant check to be forgotten, and that is exactly what had
 * happened: voiceCallStart checked CALL_PHONE with a raw checkSelfPermission and
 * reported success while only opening the dialler, which reads as a placed call.
 */
object PhoneCalls {

    /**
     * Dial a number, or refuse and explain.
     *
     * [confirmed] gates ACTION_CALL. Without it the tool opens the dialler with the
     * number filled in, which is the one useful thing that never dials behind
     * somebody's back, and says so.
     */
    fun place(context: Context, number: String, confirmed: Boolean): ToolExecutionResult {
        val normalised = number.trim().replace(" ", "").replace("-", "")
        if (normalised.isEmpty()) {
            return result(
                CallPlacedResult(
                    success = false,
                    status = "FAILED",
                    error = "No number was given, so nothing was dialled.",
                )
            )
        }

        val check = PermissionGrants.check(context, TeamThreePermissions.CALL_PHONE)
        if (check.denied) {
            // The dialler path needs no grant, so it is offered rather than refused
            // outright: a person who has not granted CALL_PHONE can still be handed a
            // filled-in dialler, and that is a real outcome, not a failure dressed up.
            val opened = runCatching {
                context.startActivity(
                    Intent(Intent.ACTION_DIAL, Uri.parse("tel:$normalised"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                true
            }.getOrDefault(false)
            return result(
                CallPlacedResult(
                    success = false,
                    status = "FAILED",
                    number = normalised,
                    dialerOpened = opened,
                    note = if (opened) {
                        "No call was placed. The dialler was opened with the number filled in, " +
                            "so a person still has to press call."
                    } else {
                        "No call was placed and the dialler could not be opened either."
                    },
                    error = check.sentence(),
                )
            )
        }

        if (!confirmed) {
            return result(
                CallPlacedResult(
                    success = false,
                    status = "PENDING_CONFIRMATION",
                    number = normalised,
                    needsConfirmation = true,
                    note = "Placing a call is outbound and cannot be taken back once it rings, so it " +
                        "needs an explicit yes. Call again with confirmed = true to dial $normalised.",
                )
            )
        }

        return try {
            context.startActivity(
                Intent(Intent.ACTION_CALL, Uri.parse("tel:$normalised"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            // ACTION_CALL only asks the platform to start a call. The call state is a
            // separate fact and is reported separately rather than assumed from intent
            // success.
            val state = callStateName(context)
            result(
                CallPlacedResult(
                    success = true,
                    status = "DIALED",
                    number = normalised,
                    dialled = true,
                    callStateAfter = state,
                    note = if (state == "IDLE") {
                        "The call intent was accepted but the platform still reports IDLE. A SIM that " +
                            "cannot register, or a device whose dialler is not the default, produces this."
                    } else {
                        "The call was placed and the platform reports $state."
                    },
                )
            )
        } catch (e: SecurityException) {
            PermissionGrants.refusal(PermissionGrants.check(context, TeamThreePermissions.CALL_PHONE))
        } catch (e: Exception) {
            result(
                CallPlacedResult(
                    success = false,
                    status = "FAILED",
                    number = normalised,
                    error = "The call could not be placed: ${e.message}",
                )
            )
        }
    }

    fun callStateName(context: Context): String {
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            ?: return "UNKNOWN"
        return when (readCallState(tm)) {
            TelephonyManager.CALL_STATE_IDLE -> "IDLE"
            TelephonyManager.CALL_STATE_RINGING -> "RINGING"
            TelephonyManager.CALL_STATE_OFFHOOK -> "OFFHOOK"
            else -> "UNKNOWN"
        }
    }

    fun readCallState(tm: TelephonyManager): Int = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) tm.callState else {
            @Suppress("DEPRECATION") tm.callState
        }
    }.getOrDefault(TelephonyManager.CALL_STATE_IDLE)

    /**
     * Serialise a call result, and set the success flag from the payload rather than
     * from the fact that serialising worked.
     *
     * THIS WAS A REAL BUG FOUND BY A TEST, and it is the same defect the executor's
     * own [PhoneToolExecutor.result] was fixed for. `ToolExecutionResult.success(...)`
     * is a factory that unconditionally reports success, so every path that came
     * through here claimed one: a call left at PENDING_CONFIRMATION because nobody
     * had confirmed it, and a call that was never placed because CALL_PHONE was
     * missing and only the dialler opened. A caller could not tell a placed call from
     * a refusal, which is exactly what this batch exists to remove.
     *
     * The flag now follows the payload's own success field and the error travels with
     * it, so an unconfirmed call is a failure that says why.
     */
    private fun result(value: CallPlacedResult): ToolExecutionResult = ToolExecutionResult(
        rawJson = JSON.encodeToString(CallPlacedResult.serializer(), value),
        resultData = value,
        resultObject = null,
        success = value.success,
        error = value.error,
    )

    internal val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = true }
}

// ----------------------------------------------------------------------
// Definitions
// ----------------------------------------------------------------------

object PhoneToolDefinitions : ToolSetRegistration {
    const val PHONE_CALL = "phoneCall"
    const val ANSWER_CALL = "answerCall"
    const val END_CALL = "endCall"
    const val PHONE_STATE = "phoneState"
    const val PHONE_NUMBERS = "phoneNumbers"
    const val PRECISE_PHONE_STATE = "precisePhoneState"
    const val OUTGOING_CALL_WATCH = "outgoingCallWatch"
    const val SIP_STATUS = "sipStatus"
    const val REGISTER_CALL_ACCOUNT = "registerCallAccount"
    const val HANDOVER_CALL = "handoverCall"
    const val SMS_READ = "smsRead"
    const val SMS_SEND = "smsSend"
    const val SMS_WATCH = "smsWatch"
    const val WAP_PUSH = "wapPush"
    const val CELL_BROADCASTS = "cellBroadcasts"

    override val definitions = listOf(
        ToolDefinition(
            name = PHONE_CALL,
            description = "Place a phone call to a number. This supersedes voiceCallStart, which shared " +
                "the same hole: it reported success while only opening the dialler. Without the Phone " +
                "grant the dialler is opened with the number filled in and the result says plainly that " +
                "no call was placed. Placing a call is outbound and irreversible once it rings, so it " +
                "refuses until called again with confirmed = true.",
            category = "phone",
            parameters = listOf(
                ToolParameter("number", ToolParameterType.String, true, "The number to dial, in any format the dialler accepts."),
                ToolParameter("confirmed", ToolParameterType.Boolean, false, "Must be true to dial. Default false, which refuses."),
            ),
            permissions = listOf(TeamThreePermissions.CALL_PHONE.permission),
        ),
        ToolDefinition(
            name = ANSWER_CALL,
            description = "Answer the call that is ringing now. Needs the Phone grant and, on most builds, " +
                "the default-dialler or call-screening role; the result reports which of those is held. " +
                "It never claims a call was answered without reading the call state back afterwards.",
            category = "phone",
            parameters = emptyList(),
            permissions = listOf(TeamThreePermissions.ANSWER_PHONE_CALLS.permission),
        ),
        ToolDefinition(
            name = END_CALL,
            description = "End the call in progress. Needs the Phone grant; ending a call through Telecom " +
                "additionally wants the default-dialler role on some builds, which the result reports. " +
                "Reads the call state back rather than assuming the call dropped.",
            category = "phone",
            parameters = emptyList(),
            permissions = listOf(TeamThreePermissions.ANSWER_PHONE_CALLS.permission),
        ),
        ToolDefinition(
            name = PHONE_STATE,
            description = "Read the live phone state: whether a call is ringing, active or idle, each SIM's " +
                "state and carrier, the data network type, roaming, and the signal level. Use this rather " +
                "than guessing from a failed call. Every field is read from the platform; a field the " +
                "platform will not give is absent and the note says why, never filled with a placeholder.",
            category = "phone",
            parameters = emptyList(),
            permissions = listOf(TeamThreePermissions.READ_PHONE_STATE.permission),
        ),
        ToolDefinition(
            name = PHONE_NUMBERS,
            description = "Read the numbers assigned to this device's SIMs, one per SIM. A SIM whose carrier " +
                "never wrote a number is reported as having none, which is a different answer from a " +
                "refusal and from an empty list. GURU never invents a number.",
            category = "phone",
            parameters = emptyList(),
            permissions = listOf(TeamThreePermissions.READ_PHONE_NUMBERS.permission),
        ),
        ToolDefinition(
            name = PRECISE_PHONE_STATE,
            description = "Report per-subscription call state, precise signal metrics and IMS registration. " +
                "This is a signature permission that no dialog can grant, so on a normal install it " +
                "always reports the privileged dependency and why. It is listed so the capability is " +
                "visible rather than silently missing.",
            category = "phone",
            parameters = emptyList(),
            permissions = listOf(TeamThreePermissions.READ_PRECISE_PHONE_STATE.permission),
        ),
        ToolDefinition(
            name = OUTGOING_CALL_WATCH,
            description = "Watch for outgoing calls placed during a bounded window. LIVE mode intercepts " +
                "them as they are placed and needs the call-screening role; without that role it falls " +
                "back to OBSERVATION, reading the call log for new outgoing entries, which reports what " +
                "happened rather than catching it in flight. The result names which mode ran, because " +
                "those are different guarantees and conflating them would overstate what GURU saw.",
            category = "phone",
            parameters = listOf(
                ToolParameter("windowSeconds", ToolParameterType.Integer, false, "How long to watch, 5 to 120 seconds. Default 15."),
            ),
            permissions = listOf(TeamThreePermissions.PROCESS_OUTGOING_CALLS.permission),
        ),
        ToolDefinition(
            name = SIP_STATUS,
            description = "Report whether this platform carries a SIP stack and any registered accounts. The " +
                "platform SIP API was removed from Android in API 31, so on any current phone the honest " +
                "answer is that there is no SIP stack to query. Most phones have no SIP account whether " +
                "or not the platform supports it, and an empty configuration is reported as empty rather " +
                "than as an error.",
            category = "phone",
            parameters = emptyList(),
            permissions = listOf(TeamThreePermissions.USE_SIP.permission),
        ),
        ToolDefinition(
            name = REGISTER_CALL_ACCOUNT,
            description = "Register GURU as a calling account so calls can be placed through the system " +
                "dialler. Registering without a ConnectionService would put a dead account in the " +
                "dialler that fails the moment somebody chooses it, so this refuses and names what is " +
                "missing rather than registering something broken. The permission itself needs no dialog.",
            category = "phone",
            parameters = emptyList(),
            permissions = listOf(TeamThreePermissions.MANAGE_OWN_CALLS.permission),
        ),
        ToolDefinition(
            name = HANDOVER_CALL,
            description = "Accept a call handed over from another app or device. A handover needs an active " +
                "call delivered to GURU's own ConnectionService, which this build does not declare, so " +
                "this reports that dependency and never claims a call was taken over.",
            category = "phone",
            parameters = emptyList(),
            permissions = listOf(TeamThreePermissions.ACCEPT_HANDOVER.permission),
        ),
        ToolDefinition(
            name = SMS_READ,
            description = "Read text messages, filterable by sender substring, with a limit and an optional " +
                "look-back in days. Returns sender, body, time, folder and read state. A denial is a " +
                "refusal naming the SMS grant, never an empty list: \"no messages matched\" and \"I cannot " +
                "read your messages\" are different answers and this tool keeps them apart.",
            category = "sms",
            parameters = listOf(
                ToolParameter("from", ToolParameterType.String, false, "Only messages whose sender contains this text."),
                ToolParameter("limit", ToolParameterType.Integer, false, "Maximum messages, 1 to 100. Default 20."),
                ToolParameter("days", ToolParameterType.Integer, false, "Only messages from the last N days. Omit for all."),
            ),
            permissions = listOf(TeamThreePermissions.READ_SMS.permission),
        ),
        ToolDefinition(
            name = SMS_SEND,
            description = "Send a text message to a number, splitting multipart bodies with the platform's " +
                "own divider. Sending cannot be undone once it leaves, so this refuses until called again " +
                "with confirmed = true and says what to pass. It reports the parts the platform accepted, " +
                "never a bare success, and a send that threw is a failure naming the reason.",
            category = "sms",
            parameters = listOf(
                ToolParameter("number", ToolParameterType.String, true, "Destination number."),
                ToolParameter("message", ToolParameterType.String, true, "The text to send."),
                ToolParameter("confirmed", ToolParameterType.Boolean, false, "Must be true to send. Default false, which refuses."),
            ),
            permissions = listOf(TeamThreePermissions.SEND_SMS.permission),
        ),
        ToolDefinition(
            name = SMS_WATCH,
            description = "Listen for incoming text and picture messages for a bounded window and report " +
                "what arrived, firing a hook event for each so automations can act on a sender. The " +
                "receiver is registered at runtime, so it only hears messages while GURU is alive; the " +
                "result says that rather than implying a permanent inbox listener.",
            category = "sms",
            parameters = listOf(
                ToolParameter("windowSeconds", ToolParameterType.Integer, false, "How long to listen, 5 to 300 seconds. Default 30."),
            ),
            permissions = listOf(
                TeamThreePermissions.RECEIVE_SMS.permission,
                TeamThreePermissions.RECEIVE_MMS.permission,
            ),
        ),
        ToolDefinition(
            name = WAP_PUSH,
            description = "Report whether GURU can receive WAP push messages. The platform delivers these " +
                "only to the default messaging app, so a sideloaded app cannot receive them whatever " +
                "grant it holds, and modern carriers send the same content as ordinary SMS instead. " +
                "Reports that plainly rather than registering a receiver that will never fire.",
            category = "sms",
            parameters = emptyList(),
            permissions = listOf(TeamThreePermissions.RECEIVE_WAP_PUSH.permission),
        ),
        ToolDefinition(
            name = CELL_BROADCASTS,
            description = "Report whether GURU can read emergency and cell broadcast alerts. The permission " +
                "is grantable, but Android exposes no public API for a third-party app to read the broadcast " +
                "database, so this states that limit instead of returning an empty alert list that would " +
                "look like a quiet day. Reading them needs shell-UID access through Shizuku.",
            category = "sms",
            parameters = emptyList(),
            permissions = listOf(TeamThreePermissions.READ_CELL_BROADCASTS.permission),
        ),
    )

    override fun executorClass(): KClass<out ToolExecutor> = PhoneToolExecutor::class
    override fun extractorClass(): KClass<out ToolResultExtractor>? = null
}

// ----------------------------------------------------------------------
// Executor
// ----------------------------------------------------------------------

class PhoneToolExecutor(private val context: Context) : ToolExecutor {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    override suspend fun execute(toolName: String, args: Map<String, Any?>): ToolExecutionResult = when (toolName) {
        PhoneToolDefinitions.PHONE_CALL -> phoneCall(args)
        PhoneToolDefinitions.ANSWER_CALL -> answerCall()
        PhoneToolDefinitions.END_CALL -> endCall()
        PhoneToolDefinitions.PHONE_STATE -> phoneState()
        PhoneToolDefinitions.PHONE_NUMBERS -> phoneNumbers()
        PhoneToolDefinitions.PRECISE_PHONE_STATE -> precisePhoneState()
        PhoneToolDefinitions.OUTGOING_CALL_WATCH -> outgoingCallWatch(args)
        PhoneToolDefinitions.SIP_STATUS -> sipStatus()
        PhoneToolDefinitions.REGISTER_CALL_ACCOUNT -> registerCallAccount()
        PhoneToolDefinitions.HANDOVER_CALL -> handoverCall()
        PhoneToolDefinitions.SMS_READ -> smsRead(args)
        PhoneToolDefinitions.SMS_SEND -> smsSend(args)
        PhoneToolDefinitions.SMS_WATCH -> smsWatch(args)
        PhoneToolDefinitions.WAP_PUSH -> wapPush()
        PhoneToolDefinitions.CELL_BROADCASTS -> cellBroadcasts()
        else -> ToolExecutionResult.error("Unknown tool: $toolName")
    }

    // ------------------------------------------------------------------
    // Calls
    // ------------------------------------------------------------------

    private fun phoneCall(args: Map<String, Any?>): ToolExecutionResult {
        val number = args["number"] as? String
            ?: return ToolExecutionResult.error("Missing 'number'")
        val confirmed = args["confirmed"] as? Boolean
            ?: (args["confirmed"] as? String)?.equals("true", ignoreCase = true)
            ?: false
        return PhoneCalls.place(context, number, confirmed)
    }

    private suspend fun answerCall(): ToolExecutionResult = withContext(Dispatchers.IO) {
        PermissionGrants.guard(context, TeamThreePermissions.ANSWER_PHONE_CALLS)?.let { return@withContext it }

        val telecom = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
            ?: return@withContext controlFailure(
                "answer", "This device has no Telecom service, so there is no call to answer."
            )

        val before = PhoneCalls.callStateName(context)
        if (before != "RINGING") {
            return@withContext result(
                CallControlResult(
                    success = false,
                    action = "answer",
                    callStateAfter = before,
                    roleHeld = holdsDialerRole(),
                    note = "Nothing is ringing, so there is nothing to answer.",
                    error = "The phone reports $before rather than RINGING.",
                )
            )
        }

        return@withContext try {
            @Suppress("DEPRECATION")
            telecom.acceptRingingCall()
            // The state is read back because acceptRingingCall returning normally only
            // means the request was accepted, not that the call was answered.
            delay(600)
            val after = PhoneCalls.callStateName(context)
            result(
                CallControlResult(
                    success = after == "OFFHOOK",
                    action = "answer",
                    callStateAfter = after,
                    roleHeld = holdsDialerRole(),
                    note = if (after == "OFFHOOK") {
                        "Answered; the platform reports the call off hook."
                    } else {
                        "The answer request was accepted but the call still reads as $after. The " +
                            "default-dialler role is what the platform needs for this on most builds."
                    },
                    error = if (after == "OFFHOOK") null else "The call state did not become OFFHOOK.",
                )
            )
        } catch (e: SecurityException) {
            PermissionGrants.refusal(PermissionGrants.check(context, TeamThreePermissions.ANSWER_PHONE_CALLS))
        } catch (e: Exception) {
            controlFailure("answer", "The call could not be answered: ${e.message}")
        }
    }

    private suspend fun endCall(): ToolExecutionResult = withContext(Dispatchers.IO) {
        PermissionGrants.guard(context, TeamThreePermissions.ANSWER_PHONE_CALLS)?.let { return@withContext it }

        val telecom = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
            ?: return@withContext controlFailure(
                "end", "This device has no Telecom service, so there is no call to end."
            )

        val before = PhoneCalls.callStateName(context)
        if (before == "IDLE") {
            return@withContext result(
                CallControlResult(
                    success = false,
                    action = "end",
                    callStateAfter = before,
                    roleHeld = holdsDialerRole(),
                    note = "No call is in progress, so there is nothing to end.",
                    error = "The phone reports IDLE.",
                )
            )
        }

        return@withContext try {
            val ended = runCatching { telecom.endCall() }.getOrDefault(false)
            delay(600)
            val after = PhoneCalls.callStateName(context)
            result(
                CallControlResult(
                    success = ended && after == "IDLE",
                    action = "end",
                    callStateAfter = after,
                    roleHeld = holdsDialerRole(),
                    note = if (after == "IDLE") {
                        "The call was ended; the platform reports the phone idle."
                    } else {
                        "endCall returned $ended and the call still reads as $after. Ending another " +
                            "app's call needs the default-dialler role on most builds."
                    },
                    error = if (after == "IDLE") null else "The call state did not become IDLE.",
                )
            )
        } catch (e: SecurityException) {
            PermissionGrants.refusal(PermissionGrants.check(context, TeamThreePermissions.ANSWER_PHONE_CALLS))
        } catch (e: Exception) {
            controlFailure("end", "The call could not be ended: ${e.message}")
        }
    }

    /** Whether GURU holds the default-dialler role, which several call actions need. */
    private fun holdsDialerRole(): Boolean = runCatching {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            val telecom = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
            telecom?.defaultDialerPackage == context.packageName
        } else {
            val roles = context.getSystemService(android.app.role.RoleManager::class.java)
            roles?.isRoleHeld(android.app.role.RoleManager.ROLE_DIALER) == true
        }
    }.getOrDefault(false)

    private fun controlFailure(action: String, message: String): ToolExecutionResult = result(
        CallControlResult(
            success = false,
            action = action,
            roleHeld = holdsDialerRole(),
            error = message,
        )
    )

    // ------------------------------------------------------------------
    // State and numbers
    // ------------------------------------------------------------------

    private suspend fun phoneState(): ToolExecutionResult = withContext(Dispatchers.IO) {
        PermissionGrants.guard(context, TeamThreePermissions.READ_PHONE_STATE)?.let { return@withContext it }

        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            ?: return@withContext result(
                PhoneStateResult(
                    success = false,
                    error = "This device has no telephony service, so it is not a phone: there is no " +
                        "call state, no SIM and no network to report.",
                )
            )

        val state = PhoneCalls.callStateName(context)
        val sims = readSims(tm)
        val notes = mutableListOf<String>()

        if (sims.isEmpty()) {
            notes.add("The platform reports no SIM slots at all.")
        }
        sims.filter { it.simState != "READY" }.forEach {
            notes.add("SIM ${it.slotIndex} is ${it.simState}, so its carrier-level fields are not populated.")
        }

        // The network type is mapped by hand because TelephonyManager carries no
        // public getNetworkTypeName in this SDK. The raw constant is kept alongside,
        // so a type this build does not know is reported as a number rather than
        // swallowed into "no network type".
        val rawNetworkType = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                tm.dataNetworkType
            } else {
                @Suppress("DEPRECATION") tm.networkType
            }
        }.getOrDefault(TelephonyManager.NETWORK_TYPE_UNKNOWN)

        val networkType = networkTypeName(rawNetworkType)

        val dataEnabled = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) tm.isDataEnabled else null
        }.getOrNull()

        val signal = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                tm.signalStrength?.level
            } else {
                null
            }
        }.getOrNull()

        val r = PhoneStateResult(
            success = true,
            callState = state,
            inCall = state == "OFFHOOK" || state == "RINGING",
            sims = sims,
            simCount = sims.size,
            networkType = networkType,
            dataEnabled = dataEnabled,
            signalLevel = signal,
            note = notes.takeIf { it.isNotEmpty() }?.joinToString(" "),
        )
        result(r)
    }

    /**
     * The radio access technology behind a network-type constant.
     *
     * The names are spelled out rather than fetched from the platform because there
     * is no public mapping function in this SDK. A constant this build has no name
     * for is reported as its number, which is still the truth; inventing a label
     * would not be.
     */
    private fun networkTypeName(type: Int): String = when (type) {
        TelephonyManager.NETWORK_TYPE_UNKNOWN -> "UNKNOWN"
        TelephonyManager.NETWORK_TYPE_GPRS -> "GPRS"
        TelephonyManager.NETWORK_TYPE_EDGE -> "EDGE"
        TelephonyManager.NETWORK_TYPE_UMTS -> "UMTS"
        TelephonyManager.NETWORK_TYPE_CDMA -> "CDMA"
        TelephonyManager.NETWORK_TYPE_EVDO_0 -> "EVDO_0"
        TelephonyManager.NETWORK_TYPE_EVDO_A -> "EVDO_A"
        TelephonyManager.NETWORK_TYPE_1xRTT -> "1xRTT"
        TelephonyManager.NETWORK_TYPE_HSDPA -> "HSDPA"
        TelephonyManager.NETWORK_TYPE_HSUPA -> "HSUPA"
        TelephonyManager.NETWORK_TYPE_HSPA -> "HSPA"
        TelephonyManager.NETWORK_TYPE_IDEN -> "IDEN"
        TelephonyManager.NETWORK_TYPE_EVDO_B -> "EVDO_B"
        TelephonyManager.NETWORK_TYPE_LTE -> "LTE"
        TelephonyManager.NETWORK_TYPE_EHRPD -> "EHRPD"
        TelephonyManager.NETWORK_TYPE_HSPAP -> "HSPAP"
        TelephonyManager.NETWORK_TYPE_GSM -> "GSM"
        TelephonyManager.NETWORK_TYPE_TD_SCDMA -> "TD_SCDMA"
        TelephonyManager.NETWORK_TYPE_IWLAN -> "IWLAN"
        TelephonyManager.NETWORK_TYPE_NR -> "NR_5G"
        else -> "TYPE_$type"
    }

    /**
     * One entry per SIM slot the platform reports.
     *
     * A slot with no SIM is kept rather than dropped: "slot 2 is empty" answers a
     * different question from "the phone has one SIM", and folding them together
     * makes a dual-SIM phone look single.
     */
    private fun readSims(tm: TelephonyManager): List<SimState> {
        val slots = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                tm.activeModemCount
            } else {
                @Suppress("DEPRECATION") tm.phoneCount
            }
        }.getOrDefault(1).coerceAtLeast(1)

        return (0 until slots).map { index ->
            val carrier = runCatching { tm.networkOperatorName }.getOrNull()?.takeIf { it.isNotBlank() }
            val roaming = runCatching { tm.isNetworkRoaming }.getOrDefault(false)
            val simState = runCatching {
                when {
                    tm.simState == TelephonyManager.SIM_STATE_READY -> "READY"
                    tm.simState == TelephonyManager.SIM_STATE_ABSENT -> "ABSENT"
                    tm.simState == TelephonyManager.SIM_STATE_PIN_REQUIRED -> "PIN_REQUIRED"
                    tm.simState == TelephonyManager.SIM_STATE_PUK_REQUIRED -> "PUK_REQUIRED"
                    tm.simState == TelephonyManager.SIM_STATE_NETWORK_LOCKED -> "NETWORK_LOCKED"
                    else -> "STATE_${tm.simState}"
                }
            }.getOrDefault("UNKNOWN")

            SimState(
                slotIndex = index,
                simState = simState,
                carrier = if (simState == "READY") carrier else null,
                roaming = roaming,
            )
        }
    }

    private suspend fun phoneNumbers(): ToolExecutionResult = withContext(Dispatchers.IO) {
        PermissionGrants.guard(context, TeamThreePermissions.READ_PHONE_NUMBERS)?.let { return@withContext it }

        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            ?: return@withContext result(
                PhoneNumbersResult(success = false, error = "This device has no telephony service.")
            )

        val slots = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) tm.activeModemCount
            else @Suppress("DEPRECATION") tm.phoneCount
        }.getOrDefault(1).coerceAtLeast(1)

        val entries = (0 until slots).map { index ->
            // getLine1Number returns an empty string, not null, when the carrier never
            // wrote a number, so an empty string is preserved as "no number" rather
            // than being turned into a fabricated one.
            val raw = runCatching { tm.line1Number }.getOrNull()
            SimState(
                slotIndex = index,
                simState = runCatching {
                    if (tm.simState == TelephonyManager.SIM_STATE_READY) "READY" else "STATE_${tm.simState}"
                }.getOrDefault("UNKNOWN"),
                number = raw?.takeIf { it.isNotBlank() },
            )
        }

        val withNumbers = entries.count { !it.number.isNullOrBlank() }
        val note = when {
            entries.isEmpty() -> "The platform reports no SIM slots."
            withNumbers == 0 -> "No SIM on this device carries a stored number. Carriers frequently do not " +
                "write one, and a missing number here means the carrier did not provide it rather than " +
                "that the read failed."
            withNumbers < entries.size -> "${entries.size - withNumbers} of ${entries.size} SIM(s) carry no " +
                "stored number; the carriers did not provide one for those."
            else -> null
        }

        result(PhoneNumbersResult(success = true, numbers = entries, simCount = entries.size, note = note))
    }

    /**
     * The privileged per-subscription state.
     *
     * Never attempted. READ_PRECISE_PHONE_STATE is signature|privileged, so no grant
     * a person can perform changes it, and calling the API anyway would throw
     * underneath and produce a less useful sentence than the catalogue's own.
     */
    private fun precisePhoneState(): ToolExecutionResult {
        val check = PermissionGrants.check(context, TeamThreePermissions.READ_PRECISE_PHONE_STATE)
        return if (check.allowed) {
            result(
                PrecisePhoneStateResult(
                    success = true,
                    available = true,
                    note = "The privileged grant is held, so per-subscription state is readable in principle; " +
                        "this build has no call site for it because nothing has yet needed a per-SIM metric.",
                )
            )
        } else {
            // The refusal sentence comes from the catalogue so this reads the same as
            // every other privileged refusal in the app.
            val refusal = PermissionGrants.refusal(check)
            ToolExecutionResult(
                rawJson = refusal.rawJson,
                resultData = PrecisePhoneStateResult(success = false, available = false, error = refusal.error),
                resultObject = null,
                success = false,
                error = refusal.error,
            )
        }
    }

    // ------------------------------------------------------------------
    // Outgoing call watching
    // ------------------------------------------------------------------

    private suspend fun outgoingCallWatch(args: Map<String, Any?>): ToolExecutionResult =
        withContext(Dispatchers.IO) {
            PermissionGrants.guard(context, TeamThreePermissions.PROCESS_OUTGOING_CALLS)
                ?.let { return@withContext it }

            val windowSeconds = ((args["windowSeconds"] as? Number)?.toInt() ?: 15).coerceIn(5, 120)
            val roleHeld = holdsCallScreeningRole()

            // LIVE needs the screening role, which this build does not hold and cannot
            // self-assign. Rather than reporting a watch that will never fire, the tool
            // says which mode it can actually run and observes the call log instead.
            val canReadLog = PermissionGrants.check(context, TeamThreePermissions.READ_CALL_LOG).allowed
            if (!canReadLog) {
                val refusal = PermissionGrants.refusal(
                    PermissionGrants.check(context, TeamThreePermissions.READ_CALL_LOG)
                )
                return@withContext ToolExecutionResult(
                    rawJson = refusal.rawJson,
                    resultData = OutgoingCallWatchResult(
                        success = false,
                        windowSeconds = windowSeconds,
                        mode = "UNAVAILABLE",
                        roleHeld = roleHeld,
                        note = "Live interception needs the call-screening role, which GURU does not hold, " +
                            "and the call-log fallback needs the Call log grant, which it does not have " +
                            "either, so no watch can run.",
                        error = refusal.error,
                    ),
                    resultObject = null,
                    success = false,
                    error = refusal.error,
                )
            }

            val startedAt = System.currentTimeMillis()
            val seenBefore = newestOutgoingId()
            delay(windowSeconds * 1000L)
            val observed = readOutgoingSince(seenBefore, startedAt - windowSeconds * 1000L)

            result(
                OutgoingCallWatchResult(
                    success = true,
                    observed = observed,
                    count = observed.size,
                    windowSeconds = windowSeconds,
                    mode = if (roleHeld) "LIVE" else "OBSERVATION",
                    roleHeld = roleHeld,
                    note = if (roleHeld) {
                        "The call-screening role is held, so calls are intercepted as they are placed."
                    } else {
                        "The call-screening role is not held, so this watched the call log for new outgoing " +
                            "entries over $windowSeconds seconds. That reports calls that happened rather than " +
                            "catching them in flight, and a call placed and still running may not appear yet."
                    },
                )
            )
        }

    private fun holdsCallScreeningRole(): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roles = context.getSystemService(android.app.role.RoleManager::class.java)
            roles?.isRoleHeld(android.app.role.RoleManager.ROLE_CALL_SCREENING) == true
        } else {
            false
        }
    }.getOrDefault(false)

    private fun newestOutgoingId(): Long = runCatching {
        context.contentResolver.query(
            CallLog.Calls.CONTENT_URI,
            arrayOf(CallLog.Calls._ID),
            "${CallLog.Calls.TYPE} = ?",
            arrayOf(CallLog.Calls.OUTGOING_TYPE.toString()),
            "${CallLog.Calls._ID} DESC",
        )?.use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else 0L } ?: 0L
    }.getOrDefault(0L)

    private fun readOutgoingSince(afterId: Long, sinceMillis: Long): List<OutgoingCallRecord> = runCatching {
        val out = mutableListOf<OutgoingCallRecord>()
        context.contentResolver.query(
            CallLog.Calls.CONTENT_URI,
            arrayOf(
                CallLog.Calls._ID,
                CallLog.Calls.NUMBER,
                CallLog.Calls.CACHED_NAME,
                CallLog.Calls.DATE,
                CallLog.Calls.DURATION,
            ),
            "${CallLog.Calls.TYPE} = ? AND ${CallLog.Calls._ID} > ? AND ${CallLog.Calls.DATE} >= ?",
            arrayOf(CallLog.Calls.OUTGOING_TYPE.toString(), afterId.toString(), sinceMillis.toString()),
            "${CallLog.Calls.DATE} ASC",
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                out.add(
                    OutgoingCallRecord(
                        number = cursor.getString(1),
                        cachedName = cursor.getString(2),
                        startedAtMillis = cursor.getLong(3),
                        durationSeconds = cursor.getLong(4),
                    )
                )
            }
        }
        out
    }.getOrDefault(emptyList())

    // ------------------------------------------------------------------
    // SIP, call account, handover
    // ------------------------------------------------------------------

    /**
     * SIP, honestly.
     *
     * android.net.sip was removed in API 31, so there is no stack to query on any
     * current platform and referencing it would not even compile. The permission is
     * still wired because the manifest declares it and the tier is real.
     */
    private fun sipStatus(): ToolExecutionResult {
        PermissionGrants.guard(context, TeamThreePermissions.USE_SIP)?.let { return it }
        return result(
            SipStatusResult(
                success = true,
                sipApiPresent = false,
                note = "Android removed the SIP API in Android 12 (API 31). There is no SIP stack on this " +
                    "platform for GURU to query or register with, so no accounts can exist here whether or " +
                    "not one was ever configured. A carrier's own dialler may still do SIP internally, but " +
                    "that is invisible to this app.",
            )
        )
    }

    /**
     * Registering a PhoneAccount, refused while it would be broken.
     *
     * A PhoneAccount with no ConnectionService behind it appears in the dialler and
     * fails the moment somebody picks it. That is worse than not registering, so the
     * tool names the missing piece instead of adding a dead entry to the person's
     * dialler.
     */
    private fun registerCallAccount(): ToolExecutionResult {
        PermissionGrants.guard(context, TeamThreePermissions.MANAGE_OWN_CALLS)?.let { return it }

        val telecom = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
            ?: return result(
                CallAccountResult(success = false, note = "This device has no Telecom service.")
            )

        val alreadyRegistered = runCatching {
            telecom.callCapablePhoneAccounts.any { it.id.contains(context.packageName) }
        }.getOrDefault(false)

        if (alreadyRegistered) {
            return result(
                CallAccountResult(
                    success = true,
                    registered = true,
                    note = "A phone account for GURU is already registered with Telecom.",
                )
            )
        }

        return result(
            CallAccountResult(
                success = false,
                registered = false,
                note = "MANAGE_OWN_CALLS is held, but registering a phone account needs a ConnectionService " +
                    "declared in the manifest for Telecom to bind. This build declares none, and an account " +
                    "without one shows up in the dialler and fails when chosen, so nothing was registered.",
                error = "A ConnectionService declaration is required before a phone account can work.",
            )
        )
    }

    /**
     * Call handover, refused while there is no connection service to hand to.
     */
    private fun handoverCall(): ToolExecutionResult {
        PermissionGrants.guard(context, TeamThreePermissions.ACCEPT_HANDOVER)?.let { return it }
        return result(
            CallAccountResult(
                success = false,
                registered = false,
                note = "ACCEPT_HANDOVER lets a call be taken over from another app or device, but only into " +
                    "GURU's own ConnectionService, which this build does not declare. There are therefore no " +
                    "calls GURU can accept a handover for, and nothing was taken over.",
                error = "A ConnectionService and an incoming call are both required for a handover.",
            )
        )
    }

    // ------------------------------------------------------------------
    // SMS
    // ------------------------------------------------------------------

    private suspend fun smsRead(args: Map<String, Any?>): ToolExecutionResult = withContext(Dispatchers.IO) {
        PermissionGrants.guard(context, TeamThreePermissions.READ_SMS)?.let { return@withContext it }

        val from = (args["from"] as? String)?.trim()?.takeIf { it.isNotBlank() }
        val limit = ((args["limit"] as? Number)?.toInt() ?: 20).coerceIn(1, 100)
        val days = (args["days"] as? Number)?.toInt()

        val selection = buildString {
            append("1 = 1")
            if (from != null) append(" AND ${Telephony.Sms.ADDRESS} LIKE ?")
            if (days != null) append(" AND ${Telephony.Sms.DATE} >= ?")
        }
        val selectionArgs = buildList {
            if (from != null) add("%$from%")
            if (days != null) add((System.currentTimeMillis() - days * 86_400_000L).toString())
        }.toTypedArray()

        return@withContext try {
            val messages = mutableListOf<SmsMessageView>()
            context.contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                arrayOf(
                    Telephony.Sms._ID,
                    Telephony.Sms.ADDRESS,
                    Telephony.Sms.BODY,
                    Telephony.Sms.DATE,
                    Telephony.Sms.TYPE,
                    Telephony.Sms.READ,
                    Telephony.Sms.THREAD_ID,
                ),
                selection,
                selectionArgs,
                "${Telephony.Sms.DATE} DESC LIMIT $limit",
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    messages.add(
                        SmsMessageView(
                            id = cursor.getLong(0),
                            address = cursor.getString(1) ?: "",
                            body = cursor.getString(2) ?: "",
                            dateMillis = cursor.getLong(3),
                            type = typeName(cursor.getInt(4)),
                            read = cursor.getInt(5) == 1,
                            threadId = cursor.getLong(6),
                        )
                    )
                }
            }

            val scope = when {
                messages.isEmpty() && from != null -> "No message from a sender matching '$from' in the window searched."
                messages.isEmpty() && days != null -> "No messages in the last $days day(s)."
                messages.isEmpty() -> "The message store is empty."
                else -> "${messages.size} of at most $limit message(s), newest first."
            }
            result(SmsReadResult(success = true, messages = messages, count = messages.size, scope = scope))
        } catch (e: SecurityException) {
            PermissionGrants.refusal(PermissionGrants.check(context, TeamThreePermissions.READ_SMS))
        } catch (e: Exception) {
            result(SmsReadResult(success = false, error = "The message store could not be read: ${e.message}"))
        }
    }

    private fun typeName(type: Int): String = when (type) {
        Telephony.Sms.MESSAGE_TYPE_INBOX -> "inbox"
        Telephony.Sms.MESSAGE_TYPE_SENT -> "sent"
        Telephony.Sms.MESSAGE_TYPE_DRAFT -> "draft"
        Telephony.Sms.MESSAGE_TYPE_OUTBOX -> "outbox"
        Telephony.Sms.MESSAGE_TYPE_FAILED -> "failed"
        Telephony.Sms.MESSAGE_TYPE_QUEUED -> "queued"
        else -> "unknown"
    }

    private suspend fun smsSend(args: Map<String, Any?>): ToolExecutionResult = withContext(Dispatchers.IO) {
        val number = (args["number"] as? String)?.trim()
            ?: return@withContext ToolExecutionResult.error("Missing 'number'")
        val body = args["message"] as? String
            ?: return@withContext ToolExecutionResult.error("Missing 'message'")
        val confirmed = args["confirmed"] as? Boolean
            ?: (args["confirmed"] as? String)?.equals("true", ignoreCase = true)
            ?: false

        PermissionGrants.guard(context, TeamThreePermissions.SEND_SMS)?.let { return@withContext it }

        if (number.isBlank()) {
            return@withContext result(
                SmsSendResult(success = false, status = "FAILED", error = "No number was given, so nothing was sent.")
            )
        }
        if (body.isBlank()) {
            return@withContext result(
                SmsSendResult(success = false, status = "FAILED", recipient = number, error = "The message body is empty.")
            )
        }

        if (!confirmed) {
            return@withContext result(
                SmsSendResult(
                    success = false,
                    status = "PENDING_CONFIRMATION",
                    recipient = number,
                    needsConfirmation = true,
                    note = "Sending cannot be taken back once it leaves this phone, so it needs an explicit " +
                        "yes. Call again with confirmed = true to send to $number.",
                )
            )
        }

        return@withContext try {
            val manager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION") SmsManager.getDefault()
            } ?: return@withContext result(
                SmsSendResult(success = false, status = "FAILED", recipient = number, error = "This device has no SmsManager service.")
            )

            val parts = manager.divideMessage(body)
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
            } else {
                android.app.PendingIntent.FLAG_UPDATE_CURRENT
            }
            val sentIntent = android.app.PendingIntent.getBroadcast(
                context,
                0,
                Intent("com.unuslumen.app.guru.SMS_SENT").setPackage(context.packageName),
                flags,
            )

            if (parts.size <= 1) {
                manager.sendTextMessage(number, null, body, sentIntent, null)
            } else {
                // The platform's own divider is what a receiving phone reassembles, so
                // the parts are built with it rather than by splitting on length.
                val sentIntents = ArrayList(parts.map { sentIntent })
                manager.sendMultipartTextMessage(number, null, parts, sentIntents, null)
            }

            result(
                SmsSendResult(
                    success = true,
                    status = "SENT",
                    recipient = number,
                    parts = parts.size,
                    note = "Handed ${parts.size} part(s) to the phone's messaging service. The platform " +
                        "reports final delivery separately, so a carrier rejection reached after this point " +
                        "is not visible here.",
                )
            )
        } catch (e: SecurityException) {
            PermissionGrants.refusal(PermissionGrants.check(context, TeamThreePermissions.SEND_SMS))
        } catch (e: Exception) {
            result(
                SmsSendResult(
                    success = false,
                    status = "FAILED",
                    recipient = number,
                    error = "The message was not sent: ${e.message}",
                )
            )
        }
    }

    /**
     * Listen for incoming messages for a bounded window.
     *
     * The receiver is registered at runtime, which is the only way a sideloaded app
     * may do it without a manifest entry, and that means it hears messages only while
     * this process is alive. The result says so instead of implying a permanent
     * listener. Each message also fires a hook event so an automation can act on a
     * sender without polling.
     */
    private suspend fun smsWatch(args: Map<String, Any?>): ToolExecutionResult = withContext(Dispatchers.IO) {
        PermissionGrants.guard(context, TeamThreePermissions.RECEIVE_SMS)?.let { return@withContext it }

        val windowSeconds = ((args["windowSeconds"] as? Number)?.toInt() ?: 30).coerceIn(5, 300)
        val received = java.util.Collections.synchronizedList(mutableListOf<SmsMessageView>())

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                val messages = intent?.let { Telephony.Sms.Intents.getMessagesFromIntent(it) } ?: return
                for (message in messages) {
                    val view = SmsMessageView(
                        address = message.originatingAddress ?: message.displayOriginatingAddress ?: "",
                        body = message.displayMessageBody ?: message.messageBody ?: "",
                        dateMillis = message.timestampMillis,
                        type = "inbox",
                        read = false,
                    )
                    received.add(view)
                    HookEventBus.fire(
                        HookEventType.CUSTOM,
                        mapOf<String, Any?>(
                            "event" to "smsReceived",
                            "from" to view.address,
                            "body" to view.body,
                            "receivedAtMillis" to view.dateMillis,
                        ),
                    )
                }
            }
        }

        val smsFilter = IntentFilter(Telephony.Sms.Intents.SMS_RECEIVED_ACTION).apply {
            priority = 999
        }
        val mmsFilter = IntentFilter(Telephony.Sms.Intents.WAP_PUSH_RECEIVED_ACTION)

        val smsRegistered = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, smsFilter, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("DEPRECATION") context.registerReceiver(receiver, smsFilter)
            }
            true
        }.getOrDefault(false)

        val mmsRegistered = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, mmsFilter, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("DEPRECATION") context.registerReceiver(receiver, mmsFilter)
            }
            true
        }.getOrDefault(false)

        if (!smsRegistered) {
            return@withContext result(
                SmsWatchResult(
                    success = false,
                    watching = false,
                    windowSeconds = windowSeconds,
                    note = "The platform refused to register an SMS receiver.",
                    error = "The SMS receiver could not be registered.",
                )
            )
        }

        try {
            delay(windowSeconds * 1000L)
        } finally {
            runCatching { context.unregisterReceiver(receiver) }
        }

        val captured = received.toList()
        result(
            SmsWatchResult(
                success = true,
                watching = false,
                registeredForSms = true,
                registeredForMms = mmsRegistered,
                received = captured,
                windowSeconds = windowSeconds,
                note = if (captured.isEmpty()) {
                    "No message arrived in $windowSeconds seconds. The receiver was registered and then " +
                        "removed, which is a quiet window rather than a failed watch."
                } else {
                    "${captured.size} message(s) arrived and each fired a hook event. The receiver ran " +
                        "inside the GURU process, so this listened for the window rather than permanently, " +
                        "and does not survive the process closing."
                },
            )
        )
    }

    private fun wapPush(): ToolExecutionResult {
        PermissionGrants.guard(context, TeamThreePermissions.RECEIVE_WAP_PUSH)?.let { return it }
        return result(
            WapPushResult(
                success = true,
                available = false,
                note = "Android delivers WAP push only to the app holding the default messaging role. GURU " +
                    "is sideloaded and holds no role, so a receiver registered here would never fire, and " +
                    "modern carriers send the same content as an ordinary SMS that smsWatch already hears.",
            )
        )
    }

    private fun cellBroadcasts(): ToolExecutionResult {
        PermissionGrants.guard(context, TeamThreePermissions.READ_CELL_BROADCASTS)?.let { return it }
        return result(
            CellBroadcastResult(
                success = true,
                available = false,
                note = "The permission is held, but Android exposes no public API for a third-party app to " +
                    "read the cell-broadcast database, so no alerts can be listed here. That is a platform " +
                    "limit rather than a quiet day with no alerts, and reading them needs shell-UID access " +
                    "through Shizuku.",
            )
        )
    }

    // ------------------------------------------------------------------
    // Plumbing
    // ------------------------------------------------------------------

    /**
     * Serialise a payload, and set the success flag from the payload rather than from
     * the fact that serialising worked.
     *
     * THIS WAS A REAL BUG FOUND BY A TEST. The earlier version called
     * ToolExecutionResult.success(...) for every payload, which forced success = true
     * even when the payload said SUCCESS = FALSE and carried an error. A caller then
     * could not tell a refusal from a completed call, which is precisely the defect
     * this whole batch exists to remove. The flag now follows the payload's own
     * success field, and the error travels with it.
     */
    private fun result(value: Any): ToolExecutionResult = when (value) {
        is CallPlacedResult -> emit(value, CallPlacedResult.serializer(), value.success, value.error)
        is CallControlResult -> emit(value, CallControlResult.serializer(), value.success, value.error)
        is PhoneStateResult -> emit(value, PhoneStateResult.serializer(), value.success, value.error)
        is PhoneNumbersResult -> emit(value, PhoneNumbersResult.serializer(), value.success, value.error)
        is PrecisePhoneStateResult -> emit(value, PrecisePhoneStateResult.serializer(), value.success, value.error)
        is OutgoingCallWatchResult -> emit(value, OutgoingCallWatchResult.serializer(), value.success, value.error)
        is SipStatusResult -> emit(value, SipStatusResult.serializer(), value.success, value.error)
        is CallAccountResult -> emit(value, CallAccountResult.serializer(), value.success, value.error)
        is SmsReadResult -> emit(value, SmsReadResult.serializer(), value.success, value.error)
        is SmsSendResult -> emit(value, SmsSendResult.serializer(), value.success, value.error)
        is SmsWatchResult -> emit(value, SmsWatchResult.serializer(), value.success, value.error)
        is WapPushResult -> emit(value, WapPushResult.serializer(), value.success, value.error)
        is CellBroadcastResult -> emit(value, CellBroadcastResult.serializer(), value.success, value.error)
        else -> ToolExecutionResult.error("Unserialisable result")
    }

    private fun <T : ToolResultData> emit(
        value: T,
        serializer: kotlinx.serialization.KSerializer<T>,
        success: Boolean,
        error: String?,
    ): ToolExecutionResult = ToolExecutionResult(
        rawJson = json.encodeToString(serializer, value),
        resultData = value,
        resultObject = null,
        success = success,
        error = error,
    )
}

/**
 * The phone domain's wiring: its definitions and the executor that runs them, in one
 * file so the two cannot drift apart.
 */
object PhoneDomainModule : DomainToolModule {
    override val id: String = "phone"

    override val registrations: List<ToolSetRegistration> = listOf(PhoneToolDefinitions)

    override fun bindings(): Module = module {
        factory { PhoneToolExecutor(get()) }
    }
}
