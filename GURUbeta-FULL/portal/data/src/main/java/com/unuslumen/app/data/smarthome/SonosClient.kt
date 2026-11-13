// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.smarthome

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL

/**
 * Talks to Sonos speakers over their own UPnP services.
 *
 * WHY THIS IS A CLIENT RATHER THAN A SHELL-OUT (issues 179 and 186). All five Sonos tools ran
 * `sonos <verb>`, a Node CLI that Termux does not package and the APK never contained, so every one
 * could only fail. A speaker is an ordinary UPnP device on port 1400, so the whole capability is a
 * POST of a SOAP envelope.
 *
 * NO TOR, for the same reason the Hue client has none: a speaker is on the owner's own wifi, and a
 * circuit cannot reach it. The request is pinned to `Proxy.NO_PROXY` so it cannot accidentally be
 * routed anywhere else.
 */
class SonosClient {

    companion object {
        private const val TAG = "guru"
        private const val TIMEOUT_MS = 5_000
    }

    /** One reply, already read. */
    data class Reply(val statusCode: Int, val body: String, val failure: String? = null)

    /**
     * POST one SOAP action to a speaker.
     *
     * THE `SOAPAction` HEADER IS NOT OPTIONAL, and its absence produces a bare 500 with no
     * explanation. It goes in the header AND the action appears in the body, which is the protocol
     * rather than duplication.
     */
    fun call(
        speaker: SonosApi.Speaker,
        service: String,
        action: String,
        arguments: Map<String, String>,
    ): Reply = try {
        val url = URL("${speaker.baseUrl}${SonosApi.controlPath(service)}")
        val connection = url.openConnection(Proxy.NO_PROXY) as HttpURLConnection
        connection.requestMethod = "POST"
        connection.connectTimeout = TIMEOUT_MS
        connection.readTimeout = TIMEOUT_MS
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
        connection.setRequestProperty("SOAPAction", SonosApi.soapAction(service, action))
        val envelope = SonosApi.envelope(service, action, arguments)
        connection.outputStream.use { it.write(envelope.toByteArray(Charsets.UTF_8)) }

        val status = connection.responseCode
        val stream = if (status in 200..299) connection.inputStream else connection.errorStream
        val text = stream?.bufferedReader(Charsets.UTF_8)?.use { reader ->
            // Bounded, like every other body read in this app.
            val buffer = CharArray(64 * 1024)
            val read = reader.read(buffer)
            if (read <= 0) "" else String(buffer, 0, read)
        }.orEmpty()
        connection.disconnect()
        Log.d(TAG, "Sonos $action on ${speaker.host} -> $status (${text.length} chars)")
        Reply(status, text)
    } catch (e: Exception) {
        Reply(0, "", e.message ?: e.javaClass.simpleName)
    }

    /**
     * Find speakers from the speakers themselves.
     *
     * A SONOS SPEAKER KNOWS EVERY OTHER SPEAKER IN ITS HOUSE, through its own zone group topology,
     * so one address is enough to enumerate the lot. That is better than an SSDP sweep here because
     * it also gives the zone names the tools take as arguments, and because every speaker answers
     * with the same picture rather than each announcing only itself.
     */
    suspend fun discoverFrom(seedHost: String): List<SonosApi.Speaker> = withContext(Dispatchers.IO) {
        val seed = SonosApi.Speaker(host = seedHost, name = seedHost)
        val (service, args) = SonosApi.getZoneGroupState()
        val reply = call(seed, service, "GetZoneGroupState", args)
        if (reply.failure != null || reply.statusCode !in 200..299) {
            Log.d(TAG, "Sonos topology from $seedHost failed: ${reply.failure ?: reply.statusCode}")
            return@withContext emptyList()
        }
        SonosApi.speakersInZoneGroupState(reply.body)
    }

    /** The speaker's own name and model, from its device description. */
    suspend fun identity(speaker: SonosApi.Speaker): SonosApi.Speaker = withContext(Dispatchers.IO) {
        val reply = call(speaker, SonosApi.DEVICE_PROPERTIES, "GetZoneAttributes", emptyMap())
        if (reply.statusCode !in 200..299) return@withContext speaker
        val name = SonosApi.elementText(reply.body, "CurrentZoneName") ?: speaker.name
        speaker.copy(name = name)
    }
}
