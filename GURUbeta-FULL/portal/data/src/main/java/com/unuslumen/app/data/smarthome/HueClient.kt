// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.smarthome

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL

/**
 * Talks to a Hue Bridge over plain HTTP on the local network.
 *
 * WHY THIS IS A CLIENT RATHER THAN A SHELL-OUT (issue 179). The old path ran
 * `openhue get light`, and `openhue` is a Go binary that Termux does not package and that was never
 * in the APK. So it could only fail, and it failed with a message telling a person to install
 * something that does not exist. The bridge is an ordinary JSON HTTP server, so the entire
 * capability is a handful of requests: no binary, no library, no dependency.
 *
 * NO TOR, AND THAT IS DELIBERATE. A bridge is on the owner's own wifi, and routing to it through a
 * circuit would fail exactly as the #215 LAN bug did. The request is pinned to `Proxy.NO_PROXY` for
 * the same reason the SSDP description fetch is: this path has no business reaching the internet.
 * The bridge address comes from discovery on the local network, so it cannot be anything else.
 *
 * THE DECISIONS LIVE IN [HueApi] and are proven on the JVM. This file is only transport, which means
 * a change to how a request is made cannot quietly change what a response means.
 */
class HueClient(private val context: Context) {

    companion object {
        private const val TAG = "guru"
        private const val PREF = "guru_hue"
        private const val KEY_BRIDGE_HOST = "bridge_host"
        private const val KEY_APP_KEY = "app_key"
        private const val TIMEOUT_MS = 5_000
    }

    private val prefs get() = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    /** The saved bridge, or null when none has been paired. */
    fun savedBridge(): HueApi.Bridge? {
        val host = prefs.getString(KEY_BRIDGE_HOST, null) ?: return null
        return HueApi.Bridge(
            host = host,
            applicationKey = prefs.getString(KEY_APP_KEY, "").orEmpty(),
        )
    }

    fun saveBridge(bridge: HueApi.Bridge) {
        prefs.edit()
            .putString(KEY_BRIDGE_HOST, bridge.host)
            .putString(KEY_APP_KEY, bridge.applicationKey)
            .apply()
    }

    /** One HTTP response, already read. */
    data class Response(val statusCode: Int, val body: String, val failure: String? = null)

    /**
     * A GET or PUT against the bridge, with the response read and the failure named.
     *
     * A NON-2XX STATUS IS NOT A FAILURE OF THE TRANSPORT. The bridge answers 200 with an error array
     * for most refusals, and a 4xx means it answered and refused. Both carry a body worth reading,
     * so the body is returned either way and [HueApi.failureSentence] is what decides what it means.
     */
    private fun request(
        bridge: HueApi.Bridge,
        path: String,
        method: String = "GET",
        body: String? = null,
    ): Response = try {
        val url = URL("http://${bridge.host}$path")
        val connection = url.openConnection(Proxy.NO_PROXY) as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = TIMEOUT_MS
        connection.readTimeout = TIMEOUT_MS
        connection.setRequestProperty("Content-Type", "application/json")
        if (body != null) {
            connection.doOutput = true
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        }
        val status = connection.responseCode
        val stream = if (status in 200..299) connection.inputStream else connection.errorStream
        val text = stream?.bufferedReader(Charsets.UTF_8)?.use { reader ->
            // Bounded, like every other body read in this app: a device on the wifi could otherwise
            // stream until the heap dies, and this response is being read on a phone.
            val buffer = CharArray(256 * 1024)
            val read = reader.read(buffer)
            if (read <= 0) "" else String(buffer, 0, read)
        }.orEmpty()
        connection.disconnect()
        Log.d(TAG, "Hue $method $path -> $status (${text.length} chars)")
        Response(status, text)
    } catch (e: Exception) {
        Response(0, "", e.message ?: e.javaClass.simpleName)
    }

