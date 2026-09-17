package com.unuslumen.app.domain.slashcommands

import com.unuslumen.app.domain.slashcommands.commands.ThinkCommand
import com.unuslumen.app.domain.slashcommands.commands.WhoamiCommand
import com.unuslumen.app.preferences.domain.model.PrefsKey
import com.unuslumen.app.preferences.domain.model.stringPreferencesKey
import com.unuslumen.app.preferences.domain.use_case.GetPreferenceUseCase
import com.unuslumen.app.preferences.domain.use_case.SavePreferenceUseCase
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Preference-backed command behaviour: /whoami reads the stored display name,
 * /think persists valid levels and rejects junk without saving.
 */
class PreferenceCommandTest {

    private val nameKey = stringPreferencesKey("user_name")
    private val levelKey = stringPreferencesKey("thinking_level")

    private val getPreference: GetPreferenceUseCase = mockk()
    private val savePreference: SavePreferenceUseCase = mockk(relaxed = true)
    private val session = FakeSession()

    @Before
    fun setUp() {
        every { getPreference(nameKey, any()) } returns flowOf("")
        every { getPreference(levelKey, any()) } returns flowOf("medium")
    }

    @Test
    fun `whoami reports the stored name`() = runTest {
        every { getPreference(nameKey, any()) } returns flowOf("Steven")
        val result = WhoamiCommand(getPreference).execute(invoke("whoami"), session)
        assertEquals("You are Steven.", (result as SlashCommandResult.Handled).reply)
    }

    @Test
    fun `whoami handles anonymous users`() = runTest {
        every { getPreference(nameKey, any()) } returns flowOf("")
        val result = WhoamiCommand(getPreference).execute(invoke("whoami"), session)
        assertTrue((result as SlashCommandResult.Handled).reply.contains("anonymous"))
    }

    @Test
    fun `think persists a valid level`() = runTest {
        every { getPreference(levelKey, any()) } returns flowOf("medium")
        val result = ThinkCommand(getPreference, savePreference)
            .execute(invoke("think", values = mapOf("level" to "high")), session)
        assertEquals("Thinking level set to HIGH.", (result as SlashCommandResult.Handled).reply)
        coVerifySaved("high")
    }

    @Test
    fun `think with no arg reports current level`() = runTest {
        every { getPreference(levelKey, any()) } returns flowOf("low")
        val result = ThinkCommand(getPreference, savePreference).execute(invoke("think"), session)
        assertTrue((result as SlashCommandResult.Handled).reply.contains("LOW"))
        verifySavedNothing()
    }

    @Test
    fun `think rejects junk levels and saves nothing`() = runTest {
        every { getPreference(levelKey, any()) } returns flowOf("low")
        val result = ThinkCommand(getPreference, savePreference)
            .execute(invoke("think", values = mapOf("level" to "banana")), session)
        assertTrue((result as SlashCommandResult.Handled).reply.contains("LOW"))
        verifySavedNothing()
    }

    private fun coVerifySaved(expected: String) {
        verify { savePreference(any<PrefsKey<String>>(), expected) }
    }

    private fun verifySavedNothing() {
        verify(exactly = 0) { savePreference(any<PrefsKey<String>>(), any<String>()) }
    }

    class FakeSession : SlashCommandSession {
        override val conversationId: String? = null
        override val messageCount = 0
        override val isBusy = false
        override val providerId: String? = null
        override val tokenEstimate = 0
        override val recentToolCalls: List<ToolCallFact> = emptyList()
        override val conversationTasks: List<ConversationTaskSummary> = emptyList()
    }

    private fun invoke(key: String, values: Map<String, String> = emptyMap(), raw: String? = null) =
        SlashInvocation(key = key, rawArgs = raw, values = values)
}