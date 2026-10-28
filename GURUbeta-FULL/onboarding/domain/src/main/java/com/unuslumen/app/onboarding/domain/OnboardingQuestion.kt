// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.onboarding.domain

/**
 * OnboardingQuestion — the shape of one thing GURU asks during first boot.
 *
 * A question is pure data. The catalogue that supplies the questions lives in
 * OnboardingQuestionCatalogue, deliberately separate from any screen, so the
 * wording, order and field set can be rewritten without touching a composable.
 *
 * Every answer is stored twice on purpose:
 *  - the typed column on guru_user_profile (deterministic, always injected)
 *  - one brain fact written under [brainCategory] (searchable, decays, dreams)
 *
 * [injectInBlock] decides whether the answer also joins the short profile
 * block that rides every single send. Keep that list tight: everything here
 * costs context on every message forever.
 *
 * [chapter] is the index into OnboardingQuestionCatalogue.chapters. The
 * presentation layer uses it for the colour field, the chapter rail and the
 * constellation node colour. Adding a chapter never touches a screen.
 */
data class OnboardingQuestion(
    /** Stable machine key. This is the Room column name and the brain fact tag. */
    val key: String,
    /** The question as GURU asks it. Wording lives in the catalogue. */
    val prompt: String,
    /** Optional second line under the prompt, for clarification or tone. */
    val helper: String = "",
    /** How the answer is collected. See [QuestionInput]. */
    val input: QuestionInput = QuestionInput.FreeText,
    /** Which chapter this question belongs to, as an index into chapters. */
    val chapter: Int = 0,
    /** Shown when the flow reviews every answer before saving. */
    val reviewLabel: String,
    /** Brain fact category. Defaults to the profile category. */
    val brainCategory: String = PROFILE_CATEGORY,
    /** Whether this answer joins the always-injected profile block. */
    val injectInBlock: Boolean = false,
    /** Whether the flow refuses to advance without an answer. */
    val required: Boolean = false,
    /** How the answer becomes a sentence for the brain. {value} is substituted. */
    val factTemplate: String = "{value}",
    /** Placeholder inside the text field. */
    val placeholder: String = "",
    /** Max characters kept. Guards the vault against a pasted essay. */
    val maxLength: Int = 1200,
    /** Choices for Choice, MultiChoice, Chips and Tabs inputs, in display order. */
    val choices: List<QuestionChoice> = emptyList(),
    /** Slider only: the words at the left end, e.g. "Gentle". */
    val sliderMinLabel: String = "",
    /** Slider only: the words at the right end, e.g. "Straight to it". */
    val sliderMaxLabel: String = "",
    /** Slider only: how many stops there are, numbered 1 to this. */
    val sliderSteps: Int = 10,
    /**
     * Slider only: the words for each stop, lowest first.
     *
     * A slider without phrases stores a bare position, and "7 out of 10" tells a
     * model almost nothing. A slider with phrases stores the phrase itself, and
     * "Don't dress it up, I can handle it" tells it exactly how to speak. The
     * list length is the number of stops, so one entry per stop, in order.
     */
    val sliderPhrases: List<String> = emptyList(),
    /** Skip the brain write for answers that make no sense as a standalone fact. */
    val writeToBrain: Boolean = true,
    /**
     * Chips only: offer an extra "Something else" chip that turns into a text
     * field when tapped.
     *
     * Exists because no fixed list is ever complete. A list of languages, or
     * nationalities, or anything else a person can be, will always be missing
     * somebody. Without this, the missing person either picks the wrong answer
     * or leaves it blank, and both are worse than a line of text.
     *
     * The typed value is stored in the same comma-joined column as the tapped
     * ones, so nothing about storage changes. Anything in the answer that is
     * not one of the declared [choices] is, by definition, something they
     * typed.
     */
    val allowOtherText: Boolean = false,
    /**
     * Present when this list question is answered through the catalogue picker
     * rather than the plain tile grid: chosen bars at the top, an add-your-own
     * field, then the choices underneath.
     *
     * It is data rather than a flag because each of the three bands carries a
     * line of copy, and every word a human reads in this flow comes from the
     * catalogue. A question with no chrome here keeps the tile grid, so nothing
     * else in the flow has to change.
     */
    val picker: PickerChrome? = null
) {
    /**
     * The section name, derived from the chapter so a question can never drift
     * out of step with the chapter it draws its colour from. Kept as a property
     * so every existing caller that reads `section` keeps working.
     */
    val section: String
        get() = OnboardingQuestionCatalogue.chapterName(chapter)

    /**
     * The answer as a model should read it, for every answer lane to share.
     *
     * A slider is stored as a stop number, and a number alone says nothing. When
     * the question carries phrases, the phrase IS the answer, and the number
     * rides beside it so the model sees both the words they picked and where
     * that sits on the scale. Everything else passes through trimmed.
     */
    fun renderAnswer(raw: String): String {
        val trimmed = raw.trim()
        if (input != QuestionInput.Slider || sliderPhrases.isEmpty()) return trimmed

        val position = trimmed.toIntOrNull() ?: return trimmed
        val stop = position.coerceIn(1, sliderPhrases.size)
        return "${sliderPhrases[stop - 1]} ($stop of ${sliderPhrases.size})"
    }

    /**
     * The answer as a human should read it on the review page.
     *
     * Identical to [renderAnswer] except for a capture, where the stored answer
     * is an absolute file path. Nobody wants to read
     * `/data/user/0/.../selfie_1770000000.jpg` back at themselves, so a capture
     * reports that a photo was taken and where it lives. The brain and the
     * profile block still receive the path itself, because that is what lets
     * the model find the file.
     */
    fun displayAnswer(raw: String): String {
        val trimmed = raw.trim()
        return when (input) {
            QuestionInput.Capture -> if (trimmed.isEmpty()) "" else "Photo saved on this device"
            QuestionInput.Date -> formatDay(trimmed)
            else -> renderAnswer(raw)
        }
    }

    /**
     * A stored ISO day rendered the way a person writes one, e.g. 31 December 1994.
     *
     * Returns the raw string unchanged when it is not a parseable ISO day, rather
     * than swallowing it: a value that cannot be parsed is a bug worth seeing,
     * and silently blanking it would hide the bug.
     */
    private fun formatDay(iso: String): String {
        if (iso.isBlank()) return ""
        return runCatching {
            java.time.LocalDate.parse(iso)
                .format(
                    java.time.format.DateTimeFormatter.ofPattern(
                        "d MMMM yyyy",
                        java.util.Locale.UK
                    )
                )
        }.getOrDefault(iso)
    }

    companion object {
        const val PROFILE_CATEGORY = "user_profile"
        const val PROFILE_DOMAIN = "identity"
        const val PROFILE_TOPIC = "profile"
    }
}

