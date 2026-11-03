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
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Everything broadcasting on this network, found by running every protocol at once.
 *
 * WHY A SECOND SWEEP RATHER THAN A REWRITE OF [NetworkScanner]. The existing scanner is wired into
 * three tools and its own tests, and it does its job: a TCP sweep, SSDP and mDNS. This adds the
 * protocols it has never had — SNMP, NBNS, reverse DNS through the gateway — and changes two
 * things it got wrong. Replacing it in one move would take three tools down with it on a bad day,
 * so this is the sweep the tools migrate onto once it is proven, and the old one stays until then.
 *
 * THE TWO FAULTS THIS FIXES.
 *
 * FIRST, THE SWEEP WAS NEVER BOUND TO THE WIFI NETWORK. When the phone has a validated mobile
 * connection and the wifi has no internet, Android makes cellular the default network, and every
 * unbound socket sends its packets out of the wrong interface. That is a network full of devices
 * reporting as empty, and it is why a laptop on the same wifi found three sets while the phone
 * found none. [wifiInterface] resolves the wifi interface once per sweep and
 * [HostPresence.probePort] takes it, so every connect leaves by the right interface.
 *
 * SECOND, PRESENCE WAS ONLY PROVED BY AN OPEN PORT. A device that answers ARP and drops every
 * connection is on the network, and it was invisible. [HostPresence] classifies how a connect
 * failed so a refusal and an unreachable host are different facts.
 *
 * THE BUDGET. Every protocol runs at the same time, so the wall clock is the slowest stage rather
 * than the sum. The aliveness pass decides which addresses are worth the expensive probes, and the
 * expensive probes only run against those. Nothing has a wall-clock cut across the whole sweep,
 * because a cut of that kind is how a device that answers slowly gets missed.
 */
