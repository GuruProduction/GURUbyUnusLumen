// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.tools

import android.content.Context
import com.unuslumen.app.data.discovery.ArpDiscoveryManager
import com.unuslumen.app.data.smarthome.HueApi
import com.unuslumen.app.util.shell.ShellExecutor
import com.unuslumen.app.data.tools.registry.ToolExecutionResult
import com.unuslumen.app.data.tools.registry.ToolExecutor
import com.unuslumen.app.data.tools.registry.permission.PermissionGrants
import com.unuslumen.app.data.tools.registry.permission.TeamThreePermissions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

class SmartHomeToolExecutor(
    private val context: Context,
    /**
     * The mDNS manager, for finding a Hue Bridge locally.
     *
     * INJECTED RATHER THAN RE-CREATED, so this uses the same listener the discovery tools report on
     * rather than opening a second one that could disagree with the first. That disagreement is
     * exactly what issue 330 was: two tools answering about one network in one minute.
     */
    private val mdnsDiscoveryManager: com.unuslumen.app.data.discovery.MdnsDiscoveryManager,
) : ToolExecutor {
    private val json = Json { ignoreUnknownKeys = true }
    private val shellExecutor = ShellExecutor(context)

    companion object {
        /**
         * The one place the permission-request action is written.
         *
         * MainActivity listens for exactly this string and raises the real
         * Android dialog for BLUETOOTH_CONNECT. bluetoothList, bluetoothConnect
         * and bluetoothDisconnect all send it through [requestBluetoothPermission]
         * so the three can never drift apart.
         */
        private const val BLUETOOTH_PERMISSION_ACTION =
            "com.unuslumen.app.guru.REQUEST_BLUETOOTH_PERMISSION"
    }

    override suspend fun execute(toolName: String, args: Map<String, Any?>): ToolExecutionResult = when (toolName) {
        SmartHomeToolDefinitions.HUE_DISCOVER -> hueDiscover()
        SmartHomeToolDefinitions.HUE_PAIR -> huePair(args)
        SmartHomeToolDefinitions.HUE_LIST_LIGHTS -> hueListLights()
        SmartHomeToolDefinitions.HUE_LIST_ROOMS -> hueListRooms()
        SmartHomeToolDefinitions.HUE_LIST_SCENES -> hueListScenes()
        SmartHomeToolDefinitions.HUE_SET_LIGHT -> hueSetLight(args)
        SmartHomeToolDefinitions.HUE_SET_ROOM -> hueSetRoom(args)
        SmartHomeToolDefinitions.HUE_ACTIVATE_SCENE -> hueActivateScene(args)
        SmartHomeToolDefinitions.SONOS_DISCOVER -> sonosDiscover()
        SmartHomeToolDefinitions.SONOS_STATUS -> sonosStatus(args)
        SmartHomeToolDefinitions.SONOS_PLAY_PAUSE -> sonosPlayPause(args)
        SmartHomeToolDefinitions.SONOS_VOLUME -> sonosVolume(args)
        SmartHomeToolDefinitions.SONOS_GROUP -> sonosGroup(args)
        SmartHomeToolDefinitions.BLUETOOTH_LIST -> bluetoothList()
        SmartHomeToolDefinitions.BLUETOOTH_CONNECT -> bluetoothConnect(args)
        SmartHomeToolDefinitions.BLUETOOTH_DISCONNECT -> bluetoothDisconnect(args)
        SmartHomeToolDefinitions.NETWORK_SCAN -> networkScan()
        SmartHomeToolDefinitions.NETWORK_PING -> networkPing(args)
        else -> ToolExecutionResult.error("Unknown tool: $toolName")
    }

    /*
     * THE SIX HUE TOOLS, OVER THE BRIDGE'S OWN HTTP API (issue 179).
     *
     * Every one of these used to run `openhue <something>`, and `openhue` is a Go binary Termux does
     * not package and the APK never contained. So the commands could only fail, and they failed
     * with a sentence telling a person to install something that does not exist. The whole smart
     * home capability was dead, in the ticket's words, and had always been dead.
     *
     * The bridge is a JSON HTTP server on the local network. Discovery, the request shapes and the
     * meaning of every response live in [HueApi] as pure functions, proven on the JVM; [HueClient]
     * is the transport. What is left here is mapping a tool's arguments onto those decisions.
     *
     * A MISSING BRIDGE OR KEY IS ITS OWN SENTENCE. "Not paired yet" and "the bridge is not
     * answering" are different instructions, and collapsing them into one message is the habit this
     * whole batch exists to remove.
     */
    private val hueClient by lazy { com.unuslumen.app.data.smarthome.HueClient(context) }

    /**
     * Find bridges on this network.
     *
     * The local mDNS route is the one that works without internet, and the app already has it: the
     * SSDP/mDNS discovery manager records `_hue._tcp` services, so this asks it rather than opening
     * a second listener that could disagree with the first.
     */
    private suspend fun hueDiscover(): ToolExecutionResult = withContext(Dispatchers.IO) {
        val local = runCatching {
            mdnsDiscoveryManager.getDiscoveredDevices()
                .filter { it.serviceType.contains("_hue.", ignoreCase = true) }
                .mapNotNull { device -> device.host.takeIf { it.isNotBlank() } }
        }.getOrDefault(emptyList())

        val bridges = hueClient.discover(local)
        val r = HueBridgeResult(
            success = bridges.isNotEmpty(),
            bridges = bridges.map { it.host },
            error = if (bridges.isEmpty()) {
                "No Hue Bridge answered on this network. Check that the bridge is powered and on " +
                    "the same wifi as this phone; if it is, run mdnsDiscover first so the local " +
                    "announcements are collected."
            } else null,
        )
        ToolExecutionResult.success(r, json.encodeToString(HueBridgeResult.serializer(), r))
    }

    /**
     * Pair with a bridge, which is what issues the application key.
     *
     * THE WAITING STATE IS NOT A FAILURE and is reported as its own outcome, because the bridge is
     * behaving exactly as designed and the next move belongs to the person holding the phone.
     */
    private suspend fun huePair(args: Map<String, Any?>): ToolExecutionResult = withContext(Dispatchers.IO) {
        val host = args["bridge"] as? String ?: return@withContext ToolExecutionResult.error("Missing 'bridge'")
        val outcome = hueClient.pair(host.trim())
        val r = HueBridgeResult(
            success = outcome.state == com.unuslumen.app.data.smarthome.HueApi.PairingState.PAIRED,
            state = outcome.state.name,
            bridge = host.trim(),
            error = if (outcome.state == com.unuslumen.app.data.smarthome.HueApi.PairingState.PAIRED) {
                null
            } else {
                outcome.sentence
            },
        )
        ToolExecutionResult.success(r, json.encodeToString(HueBridgeResult.serializer(), r))
    }

    /** The saved bridge and key, or a result explaining what is missing. */
    private fun hueBridgeOrNull(): Pair<com.unuslumen.app.data.smarthome.HueApi.Bridge?, String?> {
        val bridge = hueClient.savedBridge()
            ?: return null to "No Hue Bridge has been set up yet. Run hueDiscover to find one, then " +
                "huePair with its address. The bridge's own button is pressed once during pairing."
        if (!bridge.hasKey) {
            return null to "The Hue Bridge at ${bridge.host} has no application key yet, so it will " +
                "refuse every request. Press the bridge's button and run huePair."
        }
        return bridge to null
    }

    private suspend fun hueListLights(): ToolExecutionResult = withContext(Dispatchers.IO) {
        val (bridge, missing) = hueBridgeOrNull()
        if (bridge == null) {
            val r = HueLightsResult(success = false, lights = emptyList(), error = missing)
            return@withContext ToolExecutionResult.success(r, json.encodeToString(HueLightsResult.serializer(), r))
        }
        val (body, error) = hueClient.read(bridge, HueApi.lightsPath(bridge))
        if (body == null) {
            val r = HueLightsResult(success = false, lights = emptyList(), error = error)
            return@withContext ToolExecutionResult.success(r, json.encodeToString(HueLightsResult.serializer(), r))
        }
        /*
         * EACH LIGHT'S STATE IS READ FROM ITS OWN `state` OBJECT. The old parser guessed `on` by
         * searching the whole line for the substring "on", which is true of every light whose name
         * contains it.
         */
        val lights = HueApi.entriesIn(body).map { entry ->
            val fragment = body.substringAfter("\"${entry.id}\"", "")
            HueLight(
                id = entry.id,
                name = entry.name,
                on = Regex("\"on\"\\s*:\\s*true").containsMatchIn(fragment),
                brightness = HueApi.extractNumber(fragment, "bri") ?: 0,
            )
        }
        val r = HueLightsResult(success = true, lights = lights, error = null)
        ToolExecutionResult.success(r, json.encodeToString(HueLightsResult.serializer(), r))
    }

    private suspend fun hueListRooms(): ToolExecutionResult = withContext(Dispatchers.IO) {
        val (bridge, missing) = hueBridgeOrNull()
        if (bridge == null) {
            val r = HueRoomsResult(success = false, rooms = emptyList(), error = missing)
            return@withContext ToolExecutionResult.success(r, json.encodeToString(HueRoomsResult.serializer(), r))
        }
        val (body, error) = hueClient.read(bridge, HueApi.roomsPath(bridge))
        if (body == null) {
            val r = HueRoomsResult(success = false, rooms = emptyList(), error = error)
            return@withContext ToolExecutionResult.success(r, json.encodeToString(HueRoomsResult.serializer(), r))
        }
        val rooms = HueApi.entriesIn(body).map { HueRoom(id = it.id, name = it.name) }
        val r = HueRoomsResult(success = true, rooms = rooms, error = null)
        ToolExecutionResult.success(r, json.encodeToString(HueRoomsResult.serializer(), r))
    }

    private suspend fun hueListScenes(): ToolExecutionResult = withContext(Dispatchers.IO) {
        val (bridge, missing) = hueBridgeOrNull()
        if (bridge == null) {
            val r = HueScenesResult(success = false, scenes = emptyList(), error = missing)
            return@withContext ToolExecutionResult.success(r, json.encodeToString(HueScenesResult.serializer(), r))
        }
        val (body, error) = hueClient.read(bridge, HueApi.scenesPath(bridge))
        if (body == null) {
            val r = HueScenesResult(success = false, scenes = emptyList(), error = error)
            return@withContext ToolExecutionResult.success(r, json.encodeToString(HueScenesResult.serializer(), r))
        }
        val scenes = HueApi.entriesIn(body).map { HueScene(id = it.id, name = it.name) }
        val r = HueScenesResult(success = true, scenes = scenes, error = null)
        ToolExecutionResult.success(r, json.encodeToString(HueScenesResult.serializer(), r))
    }

    /**
     * Resolve a light the caller named to the id the bridge addresses.
     *
     * THE CALLER SAYS A NAME, THE API WANTS AN ID. Accepting either is what makes the tool usable
     * without making a person learn bridge ids, and it is the same allowance bluetoothConnect makes
     * for a MAC or a name.
     */
    private suspend fun resolveHueId(bridge: HueApi.Bridge, path: String, query: String): Pair<String?, String?> {
        // A LOCAL CALLED `error` WOULD SHADOW ToolExecutionResult.error, which is how this failed to
        // compile the first time. The names here are deliberate.
        val readPair = hueClient.read(bridge, path)
        val body = readPair.first
        val readFailure = readPair.second
        if (body == null) return null to (readFailure ?: "The bridge did not answer.")
        val entries = HueApi.entriesIn(body)
        val trimmed = query.trim()
        entries.firstOrNull { it.id == trimmed }?.let { return it.id to null }
        entries.firstOrNull { it.name.equals(trimmed, ignoreCase = true) }?.let { return it.id to null }
        return null to "\"$query\" is neither an id nor the name of anything the bridge reports. " +
            "It knows: ${entries.joinToString(", ") { it.name }}."
    }

    private suspend fun hueSetLight(args: Map<String, Any?>): ToolExecutionResult = withContext(Dispatchers.IO) {
        val light = args["light"] as? String ?: return@withContext ToolExecutionResult.error("Missing 'light'")
        val power = args["power"] as? String
        val brightness = (args["brightness"] as? Number)?.toInt()
        val temperature = (args["temperature"] as? Number)?.toInt()

        val (bridge, missing) = hueBridgeOrNull()
        if (bridge == null) {
            val r = HueResult(success = false, action = "set_light", target = light, error = missing)
            return@withContext ToolExecutionResult.success(r, json.encodeToString(HueResult.serializer(), r))
        }
        val (id, resolveError) = resolveHueId(bridge, HueApi.lightsPath(bridge), light)
        if (id == null) {
            val r = HueResult(success = false, action = "set_light", target = light, error = resolveError)
            return@withContext ToolExecutionResult.success(r, json.encodeToString(HueResult.serializer(), r))
        }
        val body = HueApi.lightStateBody(
            on = when (power?.lowercase()) {
                "on" -> true
                "off" -> false
                else -> null
            },
            brightnessPercent = brightness,
            temperatureMired = temperature,
        )
        val (ok, writeError) = hueClient.write(bridge, HueApi.lightStatePath(bridge, id), body)
        val r = HueResult(success = ok, action = "set_light", target = light, error = writeError)
        ToolExecutionResult.success(r, json.encodeToString(HueResult.serializer(), r))
    }

    private suspend fun hueSetRoom(args: Map<String, Any?>): ToolExecutionResult = withContext(Dispatchers.IO) {
        val room = args["room"] as? String ?: return@withContext ToolExecutionResult.error("Missing 'room'")
        val power = args["power"] as? String
        val brightness = (args["brightness"] as? Number)?.toInt()

        val (bridge, missing) = hueBridgeOrNull()
        if (bridge == null) {
            val r = HueResult(success = false, action = "set_room", target = room, error = missing)
            return@withContext ToolExecutionResult.success(r, json.encodeToString(HueResult.serializer(), r))
        }
        val (id, resolveError) = resolveHueId(bridge, HueApi.roomsPath(bridge), room)
        if (id == null) {
            val r = HueResult(success = false, action = "set_room", target = room, error = resolveError)
            return@withContext ToolExecutionResult.success(r, json.encodeToString(HueResult.serializer(), r))
        }
        val body = HueApi.lightStateBody(
            on = when (power?.lowercase()) {
                "on" -> true
                "off" -> false
                else -> null
            },
            brightnessPercent = brightness,
        )
        val (ok, writeError) = hueClient.write(bridge, HueApi.roomActionPath(bridge, id), body)
        val r = HueResult(success = ok, action = "set_room", target = room, error = writeError)
        ToolExecutionResult.success(r, json.encodeToString(HueResult.serializer(), r))
    }

    private suspend fun hueActivateScene(args: Map<String, Any?>): ToolExecutionResult = withContext(Dispatchers.IO) {
        val scene = args["scene"] as? String ?: return@withContext ToolExecutionResult.error("Missing 'scene'")
        val room = args["room"] as? String ?: return@withContext ToolExecutionResult.error("Missing 'room'")

        val (bridge, missing) = hueBridgeOrNull()
        if (bridge == null) {
            val r = HueResult(success = false, action = "activate_scene", target = "$scene in $room", error = missing)
            return@withContext ToolExecutionResult.success(r, json.encodeToString(HueResult.serializer(), r))
        }
        val (roomId, roomError) = resolveHueId(bridge, HueApi.roomsPath(bridge), room)
        if (roomId == null) {
            val r = HueResult(success = false, action = "activate_scene", target = "$scene in $room", error = roomError)
            return@withContext ToolExecutionResult.success(r, json.encodeToString(HueResult.serializer(), r))
        }
        val (sceneId, sceneError) = resolveHueId(bridge, HueApi.scenesPath(bridge), scene)
        if (sceneId == null) {
            val r = HueResult(success = false, action = "activate_scene", target = "$scene in $room", error = sceneError)
            return@withContext ToolExecutionResult.success(r, json.encodeToString(HueResult.serializer(), r))
        }
        /*
         * A SCENE IS APPLIED TO A GROUP, not started on its own. Group 0 is every light, and the
         * bridge's documented form is `{"scene":"<id>"}` on the group's action endpoint.
         */
        val body = """{"scene":"$sceneId"}"""
        val (ok, writeError) = hueClient.write(bridge, HueApi.roomActionPath(bridge, roomId), body)
        val r = HueResult(success = ok, action = "activate_scene", target = "$scene in $room", error = writeError)
        ToolExecutionResult.success(r, json.encodeToString(HueResult.serializer(), r))
    }

    /*
     * THE FIVE SONOS TOOLS, OVER THE SPEAKERS' OWN UPnP SERVICES (issues 179 and 186).
     *
     * Every one of these ran `sonos <verb>`, a Node CLI that Termux does not package and the APK
     * never contained, so all five could only fail. A speaker is an ordinary UPnP device on port
     * 1400, so the envelope shapes and the reading of replies live in [SonosApi] as pure functions
     * and [SonosClient] is the transport.
     *
     * EVERY SPEAKER IS ADDRESSED BY A DISCOVERED HOST. The tools take a name, and the name is
     * resolved against the zone topology the speaker itself reports, because a guessed address
     * changes the wrong room.
     */
    private val sonosClient by lazy { com.unuslumen.app.data.smarthome.SonosClient() }

    /**
     * The one place a Sonos speaker is looked up.
     *
     * A SEED ADDRESS IS NEEDED THE FIRST TIME, because the topology comes from a speaker. So the
     * parameter accepts an IP or a name, and a name is resolved by asking any speaker the last scan
     * found. When nothing has been discovered yet the sentence says to run sonosDiscover rather
     * than pretending the house is empty.
     */
    private var lastSonosSpeakers: List<com.unuslumen.app.data.smarthome.SonosApi.Speaker> = emptyList()

    private suspend fun resolveSonosSpeaker(query: String): Pair<com.unuslumen.app.data.smarthome.SonosApi.Speaker?, String?> {
        val trimmed = query.trim()
        // An address is taken as given, which is how a first speaker is reached at all.
        if (Regex("""^\d{1,3}(\.\d{1,3}){3}$""").matches(trimmed)) {
            return com.unuslumen.app.data.smarthome.SonosApi.Speaker(trimmed, trimmed) to null
        }
        if (lastSonosSpeakers.isEmpty()) {
            return null to "No Sonos speakers have been discovered yet, and \"$trimmed\" is a name " +
                "rather than an address. Run sonosDiscover with a speaker's IP first."
        }
        val named = lastSonosSpeakers.firstOrNull { it.name.equals(trimmed, ignoreCase = true) }
        return when {
            named != null -> named to null
            else -> null to "\"$trimmed\" is not one of the speakers this network reports. It knows: " +
                lastSonosSpeakers.joinToString(", ") { it.name } + "."
        }
    }

    private suspend fun sonosDiscover(): ToolExecutionResult = withContext(Dispatchers.IO) {
        /*
         * A SEED IS FOUND THROUGH THE SAME SSDP SCAN THE OTHER NETWORK TOOLS USE, so this does not
         * open a second listener that could disagree with the first. Sonos announces itself as
         * MediaRenderer, and one speaker's topology then names every other speaker in the house.
         */
        val local = runCatching {
            mdnsDiscoveryManager.getDiscoveredDevices()
                .mapNotNull { device -> device.host.takeIf { it.isNotBlank() } }
                .distinct()
        }.getOrDefault(emptyList())

        val found = mutableListOf<com.unuslumen.app.data.smarthome.SonosApi.Speaker>()
        for (host in local.take(12)) {
            val speakers = sonosClient.discoverFrom(host)
            if (speakers.isNotEmpty()) {
                found.addAll(speakers)
                break
            }
        }
        lastSonosSpeakers = found.distinctBy { it.host }

        val r = SonosDiscoverResult(
            success = lastSonosSpeakers.isNotEmpty(),
            speakers = lastSonosSpeakers.map {
                SonosSpeaker(name = it.name, ip = it.host, model = it.model.ifBlank { "Sonos" })
            },
            error = if (lastSonosSpeakers.isEmpty()) {
                "No Sonos speaker answered. Run mdnsDiscover first so the network announcements " +
                    "are collected, and check that the speaker is on the same wifi as this phone."
            } else null,
        )
        ToolExecutionResult.success(r, json.encodeToString(SonosDiscoverResult.serializer(), r))
    }

    private suspend fun sonosStatus(args: Map<String, Any?>): ToolExecutionResult = withContext(Dispatchers.IO) {
        val query = args["speaker"] as? String ?: return@withContext ToolExecutionResult.error("Missing 'speaker'")
        val (speaker, resolveError) = resolveSonosSpeaker(query)
        if (speaker == null) {
            val r = SonosStatusResult(success = false, status = null, error = resolveError)
            return@withContext ToolExecutionResult.success(r, json.encodeToString(SonosStatusResult.serializer(), r))
        }

        val (svc, actionArgs) = com.unuslumen.app.data.smarthome.SonosApi.getTransportInfo()
        val reply = sonosClient.call(speaker, svc, "GetTransportInfo", actionArgs)
        if (reply.failure != null || reply.statusCode !in 200..299) {
            val r = SonosStatusResult(
                success = false,
                status = null,
                error = com.unuslumen.app.data.smarthome.SonosApi.failureSentence(
                    speaker.host, reply.statusCode, reply.body, reply.failure,
                ),
            )
            return@withContext ToolExecutionResult.success(r, json.encodeToString(SonosStatusResult.serializer(), r))
        }

        val rawState = com.unuslumen.app.data.smarthome.SonosApi
            .elementText(reply.body, "CurrentTransportState").orEmpty()
        val track = com.unuslumen.app.data.smarthome.SonosApi
            .elementText(reply.body, "CurrentTrackURI").orEmpty()

        val (posSvc, posArgs) = com.unuslumen.app.data.smarthome.SonosApi.getPositionInfo()
        val posReply = sonosClient.call(speaker, posSvc, "GetPositionInfo", posArgs)
        val title = com.unuslumen.app.data.smarthome.SonosApi.elementText(posReply.body, "TrackURI")
            .orEmpty()
            .ifBlank { track }

        val status = buildString {
            append("\"${speaker.name}\" is ")
            append(com.unuslumen.app.data.smarthome.SonosApi.describeTransportState(rawState))
            if (title.isNotBlank()) append(", playing $title") else append(", with nothing queued")
            append(".")
        }
        val r = SonosStatusResult(success = true, status = status, error = null)
        ToolExecutionResult.success(r, json.encodeToString(SonosStatusResult.serializer(), r))
    }

    private suspend fun sonosPlayPause(args: Map<String, Any?>): ToolExecutionResult = withContext(Dispatchers.IO) {
        val query = args["speaker"] as? String ?: return@withContext ToolExecutionResult.error("Missing 'speaker'")
        val verb = args["action"] as? String ?: return@withContext ToolExecutionResult.error("Missing 'action'")

        // AN UNKNOWN VERB IS REFUSED RATHER THAN GUESSED AT. The old code passed it straight to the
        // shell, where it became an unknown CLI subcommand and a failure with no explanation.
        val mapping = com.unuslumen.app.data.smarthome.SonosApi.transportAction(verb)
        if (mapping == null) {
            val r = SonosResult(
                success = false,
                action = verb,
                speaker = query,
                error = "\"$verb\" is not a transport action. Use play, pause or stop.",
            )
            return@withContext ToolExecutionResult.success(r, json.encodeToString(SonosResult.serializer(), r))
        }
        val (service, action) = mapping

        val (speaker, resolveError) = resolveSonosSpeaker(query)
        if (speaker == null) {
            val r = SonosResult(success = false, action = verb, speaker = query, error = resolveError)
            return@withContext ToolExecutionResult.success(r, json.encodeToString(SonosResult.serializer(), r))
        }
        val reply = sonosClient.call(
            speaker, service, action, com.unuslumen.app.data.smarthome.SonosApi.transportArguments(verb),
        )
        val ok = reply.failure == null && reply.statusCode in 200..299
        val r = SonosResult(
            success = ok,
            action = action.lowercase(),
            speaker = speaker.name,
            error = if (ok) null else com.unuslumen.app.data.smarthome.SonosApi.failureSentence(
                speaker.host, reply.statusCode, reply.body, reply.failure,
            ),
        )
        ToolExecutionResult.success(r, json.encodeToString(SonosResult.serializer(), r))
    }

    private suspend fun sonosVolume(args: Map<String, Any?>): ToolExecutionResult = withContext(Dispatchers.IO) {
        val query = args["speaker"] as? String ?: return@withContext ToolExecutionResult.error("Missing 'speaker'")
        val volume = (args["volume"] as? Number)?.toInt()
            ?: return@withContext ToolExecutionResult.error("Missing 'volume'")

        val (speaker, resolveError) = resolveSonosSpeaker(query)
        if (speaker == null) {
            val r = SonosResult(success = false, action = "volume", speaker = query, error = resolveError)
            return@withContext ToolExecutionResult.success(r, json.encodeToString(SonosResult.serializer(), r))
        }
        val (service, actionArgs) = com.unuslumen.app.data.smarthome.SonosApi.setVolume(volume)
        val reply = sonosClient.call(speaker, service, "SetVolume", actionArgs)
        val ok = reply.failure == null && reply.statusCode in 200..299
        val r = SonosResult(
            success = ok,
            action = "volume",
            speaker = speaker.name,
            error = if (ok) null else com.unuslumen.app.data.smarthome.SonosApi.failureSentence(
                speaker.host, reply.statusCode, reply.body, reply.failure,
            ),
        )
        ToolExecutionResult.success(r, json.encodeToString(SonosResult.serializer(), r))
    }

    private suspend fun sonosGroup(args: Map<String, Any?>): ToolExecutionResult = withContext(Dispatchers.IO) {
        val action = args["action"] as? String ?: return@withContext ToolExecutionResult.error("Missing 'action'")
        val query = args["speaker"] as? String

        /*
         * GROUPING IS HONESTLY UNSUPPORTED RATHER THAN A LIE. Joining and leaving a zone group on
         * Sonos is done by writing the topology back through `SetZoneAttributes`/`AddZonePlayerTo
         * Group` on services this client does not implement, and pretending a join happened would
         * be worse than saying it cannot be done yet.
         *
         * The old code shelled out to `sonos group join`, which could only fail, and it reported
         * the failure as though the speaker had refused.
         */
        val r = SonosResult(
            success = false,
            action = "group_$action",
            speaker = query,
            error = "Grouping is not implemented yet. Sonos groups are changed by writing the zone " +
                "topology back, which this client does not do. Individual speakers can be " +
                "controlled with sonosPlayPause and sonosVolume, and sonosStatus reports what one " +
                "speaker is doing.",
        )
        ToolExecutionResult.success(r, json.encodeToString(SonosResult.serializer(), r))
    }

    /**
     * List paired Bluetooth devices.
     *
     * THE DEFECT THIS FIXES. A SecurityException was caught and turned into
     * `success = true` with an error string, and the tool fired a raw broadcast asking
     * the UI for a permission dialog mid-conversation. Both are wrong for this batch:
     * the success flag lied about a refusal, and a tool must not interrupt with a
     * dialog. The grant is checked first now, and its sentence — what is missing, why
     * it matters, exactly where to grant it — is the refusal. The paired list is a
     * real read: an empty list here genuinely means nothing is paired.
     */
    private suspend fun bluetoothList(): ToolExecutionResult = withContext(Dispatchers.IO) {
        PermissionGrants.guard(context, TeamThreePermissions.BLUETOOTH_CONNECT)?.let { return@withContext it }

        if (!PermissionGrants.hasBluetooth(context)) {
            val absent = PermissionGrants.guardHardware(
                present = false,
                label = "Bluetooth radio",
                why = "GURU can connect to and talk to a paired Bluetooth device",
            )
            return@withContext absent ?: ToolExecutionResult.error(
                "This device reports Bluetooth support through one API and not another, so the paired " +
                    "list cannot be read."
            )
        }

        try {
            val bluetoothManager = context.getSystemService(android.bluetooth.BluetoothManager::class.java)
            val adapter = bluetoothManager?.adapter
            if (adapter == null) {
                val r = BluetoothDevicesResult(
                    success = false,
                    devices = emptyList(),
                    error = "This device reports Bluetooth support but exposes no adapter, so no paired " +
                        "devices can be listed.",
                )
                return@withContext ToolExecutionResult.success(r, json.encodeToString(BluetoothDevicesResult.serializer(), r))
            }
            if (!adapter.isEnabled) {
                val r = BluetoothDevicesResult(
                    success = false,
                    devices = emptyList(),
                    error = "Bluetooth is switched off on this phone, so the paired list cannot be read " +
                        "until it is turned on; the permission is separate from the switch.",
                )
                return@withContext ToolExecutionResult.success(r, json.encodeToString(BluetoothDevicesResult.serializer(), r))
            }
            val pairedDevices = adapter.bondedDevices ?: emptySet()
            val devices = pairedDevices.map { device ->
                BluetoothDevice(mac = device.address, name = runCatching { device.name }.getOrNull() ?: "Unknown")
            }
            val r = BluetoothDevicesResult(success = true, devices = devices, error = null)
            ToolExecutionResult.success(r, json.encodeToString(BluetoothDevicesResult.serializer(), r))
        } catch (e: SecurityException) {
            // The grant changed underneath us. Re-check so the sentence describes now,
            // rather than reporting a stale state or firing a dialog at the person.
            PermissionGrants.refusal(PermissionGrants.check(context, TeamThreePermissions.BLUETOOTH_CONNECT))
        } catch (e: Exception) {
            val r = BluetoothDevicesResult(success = false, devices = emptyList(), error = "Bluetooth access failed: ${e.message}")
            ToolExecutionResult.success(r, json.encodeToString(BluetoothDevicesResult.serializer(), r))
        }
    }

    /**
     * Ask the app for BLUETOOTH_CONNECT.
     *
     * The only place that string is written. MainActivity listens for it and
     * raises the real Android dialog; every Bluetooth path here reports the
     * denial honestly and fires this, rather than silently reading an empty
     * device list. bluetoothList, bluetoothConnect and bluetoothDisconnect all
     * share it so the three can never drift apart.
     */
    private fun requestBluetoothPermission() {
        try {
            val intent = android.content.Intent(BLUETOOTH_PERMISSION_ACTION)
            intent.setPackage(context.packageName)
            context.sendBroadcast(intent)
        } catch (_: Exception) {
        }
    }

    /**
     * The one shape for "BLUETOOTH_CONNECT was denied".
     *
     * Fires the same broadcast bluetoothList uses, so the dialog lands, and
     * reports the grant problem instead of blaming the device. The [device]
     * parameter is the caller's own argument, echoed back so the model can see
     * which request was refused.
     */
    private fun bluetoothPermissionDenied(action: String, device: String? = null): ToolExecutionResult {
        requestBluetoothPermission()
        val r = BluetoothResult(
            success = false,
            action = action,
            device = device,
            error = "Bluetooth permission denied. A permission request has been sent — grant BLUETOOTH_CONNECT in the dialog, then try again.",
        )
        return ToolExecutionResult.success(r, json.encodeToString(BluetoothResult.serializer(), r))
    }

    /**
     * Resolve a caller-supplied device to a real BluetoothDevice.
     *
     * Accepts a MAC address or a bonded device name, because the tool's
     * parameter description promises both and the old bluetoothctl shell-out
     * honoured neither. Returns null and the reason as a pair so the caller can
     * report which of the two failed rather than a generic error.
     */
    private fun resolveBluetoothDevice(
        adapter: android.bluetooth.BluetoothAdapter,
        query: String,
    ): Pair<android.bluetooth.BluetoothDevice?, String?> {
        val trimmed = query.trim()

        // A MAC address. getRemoteDevice throws IllegalArgumentException on a
        // malformed one, which is the documented contract, so it is caught
        // rather than pre-validated with a regex that could disagree with the
        // platform about what a valid address is.
        if (android.bluetooth.BluetoothAdapter.checkBluetoothAddress(trimmed.uppercase())) {
            return try {
                adapter.getRemoteDevice(trimmed.uppercase()) to null
            } catch (e: IllegalArgumentException) {
                null to "Bluetooth rejected \"$query\" as an address: ${e.message}"
            }
        }

        // A name. Only bonded devices are addressable by name, because an
        // unbonded device has no stable identity until discovery finds it.
        val named = adapter.bondedDevices?.firstOrNull { it.name?.equals(trimmed, ignoreCase = true) == true }
        return when {
            named != null -> named to null
            else -> null to "\"$query\" is neither a valid MAC address nor the name of a paired device. Run bluetoothList for the addresses and names this phone already knows."
        }
    }

    /**
     * Connect to a device.
     *
     * Replaces `bluetoothctl connect`, which is BlueZ and does not exist on
     * Android at all, so the shell-out could only ever fail and reported that
     * failure as though the platform had refused.
     *
     * Three different things answer to "connect" on Android and each does the
     * one the request implies rather than pretending they are the same:
     *
     *  1. An unpaired device cannot be connected. Pairing has to happen first,
     *     and createBond is the documented call for it. It is asynchronous: the
     *     system raises its own pairing UI, so this reports a pairing request
     *     and never a connection it has not seen.
     *  2. A paired device is connected through its profiles. BluetoothDevice
     *     .connect() does exactly that and became public in API 37; it pages the
     *     device and brings up every enabled profile. It is still asynchronous,
     *     and the system reports the outcome on each profile's
     *     ACTION_CONNECTION_STATE_CHANGED.
     *  3. Below API 37 there is no public call at any privilege: A2dp.connect and
     *     Headset.connect are @hide. Rather than inventing one, the result says
     *     so and opens the system's Bluetooth screen for a one-tap connect.
     *
     * The result names which of those ran, and never reports "connected" for a
     * request that was merely dispatched.
     */
    private suspend fun bluetoothConnect(args: Map<String, Any?>): ToolExecutionResult = withContext(Dispatchers.IO) {
        val target = args["device"] as? String ?: return@withContext ToolExecutionResult.error("Missing 'device'")
        val action = "connect"
        try {
            val manager = context.getSystemService(android.bluetooth.BluetoothManager::class.java)
            val adapter = manager?.adapter
            if (adapter == null) {
                return@withContext bluetoothFailure(action, target, "This device has no Bluetooth adapter.")
            }
            if (!adapter.isEnabled) {
                return@withContext bluetoothFailure(action, target, "Bluetooth is switched off. Turn it on and try again.")
            }

            val (device, resolveError) = resolveBluetoothDevice(adapter, target)
            if (device == null) {
                return@withContext bluetoothFailure(action, target, resolveError ?: "Could not resolve \"$target\" to a Bluetooth device.")
            }

            val label = device.name ?: device.address

            // Already connected? Report the state rather than repeating the work.
            val alreadyConnected = isDeviceConnected(manager, device)
            if (alreadyConnected) {
                val r = BluetoothResult(
                    success = true,
                    action = "already_connected",
                    device = device.address,
                    detail = "\"$label\" was already connected to this phone, so nothing was changed.",
                )
                return@withContext ToolExecutionResult.success(r, json.encodeToString(BluetoothResult.serializer(), r))
            }

            if (device.bondState != android.bluetooth.BluetoothDevice.BOND_BONDED) {
                val started = device.createBond()
                val r = BluetoothResult(
                    success = started,
                    action = "pairing_requested",
                    device = device.address,
                    error = if (started) null else "The system refused to start pairing with \"$label\".",
                    detail = if (started) {
                        "Pairing has started and Android is showing its own confirmation. A device cannot be connected until it is paired, so confirm on the phone and call bluetoothConnect again."
                    } else null,
                )
                return@withContext ToolExecutionResult.success(r, json.encodeToString(BluetoothResult.serializer(), r))
            }

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.CINNAMON_BUN) {
                // connect() returns a BluetoothStatusCodes value, not a boolean.
                val status = device.connect()
                val accepted = status == android.bluetooth.BluetoothStatusCodes.SUCCESS
                val r = BluetoothResult(
                    success = accepted,
                    action = if (accepted) "connect_requested" else "connect_refused",
                    device = device.address,
                    error = if (accepted) null else bluetoothStatusReason(status, label, connecting = true),
                    detail = if (accepted) {
                        "\"$label\" is paired and Android has been asked to connect its profiles. The connection completes asynchronously — check the device before assuming it is up."
                    } else null,
                )
                return@withContext ToolExecutionResult.success(r, json.encodeToString(BluetoothResult.serializer(), r))
            }

            // No public connect on this Android version. Say so, and put the user
            // one tap away from doing it themselves rather than pretending.
            val settingsOpened = openBluetoothSettings()
            val r = BluetoothResult(
                success = false,
                action = "manual_required",
                device = device.address,
                error = "This Android version (SDK ${android.os.Build.VERSION.SDK_INT}) exposes no public call to start a Bluetooth connection from an app.",
                detail = if (settingsOpened) {
                    "\"$label\" is paired. GURU opened Settings > Connected devices; tap the device there to connect it. Nothing was connected by this call."
                } else {
                    "\"$label\" is paired. Open Settings > Connected devices and tap the device to connect it. Nothing was connected by this call."
                },
            )
            ToolExecutionResult.success(r, json.encodeToString(BluetoothResult.serializer(), r))
        } catch (e: SecurityException) {
            bluetoothPermissionDenied(action, target)
        } catch (e: Exception) {
            bluetoothFailure(action, target, "Connecting failed: ${e.message}")
        }
    }

    /**
     * Is this device connected on any profile GURU can see?
     *
     * Profile connection state is the answer that has existed since API 18 and
     * covers every device this tool deals with: audio hardware on A2DP or
     * HEADSET, and peripherals on GATT. BluetoothDevice.isConnected(int) would
     * also answer at transport level, but it only arrived in 36.1 and calling it
     * on an earlier build would throw, so it is deliberately not used rather
     * than guarded by a version check that would be wrong on 36.0.
     *
     * A SecurityException here means BLUETOOTH_CONNECT is missing; it is allowed
     * to propagate so the caller's catch reports the grant problem instead of
     * misreading it as "not connected".
     */
    private fun isDeviceConnected(
        manager: android.bluetooth.BluetoothManager?,
        device: android.bluetooth.BluetoothDevice,
    ): Boolean = listOf(
        android.bluetooth.BluetoothProfile.GATT,
        android.bluetooth.BluetoothProfile.A2DP,
        android.bluetooth.BluetoothProfile.HEADSET,
    ).any { profile ->
        manager?.getConnectionState(device, profile) ==
            android.bluetooth.BluetoothProfile.STATE_CONNECTED
    }

    /**
     * Turn a BluetoothStatusCodes value into a sentence the caller can act on.
     *
     * [connecting] only changes the wording, because the same status codes come
     * back from both connect() and disconnect() and "was not connected" would be
     * the wrong tense for a disconnect attempt.
     */
    private fun bluetoothStatusReason(status: Int, label: String, connecting: Boolean): String {
        val outcome = if (connecting) "was not connected" else "was not disconnected"
        return when (status) {
            android.bluetooth.BluetoothStatusCodes.ERROR_BLUETOOTH_NOT_ENABLED ->
                "Bluetooth is switched off, so \"$label\" $outcome."
            android.bluetooth.BluetoothStatusCodes.ERROR_BLUETOOTH_NOT_ALLOWED ->
                "Android blocked the request for \"$label\" and a device policy may be restricting it, so it $outcome."
            android.bluetooth.BluetoothStatusCodes.ERROR_MISSING_BLUETOOTH_CONNECT_PERMISSION ->
                "BLUETOOTH_CONNECT was not granted, so \"$label\" $outcome."
            android.bluetooth.BluetoothStatusCodes.ERROR_DEVICE_NOT_BONDED ->
                "\"$label\" is not paired, and pairing has to come first."
            else -> "Android refused the request for \"$label\" (status $status), so it $outcome."
        }
    }

    /**
     * Disconnect from a device.
     *
     * Replaces `bluetoothctl disconnect`, which is BlueZ and absent on Android.
     *
     * THE HONEST ANSWER IS NOT "IT WORKED". For a paired classic device Android
     * has no general force-disconnect; the pairing is what makes the device come
     * back. Two real paths exist and the tool uses whichever applies:
     *
     *  - BluetoothDevice.disconnect(), public since API 37, asks the system to
     *    bring the device's profiles down. It is asynchronous.
     *  - Below API 37 nothing public can do even that, and removeBond is NOT a
     *    substitute: it deletes the pairing, which is a different and
     *    destructive action the caller did not ask for, so it is never run.
     *
     * When neither path can act the result says exactly that, names what is
     * possible (connecting again from the device side, cancelling an in-flight
     * pairing, or unpairing), and reports that nothing was done.
     */
    private suspend fun bluetoothDisconnect(args: Map<String, Any?>): ToolExecutionResult = withContext(Dispatchers.IO) {
        val target = args["device"] as? String ?: return@withContext ToolExecutionResult.error("Missing 'device'")
        val action = "disconnect"
        try {
            val manager = context.getSystemService(android.bluetooth.BluetoothManager::class.java)
            val adapter = manager?.adapter
            if (adapter == null) {
                return@withContext bluetoothFailure(action, target, "This device has no Bluetooth adapter.")
            }
            if (!adapter.isEnabled) {
                return@withContext bluetoothFailure(action, target, "Bluetooth is switched off, so nothing was connected to disconnect.")
            }

            val (device, resolveError) = resolveBluetoothDevice(adapter, target)
            if (device == null) {
                return@withContext bluetoothFailure(action, target, resolveError ?: "Could not resolve \"$target\" to a Bluetooth device.")
            }

            val label = device.name ?: device.address
            val connected = isDeviceConnected(manager, device)

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.CINNAMON_BUN) {
                val status = device.disconnect()
                val accepted = status == android.bluetooth.BluetoothStatusCodes.SUCCESS
                val r = BluetoothResult(
                    success = accepted,
                    action = if (accepted) "disconnect_requested" else "disconnect_refused",
                    device = device.address,
                    error = if (accepted) null else bluetoothStatusReason(status, label, connecting = false),
                    detail = if (accepted) {
                        "Android has been asked to bring \"$label\"'s profiles down. The disconnection completes asynchronously — check the device before assuming it is gone."
                    } else null,
                )
                return@withContext ToolExecutionResult.success(r, json.encodeToString(BluetoothResult.serializer(), r))
            }

            val why = if (connected) {
                "nothing in this Android version (SDK ${android.os.Build.VERSION.SDK_INT}) can force-disconnect a paired device from an app"
            } else {
                "\"$label\" does not appear to be connected right now, and this Android version (SDK ${android.os.Build.VERSION.SDK_INT}) exposes no call to disconnect it anyway"
            }
            val options = buildString {
                append("What is possible: ")
                if (device.bondState == android.bluetooth.BluetoothDevice.BOND_BONDING) {
                    append("cancel the pairing that is in progress; ")
                }
                append("disconnect from the device itself; ")
                append("or unpair, which removes the pairing entirely and is a different action from disconnecting.")
            }
            val r = BluetoothResult(
                success = false,
                action = "disconnect_unavailable",
                device = device.address,
                error = "Cannot disconnect \"$label\": $why.",
                detail = options,
            )
            ToolExecutionResult.success(r, json.encodeToString(BluetoothResult.serializer(), r))
        } catch (e: SecurityException) {
            bluetoothPermissionDenied(action, target)
        } catch (e: Exception) {
            bluetoothFailure(action, target, "Disconnecting failed: ${e.message}")
        }
    }

    /**
     * Open the system Bluetooth screen so the user can finish a step GURU cannot.
     *
     * Returns whether the screen actually opened, because the caller's sentence
     * tells the user to look at it and must not send them to a screen that never
     * appeared.
     */
    private fun openBluetoothSettings(): Boolean = try {
        val intent = android.content.Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS)
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        true
    } catch (e: Exception) {
        android.util.Log.w("guru", "Could not open Bluetooth settings: ${e.message}")
        false
    }

    /** A failure result for either Bluetooth action, in one shape. */
    private fun bluetoothFailure(action: String, target: String, reason: String): ToolExecutionResult {
        val r = BluetoothResult(success = false, action = action, device = target, error = reason)
        return ToolExecutionResult.success(r, json.encodeToString(BluetoothResult.serializer(), r))
    }

    private suspend fun networkScan(): ToolExecutionResult = withContext(Dispatchers.IO) {
        try {
            val arpResult = shellExecutor.execute("cat /proc/net/arp 2>/dev/null")
            if (arpResult.success && arpResult.stdout.isNotBlank()) {
                val devices = parseNetworkDevices(arpResult.stdout)
                if (devices.isNotEmpty()) { val r = NetworkScanResult(success = true, devices = devices, error = null); return@withContext ToolExecutionResult.success(r, json.encodeToString(NetworkScanResult.serializer(), r)) }
            }
            val neighResult = shellExecutor.execute("ip neigh show 2>/dev/null")
            if (neighResult.success && neighResult.stdout.isNotBlank()) {
                val devices = parseNetworkDevices(neighResult.stdout)
                if (devices.isNotEmpty()) { val r = NetworkScanResult(success = true, devices = devices, error = null); return@withContext ToolExecutionResult.success(r, json.encodeToString(NetworkScanResult.serializer(), r)) }
            }
            val arpAResult = shellExecutor.execute("arp -a 2>/dev/null")
            if (arpAResult.success && arpAResult.stdout.isNotBlank()) {
                val devices = parseNetworkDevices(arpAResult.stdout)
                if (devices.isNotEmpty()) { val r = NetworkScanResult(success = true, devices = devices, error = null); return@withContext ToolExecutionResult.success(r, json.encodeToString(NetworkScanResult.serializer(), r)) }
            }
            try {
                /*
                 * THE FALLBACK READS THE INTERFACE, NOT dhcpInfo.
                 *
                 * Issue 34: this branch produced "Subnet: 192.168.1.179/0.0.0.0".
                 * Two faults together. dhcpInfo.ipAddress and dhcpInfo.netmask are
                 * deprecated and return ZERO on modern Android, so the mask was
                 * always 0.0.0.0; and intToIp read the low byte as the first octet
                 * while Android packs network order the other way, so even a real
                 * value came out reversed.
                 *
                 * `ip -4 addr` is authoritative, needs no permission, and cannot be
                 * deprecated. The address, the prefix and the network all come from
                 * there, so the reported subnet is the one the device is on.
                 */
                val lan = ArpDiscoveryManager(context).getInterfaceAddress()
                val devices = mutableListOf<NetworkDevice>()
                if (lan != null) {
                    devices.add(
                        NetworkDevice(
                            ip = lan.ip,
                            mac = "",
                            name = "This device (${lan.network})",
                        ),
                    )
                }
                // Gateway from the routing table, which is the one address that
                // actually matters for a scan and is not deprecated.
                val gateway = runCatching {
                    val p = ProcessBuilder("sh", "-c", "ip route 2>/dev/null | grep default").start()
                    val out = p.inputStream.bufferedReader().readText()
                    p.waitFor()
                    Regex("""default via (\d+\.\d+\.\d+\.\d+)""").find(out)?.groupValues?.get(1)
                }.getOrNull()
                if (gateway != null && devices.none { it.ip == gateway }) {
                    devices.add(NetworkDevice(ip = gateway, mac = "", name = "Gateway/Router"))
                }
                if (devices.isNotEmpty()) {
                    val subnetLabel = lan?.network ?: "unknown"
                    val r = NetworkScanResult(
                        success = true,
                        devices = devices,
                        error = "Partial scan — the ARP table was empty, so only the " +
                            "device and its gateway are listed. Subnet: $subnetLabel",
                    )
                    return@withContext ToolExecutionResult.success(r, json.encodeToString(NetworkScanResult.serializer(), r))
                }
            } catch (_: SecurityException) {} catch (_: Exception) {}
            val r = NetworkScanResult(success = false, devices = emptyList(), error = "No network devices found. The ARP table is inaccessible on this Android version. Grant location permission for WiFi-based scanning.")
            ToolExecutionResult.success(r, json.encodeToString(NetworkScanResult.serializer(), r))
        } catch (e: Exception) {
            val r = NetworkScanResult(success = false, devices = emptyList(), error = "Network scan failed: ${e.message}")
            ToolExecutionResult.success(r, json.encodeToString(NetworkScanResult.serializer(), r))
        }
    }

    private fun intToIp(i: Int): String = "${i and 0xFF}.${i shr 8 and 0xFF}.${i shr 16 and 0xFF}.${i shr 24 and 0xFF}"

    private suspend fun networkPing(args: Map<String, Any?>): ToolExecutionResult = withContext(Dispatchers.IO) {
        val host = args["host"] as? String ?: return@withContext ToolExecutionResult.error("Missing 'host'")
        val count = (args["count"] as? Number)?.toInt() ?: 3
        val result = shellExecutor.execute("ping -c $count \"$host\" 2>/dev/null")
        val success = result.stdout.contains("bytes from") || result.stdout.contains("time=")
        val r = NetworkPingResult(success = success, host = host, output = result.stdout, error = if (!success) "Ping failed: ${result.stderr}" else null)
        ToolExecutionResult.success(r, json.encodeToString(NetworkPingResult.serializer(), r))
    }

    /*
     * THE CLI OUTPUT PARSERS ARE DELETED, not kept for a caller that no longer exists. Everything
     * that read `openhue` or `sonos` stdout is gone, because those programs are not shipped and
     * cannot be: keeping a parser for their output would suggest the path still works.
     */

    private fun parseNetworkDevices(output: String): List<NetworkDevice> {
        return output.lines().filter { it.isNotBlank() && it.contains(".") }.take(50).map { line ->
            val ip = Regex("\\d+\\.\\d+\\.\\d+\\.\\d+").find(line)?.value ?: ""
            val mac = Regex("([0-9a-fA-F]{2}:){5}[0-9a-fA-F]{2}").find(line)?.value ?: ""
            NetworkDevice(ip = ip, mac = mac, name = line.trim())
        }
    }

    private fun extractNumber(text: String, key: String): Int? {
        return Regex("$key[:\\s]*(\\d+)", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)?.toIntOrNull()
    }
}