/**
 * How a question collects its answer.
 *
 * The first six are the original field kinds. Chips and Tabs are the v5
 * text-first controls: plain words with a growing underline, no pill, no box.
 * Both store comma-joined values in the one text column, so adding them needed
 * no schema change to the profile table.
 */
sealed interface QuestionInput {
    /** A plain multi-line text field. */
    data object FreeText : QuestionInput

    /** A single-line text field, for names and short answers. */
    data object ShortText : QuestionInput

    /** Pick one from [OnboardingQuestion.choices]. */
    data object Choice : QuestionInput

    /** Pick any number from [OnboardingQuestion.choices]. */
    data object MultiChoice : QuestionInput

    /** Yes, no, or decline. Stored as the choice value. */
    data object YesNo : QuestionInput

    /** A whole number, for age and similar. */
    data object Number : QuestionInput

    /** Any number of words, chosen by tapping. Multi-select, comma-joined. */
    data object Chips : QuestionInput

    /** One word, chosen by tapping. Single-select. */
    data object Tabs : QuestionInput

    /**
     * A value between two written ends. Used wherever the honest answer is a
     * position rather than a choice: blunt against gentle, morning against
     * night, never against always.
     *
     * Stored as the whole number the thumb sits on, in the same text column
     * every other answer uses, so no schema change was needed.
     */
    data object Slider : QuestionInput

    /**
     * A photo taken with the device camera.
     *
     * The answer stored is the absolute path of the JPEG the capture wrote, or
     * an empty string if the human declined the camera. It is a file rather than
     * words because the point of the answer is that something can look at it
     * later: the reference photo is what lets GURU check it is really talking to
     * their human before anything sensitive.
     *
     * The photo is written into the app's own internal storage, not the shared
     * gallery, so it never appears in anyone else's photo app and is removed
     * with the app.
     */
    data object Capture : QuestionInput

    /**
     * A calendar date, stored as an ISO-8601 day string (YYYY-MM-DD).
     *
     * Used for a birthday, where a free-text box is the wrong instrument: the
     * human should not have to guess a format, and "31/12/94", "31-12-1994" and
     * "Dec 31 1994" are three spellings of one fact that a model then has to
     * reconcile. The picker guarantees one shape.
     *
     * ISO rather than a timestamp, because a birthday has no time and no
     * timezone. Storing a midnight epoch would invent both.
     */
    data object Date : QuestionInput
}

/**
 * One selectable answer. [value] is what gets stored, [label] what is shown.
 *
 * [group] is optional and only used by the catalogue picker. When a question's
 * choices carry a group, the picker renders them as separated sections with a
 * heading each, instead of one flat grid. That is what makes a list of fifty
 * languages scannable: you read a continent at a time rather than a wall.
 *
 * [symbol] is optional too, and is whatever small mark belongs to that choice:
 * a flag, an icon character, nothing. It is a plain string so this stays pure
 * JVM with no resource dependency.
 */
data class QuestionChoice(
    val value: String,
    val label: String,
    val group: String = "",
    val symbol: String = ""
)

