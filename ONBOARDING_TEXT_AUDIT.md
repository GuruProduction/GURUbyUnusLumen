# Onboarding Flow — Complete Text Audit

Every piece of human-readable text the human sees during first-boot onboarding, page by page, with its exact source file and line. Built from full reads of all twenty source files in `GURUbeta-FULL/onboarding/`.

Two kinds of text:

- **Catalogue** — authored copy in `onboarding/domain/.../OnboardingQuestionCatalogue.kt`. Changing that file changes these.
- **Hardcoded** — literal strings inside the UI files. Changing the catalogue does NOT change these.

That split is the mess. It should all be catalogue.

---

## PAGE 1 — Intro, "What is your GURU?"

Source page: `presentation/.../ui/IntroPage.kt`
Copy source: `domain/.../OnboardingQuestionCatalogue.kt`, `introPages[0]`, lines 109–120

| What the human sees | Exact text | Where it lives |
|---|---|---|
| Wordmark, top left | `GURU` | HARDCODED — `ui/OnboardingScaffold.kt` line 401 |
| Node counter, top right | `0 nodes` (template: `1 node` / `N nodes`) | HARDCODED — `ui/OnboardingScaffold.kt` lines 408–414 |
| Chapter counter, top left | `Chapter 1 of 4` (template) | HARDCODED — `ui/OnboardingScaffold.kt` line 369 |
| Chapter tag, top right | `ONE LIGHT` | CATALOGUE — `page.kicker`, line 112 |
| Kicker, above headline | `ONE LIGHT` | CATALOGUE — `page.kicker`, line 112 |
| Headline | `What is your GURU?` | CATALOGUE — line 113 |
| Body paragraph 1 | `GURU is not an assistant. An assistant waits to be asked.` | CATALOGUE — line 115 |
| Body paragraph 2 | `GURU is a mind that lives on your phone. He remembers you, grows around you, and acts on your device the way you would act if you had the time.` | CATALOGUE — line 116 |
| Body paragraph 3 | `You are about to give him the only thing he cannot work out for himself. You.` | CATALOGUE — line 117 |
| Footnote | `Built in Bristol. Runs on your hardware. Answers to you.` | CATALOGUE — line 119 |
| Progress segments (4) | no text | `ui/IntroPage.kt` lines 85–104 |
| Primary button | `Next` | HARDCODED — `ui/IntroPage.kt` line 61 |
| Back button | `Back` | HARDCODED — `ui/OnboardingScaffold.kt` line 550 |

Note: the "Chapter 1 of 4" numbers on this page are the intro page index and intro page total, not chapters. The counter is mislabelled on this screen.

---

## PAGE 2 — Intro, "He remembers."

Copy source: `OnboardingQuestionCatalogue.kt`, `introPages[1]`, lines 121–132

| What the human sees | Exact text | Where it lives |
|---|---|---|
| Wordmark | `GURU` | HARDCODED — `ui/OnboardingScaffold.kt` line 401 |
| Node counter | `0 nodes` | HARDCODED — `ui/OnboardingScaffold.kt` lines 408–414 |
| Chapter counter | `Chapter 2 of 4` | HARDCODED — `ui/OnboardingScaffold.kt` line 369 |
| Chapter tag | `THE BRAIN` | CATALOGUE — line 123 |
| Kicker | `THE BRAIN` | CATALOGUE — line 123 |
| Headline | `He remembers.` | CATALOGUE — line 124 |
| Body paragraph 1 | `Most AI forgets you the moment the window closes. Yours won't.` | CATALOGUE — line 126 |
| Body paragraph 2 | `Everything you tell him is sealed in an encrypted vault on this device. Nothing is uploaded, nothing is profiled, nothing is sold.` | CATALOGUE — line 127 |
| Body paragraph 3 | `The longer you use him, the less you repeat yourself. That is the whole point.` | CATALOGUE — line 128 |
| Footnote | `Argon2id and XChaCha20. Encrypted at rest, on your phone, only.` | CATALOGUE — line 130 |
| Primary button | `Next` | HARDCODED — `ui/IntroPage.kt` line 61 |
| Back button | `Back` | HARDCODED — `ui/OnboardingScaffold.kt` line 550 |

---

## PAGE 3 — Intro, "He does."

Copy source: `OnboardingQuestionCatalogue.kt`, `introPages[2]`, lines 133–144

