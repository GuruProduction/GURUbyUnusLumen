// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.fabric.tv

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * Roku's External Control Protocol. Issue #187.
 *
 * WHY THIS ADAPTER IS THE SIMPLEST AND THE MOST USEFUL TO PROVE FIRST. Roku needs no
 * pairing, no token and no handshake: it answers plain HTTP on port 8060 and accepts
 * a keypress as a POST to a URL. That makes it the one vendor where a failure is
 * unambiguously a wrong host or a sleeping set rather than a broken auth flow, so it
 * is the adapter that proves the whole shape works.
 *
 * IT ALSO COVERS MORE THAN ROKU. Hisense, TCL, Sharp, Philips, Element and ONN sell
 * Roku televisions, and every one of them speaks this same protocol. So one adapter
 * controls a large share of the sets sold in the last decade.
 *
 * THE EXACT ENDPOINTS, from Roku's own developer documentation:
 *
 *  - `GET  /query/device-info` returns XML with the model name, so discovery can tell
 *    a Roku from anything else that happens to listen on 8060.
 *  - `POST /keypress/<KEY>` presses a button. The keys are `PowerOff`, `VolumeUp`,
 *    `VolumeDown`, `VolumeMute`, `Home`, `Up`, `Down`, `Left`, `Right`, `Select`,
 *    `Back`, `Play`, `Pause`, `Stop`, `Rev`, `Fwd` and `Info`.
 *
 * WHAT IT CANNOT DO. `PowerOff` exists and `PowerOn` does not: a Roku turns a set off
 * but cannot turn it back on, because the box is asleep and not listening. That is a
 * property of the protocol and is reported as such rather than pretended.
 */
class RokuRemote(
    private val host: String,
    private val port: Int = 8060,
    private val timeoutMillis: Int = 4000,
) : TvRemote {

    override val vendor: TvVendor = TvVendor.ROKU

    override suspend fun probe(): String? = withContext(Dispatchers.IO) {
        val body = runCatching { request("GET", "/query/device-info") }.getOrNull()
            ?: return@withContext null
        if (!body.contains("<device-info", ignoreCase = true)) {
            return@withContext null
        }
        // The friendly name is what the person recognises, so it is what a discovery
        // result shows rather than a model number.
        val name = tag(body, "friendly-device-name")
            ?: tag(body, "model-name")
            ?: tag(body, "user-device-name")
            ?: "Roku"
        val model = tag(body, "model-name") ?: "unknown model"
        "$name ($model)"
    }

    override suspend fun press(button: TvButton): TvCommandOutcome = withContext(Dispatchers.IO) {
        val key = keyFor(button)
            ?: return@withContext TvCommandOutcome.Failed(
                if (button == TvButton.POWER_ON) {
                    "A Roku can turn a television off but cannot turn one on: the box is asleep " +
                        "when the set is off and receives nothing. Use the set's own remote for on."
                } else {
                    "Roku's protocol has no ${button.name.lowercase().replace('_', ' ')} key."
                },
            )

        runCatching { request("POST", "/keypress/$key") }.fold(
            onSuccess = {
                TvCommandOutcome.Sent(
                    "Roku accepted the $key keypress on $host. " +
                        if (button == TvButton.POWER_OFF || button == TvButton.POWER) {
                            "Whether the picture went off cannot be read back from a set that has " +
                                "gone to standby."
                        } else {
                            "Roku acknowledges the key was delivered."
                        },
                )
            },
            onFailure = { error ->
                TvCommandOutcome.Failed(
                    "The Roku at $host:$port did not accept the $key keypress: " +
                        "${error.message ?: error.javaClass.simpleName}. A set in deep standby stops " +
                        "answering until it is woken.",
                )
            },
        )
    }

    /**
     * Read what a Roku will tell.
     *
     * The device-info document carries the power mode and the current app, so power and
     * app are real readings here rather than guesses. Volume is not in that document,
     * so it is absent instead of invented.
     */
    override suspend fun readState(): Map<TvStateKey, String>? = withContext(Dispatchers.IO) {
        val body = runCatching { request("GET", "/query/device-info") }.getOrNull()
            ?: return@withContext null
        val state = mutableMapOf<TvStateKey, String>()
        tag(body, "power-mode")?.let { state[TvStateKey.POWER] = it }
        tag(body, "current-app")?.let { state[TvStateKey.APP] = it }
        if (state.isEmpty()) null else state
    }

    /** Roku's own key names, which are not the vendor-neutral tokens. */
    private fun keyFor(button: TvButton): String? = when (button) {
        TvButton.POWER, TvButton.POWER_OFF -> "PowerOff"
        // There is no PowerOn key, which is a protocol limit rather than an oversight.
        TvButton.POWER_ON -> null
        TvButton.VOLUME_UP -> "VolumeUp"
        TvButton.VOLUME_DOWN -> "VolumeDown"
        TvButton.MUTE -> "VolumeMute"
        TvButton.CHANNEL_UP -> "ChannelUp"
        TvButton.CHANNEL_DOWN -> "ChannelDown"
        TvButton.UP -> "Up"
        TvButton.DOWN -> "Down"
        TvButton.LEFT -> "Left"
        TvButton.RIGHT -> "Right"
        TvButton.OK -> "Select"
        TvButton.BACK -> "Back"
        TvButton.HOME -> "Home"
        TvButton.MENU -> "Info"
        TvButton.PLAY -> "Play"
        TvButton.PAUSE -> "Play"
        TvButton.STOP -> "Stop"
        TvButton.REWIND -> "Rev"
        TvButton.FAST_FORWARD -> "Fwd"
        TvButton.INPUT -> "Info"
    }

    /** One HTTP call, returning the body or throwing with the platform's own words. */
    private fun request(method: String, path: String): String {
        val connection = URL("http://$host:$port$path").openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = method
            connection.connectTimeout = timeoutMillis
            connection.readTimeout = timeoutMillis
            // Roku wants a POST for a keypress and cares about neither a body nor a type,
            // but some firmware refuses a POST with no content length at all.
            if (method == "POST") {
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(0)
                connection.outputStream.close()
            }
            val code = connection.responseCode
            if (code !in 200..299) {
                throw IllegalStateException("the set answered HTTP $code")
            }
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    /** The text of one XML tag, or null when it is absent. */
    private fun tag(xml: String, name: String): String? =
        Regex("<$name[^>]*>([^<]*)</$name>", RegexOption.IGNORE_CASE)
            .find(xml)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            ?.ifBlank { null }
}
