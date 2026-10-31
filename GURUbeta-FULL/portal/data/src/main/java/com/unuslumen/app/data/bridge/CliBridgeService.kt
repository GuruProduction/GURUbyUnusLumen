// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.bridge

import android.content.Context
import android.util.Log
import com.unuslumen.app.data.tor.TorManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The CLI bridge — a live chat channel between GURU on the phone and a CLI agent
 * anywhere else. Issue #176.
 *
 * TWO TRANSPORTS, ONE PROTOCOL.
 *
 *   LAN     a plain socket listener on the local network. Instant, needs both
 *           ends on the same WiFi, and is the path that always works.
 *   Onion   Tor's own ADD_ONION through the control connection the app already
 *           holds. Reachable from anywhere, no port forwarding, no public
 *           address, and nobody in the middle.
 *
 * The onion listener needs no torrc edit and no new dependency: `TorManager`
 * already exposes the `TorControlConnection`, and jtorctl provides `addOnion`.
 * Both transports speak the frames in [BridgeProtocol], so the client is written
 * once.
 *
 * WHAT IT DELIBERATELY DOES NOT DO. No third-party relay, no cloud, no public
 * server. Messages exist in this process and in the room's data space. Nothing
 * about the channel is logged: the token is never formatted, only masked.
 */