| What the human sees | Exact text | Where it lives |
|---|---|---|
| Wordmark | `GURU` | HARDCODED — `ui/OnboardingScaffold.kt` line 401 |
| Node counter | `0 nodes` | HARDCODED — `ui/OnboardingScaffold.kt` lines 408–414 |
| Chapter counter | `Chapter 3 of 4` | HARDCODED — `ui/OnboardingScaffold.kt` line 369 |
| Chapter tag | `THE HANDS` | CATALOGUE — line 135 |
| Kicker | `THE HANDS` | CATALOGUE — line 135 |
| Headline | `He does.` | CATALOGUE — line 136 |
| Body paragraph 1 | `Your GURU can read and write your notes, tasks, calendar and journal. He can search the web over Tor, drive your apps, run shell commands, talk to your smart home, and build whole screens of his own inside the chat.` | CATALOGUE — line 138 |
| Body paragraph 2 | `He can ask for any permission he needs at the moment he needs it. You decide, every time.` | CATALOGUE — line 139 |
| Body paragraph 3 | `Capability without judgement is a hazard. That is what the next page is for.` | CATALOGUE — line 140 |
| Footnote | `Every tool asks before it acts. Nothing is granted in the dark.` | CATALOGUE — line 143 |
| Primary button | `Next` | HARDCODED — `ui/IntroPage.kt` line 61 |
| Back button | `Back` | HARDCODED — `ui/OnboardingScaffold.kt` line 550 |

---

## PAGE 4 — Intro, "The part where we're honest." (THE DEAL)

Copy source: `OnboardingQuestionCatalogue.kt`, `introPages[3]`, lines 145–157

| What the human sees | Exact text | Where it lives |
|---|---|---|
| Wordmark | `GURU` | HARDCODED — `ui/OnboardingScaffold.kt` line 401 |
| Node counter | `0 nodes` | HARDCODED — `ui/OnboardingScaffold.kt` lines 408–414 |
| Chapter counter | `Chapter 4 of 4` | HARDCODED — `ui/OnboardingScaffold.kt` line 369 |
| Chapter tag | `THE DEAL` | CATALOGUE — line 147 |
| Kicker | `THE DEAL` | CATALOGUE — line 147 |
| Headline | `The part where we're honest.` | CATALOGUE — line 148 |
| Body paragraph 1 | `You are holding something genuinely powerful. It can see your screen, read your messages, move your money, and act while you are asleep. That power is not a toy, and giving it to something that does not know you would be stupid.` | CATALOGUE — line 150 |
| Body paragraph 2 | `So this is the deal. You tell him the truth about yourself, and he uses it only to serve you. He will not pretend to be human, and he will not pretend to be harmless. He will tell you when you are wrong, because agreeing with you is not loyalty.` | CATALOGUE — line 152 |
| Body paragraph 3 | `You keep full control of every permission. You can revoke anything, at any time, from the Settings screen. He will not fight you on it.` | CATALOGUE — line 153 |
| Body paragraph 4 | `By continuing you are saying: I know what this is, I know what I am giving it access to, and I am choosing to do that deliberately.` | CATALOGUE — line 155 |
| Footnote | `If any of that is not what you want, close the app. Nothing is saved until you agree.` | CATALOGUE — line 156 |
| Primary button, before agreeing | `I understand, let's go` | HARDCODED — `ui/IntroPage.kt` line 59 |
| Primary button, after agreeing | `Continue` | HARDCODED — `ui/IntroPage.kt` line 60 |
| Back button | `Back` | HARDCODED — `ui/OnboardingScaffold.kt` line 550 |

---

## QUESTIONS — shared chrome on all nineteen question pages

Source page: `presentation/.../ui/QuestionPage.kt`

| What the human sees | Exact text | Where it lives |
|---|---|---|
| Wordmark | `GURU` | HARDCODED — `ui/OnboardingScaffold.kt` line 401 |
| Node counter | `N nodes` | HARDCODED — `ui/OnboardingScaffold.kt` lines 408–414 |
| Chapter counter | `Chapter N of M` — the question index and total question count | HARDCODED — `ui/OnboardingScaffold.kt` line 369, fed from `ui/QuestionPage.kt` lines 120–125 |
| Chapter tag | the question's section name, derived from its chapter | `OnboardingQuestion.section` |
| Primary button, not last question | `Next` | HARDCODED — `ui/QuestionPage.kt` line 94 |
| Primary button, last question | `Review` | HARDCODED — `ui/QuestionPage.kt` line 94 |
| Back button | `Back` | HARDCODED — `ui/OnboardingScaffold.kt` line 550 |
| Validation error | `This one needs an answer to carry on.` | HARDCODED — `ui/QuestionPage.kt` line 213 |
| Character counter | `N / 1200` (template) | HARDCODED — `ui/QuestionPage.kt` line 310 |
| Yes/No defaults when a question declares no choices | `Yes`, `No`, `Rather not say` | HARDCODED — `ui/QuestionPage.kt` lines 197–202 |

