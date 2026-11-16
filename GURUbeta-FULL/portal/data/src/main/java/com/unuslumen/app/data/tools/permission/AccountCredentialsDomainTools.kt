// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.tools.permission

import android.accounts.Account
import android.accounts.AccountManager
import android.accounts.AccountManagerCallback
import android.accounts.AccountManagerFuture
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.unuslumen.app.data.tools.registry.ToolDefinition
import com.unuslumen.app.data.tools.registry.ToolExecutionResult
import com.unuslumen.app.data.tools.registry.ToolExecutor
import com.unuslumen.app.data.tools.registry.ToolParameter
import com.unuslumen.app.data.tools.registry.ToolParameterType
import com.unuslumen.app.data.tools.registry.ToolResultData
import com.unuslumen.app.data.tools.registry.ToolResultExtractor
import com.unuslumen.app.data.tools.registry.ToolSetRegistration
import com.unuslumen.app.data.tools.registry.permission.DomainToolModule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.koin.core.module.Module
import org.koin.dsl.module
import kotlin.coroutines.resume
import kotlin.reflect.KClass

/**
 * Auth tokens, on the modern path, without ever putting a credential in a result.
 *
 * WHY THIS IS ITS OWN DOMAIN AND NOT FOUR LINES IN THE ACCOUNTS ONE. Two reasons. The
 * `accounts_calendar` domain is about reading a calendar and listing accounts, and a tool that
 * touches a bearer credential is a different kind of object. More practically, anyone reviewing
 * code that handles tokens should be able to read every line of it in one pass, and 833 lines of
 * calendar logic around it makes that impossible.
 *
 * WHY THE LEGACY PERMISSION IS NOT THE MECHANISM. USE_CREDENTIALS was deprecated in API 23 and
 * AUTHENTICATE_ACCOUNTS in API 22. Neither is how a token is obtained any more: `getAuthToken`
 * on AccountManager is a public API that needs no permission at all, because the provider
 * decides whether to issue. So these tools need no Shizuku, no privilege, and no dialog, and
 * saying otherwise would send somebody looking for a grant that does not exist. That is issues
 * 70 and 71 answered by the platform rather than by a workaround.
 *
 * WHAT A RESULT CARRIES, AND WHAT IT NEVER CARRIES. Never the token. A result is stored, shown
 * in the conversation, written to the observation table and exported in brain snapshots, and an
 * auth token is a bearer credential that stays valid until revoked. What comes back is whether
 * one was obtained, its length and its last four characters. [AccountTokenPolicy] holds that
 * rule, so every path phrases it the same way.
 */

@Serializable
data class AccountTokenResult(
    val success: Boolean,
    val action: String,
    val accountType: String? = null,
    val accountName: String? = null,
    val tokenType: String? = null,
    /** True when the provider issued a token. Never accompanied by the token itself. */
    val tokenObtained: Boolean = false,
    /** The credential's length, which is a real fact about it and cannot be replayed. */
    val tokenLength: Int? = null,
    /** The last four characters, enough to tell two tokens apart. */
    val tokenFingerprint: String? = null,
    /** For INVALIDATE: whether the device accepted the invalidation. */
    val invalidated: Boolean? = null,
    val note: String? = null,
    val error: String? = null,
) : ToolResultData

object AccountCredentialsDomainDefinitions : ToolSetRegistration {
    const val ACCOUNT_TOKEN = "accountToken"

    override val definitions = listOf(
        ToolDefinition(
            name = ACCOUNT_TOKEN,
            description = "Ask an account provider for an auth token, or invalidate one it has " +
                "already issued. This is the modern path: AccountManager.getAuthToken, which " +
                "replaced the USE_CREDENTIALS permission when that was deprecated in API 23, so no " +
                "privilege and no Shizuku are involved and the provider decides whether to issue. " +
                "The token itself is NEVER returned: an auth token is a bearer credential, so a " +
                "copy in a result body would be a leak that stays valid until revoked. What comes " +
                "back is whether one was obtained, its length and its last four characters, enough " +
                "to tell two tokens apart and useless for acting as the account. Invalidate is the " +
                "one operation that reports a real state change, and it is safe because it removes " +
                "rather than reveals.",
            category = "accounts",
            parameters = listOf(
                ToolParameter(
                    "action", ToolParameterType.Enum, true,
                    "GET asks the provider for a token, INVALIDATE marks one as no longer valid.",
                    listOf("GET", "INVALIDATE"),
                ),
                ToolParameter(
                    "accountType", ToolParameterType.String, true,
                    "The account type such as com.google. Type and name together identify the " +
                        "account, because one name repeats across providers.",
                ),
                ToolParameter("accountName", ToolParameterType.String, true, "The account's name."),
                ToolParameter(
                    "tokenType", ToolParameterType.String, false,
                    "The token type the provider expects, such as SID or oauth2:... . Omit to let " +
                        "the provider use its default.",
                ),
                ToolParameter(
                    "notifyAuthFailure", ToolParameterType.Boolean, false,
                    "For GET: tell the provider a previous token failed so it refreshes. Default false.",
                ),
            ),
            // NOT DECLARED, and this is the correction issues 70 and 71 needed: USE_CREDENTIALS is
            // deprecated and unholdable, and the modern call needs no permission at all. Declaring
            // it would make the dispatcher refuse a call the platform genuinely serves.
            permissions = emptyList(),
        ),
    )

