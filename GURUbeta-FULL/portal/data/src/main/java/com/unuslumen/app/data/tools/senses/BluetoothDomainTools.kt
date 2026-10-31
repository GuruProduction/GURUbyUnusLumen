// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.tools.senses

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import com.unuslumen.app.data.tools.registry.ToolDefinition
import com.unuslumen.app.data.tools.registry.ToolExecutionResult
import com.unuslumen.app.data.tools.registry.ToolExecutor
import com.unuslumen.app.data.tools.registry.ToolParameter
import com.unuslumen.app.data.tools.registry.ToolParameterType
import com.unuslumen.app.data.tools.registry.ToolResultData
import com.unuslumen.app.data.tools.registry.ToolResultExtractor
import com.unuslumen.app.data.tools.registry.ToolSetRegistration
import com.unuslumen.app.data.tools.registry.permission.DomainToolModule
import com.unuslumen.app.data.tools.registry.permission.PermissionGrants
import com.unuslumen.app.data.tools.registry.permission.TeamThreePermissions
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.koin.core.module.Module
import org.koin.dsl.module
import java.util.UUID
import kotlin.reflect.KClass

/**
 * The Bluetooth domain: a single LE scan with RSSI history and a UUID filter, pairing
 * and unpairing, and GATT advertising.
 *
 * THE THREE EXISTING PATHS THIS DOMAIN DOES NOT REPLACE. bluetoothScan and
 * bluetoothList already exist and are registered. bluetoothScan runs through
 * BluetoothScanManager and got its grant check fixed in place there and in
 * EnvironmentToolExecutor; bluetoothList runs through SmartHomeToolExecutor and got
 * the same treatment. Both had the forbidden pattern — a SecurityException caught into
 * a success payload with an error string — which is gone.
 *
 * WHY bleScan IS A NEW TOOL AND NOT A CHANGE TO bluetoothScan. bluetoothScan is a
 * classic discovery sweep: it returns whatever answered, once, with one RSSI each.
 * bleScan answers a different question — "how has that device's signal moved" — and it
 * needs the LE scanner, which is a different API with a different filter model. Folding
 * RSSI history into bluetoothScan would change the shape of a result other parts of the
 * app already read.
 *
 * EVERY TOOL HERE NEEDS A REAL HANDSET AND REAL NEARBY DEVICES TO PROVE.
 */

@Serializable
data class RssiSample(val rssiDbm: Int, val elapsedMillis: Long)

@Serializable
data class BleDeviceResult(
    val name: String? = null,
    val mac: String,
    val rssiDbm: Int? = null,
    /** Every reading for this device during the scan, oldest first. */
    val rssiHistory: List<RssiSample> = emptyList(),
    /** Mean over the history, which is steadier than any single reading. */
    val meanRssiDbm: Double? = null,
    val serviceUuids: List<String> = emptyList(),
    val bondState: String = "unknown",
    val isPaired: Boolean = false,
) : ToolResultData

@Serializable
data class BleScanResult(
    val success: Boolean,
    val devices: List<BleDeviceResult> = emptyList(),
    val deviceCount: Int = 0,
    /** The filter actually applied, echoed so a caller can see what it asked for. */
    val serviceUuidFilter: String? = null,
    val scanMillis: Int = 0,
    val note: String? = null,
    val error: String? = null,
) : ToolResultData

@Serializable
data class BluetoothPairResult(
    val success: Boolean,
    val action: String,
    val mac: String,
    /** The bond state read back after the attempt, as Android reports it. */
    val bondState: String = "unknown",
    val confirmed: Boolean = false,
    /** True when Android needs the person to accept a pairing prompt. */
    val needsUserGesture: Boolean = false,
    val note: String? = null,
    val error: String? = null,
) : ToolResultData

