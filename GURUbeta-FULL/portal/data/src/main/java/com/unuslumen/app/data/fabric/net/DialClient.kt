// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.fabric.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

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
            URL("http://$host:$port/ssdp/device-desc.xml").openConnection() as HttpURLConnection
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
            val body = connection.inputStream.bufferedReader().use { it.readText().take(200_000) }
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
     * One DIAL request.
     *
     * A 201 means a launch started and a 200 means the read succeeded, so both are
     * success; anything else is the device's own refusal and its status is quoted.
     */
    private fun request(method: String, url: String): Result<String> {
        val connection = runCatching {
            URL(url).openConnection() as HttpURLConnection
        }.getOrNull()
            ?: return Result.failure(IllegalStateException("Could not reach $url."))

        return try {
            connection.requestMethod = method
            connection.connectTimeout = timeoutMillis
            connection.readTimeout = timeoutMillis
            // Some DIAL implementations require a content type even with no body.
            connection.setRequestProperty("Content-Type", "text/plain; charset=utf-8")
            if (method == "POST") {
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(0)
                connection.outputStream.close()
            }

            val code = connection.responseCode
            val body = runCatching {
                connection.inputStream.bufferedReader().use { it.readText().take(4000) }
            }.getOrElse {
                connection.errorStream?.bufferedReader()?.use { it.readText().take(4000) }.orEmpty()
            }

            if (code in 200..299) {
                Result.success(
                    "$method $url returned HTTP $code" +
                        if (body.isBlank()) "." else ", body: ${body.trim().take(200)}",
                )
            } else {
                Result.failure(
                    IllegalStateException(
                        "$method $url returned HTTP $code" +
                            if (body.isBlank()) "." else ", body: ${body.trim().take(200)}",
                    ),
                )
            }
        } catch (error: Exception) {
            Result.failure(
                IllegalStateException(
                    "$method $url failed: ${error.message ?: error.javaClass.simpleName}.",
                ),
            )
        } finally {
            connection.disconnect()
        }
    }
}
