// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.util.shell

/**
 * Where to reach the ADB daemon, and on which of its three ports.
 *
 * WHY THIS EXISTS (issue 389). The pairing path had one number where Android has three, and it
 * hardcoded the wrong one for two of them:
 *
 *  - **The pairing port** comes from the "Pair device with pairing code" dialog and changes every
 *    time wireless debugging is cycled.
 *  - **The connect port** comes from the same dialog and is a different random number again. Nothing
 *    in Android uses 5555.
 *  - **5555** is what this app hardcoded, as a constant named `WIRELESS_DEBUGGING_PORT`, and it is not
 *    a wireless-debugging port at all on any modern device.
 *
 * The consequence was that a successful pairing led nowhere: `AdbClient` was constructed with 5555
 * regardless, dialled a port nothing listened on, and reported "Wireless Debugging may not be
 * enabled" — a sentence about the wrong thing.
 *
 * THE HOST IS NOT ALWAYS LOOPBACK EITHER. The pairing client dialled `127.0.0.1`, which is an
 * assumption that adbd's pairing server binds on loopback. AOSP's own `adb pair` dials the address
 * the dialog advertises, which is the device's Wi-Fi address. So the host is carried here rather than
 * assumed, and [candidates] offers both in the order that works most often.
 *
 * This object is pure, so the arithmetic about which port to dial is provable without a phone.
 */
object AdbTarget {

    /**
     * The address to dial for pairing, given what the device says about itself.
     *
     * THE WI-FI ADDRESS FIRST, loopback second. AOSP pairs against the advertised address, and the
     * pairing server is reached that way on a real handset. Loopback is kept as a fallback because
     * some builds do bind there, and a single failed attempt costs one TCP connect rather than a
     * pairing session.
     */
    fun pairingCandidates(deviceWifiAddress: String?): List<String> {
        val cleaned = deviceWifiAddress?.trim().orEmpty()
        val loopback = "127.0.0.1"
        return when {
            cleaned.isEmpty() -> listOf(loopback)
            cleaned == loopback -> listOf(loopback)
            else -> listOf(cleaned, loopback)
        }
    }

    /**
     * Whether a port could be a wireless-debugging port at all.
     *
     * 5555 IS NOT REJECTED HERE, because a user may forward it deliberately and refusing would be
     * wrong. What is rejected is a port outside the TCP range, which can only be a mis-parse of the
     * dialog rather than a real number.
     */
    fun isPlausiblePort(port: Int?): Boolean = port != null && port in 1..65535

    /**
     * The connect port to hand to [AdbClient], or null when none is known.
     *
     * THE FALLBACK TO 5555 IS DELIBERATELY GONE. Its own name said "wireless debugging port" and it
     * was never one; falling back to it produced a connect attempt against a port nothing listens on,
     * which then reported a fault about wireless debugging being off. No port is better than a wrong
     * port, because the caller can say "the dialog gave me no connect port" instead of dialling into
     * silence.
     */
    fun connectPort(dialogConnectPort: Int?): Int? = dialogConnectPort?.takeIf { isPlausiblePort(it) }

    /**
     * The number to dial for pairing, checked against what the dialog showed.
     *
     * Kept separate from [connectPort] because they are two different numbers from the same dialog,
     * and collapsing them is the defect this file removes.
     */
    fun pairingPort(dialogPairingPort: Int?): Int? = dialogPairingPort?.takeIf { isPlausiblePort(it) }

    /**
     * One sentence naming host, port and stage, which the old error never did.
     *
     * Issue 389's third defect: the failure string named no host, no port and no stage, so a reader
     * could not tell a TLS handshake failure from a SPAKE2 write failure — which is why the report
     * went looking at the adb binary for a cause that was never there.
     */
    fun failureSentence(host: String, port: Int, stage: String, cause: String?): String =
        "ADB pairing failed at $host:$port during $stage: ${cause ?: "no detail"}. " +
            "The host and port come from the pairing dialog, and the stage says how far the " +
            "handshake got."

    /**
     * Whether a socket-level failure is worth another attempt.
     *
     * THE OLD CODE RETRIED ONLY `ConnectException`, so a transient broken pipe aborted the whole
     * pairing. AOSP's own `adb pair` retries. These are the failures that mean "try again" rather than
     * "you gave me the wrong code": a reset, a broken pipe, a timeout, and a refused connect.
     *
     * A CRYPTOGRAPHIC FAILURE IS NEVER RETRIED, because a wrong pairing code produces the same answer
     * every time and retrying only delays telling the person.
     */
    fun isTransient(cause: Throwable?): Boolean {
        if (cause == null) return false
        if (cause is java.net.ConnectException) return true
        if (cause is java.net.SocketTimeoutException) return true
        val message = cause.message?.lowercase().orEmpty()
        return message.contains("broken pipe") ||
            message.contains("connection reset") ||
            message.contains("connection closed") ||
            message.contains("eof")
    }
}
