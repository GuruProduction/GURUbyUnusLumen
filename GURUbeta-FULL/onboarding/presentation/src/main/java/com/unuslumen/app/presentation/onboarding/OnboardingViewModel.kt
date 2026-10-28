// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.presentation.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unuslumen.app.data.onboarding.ProfileBrainSync
import com.unuslumen.app.data.onboarding.UserProfileAnswers
import com.unuslumen.app.onboarding.domain.OnboardingQuestion
import com.unuslumen.app.onboarding.domain.OnboardingQuestionCatalogue
import com.unuslumen.app.onboarding.domain.PowerKind
import com.unuslumen.app.onboarding.domain.UserProfile
import com.unuslumen.app.onboarding.domain.UserProfileRepository
import com.unuslumen.app.preferences.PrefsConstants
import com.unuslumen.app.preferences.domain.model.AiProvider
import com.unuslumen.app.preferences.domain.model.PrefsKey
import com.unuslumen.app.preferences.domain.model.stringPreferencesKey
import com.unuslumen.app.preferences.domain.model.toAiProviderOrNone
import com.unuslumen.app.preferences.domain.use_case.SavePreferenceUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.koin.android.annotation.KoinViewModel

/**
 * OnboardingViewModel — the flow's conductor.
 *
 * Owns the phase machine, the live answer draft, the setup stage and the save.
 * It never touches a composable and never assumes the catalogue has a
 * particular question: everything is driven by OnboardingQuestionCatalogue.
 *
 * THE FLOW
 *  INTRO → QUESTIONS → REVIEW → POWER → BIRTH
 *
 * SAVE ORDER, and each step matters:
 *  1. write every profile answer into the profile row (Room, durable)
 *  2. write the preferred name into USER_NAME_KEY, so ${human} resolves on the
 *     very first message with no other change
 *  3. write the setup stage: provider, key, model, base URL, skills, vision,
 *     language. These are real preferences, consumed by the send path.
 *  4. mark the profile complete, which opens the app gate
 *  5. confirm completion, then hand control to the caller
 *  6. kick the brain sync last; the boot pass retries anything that did not land
 *
 * The caller is only told to open the app once completion is actually
 * readable. A save that throws leaves the flow intact with a reason on screen,
 * because closing over a half-written profile would be worse than asking the
 * human to try once more.
 */
