package com.unuslumen.app.data.tools

import com.unuslumen.app.data.tools.registry.ToolDefinition
import com.unuslumen.app.data.tools.registry.ToolExecutor
import com.unuslumen.app.data.tools.registry.ToolParameter
import com.unuslumen.app.data.tools.registry.ToolParameterType
import com.unuslumen.app.data.tools.registry.ToolResultExtractor
import com.unuslumen.app.data.tools.registry.ToolSetRegistration
import kotlin.reflect.KClass

object SshToolDefinitions : ToolSetRegistration {
    const val SSH_CONNECT = "sshConnect"
    const val SSH_EXEC = "sshExec"
    const val SSH_DISCONNECT = "sshDisconnect"

    override val definitions = listOf(
        ToolDefinition(name = SSH_CONNECT, description = "Connect to a remote server via SSH. Returns a connection ID to use with other SSH tools. Supports password and key-based authentication. Use this to manage servers, deploy code, debug production issues, or run remote commands. No timeout.", category = "ssh", parameters = listOf(ToolParameter("host", ToolParameterType.String, true, "Server hostname or IP address"), ToolParameter("port", ToolParameterType.Integer, false, "SSH port number, default 22"), ToolParameter("username", ToolParameterType.String, true, "Username for authentication"), ToolParameter("password", ToolParameterType.String, false, "Password for authentication. Use this OR keyPath, not both."), ToolParameter("keyPath", ToolParameterType.String, false, "Path to private key file. Use this OR password, not both.")), permissions = emptyList()),
        ToolDefinition(name = SSH_EXEC, description = "Execute a command on a connected SSH server. Returns stdout, stderr, and exit code. Use for running remote commands, checking server status, deploying code, or any remote operation. No timeout.", category = "ssh", parameters = listOf(ToolParameter("connectionId", ToolParameterType.String, true, "The connection ID string returned by the sshConnect tool"), ToolParameter("command", ToolParameterType.ShellCommand, true, "The shell command to execute on the remote server")), permissions = emptyList()),
        ToolDefinition(name = SSH_DISCONNECT, description = "Close an SSH connection. Always disconnect when you're done to free resources.", category = "ssh", parameters = listOf(ToolParameter("connectionId", ToolParameterType.String, true, "The connection ID string returned by the sshConnect tool")), permissions = emptyList())
    )
    override fun executorClass(): KClass<out ToolExecutor> = SshToolExecutor::class
    override fun extractorClass(): KClass<out ToolResultExtractor>? = null
}