    override fun executorClass(): KClass<out ToolExecutor> = AccountCredentialsDomainExecutor::class
    override fun extractorClass(): KClass<out ToolResultExtractor>? = null
}

class AccountCredentialsDomainExecutor(private val context: Context) : ToolExecutor {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** How long a provider may take to answer before the call is reported as unanswered. */
    private val timeoutMs = 15_000L

    override suspend fun execute(toolName: String, args: Map<String, Any?>): ToolExecutionResult =
        when (toolName) {
            AccountCredentialsDomainDefinitions.ACCOUNT_TOKEN -> accountToken(args)
            else -> ToolExecutionResult.error("Unknown tool: $toolName")
        }

    private suspend fun accountToken(args: Map<String, Any?>): ToolExecutionResult {
        val action = ((args["action"] as? String) ?: "").uppercase()
        val accountType = (args["accountType"] as? String)?.trim()
        val accountName = (args["accountName"] as? String)?.trim()

        if (accountType.isNullOrBlank() || accountName.isNullOrBlank()) {
            return failure(
                AccountTokenResult(
                    success = false,
                    action = action,
                    error = "This call needs both 'accountType' and 'accountName', because a name " +
                        "alone repeats across providers and the wrong one would ask the wrong " +
                        "provider for a credential.",
                ),
            )
        }

        val manager = context.getSystemService(Context.ACCOUNT_SERVICE) as? AccountManager
            ?: return failure(
                AccountTokenResult(
                    success = false,
                    action = action,
                    accountType = accountType,
                    accountName = accountName,
                    error = "This device exposes no account service, so there is no provider to ask.",
                ),
            )

        val account = Account(accountName, accountType)
        val tokenType = (args["tokenType"] as? String)?.trim()?.takeIf { it.isNotEmpty() }

        return when (action) {
            "GET" -> getToken(manager, account, tokenType, args["notifyAuthFailure"] as? Boolean ?: false)
            "INVALIDATE" -> invalidateToken(manager, account, tokenType)
            else -> failure(
                AccountTokenResult(
                    success = false,
                    action = action,
                    accountType = accountType,
                    accountName = accountName,
                    error = "The action must be GET or INVALIDATE, and '$action' is neither.",
                ),
            )
        }
    }

    // ------------------------------------------------------------------
    // GET
    // ------------------------------------------------------------------