@Serializable
data class BluetoothAdvertiseResult(
    val success: Boolean,
    val advertising: Boolean = false,
    val serviceUuid: String? = null,
    /** True only when the platform's onStartSuccess callback fired. */
    val confirmed: Boolean = false,
    val durationMillis: Int = 0,
    val note: String? = null,
    val error: String? = null,
) : ToolResultData

object BluetoothDomainDefinitions : ToolSetRegistration {
    const val BLE_SCAN = "bleScan"
    const val PAIR = "bluetoothPair"
    const val UNPAIR = "bluetoothUnpair"
    const val ADVERTISE = "bluetoothAdvertise"

    override val definitions = listOf(
        ToolDefinition(
            name = BLE_SCAN,
            description = "Scan for Bluetooth Low Energy devices for a bounded window, recording the " +
                "signal strength of every advertisement so each device carries a real RSSI history and " +
                "its mean rather than one arbitrary reading. An optional service UUID keeps only " +
                "devices advertising that service, which is how a specific sensor or beacon is found " +
                "among a room of devices. Needs the Nearby devices permission.",
            category = "bluetooth",
            parameters = listOf(
                ToolParameter(
                    "serviceUuid", ToolParameterType.String, false,
                    "Keep only devices advertising this service UUID, e.g. " +
                        "0000180d-0000-1000-8000-00805f9b34fb. Omit to see every device."
                ),
                ToolParameter(
                    "scanMillis", ToolParameterType.Integer, false,
                    "How long to scan, 1000 to 30000 milliseconds. Default 8000."
                ),
            ),
            permissions = listOf(TeamThreePermissions.BLUETOOTH_SCAN.permission),
        ),
        ToolDefinition(
            name = PAIR,
            description = "Start pairing with a Bluetooth device by MAC address and report the bond " +
                "state Android reaches. Pairing is asynchronous and can require the person to accept a " +
                "prompt on the phone, so a result that is still bonding says so rather than claiming " +
                "success. Needs the Nearby devices permission.",
            category = "bluetooth",
            parameters = listOf(
                ToolParameter(
                    "mac", ToolParameterType.String, true,
                    "The device's MAC address, e.g. AA:BB:CC:DD:EE:FF."
                ),
                ToolParameter(
                    "waitSeconds", ToolParameterType.Integer, false,
                    "How long to wait for the bond to complete, 1 to 30. Default 10."
                ),
            ),
            permissions = listOf(TeamThreePermissions.BLUETOOTH_CONNECT.permission),
        ),
        ToolDefinition(
            name = UNPAIR,
            description = "Remove the pairing with a Bluetooth device by MAC address and report the " +
                "bond state read back afterwards. This is the one Bluetooth action that cannot be " +
                "undone without pairing again, so it says plainly whether the bond was really removed " +
                "rather than assuming the call worked. Needs the Nearby devices permission.",
            category = "bluetooth",
            parameters = listOf(
                ToolParameter(
                    "mac", ToolParameterType.String, true,
                    "The device's MAC address, e.g. AA:BB:CC:DD:EE:FF."
                ),
            ),
            permissions = listOf(TeamThreePermissions.BLUETOOTH_CONNECT.permission),
        ),
        ToolDefinition(
            name = ADVERTISE,
            description = "Advertise a GATT service over Bluetooth Low Energy for a bounded window so " +
                "another device can discover this phone. Reports whether the platform's own " +
                "advertising callback confirmed the start, because a request that was accepted and " +
                "then silently refused by the controller looks identical from the outside. Needs the " +
                "Nearby devices permission and LE advertising hardware.",
            category = "bluetooth",
            parameters = listOf(
                ToolParameter(
                    "serviceUuid", ToolParameterType.String, false,
                    "The service UUID to advertise. Defaults to GURU's own service UUID."
                ),
                ToolParameter(
                    "durationSeconds", ToolParameterType.Integer, false,
                    "How long to advertise, 1 to 60 seconds. Default 15."
                ),
            ),
            permissions = listOf(TeamThreePermissions.BLUETOOTH_ADVERTISE.permission),
        ),
    )