    /**
     * Find a bridge.
     *
     * TWO ROUTES, AND THE LOCAL ONE IS TRIED FIRST. `hueDiscover` asks the LAN for `_hue._tcp`
     * through the mDNS tool the app already has, so a phone with no internet can still find its own
     * bridge; only when that finds nothing does this ask the vendor's cloud endpoint, which exists
     * for the case where multicast is blocked.
     *
     * THIS FUNCTION DOES NOT READ THE LAN ITSELF, and it says so rather than implying otherwise: the
     * caller passes in whatever hosts its own mDNS scan produced. Duplicating a multicast listener
     * here would be a second discovery path that could disagree with the first, which is exactly the
     * fault #330 was two tools disagreeing about the same network.
     */
    suspend fun discover(localHosts: List<String> = emptyList()): List<HueApi.Bridge> =
        withContext(Dispatchers.IO) {
            val found = mutableListOf<HueApi.Bridge>()
            for (host in localHosts) found.add(HueApi.Bridge(host = host))

            if (found.isEmpty()) {
                runCatching {
                    val description = java.net.URL("https://discovery.meethue.com").openConnection(
                        java.net.Proxy.NO_PROXY,
                    ) as HttpURLConnection
                    description.connectTimeout = TIMEOUT_MS
                    description.readTimeout = TIMEOUT_MS
                    val body = description.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                    description.disconnect()
                    Regex("\"internalipaddress\"\\s*:\\s*\"([^\"]+)\"").findAll(body).forEach { match ->
                        found.add(HueApi.Bridge(host = match.groupValues[1]))
                    }
                }.onFailure { Log.d(TAG, "Hue cloud discovery unavailable: ${it.message}") }
            }

            found.distinctBy { it.host }
        }

    /**
     * The pairing handshake.
     *
     * The bridge only issues a key while its physical button has been pressed within the last
     * thirty seconds, and its answer until then is the documented "link button not pressed" error.
     * [HueApi.readPairingResponse] is what turns that into a state and a sentence, because "press
     * the button" and "pairing failed" are the same HTTP response and different instructions.
     */
    suspend fun pair(host: String): HueApi.PairingOutcome = withContext(Dispatchers.IO) {
        val bridge = HueApi.Bridge(host = host)
        val response = request(bridge, "/api", method = "POST", body = HueApi.PAIRING_BODY)
        val outcome = if (response.failure != null) {
            HueApi.PairingOutcome(
                HueApi.PairingState.UNEXPECTED,
                null,
                HueApi.failureSentence(host, null, "", response.failure),
            )
        } else {
            HueApi.readPairingResponse(response.body)
        }
        if (outcome.state == HueApi.PairingState.PAIRED && outcome.applicationKey != null) {
            saveBridge(HueApi.Bridge(host = host, applicationKey = outcome.applicationKey))
        }
        outcome
    }

    /** Read a resource and answer with the body, or the sentence explaining why not. */
    suspend fun read(bridge: HueApi.Bridge, path: String): Pair<String?, String?> = withContext(Dispatchers.IO) {
        val response = request(bridge, path)
        when {
            response.failure != null ->
                null to HueApi.failureSentence(bridge.host, null, "", response.failure)
            response.statusCode !in 200..299 || HueApi.needsRepairing(response.body) ->
                null to HueApi.failureSentence(bridge.host, response.statusCode, response.body)
            else -> response.body to null
        }
    }

    /** Send a state change and answer with whether the bridge accepted it. */
    suspend fun write(bridge: HueApi.Bridge, path: String, body: String): Pair<Boolean, String?> =
        withContext(Dispatchers.IO) {
            val response = request(bridge, path, method = "PUT", body = body)
            when {
                response.failure != null ->
                    false to HueApi.failureSentence(bridge.host, null, "", response.failure)
                response.statusCode !in 200..299 ->
                    false to HueApi.failureSentence(bridge.host, response.statusCode, response.body)
                HueApi.needsRepairing(response.body) ->
                    false to HueApi.failureSentence(bridge.host, response.statusCode, response.body)
                /*
                 * THE BRIDGE ANSWERS A SUCCESSFUL CHANGE WITH A SUCCESS ARRAY, and a rejected one
                 * with an error array under HTTP 200. Checking the body rather than the status is
                 * the difference between reporting a change that happened and one that did not.
                 */
                response.body.contains("\"error\"") ->
                    false to HueApi.failureSentence(bridge.host, response.statusCode, response.body)
                else -> true to null
            }
        }
}
