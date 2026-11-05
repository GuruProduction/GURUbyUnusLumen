// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.heartbeat

import com.unuslumen.app.domain.repository.HeartbeatPromptRepository
import io.mockk.coEvery
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The switch, and the one promise it makes: on means on, off means off, and nothing
 * else turns it off.
 *
 * WHY THIS EXISTS. The chain is silent by design, so a wrong switch is invisible. If
 * turning it on did not book, or turning it off did not cancel, the room would show a
 * state the phone does not actually hold, and a person would learn that from thirty
 * minutes of nothing.
 *
 * THE VIEW MODEL IS BUILT BY HAND HERE rather than through Koin. These are assertions
 * about the switch's logic, and the module's own resolution is pinned by
 * [HeartbeatModuleTest] beside this file. Two tests, two questions: does the wiring
 * exist, and does the behaviour hold.
 */
@RunWith(RobolectricTestRunner::class)
class HeartbeatSwitchTest {

    /**
     * One view model with a recorded chain call and a fixed prompt answer.
     *
     * The prompt repository is a mock because the prompt itself is not what these
     * tests are about: only whether a prompt exists matters to the switch.
     */
    private fun viewModel(
        onToggle: (Boolean) -> Unit = {},
        promptAnswer: String = "",
        onFire: (String) -> Boolean = { true },
    ): HeartbeatViewModel {
        val repository = mockk<HeartbeatPromptRepository>(relaxed = true)
        coEvery { repository.current() } returns promptAnswer
        coEvery { repository.isEdited() } returns promptAnswer.isNotBlank()
        coEvery { repository.save(any()) } returns Result.success(Unit)

        return HeartbeatViewModel(
            promptRepository = repository,
            getPreference = mockk(relaxed = true),
            isEnabledNow = { false },
            applyEnabled = onToggle,
            fireNow = onFire,
        )
    }

    @Test
    fun `turning the switch on with a prompt reaches the chain with true`() {
        var asked: Boolean? = null
        val model = viewModel(onToggle = { asked = it }, promptAnswer = "Think about today.")

        model.onEvent(HeartbeatEvent.SetEnabled(true))

        assertEquals("the chain must be told to book, not cancelled", true, asked)
        assertTrue("the room must show it as on", model.state.value.enabled)
    }

    @Test
    fun `turning the switch on with no prompt is refused and books nothing`() {
        var asked: Boolean? = null
        val model = viewModel(onToggle = { asked = it }, promptAnswer = "")

        model.onEvent(HeartbeatEvent.SetEnabled(true))

        assertEquals(
            "no prompt means the chain must not be told anything: a prompt is what a beat sends",
            null,
            asked,
        )
        assertFalse(
            "the switch must not move. On with nothing to send is a lie on screen.",
            model.state.value.enabled,
        )
        assertTrue(
            "the refusal must be stated, so the owner knows why nothing happened",
            model.state.value.promptRequired,
        )
    }

    @Test
    fun `the switch cannot be turned on until a prompt is written`() {
        val empty = viewModel(promptAnswer = "")
        assertFalse(
            "with no prompt there is nothing for a beat to send, so the switch is unavailable",
            empty.state.value.canEnable,
        )

        val ready = viewModel(promptAnswer = "Think about today.")
        assertTrue(
            "with a prompt the switch is available",
            ready.state.value.canEnable,
        )
    }

    @Test
    fun `turning the switch off reaches the chain with false`() {
        var asked: Boolean? = null
        val model = viewModel(onToggle = { asked = it })

        model.onEvent(HeartbeatEvent.SetEnabled(false))

        assertEquals("the chain must be told to cancel", false, asked)
        assertFalse("the room must show it as off", model.state.value.enabled)
    }

    @Test
    fun `the refusal names what to do, not just that something failed`() {
        val model = viewModel(onToggle = {}, promptAnswer = "")

        model.onEvent(HeartbeatEvent.SetEnabled(true))

        val error = model.state.value.error ?: ""
        assertTrue(
            "the refusal must say a prompt is needed: $error",
            error.contains("needs a prompt"),
        )
        assertTrue(
            "and it must say what to do about it: $error",
            error.contains("Write one below"),
        )
    }

    @Test
    fun `on with a prompt raises no refusal`() {
        val model = viewModel(onToggle = {}, promptAnswer = "Think about today.")

        model.onEvent(HeartbeatEvent.SetEnabled(true))

        assertFalse(
            "a prompt already exists, so nothing is refused",
            model.state.value.promptRequired,
        )
    }

    @Test
    fun `writing a prompt clears a standing refusal`() {
        val model = viewModel(onToggle = {}, promptAnswer = "")
        model.onEvent(HeartbeatEvent.SetEnabled(true))
        assertTrue("precondition: the switch was refused", model.state.value.promptRequired)

        model.onEvent(HeartbeatEvent.Edit("Think about today."))

        assertFalse(
            "once a prompt exists the refusal has answered itself",
            model.state.value.promptRequired,
        )
        assertTrue("and the switch is now available", model.state.value.canEnable)
    }

    @Test
    fun `an empty prompt refuses to be saved`() {
        val model = viewModel(promptAnswer = "")

        model.onEvent(HeartbeatEvent.Edit(""))
        model.onEvent(HeartbeatEvent.Save)

        assertTrue(
            "saving nothing must say so rather than write an empty row: ${model.state.value.error}",
            model.state.value.error?.contains("cannot be empty") == true,
        )
    }

    @Test
    fun `firing by hand with no prompt asks for one rather than sending nothing`() {
        var sent: String? = null
        val model = viewModel(promptAnswer = "", onFire = { sent = it; true })

        model.onEvent(HeartbeatEvent.FireNow)

        val state = model.state.value
        assertTrue("a beat cannot run without a prompt", state.promptRequired)
        assertFalse("nothing was sent, so nothing is in flight", state.firing)
        assertEquals("and nothing may reach the portal", null, sent)
    }

    @Test
    fun `firing by hand hands the saved prompt to the portal and stamps the fire`() {
        var sent: String? = null
        val model = viewModel(
            promptAnswer = "Look at what ${'$'}{human} asked and carry it forward.",
            onFire = { sent = it; true },
        )

        model.onEvent(HeartbeatEvent.FireNow)
        // The handover runs inside a coroutine; give it the frame it needs.
        org.robolectric.shadows.ShadowLooper.idleMainLooper()

        assertTrue(
            "the prompt must travel with the fire, because the whole fault was a beat " +
                "nobody could see: $sent",
            sent != null && sent!!.isNotBlank(),
        )
        assertTrue(
            "tokens must already be substituted, since the portal sends the text as given",
            sent != null && !sent!!.contains("${'$'}{human}"),
        )
        assertTrue(
            "the screen navigates on this, so a successful handover must stamp it",
            model.state.value.firedAt > 0L,
        )
    }
}
