// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.preferences.domain.model

/**
 * The egress posture this install runs in.
 *
 * A private or loopback host is DIRECT under every posture, because Tor cannot reach
 * private space. That is arithmetic, and it is the reason the classifier exists: sending a
 * LAN address to the proxy is a guaranteed failure rather than a privacy win.
 *
 * What is left is the question arithmetic cannot answer: may a PUBLIC request travel
 * unproxied? That trades privacy for reach, so only the owner decides it.
 */
enum class EgressMode(val id: String, val label: String) {

    /** Classify every host, ride Tor for public, fail closed when there is no circuit. */
    AUTO(
        id = "auto",
        label = "Automatic",
    ),

    /** The owner's override: a public request may go direct, and says that it did. */
    CLEARNET(
        id = "clearnet",
        label = "Clearnet override",
    ),

    /** As AUTO, and the clearnet override is refused so a later command cannot widen it. */
    TOR_ONLY(
        id = "tor-only",
        label = "Tor only",
    );

    /** One plain sentence describing what a public request will do in this posture. */
    val publicBehaviour: String
        get() = when (this) {
            AUTO ->
                "A public request rides Tor, and fails rather than falling back to a direct " +
                    "connection when Tor is not running."
            CLEARNET ->
                "A public request travels direct on this device's own connection. Every result " +
                    "that went this way says so."
            TOR_ONLY ->
                "A public request rides Tor and fails rather than going direct. The clearnet " +
                    "override is refused while this posture is active."
        }

    companion object {
        /** The posture a fresh install runs in, and the one /tor off and /clearnet off return to. */
        val DEFAULT: EgressMode = AUTO

        /**
         * Read a stored or typed posture, falling back to [DEFAULT].
         *
         * An unknown value falls back rather than throwing, because the safe direction for a
         * corrupt setting is the restrictive posture and a crash on boot is the opposite. It
         * also accepts the spellings a person actually types.
         */
        fun parse(raw: String?): EgressMode {
            val cleaned = raw?.trim()?.lowercase()?.replace("_", "-").orEmpty()
            if (cleaned.isEmpty()) return DEFAULT
            entries.firstOrNull { it.id == cleaned }?.let { return it }
            return when (cleaned) {
                "on", "tor", "toronly", "lock", "locked" -> TOR_ONLY
                "clearnet", "clear", "direct" -> CLEARNET
                "automatic", "default", "normal", "off" -> AUTO
                else -> DEFAULT
            }
        }
    }
}
