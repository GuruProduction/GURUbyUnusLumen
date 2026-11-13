// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.tools.registry.permission

import android.content.Context
import com.unuslumen.app.util.shell.shizuku.ShizukuBridge
import com.unuslumen.app.util.shell.shizuku.ShizukuState

/**
 * One bound shell-UID channel for the whole process.
 *
 * WHY THIS EXISTS. [ShizukuBridge] caches a bound UserService in an instance field, so a
 * fresh bridge per call means a fresh binder round trip per call, which is exactly the cost
 * that field was added to avoid. The shell executor holds its own long-lived bridge for
 * that reason. The permission layer needs the same channel, and two independent bindings to
 * the same service would leave two connections Shizuku has to track and one of them idle.
 *
 * WHY NOT INJECT THE EXECUTOR'S BRIDGE INSTEAD. The executor lives in `:core:util` and the
 * permission registry in `:portal:data`, which already depends on it, so this could take
 * the executor as a constructor argument. It does not, because the permission layer must
 * work on a tool that never shells out for anything else: binding the ladder to a grant
 * would make one small capability depend on the whole executor's six providers.
 *
 * THE CONTEXT IS HELD AS AN APPLICATION CONTEXT. A bridge keeps a Context for package
 * lookups and binder names, and holding an Activity here would leak it for the life of the
 * process. Application context is the one that is safe to keep.
 */
object ShellAccess {

    @Volatile
    private var bridge: ShizukuBridge? = null

    /**
     * The shared bridge, created on first use.
     *
     * Double-checked with a volatile field because two tool calls can arrive on different
     * dispatchers at once, and two bridges is the exact waste this object exists to remove.
     */
    fun bridge(context: Context): ShizukuBridge {
        bridge?.let { return it }
        return synchronized(this) {
            bridge ?: ShizukuBridge(context.applicationContext).also { bridge = it }
        }
    }

    /**
     * The three-state reading, for anything that wants to report what would unlock shell
     * access rather than only that it is missing.
     */
    fun state(context: Context): ShizukuState = bridge(context).state()

    /** Whether a command can run at shell UID right now. */
    fun available(context: Context): Boolean = state(context).usable

    /**
     * Forget the bound bridge.
     *
     * For tests and for a future sign-out path. Production code has no reason to call it:
     * the bridge survives the process, which is what makes the channel cheap.
     */
    fun reset() {
        synchronized(this) { bridge = null }
    }
}
