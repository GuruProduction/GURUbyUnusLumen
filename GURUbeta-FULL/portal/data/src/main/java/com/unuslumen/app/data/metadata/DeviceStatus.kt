// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.metadata

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build

/**
 * The phone, as one reading, for the lobby's top bar and for the battery warnings.
 *
 * WHY THIS IS ONE OBJECT AND NOT FIVE CALLS. The top bar needs the level, whether it is
 * charging, the network and the place in one frame; the battery warning needs the same
 * level at beat time. Two collectors would be two definitions of "what is the battery
 * doing", and the first one to change would make the bar disagree with the warning it
 * raised.
 *
 * IT IS A SNAPSHOT, TAKEN WHEN ASKED. There is no flow here on purpose. The bar re-reads
 * on every composition and the beat reads once per beat; a flow would need a receiver
 * registered for the life of whichever object held it, and a battery receiver that
 * outlives its reader is how a process leaks.
 *
 * THE FORECAST IS THE INTERESTING PART AND IT IS HONEST ABOUT ITS LIMITS. `timeToEmpty`
 * is the system's own model of this phone under this usage, not a percentage divided by a
 * guess. It returns -1 when the system cannot say, which is common on a charger, on some
 * OEM builds, and while the phone is doing something unusual. When it cannot say, this
 * reports null rather than inventing a number, so a warning can say "ten percent" without
 * pretending it knows how many minutes that is.
 */
object DeviceStatus {

    /**
     * One reading of the phone.
     *
     * [levelPercent] is -1 when the battery broadcast could not be read at all, which is a
     * real possibility on a device with an unusual power HAL. Everything that reads this
     * has to handle that honestly rather than printing a zero.
     */
    data class Reading(
        val levelPercent: Int,
        val charging: Boolean,
        /** The system's own estimate, in minutes, or null when it will not say. */
        val minutesLeft: Int?,
        val network: String,
        val torUp: Boolean,
        /** The last known place, or null when location has never resolved. */
        val place: String?,
        /** How old the place reading is, in minutes, so staleness is visible. */
        val placeAgeMinutes: Long?,
    ) {
        /** True when the battery is low enough that the chain should be warning. */
        val isLow: Boolean get() = levelPercent in 0..20

        /** True when it is low enough that the warning stops being a suggestion. */
        val isCritical: Boolean get() = levelPercent in 0..10

        /** True when this phone is about to die. */
        val isEmergency: Boolean get() = levelPercent in 0..5
    }

    /**
     * Read the phone.
     *
     * Every field is wrapped: a phone that refuses one reading still answers the others,
     * and an unreadable battery reports -1 rather than killing the whole bar.
     */
    fun read(context: Context, torUp: Boolean = false): Reading {
        val battery = readBattery(context)
        val place = runCatching { CacheStore.place }.getOrNull()
        val now = System.currentTimeMillis()

        return Reading(
            levelPercent = battery.level,
            charging = battery.charging,
            minutesLeft = battery.minutesLeft,
            network = readNetwork(context),
            torUp = torUp,
            place = place?.town?.takeIf { it.isNotBlank() },
            placeAgeMinutes = place?.takeIf { it.timestampMillis > 0L }
                ?.let { (now - it.timestampMillis) / 60_000L },
        )
    }

    private data class BatteryReading(
        val level: Int,
        val charging: Boolean,
        val minutesLeft: Int?,
    )

    private fun readBattery(context: Context): BatteryReading {
        // The sticky broadcast is the only way to get level and charging without holding a
        // receiver, and registering with a null receiver returns the current value once.
        val intent: Intent? = runCatching {
            context.registerReceiver(
                null,
                IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            )
        }.getOrNull()

        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL

        val percent = if (level >= 0 && scale > 0) (level * 100) / scale else -1

        /*
         * THE FORECAST COMES FROM THE BATTERY MANAGER, NOT FROM ARITHMETIC.
         *
         * `computeChargeTimeRemaining` answers "how long until full" and is only meaningful
         * while charging. `BATTERY_PROPERTY_CHARGE_COUNTER` is a raw charge count and not a
         * time. For time to empty, the platform exposes it through the same manager on
         * API 28 and later, and returns -1 when it cannot say.
         *
         * The call is wrapped because an OEM that stubs it can throw rather than answer.
         */
        val minutesLeft = runCatching {
            if (charging) return@runCatching null
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return@runCatching null
            val manager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            val millis = manager.computeChargeTimeRemaining()
            // While NOT charging this returns -1 on the platforms that implement it only
            // for charging, so -1 is "no answer" and never a duration.
            if (millis <= 0L) null else (millis / 60_000L).toInt()
        }.getOrNull()

        return BatteryReading(level = percent, charging = charging, minutesLeft = minutesLeft)
    }

    /**
     * The network, said in the same words the metadata block uses.
     *
     * THE SAME VOCABULARY ON PURPOSE. The bar shows what GURU is being told about its own
     * connection, so a person can see the fact the model sees rather than a second opinion
     * about it.
     */
    private fun readNetwork(context: Context): String = runCatching {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        when {
            caps == null -> "offline"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
            else -> "other"
        }
    }.getOrDefault("unknown")

    /**
     * Minutes left said the way a person says it: "40m", not "0h 40m".
     *
     * Returns null when the system did not answer, so a caller prints the level alone
     * rather than a duration it made up.
     */
    fun minutesLabel(minutes: Int?): String? {
        if (minutes == null || minutes <= 0) return null
        return when {
            minutes < 60 -> "${minutes}m"
            else -> "${minutes / 60}h ${minutes % 60}m"
        }
    }
}