    override fun executorClass(): KClass<out ToolExecutor> = BluetoothDomainExecutor::class
    override fun extractorClass(): KClass<out ToolResultExtractor>? = null
}

/**
 * The executor behind bleScan, bluetoothPair, bluetoothUnpair and bluetoothAdvertise.
 *
 * Every tool asks the device before the grant where Bluetooth itself can be missing or
 * switched off, because "this phone has no Bluetooth", "Bluetooth is off", and "I am
 * not allowed to use it" are three different answers and only one of them is fixable
 * in Settings > Apps.
 */
class BluetoothDomainExecutor(private val context: Context) : ToolExecutor {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val adapter: BluetoothAdapter?
        get() = runCatching {
            (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        }.getOrNull()

    override suspend fun execute(toolName: String, args: Map<String, Any?>): ToolExecutionResult = when (toolName) {
        BluetoothDomainDefinitions.BLE_SCAN -> bleScan(args)
        BluetoothDomainDefinitions.PAIR -> pair(args)
        BluetoothDomainDefinitions.UNPAIR -> unpair(args)
        BluetoothDomainDefinitions.ADVERTISE -> advertise(args)
        else -> ToolExecutionResult.error("Unknown tool: $toolName")
    }

    /**
     * A shared pre-flight for the three state checks every Bluetooth tool needs.
     *
     * Returns a refusal when the device cannot use Bluetooth at all, or null when it
     * can. The adapter check comes first because a phone with no radio can never be
     * helped by a grant, and its absence is the honest answer.
     */
    private fun bluetoothReady(why: String): ToolExecutionResult? {
        if (!PermissionGrants.hasBluetooth(context)) {
            return PermissionGrants.guardHardware(present = false, label = "Bluetooth radio", why = why)
        }
        val bluetooth = adapter
            ?: return PermissionGrants.guardHardware(
                present = false,
                label = "Bluetooth radio",
                why = why,
            )
        if (!runCatching { bluetooth.isEnabled }.getOrDefault(false)) {
            val r = AdapterOffResult(
                message = "Bluetooth is switched off on this phone, so this cannot proceed until it " +
                    "is turned on; the permission is separate from the switch and turning Bluetooth " +
                    "on needs no grant.",
            )
            return emitPayload(json, json.encodeToString(AdapterOffResult.serializer(), r), r, r.success, r.error)
        }
        return null
    }

    // ------------------------------------------------------------------
    // LE scan with RSSI history
    // ------------------------------------------------------------------

    @SuppressLint("MissingPermission")
    private suspend fun bleScan(args: Map<String, Any?>): ToolExecutionResult {
        // Permissions first: an LE scan without them throws underneath, and the device
        // checks below are only truthful once the grant is known.
        PermissionGrants.guard(context, TeamThreePermissions.BLUETOOTH_SCAN)?.let { return it }
        bluetoothReady("GURU can find nearby Bluetooth devices")?.let { return it }

        val bluetooth = adapter
            ?: return scanFailure("This device has no Bluetooth adapter, so nothing can be scanned.")

        val scanner = runCatching { bluetooth.bluetoothLeScanner }.getOrNull()
            ?: return scanFailure(
                "This phone's adapter exposes no LE scanner, so only classic discovery is available; " +
                    "use bluetoothScan for that."
            )

        val rawUuid = (args["serviceUuid"] as? String)?.trim()?.takeIf { it.isNotBlank() }
        val parsedUuid = rawUuid?.let {
            runCatching { UUID.fromString(it) }.getOrNull()
                ?: return scanFailure("'$it' is not a valid service UUID. Use the full 128-bit form.")
        }
        val scanMillis = ((args["scanMillis"] as? Number)?.toInt() ?: 8_000).coerceIn(1_000, 30_000)

        val history = linkedMapOf<String, MutableList<RssiSample>>()
        val meta = hashMapOf<String, ScanResult>()
        val startedAt = System.currentTimeMillis()
        val finished = CompletableDeferred<Unit>()

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val mac = runCatching { result.device.address }.getOrNull() ?: return
                val bucket = history.getOrPut(mac) { mutableListOf() }
                bucket.add(RssiSample(result.rssi, System.currentTimeMillis() - startedAt))
                // The last result is kept for the name and the advertised UUIDs, which
                // are not on the earlier callbacks for a device whose scan record grew.
                meta[mac] = result
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach { onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, it) }
            }

