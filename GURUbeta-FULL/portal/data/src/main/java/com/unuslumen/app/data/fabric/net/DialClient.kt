// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.fabric.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection

/**
 * DIAL: launching an app on a device that has one. Requirement 4 of the spec.
 *
 * WHY THIS IS THE "PUT A FILM ON" LAYER. Pressing a key is control of a television's own
 * interface. DIAL is one level above that: it asks the device to start an application,
 * which is what makes "put a Christmas film on the living room telly" achievable rather
 * than a sequence of D-pad presses through a menu.
 *
 * THE PROTOCOL IS SMALL:
 *
 *  - `GET  /ssdp/device-desc.xml` on the device's DIAL port returns the app list and the
 *    `Application-URL` header saying where launches go.
 *  - `POST <Application-URL><appName>` starts that app, with an optional payload.
 *  - `DELETE <Application-URL><appName>` stops it.
 *  - `GET  <Application-URL><appName>` reports whether it is running.
 *
 * WHAT HIS OWN TEST SHOWED, which shapes this file: the Virgin boxes answered DIAL on
 * 56890 but their `/apps` returned 404. So the app list cannot be assumed, and a failure
 * to list is reported as exactly that rather than as a device with no apps.
 *
 * THE ORIGIN HEADER, WHICH IS ISSUE #252. A Roku answers a DIAL launch with HTTP 403 and
 * the body "Origin  is not allowed access" when the POST carries no `Origin` header, and
 * `HttpURLConnection` drops that header silently because it is on the JDK's reserved list.
 * The launch and stop calls therefore go through [LocalHttp] with the device's own origin
 * attached, and a refusal comes back with the device's own words rather than a bare code.
 */
class DialClient(
    private val host: String,
    private val port: Int,
    private val timeoutMillis: Int = 4000,
) {

    /** The app list a device reports, with the URL launches go to. */
    data class AppList(
        val applicationUrl: String,
        val apps: List<String>,
        /** Whether the list came from the device or was empty. */
        val note: String,
    )

    /**
     * Read the device description, which carries the application URL and the app names.
     *
     * The `Application-URL` HEADER is the authoritative launch base, not the body, and a
     * device that omits it cannot be launched even if it lists apps.
     */
    suspend fun appList(): Result<AppList> = withContext(Dispatchers.IO) {
        val connection = runCatching {
            com.unuslumen.app.data.fabric.net.LocalRoute
                .open("http://$host:$port/ssdp/device-desc.xml")
                as HttpURLConnection
        }.getOrNull()
            ?: return@withContext Result.failure(
                IllegalStateException(
                    "Could not open a connection to $host:$port to read the DIAL description.",
                ),
            )

        try {
            connection.connectTimeout = timeoutMillis
            connection.readTimeout = timeoutMillis
            val code = runCatching { connection.responseCode }.getOrNull()
                ?: return@withContext Result.failure(
                    IllegalStateException("$host:$port did not answer the DIAL description request."),
                )
            if (code !in 200..299) {
                return@withContext Result.failure(
                    IllegalStateException(
                        "$host:$port answered HTTP $code for the DIAL description, so this device " +
                            "does not expose DIAL in the shape expected.",
                    ),
                )
            }
            // Bounded while reading, not afterwards. `readText().take(n)` allocates the entire
            // body into one String and truncates it afterwards, so a device that streams a
            // large description still fills the heap before the truncation runs. That is the
            // crash signature BoundedRead was written for.
            val body = connection.inputStream.use { stream -> readTextBounded(stream, 200_000) }
            val applicationUrl = connection.getHeaderField("Application-URL")
                ?: connection.getHeaderField("application-url")

            val apps = Regex("<name[^>]*>([^<]+)</name>", RegexOption.IGNORE_CASE)
                .findAll(body)
                .map { it.groupValues[1].trim() }
                .filter { it.isNotEmpty() }
                .toList()

            if (applicationUrl.isNullOrBlank()) {
                return@withContext Result.failure(
                    IllegalStateException(
                        "$host:$port answered the DIAL description but named no Application-URL, " +
                            "so there is nowhere to send a launch. It listed ${apps.size} app(s).",
                    ),
                )
            }

            Result.success(
                AppList(
                    applicationUrl = applicationUrl.trimEnd('/'),
                    apps = apps,
                    note = if (apps.isEmpty()) {
                        "The device reported no apps, which is common: many DIAL devices launch " +
                            "known app names without listing them."
                    } else {
                        "The device listed ${apps.size} app(s)."
                    },
                ),
            )
        } catch (error: Exception) {
            Result.failure(
                IllegalStateException(
                    "Reading the DIAL description from $host:$port failed: " +
                        "${error.message ?: error.javaClass.simpleName}.",
                ),
            )
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Launch one app.
     *
     * [appName] is the DIAL app name, which is the vendor's own identifier and not a
     * package name: `Netflix`, `YouTube`, `Amazon` and `Spotify` are the common ones.
     */
    suspend fun launch(applicationUrl: String, appName: String): Result<String> =
        withContext(Dispatchers.IO) {
            val target = "${applicationUrl.trimEnd('/')}/${appName.trim()}"
            request("POST", target)
        }

    /** Stop an app that is running. */
    suspend fun stop(applicationUrl: String, appName: String): Result<String> =
        withContext(Dispatchers.IO) {
            val target = "${applicationUrl.trimEnd('/')}/${appName.trim()}"
            request("DELETE", target)
        }

    /** Whether an app is running, from the device's own answer. */
    suspend fun isRunning(applicationUrl: String, appName: String): Result<String> =
        withContext(Dispatchers.IO) {
            val target = "${applicationUrl.trimEnd('/')}/${appName.trim()}"
            request("GET", target)
        }

    /**
     * One DIAL request, through the raw-socket client that can carry `Origin`.
     *
     * WHY NOT HttpURLConnection. `Origin` is a reserved header there and is dropped without
     * an error, which is exactly how a Roku ended up answering "Origin  is not allowed
     * access" to a POST that looked correct in the source. [LocalHttp] writes the headers it
     * is given, so the device's own requirement can actually be met.
     *
     * A 2xx means the device accepted, and anything else is its own refusal. Either way the
     * device's words are quoted, because a status with an explanatory body is the most
     * useful thing it can give a caller.
     */
    private fun request(method: String, url: String): Result<String> {
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
                // The device's own origin, which is what a direct client may claim. Roku
                // refuses a POST without it and names that refusal in its body.
                put("Origin", LocalHttp.sameOrigin(target, targetPort))
            },
            timeoutMillis = timeoutMillis,
        )

        return when (outcome) {
            is LocalHttp.Outcome.NoAnswer -> Result.failure(
                IllegalStateException(
                    "$method $url got no answer at all: ${outcome.reason}.",
                ),
            )
            is LocalHttp.Outcome.Answered -> {
                val response = outcome.response
                val detail = response.body.trim().take(200)
                val quoted = if (detail.isEmpty()) "" else ", body: $detail"
                if (response.ok) {
                    Result.success("$method $url returned HTTP ${response.status}$quoted")
                } else {
                    Result.failure(
                        IllegalStateException(
                            "$method $url returned HTTP ${response.status}$quoted. " +
                                "That status is the device's own answer, not an absent one.",
                        ),
                    )
                }
            }
        }
    }
}