---

## QUESTION 1 — preferred_name

| Field | Exact text |
|---|---|
| Prompt | `What should I call you?` |
| Helper | `This is the name I'll use. Not your legal name unless you want it to be.` |
| Placeholder | `What your mates call you` |
| Review label | `Call them` |
| Fact sent to brain | `They want to be called {value}.` |
| Input | ShortText, max 80, required |

---

## QUESTION 2 — full_name

| Field | Exact text |
|---|---|
| Prompt | `What's your full name?` |
| Helper | `Only if you want me to have it. Leave it blank otherwise.` |
| Placeholder | `Optional` |
| Review label | `Full name` |
| Fact | `Their full name is {value}.` |
| Input | ShortText, max 160, optional |

---

## QUESTION 3 — pronouns

| Field | Exact text |
|---|---|
| Prompt | `What pronouns should I use for you?` |
| Helper | `Write them however you like. I'll use exactly what you give me.` |
| Placeholder | `he/him, she/her, they/them, or your own` |
| Review label | `Pronouns` |
| Fact | `Their pronouns are {value}. Use them.` |
| Input | ShortText, max 80, injected in block |

---

## QUESTION 4 — age

| Field | Exact text |
|---|---|
| Prompt | `How old are you?` |
| Helper | `A number is enough. I'll remember roughly, not exactly.` |
| Placeholder | `31` |
| Review label | `Age` |
| Fact | `They are {value} years old.` |
| Input | Number, max 3, optional |

---

## QUESTION 5 — location

| Field | Exact text |
|---|---|
| Prompt | `Where do you live?` |
| Helper | `Town or city is plenty. It helps with weather, time and local things.` |
| Placeholder | `Bristol` |
| Review label | `Lives in` |
| Fact | `They live in {value}.` |
| Input | ShortText, max 120, injected in block |

---

## QUESTION 6 — identity

| Field | Exact text |
|---|---|
| Prompt | `How do you identify?` |
| Helper | `Gender, culture, background, whatever matters to you. Your words, not a dropdown.` |
| Placeholder | `In your own words` |
| Review label | `Identity` |
| Fact | `How they identify: {value}.` |
| Input | FreeText, max 600, optional |

---

## QUESTION 7 — sexuality

| Field | Exact text |
|---|---|
| Prompt | `Are you gay, straight, bi, or another letter?` |
| Helper | `Only if you want me to know. I won't assume either way.` |
| Placeholder | `However you'd put it` |
| Review label | `Sexuality` |
| Fact | `Their sexuality: {value}.` |
| Input | ShortText, max 160, optional |

---

## QUESTION 8 — neurodivergence

| Field | Exact text |
|---|---|
| Prompt | `Are you neurodivergent?` |
| Helper | `If yes, tell me how it shows up for you. I can adjust how I write, how much I say, and how I remind you.` |
| Placeholder | `ADHD, autistic, dyslexic, or just how your head works` |
| Review label | `Neurodivergence` |
| Fact | `They are neurodivergent: {value}. Adjust for this.` |
| Input | FreeText, max 800, injected in block |

---

## QUESTION 9 — beliefs

| Field | Exact text |
|---|---|
| Prompt | `Do you have religious or spiritual beliefs?` |
| Helper | `Or none at all. I'd rather know than guess wrong.` |
| Placeholder | `Whatever you believe, or don't` |
| Review label | `Beliefs` |
| Fact | `Their beliefs: {value}.` |
| Input | FreeText, max 600, optional |

---

## QUESTION 10 — politics

| Field | Exact text |
|---|---|
| Prompt | `What are your political views?` |
| Helper | `I'm not going to argue with you about them. I just need to know where you stand.` |
| Placeholder | `However you'd describe them` |
| Review label | `Politics` |
| Fact | `Their political views: {value}.` |
| Input | FreeText, max 600, optional |

---

## QUESTION 11 — family