class FullNetworkSweep(
    /** How many sockets exist at once, bounded so a sweep cannot OOM the phone. */
    private val concurrency: Int = 128,
    /** How long one TCP connect may take before it is neither open nor refused. */
    private val connectTimeoutMillis: Int = 350,
    /** Passed in so a test can drive the sweep against a fake instead of a live network. */
    private val targets: ScanTargets = ScanTargets(),
) {

    /**
     * Sweep everything reachable from this phone and report each device once.
     *
     * [onFound] is called as each device is identified rather than at the end, because a sweep
     * that takes twenty seconds and says nothing until it finishes reads as a hang.
     */
    suspend fun sweep(
        onFound: (MergedDevice) -> Unit = {},
        onStage: (String) -> Unit = {},
    ): SweepReport = withContext(Dispatchers.IO) {
        val problems = mutableListOf<String>()

        val prefix = targets.subnetPrefix()
        if (prefix == null) {
            return@withContext SweepReport(
                devices = emptyList(),
                problems = listOf(
                    "This phone is not on a wifi network with an IPv4 address, so there is no " +
                        "local network to sweep. A phone on mobile data cannot see a home network.",
                ),
                probedAddresses = 0,
                subnet = null,
            )
        }

        val addresses = (1..254).map { "$prefix.$it" }
        onStage("sweeping $prefix.0/24 across every protocol at once")

        // One record per address, and everything that answers merges into it.
        val reports = ConcurrentHashMap<String, MutableList<Identified>>()

        // What each address exposed, kept beside its reports rather than inside them. A port is not
        // an identification: it is a fact about the host that several probes contribute to and that
        // the map stores separately, so a port opening between two sweeps is visible as a change.
        val portsByAddress = ConcurrentHashMap<String, MutableSet<Int>>()

        fun record(address: String, identified: Identified) {
            reports.getOrPut(address) { java.util.Collections.synchronizedList(mutableListOf()) }
                .add(identified)
        }

        fun recordPorts(address: String, ports: Collection<Int>) {
            if (ports.isEmpty()) return
            val set = portsByAddress.getOrPut(address) {
                java.util.Collections.synchronizedSet(mutableSetOf())
            }
            set.addAll(ports)
        }

        // RESOLVED ONCE FOR THE WHOLE SWEEP. Every socket below is bound to this interface, because
        // a phone whose mobile data is validated sends an unbound socket's packets out of the
        // carrier and every device on the wifi reads as absent. Looking it up once also means a
        // mid-sweep interface change cannot half-bind the work.
        val wifi = targets.wifiInterface()

        // THE ALIVENESS PASS COMES FIRST because it decides what the expensive probes are spent
        // on, and because a present-but-silent host is proved here and nowhere else.
        val alive = findAlive(addresses, problems, onStage, wifi)
        onStage("${alive.size} address(es) answered; asking each one what it is")

        // ONE BUDGET FOR THE WHOLE PORT SWEEP, shared by every host, so fifty live devices cannot
        // each open their own full set of sockets. See [sweepPorts].
        val portGate = Semaphore(concurrency)

        coroutineScope {
            // THE PORT SWEEP, which is what gives a device its open ports and what finds a device
            // that answers a service the identification probes do not speak. It runs only against
            // live addresses, because probing a switched-off host costs a full timeout each.
            launchEach(alive) { address ->
                recordPorts(address, sweepPorts(address, portGate, wifi))
            }

            // SNMP, which is the only protocol that names a printer or a switch that serves no
            // web page. It runs against every live address rather than every address, because an
            // absent host costs a full timeout and there are 254 of them.
            launchEach(alive) { address ->
                val channel = UdpProbeChannel(bindTo = wifi)
                val result = SnmpProbe.query(address, channel)
                if (result != null) {
                    record(address, DeviceIdentifier.fromSnmp(result.values))
                }
            }

            // NBNS, for the name and MAC that a Windows machine or NAS will give away.
            launchEach(alive) { address ->
                val channel = UdpProbeChannel(bindTo = wifi)
                val replies = channel.exchange(
                    address, NBNS_PORT, NbnsProbe.nodeStatusRequest(), deadlineMillis = 500,
                )
                val first = replies.firstNotNullOfOrNull { NbnsProbe.decode(it) }
                if (first != null) {
                    record(
                        address,
                        Identified(
                            name = first.machineName,
                            evidence = listOf(
                                Evidence(
                                    "NetBIOS",
                                    "answered to the name ${first.machineName ?: "unknown"}" +
                                        (first.mac?.let { ", hardware address $it" } ?: ""),
                                    EvidenceKind.DECLARED,
                                ),
                            ),
                        ),
                    )
                }
            }

            // REVERSE DNS THROUGH THE GATEWAY. This is the only probe that names an appliance
            // which answers nothing at all, because the name came from the DHCP request it made
            // when it joined and the router still holds it.
            //
            // THE GATEWAY IS RESOLVED ONCE, OUTSIDE THE PER-ADDRESS LOOP. It used to be looked up
            // inside it, which meant 250 live addresses each did two TCP probes to find the same
            // answer, and every one of those probes went out unbound.
            val gateway = targets.gateway()
            launchEach(alive) { address ->
                if (gateway != null) {
                    val channel = UdpProbeChannel(bindTo = wifi)
                    val transactionId = dnsCounter.getAndIncrement() and 0xFFFF
                    val replies = channel.exchange(
                        gateway, DNS_PORT,
                        RouterDnsProbe.reverseQuery(address, transactionId),
                        deadlineMillis = 400,
                    )
                    val name = replies.firstNotNullOfOrNull { RouterDnsProbe.decode(it) }
                    if (name != null && name.isNotBlank()) {
                        record(address, DeviceIdentifier.fromHostname(name))
                    }
                }
            }
        }

        // EVERY ADDRESS THAT ANSWERED BECOMES A DEVICE, even one no protocol could name. A host
        // that refused a connection is a host that is there, and dropping it because it had
        // nothing to say would report an empty network on a network full of devices.
        val everyAddress = (reports.keys + portsByAddress.keys).toSet()

        val devices = everyAddress.map { address ->
            DeviceMerge.merge(
                address = address,
                reports = reports[address]?.toList() ?: emptyList(),
                openPorts = portsByAddress[address]?.toList() ?: emptyList(),
            )
        }.sortedBy { it.address.substringAfterLast('.').toIntOrNull() ?: 999 }

        devices.forEach(onFound)

        if (devices.isEmpty()) {
            problems.add(
                "No address on $prefix.0/24 answered anything. Every device is asleep, off, on a " +
                    "guest network, or behind client isolation that stops devices seeing each other.",
            )
        }

        SweepReport(
            devices = devices,
            problems = problems,
            probedAddresses = addresses.size,
            subnet = prefix,
            aliveAddresses = alive,
        )
    }

    /**
     * Which addresses exist, which is a different question from what they are.
     *
     * THE THREE ANSWERS THAT MATTER, in order of how much each proves:
     *
     *  1. A port accepted, so the host is there and running that service.
     *  2. A port refused, so a host is there and has that port closed. Still proof of presence.
     *  3. Address resolution failed, so nothing is at that address. This is the only answer that
     *     justifies skipping the address, and it is why the expensive probes do not run against
     *     the 240 addresses in a house that are empty.
     */
    private suspend fun findAlive(
        addresses: List<String>,
        problems: MutableList<String>,
        onStage: (String) -> Unit,
        bindTo: java.net.NetworkInterface? = null,
    ): List<String> = coroutineScope {
        val gate = Semaphore(concurrency)
        val alive = ConcurrentHashMap.newKeySet<String>()

        addresses.map { address ->
            async {
                gate.withPermit {
                    val probe = HostPresence.probe(
                        address, ALIVENESS_PORTS, connectTimeoutMillis, bindTo,
                    )
                    when (probe.state) {
                        HostPresence.State.OPEN,
                        HostPresence.State.PRESENT_CLOSED,
                        -> alive.add(address)

                        HostPresence.State.ABSENT -> Unit
                        // UNKNOWN means the packet left and nothing came back either way. That is
                        // not proof of absence, so the address is kept rather than discarded: an
                        // address that is only skipped on proof is the difference between a scan
                        // and a guess.
                        HostPresence.State.UNKNOWN -> alive.add(address)
                    }
                }
            }
        }.awaitAll()

        if (alive.isEmpty()) {
            problems.add(
                "No address answered, and none refused a connection either, which means this " +
                    "phone's packets are not reaching the network. Check the phone is on the " +
                    "wifi rather than mobile data.",
            )
        }
        onStage("${alive.size} of ${addresses.size} addresses are present")
        alive.sortedBy { it.substringAfterLast('.').toIntOrNull() ?: 999 }
    }

    /**
     * Every port on one address that accepts a connection.
     *
     * THE PORT LIST IS THE PROTOCOL LIST, NOT A GUESSED SET OF NUMBERS. Each entry is a port a
     * real class of device actually opens, grouped by what answering it means, and every one of
     * them is also something the classification rules can use. Scanning all 65535 ports is the
     * thing that turns a sweep into a run that never ends, and on a home network it returns
     * nothing the protocol ports did not already return.
     *
     * THE CONCURRENCY BUDGET IS PER SWEEP, NOT PER ADDRESS, and that is a real correction. The
     * first version took a fresh [Semaphore] for every host, so fifty live hosts could each open
     * [concurrency] sockets at once, which is several thousand sockets on a phone. The budget is
     * now owned by the caller and passed in, so the ceiling is the sweep's and not one host's.
     */
    private suspend fun sweepPorts(
        address: String,
        gate: Semaphore,
        bindTo: java.net.NetworkInterface? = null,
    ): List<Int> = coroutineScope {
        SERVICE_PORTS.map { port ->
            async {
                gate.withPermit {
                    if (HostPresence.probePort(address, port, connectTimeoutMillis, bindTo) ==
                        HostPresence.State.OPEN
                    ) {
                        port
                    } else {
                        null
                    }
                }
            }
        }.awaitAll().filterNotNull()
    }

    /** Run [block] on every item with a bounded number in flight. */
    private suspend fun <T> launchEach(items: List<T>, block: suspend (T) -> Unit) = coroutineScope {
        val gate = Semaphore(concurrency)
        items.map { item ->
            async {
                gate.withPermit {
                    // A single probe that throws must not take the sweep with it, because one
                    // malformed answer from one device is not a reason to lose the other fifty.
                    runCatching { block(item) }
                }
            }
        }.awaitAll()
    }

    /** What one sweep found. */
    data class SweepReport(
        val devices: List<MergedDevice>,
        val problems: List<String>,
        val probedAddresses: Int,
        val subnet: String?,
        val aliveAddresses: List<String> = emptyList(),
    ) {
        val count: Int get() = devices.size
    }

    companion object {
        /**
         * The ports asked to decide whether an address exists at all.
         *
         * Chosen for ubiquity rather than for meaning: a router, a printer and a phone each
         * answer at least one of these, and a refusal from any of them is proof the host is
         * there. This is a presence question, not an identity one, so the list stays short.
         */
        val ALIVENESS_PORTS = listOf(80, 443, 445, 62078, 5555, 8060, 8009, 631, 53)

        /**
         * The ports a device on a home network actually opens.
         *
         * EVERY ENTRY IS A PROTOCOL SOMETHING SPEAKS, never a number picked to fill a range. Grouped
         * so the reason each is here survives:
         *
         *  - Web and remote access: 22 ssh, 23 telnet, 80, 443, 8080, 8443.
         *  - Printers: 515 lpd, 631 ipp, 9100 raw, 3910, 3911.
         *  - Cameras: 554 rtsp, 8554 an alternate rtsp, 34567 a common DVR port.
         *  - Files and desktops: 139, 445 smb, 3389 rdp, 548 afp, 2049 nfs.
         *  - Media and streaming: 8008 cast, 8009 dial, 8060 roku, 8001 and 8002 samsung,
         *    3000 and 3001 lg, 7345 vizio, 6466 and 6467 android tv, 1925 and 1926 philips,
         *    9000 philips, 7000 and 7100 airplay, 5000 and 5001 synology, 32400 plex.
         *  - Home automation and IoT: 1883 and 8883 mqtt, 8123 home assistant, 5683 coap,
         *    49152 and 49153 upnp, 9080 a media service, 6668 a common IoT websocket.
         *  - Phones and set-top boxes: 62078 ios lockdown, 5555 adb, 5556 an alternate.
         *
         * Deliberately absent: the 1..65535 walk. It is minutes per host, it is the reason an
         * earlier sweep never finished, and everything a home network runs is in this list.
         */
        val SERVICE_PORTS: List<Int> = listOf(
            22, 23, 53, 80, 139, 443, 445, 515, 548, 554, 631,
            1400, 1883, 1925, 1926, 2049, 3000, 3001, 3389, 3910, 3911, 5000, 5001,
            5555, 5556, 5683, 6668, 7000, 7100, 7345, 8001, 8002, 8008, 8009, 8060,
            8080, 8123, 8443, 8554, 8883, 9000, 9080, 9100, 32400, 34567, 49152,
            49153, 62078, 6466, 6467,
        ).sorted()

        private const val NBNS_PORT = 137
        private const val DNS_PORT = 53

        private val dnsCounter = AtomicInteger(1)
    }
}

