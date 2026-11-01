// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.fabric.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Every port on a host, and what answers on each. The "see absolutely everything" layer.
 *
 * WHY THIS AFTER THE QUICK SWEEP. The first scanner probes a hand-picked port set, which
 * finds televisions and printers. This one walks all 65535 ports and then speaks to every
 * one that opened, so a device running its control interface on 49123 is found rather than
 * missed. That is the difference between a scanner and recon.
 *
 * THE CONCURRENCY CAP IS NOT OPTIONAL. His own notes record a hand-rolled loop being
 * OOM-killed at exit 137 because it spawned too much at once. So work runs under a
 * semaphore, the socket is closed in a `use` block, and the batch size is bounded rather
 * than the whole range fired at once.
 *
 * WHAT A BANNER BUYS. An open port is one bit. A banner is identity: a TLS certificate
 * names the vendor, an HTTP server header names the firmware, an SSH string names the
 * operating system, and a 220 response on port 21 means a file server. So every open port
 * is asked to say something, and what it says is reported verbatim.
 */
class DeepPortScanner(
    private val connectTimeoutMillis: Int = 160,
    private val bannerTimeoutMillis: Int = 700,
    private val concurrency: Int = 96,
) {

    /** What one open port said. */
    data class OpenPort(
        val port: Int,
        /** The service name this port usually carries. */
        val service: String,
        /** The bytes the device sent first, or null when it waited to be spoken to. */
        val banner: String? = null,
        /** A TLS certificate subject, when the port is TLS. */
        val certificate: String? = null,
        /** The HTTP server header, when the port answered HTTP. */
        val server: String? = null,
        /** What this suggests the device is, when it suggests anything. */
        val hint: HostKind? = null,
    )

    /** The result of scanning one host. */
    data class HostResult(
        val address: String,
        val openPorts: List<OpenPort>,
        /** How many ports were tried, so the coverage is visible. */
        val portsProbed: Int,
        /** Anything that stopped the scan from being complete. */
        val problem: String? = null,
    ) {
        val count: Int get() = openPorts.size
    }

    /**
     * Scan every port on one host.
     *
     * [range] lets a caller ask for the common ports only, which is what a first pass
     * wants, because 65535 probes against a dead address costs the whole timeout.
     */
    suspend fun scanHost(
        address: String,
        range: IntRange = 1..65535,
    ): HostResult = withContext(Dispatchers.IO) {
        if (!reachable(address)) {
            return@withContext HostResult(
                address = address,
                openPorts = emptyList(),
                portsProbed = range.count(),
                problem = "$address refused every connection, so it is off, on another network, " +
                    "or behind its own firewall. Nothing can be learned from a host that does not " +
                    "answer.",
            )
        }

        val gate = Semaphore(concurrency)
        val open = coroutineScope {
            range.map { port ->
                async {
                    gate.withPermit {
                        if (isOpen(address, port)) probe(address, port) else null
                    }
                }
            }.awaitAll().filterNotNull()
        }

        HostResult(
            address = address,
            openPorts = open.sortedBy { it.port },
            portsProbed = range.count(),
        )
    }

    /** Whether the host answers at all on any of the ports a device almost always has. */
    private fun reachable(address: String): Boolean = listOf(80, 443, 22, 445, 62078, 8060)
        .any { isOpen(address, it) } || isOpen(address, 7) || pingFallback(address)

    /**
     * A TCP-based aliveness test for a host with no common port.
     *
     * Android denies a raw ICMP socket at app UID, so a real ping is impossible here. A
     * connect attempt to a high port that almost every stack answers with a refusal is the
     * substitute: a refusal proves the host exists, and an unreachable host times out.
     */
    private fun pingFallback(address: String): Boolean = runCatching {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(address, 9), connectTimeoutMillis)
            true
        }
    }.getOrElse { error ->
        // A refused connection still proves the host answered, which is the whole question.
        error is java.net.ConnectException
    }

    private fun isOpen(address: String, port: Int): Boolean = runCatching {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(address, port), connectTimeoutMillis)
            true
        }
    }.getOrDefault(false)

    /**
     * Ask an open port to identify itself.
     *
     * Three attempts in order of what most devices answer:
     *  1. Read whatever it sends unprompted, which covers SSH, FTP, SMTP and most cameras.
     *  2. Send a minimal HTTP request, which covers every web interface and gives a Server
     *     header on most.
     *  3. If the port looks like TLS, take the certificate, whose subject names the vendor.
     */
    private fun probe(address: String, port: Int): OpenPort {
        val service = ServiceNames.forPort(port)

        // 1. An unprompted banner.
        val banner = readBanner(address, port, sendHttp = false)
        if (!banner.isNullOrBlank()) {
            return OpenPort(
                port = port,
                service = service,
                banner = banner.trim().take(400),
                hint = hintFor(service, banner),
            )
        }

        // 2. An HTTP request, which most interfaces answer.
        val http = readBanner(address, port, sendHttp = true)
        if (!http.isNullOrBlank()) {
            val server = Regex("(?i)^server:\\s*(.+)$", RegexOption.MULTILINE)
                .find(http)?.groupValues?.getOrNull(1)?.trim()
            val statusLine = http.lineSequence().firstOrNull { it.startsWith("HTTP/") }
            return OpenPort(
                port = port,
                service = service,
                banner = (statusLine ?: http.trim().take(200)),
                server = server,
                hint = hintForService(service, server, http),
            )
        }

        // 3. A TLS certificate, which is where a vendor name often lives.
        val certificate = readCertificate(address, port)
        return OpenPort(
            port = port,
            service = service,
            certificate = certificate,
            hint = if (certificate != null) HostKind.UNKNOWN else null,
        )
    }

    /** Read whatever the port sends, optionally after a minimal HTTP request. */
    private fun readBanner(address: String, port: Int, sendHttp: Boolean): String? = runCatching {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(address, port), connectTimeoutMillis)
            socket.soTimeout = bannerTimeoutMillis
            if (sendHttp) {
                val request = "GET / HTTP/1.0\r\nHost: $address\r\nUser-Agent: GURU\r\n\r\n"
                socket.getOutputStream().apply {
                    write(request.toByteArray(Charsets.US_ASCII))
                    flush()
                }
            }
            val buffer = ByteArray(2048)
            val read = socket.getInputStream().read(buffer)
            if (read <= 0) null else String(buffer, 0, read, Charsets.ISO_8859_1)
        }
    }.getOrNull()

    /** Take the certificate's subject, which frequently names the vendor. */
    private fun readCertificate(address: String, port: Int): String? = runCatching {
        val plain = Socket()
        plain.connect(InetSocketAddress(address, port), connectTimeoutMillis)
        plain.soTimeout = bannerTimeoutMillis
        val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
        val ssl = factory.createSocket(plain, address, port, true) as SSLSocket
        ssl.use {
            it.startHandshake()
            val session = it.session
            val subject = session.peerCertificates.firstOrNull()
                ?.let { certificate -> (certificate as? java.security.cert.X509Certificate)?.subjectDN?.name }
            val issuer = session.peerCertificates.firstOrNull()
                ?.let { certificate -> (certificate as? java.security.cert.X509Certificate)?.issuerDN?.name }
            subject ?: issuer
        }
    }.getOrNull()

    /** What a banner suggests the device is. */
    private fun hintFor(service: String, banner: String): HostKind? = when {
        // An RTSP banner is a camera, and its Server header names the model.
        banner.startsWith("RTSP", ignoreCase = true) -> HostKind.CAMERA
        banner.startsWith("220", ignoreCase = true) && service == "ftp" -> HostKind.NAS
        banner.contains("SSH-2.0-OpenSSH", ignoreCase = true) -> HostKind.COMPUTER
        banner.contains("SSH", ignoreCase = true) -> HostKind.COMPUTER
        banner.startsWith("+OK", ignoreCase = true) -> HostKind.NAS
        else -> hintForService(service, null, banner)
    }

    /** What a service name and its headers suggest. */
    private fun hintForService(service: String, server: String?, body: String): HostKind? {
        val haystack = "${server.orEmpty()} $body".lowercase()
        return when {
            haystack.contains("roku") -> HostKind.TELEVISION
            haystack.contains("samsung") -> HostKind.TELEVISION
            haystack.contains("webos") || haystack.contains("lg electronics") -> HostKind.TELEVISION
            haystack.contains("androidtv") || haystack.contains("android tv") -> HostKind.TELEVISION
            haystack.contains("vizio") -> HostKind.TELEVISION
            haystack.contains("printer") || haystack.contains("cups") -> HostKind.PRINTER
            haystack.contains("nas") || haystack.contains("synology") -> HostKind.NAS
            haystack.contains("router") || haystack.contains("gateway") -> HostKind.ROUTER
            haystack.contains("camera") || haystack.contains("ipcam") -> HostKind.CAMERA
            haystack.contains("sonos") -> HostKind.SPEAKER
            service == "rtsp" -> HostKind.CAMERA
            service == "ipp" || service == "jetdirect" -> HostKind.PRINTER
            service == "smb" -> HostKind.COMPUTER
            service == "ssh" -> HostKind.COMPUTER
            else -> null
        }
    }
}

