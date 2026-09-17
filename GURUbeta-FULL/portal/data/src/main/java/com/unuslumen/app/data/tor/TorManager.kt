package com.unuslumen.app.data.tor

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import net.freehaven.tor.control.TorControlConnection
import org.koin.core.annotation.Single
import org.torproject.jni.TorService
import java.net.InetSocketAddress
import java.net.Proxy

@Single
class TorManager {

    private var torService: TorService? = null
    private val _isReady = MutableStateFlow(false)
    val isReady: StateFlow<Boolean> = _isReady.asStateFlow()

    private val _circuitsHealthy = MutableStateFlow(false)
    val circuitsHealthy: StateFlow<Boolean> = _circuitsHealthy.asStateFlow()

    private var socksPort: Int = 9050
    private var httpPort: Int = 8118
    private var bound: Boolean = false

    private var lastHealthyTimestamp: Long = 0L

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var monitorJob: kotlinx.coroutines.Job? = null
    private lateinit var appContext: Context
    private var started = false

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val status = intent?.getStringExtra(TorService.EXTRA_STATUS) ?: return
            Log.d(TAG, "Tor status received: $status")

            when (status) {
                TorService.STATUS_ON -> {
                    socksPort = torService?.socksPort ?: 9050
                    httpPort = torService?.httpTunnelPort ?: 8118
                    _isReady.value = true
                    Log.d(TAG, "Tor is READY — SOCKS:$socksPort, HTTP:$httpPort")
                    applicationScope.launch {
                        delay(5000L)
                        newIdentity()
                        checkCircuitHealth()
                    }
                    startCircuitMonitor()
                }
                TorService.STATUS_OFF, TorService.STATUS_STOPPING -> {
                    Log.d(TAG, "Tor is OFF")
                    _isReady.value = false
                    _circuitsHealthy.value = false
                    lastHealthyTimestamp = 0L
                }
            }
        }
    }

    private fun startCircuitMonitor() {
        monitorJob?.cancel()
        monitorJob = applicationScope.launch {
            while (isActive) {
                delay(60_000L)
                if (!_isReady.value) continue
                checkCircuitHealth()
            }
        }
    }

    private fun checkCircuitHealth(): Boolean {
        val controlConn = torService?.torControlConnection ?: return false
        return try {
            val circuitStatus = controlConn.getInfo("circuit-status")
            val hasBuiltCircuits = circuitStatus?.contains("BUILT") == true
            _circuitsHealthy.value = hasBuiltCircuits
            if (hasBuiltCircuits) {
                lastHealthyTimestamp = System.currentTimeMillis()
            }
            hasBuiltCircuits
        } catch (e: Exception) {
            Log.w(TAG, "Circuit health check failed: ${e.message}")
            _circuitsHealthy.value = false
            false
        }
    }

    private fun newIdentity(): Boolean {
        val controlConn = torService?.torControlConnection ?: return false
        return try {
            controlConn.signal(TorControlConnection.SIGNAL_NEWNYM)
            _circuitsHealthy.value = false
            lastHealthyTimestamp = 0L
            true
        } catch (e: Exception) {
            Log.w(TAG, "NEWNYM failed: ${e.message}")
            false
        }
    }

    suspend fun requestNewCircuits(): Boolean {
        if (!newIdentity()) return false
        var attempts = 0
        while (attempts < 10) {
            delay(3000L)
            if (checkCircuitHealth()) return true
            attempts++
        }
        Log.w(TAG, "Circuit recovery failed after $attempts attempts")
        return false
    }

    fun start(context: Context) {
        if (started) return
        started = true
        appContext = context.applicationContext
        val filter = IntentFilter(TorService.ACTION_STATUS)
        appContext.registerReceiver(statusReceiver, filter, Context.RECEIVER_NOT_EXPORTED)

        // Start the persistent foreground service that keeps Tor alive
        TorPersistentService.start(appContext)

        // Bind to TorService for the control connection (circuit health, NEWNYM)
        // TorPersistentService keeps TorService alive as a foreground service.
        // This binding gives us access to torControlConnection for health checks.
        val intent = Intent(appContext, TorService::class.java)
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                val binder = service as? TorService.LocalBinder ?: return
                torService = binder.getService()
                socksPort = torService?.socksPort ?: 9050
                httpPort = torService?.httpTunnelPort ?: 8118
                bound = true
                Log.d(TAG, "Service connected — SOCKS:$socksPort, HTTP:$httpPort")

                if (socksPort > 0) {
                    _isReady.value = true
                    Log.d(TAG, "Tor was already running — marking ready immediately")
                    applicationScope.launch {
                        delay(2000L)
                        checkCircuitHealth()
                    }
                    startCircuitMonitor()
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                torService = null
                bound = false
                Log.w(TAG, "TorService binding lost, TorPersistentService will keep it alive")
            }
        }

        appContext.bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    fun stop() {
        // Tor is now a persistent foreground service. It does not stop.
        // This method is kept for API compatibility but does nothing.
        // TorPersistentService manages the lifecycle.
    }

    fun getProxy(): Proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", httpPort))
    fun getSocksProxy(): Proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", socksPort))

    fun areCircuitsUsable(): Boolean {
        if (!_isReady.value) return false
        if (_circuitsHealthy.value) return true
        applicationScope.launch { checkCircuitHealth() }
        return false
    }

    companion object {
        private const val TAG = "TorManager"
        private const val WIRELESS_DEBUGGING_PORT = 5555
    }
}
