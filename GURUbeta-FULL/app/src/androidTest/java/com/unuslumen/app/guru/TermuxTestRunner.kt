// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.guru

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner

/**
 * A runner that boots a PLAIN Application, so a Termux test is not competing
 * with the app's own startup.
 *
 * WHY THIS EXISTS. The instrumented Termux tests kept dying with OutOfMemoryError
 * inside the app's Tor proxy selector: GuruApplication.onCreate starts Tor and the
 * outbound work, Tor is not ready on a fresh emulator, [TorEgress] fails closed,
 * and OkHttp keeps re-entering the selector until the heap is gone. That happens
 * before the test body runs, so the failure says nothing about Termux and the
 * test can never finish.
 *
 * The fix belongs in the test harness, not in the product: a test about whether
 * ELF binaries execute has no business booting Tor, Cerebrum, the heartbeat or
 * the model layer. This runner substitutes a bare Application, so the test
 * process holds nothing but what the test itself creates.
 *
 * WHAT IS PRESERVED, and this is the part that matters:
 *
 *  - The process still runs as the app's package, with the app's UID and the
 *    app's SELinux domain (`u:r:untrusted_app`). That is the whole reason these
 *    tests are instrumented rather than JVM tests: Android's W^X rule and
 *    SELinux are properties of that domain, and a run-as or adb-shell result
 *    proves nothing about it.
 *  - `targetContext` still points at the real app, so its filesDir, its
 *    packageManager and its nativeLibraryDir are the real ones.
 *
 * WHAT IS LOST. Koin is not initialised, so a test that resolves a dependency
 * through the container cannot use this runner. The Termux and Python providers
 * take a Context in their constructor and are built directly, which is why this
 * works for them.
 *
 * It is invoked by naming it on the am instrument command line, so the project's
 * default testInstrumentationRunner is untouched and every other test still gets
 * the real application:
 *
 *   am instrument -w -e class <Test> \
 *     <pkg>.test/com.unuslumen.app.guru.TermuxTestRunner
 */
class TermuxTestRunner : AndroidJUnitRunner() {

    override fun newApplication(
        cl: ClassLoader?,
        className: String?,
        context: Context?,
    ): Application = super.newApplication(cl, PlainApplication::class.java.name, context)
}

/**
 * The bare Application the runner substitutes.
 *
 * Deliberately empty: the point is that nothing runs. Any behaviour added here
 * would be behaviour the test is no longer measuring.
 */
class PlainApplication : Application()
