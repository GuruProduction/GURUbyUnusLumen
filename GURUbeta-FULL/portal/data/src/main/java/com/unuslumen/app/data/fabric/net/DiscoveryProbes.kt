// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.fabric.net

import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger

/**
 * The individual protocol conversations, each one a small pure-ish function over a socket.
 *
 * WHY THESE ARE SEPARATE FROM THE SWEEP THAT RUNS THEM. Each probe is one protocol's exact
 * question and the exact way its answer is read, and each is a thing that can be wrong on its
 * own: a bad NBNS packet gets no reply, and a bad SNMP community gets an error. Keeping them
 * apart means the sweep is only scheduling and merging, and a broken protocol is a broken
 * function rather than a mysterious absence in a list.
 *
 * WHAT EVERY ONE OF THEM RETURNS. A probe answers "what did this device say", never "is this
 * device a television". Classification happens afterwards, in [DeviceIdentifier], where it can be
 * tested without a socket. A probe that decides what a device is would put a judgement inside a
 * timeout, which is where judgements go to be untestable.
 */

/**
 * One UDP socket per protocol, sending to many addresses and collecting replies.
 *
 * WHY ONE SOCKET AND NOT ONE PER ADDRESS. 254 sockets is 254 file descriptors and a thread per
 * reply; one socket with a correlation table is a handful of descriptors and one receive loop.
 * The correlation is the source address, because every protocol here puts the answering host's
 * address in the packet header where the socket can read it for free.
 */
class UdpProbeChannel(private val timeoutMillis: Int = 900) {

    /**
     * Send [payload] to [address]:[port] and collect every reply that arrives within the window.
     *
     * The receive loop is bounded by [deadlineMillis] from the moment the last packet was sent, so
     * a protocol that is answered by one device in a millisecond does not wait the full window for
     * the other 253.
     */
    fun exchange(
        address: String,
        port: Int,
        payload: ByteArray,
        deadlineMillis: Int = 600,
        maxReplies: Int = 4,
    ): List<ByteArray> {
        val replies = mutableListOf<ByteArray>()
        runCatching {
            DatagramSocket().use { socket ->
                socket.soTimeout = 120
                val target = InetAddress.getByName(address)
                socket.send(DatagramPacket(payload, payload.size, target, port))

                val deadline = System.currentTimeMillis() + deadlineMillis
                val buffer = ByteArray(8192)
                while (System.currentTimeMillis() < deadline && replies.size < maxReplies) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    runCatching { socket.receive(packet) }.onSuccess {
                        // Only a reply from the address we asked, because an unrelated host
                        // answering the same protocol must not be attributed to this one.
                        if (packet.address?.hostAddress == address) {
                            replies.add(packet.data.copyOfRange(0, packet.length))
                        }
                    }
                }
            }
        }
        return replies
    }
}

/**
 * SNMP, which is how a device that answers no web port at all still names itself.
 *
 * WHAT IT REACHES AND WHAT IT DOES NOT. Network printers, managed switches, routers running an
 * agent, NAS boxes with it switched on and UPS management cards answer `public` from the factory.
 * A washing machine or a fridge does not run an SNMP agent, so this is not the appliance
 * channel despite being the most powerful protocol here. It is the printer and infrastructure
 * channel, and it also finds the gateway's own client table when the gateway runs an agent.
 */
object SnmpProbe {

    /**
     * The communities worth trying, in the order that finds the most devices.
     *
     * `public` is the factory read-only default on nearly every device that ships an agent, and it
     * is the only string worth sending blind. `private` is the write default and is answered by
     * the same agents, so it costs one extra packet and occasionally catches a device whose read
     * community was changed. Anything beyond that is per-site configuration and guessing it is
     * traffic for nothing, which is why the list stops at two.
     */
    val COMMUNITIES: List<String> = listOf("public", "private")

    private val requestCounter = AtomicInteger(1)

    /** What one SNMP probe found, or null when the device did not answer SNMP at all. */
    data class Result(
        val community: String,
        val version: SnmpCodec.Version,
        val values: Map<String, String>,
    )

    /**
     * Ask one address for its system group.
     *
     * TWO VERSIONS, deliberately. v2c is asked first because it answers per varbind, so a device
     * that implements only part of MIB-II still returns the part it has. v1 is only worth a second
     * packet when v2c got nothing back, because a v1 responder rejects the whole request over one
     * unknown OID and would return an empty answer rather than a useful one.
     */
    fun query(
        address: String,
        channel: UdpProbeChannel,
        communities: List<String> = COMMUNITIES,
    ): Result? {
        for (community in communities) {
            val reply = queryOnce(address, channel, community, SnmpCodec.Version.V2C)
                ?: queryOnce(address, channel, community, SnmpCodec.Version.V1)
                ?: continue
            val values = reply.values()
            // An agent that answered with a version but no values is one that refused the
            // community, so the next community is worth a try rather than reporting an empty hit.
            if (values.isNotEmpty()) {
                return Result(
                    community = community,
                    version = reply.version,
                    values = values,
                )
            }
        }
        return null
    }

