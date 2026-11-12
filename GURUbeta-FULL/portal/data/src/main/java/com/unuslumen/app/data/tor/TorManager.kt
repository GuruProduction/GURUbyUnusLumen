// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
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
import kotlinx.coroutines.sync.withLock
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
    private var reactionJob: kotlinx.coroutines.Job? = null
    private lateinit var appContext: Context
    private var started = false

    /**
     * Serialises rebuilds so concurrent requests do not each fire a NEWNYM.
     *
     * WHY THIS EXISTS. During an outage the proxy selector can have dozens of connection
     * attempts blocked at once, and every one of them wants a circuit. Without coalescing each
     * fires its own NEWNYM, the daemon throws away the circuit it was halfway through building,
     * and the outage lasts longer than it needs to. One rebuild in flight is the correct shape:
     * the first caller drives it and everyone else waits on the result it produces.
     */
    private val rebuildMutex = kotlinx.coroutines.sync.Mutex()

    /** The rebuild currently in flight, if any, so callers can await it. */
    @Volatile
    private var rebuildInFlight: kotlinx.coroutines.Job? = null

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

    /**
     * Watch circuits and react to a loss rather than waiting to notice it twice.
     *
     * TWO JOBS, DELIBERATELY SEPARATE.
     *
     * The first watches the health flag itself. The moment it drops, recovery starts, so a
     * circuit that dies between two polls is rebuilt in milliseconds rather than up to a minute
     * later. That is what keeps a waiting request short instead of the full window.
     *
     * The second is the existing 60-second poll. It is the floor for a daemon that has gone
     * quiet without saying so, because a control connection can fail silently and leave the flag
     * reading healthy when nothing is actually built.
     */
    private fun startCircuitMonitor() {
        monitorJob?.cancel()
        monitorJob = applicationScope.launch {
            while (isActive) {
                delay(60_000L)
                if (!_isReady.value) continue
                checkCircuitHealth()
            }
        }
        reactionJob?.cancel()
        reactionJob = applicationScope.launch {
            _circuitsHealthy.collect { healthy ->
                if (!healthy && _isReady.value) {
                    Log.i(TAG, "Circuits reported unhealthy, starting recovery immediately")
                    requestCircuitRebuildAsync()
                }
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

    /**
     * Start a rebuild if one is not already running, and return the one in flight.
     *
     * THE COALESCING POINT. Every caller that wants a circuit calls this and gets back a job it
     * can await, so a hundred blocked requests produce one NEWNYM and one circuit build rather
     * than a hundred. Safe to call from any thread, including the proxy selector, which is
     * synchronous and cannot suspend.
     */
    fun requestCircuitRebuildAsync(): kotlinx.coroutines.Job {
        rebuildInFlight?.takeIf { it.isActive }?.let { return it }
        synchronized(this) {
            rebuildInFlight?.takeIf { it.isActive }?.let { return it }
            val job = applicationScope.launch {
                try {
                    rebuildMutex.withLock {
                        // Re-check inside the lock: another caller may have recovered the circuit
                        // while this one queued, in which case a NEWNYM now would throw away a
                        // healthy circuit.
                        if (!(_circuitsHealthy.value && _isReady.value)) {
                            Log.i(TAG, "Proactive circuit rebuild starting")
                            val recovered = requestNewCircuits()
                            Log.i(TAG, "Proactive circuit rebuild finished, recovered=$recovered")
                            if (!recovered && !_isReady.value) {
                                // Tor is off rather than merely circuitless. Restart the
                                // persistent service so the daemon comes back.
                                Log.w(TAG, "Tor is not ready after a rebuild, restarting the service")
                                restartService()
                            }
                        }
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Proactive circuit rebuild failed: ${e.message}")
                }
            }
            rebuildInFlight = job
            return job
        }
    }

    /**
     * Bring the daemon back after it reported itself off.
     *
     * The persistent service is START_STICKY and normally outlives everything, so reaching this
     * means it was killed or never started: a manual force-stop, an OEM battery killer, or a
     * crash inside the service. Starting it again is the only recovery available, and it is the
     * difference between Tor being down until the next app launch and coming back on its own.
     */
    private fun restartService() {
        if (!::appContext.isInitialized) return
        try {
            TorPersistentService.start(appContext)
        } catch (e: Exception) {
            Log.w(TAG, "Tor service restart failed: ${e.message}")
        }
    }

    /**
     * Whether circuit health is observable at all right now.
     *
     * CIRCUIT HEALTH IS A MEASUREMENT, NOT A FACT ABOUT TOR. It comes from
     * `getInfo("circuit-status")` over the control connection. When that connection is absent
     * every health check returns false, and false then means "could not ask" rather than "no
     * circuit". A request must never be held for the full wait window on a Tor that is working,
     * so the selector distinguishes the two: it requires a healthy circuit when health is
     * observable, and trusts readiness alone when it is not.
     */
    fun canReportCircuitHealth(): Boolean = torService?.torControlConnection != null

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

    /**
     * The Tor control connection, for callers that need more than circuits.
     *
     * Added for the CLI bridge (#176), which publishes an onion service with
     * ADD_ONION. Exposing the connection rather than wrapping each control
     * command keeps the surface honest: the control protocol is large and this
     * app will want more of it over time, so a narrow wrapper would be a queue of
     * one-line additions.
     *
     * Null when Tor is not bound or not ready, which is a state the caller must
     * report rather than retry blindly.
     */
    fun torControlConnection(): TorControlConnection? {
        if (!_isReady.value) return null
        return torService?.torControlConnection
    }

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
