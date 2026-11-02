// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.fabric.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * DIAL: launching an app on a device that has one. Requirement 4 of the spec, issue #251.
 *
 * WHY THIS IS THE "PUT A FILM ON" LAYER. Pressing a key is control of a television's own
 * interface. DIAL is one level above that: it asks the device to start an application,
 * which is what makes "put a Christmas film on the living room telly" achievable rather
 * than a sequence of D-pad presses through a menu.
 *
 * WHAT WAS BROKEN, AND IT WAS ONE ASSUMPTION. This asked for `/ssdp/device-desc.xml` on the
 * port it was given and nothing else. No device on this network serves that path: the Roku
 * answers at `/dial/dd.xml` on 8060 and the Fire TV at `/dd.xml` on 60000. So every call was
 * a 404, and the 404 was then reported as the device not exposing DIAL. It exposes it fine.
 * The description location is never a constant in DIAL, it is the `LOCATION` header of the
 * SSDP reply, so [DialEndpoints.descriptionCandidates] now tries the device's own locations
 * in order and takes the first that answers.
 *
 * TWO PORTS, WHICH IS WHY FOLLOWING THE HEADER MATTERS. The Fire TV publishes its
 * description on 60000 and its `Application-URL` on 8009. That is legal DIAL and it is why
 * the launch must be sent to the URL the device names rather than to the port we were told.
 *
 * A LAUNCH IS 201, NOT ANY 2xx. DIAL defines 201 for a launch that started the app and 200
 * for a request the device accepted without starting anything. Collapsing both into
 * "success" is how a launch that did nothing read as a launch, so [DialEndpoints.LaunchOutcome]
 * keeps them apart.
 *
 * AN APP LIST THAT WAS NOT READ IS NOT AN EMPTY LIST. Returning `[]` states "no apps
 * installed" as an observed fact, which is the same failure class as #216 and #219: an empty
 * collection read as a complete answer. [AppList] carries whether the list was actually read.
 */
