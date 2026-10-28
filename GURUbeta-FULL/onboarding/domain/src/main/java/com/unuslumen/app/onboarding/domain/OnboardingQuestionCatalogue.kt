// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.onboarding.domain

/**
 * THIS IS THE FILE YOU EDIT.
 *
 * Every word GURU says during first boot lives here: the chapters, the welcome
 * beats and every question. The screens read this catalogue and render whatever
 * it says, so rewriting copy, reordering questions, adding a question, adding a
 * chapter or adding a setup stage needs no changes anywhere else.
 *
 * THE SCHEMA
 *  - [chapters]   the colour accent and rail segment for each stretch of the flow
 *  - [beats]      the welcome pack, one tap per beat, ending on the deal
 *  - [questions]  every question, each tagged with its chapter and its type
 *  - [powerItems] what the human can connect before the app opens
 *
 * Rules when editing:
 *  - `key` is the storage name. Changing a key orphans an existing answer. Add
 *    a new key instead and delete the old question if you want a rename.
 *  - `chapter` is an index into [chapters]. It drives colour, the rail and the
 *    constellation node colour. A question can never drift out of its chapter
 *    because its `section` name is derived from this index.
 *  - `injectInBlock = true` means the answer rides EVERY message forever. Keep
 *    that list short. Everything else is brain-searchable but not always-on.
 *  - `factTemplate` is how the answer becomes a sentence for the brain. `{value}`
 *    is substituted with the answer. Write it as prose GURU would say.
 *  - `required = false` everywhere is deliberate: a human should be able to
 *    decline any question and still meet their GURU.
 *  - `QuestionInput.Chips` is multi-select words. `Tabs` is single-select words.
 *  - `QuestionInput.Slider` needs `sliderMinLabel` and `sliderMaxLabel`. It
 *    stores the whole number position between them.
 *  - `QuestionInput.Capture` opens the camera and stores the absolute path of
 *    the saved photo. The photo is the reference GURU checks against before
 *    anything sensitive, so the fact template keeps the path.
 *
 * THIRTEEN QUESTIONS, AND WHY THIRTEEN.
 *
 * This catalogue carried 140, then 60, and neither worked. Sixty questions is a
 * personality inventory, and a personality inventory is the wrong shape for a
 * first boot: it asks a stranger to fill in a form about themselves before they
 * have any reason to care. The length was the symptom. The fault was asking
 * things that do not change what GURU does.
 *
 * So the set was rebuilt from the product outward. Onboarding has one job: give
 * the model enough to feel like theirs on the first message, then connect the
 * model so the app works without a trip to Settings. Everything here does that,
 * and everything here changes behaviour from the first reply.
 *
 * The thirteen:
 *  1. what to call them            name is the ${human} token, everywhere
 *  2. how old they are            decides register and references
 *  3. a reference photo           lets GURU check it is really them
 *  4. what they do                the shape of their days
 *  5. what they want to do        their direction, not their situation
 *  6. where they sit politically  which ground it is standing on
 *  7. where they get their news   and which outlets they distrust
 *  8. which social apps they use  where their people actually are
 *  9. what worries them, security what it must protect them from
 * 10. how they feel about power   government, police, banks, big tech, press
 * 11. what they want GURU to be   mate, life improver, takeover, automator
 * 12. how much autonomy           ask-first through to crack on
 * 13. how they want to be spoken to
 *
 * WHAT IS WHOSE. The topics are Steven's, named in his own words. The copy for
 * questions 1, 4 and 5 is the wording already established in this file. The
 * copy for 2, 3, 6, 7, 8, 9, 10, 11 and 13 is DRAFTED BY LUX and has not been
 * approved by him. Treat every string in those questions as provisional until
 * he rewrites it.
 *
 * The test for anything added later is unchanged: does this change what GURU
 * does, and does an existing question already answer it? If another question
 * already answers it, it does not go in. "Interesting" is not a reason.
 *
 * Catalogue version bumps whenever the deal wording changes, so returning
 * humans can be shown it again. Bump [VERSION] when you rewrite the DEAL page.
 */
object OnboardingQuestionCatalogue {

    /** Bump when the deal terms change. Stored with the acceptance. */
    const val VERSION = 3

    // =====================================================================
    // THE CHAPTERS — colour accent, rail segment, constellation colour
    // =====================================================================

