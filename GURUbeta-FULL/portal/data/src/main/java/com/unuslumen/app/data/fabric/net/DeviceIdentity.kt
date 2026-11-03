// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.fabric.net

/**
 * Whether two sightings are the same device, and what to call that device.
 *
 * WHY THIS IS THE MOST IMPORTANT PURE FUNCTION IN THE FEATURE. A network map is only worth having
 * if a device is one thing across time. Get identity wrong in one direction and a device that moved
 * address becomes two entries, so its history splits and the map grows a ghost every time a lease
 * expires. Get it wrong in the other direction and two devices merge, so a printer inherits a
 * fridge's history and the map says things that never happened.
 *
 * THE ORDER OF EVIDENCE, and why each rank is where it is:
 *
 *  1. **A MAC address** is the device's own hardware identity. It survives a reboot, a DHCP
 *     renewal, a rename and a room move. It is the only fact that genuinely identifies a device,
 *     which is why it outranks everything and why recovering one is worth real effort.
 *  2. **An announced name** is the device's own claim about itself, such as a NetBIOS machine name
 *     or an mDNS instance name. It is stable in practice, but a name can be duplicated by a
 *     careless owner, so it ranks below a MAC.
 *  3. **An address** is a lease, not an identity. It ranks last, and a device known only by its
 *     address is explicitly marked as such, so the map records the weakness rather than hiding it.
 *
 * WHY MAC RANDOMISATION IS HANDLED RATHER THAN IGNORED. A phone that randomises its address per
 * network, which both iOS and Android now do by default, presents a different MAC on every join. A
 * map that treated a randomised MAC as hardware identity would create a new device every time the
 * owner walked back into the house. The locally-administered bit in the first octet is what
 * distinguishes a randomised address from a burned-in one, and a randomised address is therefore
 * demoted to the name rank rather than trusted.
 */
object DeviceIdentity {

    /** Which fact an identity was built from, in descending order of reliability. */
    enum class Basis {
        /** A burned-in hardware address, which is the device's own identity. */
        MAC,

        /** A name the device or the network announced, which is stable but not unique. */
        NAME,

        /** Only an address, which is the weakest possible identity and is marked as such. */
        ADDRESS,
    }

    /** One device's identity, with where it came from. */
    data class Identity(
        /** The durable key to store this device under. */
        val id: String,
        val basis: Basis,
        /** The MAC address, when one was usable, normalised and lowercased. */
        val mac: String? = null,
        /** A sentence explaining the basis, for the evidence list. */
        val reason: String,
    )

    /**
     * The identity of one sighting, from the strongest fact available.
     *
     * [address] is required because it is the one thing every sighting has. [name] and [mac] are
     * whatever the sweep recovered, and either may be null.
     */
    fun of(address: String, mac: String?, name: String?): Identity {
        val normalisedMac = mac?.let { normaliseMac(it) }?.takeIf { isHardwareAddress(it) }
        if (normalisedMac != null) {
            return Identity(
                id = "mac:$normalisedMac",
                basis = Basis.MAC,
                mac = normalisedMac,
                reason = "identified by its hardware address $normalisedMac, which stays the same " +
                    "across reboots and address changes",
            )
        }

        // A NAME THAT IS THE ADDRESS IS NOT A NAME. Some devices announce their hostname as their
        // own IP, and a router's reverse lookup can return nothing but the address it was asked
        // about. Keying one of those as `name:192.0.2.14` looked strong while actually being the
        // weakest identity there is, and it hid the weakness from the reason a person reads.
        val nameIsAddress = name?.trim() == address

        // A RANDOMISED MAC IS DELIBERATELY NOT USED AS IDENTITY. Both phones and some laptops now
        // present a different address on every network join, so trusting one would create a new
        // device each time the owner came home. When a randomised address IS the only thing seen,
        // it is still reported, but the identity falls through to a name or the address.
        val randomised = mac?.let { normaliseMac(it) }?.takeIf { it.isNotBlank() && !isHardwareAddress(it) }

        val safeName = name?.trim()?.takeIf { it.isNotBlank() }?.takeIf { !nameIsAddress }
        if (safeName != null) {
            return Identity(
                id = "name:${safeName.lowercase()}",
                basis = Basis.NAME,
                mac = randomised,
                reason = if (randomised != null) {
                    "identified by the name \"$safeName\" because its hardware address " +
                        "$randomised is randomised and changes on every join"
                } else {
                    "identified by the name \"$safeName\", which it announced itself"
                },
            )
        }

        return Identity(
            id = "address:$address",
            basis = Basis.ADDRESS,
            mac = randomised,
            reason = "identified only by the address $address, so this device will appear as a " +
                "second entry if its address changes",
        )
    }

    /**
     * A MAC address in one canonical form, so two spellings of the same address are one device.
     *
     * A device may report `94-B3-F7-90-92-81`, `94:b3:f7:90:92:81` or `94b3f7909281` depending on
     * the protocol, and an identity that treated those as three devices would defeat the point of
     * having one. Everything is lowercased and colon-separated, and a value that is not six octets
     * is rejected rather than stored as a key nobody can match.
     */
    fun normaliseMac(raw: String): String? {
        val hex = raw.lowercase().filter { it in "0123456789abcdef" }
        if (hex.length != 12) return null
        return hex.chunked(2).joinToString(":")
    }

    /**
     * Whether a MAC address is a burned-in hardware address rather than a randomised one.
     *
     * The second-least-significant bit of the first octet is the locally-administered bit: set
     * means the address was made up by software rather than assigned by the manufacturer. That is
     * the same bit virtual machines, phone hotspots and every privacy feature in modern iOS and
     * Android use, so it is the correct test rather than a heuristic.
     */
    fun isHardwareAddress(mac: String): Boolean {
        val first = mac.substringBefore(':').toIntOrNull(16) ?: return false
        // Bit 1 of the first octet set means locally administered, so NOT a hardware address.
        return first and 0x02 == 0
    }

    /**
     * Whether two identities certainly describe the same device.
     *
     * Used when merging a fresh sweep into a stored map. Only the strong bases count as certain: a
     * matching MAC is the same device, and a matching name is the same device. A matching address
     * is NOT proof, because a lease can move to a different device between sweeps.
     */
    fun sameDevice(a: Identity, b: Identity): Boolean = when {
        a.basis == Basis.MAC && b.basis == Basis.MAC -> a.mac != null && a.mac == b.mac
        a.basis == Basis.NAME && b.basis == Basis.NAME -> a.id == b.id
        else -> false
    }
}
