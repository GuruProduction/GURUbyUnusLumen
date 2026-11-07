// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.tools.registry.permission

import com.unuslumen.app.data.tools.registry.ToolDefinition
import com.unuslumen.app.data.tools.registry.ToolExecutor
import com.unuslumen.app.data.tools.registry.ToolSetRegistration
import com.unuslumen.app.data.fabric.ir.IrDomainModule
import com.unuslumen.app.data.fabric.net.NetworkDomainModule
import com.unuslumen.app.data.fabric.smart.SmartHomeDomainModule
import com.unuslumen.app.data.fabric.tv.TvDomainModule
import com.unuslumen.app.data.tools.permission.AppsManagementDomainModule
import com.unuslumen.app.data.tools.permission.CallLogContactsDomainModule
import com.unuslumen.app.data.tools.permission.DevicePowerIntrospectDomainModule
import com.unuslumen.app.data.tools.permission.NetworkWifiDomainModule
import com.unuslumen.app.data.tools.permission.SettingsControlDomainModule
import com.unuslumen.app.data.tools.phone.PhoneDomainModule
import com.unuslumen.app.data.tools.senses.BluetoothDomainModule
import com.unuslumen.app.data.tools.senses.CameraAudioDomainModule
import com.unuslumen.app.data.tools.senses.LocationDomainModule
import com.unuslumen.app.data.tools.senses.PrivilegedCaptureDomainModule
import com.unuslumen.app.data.tools.senses.SensorsDomainModule
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Registry — the single place the device-senses domains are collected.
 *
 * WHY THIS FILE IS THE ONLY ONE THAT CHANGES PER PHASE. A domain module is built
 * inside one worktree by one agent, so it can be reviewed and merged on its own.
 * The moment its tools must exist in the running app, something has to name it.
 * Doing that naming here, once, means two agents never edit the same line for the
 * whole batch: they add a file, and the batch owner adds one entry after review.
 *
 * The alternative is the arrangement this replaced: every tool added its own
 * definitions object to a generated list and its own executor to a Koin module, so
 * every merge conflicted, and a tool's description and its behaviour were edited
 * in two unrelated files by two different commits — which is precisely how issues
 * 40 and 44 shipped a tool whose claim did not match its code.
 *
 * KEEP IT BORING. Entries are ordered by domain id, and nothing here does work at
 * boot beyond handing over lists Koin has already built.
 */
object TeamThreeToolDomains {

    /**
     * Every domain that has been merged.
     *
     * A domain is listed once its tools compile and register. It is NOT listed as
     * device-verified, because nothing in this batch has been run on a handset: the
     * granted paths of the camera, the microphone, the sensors, the Bluetooth radio
     * and the location watch each need Steven's own phone, and that is stated per tool
     * in the PR body rather than implied by this list.
     *
     * Ordered by id so a merge never reorders another agent's line.
     */
    val domains: List<DomainToolModule> = listOf(
        // The apps domain first because its id sorts first, and because its names say what
        // actually happens: requestInstallApp and requestUninstallApp, not silent, because a
        // silent install needs a privilege this build does not hold.
        AppsManagementDomainModule,
        BluetoothDomainModule,
        // The personal-data domain: the call log and the address book. Named here
        // because a domain that is not in this list does not exist in the running
        // app at all, which is how the smart-home fabric once shipped tools that
        // answered nothing.
        CallLogContactsDomainModule,
        CameraAudioDomainModule,
        // Battery, the screen, the system log, a service dump and the one destructive
        // operation GURU can reach. Four of the five are privileged, so the domain's rule is
        // that an absent privilege reports what IS observable rather than an empty result.
        DevicePowerIntrospectDomainModule,
        // The smart home fabric's five generic tools (#182) and the IR transport's
        // three (#187. Both live here because the fabric was merged but never
        // named, so its tools did not exist in the running app at all: that is why a
        // request to turn a television off did nothing. A domain is not installed
        // until it appears in this list.
        SmartHomeDomainModule,
        IrDomainModule,
        // Network television control: the transport that actually drives a modern set,
        // because it needs no emitter and speaks each maker's own remote protocol.
        TvDomainModule,
        // Seeing and reaching everything else on the network. Steven's requirement is an
        // inventory of every host on the wifi, not a television list, so this is its own
        // domain with its own names.
        NetworkDomainModule,
        // The connection and Wi-Fi domain: what the link is, what the radio sees, the two
        // toggles the platform mostly refuses, and the multicast lock that mDNS and SSDP
        // discovery cannot work without. Distinct from `network` above, which is host
        // discovery rather than the connection itself.
        NetworkWifiDomainModule,
        LocationDomainModule,
        PhoneDomainModule,
        PrivilegedCaptureDomainModule,
        SensorsDomainModule,
        // Settings and device control: the settings tables, the clock, density, floating
        // overlays, the status bar, the wallpaper, biometric availability, vibration and a
        // wake lock. Every write here reports the value read back afterwards rather than the
        // value sent, because a setting that did not change is a visible lie.
        SettingsControlDomainModule,
    )

    /** Every registration from every domain, in domain order. */
    fun registrations(): List<ToolSetRegistration> = domains.flatMap { it.registrations }

    /**
     * The Koin module that binds every domain's executors.
     *
     * Built fresh each call, which matches the existing `factory { }` bindings:
     * an executor is cheap to construct and holding one across the app's life buys
     * nothing but a longer-lived Context reference.
     */
    fun koinModule(): Module = module {
        for (domain in domains) {
            includes(domain.bindings())
        }
    }

    /**
     * A flat name list for logs and for the tool-count report.
     *
     * Names are taken from the definitions themselves rather than from a second
     * hand-written list, so the report can never claim a tool that does not exist.
     */
    fun toolNames(): List<String> = registrations().flatMap { reg ->
        reg.definitions.map(ToolDefinition::name)
    }

    /**
     * Duplicate detection, run at startup.
     *
     * Two domains declaring the same tool name is not a crash: the registry's map
     * would silently keep whichever registered last, and the tool would behave
     * differently depending on load order. Returning the collisions makes that a
     * visible fault instead of a mystery, and the boot log names both owners.
     */
    fun duplicateToolNames(): Map<String, Int> = toolNames()
        .groupingBy { it }
        .eachCount()
        .filterValues { it > 1 }
}

/**
 * The executor a domain declares for each of its tool sets.
 *
 * Kept as a separate reading of [ToolSetRegistration] because Koin resolves by
 * class and the registrations already carry the class; this exists so a domain
 * file cannot bind one executor and declare another, which would put a tool's
 * description and its behaviour back in two places.
 */
fun DomainToolModule.executorClasses(): List<Class<out ToolExecutor>> =
    registrations.map { it.executorClass().java }
