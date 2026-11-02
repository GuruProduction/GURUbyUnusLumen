// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.fabric.tv

/**
 * Turning a discovered television into the adapter that drives it. Issue #187.
 *
 * THE ONE PLACE THAT KNOWS EVERY VENDOR, so no other file has to. Adding a make is a
 * branch here and an implementation of [TvRemote]; nothing else moves, no tool name is
 * added, and the fabric's five generic tools cover the new make the moment it is here.
 *
 * A VENDOR WITH NO ADAPTER IS REFUSED BY NAME. Roku, Samsung and LG have real
 * implementations. Vizio, Philips and Android TV are discovered and reported but not
 * yet drivable, and the refusal says exactly that rather than failing as though the set
 * were absent.
 */
object TvRemoteFactory {

    /**
     * The remotes this build can actually drive.
     *
     * Amazon Fire TV joined this set with issue #243. What the adapter can do is narrower
     * than the others: DIAL app launches work, button presses do not. It is still a real
     * adapter, because "put Netflix on" is the thing a person actually asks for.
     */
    val supportedVendors: Set<TvVendor> = setOf(
        TvVendor.ROKU,
        TvVendor.SAMSUNG,
        TvVendor.LG,
        TvVendor.AMAZON_FIRE_TV,
    )

    /**
     * The adapter for a set, or the reason there is none.
     *
     * [token] and [clientKey] are whatever a previous session stored, because a set that
     * was paired once must not ask again on every press.
     */
    fun remoteFor(
        tv: DiscoveredTv,
        token: String? = null,
        clientKey: String? = null,
    ): Result {
        return when (tv.vendor) {
            TvVendor.ROKU -> Result(RokuRemote(tv.host, tv.port), null)
            TvVendor.SAMSUNG -> Result(SamsungRemote(tv.host, tv.port, token = token), null)
            TvVendor.LG -> Result(LgRemote(tv.host, tv.port, clientKey = clientKey), null)

            // A FIRE TV IS DRIVABLE, JUST NOT BY BUTTONS. Issue #243: the old shape had no
            // Amazon branch at all, so a Fire TV fell through to a sentence claiming the whole
            // device could not be driven. DIAL app launches work today over plain HTTP, which is
            // what this adapter does; its button protocol is a separate pairing channel and the
            // adapter says so rather than refusing everything.
            TvVendor.AMAZON_FIRE_TV -> Result(FireTvRemote(tv.host, tv.port), null)

            TvVendor.VIZIO -> Result(
                null,
                "A Vizio set was found at ${tv.host}, but this build has no Vizio adapter yet. " +
                    "Vizio pairs with a four-digit PIN and then uses an auth token, which needs its " +
                    "own issue.",
            )
            TvVendor.PHILIPS -> Result(
                null,
                "A Philips set was found at ${tv.host}, but this build has no Philips adapter yet. " +
                    "Philips uses a local pairing request with its own API, which needs its own " +
                    "issue.",
            )
            TvVendor.ANDROID_TV -> Result(
                null,
                "An Android TV or Google TV device was found at ${tv.host}, but this build has no " +
                    "adapter for it yet. It uses its own pairing protocol with an on-screen code, " +
                    "which needs its own issue.",
            )
            TvVendor.UNKNOWN -> Result(
                null,
                "The device at ${tv.host} answered on a media port but could not be identified, so " +
                    "no adapter could be chosen for it.",
            )
        }
    }

    data class Result(val remote: TvRemote?, val reason: String?)
}
