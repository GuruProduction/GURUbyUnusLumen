// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.fabric.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * Calling any action on any UPnP service. Requirement 3 of the spec, and the biggest gap
 * between discovery and control.
 *
 * WHY THIS IS THE ONE THAT MAKES A HOUSE CONTROLLABLE. Discovery returns a device
 * description with a serviceList, and until now driving any of it meant hand-writing a
 * SOAP envelope. One generic caller that builds the envelope turns every UPnP box on the
 * network into something drivable, and it covers a lot at once: media transport on a
 * television, volume on a speaker, port mapping on a router, pan and tilt on a camera
 * through ONVIF, and playback control on anything that speaks AVTransport.
 *
 * THE PROTOCOL IN THREE FACTS:
 *
 *  1. `GET <LOCATION>` returns a device description whose `serviceList` names each
 *     service's `serviceType`, `controlURL` and `eventSubURL`.
 *  2. An action is a POST to the control URL with two headers: `SOAPACTION` naming the
 *     service type and the action, and a content type of text/xml.
 *  3. The body is a SOAP envelope whose action element is namespaced to the service type
 *     and carries one element per argument.
 *
 * WHAT IT REFUSES TO DO. It will not report an action as having done anything it cannot
 * see. A SOAP reply is parsed for a fault, and a fault is reported with the device's own
 * error code; a success is reported as the device accepting the action, not as the
 * physical thing having happened.
 */
