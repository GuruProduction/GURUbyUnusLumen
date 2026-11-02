// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.fabric.tv

import com.unuslumen.app.data.fabric.net.DeviceAnswer
import com.unuslumen.app.data.fabric.net.LocalHttp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
 *
 * THE ORIGIN HEADER, WHICH IS ISSUE #252. Roku OS 15.3.4 refuses a POST that carries no
 * `Origin` header, answers HTTP 403 with the body "Origin  is not allowed access", and
 * `HttpURLConnection` silently drops that header because it is on the JDK's reserved list.
 * A probe against a local listener confirmed it: a custom header arrived and `Origin` was
 * absent from what the server received. So this adapter speaks through [LocalHttp], which
 * writes every header the caller asks for, and it sends the set's own origin, which is the
 * value a direct client is allowed to send.
 *
 * A REFUSAL IS NOT AN ABSENCE. The same firmware made the old code call an answering set
 * "in deep standby", which is a claim that it stopped answering. Here a status the set
 * chose to send is reported as its answer with its own body, and only a request that
 * produced no response at all may be described as unreachable.
 */
class RokuRemote(
    private val host: String,
    private val port: Int = 8060,
    private val timeoutMillis: Int = 4000,
) : TvRemote {

    override val vendor: TvVendor = TvVendor.ROKU

    override suspend fun probe(): String? = withContext(Dispatchers.IO) {
        val body = bodyOf(request("GET", "/query/device-info")) ?: return@withContext null
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

        val subject = "The Roku at $host:$port"
        when (val outcome = request("POST", "/keypress/$key")) {
            is LocalHttp.Outcome.Answered -> {
                val response = outcome.response
                if (!response.ok) {
                    return@withContext TvCommandOutcome.Failed(
                        DeviceAnswer.describe(subject, response),
                    )
                }
                TvCommandOutcome.Sent(
                    "Roku accepted the $key keypress on $host. " +
                        if (button == TvButton.POWER_OFF || button == TvButton.POWER) {
                            "Whether the picture went off cannot be read back from a set that " +
                                "has gone to standby."
                        } else {
                            "Roku acknowledges the key was delivered."
                        },
                )
            }
            is LocalHttp.Outcome.NoAnswer -> TvCommandOutcome.Failed(
                "$subject did not accept the $key keypress: " +
                    DeviceAnswer.describeNoAnswer(subject.lowercase(), outcome.reason),
            )
        }
    }

    /**
     * Read what a Roku will tell.
     *
     * The device-info document carries the power mode and the current app, so power and
     * app are real readings here rather than guesses. Volume is not in that document,
     * so it is absent instead of invented.
     */
    override suspend fun readState(): Map<TvStateKey, String>? = withContext(Dispatchers.IO) {
        val body = bodyOf(request("GET", "/query/device-info")) ?: return@withContext null
        val state = mutableMapOf<TvStateKey, String>()
        tag(body, "power-mode")?.let { state[TvStateKey.POWER] = it }
        tag(body, "current-app")?.let { state[TvStateKey.APP] = it }
        if (state.isEmpty()) null else state
    }

    /**
     * Launch a channel by name.
     *
     * THE WHOLE POINT OF THIS ADAPTER GETTING BIGGER. Pressing buttons means walking a grid of
     * tiles with the D-pad. Launching means the set goes straight there, which is what a person
     * means by "put Netflix on".
     *
     * The endpoint is `/launch/<channelId>`, and Roku's channel ids are stable published numbers
     * rather than names, which is why [TvApps] holds the mapping.
     */
    override suspend fun launchApp(app: String): TvCommandOutcome = withContext(Dispatchers.IO) {
        val id = TvApps.rokuId(app)
            ?: return@withContext TvCommandOutcome.Failed(
                TvApps.refusal("The Roku at $host", TvVendor.ROKU, app),
            )

        val subject = "The Roku at $host:$port"
        when (val outcome = request("POST", "/launch/$id")) {
            is LocalHttp.Outcome.Answered -> {
                val response = outcome.response
                if (!response.ok) {
                    return@withContext TvCommandOutcome.Failed(
                        "The Roku refused to launch '$app': " +
                            DeviceAnswer.describe(subject.lowercase(), response) +
                            " A channel that is not installed answers HTTP 404, which is a real " +
                            "answer rather than a fault.",
                    )
                }
                TvCommandOutcome.Sent(
                    "The Roku at $host launched '$app' (channel $id). Whether a person can see it " +
                        "depends on that channel being installed on the set.",
                )
            }
            is LocalHttp.Outcome.NoAnswer -> TvCommandOutcome.Failed(
                "The Roku did not answer the launch of '$app': " +
                    DeviceAnswer.describeNoAnswer(subject.lowercase(), outcome.reason),
            )
        }
    }

    /**
     * Type text into a focused Roku search or keyboard field.
     *
     * Roku accepts a string through `/keypress/Lit_<urlencoded>`, which is the same mechanism its
     * own remote app uses. The text must already have somewhere to go, so this requires a search
     * box or keyboard on screen, and the refusal says that rather than pretending the text landed.
     */
    override suspend fun typeText(text: String): TvCommandOutcome = withContext(Dispatchers.IO) {
        if (text.isEmpty()) {
            return@withContext TvCommandOutcome.Failed("There is no text to type.")
        }
        // Roku wants each character as its own literal keypress string, which is what the
        // Lit_ prefix means. Sending one path per character is the documented shape.
        val encoded = java.net.URLEncoder.encode(text, "UTF-8")
        val subject = "The Roku at $host:$port"
        when (val outcome = request("POST", "/keypress/Lit_$encoded")) {
            is LocalHttp.Outcome.Answered -> {
                val response = outcome.response
                if (!response.ok) {
                    return@withContext TvCommandOutcome.Failed(
                        "The Roku did not accept the text: " +
                            DeviceAnswer.describe(subject.lowercase(), response) +
                            " Open a search box first.",
                    )
                }
                TvCommandOutcome.Sent(
                    "The Roku at $host accepted '$text'. It lands only if a search box or keyboard " +
                        "is already on screen, because a set with the home screen showing has " +
                        "nowhere to put it.",
                )
            }
            is LocalHttp.Outcome.NoAnswer -> TvCommandOutcome.Failed(
                "The Roku did not answer the text entry: " +
                    DeviceAnswer.describeNoAnswer(subject.lowercase(), outcome.reason),
            )
        }
    }

    /**
     * Push a stream onto a Roku.
     *
     * Roku's ECP does not accept an arbitrary media URL, so this refuses rather than sending
     * something the set will ignore. The honest route to a pushed stream on Roku is a channel
     * that accepts a content id, which is a different protocol from the remote.
     */
    override suspend fun castMedia(
        url: String,
        title: String?,
        mimeType: String?,
    ): TvCommandOutcome = withContext(Dispatchers.IO) {
        TvCommandOutcome.Failed(
            "A Roku's remote protocol does not accept a media URL, so nothing was sent. To play " +
                "something on demand, launch a channel with launchApp and search inside it with " +
                "typeText, which this adapter does support.",
        )
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

    /**
     * One HTTP call to the set, carrying the headers Roku requires.
     *
     * TWO THINGS THIS DOES THAT THE PREVIOUS IMPLEMENTATION COULD NOT.
     *
     * It writes `Origin`, which Roku OS 15.3.4 requires on a POST and which
     * `HttpURLConnection` drops without a word because `Origin` is on the JDK's reserved
     * header list. The value is the set's own origin, which is what a direct client is
     * entitled to claim; the alternative is a fabricated permission.
     *
     * It reports an error status as an answer rather than throwing, so a 403 arrives with
     * the set's own body intact instead of being collapsed into a bare "HTTP 403" and then
     * attributed to standby by a layer that could not see the difference.
     *
     * THE CONNECTION IS DIRECT, and that is deliberate. Every other outbound path in this
     * app is injected with a Tor SOCKS proxy, and Tor cannot route to a private address, so
     * a Roku fetch through the proxy died with a bare 000 that looked exactly like a set
     * that was switched off. [LocalHttp] refuses a public host for the same reason, so a
     * mistyped address cannot become a silent leak either.
     */
    private fun request(method: String, path: String): LocalHttp.Outcome =
        LocalHttp.request(
            host = host,
            port = port,
            method = method,
            path = path,
            // Roku wants a POST for a keypress and cares about neither a body nor a type,
            // so the header set is the only thing that has to be right.
            headers = if (method == "POST") {
                mapOf("Origin" to LocalHttp.sameOrigin(host, port))
            } else {
                emptyMap()
            },
            timeoutMillis = timeoutMillis,
        )

    /** The body of an answer, or null when the set did not answer at all. */
    private fun bodyOf(outcome: LocalHttp.Outcome): String? = when (outcome) {
        is LocalHttp.Outcome.Answered -> outcome.response.body
        is LocalHttp.Outcome.NoAnswer -> null
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