    /** One request and its reply, with the request id correlated so stray traffic is discarded. */
    private fun queryOnce(
        address: String,
        channel: UdpProbeChannel,
        community: String,
        version: SnmpCodec.Version,
    ): SnmpCodec.Response? {
        val requestId = requestCounter.getAndIncrement() and 0x7FFFFFFF
        val request = SnmpCodec.getRequest(
            community = community,
            oids = DeviceIdentifier.SYSTEM_OIDS,
            requestId = requestId,
            version = version,
        )

        for (bytes in channel.exchange(address, SNMP_PORT, request, deadlineMillis = 700)) {
            val response = SnmpCodec.decode(bytes) ?: continue
            // THE CORRELATION IS THE WHOLE POINT. A late reply to an earlier request, or a reply
            // to a different community, carries a different id and must not be read as this
            // device's answer to this question.
            if (response.requestId != requestId) continue
            if (response.failed) continue
            return response
        }
        return null
    }

    private const val SNMP_PORT = 161
}

/**
 * NBNS, the NetBIOS name service, which names devices that ignore everything else.
 *
 * WHY THIS EARNS ITS PLACE. A Windows machine, a NAS and some printers answer a NetBIOS node
 * status request and report their name, their workgroup and their MAC address. That MAC is the
 * only way an unrooted phone can recover the hardware address of an arbitrary device, because the
 * kernel's neighbour table is closed to apps since API 29.
 */
object NbnsProbe {

    /**
     * A node status request for the wildcard name, which asks a device to list every name it has
     * registered. This is the well-known 50-byte query and the name is deliberately the wildcard
     * `*` padded to 16 bytes with the 0x00 suffix, then encoded in the half-ASCII scheme
     * NetBIOS uses for transmitted names.
     */
    fun nodeStatusRequest(): ByteArray {
        val out = ByteArrayOutputStream()
        // Transaction id, then flags 0, question count 1, and three empty sections.
        out.write(byteArrayOf(0x00, 0x01))
        out.write(byteArrayOf(0x00, 0x00))
        out.write(byteArrayOf(0x00, 0x01))
        out.write(byteArrayOf(0x00, 0x00, 0x00, 0x00, 0x00, 0x00))
        // The encoded name: a 32-byte half-ASCII form of "*" padded to 16 characters.
        out.write(0x20)
        val name = "*".padEnd(16, '\u0000')
        for (character in name) {
            val code = character.code
            out.write(0x41 + ((code shr 4) and 0x0F))
            out.write(0x41 + (code and 0x0F))
        }
        out.write(0x00)
        // Type NBSTAT, class IN.
        out.write(byteArrayOf(0x00, 0x21))
        out.write(byteArrayOf(0x00, 0x01))
        return out.toByteArray()
    }

    /** What a node status reply named. */
    data class Result(
        val names: List<String>,
        val mac: String?,
    ) {
        /** The first name that is not a workgroup or a domain, which is the machine's own. */
        val machineName: String?
            get() = names.firstOrNull { it.isNotBlank() }
    }

    /**
     * Ask one address for its name table.
     *
     * The reply is a count of entries followed by fixed 18-byte records: a 15-byte name, a suffix
     * byte that says what kind of name it is, and two flag bytes. Group names carry the high bit
     * of the flag word, and a machine's own name is the one worth reporting.
     */
    fun decode(bytes: ByteArray): Result? {
        return runCatching {
            if (bytes.size < 12) return null
            // The question section is echoed back, so the answer count is what to walk. Its
            // offset is found by skipping the question name, which is always 34 bytes in the
            // request this build sends.
            var offset = 12
            while (offset < bytes.size && bytes[offset].toInt() != 0) {
                offset += (bytes[offset].toInt() and 0xFF) + 1
            }
            offset += 1 + 4          // the zero terminator, then type and class
            if (offset >= bytes.size) return null

            val count = bytes[offset].toInt() and 0xFF
            offset += 1
            if (count == 0 || offset + count * 18 > bytes.size) return null

            val names = mutableListOf<String>()
            var mac: String? = null
            for (index in 0 until count) {
                val start = offset + index * 18
                val raw = bytes.copyOfRange(start, start + 15)
                val suffix = bytes[start + 15].toInt() and 0xFF
                val flags = ((bytes[start + 16].toInt() and 0xFF) shl 8) or
                    (bytes[start + 17].toInt() and 0xFF)
                val isGroup = flags and 0x8000 != 0
                // A record's 15-byte name field is space-padded by some implementations and
                // NUL-padded by others, so both are stripped before the name is judged.
                val name = String(raw, Charsets.US_ASCII).trim { it == ' ' || it == '\u0000' }
                if (suffix == 0x00 && !isGroup && name.isNotBlank()) names.add(name)
                // THE UNIT ID IS THE LAST RECORD, and its name field holds six MAC octets in
                // hex rather than a name at all. It is read by position, which is the only way
                // to tell it from a machine name that happens to look similar.
                if (index == count - 1) {
                    mac = unitIdMac(raw)
                }
            }
            Result(names = names.distinct(), mac = mac)
        }.getOrNull()
    }

