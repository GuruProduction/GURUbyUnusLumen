package com.unuslumen.app.data.metadata

import android.app.KeyguardManager
import android.bluetooth.BluetoothManager
import android.content.Context
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.PowerManager
import android.app.NotificationManager
import com.unuslumen.app.data.tor.TorManager
import com.unuslumen.app.database.dao.ToolResultDao
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * The complete metadata block, one flat payload injected into messages.
 * Every field corresponds to a server-toggleable group. Web searches carry
 * their exact DB row ids so any past search is directly retrievable from the
 * tool_results table via getToolResult / searchToolResults.
 */
@Serializable
data class MetadataValues(
    val clock: MetadataClock? = null,
    val gaps: MetadataGaps? = null,
    val battery: MetadataBattery? = null,
    val screen: MetadataScreen? = null,
    val network: MetadataNetwork? = null,
    val audio: MetadataAudio? = null,
    @SerialName("self_state") val selfState: MetadataSelfState? = null,
    val place: MetadataPlace? = null,
    val weather: MetadataWeather? = null,
    val motion: MetadataMotion? = null,
    val bluetooth: MetadataBluetooth? = null
)

@Serializable
data class MetadataClock(
    @SerialName("iso_timestamp") val isoTimestamp: String,
    @SerialName("epoch_ms") val epochMillis: Long,
    val timezone: String,
    @SerialName("dst_active") val dstActive: Boolean
)

@Serializable
data class MetadataGaps(
    @SerialName("seconds_since_users_last_message") val secondsSinceUsersLastMessage: Long?,
    @SerialName("seconds_since_gurus_last_reply") val secondsSinceGurusLastReply: Long?
)

@Serializable
data class MetadataBattery(
    val levelPercent: Int,
    val charging: Boolean
)

@Serializable
data class MetadataScreen(
    val on: Boolean,
    val locked: Boolean
)

@Serializable
data class MetadataNetwork(
    val type: String,
    @SerialName("tor_up") val torUp: Boolean
)

@Serializable
data class MetadataAudio(
    @SerialName("ringer_mode") val ringerMode: String,
    val dnd: String
)

@Serializable
data class MetadataSelfState(
    @SerialName("build_version") val buildVersion: String,
    @SerialName("session_id") val sessionId: String,
    @SerialName("web_searches_recent") val recentWebSearches: List<MetadataWebSearch> = emptyList(),
    @SerialName("recent_actions") val recentActions: List<MetadataRecentAction> = emptyList(),
    @SerialName("compacted") val compacted: Boolean = false,
    @SerialName("context_remaining_percent") val contextRemainingPercent: Int = 100
)

@Serializable
data class MetadataWebSearch(
    val id: String,
    @SerialName("timestamp_ms") val timestampMillis: Long,
    @SerialName("ago") val ageMinutes: Long,
    val query: String,
    @SerialName("rows_in_db") val dbTable: String = "tool_results",
    @SerialName("db_id_column") val dbIdColumn: String = "id"
)

@Serializable
data class MetadataRecentAction(
    val id: String,
    val tool: String,
    @SerialName("timestamp_ms") val timestampMillis: Long,
    @SerialName("ago") val ageMinutes: Long,
    @SerialName("rows_in_db") val dbTable: String = "tool_results",
    @SerialName("db_id_column") val dbIdColumn: String = "id"
)

@Serializable
data class MetadataPlace(
    @SerialName("town") val town: String?,
    @SerialName("last_updated_ms") val lastUpdatedMillis: Long
)

@Serializable
data class MetadataWeather(
    @SerialName("condition") val condition: String?,
    @SerialName("temperature_c") val temperatureC: Double?,
    @SerialName("location") val location: String?,
    @SerialName("last_updated_ms") val lastUpdatedMillis: Long
)

@Serializable
data class MetadataMotion(
    val state: String
)

@Serializable
data class MetadataBluetooth(
    @SerialName("connected_devices") val connectedDevices: List<String> = emptyList()
)

/**
 * Context handed to the assembler by the send path. Gaps need the tail of the
 * message history; self-state needs session identity and context numbers.
 */
data class MetadataSendContext(
    val sessionId: String,
    val messages: List<com.unuslumen.app.domain.model.AiMessage>,
    @SerialName("context_total_tokens") val contextWindowTokens: Int,
    val currentTokenEstimate: Int,
    val compacted: Boolean
)

/**
 * Composes the final block string from enabled groups per server config.
 * A group disabled in config yields a null in the values object — absent data,
 * never fabricated.
 */
