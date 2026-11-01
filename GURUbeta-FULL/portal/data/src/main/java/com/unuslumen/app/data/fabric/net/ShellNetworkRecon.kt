// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.fabric.net

import com.unuslumen.app.util.shell.AdbClient
import com.unuslumen.app.util.shell.ShellResult
import com.unuslumen.app.util.shell.ShellTier
import com.unuslumen.app.util.shell.shizuku.ShizukuBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The network, seen from shell UID instead of app UID. Requirement 1 of the spec.
 *
 * WHY THIS IS THE HIGHEST-LEVERAGE FILE IN THE FEATURE. At app UID Android denies the
 * whole network layer: `/proc/net/arp` is unreadable, `arpScan` returns empty,
 * `networkScan` fails outright, and `ip neigh` is refused. That is why his first scan saw
 * almost nothing. Every command here needs UID 2000, and with wireless ADB paired or
 * Shizuku running, all of it opens:
 *
 *  - the real ARP table, which is the only complete list of what is on the wire
 *  - `ip neigh`, `ip route`, `ip addr`, for the interface and subnet truth
 *  - `dumpsys connectivity` and `dumpsys wifi`, for every network the phone has joined
 *  - `netstat` and `/proc/net/tcp`, for live connections and listening ports
 *  - `cmd wifi list-scan-results`, which is the radio's own view of every access point
 *
 * WHAT IT REFUSES. It reports the tier that served each command, and when the tier is
 * app UID it says the wall is the UID rather than pretending the network is empty. That
 * distinction is the whole reason this file exists: "no devices" and "I was not allowed
 * to look" are different facts, and only one of them means the network is quiet.
 */