            override fun onScanFailed(errorCode: Int) {
                if (!finished.isCompleted) finished.complete(Unit)
            }
        }

        val filters = if (parsedUuid != null) {
            listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(parsedUuid)).build())
        } else {
            emptyList()
        }
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .build()

        val scanError: String? = try {
            scanner.startScan(filters, settings, callback)
            withTimeoutOrNull(scanMillis.toLong()) { finished.await() }
            null
        } catch (e: SecurityException) {
            return PermissionGrants.refusal(PermissionGrants.check(context, TeamThreePermissions.BLUETOOTH_SCAN))
        } catch (e: Exception) {
            "The scan failed: ${e.message}"
        } finally {
            runCatching { scanner.stopScan(callback) }
        }

        val devices = history.entries.map { (mac, samples) ->
            val last = meta[mac]
            val record = runCatching { last?.scanRecord }.getOrNull()
            val uuids = runCatching {
                (record?.serviceUuids?.map { it.uuid.toString() } ?: emptyList())
            }.getOrDefault(emptyList())

            val bondState = runCatching { last?.device?.bondState }.getOrNull()
            BleDeviceResult(
                name = runCatching { last?.device?.name }.getOrNull()?.takeIf { it.isNotBlank() },
                mac = mac,
                rssiDbm = samples.lastOrNull()?.rssiDbm,
                rssiHistory = samples,
                meanRssiDbm = if (samples.isEmpty()) null else samples.map { it.rssiDbm }.average(),
                serviceUuids = uuids,
                bondState = bondStateName(bondState),
                isPaired = bondState == BluetoothDevice.BOND_BONDED,
            )
        }.sortedWith(compareByDescending<BleDeviceResult> { it.isPaired }.thenByDescending { it.meanRssiDbm ?: -200.0 })

        val r = BleScanResult(
            success = devices.isNotEmpty(),
            devices = devices,
            deviceCount = devices.size,
            serviceUuidFilter = rawUuid,
            scanMillis = scanMillis,
            note = if (devices.isEmpty()) {
                val filterPhrase = if (rawUuid != null) " advertising $rawUuid" else ""
                "No Bluetooth LE device answered in $scanMillis milliseconds$filterPhrase. An empty " +
                    "room and a filtered-out room look the same here, so remove the filter before " +
                    "concluding anything about the devices around you."
            } else {
                "Every device carries one RSSI sample per advertisement it sent during the scan, and " +
                    "the mean is steadier than the last reading. Classic-only devices do not appear in " +
                    "an LE scan."
            },
            error = scanError,
        )
        return emitPayload(json, json.encodeToString(BleScanResult.serializer(), r), r, r.success, r.error)
    }

    private fun scanFailure(message: String): ToolExecutionResult {
        val r = BleScanResult(success = false, error = message)
        return emitPayload(json, json.encodeToString(BleScanResult.serializer(), r), r, r.success, r.error)
    }

    private fun bondStateName(state: Int?): String = when (state) {
        BluetoothDevice.BOND_BONDED -> "bonded"
        BluetoothDevice.BOND_BONDING -> "bonding"
        BluetoothDevice.BOND_NONE -> "none"
        else -> "unknown"
    }

    // ------------------------------------------------------------------
    // Pair and unpair
    // ------------------------------------------------------------------

    @SuppressLint("MissingPermission")
    private suspend fun pair(args: Map<String, Any?>): ToolExecutionResult {
        PermissionGrants.guard(context, TeamThreePermissions.BLUETOOTH_CONNECT)?.let { return it }
        bluetoothReady("GURU can pair with a Bluetooth device")?.let { return it }

        val mac = (args["mac"] as? String)?.trim()
            ?: return pairFailure("action=pair", "", "This call needs the device's 'mac' address.")
        if (!BluetoothAdapter.checkBluetoothAddress(mac)) {
            return pairFailure("action=pair", mac, "'$mac' is not a valid Bluetooth MAC address.")
        }

        val bluetooth = adapter ?: return pairFailure("pair", mac, "This device has no Bluetooth adapter.")

        val device = runCatching { bluetooth.getRemoteDevice(mac) }.getOrNull()
            ?: return pairFailure("pair", mac, "Android cannot resolve $mac to a device.")

        val alreadyBonded = runCatching { device.bondState }.getOrNull() == BluetoothDevice.BOND_BONDED
        if (alreadyBonded) {
            val r = BluetoothPairResult(
                success = true,
                action = "pair",
                mac = mac,
                bondState = "bonded",
                confirmed = true,
                note = "This device is already paired, so no new pairing was started.",
            )
            return emitPayload(json, json.encodeToString(BluetoothPairResult.serializer(), r), r, r.success, r.error)
        }

        val waitSeconds = ((args["waitSeconds"] as? Number)?.toInt() ?: 10).coerceIn(1, 30)

        val started = try {
            device.createBond()
        } catch (e: SecurityException) {
            return PermissionGrants.refusal(PermissionGrants.check(context, TeamThreePermissions.BLUETOOTH_CONNECT))
        } catch (e: Exception) {
            return pairFailure("pair", mac, "Bonding could not be started: ${e.message}")
        }

        if (!started) {
            return pairFailure("pair", mac, "Android refused to start bonding with $mac.")
        }

        // Bonding is asynchronous and, for most pairings, needs the person to accept a
        // prompt on the phone. Polling the state is the only read that tells the truth
        // about where it got to.
        val deadline = System.currentTimeMillis() + waitSeconds * 1000L
        var state = runCatching { device.bondState }.getOrNull()
        while (System.currentTimeMillis() < deadline && state == BluetoothDevice.BOND_BONDING) {
            delay(250)
            state = runCatching { device.bondState }.getOrNull()
        }

        val bonded = state == BluetoothDevice.BOND_BONDED
        val stillBonding = state == BluetoothDevice.BOND_BONDING
        val r = BluetoothPairResult(
            success = bonded,
            action = "pair",
            mac = mac,
            bondState = bondStateName(state),
            confirmed = bonded,
            needsUserGesture = stillBonding,
            note = when {
                bonded -> "The bond completed; the state reads bonded."
                stillBonding -> "Bonding is still in progress after $waitSeconds seconds. Android " +
                    "usually needs the person to accept a pairing prompt on the phone, which this " +
                    "tool cannot press on their behalf."
                else -> "Bonding started but the state reads ${bondStateName(state)}. A rejected " +
                    "prompt or a device out of range produces this."
            },
            error = if (bonded) null else "Pairing did not complete.",
        )
        return emitPayload(json, json.encodeToString(BluetoothPairResult.serializer(), r), r, r.success, r.error)
    }

    @SuppressLint("MissingPermission")
    private suspend fun unpair(args: Map<String, Any?>): ToolExecutionResult {
        PermissionGrants.guard(context, TeamThreePermissions.BLUETOOTH_CONNECT)?.let { return it }
        bluetoothReady("GURU can remove a Bluetooth pairing")?.let { return it }

        val mac = (args["mac"] as? String)?.trim()
            ?: return pairFailure("unpair", "", "This call needs the device's 'mac' address.")
        if (!BluetoothAdapter.checkBluetoothAddress(mac)) {
            return pairFailure("unpair", mac, "'$mac' is not a valid Bluetooth MAC address.")
        }

        val bluetooth = adapter ?: return pairFailure("unpair", mac, "This device has no Bluetooth adapter.")
        val device = runCatching { bluetooth.getRemoteDevice(mac) }.getOrNull()
            ?: return pairFailure("unpair", mac, "Android cannot resolve $mac to a device.")

        val wasBonded = runCatching { device.bondState }.getOrNull() == BluetoothDevice.BOND_BONDED
        if (!wasBonded) {
            val r = BluetoothPairResult(
                success = true,
                action = "unpair",
                mac = mac,
                bondState = bondStateName(runCatching { device.bondState }.getOrNull()),
                confirmed = true,
                note = "This device is not paired, so there was no pairing to remove.",
            )
            return emitPayload(json, json.encodeToString(BluetoothPairResult.serializer(), r), r, r.success, r.error)
        }

        // Removing a bond has no public API: the documented route on Android is a
        // reflective call into the hidden removeBond(), which is what every app does.
        // A refusal here is reported as the platform's answer rather than as a success.
        val removed = try {
            val method = device.javaClass.getMethod("removeBond")
            method.invoke(device) as? Boolean ?: false
        } catch (e: SecurityException) {
            return PermissionGrants.refusal(PermissionGrants.check(context, TeamThreePermissions.BLUETOOTH_CONNECT))
        } catch (e: Exception) {
            false
        }

        // The state change is asynchronous too; give it a moment before reading it.
        var state = runCatching { device.bondState }.getOrNull()
        val deadline = System.currentTimeMillis() + 5_000L
        while (System.currentTimeMillis() < deadline && state == BluetoothDevice.BOND_BONDED) {
            delay(200)
            state = runCatching { device.bondState }.getOrNull()
        }

        val unpaired = state != BluetoothDevice.BOND_BONDED
        val r = BluetoothPairResult(
            success = unpaired,
            action = "unpair",
            mac = mac,
            bondState = bondStateName(state),
            confirmed = unpaired,
            note = if (unpaired) {
                "The bond was removed; the state reads ${bondStateName(state)}."
            } else {
                "Android accepted the unpair request ($removed) but the device still reads as bonded, " +
                    "which happens when another service holds the connection."
            },
            error = if (unpaired) null else "The pairing was not removed.",
        )
        return emitPayload(json, json.encodeToString(BluetoothPairResult.serializer(), r), r, r.success, r.error)
    }

    private fun pairFailure(action: String, mac: String, message: String): ToolExecutionResult {
        val r = BluetoothPairResult(success = false, action = action, mac = mac, error = message)
        return emitPayload(json, json.encodeToString(BluetoothPairResult.serializer(), r), r, r.success, r.error)
    }

    // ------------------------------------------------------------------
    // Advertising
    // ------------------------------------------------------------------

    /** GURU's own service UUID, so a peer can look for this app by name. */
    private val guruServiceUuid: UUID = UUID.fromString("6c4f8a52-8f0e-4a71-9d0c-2f3b5e7a1c90")

    @SuppressLint("MissingPermission")
    private suspend fun advertise(args: Map<String, Any?>): ToolExecutionResult {
        PermissionGrants.guard(context, TeamThreePermissions.BLUETOOTH_ADVERTISE)?.let { return it }
        bluetoothReady("GURU can announce itself to nearby Bluetooth devices")?.let { return it }

        val bluetooth = adapter
            ?: return advertiseFailure("This device has no Bluetooth adapter, so it cannot advertise.")

        if (!runCatching { bluetooth.isMultipleAdvertisementSupported }.getOrDefault(false)) {
            return PermissionGrants.guardHardware(
                present = false,
                label = "LE advertising support",
                why = "GURU can announce itself to nearby Bluetooth devices",
            ) ?: advertiseFailure("This phone's controller does not support LE advertising.")
        }

        val advertiser = runCatching { bluetooth.bluetoothLeAdvertiser }.getOrNull()
            ?: return advertiseFailure("The adapter exposes no LE advertiser.")

        val rawUuid = (args["serviceUuid"] as? String)?.trim()?.takeIf { it.isNotBlank() }
        val uuid = if (rawUuid != null) {
            runCatching { UUID.fromString(rawUuid) }.getOrNull()
                ?: return advertiseFailure("'$rawUuid' is not a valid service UUID. Use the full 128-bit form.")
        } else {
            guruServiceUuid
        }

        val durationSeconds = ((args["durationSeconds"] as? Number)?.toInt() ?: 15).coerceIn(1, 60)

        val started = CompletableDeferred<Boolean>()
        val callback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
                if (!started.isCompleted) started.complete(true)
            }

            override fun onStartFailure(errorCode: Int) {
                if (!started.isCompleted) started.complete(false)
            }
        }

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(false)
            .setTimeout(0)
            .build()
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(uuid))
            .build()

        val confirmed = try {
            advertiser.startAdvertising(settings, data, callback)
            withTimeoutOrNull(3_000L) { started.await() } == true
        } catch (e: SecurityException) {
            return PermissionGrants.refusal(PermissionGrants.check(context, TeamThreePermissions.BLUETOOTH_ADVERTISE))
        } catch (e: Exception) {
            runCatching { advertiser.stopAdvertising(callback) }
            return advertiseFailure("Advertising could not start: ${e.message}")
        }

        if (!confirmed) {
            // Stop anyway: a failed start can still leave a partially registered set.
            runCatching { advertiser.stopAdvertising(callback) }
            val r = BluetoothAdvertiseResult(
                success = false,
                advertising = false,
                serviceUuid = uuid.toString(),
                confirmed = false,
                durationMillis = 0,
                note = "The advertiser did not confirm the start within three seconds. Another app " +
                    "already advertising, an oversized payload, or a controller that is busy all " +
                    "produce this, and none of them is a permission problem.",
                error = "The platform did not confirm the advertisement started.",
            )
            return emitPayload(json, json.encodeToString(BluetoothAdvertiseResult.serializer(), r), r, r.success, r.error)
        }

        // Hold the advertisement for the requested window, then stop it. A tool that
        // returned while still advertising would leave the radio on after the call
        // ended, which drains the battery for a result nobody is watching.
        try {
            delay(durationSeconds * 1000L)
        } finally {
            runCatching { advertiser.stopAdvertising(callback) }
        }

        val r = BluetoothAdvertiseResult(
            success = true,
            advertising = false,
            serviceUuid = uuid.toString(),
            confirmed = true,
            durationMillis = durationSeconds * 1000,
            note = "Advertised $uuid for $durationSeconds seconds and then stopped. The platform " +
                "confirmed the start; whether a second device saw it can only be answered by the " +
                "second device.",
        )
        return emitPayload(json, json.encodeToString(BluetoothAdvertiseResult.serializer(), r), r, r.success, r.error)
    }

    private fun advertiseFailure(message: String): ToolExecutionResult {
        val r = BluetoothAdvertiseResult(success = false, error = message)
        return emitPayload(json, json.encodeToString(BluetoothAdvertiseResult.serializer(), r), r, r.success, r.error)
    }
}

/**
 * A Bluetooth tool's answer when the adapter exists but is switched off.
 *
 * A dedicated shape rather than a serializable string so the model reads a sentence it
 * can repeat, and so "Bluetooth is off" can never be confused with a grant refusal or
 * an empty scan result.
 */
@Serializable
data class AdapterOffResult(
    val success: Boolean = false,
    val bluetoothEnabled: Boolean = false,
    val message: String,
) : ToolResultData

/**
 * The Bluetooth domain's wiring.
 */
object BluetoothDomainModule : DomainToolModule {
    override val id: String = "bluetooth"

    override val registrations: List<ToolSetRegistration> = listOf(BluetoothDomainDefinitions)

    override fun bindings(): Module = module {
        factory { BluetoothDomainExecutor(get()) }
    }
}
