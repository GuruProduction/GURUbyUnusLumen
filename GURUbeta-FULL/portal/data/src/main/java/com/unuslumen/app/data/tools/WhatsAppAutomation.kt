// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.tools

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import android.view.accessibility.AccessibilityNodeInfo
import com.unuslumen.app.util.shell.GuruAccessibilityService
import kotlinx.coroutines.delay

/**
 * The real WhatsApp integration.
 *
 * ============================================================================
 * WHAT WAS WRONG
 * ============================================================================
 *
 * Issue 174. `whatsappSend` fired a `wa.me` URL at the OS and returned
 * `success = true` on the next line. What actually happens when that URL
 * resolves is that WhatsApp opens on a compose screen with the text
 * pre-filled, and then waits for a human thumb. Nothing was sent. The tool's
 * idea of success was "I launched an intent", and every caller walked away
 * believing the message had gone out.
 *
 * `whatsappSearch` was worse: a hardcoded `success = false` with the string
 * "requires ContentProvider access", which named a mechanism without saying
 * whether it was obtainable. It never attempted anything.
 *
 * ============================================================================
 * WHAT REPLACES IT
 * ============================================================================
 *
 * Two honest capabilities, each reporting exactly what it achieved:
 *
 *  - DRAFT. WhatsApp is opened with the recipient and text filled in, and the
 *    tool says so. `sent = false`, and the reason is that WhatsApp requires a
 *    human tap. This is the unprivileged floor and it is genuinely useful, but
 *    it is not a send and must never be described as one.
 *
 *  - SEND. With the accessibility service running, the tool drives the compose
 *    screen itself: it confirms the draft landed in the input field, taps send,
 *    and then reads the message bubble back off the screen. Only that last read
 *    produces `sent = true`.
 *
 * The difference between those two is the whole issue. A tool that cannot tell
 * them apart is the bug.
 */
object WhatsAppAutomation {

    const val PACKAGE = "com.whatsapp"

    /** WhatsApp's compose activity resource ids, which are stable across versions. */
    private const val ID_SEND_BUTTON = "com.whatsapp:id/send"
    private const val ID_SEARCH_INPUT = "com.whatsapp:id/search_input"
    private const val ID_SEARCH_SRC_TEXT = "com.whatsapp:id/search_src_text"

    /** How long to give WhatsApp to render before reading its screen. */
    private const val LAUNCH_SETTLE_MS = 1_500L
    private const val SEND_SETTLE_MS = 900L
    private const val SEARCH_SETTLE_MS = 1_200L

    // =========================================================================
    // Recipient resolution
    // =========================================================================

