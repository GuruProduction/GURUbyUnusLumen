package com.unuslumen.app.data.heartbeat

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Process-wide heartbeat admission gate.
 *
 * One instance per JVM process: both members are static so the ViewModel's
 * AiRepository and the worker's AiRepository — different Koin @Factory
 * instances — still observe the same state.
 *
 * Two independent signals keep a heartbeat from ever racing a live human chat:
 *  - [chatInFlight] is held for the duration of any sendMessage call, human or
 *    heartbeat, so at most ONE model conversation runs process-wide at a time.
 *  - [sendsInFlight] is a belt-and-braces counter incremented inside
 *    AiRepositoryImpl; if any send is somehow active without the gate, skip.
 */
object HeartbeatGate {

    /** Held while ANY LLM send is running (human chat or heartbeat beat). */
    private val chatInFlight = AtomicBoolean(false)

    /** Count of sends currently in flight, maintained by AiRepositoryImpl. */
    val sendsInFlight = AtomicInteger(0)

    /**
     * Attempt to admit the heartbeat. Returns true when the beat may run and
     * marks the chat channel busy until [release] is called. Returns false when
     * a send (human or another beat) is already in flight — the beat stands down.
     */
    fun tryAcquire(): Boolean = chatInFlight.compareAndSet(false, true)

    /** Release the gate after a beat finishes (or is abandoned). */
    fun release() {
        chatInFlight.set(false)
    }

    /** True when at least one send (human or beat) is currently in flight. */
    fun isSendActive(): Boolean = sendsInFlight.get() > 0
}