/**
 * Where a sweep should look, and which interface its packets must leave by.
 *
 * WHY THIS IS A SEPARATE OBJECT. Every value here comes from Android's connectivity services, and
 * a test cannot provide those. Wrapping them means the sweep can be driven against a fake network
 * in a JVM test, and the one production implementation is the only code that touches the platform.
 *
 * THE INTERFACE IS THE PART THAT WAS MISSING. [bind] is what a caller hands to every socket so the
 * packet leaves by the wifi interface even when Android considers cellular the default. Without it
 * a scan on a phone with a working mobile connection and an unvalidated wifi sees nothing at all.
 */
open class ScanTargets {

    /** The /24 this phone is on, or null when it is not on a private IPv4 network. */
    open fun subnetPrefix(): String? = runCatching {
        for (iface in NetworkInterface.getNetworkInterfaces().toList()) {
            if (!iface.isUp || iface.isLoopback) continue
            // A mobile data interface is never the network to sweep, and sweeping it would be a
            // scan of the carrier's address space rather than the user's own.
            if (isCellular(iface)) continue
            for (address in iface.inetAddresses.toList()) {
                if (address !is Inet4Address || address.isLoopbackAddress) continue
                val host = address.hostAddress ?: continue
                if (isPrivateIpv4(host)) return host.substringBeforeLast('.')
            }
        }
        null
    }.getOrNull()

