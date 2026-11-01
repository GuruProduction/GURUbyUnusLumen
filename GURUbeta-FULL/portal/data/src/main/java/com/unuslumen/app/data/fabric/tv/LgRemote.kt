// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.fabric.tv

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * LG's webOS remote, over the WebSocket on port 3000. Issue #187.
 *
 * THE PROTOCOL, as every open client implements it:
 *
 *  1. Connect to `ws://<ip>:3000` with no path.
 *  2. Send a register message:
 *     `{"id":"register_0","type":"register","payload":{"forcePairing":false,
 *       "pairingType":"PROMPT","client-key":"<stored or absent>",
 *       "manifest":{"manifestVersion":1,...}}}`
 *  3. The set shows a pairing prompt. On acceptance it answers with
 *     `{"type":"registered","payload":{"client-key":"<key>"}}`, and that key means no
 *     prompt next time.
 *  4. Commands are `{"id":"<n>","type":"request","uri":"ssap://audio/volumeUp"}`.
 *
 * WHY THE MANIFEST MATTERS. webOS refuses a register with no manifest, and the manifest
 * is what declares which permissions the client is asking for. A remote needs the
 * audio, media and television control groups, so those are the ones declared. Sending a
 * minimal manifest produces a set that pairs and then refuses every command, which
 * looks exactly like a broken adapter.
 *
 * WHAT IT CANNOT DO. Same as every network remote: a set with no power to its network
 * board cannot be woken, and webOS has a "Mobile TV On" or "Quick Start+" setting that
 * has to be on for it to listen while the screen is off.
 */