/**
 * The copy and the behaviour of the catalogue picker, when a list question uses it.
 *
 * The picker is three bands stacked, and the order is the design: what you have
 * chosen at the top as full-width bars, a field to add your own, then the
 * catalogue below. Without copy for those three bands the screen cannot render,
 * so the chrome travels with the question rather than living inside the screen.
 *
 * [ordered] turns the chosen band into a numbered list where the first entry is
 * the primary one and moving an entry up or down is how the primary gets changed.
 * That plus [markFirst] is what makes "first in the list means native" work for
 * languages without a separate question. For anything where order carries no
 * meaning, both are off and the chosen band is an unordered set.
 */
data class PickerChrome(
    /** Heading over the chosen bars, e.g. "Your languages". */
    val chosenLabel: String,
    /** Shown in place of the bars when nothing has been picked. */
    val emptyLabel: String,
    /** Placeholder inside the add-your-own field. */
    val addHint: String,
    /** Heading over the grouped catalogue. */
    val catalogueLabel: String,
    /** Number the chosen bars and let them be reordered. */
    val ordered: Boolean = false,
    /** Badge the first chosen bar, e.g. "native". Requires [ordered]. */
    val markFirst: Boolean = false,
    /** The word in that badge. */
    val firstMarkLabel: String = ""
)

/**
 * One chapter of the journey: the colour field, the rail segment and the
 * constellation colour for everything answered inside it. Colour is a hex
 * string so this stays pure JVM and Android-free.
 */
data class OnboardingChapter(
    val id: String,
    val name: String,
    /** Saturated field colour, full-bleed behind the whole screen. */
    val colour: String,
    /** Ink used on top of [colour]. Dark on light fields, light on dark fields. */
    val ink: String,
    /** The chapter's ambient ground, used for the content area. */
    val ground: String
)

/**
 * One line in the power-up stage: something the human can connect so the app
 * just works after onboarding. [kind] decides what the tap actually does, and
 * every kind performs a real write or a real permission request. Nothing here
 * is a decorative toggle.
 */
data class PowerItem(
    val key: String,
    val title: String,
    val caption: String,
    val kind: PowerKind,
    /**
     * For [PowerKind.PROVIDER] only: every provider this row can connect.
     *
     * It lives on the item rather than being hardcoded in the screen so the
     * list of things GURU can talk to is one authored list, in the catalogue,
     * exactly like a question's choices. The [QuestionChoice.value] is the
     * stored name and the label is what the human reads.
     */
    val providers: List<QuestionChoice> = emptyList()
)

/** What a power row does when tapped. */
enum class PowerKind {
    /**
     * THE MODEL ROW — the one that decides whether the app works after
     * onboarding.
     *
     * It replaces the old MODEL_KEY and LOCAL_BRAIN pair, which between them
     * offered two of the seven providers Settings offers: OpenAI and Ollama,
     * with no route to Anthropic, Google, xAI or a generic OpenAI-compatible
     * endpoint. A human who wanted Claude had to finish onboarding and then go
     * hunting in Settings, which is the exact trip this stage exists to remove.
     *
     * One row, every provider, and the fields underneath change to match the
     * one you picked, so the panel always asks for exactly what that provider
     * needs and never for what it does not.
     */
    PROVIDER,
    /** Enables GURU's tool surface. */
    SKILLS,
    /** Enables screen vision alongside every message. */
    VISION,
    /** Records the language he should answer in. */
    LANGUAGE
}

/**
 * OnboardingBeat — one tap of the welcome pack.
 *
 * The intro is not a set of titled pages. It is plain prose on a colour field,
 * revealed a line at a time, advanced by a tap. A beat carries only the words
 * and the field they sit on, so the flow can be rewritten without touching a
 * screen.
 *
 * [acceptsDeal] marks the beat where a tap records consent. It sits on whichever
 * beat carries the deal language; moving it is a catalogue change, never a code
 * change.
 */
data class OnboardingBeat(
    /** Stable id for reveal resets and future deep links. */
    val id: String,
    /** Full-bleed field colour as a hex string. */
    val field: String,
    /** Ink used on top of the field. */
    val ink: String,
    /** The lines, revealed in order. Plain prose, one paragraph per line. */
    val lines: List<OnboardingBeatLine>,
    /** True where tapping through records the deal acceptance. */
    val acceptsDeal: Boolean = false,
    /** Draw the GURU mark alone on the field, growing, instead of any prose. */
    val heroLogo: Boolean = false
)

/** One paragraph inside a beat. [emphasis] decides scale, weight and ink alpha. */
data class OnboardingBeatLine(
    val text: String,
    val emphasis: BeatEmphasis = BeatEmphasis.Normal
)

/** How a beat line is set. */
enum class BeatEmphasis {
    /** Body prose: the standard reading size. */
    Normal,
    /** Body prose at reduced presence, for the supporting paragraph. */
    Soft,
    /** The largest setting, for a word or two. */
    Hero,
    /** The small line above a hero, for an opening like "Introducing…". */
    HeroSub
}