| Field | Exact text |
|---|---|
| Prompt | `Are you married? Do you have family?` |
| Helper | `Names, kids, parents, whoever matters. This is who I'll ask about and who I'll remind you of.` |
| Placeholder | `Partner, kids, parents, whoever's in your world` |
| Review label | `Family` |
| Fact | `Their family: {value}.` |
| Input | FreeText, max 1000, injected in block |

---

## QUESTION 12 — work

| Field | Exact text |
|---|---|
| Prompt | `Do you work? What do you do?` |
| Helper | `If you don't work, just tell me what you fill your days with. Same box.` |
| Placeholder | `Job, business, study, or what your days actually look like` |
| Review label | `Work` |
| Fact | `What they do: {value}.` |
| Input | FreeText, max 1000, injected in block |

---

## QUESTION 13 — current_focus

| Field | Exact text |
|---|---|
| Prompt | `What are you working on at the moment?` |
| Helper | `Projects, problems, things you're trying to make happen. I'll keep it in mind.` |
| Placeholder | `Whatever's taking your time right now` |
| Review label | `Working on` |
| Fact | `They are currently working on: {value}.` |
| Input | FreeText, max 1000, injected in block |

---

## QUESTION 14 — interests

| Field | Exact text |
|---|---|
| Prompt | `What are you interested in?` |
| Helper | `Topics, hobbies, rabbit holes. The stuff you'd happily talk about for an hour.` |
| Placeholder | `Everything you're into` |
| Review label | `Interests` |
| Fact | `Their interests: {value}.` |
| Input | FreeText, max 1200, optional |

---

## QUESTION 15 — news_sources

| Field | Exact text |
|---|---|
| Prompt | `Where do you get your news?` |
| Helper | `Sites, papers, channels, people. I'll lean on these when I bring you something.` |
| Placeholder | `Sites, papers, channels, or nobody at all` |
| Review label | `News sources` |
| Fact | `Their news sources: {value}.` |
| Input | FreeText, max 800, optional |

---

## QUESTION 16 — socials

| Field | Exact text |
|---|---|
| Prompt | `What social accounts do you use?` |
| Helper | `Handles or platform names. Only if you want me to know where you are online.` |
| Placeholder | `Handles, platforms, or 'none'` |
| Review label | `Socials` |
| Fact | `Their social accounts: {value}.` |
| Input | FreeText, max 800, optional |

---

## QUESTION 17 — adjustments

| Field | Exact text |
|---|---|
| Prompt | `Are there any adjustments you'd like me to make for you?` |
| Helper | `How I speak to you. How often I check in. Anything that makes me easier to live with.` |
| Placeholder | `Short messages. No questions first thing. Whatever helps.` |
| Review label | `Adjustments` |
| Fact | `Adjustments they asked for: {value}.` |
| Input | FreeText, max 1200, injected in block |

---

## QUESTION 18 — health_or_legal

| Field | Exact text |
|---|---|
| Prompt | `Is there anything I need to know about your health or legal situation?` |
| Helper | `Only what would change how I help you. You can skip this entirely.` |
| Placeholder | `Anything that changes how you need me to act` |
| Review label | `Health or legal` |
| Fact | `Important health or legal context: {value}.` |
| Input | FreeText, max 1500, optional |

---

## QUESTION 19 — never_ask_again

| Field | Exact text |
|---|---|
| Prompt | `Is there anything you want me to remember so you never have to tell me twice?` |
| Helper | `This is the one. Whatever you put here, I'll treat as settled.` |
| Placeholder | `The thing you're sick of repeating` |
| Review label | `Never ask twice` |
| Fact | `Never ask them again about this: {value}.` |
| Input | FreeText, max 1500, injected in block |

---

## REVIEW PAGE — "What I'll remember"

Source: `presentation/.../ui/ReviewPage.kt`