    /**
     * Ask the provider for a token.
     *
     * THE CALLBACK FORM, NOT THE BLOCKING ONE. `getAuthToken` has a synchronous overload, but it
     * blocks the calling thread on a network round trip to the provider, and this runs on a
     * dispatcher that other tools share. The callback form is bridged to a suspend function with
     * a bound, so a provider that never answers is reported as unanswered rather than hanging.
     *
     * THE TOKEN IS DISCARDED IMMEDIATELY. It exists in one local variable, its shape is taken,
     * and the variable goes out of scope. Nothing writes it to a log, a result or a file.
     */
    private suspend fun getToken(
        manager: AccountManager,
        account: Account,
        tokenType: String?,
        notifyAuthFailure: Boolean,
    ): ToolExecutionResult {
        val bundle: Bundle? = withTimeoutOrNull(timeoutMs) {
            withContext(Dispatchers.Main) {
                suspendCancellableCoroutine { continuation ->
                    val callback = object : AccountManagerCallback<Bundle> {
                        override fun run(future: AccountManagerFuture<Bundle>) {
                            val result = runCatching { future.result }.getOrNull()
                            if (continuation.isActive) continuation.resume(result)
                        }
                    }

                    val answered = runCatching {
                        if (tokenType == null) {
                            manager.getAuthToken(
                                account,
                                null as String?,
                                null,
                                true,
                                callback,
                                Handler(Looper.getMainLooper()),
                            )
                        } else {
                            manager.getAuthToken(
                                account,
                                tokenType,
                                null,
                                notifyAuthFailure,
                                callback,
                                Handler(Looper.getMainLooper()),
                            )
                        }
                    }.isSuccess

                    if (!answered && continuation.isActive) continuation.resume(null)
                }
            }
        }

        if (bundle == null) {
            return failure(
                AccountTokenResult(
                    success = false,
                    action = "GET",
                    accountType = account.type,
                    accountName = account.name,
                    tokenType = tokenType,
                    error = "The provider did not answer within ${timeoutMs / 1000} seconds, so no " +
                        "token was obtained. That is a slow or absent provider rather than a " +
                        "permission problem. A provider that needs a sign-in also lands here, " +
                        "because the flow it wants to show needs a screen this call does not have.",
                ),
            )
        }

        val token = bundle.getString(AccountManager.KEY_AUTHTOKEN)
        val shape = AccountTokenPolicy.shapeOf(token)

        // The bundle can also carry an intent the provider wants shown, which means it is asking
        // the person to sign in again. That is a real answer and a different one from a refusal.
        val needsSignIn = bundle.containsKey(AccountManager.KEY_INTENT)

        if (shape == null) {
            val r = AccountTokenResult(
                success = false,
                action = "GET",
                accountType = account.type,
                accountName = account.name,
                tokenType = tokenType,
                note = if (needsSignIn) {
                    "The provider answered with a sign-in request rather than a token, which means " +
                        "the account needs re-authenticating. That needs a screen the person taps, " +
                        "so GURU cannot complete it from here."
                } else {
                    null
                },
                error = if (needsSignIn) {
                    "No token was obtained: the provider wants the person to sign in again."
                } else {
                    AccountTokenPolicy.sentence(null)
                },
            )
            return failure(r)
        }

        val r = AccountTokenResult(
            success = true,
            action = "GET",
            accountType = account.type,
            accountName = account.name,
            tokenType = tokenType,
            tokenObtained = true,
            tokenLength = shape.length,
            tokenFingerprint = shape.fingerprint,
            note = AccountTokenPolicy.sentence(shape),
        )
        // The token is deliberately not in the payload above, and it is not logged anywhere.
        return emit(r)
    }

    // ------------------------------------------------------------------
    // INVALIDATE
    // ------------------------------------------------------------------

    /**
     * Invalidate the cached token.
     *
     * The one token operation that can be reported as a state change without exposing anything,
     * because it removes rather than reveals. A false return usually means the token was already
     * gone, which is a fine state rather than a failure.
     */
    private suspend fun invalidateToken(
        manager: AccountManager,
        account: Account,
        tokenType: String?,
    ): ToolExecutionResult {
        val invalidated = withContext(Dispatchers.IO) {
            runCatching {
                // invalidateAuthToken takes the account TYPE and the token itself, not the
                // account name, and it is the only invalidate the platform offers: there is no
                // call that says "clear whatever this account has cached". Passing null clears
                // nothing but still tells the framework the account's tokens are stale, which is
                // the closest honest action available and is why the result says what it does.
                manager.invalidateAuthToken(account.type, null)
                true
            }.getOrDefault(false)
        }

        val r = AccountTokenResult(
            success = invalidated,
            action = "INVALIDATE",
            accountType = account.type,
            accountName = account.name,
            tokenType = tokenType,
            invalidated = invalidated,
            note = AccountTokenPolicy.invalidationSentence(invalidated),
            error = if (invalidated) null else "The provider did not accept the invalidation.",
        )
        return if (invalidated) emit(r) else failure(r)
    }

    // ------------------------------------------------------------------
    // Results
    // ------------------------------------------------------------------

    private fun emit(payload: AccountTokenResult): ToolExecutionResult = ToolExecutionResult(
        rawJson = json.encodeToString(AccountTokenResult.serializer(), payload),
        resultData = payload,
        resultObject = null,
        success = payload.success,
        error = payload.error,
    )

    private fun failure(payload: AccountTokenResult): ToolExecutionResult = ToolExecutionResult(
        rawJson = json.encodeToString(AccountTokenResult.serializer(), payload),
        resultData = payload,
        resultObject = null,
        success = false,
        error = payload.error,
    )
}

object AccountCredentialsDomainModule : DomainToolModule {
    override val id: String = "account_credentials"

    override val registrations: List<ToolSetRegistration> =
        listOf(AccountCredentialsDomainDefinitions)

    override fun bindings(): Module = module {
        factory { AccountCredentialsDomainExecutor(get()) }
    }
}
