package com.unuslumen.app.data.tools.quickjs

/**
 * QuickJsEngine — Kotlin interface to the QuickJS JavaScript engine via JNI.
 *
 * A persistent JavaScript runtime that survives across tool executions.
 * Scripts are evaluated on a dedicated native thread so that JS state
 * (global variables, loaded modules, registered functions) persists
 * between calls.
 *
 * Lifecycle:
 *   1. Create: spin up JSRuntime + JSContext on a native pthread.
 *   2. setBridgeCallback: register the callback that JS calls
 *      __bridge.call(category, action, argsJson) to invoke.
 *   3. eval: run a script. Blocks until JS finishes or throws.
 *   4. close: signal the JS thread to exit, free everything.
 *
 * Memory is managed on both sides:
 *   - QuickJS GC handles JavaScript objects.
 *   - JNI global refs handle the callback reference.
 *   - The engine struct is freed in nativeClose.
 *
 * This class is NOT thread-safe. Only the thread that called
 * setBridgeCallback should call eval and close. Internally the
 * native engine posts scripts to the JS thread via a queue and
 * blocks on a condition variable until the result is ready.
 */
class QuickJsEngine {

    /** Native engine pointer (0 = not initialized / closed). */
    private var ptr: Long = 0L

    /** Set to true after close() so finalize() does not double-free. */
    @Volatile
    private var closed: Boolean = false

    init {
        ptr = nativeInit()
        if (ptr == 0L) {
            throw IllegalStateException("Failed to initialize QuickJS runtime")
        }
    }

    /**
     * Register the bridge callback. JavaScript code calls
     *   __bridge.call(category, action, argsJson)
     * and this callback is invoked synchronously on the JS thread.
     *
     * [callback] receives:
     *   - category: e.g. "contacts", "camera", "clipboard"
     *   - action:   e.g. "list", "snapshot", "read"
     *   - argsJson: JSON string of arguments
     * And returns a JSON string result.
     */
    fun setBridgeCallback(callback: BridgeCallback) {
        checkNotClosed()
        if (!nativeSetBridgeCallback(ptr, callback)) {
            throw IllegalStateException("Failed to register bridge callback")
        }
    }

    /**
     * Evaluate a JavaScript script. Blocks until the script finishes
     * or throws. Returns the result as a JSON string.
     *
     * - If the script returns undefined, "null" is returned.
     * - If the script returns a value, it is JSON.stringify'd.
     * - If the script throws, a [RuntimeException] is thrown with
     *   the JavaScript error message.
     *
     * @param script   The JavaScript source code.
     * @param filename  The filename for error reporting (default "eval").
     * @return JSON string of the evaluation result.
     * @throws RuntimeException if the JavaScript throws.
     */
    fun eval(script: String, filename: String = "eval"): String {
        checkNotClosed()
        return nativeEval(ptr, script, filename)
    }

    /**
     * Destroy the engine. Safe to call multiple times.
     * After close, calling eval or setBridgeCallback throws.
     */
    fun close() {
        if (closed || ptr == 0L) return
        closed = true
        nativeClose(ptr)
        ptr = 0L
    }

    protected fun finalize() {
        close()
    }

    private fun checkNotClosed() {
        if (closed || ptr == 0L) {
            throw IllegalStateException("QuickJsEngine is closed")
        }
    }

    /* ---------------------------------------------------------------- */
    /* External declarations                                             */
    /* ---------------------------------------------------------------- */

    private external fun nativeInit(): Long

    private external fun nativeSetBridgeCallback(
        ptr: Long,
        callback: BridgeCallback
    ): Boolean

    private external fun nativeEval(
        ptr: Long,
        script: String,
        filename: String
    ): String

    private external fun nativeClose(ptr: Long)

    companion object {
        init {
            System.loadLibrary("quickjs_bridge")
        }
    }
}

/**
 * BridgeCallback — Interface invoked when JavaScript calls
 *   __bridge.call(category, action, argsJson)
 *
 * The implementation runs on the JS thread (the native pthread).
 * It must complete synchronously and return a JSON result string.
 *
 * If the implementation throws, the C bridge catches the exception
 * and converts it to a JavaScript InternalError.
 */
interface BridgeCallback {
    fun onBridgeCall(
        category: String,
        action: String,
        argsJson: String
    ): String
}