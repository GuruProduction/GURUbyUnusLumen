// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.fabric.tv

import com.unuslumen.app.data.fabric.net.DialClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Amazon Fire TV, over DIAL on port 8009. Issue #243.
 *
 * WHY THIS ADAPTER EXISTS AT ALL. A Fire TV was being reported as an Android TV because
 * port 8009 was open, and the note that came with it said the set "uses its own pairing
 * protocol with an on-screen code". That sentence was wrong twice over: the device is
 * not an Android TV, and it accepts app launches over plain HTTP on the port the scan
 * had already found.
 *
 * WHAT IS PROVEN HERE AND WHAT IS NOT. The launch path is DIAL, which is the documented
 * Amazon mechanism and the same one [DialClient] already speaks: read the description,
 * take the `Application-URL` header, POST the app name. Pressing buttons is a different
 * protocol entirely — Fire TV remote keys go over an encrypted ADB-style channel that
 * needs a pairing code shown on the screen — so [press] refuses honestly rather than
 * pretending, and names what would be needed.
 *
 * A refusal that says "not this, but here is what does work" is the honest answer. A
 * refusal that claims the whole device is undrivable was the defect.
 */
class FireTvRemote(
    private val host: String,
    private val port: Int = 8009,
    private val timeoutMillis: Int = 4000,
) : TvRemote {

    override val vendor: TvVendor = TvVendor.AMAZON_FIRE_TV

    private val dial = DialClient(host, port, timeoutMillis)

    override suspend fun probe(): String? = withContext(Dispatchers.IO) {
        val list = dial.appList().getOrNull() ?: return@withContext null
        if (list.applicationUrl.isBlank()) return@withContext null
        "DIAL answered with an application URL, and listed ${list.apps.size} app(s)"
    }

    /**
     * Launch an app by the name a person says.
     *
     * Amazon's own app names are the spoken ones: Netflix, YouTube, Prime Video. DIAL
     * takes the name as the path segment, so the mapping here is only normalisation.
     */
    override suspend fun launchApp(app: String): TvCommandOutcome = withContext(Dispatchers.IO) {
        val target = amazonAppId(app)
            ?: return@withContext TvCommandOutcome.Failed(
                "A Fire TV does not have an app called '$app' that this build knows. " +
                    "It accepts these names: ${SPOKEN.joinToString(", ")}.",
            )

        // THE APPLICATION URL COMES FROM THE SET, NOT FROM THIS FILE.
        //
        // DIAL puts the launch base in the `Application-URL` HEADER of the description
        // response, and a device that omits it cannot be launched even if it lists apps.
        // Reading it first is also the reachability check: an asleep set fails here rather
        // than being reported as having been told to open something.
        val list = dial.appList().getOrElse { error ->
            return@withContext TvCommandOutcome.Failed(
                "The Fire TV did not answer its DIAL description: " +
                    "${error.message ?: error.javaClass.simpleName}. A Fire TV only answers while " +
                    "it is awake and on the same wifi as the phone.",
            )
        }
        if (list.applicationUrl.isBlank()) {
            return@withContext TvCommandOutcome.Failed(
                "The Fire TV answered but published no application URL, so it cannot be launched " +
                    "over DIAL from here.",
            )
        }

        dial.launch(list.applicationUrl, target).fold(
            onSuccess = {
                TvCommandOutcome.Sent(
                    "Asked the Fire TV to open $app over DIAL. An awake set opens it within a " +
                        "second or two.",
                )
            },
            onFailure = { error ->
                TvCommandOutcome.Failed(
                    "The Fire TV did not accept the launch of $app: " +
                        "${error.message ?: error.javaClass.simpleName}. A Fire TV only answers " +
                        "DIAL while it is awake and its remote feature is on.",
                )
            },
        )
    }

    /** Stop the app that is running, which DIAL supports for a named app. */
    suspend fun stop(app: String): TvCommandOutcome = withContext(Dispatchers.IO) {
        val target = amazonAppId(app)
            ?: return@withContext TvCommandOutcome.Failed(
                "A Fire TV does not have an app called '$app' that this build knows.",
            )
        val list = dial.appList().getOrElse { error ->
            return@withContext TvCommandOutcome.Failed(
                "The Fire TV did not answer its DIAL description: " +
                    "${error.message ?: error.javaClass.simpleName}.",
            )
        }
        if (list.applicationUrl.isBlank()) {
            return@withContext TvCommandOutcome.Failed(
                "The Fire TV answered but published no application URL, so nothing was stopped.",
            )
        }
        dial.stop(list.applicationUrl, target).fold(
            onSuccess = { TvCommandOutcome.Sent("Asked the Fire TV to stop $app.") },
            onFailure = { error ->
                TvCommandOutcome.Failed(
                    "The Fire TV did not stop $app: ${error.message ?: error.javaClass.simpleName}.",
                )
            },
        )
    }

    /**
     * Button presses are not available over DIAL, and this says which protocol would be.
     *
     * A Fire TV's directional and media keys travel over an encrypted channel that wants
     * a pairing code displayed on the television. DIAL launches apps and nothing else.
     * Reporting that is the difference between a gap and a lie.
     */
    override suspend fun press(button: TvButton): TvCommandOutcome = TvCommandOutcome.Failed(
        "A Fire TV takes button presses over its encrypted remote channel, which needs a " +
            "pairing code shown on the screen; that protocol is not in this build. App launches " +
            "over DIAL do work, so tvLaunch \"netflix\" reaches this set.",
        needsPairing = true,
    )

    /** Read what is running, which DIAL can answer. */
    override suspend fun readState(): Map<TvStateKey, String>? = withContext(Dispatchers.IO) {
        val list = dial.appList().getOrNull() ?: return@withContext null
        if (list.applicationUrl.isBlank()) return@withContext null
        // DIAL reports which app is in the foreground as a path segment of the app list.
        val running = list.apps.firstOrNull()
        if (running == null) emptyMap()
        else mapOf(TvStateKey.APP to running)
    }

    companion object {
        /** The names a person says, which are also what DIAL expects as the app segment. */
        val SPOKEN: List<String> = listOf(
            "netflix", "youtube", "prime video", "amazon", "bbc iplayer", "itvx",
            "channel 4", "disney plus", "spotify", "plex", "apple tv",
        )

        /**
         * The DIAL app name for a spoken name, or null when Amazon has no such app.
         *
         * DIAL takes the name as a path segment, so this is a normalised spelling rather
         * than a numeric id. The list is deliberately short: an app Amazon does not ship
         * is refused by name instead of launching something adjacent.
         */
        fun amazonAppId(app: String): String? = when (app.trim().lowercase()
            .replace("+", " plus").replace("-", " ").replace(Regex("\\s+"), " ")) {
            "netflix" -> "Netflix"
            "youtube" -> "YouTube"
            "prime video", "amazon" -> "Prime Video"
            "bbc iplayer" -> "BBC iPlayer"
            "itvx", "itv hub" -> "ITVX"
            "channel 4" -> "Channel 4"
            "disney plus" -> "Disney+"
            "spotify" -> "Spotify"
            "plex" -> "Plex"
            "apple tv" -> "Apple TV"
            else -> null
        }
    }
}