class MetadataAssembler(
    private val context: Context,
    private val toolResultDao: ToolResultDao,
    private val torManager: TorManager?
) {
    private val json = Json { encodeDefaults = true }

    @kotlinx.serialization.ExperimentalSerializationApi
    suspend fun assemble(
        config: MetadataConfig,
        sendContext: MetadataSendContext
    ): String? {
        if (!config.enabled) return null

        val now = System.currentTimeMillis()
        val values = MetadataValues(
            clock = if (config.groups.clock) collectClock(now) else null,
            gaps = if (config.groups.gaps) collectGaps(sendContext.messages, now) else null,
            battery = if (config.groups.battery) collectBattery() else null,
            screen = if (config.groups.screen) collectScreen() else null,
            network = if (config.groups.network) collectNetwork() else null,
            audio = if (config.groups.audio) collectAudio() else null,
            selfState = if (config.groups.selfState) {
                collectSelfState(config, sendContext, now)
            } else null,
            place = if (config.groups.place) lastKnownPlace(now, config.placeRefreshMinutes) else null,
            weather = if (config.groups.weather) lastKnownWeather(now, config.weatherRefreshMinutes) else null,
            motion = if (config.groups.motion) collectMotion() else null,
            bluetooth = if (config.groups.bluetooth) collectBluetooth(config) else null
        )

        if (values == MetadataValues()) return null  // everything disabled — no block at all

        val valuesJson = json.encodeToString(values)
        return config.blockTemplate.replace("{METADATA}", valuesJson)
    }

    private fun collectClock(now: Long): MetadataClock {
        val tz = java.util.TimeZone.getDefault()
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZZZZZ", Locale.US).apply {
            setTimeZone(tz)
        }.format(Date(now))
        val dst = tz.inDaylightTime(Date(now))
        return MetadataClock(
            isoTimestamp = iso,
            epochMillis = now,
            timezone = tz.id,
            dstActive = dst
        )
    }

    private fun collectGaps(messages: List<com.unuslumen.app.domain.model.AiMessage>, now: Long): MetadataGaps {
        val lastUser = messages.filterIsInstance<com.unuslumen.app.domain.model.AiMessage.UserMessage>().lastOrNull()?.time
        val lastAssistant = messages.filterIsInstance<com.unuslumen.app.domain.model.AiMessage.AssistantMessage>().lastOrNull()?.time
        val userGap = lastUser?.let { (now - it) / 1000L }
        val assistantGap = lastAssistant?.let { (now - it) / 1000L }
        return MetadataGaps(
            secondsSinceUsersLastMessage = userGap,
            secondsSinceGurusLastReply = assistantGap
        )
    }

    private fun collectBattery(): MetadataBattery? {
        return try {
            val intent = context.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
                ?: return null
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL
            if (level < 0 || scale <= 0) return null
            MetadataBattery(
                levelPercent = (level * 100) / scale,
                charging = charging
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun collectScreen(): MetadataScreen? {
        return try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            val km = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            MetadataScreen(on = pm.isInteractive, locked = km.isKeyguardLocked)
        } catch (e: Exception) {
            null
        }
    }

    private fun collectNetwork(): MetadataNetwork? {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val caps = cm.getNetworkCapabilities(cm.activeNetwork)
            val type = when {
                caps == null -> "offline"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
                else -> "other"
            }
            MetadataNetwork(
                type = type,
                torUp = torManager?.isReady?.value ?: false
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun collectAudio(): MetadataAudio? {
        return try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val ringer = when (am.ringerMode) {
                AudioManager.RINGER_MODE_SILENT -> "silent"
                AudioManager.RINGER_MODE_VIBRATE -> "vibrate"
                else -> "normal"
            }
            val dnd = when (nm.currentInterruptionFilter) {
                NotificationManager.INTERRUPTION_FILTER_NONE -> "total_silence"
                NotificationManager.INTERRUPTION_FILTER_ALARMS -> "alarms_only"
                NotificationManager.INTERRUPTION_FILTER_PRIORITY -> "priority_only"
                NotificationManager.INTERRUPTION_FILTER_ALL -> "off"
                else -> "unknown"
            }
            MetadataAudio(ringerMode = ringer, dnd = dnd)
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun collectSelfState(
        config: MetadataConfig,
        sendContext: MetadataSendContext,
        now: Long
    ): MetadataSelfState {
        // Last N web searches straight from the tool_results table with row ids.
        // GURU then knows exactly where every past search lives on-device.
        val webSearches = try {
            toolResultDao.getRecentByToolNameAndConversation(
                "webSearch", sendContext.sessionId, config.recentWebSearchesCount
            ).map { entity ->
                val query = parseSearchQuery(entity.parameters)
                MetadataWebSearch(
                    id = entity.id,
                    timestampMillis = entity.timestamp,
                    ageMinutes = (now - entity.timestamp) / 60_000L,
                    query = query,
                )
            }
        } catch (e: Exception) {
            emptyList()
        }

        // Fetch a wider window then filter webSearch out, so that when several of the
        // most recent rows are searches the real actions are still visible. Filtering
        // AFTER fetching a bare N meant search rows could crowd out every action.
        // The overscan depth is superadmin config: recent_actions_overscan.
        val recentActionEntries = try {
            val all = toolResultDao.getRecentByConversation(
                sendContext.sessionId, config.recentActionsCount + config.recentActionsOverscan.coerceAtLeast(0)
            )
            all.filter { it.toolName != "webSearch" }.take(config.recentActionsCount)
        } catch (e: Exception) {
            emptyList()
        }

        val totalPercent = if (sendContext.contextWindowTokens > 0) {
            ((sendContext.contextWindowTokens - sendContext.currentTokenEstimate) * 100) /
                    sendContext.contextWindowTokens
        } else 100

        return MetadataSelfState(
            buildVersion = buildVersion(),
            sessionId = sendContext.sessionId,
            recentWebSearches = webSearches,
            recentActions = recentActionEntries.map { entity ->
                MetadataRecentAction(
                    id = entity.id,
                    tool = entity.toolName,
                    timestampMillis = entity.timestamp,
                    ageMinutes = (now - entity.timestamp) / 60_000L,
                )
            },
            compacted = sendContext.compacted,
            contextRemainingPercent = totalPercent.coerceIn(0, 100)
        )
    }

    private fun parseSearchQuery(parametersJson: String): String {
        if (parametersJson.isBlank()) return ""
        return try {
            val obj = Json.parseToJsonElement(parametersJson)
            (obj as? kotlinx.serialization.json.JsonObject)?.get("query")
                ?.let { it as? kotlinx.serialization.json.JsonPrimitive }?.content ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    private fun buildVersion(): String {
        return try {
            val pm = context.packageManager.getPackageInfo(context.packageName, 0)
            "${pm.versionName} (${pm.longVersionCode})"
        } catch (e: Exception) {
            "unknown"
        }
    }

    // Last-known sensors. Values survive across sends; refreshed on cadence from
    // config. The message path NEVER blocks on these — if stale, the age is visible.
    private fun lastKnownPlace(now: Long, refreshMinutes: Int): MetadataPlace? {
        val cached = CacheStore.place
        return MetadataPlace(
            town = cached?.town,
            lastUpdatedMillis = cached?.timestampMillis ?: 0L
        )
    }

    private fun lastKnownWeather(now: Long, refreshMinutes: Int): MetadataWeather? {
        val cached = CacheStore.weather
        return MetadataWeather(
            condition = cached?.condition,
            temperatureC = cached?.temperatureC,
            location = cached?.location,
            lastUpdatedMillis = cached?.timestampMillis ?: 0L
        )
    }

    private fun collectMotion(): MetadataMotion? {
        // Motion is owned by MetadataRefreshWorker, fed into CacheStore on the
        // config cadence. The send path reads only. If the worker has not filled
        // it yet, the block stays absent honestly rather than blocking on a read.
        val state = CacheStore.motionState ?: return null
        return MetadataMotion(state = state)
    }

    private fun readMotionLive(): String? {
        return null
    }

    private fun collectBluetooth(config: MetadataConfig): MetadataBluetooth? {
        return try {
            // Permission gate. On Android 12+ the profile state queries below
            // return empty/disconnected rather than throwing, so a missing
            // BLUETOOTH_CONNECT previously produced a confident empty list.
            // Ask for the permission the same way bluetoothList does instead
            // of silently reporting an empty world.
            if (context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
                != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                try {
                    val intent = android.content.Intent("com.unuslumen.app.guru.REQUEST_BLUETOOTH_PERMISSION")
                    intent.setPackage(context.packageName)
                    context.sendBroadcast(intent)
                } catch (_: Exception) {}
                return null  // absent data, never fabricated; dialog lands, next send populates
            }
            val bm = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            // Polling set comes from superadmin config: bluetooth_profiles. GATT
            // covers LE peripherals; A2DP/HEADSET cover audio hardware like ears.
            // Union then dedupe by name so dual-profile devices show once.
            val profiles = config.bluetoothProfiles
                .map { it.lowercase(Locale.US).trim() }
                .filter { it in setOf("gatt", "a2dp", "headset") }
                .ifEmpty { listOf("gatt", "a2dp", "headset") }
                .mapNotNull { profileName ->
                    when (profileName) {
                        "gatt" -> android.bluetooth.BluetoothProfile.GATT
                        "a2dp" -> android.bluetooth.BluetoothProfile.A2DP
                        "headset" -> android.bluetooth.BluetoothProfile.HEADSET
                        else -> null
                    }
                }
            val names = LinkedHashSet<String>()
            // Query devices in the given connection state per profile directly.
            // The old adapter.getProfileConnectionState precheck silently reads
            // as not-connected without connect permission, so an attached device
            // never made it past this line. The profile list itself is the truth.
            for (profile in profiles) {
                bm.getDevicesMatchingConnectionStates(
                    profile,
                    intArrayOf(android.bluetooth.BluetoothProfile.STATE_CONNECTED)
                )?.forEach { d ->
                    val resolved = d.name ?: resolveBondedName(d.address) ?: d.address
                    if (!resolved.isNullOrBlank()) names.add(resolved)
                }
            }
            MetadataBluetooth(connectedDevices = names.toList())
        } catch (e: Exception) {
            android.util.Log.w("guru", "Metadata bluetooth read failed: ${e.message}")
            null
        }
    }

    private fun resolveBondedName(address: String): String? {
        return try {
            val bm = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            bm.adapter?.bondedDevices?.firstOrNull { it.address == address }?.name
        } catch (e: Exception) {
            null
        }
    }
}