class LgRemote(
    private val host: String,
    private val port: Int = 3000,
    private val clientKey: String? = null,
    private val timeoutMillis: Int = 5000,
) : TvRemote {

    override val vendor: TvVendor = TvVendor.LG

    /** The client key webOS issued, which a caller should store and pass back. */
    @Volatile
    var issuedKey: String? = null
        private set

    @Volatile
    var pairingPromptShown: Boolean = false
        private set

    override suspend fun probe(): String? = withContext(Dispatchers.IO) {
        // webOS answers a plain HTTP GET on the same port with a description document,
        // which is enough to confirm the set is there without a pairing prompt.
        runCatching {
            val url = java.net.URL("http://$host:$port/")
            // Direct, never through the Tor proxy: a private address cannot route over a circuit,
            // and the failure looks exactly like a television that is switched off.
            val connection = com.unuslumen.app.data.fabric.net.LocalRoute.open(url.toString())
                ?: return@runCatching null
            try {
                connection.connectTimeout = timeoutMillis
                connection.readTimeout = timeoutMillis
                val body = connection.inputStream.bufferedReader().use { it.readText() }
                val model = Regex("\"modelName\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.getOrNull(1)
                val name = Regex("\"deviceName\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.getOrNull(1)
                "${name ?: "LG television"} (${model ?: "webOS"})"
            } finally {
                connection.disconnect()
            }
        }.getOrNull()
    }

    override suspend fun press(button: TvButton): TvCommandOutcome = withContext(Dispatchers.IO) {
        val uri = uriFor(button)
            ?: return@withContext TvCommandOutcome.Failed(unsupportedReason(button))
        send(uri, button)
    }

    override suspend fun setLevel(key: TvStateKey, value: Int): TvCommandOutcome =
        withContext(Dispatchers.IO) {
            when (key) {
                TvStateKey.VOLUME -> {
                    val level = value.coerceIn(0, 100)
                    send("ssap://audio/setVolume", TvButton.POWER, """{"volume":$level}""")
                }
                TvStateKey.MUTED -> {
                    send(
                        "ssap://audio/setMute",
                        TvButton.POWER,
                        """{"mute":${if (value != 0) "true" else "false"}}""",
                    )
                }
                else -> TvCommandOutcome.Failed(
                    "webOS cannot set ${key.name.lowercase()} to an exact value.",
                )
            }
        }

    /**
     * Register, send one request, and close.
     *
     * A connection per command for the same reason as Samsung: webOS drops an idle
     * channel, and a cached socket that died is indistinguishable from a refused
     * command.
     */
    private fun send(uri: String, button: TvButton, extraPayload: String? = null): TvCommandOutcome {
        val result = WebSocketClient.connect(host, port, "/", timeoutMillis)
        val client = result.client
            ?: return TvCommandOutcome.Failed(
                "Could not open webOS's remote channel on $host:$port: ${result.reason}. A set " +
                    "whose Quick Start or Mobile TV On setting is off stops listening when the " +
                    "screen goes off, and nothing can wake it over wifi.",
            )

        return try {
            val register = buildString {
                append("""{"id":"register_0","type":"register","payload":{""")
                append(""""forcePairing":false,"pairingType":"PROMPT",""")
                append(""""client-key":"${clientKey ?: issuedKey ?: ""}",""")
                append(""""manifest":{""")
                append(""""manifestVersion":1,""")
                append(""""appVersion":"1.1",""")
                append(""""signed":{"appId":"com.unuslumen.guru",""")
                append(""""created":"20260101","vendor":"Unus Lumen"},""")
                // The permission groups a remote actually needs. Without them webOS
                // registers and then refuses the command, which reads as a broken adapter.
                append(""""permissions":[""")
                append(""""CONTROL_AUDIO","CONTROL_MEDIA_PLAYBACK","CONTROL_POWER",""")
                append(""""CONTROL_INPUT_TV","CONTROL_INPUT_JOYSTICK",""")
                append(""""CONTROL_INPUT_MEDIA_RECORDING","CONTROL_INPUT_TEXT",""")
                append(""""READ_INSTALLED_APPS","READ_TV_CURRENT_TIME","READ_CURRENT_CHANNEL",""")
                append(""""READ_POWER_STATE","WRITE_SETTINGS"]""")
                append("}}}")
            }
            client.sendText(register)

            val registered = runCatching { client.readText() }.getOrNull()
                ?: return TvCommandOutcome.Failed(
                    "webOS's channel opened but the set did not answer the register request, so " +
                        "nothing was sent.",
                    needsPairing = true,
                )

            if (registered.contains("\"type\":\"error\"")) {
                pairingPromptShown = true
                return TvCommandOutcome.Failed(
                    "The television refused GURU's registration. Look at the screen: webOS shows a " +
                        "pairing prompt, and accepting it lets GURU control the set.",
                    needsPairing = true,
                )
            }
            Regex("\"client-key\"\\s*:\\s*\"([^\"]+)\"").find(registered)
                ?.groupValues?.getOrNull(1)
                ?.let { issuedKey = it }

            val message = buildString {
                append("""{"id":"cmd_1","type":"request","uri":"$uri"""")
                if (extraPayload != null) {
                    append(""","payload":$extraPayload""")
                }
                append("}")
            }
            client.sendText(message)

            // webOS answers every request, so a reply carrying an error is a real refusal
            // and is reported as one rather than assumed to have worked.
            val reply = runCatching { client.readText() }.getOrNull()
            if (reply != null && reply.contains("\"type\":\"error\"")) {
                val detail = Regex("\"errorText\"\\s*:\\s*\"([^\"]+)\"").find(reply)?.groupValues?.getOrNull(1)
                return TvCommandOutcome.Failed(
                    "webOS refused ${button.name.lowercase().replace('_', ' ')}: " +
                        "${detail ?: "the set returned an error"}.",
                )
            }

            TvCommandOutcome.Sent(
                "webOS accepted $uri on $host." +
                    if (issuedKey != null) " The set issued a client key, so it will not ask again." else "",
            )
        } catch (error: Exception) {
            TvCommandOutcome.Failed(
                "webOS's channel broke while sending: ${error.message ?: error.javaClass.simpleName}.",
            )
        } finally {
            client.close()
        }
    }

    /**
     * The ssap:// URI for each button.
     *
     * WHAT IS A REAL SSAP COMMAND AND WHAT IS NOT, stated because the difference is the
     * whole adapter. webOS exposes power, volume and channel as their own SSAP URIs,
     * and those are implemented here because they are the commands this feature needs
     * most and their shape is documented.
     *
     * D-PAD AND MEDIA KEYS ARE NOT SSAP COMMANDS. webOS sends them over a SECOND socket
     * obtained from `ssap://com.webos.service.networkinput/getPointerInputSocket`, as a
     * text frame whose format I have not been able to verify against a source I trust.
     * Inventing one would produce a D-pad that transmits into a socket and does nothing,
     * which is precisely the defect this project keeps removing. So they refuse with a
     * sentence naming the reason, and the gap is visible rather than silent.
     */
    private fun uriFor(button: TvButton): String? = when (button) {
        TvButton.POWER, TvButton.POWER_OFF -> "ssap://system/turnOff"
        TvButton.VOLUME_UP -> "ssap://audio/volumeUp"
        TvButton.VOLUME_DOWN -> "ssap://audio/volumeDown"
        TvButton.MUTE -> "ssap://audio/setMute"
        TvButton.CHANNEL_UP -> "ssap://tv/channelUp"
        TvButton.CHANNEL_DOWN -> "ssap://tv/channelDown"
        // Power on is NOT a working SSAP URI on a set that is already off, because a set
        // that is off has no listener. It is refused rather than attempted for that
        // reason, and the sentence says what the setting is that makes it possible.
        else -> null
    }

    /** Why a button has no URI, so a refusal names the real cause. */
    private fun unsupportedReason(button: TvButton): String = when (button) {
        TvButton.POWER_ON ->
            "A television that is fully off has no network stack, so nothing over wifi can wake " +
                "it. webOS needs its Quick Start or Mobile TV On setting switched on for the set " +
                "to listen while the screen is off."
        TvButton.UP, TvButton.DOWN, TvButton.LEFT, TvButton.RIGHT, TvButton.OK,
        TvButton.BACK, TvButton.HOME, TvButton.MENU, TvButton.PLAY, TvButton.PAUSE,
        TvButton.STOP, TvButton.REWIND, TvButton.FAST_FORWARD, TvButton.INPUT ->
            "webOS sends ${button.name.lowercase().replace('_', ' ')} over a separate pointer " +
                "input socket rather than as an SSAP command, and that socket's message format " +
                "has not been verified on a set, so GURU refuses rather than sending something " +
                "that would do nothing."
        else -> "webOS has no ${button.name.lowercase().replace('_', ' ')} command."
    }
}