    /** The interface a sweep's packets must leave by, or null when the platform decides. */
    open fun wifiInterface(): NetworkInterface? = runCatching {
        for (iface in NetworkInterface.getNetworkInterfaces().toList()) {
            if (!iface.isUp || iface.isLoopback || isCellular(iface)) continue
            val hasPrivateV4 = iface.inetAddresses.toList().any { address ->
                address is Inet4Address && isPrivateIpv4(address.hostAddress.orEmpty())
            }
            if (hasPrivateV4) return iface
        }
        null
    }.getOrNull()

    /** The gateway address, which is where reverse DNS and the router's own tables live. */
    open fun gateway(): String? = runCatching {
        // The gateway is found from the routing table rather than assumed to be .1, because a
        // network with a mesh node or a second router has its gateway somewhere else entirely.
        val prefix = subnetPrefix() ?: return null
        val candidates = listOf("$prefix.1", "$prefix.254")
        val wifi = wifiInterface()
        candidates.firstOrNull { address ->
            HostPresence.probePort(address, 53, 300, wifi) != HostPresence.State.ABSENT
        }
    }.getOrNull()

    private fun isCellular(iface: NetworkInterface): Boolean {
        val name = iface.name.lowercase()
        return name.startsWith("rmnet") || name.startsWith("ccmni") ||
            name.startsWith("pdp") || name.startsWith("wwan")
    }

    private fun isPrivateIpv4(host: String): Boolean {
        val octets = host.split('.').mapNotNull { it.toIntOrNull() }
        if (octets.size != 4) return false
        return when {
            octets[0] == 10 -> true
            octets[0] == 192 && octets[1] == 168 -> true
            octets[0] == 172 && octets[1] in 16..31 -> true
            else -> false
        }
    }
}