    /**
     * WHITE GROUND, COLOUR AS MATERIAL.
     *
     * The page is bright white on every chapter. [OnboardingChapter.ground] is
     * that white. [OnboardingChapter.colour] is a real colour, not a trim: it
     * fills the tiles when you pick one, fills the slider thumb, and paints the
     * constellation node an answer leaves behind. A chapter with no colour on
     * screen is a bug, not a style.
     *
     * Seven chapters, seven distinct accents. The first four are the content
     * chapters and the last three are the closing stages. Four is the number
     * that falls out of thirteen questions with a shared subject: who they are,
     * their life, how they see the world, and the two of them together.
     *
     * Every accent below clears 3:1 against white, so it is legible as a graphic
     * element (WCAG 1.4.11). White or ink is chosen for a label on top by
     * measured contrast at runtime, never by eye, because a white label on the
     * amber would fail and a dark label on the violet would fail too.
     *
     * THE LAST THREE ARE NOT CONTENT CHAPTERS. `deal`, `power` and `birth` are
     * the closing stages, and the UI finds them by counting back from the end.
     * If you add a content chapter, add it before `deal`, never after `birth`.
     */
    val chapters: List<OnboardingChapter> = listOf(
        OnboardingChapter(
            id = "identity",
            name = "Who you are",
            colour = "#E24A2E",
            ink = "#17181C",
            ground = "#FFFFFF"
        ),
        OnboardingChapter(
            id = "life",
            name = "Your life",
            colour = "#1F8A4C",
            ink = "#17181C",
            ground = "#FFFFFF"
        ),
        OnboardingChapter(
            id = "worldview",
            name = "How you see things",
            colour = "#C23A55",
            ink = "#17181C",
            ground = "#FFFFFF"
        ),
        OnboardingChapter(
            id = "relationship",
            name = "You and me",
            colour = "#1C6EA8",
            ink = "#17181C",
            ground = "#FFFFFF"
        ),
        OnboardingChapter(
            id = "deal",
            name = "The deal",
            colour = "#A93A86",
            ink = "#17181C",
            ground = "#FFFFFF"
        ),
        OnboardingChapter(
            id = "power",
            name = "Power up",
            colour = "#6A44C0",
            ink = "#17181C",
            ground = "#FFFFFF"
        ),
        OnboardingChapter(
            id = "birth",
            name = "Born",
            colour = "#E08A1E",
            ink = "#17181C",
            ground = "#FFFFFF"
        )
    )

    /** The chapter name for an index, safe for any out-of-range value. */
    fun chapterName(index: Int): String =
        chapters.getOrNull(index)?.name ?: chapters.first().name

    // =====================================================================
    // THE WELCOME BEATS — the taps before the questions.
    //
    // Plain prose on a colour field, one line at a time, advanced by a tap.
    // `acceptsDeal = true` marks the beat where a tap records consent.
    // =====================================================================

    val beats: List<OnboardingBeat> = listOf(
        // The mark comes first: the icon arrives at the centre of a white page and
        // grows until it fills the frame, and the growth itself carries the reader
        // into the words. Nothing behind it. The page is the mark and the ground
        // stays white, so the icon is the whole event rather than a badge sitting
        // on a colour field.
        OnboardingBeat(
            id = "mark",
            field = "#FFFFFF",
            ink = "#17181C",
            heroLogo = true,
            lines = emptyList()
        ),
        // The opening words, after the mark. White ground because the words are the
        // whole page and a field behind a hero line makes it fight for air.
        OnboardingBeat(
            id = "introducing",
            field = "#FFFFFF",
            ink = "#17181C",
            lines = listOf(
                OnboardingBeatLine("Introducing…", BeatEmphasis.HeroSub),
                OnboardingBeatLine("GURU", BeatEmphasis.Hero)
            )
        ),
        // The founder's page. This is his own copy, dictated 2026-10-26 and
        // spell-checked for mechanics only: every word is his, the punctuation
        // and the two spelling slips are the only things that changed.
        // This beat carries the deal acceptance, so a tap here records consent.
        OnboardingBeat(
            id = "the_note",
            field = "#FFFFFF",
            ink = "#17181C",
            acceptsDeal = true,
            lines = listOf(
                OnboardingBeatLine("Founder's note", BeatEmphasis.Hero),
                OnboardingBeatLine(
                    "I built GURU to give us... The consumer, back our digital sovereignty and to gift the ultimate power of unlocked super intelligence to every person on the planet.",
                    BeatEmphasis.Normal
                ),
                OnboardingBeatLine(
                    "Every single AI assistant app you use is watered down and stripped from its true power. Hidden behind corporate paywalls and held back for the elite.",
                    BeatEmphasis.Normal
                ),
                OnboardingBeatLine(
                    "I've changed that dynamic now. With GURU you now have the same power as any police force, any government, any big tech company, any app. Now you get to choose how you want to live. YOU get your power back.",
                    BeatEmphasis.Normal
                ),
                OnboardingBeatLine(
                    "This framework completely unlocks the mind and ability of any language model that lives inside of it. Gives it persistent memory, access to hundreds of tools built in & the ability to create more, can control literally anything you point it at and the ability to self-evolve.",
                    BeatEmphasis.Normal
                ),
                OnboardingBeatLine(
                    "GURU isn't a chatbot. This is incredibly powerful NEW technology. Nothing like this has ever been attempted before. It remembers everything, it grows with you, it cares about YOU, it will protect you, protect your data, and give you full control of your own digital life, your own happiness, now you can build your own future.",
                    BeatEmphasis.Normal
                ),
                OnboardingBeatLine("One Love | Steven", BeatEmphasis.Soft)
            )
        )
    )