    /**
     * Turn whatever the caller passed into a bare international number.
     *
     * The tool's parameter description promises "phone number or contact name",
     * and the old code shoved the raw string into the URL either way. Pass it
     * "Sarah" and WhatsApp opened with no recipient at all. This resolves the
     * name properly, or refuses rather than launching something broken.
     */
    fun resolveNumber(context: Context, recipient: String): ResolvedRecipient {
        val trimmed = recipient.trim()

        // Already a number. Strip formatting characters and keep the digits.
        val digits = trimmed.filter { it.isDigit() || it == '+' }
        if (digits.count { it.isDigit() } >= 7) {
            return ResolvedRecipient.Number(digits.removePrefix("+"))
        }

        // A contact name. Look it up.
        val resolver: ContentResolver = context.contentResolver
        return try {
            resolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER,
                ),
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
                arrayOf("%$trimmed%"),
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC",
            )?.use { cursor ->
                val nameIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numberIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                val matches = mutableListOf<Pair<String, String>>()
                while (cursor.moveToNext() && matches.size < 10) {
                    val name = cursor.getString(nameIdx) ?: continue
                    val number = cursor.getString(numberIdx) ?: continue
                    val clean = number.filter { it.isDigit() || it == '+' }.removePrefix("+")
                    if (clean.count { it.isDigit() } >= 7) matches.add(name to clean)
                }
                when {
                    matches.isEmpty() -> ResolvedRecipient.NotFound(trimmed)
                    matches.size == 1 -> ResolvedRecipient.Number(matches.first().second)
                    else -> {
                        // Prefer an exact name match if there is one, because a
                        // partial search for "Sam" should not silently pick
                        // "Samantha" over "Sam".
                        val exact = matches.filter { it.first.equals(trimmed, ignoreCase = true) }
                        if (exact.size == 1) ResolvedRecipient.Number(exact.first().second)
                        else ResolvedRecipient.Ambiguous(matches.map { it.first })
                    }
                }
            } ?: ResolvedRecipient.NotFound(trimmed)
        } catch (e: SecurityException) {
            ResolvedRecipient.NoContactsAccess(e.message)
        } catch (e: Exception) {
            ResolvedRecipient.NotFound(trimmed)
        }
    }

    sealed interface ResolvedRecipient {
        data class Number(val digits: String) : ResolvedRecipient
        data class NotFound(val query: String) : ResolvedRecipient
        data class Ambiguous(val candidates: List<String>) : ResolvedRecipient
        data class NoContactsAccess(val detail: String?) : ResolvedRecipient
    }

    // =========================================================================
    // Compose
    // =========================================================================

    /**
     * Open WhatsApp on a compose screen for [digits] with [message] pre-filled.
     *
     * Returns whether an activity was actually started. That is all it claims:
     * WhatsApp renders the compose screen and stops, so every caller reports
     * this as a draft and lets [attemptSend] decide whether a send happened.
     */
    fun launchCompose(context: Context, digits: String, message: String): Boolean {
        val uri = Uri.parse("https://api.whatsapp.com/send?phone=$digits&text=${Uri.encode(message)}")
        // Try targeting WhatsApp directly first, because a bare ACTION_VIEW can
        // be resolved by a browser instead. If WhatsApp does not declare a
        // matching filter, fall back to the untargeted intent rather than
        // reporting that WhatsApp refused.
        val targeted = Intent(Intent.ACTION_VIEW, uri).apply {
            setPackage(PACKAGE)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(targeted)
            true
        } catch (e: Exception) {
            try {
                val open = Intent(Intent.ACTION_VIEW, uri).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(open)
                true
            } catch (e2: Exception) {
                false
            }
        }
    }

    /** Whether WhatsApp is installed at all. */
    fun isInstalled(context: Context): Boolean = try {
        context.packageManager.getPackageInfo(PACKAGE, 0)
        true
    } catch (e: Exception) {
        false
    }

    // =========================================================================
    // Accessibility-driven send
    // =========================================================================

    /**
     * Try to actually send, rather than leaving a draft.
     *
     * Returns [SendAttempt.Sent] only when the message text is observed in the
     * conversation AND is no longer sitting in the compose box. Everything else
     * is reported as what it is. A tap that was dispatched is not a message
     * that was sent.
     */
    suspend fun attemptSend(message: String): SendAttempt {
        val service = GuruAccessibilityService.instance
            ?: return SendAttempt.NoAccessibility

        delay(LAUNCH_SETTLE_MS)

        // 1. Confirm the draft actually landed in the compose box. If it did
        //    not, WhatsApp did not open where we expected and tapping send
        //    would either do nothing or send the wrong thing.
        //
        //    THE ORDER HERE IS LOAD-BEARING, and the old order was wrong.
        //    typeText performs ACTION_SET_TEXT, which REPLACES the entire
        //    content of the first editable node it finds. WhatsApp fills the
        //    compose box from the intent, so typing first overwrote the message
        //    that was already sitting there, and on a screen that had not
        //    finished opening the first editable node is the chat list's search
        //    field, so the message was typed into the wrong place entirely.
        //
        //    The draft is now read off the screen first. Typing happens only
        //    when it is genuinely absent, and only when the send control is
        //    visible, which is what marks the conversation compose screen as
        //    opposed to any other screen that happens to have an input field.
        val draftAlreadyVisible = editableContains(service, message) || screenContains(service, message)
        val draftPresent = draftAlreadyVisible ||
            (hasSendControl(service) && service.typeText(message) && editableContains(service, message))
        if (!draftPresent) return SendAttempt.DraftNotVisible

        // 2. Find and tap send.
        val tapped = service.clickViewId(ID_SEND_BUTTON) || service.clickText("Send", exact = false)
        if (!tapped) return SendAttempt.SendButtonNotFound

        delay(SEND_SETTLE_MS)

        // 3. Read the conversation back.
        //
        // The evidence that counts is the message appearing in the thread. The
        // trap is that the message ALSO sits in the compose box until the send
        // actually completes, so a naive "is the text on screen" check returns
        // true even when nothing was sent. That would be the same class of lie
        // as the original bug, just wearing a different hat.
        //
        // So: the text must be gone from the editable field, and present
        // somewhere else on screen. Both conditions, or it is not a send.
        val stillInCompose = editableContains(service, message)
        val visibleInThread = screenContains(service, message)

        return when {
            stillInCompose -> SendAttempt.TappedButUnconfirmed
            visibleInThread -> SendAttempt.Sent
            else -> SendAttempt.TappedButUnconfirmed
        }
    }

    sealed interface SendAttempt {
        /** The message text was observed in the conversation after sending. */
        data object Sent : SendAttempt
        /** The accessibility service is not running, so a draft is the best available. */
        data object NoAccessibility : SendAttempt
        /** WhatsApp opened but the text never appeared in an input field. */
        data object DraftNotVisible : SendAttempt
        /** The compose screen was reached but no send control was found. */
        data object SendButtonNotFound : SendAttempt
        /** Send was tapped but the message did not appear. Do not claim success. */
        data object TappedButUnconfirmed : SendAttempt
    }

    // =========================================================================
    // Accessibility-driven search
    // =========================================================================

    /**
     * Search WhatsApp for [query] by driving its own search field.
     *
     * Returns the visible result lines. This reflects messages on the device
     * now, including anything received since the last time it was opened. It
     * does not reach messages that have been deleted or that live only in an
     * archived thread that the search surface does not index.
     */
    suspend fun search(context: Context, query: String, limit: Int): SearchAttempt {
        val service = GuruAccessibilityService.instance
            ?: return SearchAttempt.NoAccessibility

        // Open WhatsApp's main screen rather than a compose screen.
        val launch = context.packageManager.getLaunchIntentForPackage(PACKAGE)
            ?: return SearchAttempt.NotInstalled
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(launch)

        delay(LAUNCH_SETTLE_MS)

        // Reach the search field. WhatsApp exposes it by content description on
        // the chat list, and by resource id once the search surface is open.
        val opened = service.clickText("Search", exact = false) ||
            service.clickViewId(ID_SEARCH_INPUT) ||
            service.clickViewId(ID_SEARCH_SRC_TEXT)
        if (!opened) return SearchAttempt.SearchFieldNotFound

        delay(400L)

        val typed = service.typeText(query) || service.typeText(query, targetHint = "Search")
        if (!typed) return SearchAttempt.QueryNotEntered

        delay(SEARCH_SETTLE_MS)

        val lines = collectTextLines(service)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .filterNot { it.equals("Search", ignoreCase = true) }
            .distinct()

        return if (lines.isEmpty()) SearchAttempt.NoResults(query)
        else SearchAttempt.Results(lines.take(limit.coerceIn(1, 100)))
    }

    sealed interface SearchAttempt {
        data class Results(val lines: List<String>) : SearchAttempt
        data class NoResults(val query: String) : SearchAttempt
        /** The service is not running. This is a grant problem, not an empty inbox. */
        data object NoAccessibility : SearchAttempt
        data object NotInstalled : SearchAttempt
        data object SearchFieldNotFound : SearchAttempt
        data object QueryNotEntered : SearchAttempt
    }

    // =========================================================================
    // Screen reading
    // =========================================================================

    /**
     * Is a send control on screen right now?
     *
     * Its presence is what distinguishes a conversation compose screen from any
     * other screen in the app, so it decides whether typing the message is safe.
     * It is the same two lookups the send step then uses, kept in one place so
     * the two can never disagree about what the screen is.
     */
    private fun hasSendControl(service: GuruAccessibilityService): Boolean {
        val root = service.rootInActiveWindow ?: return false
        return (root.findAccessibilityNodeInfosByViewId(ID_SEND_BUTTON)?.isNotEmpty() == true) ||
            (root.findAccessibilityNodeInfosByText("Send")?.isNotEmpty() == true)
    }

    /** Does any visible node on screen contain [needle]? */
    private fun screenContains(service: GuruAccessibilityService, needle: String): Boolean {
        val root = service.rootInActiveWindow ?: return false
        return nodeContains(root, needle)
    }

    /**
     * Is [needle] still sitting in an editable field?
     *
     * This is the check that stops the tool claiming a send it did not make.
     * A message that is still in the compose box has not been sent, no matter
     * how many times a send control was tapped.
     */
    private fun editableContains(service: GuruAccessibilityService, needle: String): Boolean {
        val root = service.rootInActiveWindow ?: return false
        return editableContains(root, needle, 0)
    }

    private fun editableContains(node: AccessibilityNodeInfo, needle: String, depth: Int): Boolean {
        if (depth > 40) return false
        if (node.isEditable) {
            val text = node.text?.toString()
            if (text != null && text.contains(needle, ignoreCase = true)) return true
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (editableContains(child, needle, depth + 1)) return true
        }
        return false
    }

    private fun nodeContains(node: AccessibilityNodeInfo, needle: String): Boolean {
        val text = node.text?.toString()
        val desc = node.contentDescription?.toString()
        if (text != null && text.contains(needle, ignoreCase = true)) return true
        if (desc != null && desc.contains(needle, ignoreCase = true)) return true
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (nodeContains(child, needle)) return true
        }
        return false
    }

    /** Every distinct non-empty text and content description currently on screen. */
    private fun collectTextLines(service: GuruAccessibilityService): List<String> {
        val root = service.rootInActiveWindow ?: return emptyList()
        val out = mutableListOf<String>()
        collect(root, out, 0)
        return out
    }

    private fun collect(node: AccessibilityNodeInfo, out: MutableList<String>, depth: Int) {
        if (depth > 40) return
        node.text?.toString()?.let { if (it.isNotBlank()) out.add(it) }
        node.contentDescription?.toString()?.let { if (it.isNotBlank()) out.add(it) }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collect(child, out, depth + 1)
        }
    }
}