class DialClient(
    private val host: String,
    private val port: Int,
    private val timeoutMillis: Int = 4000,
    /** The SSDP `LOCATION` when discovery already saw one. Tried first, because it is the device's word. */
    private val knownLocation: String? = null,
) {

    /**
     * What a device reported about its apps.
     *
     * [read] is the field that stops an unread list being reported as an empty one. A device
     * that answered the description but listed nothing has `read = true` and no names; a
     * device whose description was never obtained has `read = false`.
     */
    data class AppList(
        val applicationUrl: String,
        val apps: List<String>,
        /** Whether the app list itself was actually read from the device. */
        val read: Boolean,
        val note: String,
    )

    /** The result of trying to find a device's description. */
    private data class Located(
        val applicationUrl: String,
        val apps: List<String>,
        val read: Boolean,
        val from: String,
        /** Every path that was tried and rejected, so a failure names what was asked. */
        val tried: List<String>,
    )

    /**
     * Read the device description, following the device's own location rather than a guess.
     *
     * Every candidate in [DialEndpoints.descriptionCandidates] is tried until one answers with
     * an `Application-URL`. A 404 or 405 says only that this path is not the description, so
     * the next is tried; anything else stops the search because the device has spoken.
     */
    suspend fun appList(): Result<AppList> = withContext(Dispatchers.IO) {
        locate().fold(
            onSuccess = { located ->
                Result.success(
                    AppList(
                        applicationUrl = located.applicationUrl.trimEnd('/'),
                        apps = located.apps,
                        read = located.read,
                        note = when {
                            !located.read -> "The app list was not read from this device."
                            located.apps.isEmpty() ->
                                "The device answered its description at ${located.from} and listed no " +
                                    "apps. That is common: many DIAL devices launch known app names " +
                                    "without listing them."
                            else -> "The device listed ${located.apps.size} app(s)."
                        },
                    ),
                )
            },
            onFailure = { error ->
                Result.failure(IllegalStateException(error.message ?: "DIAL discovery failed."))
            },
        )
    }

    /**
     * Find the description and its application URL.
     *
     * The path that worked is reported so a caller can say where the device keeps it, which
     * is the piece of evidence that was missing when this was reported as "does not expose
     * DIAL in the shape expected".
     */
    private fun locate(): Result<Located> {
        val candidates = DialEndpoints.descriptionCandidates(host, port, knownLocation)
        val tried = mutableListOf<String>()
        var lastRefusal: String? = null

        for (url in candidates) {
            tried += url
            val outcome = LocalHttp.request(
                host = hostOf(url) ?: host,
                port = portOf(url, port),
                method = "GET",
                path = pathOf(url),
                // The device's own origin, which is what a direct client may claim. A Roku
                // refuses a POST without it and names that refusal in its body.
                headers = mapOf("Origin" to LocalHttp.sameOrigin(hostOf(url) ?: host, portOf(url, port))),
                timeoutMillis = timeoutMillis,
            )

            when (outcome) {
                is LocalHttp.Outcome.NoAnswer -> {
                    // Nothing answered at this candidate. Keep trying: a description on a
                    // port the device opened late is still a description.
                    lastRefusal = "${outcome.reason}"
                }
                is LocalHttp.Outcome.Answered -> {
                    val response = outcome.response
                    if (DialEndpoints.keepsSearching(response.status)) {
                        // This path is not the description. That is a fact about the
                        // candidate, not about the device, so the search continues.
                        lastRefusal = "HTTP ${response.status} at $url"
                        continue
                    }
                    if (!response.ok) {
                        // The device answered with a real status. That is its answer.
                        return Result.failure(
                            IllegalStateException(
                                "$url answered HTTP ${response.status}. " +
                                    DeviceAnswer.describe("The device at $host:$port", response),
                            ),
                        )
                    }

                    val applicationUrl = response.header("Application-URL")
                    if (!DialEndpoints.isUsable(applicationUrl)) {
                        // The description was found but names no launch base, so it cannot be
                        // launched from here. Reported as that, with the path that worked.
                        return Result.failure(
                            IllegalStateException(
                                "$url answered HTTP ${response.status} but named no Application-URL, " +
                                    "so there is nowhere to send a launch.",
                            ),
                        )
                    }

                    val apps = parseApps(response.body)
                    return Result.success(
                        Located(
                            applicationUrl = applicationUrl!!,
                            apps = apps,
                            // The description was read, so the list is a reading, even when empty.
                            read = true,
                            from = url,
                            tried = tried,
                        ),
                    )
                }
            }
        }

        return Result.failure(
            IllegalStateException(
                "No DIAL description answered at $host:$port. Tried: ${tried.joinToString(", ")}. " +
                    (lastRefusal?.let { "Last answer: $it." } ?: ""),
            ),
        )
    }

    /**
     * Launch one app.
     *
     * [appName] is the DIAL app name, which is the vendor's own identifier and not a package
     * name: Netflix, YouTube, Amazon and Spotify are the common ones. [DialEndpoints.appNameFor]
     * maps a spoken name onto the published spelling.
     *
     * ONLY 201 IS A LAUNCH. A 200 means the device accepted the request without starting the
     * app, which is the difference this issue was filed over.
     */
    suspend fun launch(applicationUrl: String, appName: String): Result<LaunchReport> =
        withContext(Dispatchers.IO) {
            val target = "${applicationUrl.trimEnd('/')}/${DialEndpoints.appNameFor(appName)}"
            request("POST", target).map { answered ->
                LaunchReport(
                    outcome = DialEndpoints.launchOutcome(answered.status),
                    status = answered.status,
                    url = target,
                    location = answered.location,
                    body = answered.body,
                )
            }
        }

    /** Stop an app that is running. */
    suspend fun stop(applicationUrl: String, appName: String): Result<String> =
        withContext(Dispatchers.IO) {
            val target = "${applicationUrl.trimEnd('/')}/${DialEndpoints.appNameFor(appName)}"
            request("DELETE", target).map { "DELETE $target returned HTTP ${it.status}" }
        }

    /** Whether an app is running, from the device's own answer. */
    suspend fun isRunning(applicationUrl: String, appName: String): Result<String> =
        withContext(Dispatchers.IO) {
            val target = "${applicationUrl.trimEnd('/')}/${DialEndpoints.appNameFor(appName)}"
            request("GET", target).map { "GET $target returned HTTP ${it.status}" }
        }

    /** What a launch actually did, which is the field a caller must report. */
    data class LaunchReport(
        val outcome: DialEndpoints.LaunchOutcome,
        val status: Int,
        val url: String,
        /** The `Location` header, which DIAL uses to name the running app's own URL. */
        val location: String?,
        val body: String,
    ) {
        /** Whether the app was started. Only 201 counts. */
        val started: Boolean get() = outcome == DialEndpoints.LaunchOutcome.STARTED
    }

    /** One DIAL answer, reduced to the parts that matter. */
    private data class Answered(val status: Int, val location: String?, val body: String)

    /**
     * One DIAL request, through the raw-socket client that can carry `Origin`.
     *
     * WHY NOT HttpURLConnection. `Origin` is a reserved header there and is dropped without
     * an error, which is how a Roku answered "Origin  is not allowed access" to a POST that
     * looked correct in the source. [LocalHttp] writes the headers it is given.
     *
     * The status is returned to the caller rather than collapsed into success or failure,
     * because 201 and 200 mean different things and only the caller knows which it asked for.
     */
    private fun request(method: String, url: String): Result<Answered> {
        val parsed = runCatching { java.net.URI(url) }.getOrNull()
            ?: return Result.failure(IllegalStateException("'$url' is not a URL."))
        val target = parsed.host ?: return Result.failure(
            IllegalStateException("'$url' names no host."),
        )
        val targetPort = if (parsed.port > 0) parsed.port else 80
        val path = (parsed.rawPath ?: "/") + (parsed.rawQuery?.let { "?$it" } ?: "")

        val outcome = LocalHttp.request(
            host = target,
            port = targetPort,
            method = method,
            path = path,
            headers = buildMap {
                // Some DIAL implementations require a content type even with no body.
                put("Content-Type", "text/plain; charset=utf-8")
                // The device's own origin, which is what a direct client may claim.
                put("Origin", LocalHttp.sameOrigin(target, targetPort))
            },
            timeoutMillis = timeoutMillis,
        )

        return when (outcome) {
            is LocalHttp.Outcome.NoAnswer -> Result.failure(
                IllegalStateException("$method $url got no answer at all: ${outcome.reason}."),
            )
            is LocalHttp.Outcome.Answered -> {
                val response = outcome.response
                Result.success(
                    Answered(
                        status = response.status,
                        location = response.header("Location"),
                        body = response.body,
                    ),
                )
            }
        }
    }

    /** Every `<name>` in a description, which is what an app list looks like over DIAL. */
    private fun parseApps(body: String): List<String> =
        Regex("<name[^>]*>([^<]+)</name>", RegexOption.IGNORE_CASE)
            .findAll(body)
            .map { it.groupValues[1].trim() }
            .filter { it.isNotEmpty() }
            .toList()

    private fun hostOf(url: String): String? = runCatching { java.net.URI(url).host }.getOrNull()

    private fun portOf(url: String, fallback: Int): Int =
        runCatching { java.net.URI(url).port }.getOrNull()?.takeIf { it > 0 } ?: fallback

    private fun pathOf(url: String): String {
        val uri = runCatching { java.net.URI(url) }.getOrNull()
        val path = uri?.rawPath?.takeIf { it.isNotBlank() } ?: "/"
        val query = uri?.rawQuery?.let { "?$it" } ?: ""
        return path + query
    }
}