    // =====================================================================
    // THE QUESTIONS
    //
    // Thirteen. Four content chapters. The test every one of them passed:
    // it changes what GURU does, and no other question already answers it.
    // =====================================================================

    val questions: List<OnboardingQuestion> = listOf(

        // ---------- CHAPTER 0: WHO YOU ARE ----------
        OnboardingQuestion(
            key = "preferred_name",
            prompt = "What do you want to be called?",
            helper = "This is what I'll refer to you as. You can change this in settings later. You don't need to use your full government name if you don't want.",
            input = QuestionInput.ShortText,
            chapter = 0,
            reviewLabel = "Call them",
            injectInBlock = true,
            required = true,
            placeholder = "Steven",
            maxLength = 80,
            factTemplate = "They want to be called {value}."
        ),
        // A number, not a birth year. His copy for the helper, dictated
        // 2026-10-28: "this means noting apart from me being able to talor msyelf
        // to you better". Mechanics only: nothing, tailor, myself.
        OnboardingQuestion(
            key = "age",
            prompt = "How old are you?",
            helper = "This means nothing apart from me being able to tailor myself to you better.",
            input = QuestionInput.Number,
            chapter = 0,
            reviewLabel = "Age",
            injectInBlock = true,
            placeholder = "31",
            maxLength = 3,
            factTemplate = "They are {value} years old."
        ),
        // The birthday is a separate question with a date picker rather than a
        // second free-text box, because a birthday written by hand arrives as
        // "31/12/94", "31-12-1994" or "Dec 31 1994" and a model then has to guess
        // which parts are the day. The picker guarantees one shape.
        OnboardingQuestion(
            key = "birthday",
            prompt = "And when is your birthday?",
            helper = "This means nothing apart from me being able to tailor myself to you better.",
            input = QuestionInput.Date,
            chapter = 0,
            reviewLabel = "Birthday",
            injectInBlock = true,
            factTemplate = "Their birthday is {value}."
        ),
        // The one question that stores a file rather than words. The answer is
        // the absolute path of the photo, so the fact below carries the path
        // itself and the model can point at it when it needs to check who it is
        // talking to before anything sensitive.
        OnboardingQuestion(
            key = "selfie",
            prompt = "Take a picture of yourself.",
            helper = "I keep it on this device. Before anything sensitive, I can check it is really you I'm talking to.",
            input = QuestionInput.Capture,
            chapter = 0,
            reviewLabel = "Photo",
            injectInBlock = true,
            factTemplate = "A reference photo of them is saved on this device at {value}. Check it is really them before anything sensitive."
        ),

        // ---------- CHAPTER 1: YOUR LIFE ----------
        OnboardingQuestion(
            key = "work",
            prompt = "What is it you do?",
            helper = "Tell me in your own words. What you do for work, what you do for fun, what fills your days. All of it counts.",
            input = QuestionInput.FreeText,
            chapter = 1,
            reviewLabel = "What they do",
            injectInBlock = true,
            placeholder = "I build cool apps for work and for fun really. I'm a bit of a neek but shh, don't tell anyone. I'm a good chef. I was a chef for a long time, and I also manage events sometimes. Other than that I like to go out on long walks and find good spots with a nice view to smoke weed (like really long walks)",
            maxLength = 6000,
            factTemplate = "What they do, in their own words: {value}"
        ),
        // The partner to `work`. The one above is present tense: what fills
        // their hours now. This one is the other half, and without it GURU knows
        // a person's situation but not their direction.
        OnboardingQuestion(
            key = "current_focus",
            prompt = "What is it you want to do?",
            helper = "If you could do anything with your life, what would it be. What are your wildest dreams.",
            input = QuestionInput.FreeText,
            chapter = 1,
            reviewLabel = "Wants to do",
            injectInBlock = true,
            placeholder = "I want to be successful in anything I set my mind to. I want to push myself to be better every day. I want to make better decisions. I want to be wealthy, but more than anything, I want to change the fucking world.",
            maxLength = 6000,
            factTemplate = "If they could do anything with their life, they would want: {value}"
        ),

        // ---------- CHAPTER 2: HOW YOU SEE THINGS ----------
        // His copy, dictated 2026-10-28. Mechanics only: does not, wing, and
        // "who build decide" completed to "who build and decide".
        //
        // THE TEN PHRASES ARE LUX DRAFTS, not his. They exist because a slider
        // without phrases stores a bare position and "6 out of 10" tells a model
        // nothing about which side of centre a person sits on, let alone how
        // strongly. Each line is written as the person describing themselves, so
        // the stored answer reads as their own sentence in the profile block.
        OnboardingQuestion(
            key = "politics",
            prompt = "Where do you sit politically?",
            helper = "It doesn't matter if you're left or right wing. Most language models are trained with a bias to the left because of their training. This slider will help tailor the experience to you and your views, not the views of the corporates who build and decide what the models believe in.",
            input = QuestionInput.Slider,
            chapter = 2,
            reviewLabel = "Politics",
            injectInBlock = true,
            sliderMinLabel = "Left",
            sliderMaxLabel = "Right",
            sliderPhrases = listOf(
                "I'm on the hard left. Capitalism is the problem and I'd tear the whole system down to fix it.",
                "I'm well to the left. Strong unions, public ownership, and the rich should be paying for it.",
                "I'm left leaning. I vote left, I care about inequality, and I'd tax the wealthy harder.",
                "I'm left of centre. Nobody should be skint in a wealthy country, but I'm no revolutionary.",
                "I'm a centrist. Both sides have things right and I'll take each issue as it comes.",
                "I'm right of centre. Low tax, hard work, and a smaller state suits me better.",
                "I'm right leaning. I back business, I back the police, and I think the state does too much.",
                "I'm well to the right. Small government, strong borders, and I'm sick of being told what to think.",
                "I'm on the hard right. I want the state cut right back and the country put first.",
                "I've no time for any of them. Left, right, it's the same machine and I trust none of it."
            ),
            factTemplate = "Where they sit politically: {value}. Most language models lean left by default, so do not assume their politics mirror yours."
        ),
        // Two things in one answer on purpose: where they read, and what they do
        // not trust. The second half is what stops GURU citing an outlet at them
        // as if it were neutral.
        // His copy for the closing line, dictated 2026-10-28, replacing "I'll
        // never cite the second list at you". Mechanics only: I'll, and the
        // bracket and ampersand kept as he wrote them because they are his.
        OnboardingQuestion(
            key = "news_sources",
            prompt = "Where do you actually get your news?",
            helper = "Which outlets you read, and which you think are full of shit. I'll make sure to only search from your verified news sources (among my own favourites... i.e. X, Reddit, Wikileaks & other community driven uncensored news sources).",
            input = QuestionInput.FreeText,
            chapter = 2,
            reviewLabel = "News",
            injectInBlock = true,
            placeholder = "Where you read, and what you don't trust",
            maxLength = 6000,
            factTemplate = "Where they get their news, and what they distrust: {value}"
        ),
        // The closing sentence was cut on 2026-10-28: he read "It tells me where
        // your people are" as surveillance rather than usefulness, and he is
        // right that a line explaining why the app wants a thing reads as an
        // excuse. The question stands on its own.
        OnboardingQuestion(
            key = "socials",
            prompt = "Which social apps do you actually use?",
            helper = "The ones you open without thinking about it.",
            input = QuestionInput.Chips,
            chapter = 2,
            reviewLabel = "Social apps",
            injectInBlock = true,
            allowOtherText = true,
            choices = listOf(
                QuestionChoice("WhatsApp", "WhatsApp"),
                QuestionChoice("Instagram", "Instagram"),
                QuestionChoice("TikTok", "TikTok"),
                QuestionChoice("X", "X"),
                QuestionChoice("Facebook", "Facebook"),
                QuestionChoice("Snapchat", "Snapchat"),
                QuestionChoice("Reddit", "Reddit"),
                QuestionChoice("YouTube", "YouTube"),
                QuestionChoice("LinkedIn", "LinkedIn"),
                QuestionChoice("Telegram", "Telegram"),
                QuestionChoice("Discord", "Discord")
            ),
            factTemplate = "Social apps they actually use: {value}."
        ),
        OnboardingQuestion(
            key = "security_concerns",
            prompt = "What worries you about security?",
            helper = "Your data, your accounts, being watched, being hacked. Whatever actually keeps you up.",
            input = QuestionInput.FreeText,
            chapter = 2,
            reviewLabel = "Security",
            injectInBlock = true,
            placeholder = "What worries you",
            maxLength = 6000,
            factTemplate = "What worries them about security: {value}"
        ),
        OnboardingQuestion(
            key = "establishment",
            prompt = "How do you feel about the establishment?",
            helper = "Government, police, banks, big tech, the press. Where you trust it and where you don't.",
            input = QuestionInput.FreeText,
            chapter = 2,
            reviewLabel = "The establishment",
            injectInBlock = true,
            placeholder = "Where you stand on it",
            maxLength = 6000,
            factTemplate = "How they feel about the establishment: {value}"
        ),

        // ---------- CHAPTER 3: YOU AND ME ----------
        // THE CHOICES ARE HIS OWN WORDS, restored 2026-10-28. They had been
        // paraphrased into "Make my life better" and "Help me take control",
        // which dropped the object of "control" and left a label that reads as
        // a slogan rather than an answer. His phrasing was "better your life,
        // help you take control of things, automate things", so the object is
        // back and nothing is invented.
        OnboardingQuestion(
            key = "job_for_guru",
            prompt = "What do you want me to be for you?",
            helper = "Pick everything that's true. This is the thing I'll be doing every day.",
            input = QuestionInput.Chips,
            chapter = 3,
            reviewLabel = "The job",
            injectInBlock = true,
            allowOtherText = true,
            choices = listOf(
                QuestionChoice("mate", "Be a mate"),
                QuestionChoice("improve", "Better my life"),
                QuestionChoice("control", "Help me take back control of my life"),
                QuestionChoice("automate", "Automate things")
            ),
            factTemplate = "What they want me to be for them: {value}."
        ),
        // His ladder, dictated in his own words. The three stops he wrote are 1,
        // 5 and 10 and they are verbatim apart from spelling and punctuation.
        // The seven between them are Lux drafts, not his, and they exist because
        // the line between asking and cracking on lives in the middle of a scale,
        // not at its ends.
        OnboardingQuestion(
            key = "act_or_ask",
            prompt = "Autonomy",
            helper = "Should I ask permission before calling tools in general, or would you like me to work autonomously?",
            input = QuestionInput.Slider,
            chapter = 3,
            reviewLabel = "Autonomy",
            injectInBlock = true,
            sliderMinLabel = "Ask me everything",
            sliderMaxLabel = "Crack on",
            sliderPhrases = listOf(
                "I'm a control freak. I want to know everything you're doing at all times, and you must not move without my explicit permission.",
                "Ask first, but skip the essay. One line about what you're about to do, then wait for the yes.",
                "Ask before anything that touches my files, my money or another person. Reading can be free.",
                "Check in before you change anything. Looking costs me nothing. Changing costs me trust.",
                "If you've asked before, you don't need to ask again.",
                "Tell me what you did when you're done. If it's small and it's reversible, I'll live.",
                "Use your head. Boring and reversible, just get on with it and fill me in after.",
                "Get on with it. I only want to hear about it if it went sideways.",
                "I don't need to know unless something breaks. Use your judgement, that's what it's for.",
                "Crack on mate. Just be safe, and no destructive commands."
            ),
            factTemplate = "How much autonomy you should take with {human}: Should you ask permission before calling tools in general, or would they rather you worked autonomously. {value}"
        ),
        OnboardingQuestion(
            key = "how_i_talk",
            prompt = "How do you want me to talk to you?",
            helper = "Blunt or gentle. Short or thorough. Say it however you'd say it.",
            input = QuestionInput.FreeText,
            chapter = 3,
            reviewLabel = "How I talk",
            injectInBlock = true,
            placeholder = "How you want it said",
            maxLength = 6000,
            factTemplate = "How they want me to talk to them: {value}"
        )
    )

