// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.fabric.net

/**
 * What a device's answer means, in one sentence a person can act on. Issue #252.
 *
 * WHY THIS IS SEPARATE FROM THE TRANSPORT. The defect was not that the request failed. It
 * was that a device which answered with HTTP 403 was described as a set in "deep standby",
 * which is a claim about a host that stopped answering. Those two facts are different and
 * the wrong one sent the reader to Wake-on-LAN against a television that was on and serving
 * pages. The rule that separates them is a decision, decisions are what a JVM test can pin,
 * and so it lives here with no socket and no Android.
 *
 * THE ONE RULE. A status the device chose to send is reported as the device's answer, with
 * its own body when it explains itself. Only a request that produced no response at all may
 * be called unreachable, asleep or closed.
 */
object DeviceAnswer {

    /**
     * A sentence for an answer that came back.
     *
     * [subject] is the thing being addressed, such as "the Roku at 192.0.2.20:8060", so
     * every sentence names the device rather than a bare "the request".
     */
    fun describe(subject: String, response: LocalHttp.Response): String {
        val body = response.body.trim()
        // The device's own words are the most useful part of a refusal and they are the
        // part the previous code threw away by reading only the input stream, which for an
        // error status is empty on every platform.
        val said = if (body.isEmpty()) "" else " It said: ${body.take(300)}"
        val reason = if (response.reason.isBlank()) "" else " ${response.reason.trim()}"

        return when {
            response.ok -> "$subject accepted the request with HTTP ${response.status}.$said"
            response.status == 401 || response.status == 407 ->
                "$subject refused the request with HTTP ${response.status}$reason, which means it " +
                    "wants a credential this request did not carry.$said"
            response.status == 403 ->
                "$subject refused the request with HTTP 403$reason. The set is awake and answering, " +
                    "so this is its own refusal rather than a sleeping device, and it says " +
                    "what it wants:${if (body.isEmpty()) " nothing, which usually means a required header was absent." else said}"
            response.status == 404 ->
                "$subject answered HTTP 404$reason, which means the endpoint does not exist on " +
                    "this device rather than the device being unreachable.$said"
            response.status == 405 ->
                "$subject answered HTTP 405$reason, so the device exists and rejects this method " +
                    "for that path.$said"
            response.status in 500..599 ->
                "$subject answered HTTP ${response.status}$reason, which is a fault on the device " +
                    "itself rather than a problem with the request.$said"
            else ->
                "$subject answered HTTP ${response.status}$reason, and that is the device's own " +
                    "answer rather than an absent one.$said"
        }
    }

    /**
     * A sentence for a request that got no response at all.
     *
     * Only this branch may talk about a set being asleep, because only here is there no
     * evidence that anything processed the request.
     */
    fun describeNoAnswer(subject: String, reason: String): String =
        "$subject did not answer at all: $reason. A television stops answering when it is in " +
            "deep standby or off the network, so check the set is on and on this wifi before " +
            "concluding it refused."

    /**
     * Whether a status means the device told us the request was unacceptable.
     *
     * A 4xx is the device's considered answer. A 5xx is the device failing. Neither is an
     * absence, and a caller that treats them the same is the defect this file removes.
     */
    fun isRefusal(status: Int): Boolean = status in 400..499
}
