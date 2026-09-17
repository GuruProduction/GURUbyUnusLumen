package com.unuslumen.app.data.tools

import android.content.Context
import android.util.Log
import com.unuslumen.app.data.tools.registry.ToolExecutionResult
import com.unuslumen.app.data.tools.registry.ToolExecutor
import com.unuslumen.app.util.shell.ShellExecutor
import com.unuslumen.app.util.shell.ToyboxProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

class SshToolExecutor(
    private val context: Context,
    private val fileAccessGuard: FileAccessGuard
) : ToolExecutor {
    private val json = Json { ignoreUnknownKeys = true }
    private val shellExecutor by lazy { ShellExecutor(context) }
    private val toyboxProvider by lazy { ToyboxProvider(context) }
    private val connections = mutableMapOf<String, SshConnection>()
    private var sshChecked = false
    private var sshPath: String? = null

    private data class SshConnection(val host: String, val port: Int, val username: String, val password: String, val keyPath: String)

    private suspend fun ensureReady(): String? = withContext(Dispatchers.IO) {
        if (!sshChecked) {
            val symlinkPath = toyboxProvider.getSymlinkDir() + "/ssh"
            val symlinkFile = File(symlinkPath)
            if (symlinkFile.exists() && symlinkFile.canExecute()) sshPath = symlinkPath
            else { val soPath = toyboxProvider.getPath("ssh"); val soFile = File(soPath)
                if (soFile.exists() && soFile.canExecute()) sshPath = soPath
                else { val r = shellExecutor.execute("which ssh"); if (r.success && r.stdout.isNotBlank()) sshPath = r.stdout.trim() } }
            sshChecked = true; Log.d("guru", "SshToolExecutor: sshPath=$sshPath")
        }
        if (sshPath == null) return@withContext "SSH is not available on this device."
        null
    }

    override suspend fun execute(toolName: String, args: Map<String, Any?>): ToolExecutionResult = when (toolName) {
        SshToolDefinitions.SSH_CONNECT -> sshConnect(args)
        SshToolDefinitions.SSH_EXEC -> sshExec(args)
        SshToolDefinitions.SSH_DISCONNECT -> sshDisconnect(args)
        else -> ToolExecutionResult.error("Unknown tool: $toolName")
    }

    private suspend fun sshConnect(args: Map<String, Any?>): ToolExecutionResult = withContext(Dispatchers.IO) {
        val host = args["host"] as? String ?: return@withContext ToolExecutionResult.error("Missing 'host'")
        val port = (args["port"] as? Number)?.toInt() ?: 22
        val username = args["username"] as? String ?: return@withContext ToolExecutionResult.error("Missing 'username'")
        val password = args["password"] as? String ?: ""
        val keyPath = args["keyPath"] as? String ?: ""
        val err = ensureReady(); if (err != null) return@withContext ToolExecutionResult.error(err)
        val ssh = sshPath ?: return@withContext ToolExecutionResult.error("SSH binary not found")
        try {
            val connId = "ssh_${System.currentTimeMillis()}"
            val cmd = buildSshCommand(ssh, host, port, username, password, keyPath, "echo SSH_CONNECTED")
            val r = shellExecutor.execute(cmd)
            if (r.success && r.stdout.contains("SSH_CONNECTED")) {
                connections[connId] = SshConnection(host, port, username, password, keyPath)
                val res = SshConnectResult(true, connId, null); ToolExecutionResult.success(res, json.encodeToString(SshConnectResult.serializer(), res))
            } else { val res = SshConnectResult(false, "", "SSH connection failed: ${r.stderr.trim().ifBlank { "Unknown error" }}"); ToolExecutionResult.success(res, json.encodeToString(SshConnectResult.serializer(), res)) }
        } catch (e: Exception) { val res = SshConnectResult(false, "", "SSH error: ${e.message}"); ToolExecutionResult.success(res, json.encodeToString(SshConnectResult.serializer(), res)) }
    }

    private suspend fun sshExec(args: Map<String, Any?>): ToolExecutionResult = withContext(Dispatchers.IO) {
        val connectionId = args["connectionId"] as? String ?: return@withContext ToolExecutionResult.error("Missing 'connectionId'")
        val command = args["command"] as? String ?: return@withContext ToolExecutionResult.error("Missing 'command'")
        if (fileAccessGuard.checkSedBlocked(command)) return@withContext ToolExecutionResult.error("BLOCKED: sed is not allowed.")
        val conn = connections[connectionId] ?: return@withContext ToolExecutionResult.error("Connection not found: $connectionId")
        val err = ensureReady(); if (err != null) return@withContext ToolExecutionResult.error(err)
        val ssh = sshPath ?: return@withContext ToolExecutionResult.error("SSH binary not found")
        try {
            val cmd = buildSshCommand(ssh, conn.host, conn.port, conn.username, conn.password, conn.keyPath, command)
            val r = shellExecutor.execute(cmd)
            val res = SshExecResult(r.exitCode, r.stdout.trim(), r.stderr.trim(), r.exitCode == 0); ToolExecutionResult.success(res, json.encodeToString(SshExecResult.serializer(), res))
        } catch (e: Exception) { val res = SshExecResult(-1, "", "SSH exec error: ${e.message}", false); ToolExecutionResult.success(res, json.encodeToString(SshExecResult.serializer(), res)) }
    }

    private fun sshDisconnect(args: Map<String, Any?>): ToolExecutionResult {
        val connectionId = args["connectionId"] as? String ?: return ToolExecutionResult.error("Missing 'connectionId'")
        val existed = connections.remove(connectionId) != null
        val res = SshDisconnectResult(existed, if (existed) null else "Connection not found: $connectionId")
        return ToolExecutionResult.success(res, json.encodeToString(SshDisconnectResult.serializer(), res))
    }

    private fun buildSshCommand(ssh: String, host: String, port: Int, username: String, password: String, keyPath: String, remoteCommand: String): String {
        val esc = remoteCommand.replace("'", "'\"'\"'"); val escP = password.replace("'", "'\"'\"'")
        return if (keyPath.isNotBlank() && File(keyPath).exists()) "$ssh -y -y -l $username -p $port -i \"$keyPath\" $host '$esc'"
        else if (password.isNotBlank()) "echo '$escP' | $ssh -y -y -l $username -p $port $host '$esc'"
        else "$ssh -y -y -l $username -p $port $host '$esc'"
    }
}