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

        val message = """{"method":"ms.remote.control","params":{"Cmd":"Click","""
            .plus(""""DataOfCmd":"$key",""")
            .plus(""""Option":"false","TypeOfRemote":"SendRemoteKey"}}""")

        send(message, "the $key keypress")
    }

    /**
     * Launch an app by name.
     *
     * THE CAPABILITY THAT MAKES A REMOTE INTO CONTROL. Pressing buttons means walking the app
     * grid with the D-pad. This asks the set to start the app directly, over the same channel a
     * keypress uses.
     *
     * The message is `ms.channel.emit` on the app channel rather than a remote key, and the
     * payload carries the app id, which is why [TvApps] holds the Samsung mapping.
     */
    override suspend fun launchApp(app: String): TvCommandOutcome = withContext(Dispatchers.IO) {
        val id = TvApps.samsungId(app)
            ?: return@withContext TvCommandOutcome.Failed(
                TvApps.refusal("The Samsung set at $host", TvVendor.SAMSUNG, app),
            )

        val message = """{"method":"ms.channel.emit","params":{"event":"ed.apps.launch","""
            .plus(""""to":"host",""")
            .plus(""""data":{"action_type":"DEEP_LINK","appId":"$id"}}}""")

        send(message, "launch '$app' (app id $id)")
    }

    /**
     * Type text into a focused Samsung field.
     *
     * Samsung's text channel takes an IME string, so a person can fill a search box rather than
     * spelling it out with directional presses.
     */
    override suspend fun typeText(text: String): TvCommandOutcome = withContext(Dispatchers.IO) {
        if (text.isEmpty()) {
            return@withContext TvCommandOutcome.Failed("There is no text to type.")
        }
        val escaped = text.replace("\\", "\\\\").replace("\"", "\\\"")
        val message = """{"method":"ms.remote.control","params":{"Cmd":"SendInputString","""
            .plus(""""DataOfCmd":"base64:${java.util.Base64.getEncoder().encodeToString(text.toByteArray())}",""")
            .plus(""""TypeOfRemote":"SendInputString"}}""")

        send(message, "type text")
    }

    /**
     * Push a stream onto a Samsung set.
     *
     * The remote channel does not accept a media URL, so this refuses rather than sending a
     * message the television will drop. Casting to a Samsung set goes through the Cast or DIAL
     * layer, not the remote channel.
     */
    override suspend fun castMedia(
        url: String,
        title: String?,
        mimeType: String?,
    ): TvCommandOutcome = TvCommandOutcome.Failed(
        "Samsung's remote channel does not accept a media URL, so nothing was sent. Use launchApp " +
            "to start an app and typeText to search inside it, both of which this adapter supports.",
    )

    /**
     * Send one message on a fresh channel and report what came back.
     *
     * EVERY Samsung command goes through here, so the token handling, the acknowledgement and the
     * pairing prompt live in one place rather than three. The keypress path passes its own message
     * and this method does not care what it says.
     */
    private fun send(message: String, describe: String): TvCommandOutcome {
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

        val path = url.removePrefix("ws://$host:$port")
        val result = WebSocketClient.connect(host, port, path, timeoutMillis)
        val client = result.client
            ?: return TvCommandOutcome.Failed(
                "Could not open Samsung's channel on $host:$port to $describe: ${result.reason}.",
            )

        return try {
            val greeting = runCatching { client.readText() }.getOrNull()
                ?: return TvCommandOutcome.Failed(
                    "Samsung's channel opened but the set did not acknowledge it, so $describe was " +
                        "not sent.",
                    needsPairing = true,
                )

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

            client.sendText(message)
            TvCommandOutcome.Sent(
                "Samsung accepted $describe on $host. " +
                    if (issuedToken != null) {
                        "The set issued a token, so it will not ask again."
                    } else {
                        "The set issued no token, so it may ask for permission again next time."
                    },
            )
        } catch (error: Exception) {
            TvCommandOutcome.Failed(
                "Samsung's channel broke while sending $describe: " +
                    "${error.message ?: error.javaClass.simpleName}.",
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