class CliBridgeService(
    private val context: Context,
    private val torManager: TorManager,
) {

    /** What the room shows about the channel's state. */
    data class BridgeState(
        val listening: Boolean = false,
        val lanAddress: String? = null,
        val lanPort: Int = 0,
        val onionAddress: String? = null,
        val onionError: String? = null,
        val connectedAgents: List<String> = emptyList(),
        val paired: Boolean = false,
    )

    private val _state = MutableStateFlow(BridgeState())
    val state: StateFlow<BridgeState> = _state.asStateFlow()

    /** Lines arriving from any connected agent, for the room to render. */
    private val _incoming = MutableStateFlow<List<BridgeFrame.Message>>(emptyList())
    val incoming: StateFlow<List<BridgeFrame.Message>> = _incoming.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var serverJob: Job? = null
    private var serverSocket: ServerSocket? = null
    private val clients = CopyOnWriteArrayList<Client>()

    private class Client(
        val agent: String,
        val socket: Socket,
        val writer: PrintWriter,
        @Volatile var alive: Boolean = true,
    )

    // ------------------------------------------------------------------
    // Pairing
    // ------------------------------------------------------------------

    /**
     * The shared secret, generated once on first pair.
     *
     * Held in preferences rather than on the filesystem because it is a
     * credential, and this app already treats its data dir as readable by the
     * app only. The room shows it once as a QR; the CLI is given it out of band.
     *
     * 32 bytes from SecureRandom, base64url, so it cannot be guessed and cannot
     * be confused with a shorter human-typed code.
     */
    fun ensureToken(): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY_TOKEN, null)?.let { if (it.length >= 43) return it }
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        val token = android.util.Base64.encodeToString(
            bytes,
            android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP,
        )
        prefs.edit().putString(KEY_TOKEN, token).apply()
        return token
    }

    /** Forget the secret so the next pair issues a new one. */
    fun resetToken() {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_TOKEN).apply()
        disconnectAll()
        _state.value = _state.value.copy(paired = false)
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    /**
     * Start both transports.
     *
     * The LAN listener binds first because it either works or fails immediately,
     * and its failure is a real answer (no network, port taken). The onion is
     * requested after, because Tor may still be starting and that is a state the
     * room should show rather than a reason to refuse the whole channel.
     */
    suspend fun start() {
        if (serverJob?.isActive == true) return
        val token = ensureToken()

        val lan = startLanListener(token)
        // The onion maps to the SAME local listener, so one accept loop serves
        // both transports and the protocol cannot diverge between them.
        val onion = requestOnion(lan.port)

        _state.value = _state.value.copy(
            listening = lan.port > 0,
            lanAddress = lan.address,
            lanPort = lan.port,
            onionAddress = onion.address,
            onionError = onion.error,
            paired = true,
        )
        Log.d(
            TAG,
            "CliBridge: listening=${lan.address}:${lan.port} onion=${onion.address ?: "none"} " +
                "onionError=${onion.error ?: "none"}",
        )
    }

    private data class LanResult(val address: String?, val port: Int)

    private data class OnionResult(val address: String?, val error: String?)

    private fun startLanListener(token: String): LanResult {
        return try {
            val socket = ServerSocket(0, 8, InetAddress.getByName("0.0.0.0"))
            serverSocket = socket
            val port = socket.localPort
            serverJob = scope.launch {
                while (isActive) {
                    val client = try {
                        socket.accept()
                    } catch (e: Exception) {
                        break // socket closed
                    }
                    launch { handleClient(client, token) }
                }
            }
            LanResult(localIpAddress(), port)
        } catch (e: Exception) {
            Log.w(TAG, "CliBridge: LAN listener failed: ${e.message}")
            LanResult(null, 0)
        }
    }

    /**
     * Ask Tor to publish an onion service pointing at the LAN listener.
     *
     * ADD_ONION with no key asks Tor to generate one and, with the DiscardPK
     * flag, not to hand it back, which means the address is stable for this
     * process only. That is the right default: a persistent onion identity is a
     * decision with privacy consequences and belongs to the user, not to a
     * background start.
     */
    private fun requestOnion(localPort: Int): OnionResult {
        if (localPort <= 0) return OnionResult(null, "the LAN listener is not running, so there is nothing to publish")
        val connection = torManager.torControlConnection()
            ?: return OnionResult(null, "Tor is not running. Bring Tor up, then start the bridge again.")
        return try {
            val ports = mapOf(localPort to "127.0.0.1:$localPort")
            val response = connection.addOnion(null, ports, emptyList()) ?: return OnionResult(null, "Tor returned no onion")
            val serviceId = response["ServiceID"]
            if (serviceId.isNullOrBlank()) {
                OnionResult(null, "Tor accepted the request but returned no ServiceID")
            } else {
                OnionResult("$serviceId.onion", null)
            }
        } catch (e: Exception) {
            OnionResult(null, "Tor refused the onion service: ${e.message?.take(160)}")
        }
    }

    suspend fun stop() {
        serverJob?.cancel()
        runCatching { serverSocket?.close() }
        serverSocket = null
        disconnectAll()
        _state.value = _state.value.copy(listening = false, lanPort = 0, onionAddress = null, connectedAgents = emptyList())
    }

    private fun disconnectAll() {
        clients.forEach { runCatching { it.socket.close() } }
        clients.clear()
        _state.value = _state.value.copy(connectedAgents = emptyList())
    }

    // ------------------------------------------------------------------
    // One connected peer
    // ------------------------------------------------------------------

    private suspend fun handleClient(socket: Socket, expectedToken: String) {
        var agentName: String? = null
        try {
            socket.soTimeout = 0
            val reader = BufferedReader(InputStreamReader(socket.inputStream))
            val writer = PrintWriter(socket.outputStream, true)

            // The first frame must be a hello, and it must satisfy version then
            // token. Nothing else is read until it does.
            val firstLine = reader.readLine() ?: return
            when (val parsed = parseFrame(firstLine)) {
                is ParseResult.Bad -> {
                    writer.print(encodeFrame(BridgeFrame.Error(parsed.code, parsed.reason)))
                    writer.flush()
                    return
                }
                is ParseResult.Ok -> {
                    val rejection = authoriseHello(parsed.frame, expectedToken)
                    if (rejection != null) {
                        writer.print(encodeFrame(rejection))
                        writer.flush()
                        Log.d(TAG, "CliBridge: rejected a peer: ${rejection.code}")
                        return
                    }
                    agentName = (parsed.frame as BridgeFrame.Hello).agent
                }
            }

            val client = Client(agentName!!, socket, writer)
            clients.add(client)
            _state.value = _state.value.copy(connectedAgents = clients.filter { it.alive }.map { it.agent })

            writer.print(encodeFrame(BridgeFrame.Welcome(guru = instanceName(), room = ROOM_NAME)))
            writer.flush()
            Log.d(TAG, "CliBridge: agent '${agentName}' connected")

            while (client.alive) {
                val line = reader.readLine() ?: break
                when (val parsed = parseFrame(line)) {
                    is ParseResult.Bad -> {
                        writer.print(encodeFrame(BridgeFrame.Error(parsed.code, parsed.reason)))
                        writer.flush()
                    }
                    is ParseResult.Ok -> handleFrame(client, parsed.frame)
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "CliBridge: peer closed: ${e.message?.take(120)}")
        } finally {
            clients.firstOrNull { it.agent == agentName }?.let {
                it.alive = false
                clients.remove(it)
            }
            _state.value = _state.value.copy(connectedAgents = clients.map { it.agent })
            runCatching { socket.close() }
        }
    }

    private fun handleFrame(client: Client, frame: BridgeFrame) {
        when (frame) {
            is BridgeFrame.Message -> {
                // A message from the agent lands in the room's transcript.
                _incoming.value = (_incoming.value + frame).takeLast(MAX_TRANSCRIPT)
                client.writer.print(encodeFrame(BridgeFrame.Ack(java.util.UUID.randomUUID().toString())))
                client.writer.flush()
            }
            is BridgeFrame.Status -> {
                // The status reply is filled by the room's owner; here it reports
                // what the bridge itself knows, which is the connection set.
                client.writer.print(
                    encodeFrame(
                        BridgeFrame.Status(
                            request = false,
                            inflight = _state.value.connectedAgents,
                            queued = emptyList(),
                            blocked = listOfNotNull(_state.value.onionError),
                        ),
                    ),
                )
                client.writer.flush()
            }
            else -> {
                client.writer.print(
                    encodeFrame(BridgeFrame.Error(BridgeFrame.CODE_BAD_FRAME, "unexpected frame from a client")),
                )
                client.writer.flush()
            }
        }
    }

    /** Send text from GURU to every connected agent. */
    fun broadcast(text: String): Int {
        val frame = BridgeFrame.Message(text = text, from = "guru", at = System.currentTimeMillis())
        val payload = encodeFrame(frame)
        var sent = 0
        clients.filter { it.alive }.forEach {
            runCatching {
                it.writer.print(payload)
                it.writer.flush()
                sent++
            }
        }
        return sent
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /**
     * The device's LAN address, or null when it has none.
     *
     * Read from the interface list rather than from a WiFi API, because the same
     * question must also answer correctly over a hotspot and over ethernet on a
     * tablet. Loopback is skipped: it is not reachable from another machine.
     */
    fun localIpAddress(): String? {
        return runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .asSequence()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList().asSequence() }
                .filterIsInstance<InetAddress>()
                .firstOrNull { !it.isLoopbackAddress && it is java.net.Inet4Address }
                ?.hostAddress
        }.getOrNull()
    }

    private fun instanceName(): String =
        android.provider.Settings.Global.getString(context.contentResolver, "device_name")
            ?: android.os.Build.MODEL
            ?: "GURU"

    companion object {
        private const val TAG = "guru"
        private const val PREFS = "cli_bridge"
        private const val KEY_TOKEN = "pair_token"
        const val ROOM_NAME = "cli_bridge"
        const val MAX_TRANSCRIPT = 500
    }
}
