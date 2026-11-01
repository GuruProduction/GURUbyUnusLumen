// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.fabric.tv

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Samsung's Tizen remote, over the WebSocket the set opens on port 8001. Issue #187.
 *
 * THE PROTOCOL, as every open client implements it:
 *
 *  1. Connect to `ws://<ip>:8001/api/v2/channels/samsung.remote.control`.
 *  2. The set answers with an `ms.channel.connect` event. The first time, that reply
 *     contains no token, and the television shows an "Allow this device?" prompt.
 *  3. Every keypress is one JSON message:
 *     `{"method":"ms.remote.control","params":{"Cmd":"Click",
 *       "DataOfCmd":"KEY_VOLUP","Option":"false","TypeOfRemote":"SendRemoteKey"}}`
 *
 * THE TOKEN IS THE WHOLE DIFFICULTY. After the person accepts the prompt, the set
 * includes a token in the connect reply, and that token is what stops the prompt
 * appearing on every single connection. It belongs in the URL query string as
 * `?name=<app>&token=<token>`, so it is kept here and reported back for storage.
 *
 * WHAT IT CANNOT DO. A set that is fully off has no network stack, so nothing wakes it
 * over wifi. Samsung sets have a "Network Standby" or "Remote Control" setting that must
 * be on for a powered-down set to answer, which is named in the refusal because it is
 * the only thing that fixes it.
 */
class SamsungRemote(
    private val host: String,
    private val port: Int = 8001,
    private val appName: String = "GURU",
    private val token: String? = null,
    private val timeoutMillis: Int = 5000,
) : TvRemote {

    override val vendor: TvVendor = TvVendor.SAMSUNG

    /** The token the set issued, which a caller should keep and pass back next time. */
    @Volatile
    var issuedToken: String? = null
        private set

    /** Whether the set asked a person to accept this connection. */
    @Volatile
    var pairingPromptShown: Boolean = false
        private set

    override suspend fun probe(): String? = withContext(Dispatchers.IO) {
        // The REST endpoint on the same port answers without a WebSocket handshake, so
        // it is the cheapest way to say whether a Samsung set is there at all.
        runCatching {
            val url = java.net.URL("http://$host:$port/api/v2/")
            val connection = url.openConnection() as java.net.HttpURLConnection
            try {
                connection.connectTimeout = timeoutMillis
                connection.readTimeout = timeoutMillis
                val body = connection.inputStream.bufferedReader().use { it.readText() }
                val name = Regex("\"name\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.getOrNull(1)
                val model = Regex("\"modelName\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.getOrNull(1)
                "${name ?: "Samsung television"} (${model ?: "unknown model"})"
            } finally {
                connection.disconnect()
            }
        }.getOrNull()
    }

    override suspend fun press(button: TvButton): TvCommandOutcome = withContext(Dispatchers.IO) {
        val key = keyFor(button)
            ?: return@withContext TvCommandOutcome.Failed(
                "Samsung's remote protocol has no ${button.name.lowercase().replace('_', ' ')} key.",
            )

        connect(key)
    }

    /**
     * Open the channel, send one key, and close it.
     *
     * A connection per press rather than a held channel: a television closes an idle
     * channel itself, so a cached socket is a socket that is usually dead, and a stale
     * socket fails in exactly the way a wrong code does.
     *
     * THE KEY IS PASSED IN rather than computed here. It was computed in `press` and
     * referenced here, which does not compile, and more importantly would have been the
     * wrong shape: the key is the caller's decision and this method's job is only the
     * channel.
     */
    private fun connect(key: String): TvCommandOutcome {
        val url = buildString {
            append("ws://$host:$port/api/v2/channels/samsung.remote.control")
            append("?name=")
            append(java.net.URLEncoder.encode(appName, "UTF-8"))
            val existing = issuedToken ?: token
            if (!existing.isNullOrBlank()) {
                append("&token=")
                append(java.net.URLEncoder.encode(existing, "UTF-8"))
            }
        }

        // The full path INCLUDING the query string, because the query carries the token
        // and a channel opened without it makes the set ask for permission again.
        val path = url.removePrefix("ws://$host:$port")
        val result = WebSocketClient.connect(host, port, path, timeoutMillis)
        val client = result.client
            ?: return TvCommandOutcome.Failed(
                "Could not open Samsung's remote channel on $host:$port: ${result.reason}." +
                    " A set that is fully powered down has no network stack and cannot be woken " +
                    "over wifi; Samsung's Network Standby setting has to be on for that.",
            )

        return try {
            // The connect acknowledgement carries the token, so it is read before the
            // keypress rather than after: a set that has not acknowledged is not ready.
            val greeting = runCatching { client.readText() }.getOrNull()
            if (greeting == null) {
                return TvCommandOutcome.Failed(
                    "Samsung's channel opened but the set did not acknowledge the connection, so " +
                        "nothing was sent.",
                    needsPairing = true,
                )
            }
            Regex("\"token\"\\s*:\\s*\"([^\"]+)\"").find(greeting)?.groupValues?.getOrNull(1)?.let {
                issuedToken = it
            }
            if (greeting.contains("ms.channel.unauthorized")) {
                pairingPromptShown = true
                return TvCommandOutcome.Failed(
                    "The television refused the connection. Look at the screen: it shows an " +
                        "Allow this device prompt, and accepting it lets GURU control the set.",
                    needsPairing = true,
                )
            }

            val message = """{"method":"ms.remote.control","params":{"Cmd":"Click","""
                .plus(""""DataOfCmd":"$key",""")
                .plus(""""Option":"false","TypeOfRemote":"SendRemoteKey"}}""")
            client.sendText(message)
            TvCommandOutcome.Sent(
                "Samsung accepted the $key keypress on $host. " +
                    if (issuedToken != null) {
                        "The set issued a token, so it will not ask again."
                    } else {
                        "The set issued no token, so it may ask for permission again next time."
                    },
            )
        } catch (error: Exception) {
            TvCommandOutcome.Failed(
                "Samsung's channel broke while sending: ${error.message ?: error.javaClass.simpleName}.",
            )
        } finally {
            client.close()
        }
    }

    /**
     * Samsung's key names, which are `KEY_` tokens.
     *
     * `KEY_POWER` is a toggle, and there is no separate on or off: the protocol has one
     * power key and the set decides what it means.
     */
    private fun keyFor(button: TvButton): String? = when (button) {
        TvButton.POWER, TvButton.POWER_ON, TvButton.POWER_OFF -> "KEY_POWER"
        TvButton.VOLUME_UP -> "KEY_VOLUP"
        TvButton.VOLUME_DOWN -> "KEY_VOLDOWN"
        TvButton.MUTE -> "KEY_MUTE"
        TvButton.CHANNEL_UP -> "KEY_CHUP"
        TvButton.CHANNEL_DOWN -> "KEY_CHDOWN"
        TvButton.UP -> "KEY_UP"
        TvButton.DOWN -> "KEY_DOWN"
        TvButton.LEFT -> "KEY_LEFT"
        TvButton.RIGHT -> "KEY_RIGHT"
        TvButton.OK -> "KEY_ENTER"
        TvButton.BACK -> "KEY_RETURN"
        TvButton.HOME -> "KEY_HOME"
        TvButton.MENU -> "KEY_MENU"
        TvButton.PLAY -> "KEY_PLAY"
        TvButton.PAUSE -> "KEY_PAUSE"
        TvButton.STOP -> "KEY_STOP"
        TvButton.REWIND -> "KEY_REWIND"
        TvButton.FAST_FORWARD -> "KEY_FF"
        TvButton.INPUT -> "KEY_SOURCE"
    }
}