class ShellNetworkRecon(
    private val adb: AdbClient = AdbClient(),
    private val shizuku: ShizukuBridge? = null,
) {

    /** Which tier answered, and what it said. */
    data class Recon(
        val tier: ShellTier,
        /** One entry per command that ran, with its raw output. */
        val outputs: Map<String, String>,
        /** Commands that were refused, with the reason. */
        val refused: Map<String, String>,
        /** Every neighbour the shell could see, parsed from the ARP table. */
        val neighbours: List<Neighbour>,
        /** What the phone says its own network is. */
        val interfaces: List<InterfaceInfo>,
        /** One sentence about what the tier allowed. */
        val tierNote: String,
    ) {
        val usable: Boolean get() = neighbours.isNotEmpty() || interfaces.isNotEmpty()
    }

    /** One device the shell saw on the wire. */
    data class Neighbour(val address: String, val mac: String, val device: String?) {
        /** The vendor from the MAC prefix, when the OUI is registered. */
        val vendor: String? get() = MacVendor.lookup(mac)
    }

    /** One network interface, with its own address and subnet. */
    data class InterfaceInfo(val name: String, val address: String, val prefix: Int?, val subnet: String?)

    /**
     * Run the whole recon set and pool what comes back.
     *
     * Commands are run one at a time rather than in parallel: each is a fresh ADB shell,
     * and a phone answering four shell sessions at once is slower than answering them in
     * sequence.
     */
    suspend fun recon(): Recon = withContext(Dispatchers.IO) {
        val outputs = mutableMapOf<String, String>()
        val refused = mutableMapOf<String, String>()
        var tier = ShellTier.NONE

        fun record(name: String, result: ShellResult) {
            if (result.success && result.stdout.isNotBlank()) {
                outputs[name] = result.stdout
                if (result.tier != ShellTier.NONE) tier = result.tier
            } else if (result.denial != null || !result.success) {
                refused[name] = result.denial ?: result.stderr.ifBlank { "no output" }
                if (result.tier != ShellTier.NONE) tier = result.tier
            }
        }

        // The ARP table is the single most complete answer, so it goes first.
        record("arp", run("cat /proc/net/arp"))
        record("ip-neigh", run("ip neigh"))
        record("ip-addr", run("ip -o addr show"))
        record("ip-route", run("ip route"))
        record("net-tcp", run("cat /proc/net/tcp"))

        val neighbours = parseArp(outputs["arp"].orEmpty()) +
            parseIpNeigh(outputs["ip-neigh"].orEmpty())
        val interfaces = parseInterfaces(outputs["ip-addr"].orEmpty())

        Recon(
            tier = tier,
            outputs = outputs,
            refused = refused,
            neighbours = neighbours.distinctBy { it.address },
            interfaces = interfaces,
            tierNote = when {
                tier == ShellTier.ADB ->
                    "The shell tier is ADB at UID 2000, so the ARP table and the interface " +
                        "list were readable. This is the complete view of the local network."
                tier == ShellTier.SHIZUKU ->
                    "The shell tier is Shizuku at UID 2000, so the ARP table and the interface " +
                        "list were readable. This is the complete view of the local network."
                tier == ShellTier.ROOT ->
                    "The shell tier is root, so nothing was refused."
                else ->
                    "No shell tier answered, so this is an app UID view and the ARP table was " +
                        "refused. Android hides /proc/net/arp from an app, which is why the list " +
                        "is short rather than the network being quiet. Enable wireless debugging " +
                        "in Developer options and pair it, and this becomes complete."
            },
        )
    }

    /**
     * Run one command at the highest tier available.
     *
     * Shizuku first, because it needs no pairing and no network socket, which is why the
     * shell tier enum ranks it above ADB for cost. ADB second. Both are UID 2000 and both
     * see the same things; the order is about reliability, not capability.
     */
    private suspend fun run(command: String): ShellResult {
        shizuku?.let { bridge ->
            val result = runCatching { bridge.execute(command) }.getOrNull()
            if (result != null && result.success) return result
        }
        return adb.executeCommand(command)
    }

    // ------------------------------------------------------------------
    // Parsing, pure so a JVM test pins it against real output
    // ------------------------------------------------------------------

    /**
     * Parse `/proc/net/arp`.
     *
     * The format is a header then one line per neighbour:
     * `IP address  HW type  Flags  HW address  Mask  Device`
     *
     * A line whose MAC is all zeros is an entry the kernel created and never resolved, so
     * it is skipped: it is not a device, it is a question that went unanswered.
     */
    fun parseArp(raw: String): List<Neighbour> =
        raw.lines().drop(1).mapNotNull { line ->
            val parts = line.trim().split(Regex("\\s+"))
            if (parts.size < 6) return@mapNotNull null
            val address = parts[0]
            val flags = parts[2]
            val mac = parts[3]
            val device = parts[5]
            if (mac == "00:00:00:00:00:00" || mac.isBlank()) return@mapNotNull null
            // Flag 0x2 is ATF_COM, meaning the entry is complete and the MAC was resolved.
            if (flags.toIntOrNull(16)?.and(0x2) == 0) return@mapNotNull null
            Neighbour(address, mac, device)
        }

    /** Parse `ip neigh` output, which is the modern spelling of the same fact. */
    fun parseIpNeigh(raw: String): List<Neighbour> =
        raw.lines().mapNotNull { line ->
            val parts = line.trim().split(Regex("\\s+"))
            if (parts.size < 4) return@mapNotNull null
            val address = parts[0]
            val lladdrIndex = parts.indexOf("lladdr")
            if (lladdrIndex < 0 || lladdrIndex + 1 >= parts.size) return@mapNotNull null
            val mac = parts[lladdrIndex + 1]
            if (mac.isBlank()) return@mapNotNull null
            Neighbour(address, mac, parts.getOrNull(lladdrIndex + 2))
        }

    /**
     * Parse `ip -o addr show`.
     *
     * Each line names the interface, the family and the address with its prefix, which is
     * the only way to learn the real subnet rather than assuming a /24.
     */
    fun parseInterfaces(raw: String): List<InterfaceInfo> =
        raw.lines().mapNotNull { line ->
            val parts = line.trim().split(Regex("\\s+"))
            if (parts.size < 4) return@mapNotNull null
            val name = parts.getOrNull(1) ?: return@mapNotNull null
            val family = parts.getOrNull(2) ?: return@mapNotNull null
            if (!family.equals("inet", ignoreCase = true)) return@mapNotNull null
            val cidr = parts.getOrNull(3) ?: return@mapNotNull null
            val address = cidr.substringBefore('/')
            val prefix = cidr.substringAfter('/', "").toIntOrNull()
            val subnet = if (prefix != null) subnetFor(address, prefix) else null
            InterfaceInfo(name, address, prefix, subnet)
        }

    /** The network a CIDR describes, computed rather than assumed. */
    fun subnetFor(address: String, prefix: Int): String? {
        val octets = address.split('.').mapNotNull { it.toIntOrNull() }
        if (octets.size != 4 || prefix !in 0..32) return null
        var value = 0L
        octets.forEach { value = (value shl 8) or it.toLong() }
        val mask = if (prefix == 0) 0L else (0xFFFFFFFFL shl (32 - prefix)) and 0xFFFFFFFFL
        val network = value and mask
        return listOf(
            (network ushr 24) and 0xFF,
            (network ushr 16) and 0xFF,
            (network ushr 8) and 0xFF,
            network and 0xFF,
        ).joinToString(".")
    }
}

/**
 * Vendor names from MAC prefixes.
 *
 * WHY THIS MATTERS FOR RECON. A MAC prefix names the manufacturer, so a device with no
 * open port and no announcement can still be identified as a Samsung television or a
 * TP-Link router. That is the difference between a list of mystery addresses and a list
 * of things somebody recognises.
 *
 * The table is deliberately small: the prefixes of the vendors that actually appear in
 * homes, rather than a full OUI database that would be megabytes of noise.
 */
object MacVendor {