    /**
     * The MAC address carried in the unit-id record's name field.
     *
     * NBNS puts the address in the first six bytes of the 15-byte name field, with the rest left
     * as padding. The bytes are raw octets rather than ASCII, so they are read as bytes and never
     * as text. A field whose octets are all zero is a device that did not report one, which is
     * reported as null rather than as the address 00:00:00:00:00:00.
     */
    private fun unitIdMac(raw: ByteArray): String? {
        if (raw.size < 6) return null
        val octets = raw.take(6).map { it.toInt() and 0xFF }
        if (octets.all { it == 0 }) return null
        return octets.joinToString(":") { "%02x".format(it) }
    }

    private const val NBNS_PORT = 137
}

/**
 * Reverse DNS straight to the gateway, which is how an appliance that answers nothing gets a name.
 *
 * WHY THIS IS THE HIGHEST-VALUE PROBE FOR APPLIANCES. A washing machine, a fridge and a smart plug
 * join the wifi, take an address from DHCP and then answer nothing on any port. The router keeps
 * the name they sent, and its DNS answers a reverse lookup for the address with it. That single
 * query turns "a device at .14" into "Samsung-Washer", which is the difference between a list
 * nobody can use and one that describes a house.
 *
 * WHY IT GOES TO THE GATEWAY RATHER THAN THE SYSTEM RESOLVER. The phone's resolver may be a
 * private DNS service, which would never see the router's lease table. Asking the gateway directly
 * is asking the machine that holds the answer.
 */
object RouterDnsProbe {

    /**
     * A PTR query for one address's reverse name.
     *
     * The reverse name is the address's four octets in reverse, under `in-addr.arpa`. The query is
     * built with the same head layout as the mDNS builder but sent as a plain DNS query to the
     * gateway with a real transaction id, because a unicast DNS server answers one normally.
     */
    fun reverseQuery(address: String, transactionId: Int): ByteArray {
        val out = ByteArrayOutputStream()
        out.write((transactionId shr 8) and 0xFF)
        out.write(transactionId and 0xFF)
        // 0x0100 asks for recursion, which the router will do from its own lease table.
        out.write(byteArrayOf(0x01, 0x00))
        out.write(byteArrayOf(0x00, 0x01))
        out.write(byteArrayOf(0x00, 0x00, 0x00, 0x00, 0x00, 0x00))

        val labels = address.split('.').reversed() + listOf("in-addr", "arpa")
        for (label in labels) {
            val bytes = label.toByteArray(Charsets.US_ASCII)
            out.write(bytes.size)
            out.write(bytes)
        }
        out.write(0)
        out.write(byteArrayOf(0x00, 0x0C))   // PTR
        out.write(byteArrayOf(0x00, 0x01))   // class IN
        return out.toByteArray()
    }

