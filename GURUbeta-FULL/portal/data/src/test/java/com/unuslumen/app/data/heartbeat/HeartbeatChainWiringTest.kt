// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.heartbeat

import com.unuslumen.app.database.dao.HeartbeatPromptDao
import com.unuslumen.app.domain.repository.HeartbeatPromptRepository
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.error.NoDefinitionFoundException
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Every binding a heartbeat beat reaches for must exist in the graph the app builds.
 *
 * WHY THIS EXISTS, AND WHY IT IS NOT A TEST OF BEHAVIOUR. A beat runs inside a
 * coroutine started by a foreground service that was started by a broadcast from an
 * alarm. Nothing about that path reports a failure to a person: the coroutine's
 * exception is caught, logged under a tag nobody reads, and the service finishes.
 * On the phone that is indistinguishable from "the heartbeat never fires", which is
 * exactly the report this test is answering.
 *
 * The chain acquired a new dependency when the prompt moved into the database. Koin
 * fails on an unresolvable constructor dependency at the moment the component is
 * created, not when the app is assembled, so a compile and a green build say nothing
 * about it. This starts a container and asks for the types by interface, which is what
 * the service does.
 */
@RunWith(RobolectricTestRunner::class)
class HeartbeatChainWiringTest {

    private fun container() = koinApplication {
        modules(
            module {
                single<HeartbeatPromptDao> { mockk(relaxed = true) }
                single<HeartbeatPromptRepository> {
                    HeartbeatPromptRepositoryImpl(
                        context = org.robolectric.RuntimeEnvironment.getApplication(),
                        dao = get(),
                    )
                }
            },
        )
    }.koin

    @Test
    fun `the heartbeat prompt repository resolves by its interface`() {
        val resolved = try {
            container().get<HeartbeatPromptRepository>()
            true
        } catch (e: NoDefinitionFoundException) {
            false
        }

        assertTrue(
            "HeartbeatPromptRepository did not resolve. The run service injects it, and a " +
                "beat that cannot build its service dies inside a coroutine with nothing on " +
                "screen, which reads as the heartbeat never firing at all.",
            resolved,
        )
    }

    /**
     * The production binding is by annotation, so this asks the source rather than the
     * container: aiDataModule component-scans com.unuslumen.app.data, and the class
     * carries @Single(binds = [HeartbeatPromptRepository::class]). If that annotation
     * is ever dropped, the scan still runs and still produces nothing for this type.
     */
    @Test
    fun `the production implementation declares the interface it binds`() {
        val source = File(
            "src/main/java/com/unuslumen/app/data/heartbeat/HeartbeatPromptRepositoryImpl.kt"
        )
        assertTrue(
            "the test is looking at the wrong directory: ${source.absolutePath}",
            source.exists(),
        )
        val text = source.readText()
        assertTrue(
            "HeartbeatPromptRepositoryImpl no longer declares " +
                "@Single(binds = [HeartbeatPromptRepository::class]), so component scanning " +
                "would produce no binding for the interface the run service injects",
            text.contains("binds = [HeartbeatPromptRepository::class]"),
        )
        assertTrue(
            "the implementation no longer carries @Single at all",
            text.contains("@Single"),
        )
    }

    @Test
    fun `the bundled prompt is still packaged and still substitutes its name`() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val raw = HeartbeatPrompts.bundledText(context)
        assertTrue(
            "the bundled heartbeat prompt is not on the asset path any more, so a fresh " +
                "install has no prompt at all and every beat ends before it reaches the model",
            raw != null && raw.isNotBlank(),
        )
        val substituted = HeartbeatPrompts.substitute(raw!!, "Steven")
        assertEquals(
            "substitution must not leave a literal human token behind",
            false,
            substituted.contains("\${human}") || substituted.contains("{{human"),
        )
    }
}
