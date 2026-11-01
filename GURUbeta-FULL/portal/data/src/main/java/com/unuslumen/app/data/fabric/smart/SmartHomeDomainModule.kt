// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.fabric.smart

import com.unuslumen.app.data.fabric.DefaultFabricRegistry
import com.unuslumen.app.data.fabric.DeviceFabric
import com.unuslumen.app.data.fabric.DeviceTransportRegistry
import com.unuslumen.app.data.fabric.FabricToolDefinitions
import com.unuslumen.app.data.fabric.FabricToolExecutor
import com.unuslumen.app.data.tools.registry.ToolSetRegistration
import com.unuslumen.app.data.tools.registry.permission.DomainToolModule
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * The smart home fabric's wiring. Issue #182, finished here. Issue #187.
 *
 * THE DEFECT THIS FIXES, stated plainly because it is why a television would not
 * turn off. PR #196 merged the fabric: the model, the decisions, the store and the
 * five generic tools all exist on main and all compile. What it did not include was
 * any wiring:
 *
 *  - [FabricToolDefinitions] was never added to the registration aggregator, so
 *    `smartDevices`, `smartControl`, `smartState`, `smartScene` and
 *    `smartCommission` were absent from the ToolRegistry. The model cannot call a
 *    tool that is not registered, so a request to control a telly reached nothing.
 *  - [DeviceFabric] was never constructed, so there was nothing for a tool to talk
 *    to even if it had been registered.
 *  - [DeviceTransportRegistry] was never populated, so a constructed fabric would
 *    have discovered zero devices and reported every transport missing.
 *
 * A merged foundation with no wiring is not a shipped feature, and the honest fix is
 * the wiring rather than a workaround around its absence.
 *
 * WHY THIS IS A DOMAIN MODULE. The aggregator's own documentation says a tool set
 * becomes real by appearing in one list, and a domain owns its definitions, its
 * executor and its Koin bindings in one file. The fabric predates that seam, so it
 * gets a module here rather than three edits to shared files.
 *
 * ONE REGISTRY, POPULATED ONCE. [DefaultFabricRegistry] is the single place that
 * decides which transports are installed, so adding a protocol is one line there and
 * nothing else moves. This module only names it.
 */
object SmartHomeDomainModule : DomainToolModule {

    override val id: String = "smart_home"

    override val registrations: List<ToolSetRegistration> = listOf(FabricToolDefinitions)

    override fun bindings(): Module = module {
        // The registry is a single because a second one would be a second set of
        // transports: a device registered in one would be invisible to the other
        // while the tools reported an empty house.
        single { DefaultFabricRegistry.build(get()) }

        // The DAO comes from the database module, the registry from above. DeviceFabric
        // owns the rules and decides nothing about which protocols exist.
        single { DeviceFabric(dao = get(), registry = get()) }

        factory { FabricToolExecutor(get<DeviceFabric>()) }
    }
}
