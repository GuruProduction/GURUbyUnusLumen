// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.heartbeat

import com.unuslumen.app.database.dao.HeartbeatPromptDao
import com.unuslumen.app.domain.repository.HeartbeatPromptRepository
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.error.NoDefinitionFoundException
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Every binding a heartbeat beat reaches for must exist, and the prompt must come
 * from one place only.
 *
 * WHY THE WIRING HALF EXISTS, AND WHY IT IS NOT A TEST OF BEHAVIOUR. A beat runs
 * inside a coroutine started by a foreground service that was started by a broadcast
 * from an alarm. Nothing about that path reports a failure to a person: the
 * coroutine's exception is caught, logged under a tag nobody reads, and the service
 * finishes. On the phone that is indistinguishable from "the heartbeat never fires",
 * which is exactly the report this test answers.
 *
 * WHY THE PROMPT HALF EXISTS. The prompt used to come from two places, the database
 * row and a bundled asset, and the asset was deleted on purpose. A second source
 * creeping back in would mean the app carrying its own opinion about what GURU says
 * to itself, which is the owner's decision. So this pins the absence: no asset, no
 * fallback, and an empty answer when nothing has been written.
 */
@RunWith(RobolectricTestRunner::class)
class HeartbeatChainWiringTest {

    private fun container() = koinApplication {
        modules(
            module {
                single<HeartbeatPromptDao> { mockk(relaxed = true) }
                single<HeartbeatPromptRepository> {
                    HeartbeatPromptRepositoryImpl(dao = get())
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
    fun `no bundled heartbeat prompt is packaged any more`() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val asset = try {
            context.assets.open("heartbeat/HEARTBEAT_PROMPT.md")
        } catch (e: Exception) {
            null
        }
        assertTrue(
            "a heartbeat prompt asset is back in the APK. The prompt belongs to the owner in " +
                "the database, and a shipped one means the app carries its own opinion about " +
                "what GURU says to itself every thirty minutes.",
            asset == null,
        )
    }

    @Test
    fun `an empty repository answers with no prompt rather than anything invented`() {
        // The mock DAO has no row, which is a fresh install. The honest answer is an
        // empty string: the beat then ends and says why, instead of injecting words
        // nobody chose.
        val repository = container().get<HeartbeatPromptRepository>()
        val current = kotlinx.coroutines.runBlocking { repository.current() }
        assertEquals(
            "a fresh install must have no prompt rather than a fallback",
            "",
            current,
        )
        val edited = kotlinx.coroutines.runBlocking { repository.isEdited() }
        assertFalse("nothing has been written, so nothing is edited", edited)
    }

    @Test
    fun `the token contract still substitutes both human spellings`() {
        val dollar = HeartbeatPrompts.substitute("Hello \${human}.", "Steven")
        assertTrue("the name must appear: $dollar", dollar.contains("Steven"))
        assertFalse("no literal token may survive: $dollar", dollar.contains("\${human}"))

        val braces = HeartbeatPrompts.substitute("Hello {{human_name}}.", "Steven")
        assertTrue("the name must appear: $braces", braces.contains("Steven"))
        assertFalse("no literal token may survive: $braces", braces.contains("{{human_name}}"))
    }
}