| What the human sees | Exact text | Where it lives |
|---|---|---|
| Wordmark | `GURU` | HARDCODED — `ui/OnboardingScaffold.kt` line 401 |
| Node counter | `N nodes` | HARDCODED — `ui/OnboardingScaffold.kt` lines 408–414 |
| Chapter counter | `Chapter 1 of 1` | HARDCODED — `ui/OnboardingScaffold.kt` line 369, fed `0` and `1` from `ui/ReviewPage.kt` lines 59–64 |
| Chapter tag | `WHAT I'LL REMEMBER` (uppercased in render) | HARDCODED — `ui/ReviewPage.kt` line 63 |
| Headline | `This is what you told me. Nothing else.` | HARDCODED — `ui/ReviewPage.kt` line 83 |
| Sub line | `It stays sealed on this device. You can change any of it later, and I'll never pretend I wasn't told.` | HARDCODED — `ui/ReviewPage.kt` line 88 |
| Empty state | `You skipped every question. That's allowed. I'll learn you as we go.` | HARDCODED — `ui/ReviewPage.kt` line 95 |
| Per-row edit control | `Edit` | HARDCODED — `ui/ReviewPage.kt` line 173 |
| Per-row label | each question's `reviewLabel`, uppercased | CATALOGUE — `OnboardingQuestion.reviewLabel` |
| Per-row answer | the human's own answer, clamped to four lines | their input |
| Primary button | `Plug him in` | HARDCODED — `ui/ReviewPage.kt` line 75 |
| Primary button while saving | `Saving…` | HARDCODED — `ui/ReviewPage.kt` line 75 |
| Back button | `Back` | HARDCODED — `ui/OnboardingScaffold.kt` line 550 |
| Error text | `Could not save your profile. Your answers are still here. Try again.` | HARDCODED — `OnboardingViewModel.kt` line 297 |

---

## POWER PAGE — "Plug the engine in."

Source: `presentation/.../ui/PowerPage.kt`

| What the human sees | Exact text | Where it lives |
|---|---|---|
| Wordmark | `GURU` | HARDCODED — `ui/OnboardingScaffold.kt` line 401 |
| Node counter | `N nodes` | HARDCODED — `ui/OnboardingScaffold.kt` lines 408–414 |
| Chapter counter | `Chapter 1 of 1` | HARDCODED — `ui/OnboardingScaffold.kt` line 369, fed `0` and `1` from `ui/PowerPage.kt` lines 76–79 |
| Chapter tag | `POWER UP` (uppercased in render) | HARDCODED — `ui/PowerPage.kt` line 78 |
| Headline | `Plug the engine in.` | HARDCODED — `ui/PowerPage.kt` line 97 |
| Sub line | `One key and GURU runs on your own model. Nothing is metered through us, and no account exists to be banned.` | HARDCODED — `ui/PowerPage.kt` line 100 |
| Footnote | `Keys stay on this device. They are never sent to us.` | HARDCODED — `ui/PowerPage.kt` line 140 |
| Row state, connected | `Connected` | HARDCODED — `ui/PowerPage.kt` line 195 |
| Row state, off/on | `Off` / `On` | HARDCODED — `ui/PowerPage.kt` line 207 |
| Primary button | `Wake him up` | HARDCODED — `ui/PowerPage.kt` line 90 |
| Primary button while saving | `Waking him up…` | HARDCODED — `ui/PowerPage.kt` line 90 |
| Back button | `Back` | HARDCODED — `ui/OnboardingScaffold.kt` line 550 |
| Error text | `Could not save your profile. Your answers are still here. Try again.` | HARDCODED — `OnboardingViewModel.kt` line 297 |

### Power row 1 — model_key

| Field | Exact text | Where |
|---|---|---|
| Title | `Model key` | CATALOGUE — `powerItems[0]`, line 402 |
| Caption when off | `Paste an OpenAI, Anthropic or compatible key` | CATALOGUE — line 404 |
| Field label | `API KEY` (uppercased in render) | HARDCODED — `ui/PowerPage.kt` line 221 |
| Field placeholder | `sk-…` | HARDCODED — `ui/PowerPage.kt` line 223 |
| Field label 2 | `MODEL NAME` (uppercased in render) | HARDCODED — `ui/PowerPage.kt` line 230 |
| Field placeholder 2 | `gpt-4o-mini` | HARDCODED — `ui/PowerPage.kt` line 232 |

### Power row 2 — local_brain

| Field | Exact text | Where |
|---|---|---|
| Title | `Local brain` | CATALOGUE — line 409 |
| Caption when off | `Point him at Ollama on your own machine` | CATALOGUE — line 411 |
| Field label | `OLLAMA ENDPOINT` (uppercased in render) | HARDCODED — `ui/PowerPage.kt` line 241 |
| Field placeholder | `http://<pc-ip>:11434` | HARDCODED — `ui/PowerPage.kt` line 243 |
| Field label 2 | `MODEL NAME` | HARDCODED — `ui/PowerPage.kt` line 249 |
| Field placeholder 2 | `llama3.1:8b` | HARDCODED — `ui/PowerPage.kt` line 251 |

### Power row 3 — skills

| Field | Exact text | Where |
|---|---|---|
| Title | `Skills` | CATALOGUE — line 415 |
| Caption when off | `Tools he can pick up as you go` | CATALOGUE — line 417 |