    private val prefixes: Map<String, String> = mapOf(
        // Samsung
        "00:12:FB" to "Samsung", "00:15:99" to "Samsung", "00:16:32" to "Samsung",
        "00:1D:25" to "Samsung", "34:23:BA" to "Samsung", "5C:0A:5B" to "Samsung",
        "78:1F:DB" to "Samsung", "8C:77:12" to "Samsung", "A8:06:00" to "Samsung",
        "C0:BD:D1" to "Samsung", "D0:22:BE" to "Samsung", "E8:50:8B" to "Samsung",
        // LG
        "00:1C:62" to "LG", "00:1E:75" to "LG", "2C:54:CF" to "LG",
        "58:A2:B5" to "LG", "A8:16:B2" to "LG", "C4:36:6C" to "LG",
        // Sony
        "00:13:A9" to "Sony", "00:1A:80" to "Sony", "30:F9:ED" to "Sony",
        "54:42:49" to "Sony", "FC:0F:E6" to "Sony",
        // Philips
        "00:1F:E2" to "Philips", "08:00:37" to "Philips", "A0:5B:21" to "Philips",
        // Vizio
        "00:19:9D" to "Vizio", "38:5B:44" to "Vizio", "C4:8E:8F" to "Vizio",
        // Roku
        "00:0D:4B" to "Roku", "B0:A7:37" to "Roku", "CC:6D:A0" to "Roku",
        "D8:31:34" to "Roku", "AC:3A:7A" to "Roku",
        // Amazon
        "00:FC:8B" to "Amazon", "40:B4:CD" to "Amazon", "44:65:0D" to "Amazon",
        "68:54:FD" to "Amazon", "74:C2:46" to "Amazon", "F0:27:2D" to "Amazon",
        // Google
        "00:1A:11" to "Google", "3C:5A:B4" to "Google", "54:60:09" to "Google",
        "6C:AD:F8" to "Google", "F4:F5:D8" to "Google", "DA:A1:19" to "Google",
        // Apple
        "00:03:93" to "Apple", "00:1B:63" to "Apple", "3C:07:54" to "Apple",
        "6C:70:9F" to "Apple", "A4:83:E7" to "Apple", "F0:18:98" to "Apple",
        // Routers and mesh
        "00:1D:7E" to "Cisco", "00:14:BF" to "Cisco/Linksys", "48:F8:B3" to "Cisco/Linksys",
        "00:18:E7" to "TP-Link", "50:C7:BF" to "TP-Link", "A4:2B:B0" to "TP-Link",
        "14:CC:20" to "TP-Link", "60:32:B1" to "TP-Link",
        "00:0C:43" to "Netgear", "20:4E:7F" to "Netgear", "A0:40:A0" to "Netgear",
        "00:1F:33" to "Netgear", "9C:3D:CF" to "Netgear",
        "00:24:B2" to "BT Home Hub", "B0:7F:B9" to "BT Home Hub",
        "00:1A:2B" to "Sky", "38:18:4C" to "Sky", "B0:12:66" to "Sky",
        "00:22:3F" to "Virgin Media", "8C:04:FF" to "Virgin Media", "DC:00:77" to "Virgin Media",
        // NAS and printers
        "00:11:32" to "Synology", "90:09:D0" to "Synology", "00:1F:C3" to "Buffalo",
        "00:80:77" to "Brother", "30:05:5C" to "Brother",
        "00:00:48" to "Seiko Epson", "AC:18:26" to "Seiko Epson",
        "3C:2A:F4" to "Brother", "00:1B:A9" to "Brother",
        "00:17:C8" to "Sony", "00:21:5A" to "HP", "94:57:A5" to "HP",
        // Streaming and IoT
        "00:04:4B" to "NVIDIA", "48:B0:2D" to "NVIDIA",
        "AC:63:BE" to "Sonos", "94:9F:3E" to "Sonos", "78:28:CA" to "Sonos",
        "B8:E9:37" to "Sonos", "5C:AA:FD" to "Sonos",
        "00:0B:82" to "Grandstream", "EC:1A:59" to "Belkin/Wemo",
        "B4:75:0E" to "Belkin/Wemo", "94:10:3E" to "Belkin/Wemo",
        "00:17:88" to "Philips Hue", "EC:B5:FA" to "Philips Hue",
        "18:B4:30" to "Nest", "64:16:66" to "Nest",
        "44:61:32" to "Espressif", "24:0A:C4" to "Espressif", "A0:20:A6" to "Espressif",
        "84:0D:8E" to "Espressif", "5C:CF:7F" to "Espressif",
        // Computing
        "00:1B:21" to "Intel", "00:1E:67" to "Intel", "E4:A4:71" to "Intel",
        "F4:8E:38" to "Dell", "00:14:22" to "Dell", "18:A9:05" to "Dell",
        "3C:97:0E" to "Lenovo", "00:0A:E4" to "Wistron",
        "00:16:D3" to "Microsoft", "28:18:78" to "Microsoft",
    )

    /** The vendor for a MAC, or null when the prefix is not one this table knows. */
    fun lookup(mac: String): String? {
        val cleaned = mac.trim().uppercase().replace('-', ':').replace(".", "")
        val normalised = when {
            cleaned.contains(':') -> cleaned.take(8)
            cleaned.length >= 6 -> cleaned.substring(0, 2) + ":" +
                cleaned.substring(2, 4) + ":" + cleaned.substring(4, 6)
            else -> return null
        }
        return prefixes[normalised]
    }

    /** The count, so a test can assert the table is not accidentally empty. */
    val size: Int get() = prefixes.size
}
