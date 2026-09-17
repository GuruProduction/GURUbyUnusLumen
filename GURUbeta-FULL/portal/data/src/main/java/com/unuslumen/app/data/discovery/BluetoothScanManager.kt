package com.unuslumen.app.data.discovery

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import org.koin.core.annotation.Single

@Single
class BluetoothScanManager(
    private val context: Context
) {
    companion object {
        private const val TAG = "BluetoothScanManager"
        private const val DISCOVERY_TIMEOUT_MS = 15000L
    }

    @Serializable
    data class DiscoveredBluetoothDevice(
        val name: String,
        val mac: String,
        val deviceType: String,
        val majorDeviceClass: String,
        val minorDeviceClass: String,
        val bondState: String,
        val isPaired: Boolean,
        val rssi: Int?,
        val timestamp: Long
    )

    @Serializable
    data class BluetoothScanResult(
        val success: Boolean,
        val devices: List<DiscoveredBluetoothDevice>,
        val error: String?
    )

    @SuppressLint("MissingPermission")
    suspend fun scan(): BluetoothScanResult {
        val bluetoothManager = context.getSystemService(BluetoothManager::class.java)
            ?: return BluetoothScanResult(false, emptyList(), "BluetoothManager not available")

        val adapter = bluetoothManager.adapter
            ?: return BluetoothScanResult(false, emptyList(), "Bluetooth adapter not available")

        if (!adapter.isEnabled) {
            return BluetoothScanResult(false, emptyList(), "Bluetooth is not enabled. Enable Bluetooth first.")
        }

        if (!hasRequiredPermissions()) {
            return BluetoothScanResult(false, emptyList(), "Missing Bluetooth permissions.")
        }

        val discoveredDevices = mutableMapOf<String, BluetoothDevice>()
        val rssiMap = mutableMapOf<String, Int>()

        val receiver = object : BroadcastReceiver() {
            @SuppressLint("MissingPermission")
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    BluetoothDevice.ACTION_FOUND -> {
                        val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                        }
                        val rssi: Int = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE).toInt()
                        if (device != null) {
                            discoveredDevices[device.address] = device
                            rssiMap[device.address] = rssi
                            Log.d(TAG, "Found: ${device.name ?: "Unknown"} (${device.address}) RSSI: $rssi")
                        }
                    }
                    BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                        Log.d(TAG, "Discovery finished. Found ${discoveredDevices.size} devices.")
                    }
                }
            }
        }

        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }

        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)

        try {
            if (adapter.isDiscovering) {
                adapter.cancelDiscovery()
                delay(200)
            }

            val started = adapter.startDiscovery()
            if (!started) {
                return BluetoothScanResult(false, emptyList(), "Failed to start Bluetooth discovery.")
            }

            var waited = 0
            while (waited < DISCOVERY_TIMEOUT_MS) {
                delay(500)
                waited += 500
                if (!adapter.isDiscovering && waited > 1000) {
                    break
                }
            }

            if (adapter.isDiscovering) {
                adapter.cancelDiscovery()
            }

            val timestamp = System.currentTimeMillis()

            val devices = discoveredDevices.values.map { device ->
                val btClass = device.bluetoothClass
                val majorClass = btClass?.majorDeviceClass?.let { majorClassToString(it) } ?: "Unknown"
                val minorClass = btClass?.let { minorClassToString(it) } ?: "Unknown"
                val deviceType = btClass?.let { deviceTypeFromClass(it) } ?: "Unknown"

                DiscoveredBluetoothDevice(
                    name = try { device.name ?: "Unknown" } catch (e: SecurityException) { "Unknown" },
                    mac = device.address,
                    deviceType = deviceType,
                    majorDeviceClass = majorClass,
                    minorDeviceClass = minorClass,
                    bondState = bondStateToString(device.bondState),
                    isPaired = device.bondState == BluetoothDevice.BOND_BONDED,
                    rssi = rssiMap[device.address],
                    timestamp = timestamp
                )
            }.sortedWith(compareByDescending<DiscoveredBluetoothDevice> { it.isPaired }.thenByDescending { it.rssi ?: -200 })

            return BluetoothScanResult(true, devices, null)
        } catch (e: SecurityException) {
            return BluetoothScanResult(false, emptyList(), "Bluetooth permission denied: ${e.message}")
        } catch (e: Exception) {
            return BluetoothScanResult(false, emptyList(), "Bluetooth scan failed: ${e.message}")
        } finally {
            try { context.unregisterReceiver(receiver) } catch (e: Exception) { Log.w(TAG, "Unregister failed: ${e.message}") }
        }
    }

    private fun hasRequiredPermissions(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                   ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            return ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH) == PackageManager.PERMISSION_GRANTED &&
                   ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_ADMIN) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun majorClassToString(majorClass: Int): String = when (majorClass) {
        BluetoothClass.Device.Major.COMPUTER -> "Computer"
        BluetoothClass.Device.Major.PHONE -> "Phone"
        BluetoothClass.Device.Major.NETWORKING -> "Networking"
        BluetoothClass.Device.Major.AUDIO_VIDEO -> "Audio/Video"
        BluetoothClass.Device.Major.PERIPHERAL -> "Peripheral"
        BluetoothClass.Device.Major.IMAGING -> "Imaging"
        BluetoothClass.Device.Major.WEARABLE -> "Wearable"
        BluetoothClass.Device.Major.TOY -> "Toy"
        BluetoothClass.Device.Major.HEALTH -> "Health"
        BluetoothClass.Device.Major.UNCATEGORIZED -> "Uncategorized"
        else -> "Unknown (0x${majorClass.toString(16)})"
    }

    private fun minorClassToString(btClass: BluetoothClass): String {
        val minor = btClass.deviceClass
        return when (btClass.majorDeviceClass) {
            BluetoothClass.Device.Major.COMPUTER -> when (minor) {
                BluetoothClass.Device.COMPUTER_DESKTOP -> "Desktop"
                BluetoothClass.Device.COMPUTER_SERVER -> "Server"
                BluetoothClass.Device.COMPUTER_LAPTOP -> "Laptop"
                BluetoothClass.Device.COMPUTER_HANDHELD_PC_PDA -> "Handheld PC/PDA"
                BluetoothClass.Device.COMPUTER_PALM_SIZE_PC_PDA -> "Palm Size PC/PDA"
                BluetoothClass.Device.COMPUTER_WEARABLE -> "Wearable Computer"
                else -> "Computer (0x${minor.toString(16)})"
            }
            BluetoothClass.Device.Major.PHONE -> when (minor) {
                BluetoothClass.Device.PHONE_CELLULAR -> "Cellular"
                BluetoothClass.Device.PHONE_CORDLESS -> "Cordless"
                BluetoothClass.Device.PHONE_SMART -> "Smartphone"
                0x506 -> "Modem"
                BluetoothClass.Device.PHONE_ISDN -> "ISDN"
                else -> "Phone (0x${minor.toString(16)})"
            }
            BluetoothClass.Device.Major.AUDIO_VIDEO -> when (minor) {
                BluetoothClass.Device.AUDIO_VIDEO_WEARABLE_HEADSET -> "Wearable Headset"
                BluetoothClass.Device.AUDIO_VIDEO_HANDSFREE -> "Hands-free"
                BluetoothClass.Device.AUDIO_VIDEO_MICROPHONE -> "Microphone"
                BluetoothClass.Device.AUDIO_VIDEO_LOUDSPEAKER -> "Loudspeaker"
                BluetoothClass.Device.AUDIO_VIDEO_HEADPHONES -> "Headphones"
                BluetoothClass.Device.AUDIO_VIDEO_PORTABLE_AUDIO -> "Portable Audio"
                BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO -> "Car Audio"
                BluetoothClass.Device.AUDIO_VIDEO_SET_TOP_BOX -> "Set-top Box"
                BluetoothClass.Device.AUDIO_VIDEO_HIFI_AUDIO -> "Hi-Fi Audio"
                BluetoothClass.Device.AUDIO_VIDEO_VCR -> "VCR"
                BluetoothClass.Device.AUDIO_VIDEO_VIDEO_CAMERA -> "Video Camera"
                BluetoothClass.Device.AUDIO_VIDEO_CAMCORDER -> "Camcorder"
                BluetoothClass.Device.AUDIO_VIDEO_VIDEO_MONITOR -> "Video Monitor"
                BluetoothClass.Device.AUDIO_VIDEO_VIDEO_DISPLAY_AND_LOUDSPEAKER -> "Video Display & Loudspeaker"
                BluetoothClass.Device.AUDIO_VIDEO_VIDEO_CONFERENCING -> "Video Conferencing"
                BluetoothClass.Device.AUDIO_VIDEO_VIDEO_GAMING_TOY -> "Gaming/Toy"
                else -> "Audio/Video (0x${minor.toString(16)})"
            }
            BluetoothClass.Device.Major.PERIPHERAL -> when (minor and 0xFFC0) {
                BluetoothClass.Device.PERIPHERAL_KEYBOARD -> "Keyboard"
                0x500 -> "Pointing Device"
                BluetoothClass.Device.PERIPHERAL_KEYBOARD_POINTING -> "Keyboard+Pointing"
                0x502 -> "Joystick"
                0x504 -> "Gamepad"
                0x503 -> "Remote Control"
                0x540 -> "Sensing Device"
                0x560 -> "Digitizer Tablet"
                0x580 -> "Card Reader"
                else -> "Peripheral (0x${minor.toString(16)})"
            }
            BluetoothClass.Device.Major.WEARABLE -> when (minor) {
                BluetoothClass.Device.WEARABLE_WRIST_WATCH -> "Wrist Watch"
                BluetoothClass.Device.WEARABLE_PAGER -> "Pager"
                BluetoothClass.Device.WEARABLE_JACKET -> "Jacket"
                BluetoothClass.Device.WEARABLE_HELMET -> "Helmet"
                BluetoothClass.Device.WEARABLE_GLASSES -> "Glasses"
                else -> "Wearable (0x${minor.toString(16)})"
            }
            BluetoothClass.Device.Major.TOY -> when (minor) {
                BluetoothClass.Device.TOY_ROBOT -> "Robot"
                BluetoothClass.Device.TOY_VEHICLE -> "Vehicle"
                BluetoothClass.Device.TOY_DOLL_ACTION_FIGURE -> "Doll/Action Figure"
                BluetoothClass.Device.TOY_CONTROLLER -> "Controller"
                BluetoothClass.Device.TOY_GAME -> "Game"
                else -> "Toy (0x${minor.toString(16)})"
            }
            BluetoothClass.Device.Major.HEALTH -> when (minor shr 3) {
                0x00 -> "Undefined"
                0x01 -> "Blood Pressure Monitor"
                0x02 -> "Thermometer"
                0x03 -> "Weighing Scale"
                0x04 -> "Glucose Meter"
                0x05 -> "Pulse Oximeter"
                0x06 -> "Heart/Pulse Rate Monitor"
                0x07 -> "Health Data Display"
                0x08 -> "Step Counter"
                0x09 -> "Body Composition Analyzer"
                else -> "Health (0x${minor.toString(16)})"
            }
            else -> "Uncategorized (0x${minor.toString(16)})"
        }
    }

    private fun deviceTypeFromClass(btClass: BluetoothClass): String {
        val minor = btClass.deviceClass
        return when (btClass.majorDeviceClass) {
            BluetoothClass.Device.Major.AUDIO_VIDEO -> when {
                minor == BluetoothClass.Device.AUDIO_VIDEO_LOUDSPEAKER -> "Bluetooth Speaker"
                minor == BluetoothClass.Device.AUDIO_VIDEO_HEADPHONES -> "Headphones"
                minor == BluetoothClass.Device.AUDIO_VIDEO_WEARABLE_HEADSET -> "Headset"
                minor == BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO -> "Car Audio System"
                minor == BluetoothClass.Device.AUDIO_VIDEO_HIFI_AUDIO -> "Hi-Fi Audio System"
                minor == BluetoothClass.Device.AUDIO_VIDEO_PORTABLE_AUDIO -> "Portable Speaker"
                minor == BluetoothClass.Device.AUDIO_VIDEO_VIDEO_DISPLAY_AND_LOUDSPEAKER -> "TV/Display"
                minor == BluetoothClass.Device.AUDIO_VIDEO_VIDEO_CONFERENCING -> "Video Conferencing"
                else -> "Audio/Video Device"
            }
            BluetoothClass.Device.Major.PERIPHERAL -> when (minor and 0xFFC0) {
                BluetoothClass.Device.PERIPHERAL_KEYBOARD -> "Keyboard"
                0x500 -> "Mouse/Pointing Device"
                BluetoothClass.Device.PERIPHERAL_KEYBOARD_POINTING -> "Keyboard+Mouse Combo"
                0x504 -> "Gamepad"
                0x502 -> "Joystick"
                0x503 -> "Remote Control"
                else -> "Peripheral"
            }
            BluetoothClass.Device.Major.COMPUTER -> "Computer (${minorClassToString(btClass)})"
            BluetoothClass.Device.Major.PHONE -> "Phone (${minorClassToString(btClass)})"
            BluetoothClass.Device.Major.WEARABLE -> "Wearable (${minorClassToString(btClass)})"
            BluetoothClass.Device.Major.TOY -> "Toy (${minorClassToString(btClass)})"
            BluetoothClass.Device.Major.HEALTH -> "Health Device (${minorClassToString(btClass)})"
            BluetoothClass.Device.Major.NETWORKING -> "Network Device"
            BluetoothClass.Device.Major.IMAGING -> "Imaging Device"
            else -> "Unknown Device"
        }
    }

    private fun bondStateToString(state: Int): String = when (state) {
        BluetoothDevice.BOND_NONE -> "Not Paired"
        BluetoothDevice.BOND_BONDING -> "Pairing..."
        BluetoothDevice.BOND_BONDED -> "Paired"
        else -> "Unknown"
    }
}