/**
 * The conventional service on a port.
 *
 * Named rather than numeric in a result, because "443 is https" is what tells a person
 * what they are looking at, and an unexplained number does not.
 */
object ServiceNames {

    private val table: Map<Int, String> = mapOf(
        7 to "echo", 9 to "discard", 20 to "ftp-data", 21 to "ftp", 22 to "ssh",
        23 to "telnet", 25 to "smtp", 53 to "dns", 67 to "dhcp", 69 to "tftp",
        80 to "http", 81 to "http-alt", 88 to "kerberos", 110 to "pop3",
        111 to "rpcbind", 123 to "ntp", 135 to "msrpc", 137 to "netbios-ns",
        138 to "netbios-dgm", 139 to "netbios-ssn", 143 to "imap", 161 to "snmp",
        389 to "ldap", 443 to "https", 445 to "smb", 465 to "smtps", 500 to "isakmp",
        514 to "syslog", 515 to "lpd", 548 to "afp", 554 to "rtsp", 587 to "submission",
        631 to "ipp", 636 to "ldaps", 873 to "rsync", 902 to "vmware",
        993 to "imaps", 995 to "pop3s", 1080 to "socks", 1194 to "openvpn",
        1433 to "mssql", 1521 to "oracle", 1883 to "mqtt", 1900 to "ssdp",
        1925 to "philips-jointspace", 2049 to "nfs", 3000 to "lg-webos",
        3001 to "lg-webos-tls", 3306 to "mysql", 3389 to "rdp", 3690 to "svn",
        4000 to "icmp-ps", 5000 to "synology", 5060 to "sip", 5353 to "mdns",
        5432 to "postgres", 5555 to "adb", 5683 to "coap", 5900 to "vnc",
        6379 to "redis", 6466 to "androidtv-remote", 7000 to "airplay",
        7345 to "vizio-smartcast", 7547 to "tr069", 8000 to "http-alt",
        8001 to "samsung-remote", 8002 to "samsung-remote-tls", 8008 to "cast",
        8009 to "cast-tls", 8060 to "roku-ecp", 8080 to "http-proxy",
        8081 to "http-alt", 8082 to "http-alt", 8088 to "http-alt",
        8118 to "privoxy", 8123 to "home-assistant", 8200 to "go-ipfs",
        8443 to "https-alt", 8883 to "mqtt-tls", 8888 to "http-alt",
        9000 to "philips-jointspace-tls", 9080 to "http-alt", 9090 to "http-alt",
        9100 to "jetdirect", 9229 to "node-inspector", 9443 to "https-alt",
        9922 to "lg-webos-ssh", 10000 to "webmin", 11211 to "memcached",
        27017 to "mongodb", 32400 to "plex", 49152 to "upnp",
        49153 to "upnp-event", 55553 to "androidtv-pairing",
        62078 to "iphone-sync",
    )

    /** The service name for a port, or a plain statement that it has no well-known owner. */
    fun forPort(port: Int): String = table[port] ?: "unassigned"
}