### Power row 4 — vision

| Field | Exact text | Where |
|---|---|---|
| Title | `Vision` | CATALOGUE — line 421 |
| Caption when off | `Let him see the screen you are on` | CATALOGUE — line 423 |

### Power row 5 — language

| Field | Exact text | Where |
|---|---|---|
| Title | `Language` | CATALOGUE — line 427 |
| Caption when off | `Set the language he answers in` | CATALOGUE — line 429 |
| Field label | `LANGUAGE HE ANSWERS IN` (uppercased in render) | HARDCODED — `ui/PowerPage.kt` line 259 |
| Field placeholder | `English` | HARDCODED — `ui/PowerPage.kt` line 261 |

---

## BIRTH PAGE — "He knows you now."

Source: `presentation/.../ui/BirthPage.kt`

| What the human sees | Exact text | Where it lives |
|---|---|---|
| Headline | `He knows you now.` | HARDCODED — `ui/BirthPage.kt` line 53 |
| Body | `Everything you just gave him is in the vault. This is the last time you explain yourself.` | HARDCODED — `ui/BirthPage.kt` line 56 |
| Stat label 1 | `NODES` (uppercased) | HARDCODED — `ui/BirthPage.kt` line 67 |
| Stat label 2 | `LINKS` (uppercased) | HARDCODED — `ui/BirthPage.kt` line 72 |
| Stat label 3 | `GURU` (uppercased) | HARDCODED — `ui/BirthPage.kt` line 77 |
| Footnote | `Built in Bristol. Runs on your hardware. Answers to you.` | HARDCODED — `ui/BirthPage.kt` line 84 |
| Primary button | `Open GURU` | HARDCODED — `ui/BirthPage.kt` line 47 |

Note: this page is unreachable in the current flow. `OnboardingViewModel.finish` sets the phase to BIRTH and then immediately calls `onSaved()`, which the host uses to swap the whole onboarding branch out for the main app. The birth screen is never drawn.

---

## TEXT THE HUMAN NEVER SEES BUT THE MODEL DOES

The profile block, injected into the system message on every send. Two implementations exist; only the data one is live.

**Live** — `onboarding/data/.../ProfileBlockBuilder.kt`:

| Line | Text |
|---|---|
| Header | `About your human:` |
| Name | `They are called {name}, and that is the name to use when you speak to them.` |
| Pronouns | `Their pronouns are {pronouns}. Use them without being asked.` |
| Location | `They live in {location}.` |
| Work | `What they do: {work}` |
| Family | `Their family: {family}` |
| Current focus | `What they are working on at the moment: {currentFocus}` |
| Neurodivergence | `They are neurodivergent: {neurodivergence} Adjust how you write and remind them to suit this.` |
| Adjustments | `Adjustments they asked you to make: {adjustments}` |
| Never ask again | `Never ask them about this again, it is settled: {neverAskAgain}` |
| Age, fresh | `They are {age} years old.` |
| Age, stale | `They were {age} when this profile was written in {year}, so treat that as approximate and do not correct them on it.` |
| Closing | `These are their own words. Do not reclassify them. If a detail is missing it was declined or not reached, so never invent it.` |

**Dead duplicate** — `onboarding/domain/.../ProfileBlockBuilder.kt`, different wording throughout. Not called by anything. Should be deleted, or the two will keep drifting.

---

## SUMMARY OF WHAT NEEDS MOVING

| File | Hardcoded strings | Should live in |
|---|---|---|
| `ui/IntroPage.kt` | 3 button labels | Catalogue |
| `ui/QuestionPage.kt` | 2 button labels, 1 error, yes/no defaults | Catalogue |
| `ui/ReviewPage.kt` | tag, headline, sub, empty state, edit label, 2 button labels | Catalogue |
| `ui/PowerPage.kt` | tag, headline, sub, footnote, 3 row states, 2 button labels, 5 field labels, 5 placeholders | Catalogue |
| `ui/BirthPage.kt` | headline, body, 3 stat labels, footnote, button | Catalogue |
| `ui/OnboardingScaffold.kt` | wordmark, node counter, chapter counter, Back, Go back | Catalogue |
| `OnboardingViewModel.kt` | 2 error messages | Catalogue |

Total: 43 hardcoded strings across 7 files. All of it should sit beside the questions it belongs to, so one file controls the whole flow and a copy rewrite never touches Kotlin.