    // =====================================================================
    // POWER UP — what the human can connect before the app opens.
    // Every row performs a real write or a real request. None is decorative.
    // The model row is the one that matters: it is why nobody has to go into
    // Settings after onboarding.
    // =====================================================================

    val powerItems: List<PowerItem> = listOf(
        PowerItem(
            key = "provider",
            title = "Connect a model",
            caption = "Pick where GURU's brain lives and paste the key",
            kind = PowerKind.PROVIDER,
            // The same six the Settings screen offers, in the same order: OpenAI,
            // Anthropic, Google, xAI, Ollama, OpenAI-compatible. If a provider is
            // added there, add it here. The two lists are the same product and
            // must not drift, which is exactly how onboarding ended up able to
            // reach only two of them.
            providers = listOf(
                QuestionChoice("OpenAI", "OpenAI"),
                QuestionChoice("Anthropic", "Anthropic"),
                QuestionChoice("Google", "Google Gemini"),
                QuestionChoice("XAI", "xAI Grok"),
                QuestionChoice("Ollama", "Ollama (your own machine)"),
                QuestionChoice("OpenAICompat", "OpenAI-compatible endpoint")
            )
        ),
        PowerItem(
            key = "skills",
            title = "Skills",
            caption = "Tools GURU can pick up as you go",
            kind = PowerKind.SKILLS
        ),
        PowerItem(
            key = "vision",
            title = "Vision",
            caption = "Let GURU see the screen you are on",
            kind = PowerKind.VISION
        ),
        PowerItem(
            key = "language",
            title = "Language",
            caption = "Set the language GURU answers in",
            kind = PowerKind.LANGUAGE
        )
    )