class UpnpSoapClient(
    private val timeoutMillis: Int = 4000,
) {

    /** One service from a device's serviceList. */
    data class Service(
        val serviceType: String,
        val serviceId: String,
        val controlUrl: String,
        val eventUrl: String? = null,
    ) {
        /**
         * The short name, which is what a caller thinks of as the service.
         *
         * THE LAST SEGMENT IS THE VERSION, NOT THE NAME. A service type is
         * `urn:schemas-upnp-org:service:AVTransport:1`, so taking the last segment gives
         * "1", which is meaningless and would make every service name lookup fail. The
         * name is the second-to-last segment.
         */
        val shortName: String
            get() {
                val parts = serviceType.split(':')
                return if (parts.size >= 2) parts[parts.size - 2] else serviceType
            }
    }

    /** A device's description, with every service it exposes. */
    data class DeviceDescription(
        val friendlyName: String?,
        val manufacturer: String?,
        val modelName: String?,
        val services: List<Service>,
        /** The absolute base the control URLs are relative to. */
        val baseUrl: String,
    )

    /**
     * Read and parse a device description.
     *
     * Control URLs in the description are usually relative, so they are resolved against
     * the LOCATION the description came from. A relative URL posted without that
     * resolution goes to the phone, which fails in a way that looks like a dead device.
     */
    suspend fun describe(location: String): Result<DeviceDescription> = withContext(Dispatchers.IO) {
        val body = runCatching { fetch(location) }.getOrNull()
            ?: return@withContext Result.failure(
                IllegalStateException("$location did not answer, so there is no description to read."),
            )

        val base = runCatching {
            val url = URL(location)
            "${url.protocol}://${url.host}${if (url.port > 0) ":${url.port}" else ""}"
        }.getOrElse { return@withContext Result.failure(IllegalStateException("$location is not a URL.")) }

        val services = Regex("<service>(.*?)</service>", RegexOption.DOT_MATCHES_ALL)
            .findAll(body)
            .mapNotNull { match ->
                val block = match.groupValues[1]
                val type = tag(block, "serviceType") ?: return@mapNotNull null
                val control = tag(block, "controlURL") ?: return@mapNotNull null
                Service(
                    serviceType = type,
                    serviceId = tag(block, "serviceId") ?: "",
                    controlUrl = resolve(base, control),
                    eventUrl = tag(block, "eventSubURL")?.let { resolve(base, it) },
                )
            }
            .toList()

        Result.success(
            DeviceDescription(
                friendlyName = tag(body, "friendlyName"),
                manufacturer = tag(body, "manufacturer"),
                modelName = tag(body, "modelName"),
                services = services,
                baseUrl = base,
            ),
        )
    }

    /**
     * Invoke one action.
     *
     * [arguments] are the action's input arguments in order, which is what the envelope
     * carries. A caller that does not know them can invoke with none, which is how a
     * state query works on most services.
     */
    suspend fun invoke(
        service: Service,
        action: String,
        arguments: Map<String, String> = emptyMap(),
    ): Result<String> = withContext(Dispatchers.IO) {
        val envelope = buildEnvelope(service.serviceType, action, arguments)

        val connection = runCatching {
            URL(service.controlUrl).openConnection() as HttpURLConnection
        }.getOrNull()
            ?: return@withContext Result.failure(
                IllegalStateException("Could not reach ${service.controlUrl}."),
            )

        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = timeoutMillis
            connection.readTimeout = timeoutMillis
            connection.doOutput = true
            // SOAPACTION carries the service type and the action in quotes, and some
            // stacks refuse the request without it even though SOAP 1.2 deprecated it.
            connection.setRequestProperty(
                "SOAPACTION",
                "\"${service.serviceType}#$action\"",
            )
            connection.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
            val bytes = envelope.toByteArray(Charsets.UTF_8)
            connection.setFixedLengthStreamingMode(bytes.size)
            connection.outputStream.use { it.write(bytes) }

            val code = connection.responseCode
            // Bounded while reading, because `readText().take(n)` allocates the whole body and
            // truncates afterwards, which bounds what is kept and not what is allocated.
            val reply = runCatching {
                connection.inputStream.use { stream -> readTextBounded(stream, 20_000) }
            }.getOrElse {
                connection.errorStream?.use { stream -> readTextBounded(stream, 20_000) }.orEmpty()
            }

            // A SOAP fault arrives as HTTP 500 with an error code in the body, so the
            // fault is checked before the status: reporting an HTTP status alone would
            // hide which argument the device rejected.
            val fault = Regex("<errorCode>\\s*(\\d+)\\s*</errorCode>", RegexOption.IGNORE_CASE)
                .find(reply)?.groupValues?.getOrNull(1)
            if (fault != null) {
                val description = tag(reply, "errorDescription") ?: "no description given"
                return@withContext Result.failure(
                    IllegalStateException(
                        "${service.shortName} refused $action with UPnP error $fault: $description.",
                    ),
                )
            }
            if (code !in 200..299) {
                return@withContext Result.failure(
                    IllegalStateException(
                        "${service.shortName} returned HTTP $code for $action" +
                            if (reply.isBlank()) "." else ", body: ${reply.trim().take(200)}",
                    ),
                )
            }

            Result.success(
                "$action was accepted by ${service.shortName} at ${service.controlUrl}." +
                    if (reply.contains("<Result>")) " It returned a result." else "",
            )
        } catch (error: Exception) {
            Result.failure(
                IllegalStateException(
                    "Calling $action on ${service.shortName} failed: " +
                        "${error.message ?: error.javaClass.simpleName}.",
                ),
            )
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Build the SOAP envelope for one action.
     *
     * Exposed because it is pure text and the exact shape decides whether a device
     * accepts the call, so a JVM test asserts it rather than discovering it on hardware.
     */
    fun buildEnvelope(
        serviceType: String,
        action: String,
        arguments: Map<String, String> = emptyMap(),
    ): String {
        val args = arguments.entries.joinToString("") { (name, value) ->
            "<$name>${escapeXml(value)}</$name>"
        }
        return buildString {
            append("""<?xml version="1.0" encoding="utf-8"?>""")
            append("""<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" """)
            append("""s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">""")
            append("<s:Body>")
            append("""<u:$action xmlns:u="$serviceType">""")
            append(args)
            append("</u:$action>")
            append("</s:Body></s:Envelope>")
        }
    }

    /** Resolve a possibly relative control URL against the device's base. */
    fun resolve(base: String, controlUrl: String): String {
        if (controlUrl.startsWith("http://") || controlUrl.startsWith("https://")) return controlUrl
        return if (controlUrl.startsWith("/")) base + controlUrl else "$base/$controlUrl"
    }

    private fun fetch(url: String): String {
        val connection = com.unuslumen.app.data.fabric.net.LocalRoute.open(url)
                ?: throw IllegalStateException("no connection could be built to $url")
        return try {
            connection.connectTimeout = timeoutMillis
            connection.readTimeout = timeoutMillis
            connection.inputStream.use { stream -> readTextBounded(stream, 300_000) }
        } finally {
            connection.disconnect()
        }
    }

    /** Escape the five characters XML cannot carry raw. */
    private fun escapeXml(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

    private fun tag(xml: String, name: String): String? =
        Regex("<$name[^>]*>([^<]*)</$name>", RegexOption.IGNORE_CASE)
            .find(xml)?.groupValues?.getOrNull(1)?.trim()?.ifBlank { null }
}