    /**
     * The name a reply carries, or null when the router did not know the address.
     *
     * Only the answer section is walked, and a name with a compression pointer is rejected rather
     * than followed: the PTR target is a fully written name in practice, and following a pointer
     * into a malformed packet is how a parser reads past the end of its buffer.
     */
    fun decode(bytes: ByteArray): String? {
        return runCatching {
            if (bytes.size < 12) return null
            val answerCount = ((bytes[6].toInt() and 0xFF) shl 8) or (bytes[7].toInt() and 0xFF)
            if (answerCount == 0) return null

            var offset = 12
            // Skip the question section: its name, the zero terminator, then type (2) and
            // class (2). Five bytes after the terminator, which is the off-by-one that made
            // every lookup after the first read its header from the middle of the last one.
            while (offset < bytes.size && bytes[offset].toInt() != 0) {
                offset += (bytes[offset].toInt() and 0xFF) + 1
            }
            offset += 5
            if (offset >= bytes.size) return null

            repeat(answerCount) {
                // An answer name that is a compression pointer, which is the usual case.
                if (offset + 2 > bytes.size) return null
                if (bytes[offset].toInt() and 0xC0 == 0xC0) {
                    offset += 2
                } else {
                    while (offset < bytes.size && bytes[offset].toInt() != 0) {
                        offset += (bytes[offset].toInt() and 0xFF) + 1
                    }
                    offset += 1
                }
                // A resource record is type (2), class (2), ttl (4), then rdlength (2). The type
                // and the length are read from their own fixed offsets rather than by advancing,
                // because an off-by-one here reads the TTL as the length and walks into the
                // wrong bytes on every lookup after the first.
                if (offset + 12 > bytes.size) return null
                val type = ((bytes[offset].toInt() and 0xFF) shl 8) or
                    (bytes[offset + 1].toInt() and 0xFF)
                val dataLength = ((bytes[offset + 10].toInt() and 0xFF) shl 8) or
                    (bytes[offset + 11].toInt() and 0xFF)
                offset += 12
                if (type == 12 && dataLength > 0) {
                    val start = offset
                    val labels = mutableListOf<String>()
                    var cursor = start
                    while (cursor < bytes.size && cursor - start < dataLength) {
                        val length = bytes[cursor].toInt() and 0xFF
                        if (length == 0 || length and 0xC0 != 0) break
                        cursor += 1
                        if (cursor + length > bytes.size) break
                        labels.add(String(bytes, cursor, length, Charsets.US_ASCII))
                        cursor += length
                    }
                    if (labels.isNotEmpty()) return labels.joinToString(".")
                }
                offset += dataLength
            }
            null
        }.getOrNull()
    }
}

/**
 * A non-blocking TCP connect whose failure names which kind of nothing answered.
 *
 * THE DISTINCTION THIS MAKES IS THE WHOLE POINT. A host that is switched off fails address
 * resolution at the ARP layer and reports `EHOSTUNREACH` after three retries, which takes about
 * three seconds. A host that is present but drops the packet leaves the connect pending. A host
 * that is present and closed the port refuses immediately. Those are three different facts and a
 * scanner that counts only successes throws two of them away.
 *
 * AN UNROOTED PHONE CANNOT READ THE NEIGHBOUR TABLE, so this is how presence is proved for a
 * device that answers no service at all: it answered ARP, which is handled below the application
 * and cannot be switched off.
 */
object HostPresence {

    /** What one address turned out to be. */
    enum class State {
        /** Something accepted the connection on this port. */
        OPEN,
        /** The address replied with a refusal, which proves a host is there. */
        PRESENT_CLOSED,
        /** Address resolution failed, so nothing is at this address right now. */
        ABSENT,
        /** The packet went out and nothing came back either way. */
        UNKNOWN,
    }

    /** What one probe returned, with the port that produced it so the caller keeps the fact. */
    data class Probe(val state: State, val port: Int)

    /**
     * Probe one address on a list of ports and report the strongest fact learned.
     *
     * Ordered so a refusal or an open port is found before an absence is concluded, because the
     * first two prove presence and only the third needs all of them to fail.
     */
    fun probe(address: String, ports: List<Int>, timeoutMillis: Int = 350): Probe {
        var sawPresent = false
        var sawAbsent = false
        for (port in ports) {
            val state = probePort(address, port, timeoutMillis)
            when (state) {
                State.OPEN -> return Probe(State.OPEN, port)
                State.PRESENT_CLOSED -> {
                    sawPresent = true
                }
                State.ABSENT -> sawAbsent = true
                State.UNKNOWN -> Unit
            }
        }
        return when {
            sawPresent -> Probe(State.PRESENT_CLOSED, ports.firstOrNull() ?: 0)
            sawAbsent -> Probe(State.ABSENT, 0)
            else -> Probe(State.UNKNOWN, 0)
        }
    }

    /**
     * One connect, classified by how it failed.
     *
     * The exception type is the distinction and it has to be read in this order: a
     * `ConnectException` whose message mentions an unreachable host is an absence, while the same
     * type with any other message is a refusal. A timeout is neither, because there is no evidence
     * either way.
     */
    fun probePort(address: String, port: Int, timeoutMillis: Int = 350): State {
        return runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(address, port), timeoutMillis)
                State.OPEN
            }
        }.getOrElse { error ->
            when (error) {
                is java.net.SocketTimeoutException -> State.UNKNOWN
                is java.net.ConnectException -> {
                    val message = error.message.orEmpty().lowercase()
                    if (message.contains("unreachable") || message.contains("no route")) {
                        State.ABSENT
                    } else {
                        State.PRESENT_CLOSED
                    }
                }
                is java.net.NoRouteToHostException -> State.ABSENT
                else -> State.UNKNOWN
            }
        }
    }
}