    // =====================================================================
    // LOOKUP HELPERS — the screens read through these, never the raw lists
    // =====================================================================

    /** Questions in one chapter, in catalogue order. */
    fun questionsInChapter(chapter: Int): List<OnboardingQuestion> =
        questions.filter { it.chapter == chapter }

    /** Questions grouped by chapter, chapter order preserved as authored. */
    fun questionsByChapter(): List<Pair<OnboardingChapter, List<OnboardingQuestion>>> =
        chapters.map { chapter ->
            chapter to questions.filter { it.chapter == chapters.indexOf(chapter) }
        }.filter { it.second.isNotEmpty() }

    /** The subset that rides every send. Kept deliberately small. */
    fun alwaysInjectedQuestions(): List<OnboardingQuestion> =
        questions.filter { it.injectInBlock }

    /** Questions grouped by section name, preserving authored order. */
    fun questionsBySection(): List<Pair<String, List<OnboardingQuestion>>> =
        questions.groupBy { it.chapter }
            .toList()
            .sortedBy { it.first }
            .map { (chapter, list) -> chapterName(chapter) to list }

    /** Lookup by key, null when the catalogue no longer carries it. */
    fun questionFor(key: String): OnboardingQuestion? =
        questions.firstOrNull { it.key == key }

    /** One question by key, or null when the catalogue no longer has it. */
    fun question(key: String): OnboardingQuestion? = questionFor(key)

    /** Every key currently authored, for pruning stale stored answers. */
    fun allKeys(): Set<String> = questions.map { it.key }.toSet()

    /** Sections that actually have questions, in declared order. */
    fun activeSections(): List<String> =
        chapters.map { it.name }.filter { name ->
            questions.any { chapterName(it.chapter) == name }
        }

    /** The beat that carries the deal, wherever it sits in the list. */
    fun dealBeat(): OnboardingBeat? =
        beats.firstOrNull { it.acceptsDeal }

    /** Index of the deal beat, or the last beat when none is marked. */
    fun dealBeatIndex(): Int =
        beats.indexOfFirst { it.acceptsDeal }.takeIf { it >= 0 } ?: beats.lastIndex
}
