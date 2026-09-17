package com.unuslumen.app.data.discovery

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

class TcpSocketManager {
    companion object {
        private const val TAG = "TcpSocketManager"
        private const val DEFAULT_TIMEOUT_MS = 10000
        private const val DEFAULT_RECEIVE_TIMEOUT_MS = 5000
    }

    @Serializable
    data class TcpConnectResult(
        val success: Boolean,
        val connectionId: String,
        val remoteAddress: String,
        val remotePort: Int,
        val localAddress: String,
        val localPort: Int,
        val error: String?
    )

    @Serializable
    data class TcpSendResult(
        val success: Boolean,
        val bytesSent: Int,
        val error: String?
    )

    @Serializable
    data class TcpReceiveResult(
        val success: Boolean,
        val data: String,
        val bytesReceived: Int,
        val timedOut: Boolean,
        val error: String?
    )

    @Serializable
    data class TcpDisconnectResult(
        val success: Boolean,
        val connectionId: String,
        val error: String?
    )

    private val connections = ConcurrentHashMap<String, SocketConnection>()

    data class SocketConnection(
        val socket: Socket,
        val input: InputStream,
        val output: OutputStream
    )

    suspend fun connect(host: String, port: Int, timeoutMs: Int = DEFAULT_TIMEOUT_MS): TcpConnectResult = withContext(Dispatchers.IO) {
        try {
            val socket = Socket()
            socket.connect(InetSocketAddress(host, port), timeoutMs)
            socket.soTimeout = 0
            socket.keepAlive = true
            socket.tcpNoDelay = true

            val input = socket.getInputStream()
            val output = socket.getOutputStream()
            val connectionId = "tcp_${System.currentTimeMillis()}"
            connections[connectionId] = SocketConnection(socket, input, output)

            val localAddr = socket.localAddress?.hostAddress ?: ""
            val localPort = socket.localPort

            Log.d(TAG, "Connected to $host:$port (id=$connectionId)")

            TcpConnectResult(
                success = true,
                connectionId = connectionId,
                remoteAddress = host,
                remotePort = port,
                localAddress = localAddr,
                localPort = localPort,
                error = null
            )
        } catch (e: Exception) {
            Log.e(TAG, "Connect failed: ${e.message}")
            TcpConnectResult(
                success = false,
                connectionId = "",
                remoteAddress = host,
                remotePort = port,
                localAddress = "",
                localPort = 0,
                error = "Connection failed: ${e.message}"
            )
        }
    }

    suspend fun send(connectionId: String, data: String, hex: Boolean = false): TcpSendResult = withContext(Dispatchers.IO) {
        val conn = connections[connectionId]
        if (conn == null) {
            return@withContext TcpSendResult(false, 0, "Not connected: $connectionId")
        }
        try {
            val bytes = if (hex) {
                data.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            } else {
                data.toByteArray(Charsets.UTF_8)
            }
            conn.output.write(bytes)
            conn.output.flush()
            Log.d(TAG, "Sent ${bytes.size} bytes to $connectionId")
            TcpSendResult(true, bytes.size, null)
        } catch (e: Exception) {
            Log.e(TAG, "Send failed: ${e.message}")
            TcpSendResult(false, 0, "Send failed: ${e.message}")
        }
    }

    suspend fun receive(connectionId: String, timeoutMs: Int = DEFAULT_RECEIVE_TIMEOUT_MS, maxBytes: Int = 65536): TcpReceiveResult = withContext(Dispatchers.IO) {
        val conn = connections[connectionId]
        if (conn == null) {
            return@withContext TcpReceiveResult(false, "", 0, false, "Not connected: $connectionId")
        }
        try {
            conn.socket.soTimeout = timeoutMs
            val buffer = ByteArray(maxBytes)
            val read = conn.input.read(buffer)
            if (read == -1) {
                return@withContext TcpReceiveResult(false, "", 0, false, "Connection closed by remote")
            }
            val data = String(buffer, 0, read, Charsets.UTF_8)
            Log.d(TAG, "Received $read bytes from $connectionId")
            TcpReceiveResult(true, data, read, false, null)
        } catch (e: java.net.SocketTimeoutException) {
            Log.d(TAG, "Receive timed out after ${timeoutMs}ms")
            TcpReceiveResult(true, "", 0, true, null)
        } catch (e: Exception) {
            Log.e(TAG, "Receive failed: ${e.message}")
            TcpReceiveResult(false, "", 0, false, "Receive failed: ${e.message}")
        }
    }

    suspend fun sendAndReceive(connectionId: String, data: String, timeoutMs: Int = DEFAULT_RECEIVE_TIMEOUT_MS, maxBytes: Int = 65536, hex: Boolean = false): String = withContext(Dispatchers.IO) {
        val sendResult = send(connectionId, data, hex)
        if (!sendResult.success) {
            return@withContext "Send failed: ${sendResult.error}"
        }
        val recvResult = receive(connectionId, timeoutMs, maxBytes)
        if (!recvResult.success) {
            return@withContext "Receive failed: ${recvResult.error}"
        }
        if (recvResult.timedOut) {
            return@withContext "Receive timed out after ${timeoutMs}ms"
        }
        recvResult.data
    }

    fun disconnect(connectionId: String): TcpDisconnectResult {
        val conn = connections.remove(connectionId)
        if (conn == null) {
            return TcpDisconnectResult(false, connectionId, "Not connected: $connectionId")
        }
        try {
            conn.input.close()
            conn.output.close()
            conn.socket.close()
            Log.d(TAG, "Disconnected $connectionId")
            return TcpDisconnectResult(true, connectionId, null)
        } catch (e: Exception) {
            Log.e(TAG, "Disconnect failed: ${e.message}")
            return TcpDisconnectResult(false, connectionId, "Disconnect failed: ${e.message}")
        }
    }

    fun isConnected(connectionId: String): Boolean {
        val conn = connections[connectionId]
        return conn != null && conn.socket.isConnected && !conn.socket.isClosed
    }

    fun listConnections(): List<String> {
        return connections.keys.toList()
    }
}