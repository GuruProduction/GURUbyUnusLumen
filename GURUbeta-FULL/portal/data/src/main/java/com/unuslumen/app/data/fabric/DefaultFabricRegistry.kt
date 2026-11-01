// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.fabric

import android.content.Context
import com.unuslumen.app.data.fabric.ir.IrTransport
import com.unuslumen.app.data.fabric.ir.IrWiring

/**
 * The registry every transport is installed into, built from a Context. Issue #187.
 *
 * WHY THIS IS A SEPARATE OBJECT. [DeviceFabric] takes a registry and knows nothing
 * about which protocols exist, which is the whole point of the registration point
 * #182 built. Something still has to decide what is installed, and that decision is
 * here rather than inside the fabric or inside a transport.
 *
 * ADDING A PROTOCOL IS ONE ENTRY. Matter, Home Assistant, MQTT, Zigbee/Z-Wave and
 * Hue/Sonos are their own issues, and each becomes a line in this list. No shared
 * file moves, no tool name is added, and the five generic fabric tools cover every
 * one of them.
 *
 * THE SAME IR WIRING IS SHARED, so a device imported through the IR tools is the
 * same device the transport presses buttons on. Two wirings would mean a library the
 * tools can list and a transport that cannot find it.
 */
object DefaultFabricRegistry {

    /**
     * Build a populated registry for this context.
     *
     * A fresh registry each call rather than a process-wide singleton, because a
     * singleton captured at construction is exactly how a stale transmitter list
     * survives a settings change and reports a blaster that was removed an hour ago.
     */
    fun build(context: Context): DeviceTransportRegistry {
        val registry = DeviceTransportRegistry()
        val ir = IrWiring.forContext(context)
        registry.register(IrTransport(ir.transmitters, ir.store))
        return registry
    }
}