@KoinViewModel
class OnboardingViewModel(
    private val userProfileRepository: UserProfileRepository,
    private val profileBrainSync: ProfileBrainSync,
    private val savePreference: SavePreferenceUseCase
) : ViewModel() {

    private val _state = MutableStateFlow(
        OnboardingUiState(
            chapters = OnboardingQuestionCatalogue.chapters,
            beats = OnboardingQuestionCatalogue.beats,
            questionTotal = OnboardingQuestionCatalogue.questions.size
        )
    )
    val state = _state.asStateFlow()

    /** The working profile, carrying ids and pending bookkeeping across saves. */
    private var workingProfile: UserProfile = UserProfile()

    /** Guards the deal against a double tap while the write is in flight. */
    private var acceptingDeal = false

    init {
        viewModelScope.launch {
            workingProfile = userProfileRepository.getProfile()
            // Resume where the human left off if they closed mid-flow.
            val draft = OnboardingQuestionCatalogue.questions.associate { question ->
                question.key to UserProfileAnswers.read(workingProfile, question.key)
            }
            _state.value = _state.value.copy(
                draft = draft,
                dealAccepted = workingProfile.dealAcceptedAt > 0
            )
        }
    }

    // =====================================================================
    // Answers
    // =====================================================================

    /** Record one answer as it is typed. Cheap, local, no writes. */
    fun setAnswer(key: String, value: String) {
        _state.value = _state.value.copy(draft = _state.value.draft + (key to value))
    }

    // =====================================================================
    // Navigation
    // =====================================================================

    /** Move forward one beat, or into the questions from the last one. */
    fun nextIntro() {
        val current = _state.value
        val nextIndex = current.beatIndex + 1
        if (nextIndex < current.beats.size) {
            _state.value = current.copy(beatIndex = nextIndex)
        } else {
            _state.value = current.copy(
                phase = OnboardingPhase.QUESTIONS,
                questionIndex = 0,
                question = OnboardingQuestionCatalogue.questions.firstOrNull()
            )
        }
    }

    /** Back one beat. Never leaves the flow. */
    fun previousIntro() {
        val current = _state.value
        if (current.beatIndex > 0) {
            _state.value = current.copy(beatIndex = current.beatIndex - 1)
        }
    }

    /**
     * Agree to the deal, then advance.
     *
     * The phase only moves on a successful write. If the vault cannot be
     * written, the human stays on the deal page with the reason visible rather
     * than being waved through on a promise that was never recorded.
     */
    fun acceptDeal() {
        if (acceptingDeal) return
        acceptingDeal = true
        viewModelScope.launch {
            try {
                userProfileRepository.acceptDeal(OnboardingQuestionCatalogue.VERSION)
                workingProfile = workingProfile.copy(
                    dealAcceptedAt = System.currentTimeMillis(),
                    dealVersion = OnboardingQuestionCatalogue.VERSION
                )
                _state.value = _state.value.copy(dealAccepted = true, error = null)
                nextIntro()
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    error = "Could not record your agreement. Check your storage and try again."
                )
            } finally {
                acceptingDeal = false
            }
        }
    }

    /** Forward one question, or to review after the last one. */
    fun nextQuestion() {
        val current = _state.value
        if (!current.canAdvance) return
        val nextIndex = current.questionIndex + 1
        val questions = OnboardingQuestionCatalogue.questions
        if (nextIndex < questions.size) {
            _state.value = current.copy(
                questionIndex = nextIndex,
                question = questions[nextIndex]
            )
        } else {
            _state.value = current.copy(phase = OnboardingPhase.REVIEW, question = null)
        }
    }

    /** Back one question, or to the last intro page from the first question. */
    fun previousQuestion() {
        val current = _state.value
        when {
            current.phase == OnboardingPhase.REVIEW -> {
                _state.value = current.copy(
                    phase = OnboardingPhase.QUESTIONS,
                    questionIndex = OnboardingQuestionCatalogue.questions.lastIndex,
                    question = OnboardingQuestionCatalogue.questions.lastOrNull()
                )
            }
            current.questionIndex > 0 -> {
                val previousIndex = current.questionIndex - 1
                _state.value = current.copy(
                    questionIndex = previousIndex,
                    question = OnboardingQuestionCatalogue.questions[previousIndex]
                )
            }
            else -> {
                _state.value = current.copy(
                    phase = OnboardingPhase.INTRO,
                    beatIndex = current.beats.lastIndex.coerceAtLeast(0)
                )
            }
        }
    }

    /** Jump back into a specific question from the review list. */
    fun editQuestion(key: String) {
        val index = OnboardingQuestionCatalogue.questions.indexOfFirst { it.key == key }
        if (index < 0) return
        _state.value = _state.value.copy(
            phase = OnboardingPhase.QUESTIONS,
            questionIndex = index,
            question = OnboardingQuestionCatalogue.questions[index]
        )
    }

    /** From review into the setup stage. */
    fun toPower() {
        _state.value = _state.value.copy(phase = OnboardingPhase.POWER, question = null)
    }

    /** From the setup stage back into review. */
    fun backToReview() {
        _state.value = _state.value.copy(phase = OnboardingPhase.REVIEW, question = null)
    }

    /**
     * From the birth screen back to the setup stage.
     *
     * This exists because finishing used to mark the profile complete BEFORE the
     * birth screen was drawn, which meant a human who tapped straight through the
     * API screen had already opened the app gate and had no route back to put
     * their key in. Completion is now recorded by [openGuru] instead, so the birth
     * screen is still part of onboarding and everything behind it is reachable.
     */
    fun backToPower() {
        if (_state.value.saving) return
        _state.value = _state.value.copy(phase = OnboardingPhase.POWER, question = null)
    }

    // =====================================================================
    // Power up — real writes, nothing decorative
    // =====================================================================

    /** Record one setup field as it is typed. */
    fun setPowerField(key: String, value: String) {
        _state.value = _state.value.copy(powerDraft = _state.value.powerDraft + (key to value))
    }

    /**
     * Connect or disconnect one setup row.
     *
     * A row only shows as connected when there is something real behind it:
     *  - provider needs a chosen provider plus whatever that provider demands
     *  - skills, vision and language need a non-blank value
     * The write itself happens on finish, so backing out never leaves a
     * half-configured app behind.
     */
    fun togglePower(key: String) {
        val current = _state.value
        val on = if (key in current.powerOn) current.powerOn - key else current.powerOn + key
        _state.value = current.copy(powerOn = on, error = null)
    }

    /** The provider chosen in the setup stage, or None before one is picked. */
    private fun chosenProvider(): AiProvider =
        _state.value.powerDraft["provider"].orEmpty().trim().toAiProviderOrNone()

    /** True when the chosen provider needs a base URL typed by the human. */
    private fun providerNeedsUrl(provider: AiProvider): Boolean =
        provider == AiProvider.Ollama || provider == AiProvider.OpenAICompat

    /** True when the chosen provider needs a key typed by the human. */
    private fun providerNeedsKey(provider: AiProvider): Boolean = when (provider) {
        AiProvider.OpenAI,
        AiProvider.Anthropic,
        AiProvider.Google,
        AiProvider.XAI -> true
        else -> false
    }

    // =====================================================================
    // Save
    // =====================================================================

    /**
     * Persist the profile and the setup stage, then draw the birth screen.
     *
     * DELIBERATELY DOES NOT MARK THE PROFILE COMPLETE. Completion is what opens
     * the app gate, and marking it here meant the gate was already open while the
     * birth screen was still on screen, leaving the human stranded: they could not
     * go back to the API step to fix a key they had skipped, because by the app's
     * own reckoning they had already finished. So this writes everything durably
     * and stops one step short. [openGuru] is the only thing that finishes.
     */
    fun finish() {
        if (_state.value.saving) return
        viewModelScope.launch {
            _state.value = _state.value.copy(saving = true, error = null)
            try {
                persistProfile(complete = false)
                _state.value = _state.value.copy(saving = false, phase = OnboardingPhase.BIRTH)
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    saving = false,
                    error = "Could not save your profile. Your answers are still here. Try again."
                )
            }
        }
    }

    /**
     * The birth screen's only action: finish onboarding and let the app open.
     *
     * Re-persists before completing, because the human may have gone back to the
     * setup stage after [finish] and typed a key that has not been written yet.
     * The gate is confirmed readable before the caller is told to open, so the app
     * can never launch over a profile the repository does not yet agree is done.
     */
    fun openGuru(onReady: () -> Unit) {
        if (_state.value.saving) return
        viewModelScope.launch {
            _state.value = _state.value.copy(saving = true, error = null)
            try {
                persistProfile(complete = true)

                if (!userProfileRepository.isOnboardingComplete()) {
                    throw IllegalStateException("Profile saved but not marked complete.")
                }

                _state.value = _state.value.copy(saving = false)
                onReady()

                // Brain sync last, never blocking the gate. The boot pass retries
                // anything that did not land, so nothing is lost if the vault is
                // still opening.
                viewModelScope.launch {
                    runCatching { profileBrainSync.syncAll() }
                }
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    saving = false,
                    error = "Could not save your profile. Your answers are still here. Try again."
                )
            }
        }
    }

    /**
     * Fold the draft into the profile, write the setup stage, and optionally
     * close the gate. Shared by [finish] and [openGuru] so the two can never
     * drift apart on what "saved" means.
     */
    private suspend fun persistProfile(complete: Boolean) {
        // 1. Fold every draft answer into the working profile.
        var profile = workingProfile
        _state.value.draft.forEach { (key, value) ->
            profile = UserProfileAnswers.write(profile, key, value)
        }

        // 2. The preferred name becomes the ${human} token everywhere.
        val preferredName = profile.preferredName.trim()
        if (preferredName.isNotEmpty()) {
            savePreference(
                stringPreferencesKey(PrefsConstants.USER_NAME_KEY),
                preferredName
            )
        }

        // 3. The setup stage, written as real preferences.
        writePowerStage()

        // 4. Durable profile write.
        userProfileRepository.saveProfile(profile)
        workingProfile = profile

        // 5. Completion opens the app gate, and only this does.
        if (complete) userProfileRepository.markComplete()
    }

    /**
     * Write every connected setup row into its real preference.
     *
     * Each branch writes exactly the keys the send path already reads, so
     * onboarding configures the app with the same mechanism the Settings
     * screen uses. Nothing new is invented here.
     */
    private suspend fun writePowerStage() {
        val draft = _state.value.powerDraft

        // WHAT IS TYPED IS WHAT IS SAVED.
        //
        // This used to write the model only when its row had been toggled On, and
        // the row refused to switch on unless every field was already filled. So
        // a human who picked a provider, pasted a key or a URL, typed a model name
        // and tapped forward had written nothing at all, and the app came up with
        // no model. That gate is gone. A field that has something in it is saved;
        // a field that is blank is left alone, which also means a blank never
        // erases a value somebody saved before.
        //
        // The keys are exactly the ones the Settings screen writes, on purpose:
        // AI_PROVIDER_KEY as an Int of the provider id, then the three BYO strings.
        // One storage shape, one reader, so onboarding and Settings can never
        // disagree about where the model lives.

        val provider = chosenProvider()
        if (provider != AiProvider.None) {
            savePreference(PrefsKey.IntKey(PrefsConstants.AI_PROVIDER_KEY), provider.id)
        }

        val key = draft["provider_key"].orEmpty().trim()
        if (key.isNotEmpty()) {
            savePreference(stringPreferencesKey(PrefsConstants.BYO_API_KEY_KEY), key)
        }

        val model = draft["model_name"].orEmpty().trim()
        if (model.isNotEmpty()) {
            savePreference(stringPreferencesKey(PrefsConstants.BYO_MODEL_NAME_KEY), model)
        }

        val url = draft["provider_url"].orEmpty().trim()
        if (url.isNotEmpty()) {
            savePreference(stringPreferencesKey(PrefsConstants.BYO_BASE_URL_KEY), url)
        }

        // The switches are genuine booleans, so they always record their current
        // position. "Off" is a decision and has to be written too: leaving a stale
        // true behind because the row happened to be off would be a lie about what
        // the human chose.
        savePreference(
            PrefsKey.BooleanKey(PrefsConstants.AI_TOOLS_ENABLED_KEY),
            _state.value.powerOn.contains("skills")
        )
        savePreference(
            PrefsKey.BooleanKey(PrefsConstants.VISION_ENABLED_KEY),
            _state.value.powerOn.contains("vision")
        )

        val language = draft["language"].orEmpty().trim()
        if (language.isNotEmpty()) {
            savePreference(stringPreferencesKey(PrefsConstants.APP_LANGUAGE_KEY), language)
        }
    }

    /** The questions the human actually answered, for the review page. */
    fun answeredQuestions(): List<Pair<OnboardingQuestion, String>> {
        val draft = _state.value.draft
        return OnboardingQuestionCatalogue.questions.mapNotNull { question ->
            val answer = draft[question.key]?.trim().orEmpty()
            if (answer.isBlank()) null else question to answer
        }
    }

    /** True once the profile is saved and the gate should open. */
    fun isComplete(onComplete: (Boolean) -> Unit) {
        viewModelScope.launch { onComplete(userProfileRepository.isOnboardingComplete()) }
    }

    /** The power rows the catalogue declares, for the setup screen. */
    fun powerItems() = OnboardingQuestionCatalogue.powerItems

    /** The kind behind a power row key. */
    fun powerKind(key: String): PowerKind? =
        OnboardingQuestionCatalogue.powerItems.firstOrNull { it.key == key }?.kind

    /**
     * Whether tapping a row would do anything at all.
     *
     * IT NO LONGER GATES THE SAVE, and that is deliberate. A row is connectable
     * when tapping it toggles it, which is always. The old version refused to
     * switch a row on until every field was complete, and the save then only
     * wrote rows that were switched on, so an unfinished model panel silently
     * saved nothing. What a row has behind it is now answered by [hasContent],
     * which the panel uses to describe itself, never to block a write.
     */
    fun canConnect(key: String): Boolean = true

    /**
     * Whether a row currently has enough filled in to be worth a tick on screen.
     *
     * Display only. It never decides whether a value is written, because a
     * half-filled panel still has real answers in it and losing them was the bug.
     */
    fun hasContent(key: String): Boolean {
        val draft = _state.value.powerDraft
        return when (key) {
            "provider" -> draft["provider"].orEmpty().isNotBlank() ||
                draft["model_name"].orEmpty().isNotBlank()
            "language" -> draft["language"].orEmpty().isNotBlank()
            else -> true
        }
    }
}
