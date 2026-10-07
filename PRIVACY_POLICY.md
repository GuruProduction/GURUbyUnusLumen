# Privacy Policy

Effective date: 7 October 2026. Applies to every build of GURU by Unus Lumen from release 3.6 onward.

Unus Lumen Ltd. Bristol, United Kingdom.
Contact: steven@unuslumen.com

This is the plain-language privacy statement for the app called GURU, an open-source Android framework that runs an AI with real control of your phone. It tells you what data lives where, what crosses a network and who can see it, and what never happens. It is written to be read by the people using the app, not by lawyers.

One line first, because it is the truth of the whole product that this policy explains: GURU takes in almost everything, stores almost everything on your device and sends almost nothing anywhere. On an install you fully control, the company behind GURU never receives or holds your personal data at all.

---

## 1. The privacy model

GURU is not an online service. It is an APK you install onto your own device from our public GitHub releases. No account is created. No signup exists. No profile is built. Nothing about you is recorded server-side, permanently.

Your conversations with GURU, its memory of you, your notes, tasks, journal, bookmarked URLs, alarms, calendar events, projects and tool histories stay in a local on-device database. Your tool-result cache is trimmed automatically every 24 hours. All of these are in GURU's own private sandbox on your storage, which your device's own operating system limits other apps from reading. They die if you uninstall, which is deliberate.

Your memory (Cerebrum, the brain) is stored encrypted with Argon2id-derived keys and XChaCha20-Poly1305 as its vault cipher. The unlock passphrase never leaves the phone: it is sealed inside your device's Android hardware Keystore inside a separate AES-256-GCM envelope, and otherwise lives only briefly in the brain's own protected memory while running. If a memory-vault decryption ever fails integrity checks (signing that it is tampered-with), the app refuses to boot the brain rather than quietly proceeding — it deletes the vault's own app-space envelopes and recreates a new one fresh while leaving every other part of your phone and this app untouched. Any app you are not yourself controlling, we can never see your memory in this install and no other install of GURU on the planet can reach it.

Your connected third-party credentials (your GitHub token, Trello key/token, Notion token, API and model-server key) go through the app's CredentialVault — one AES-256-GCM hardware-sealed envelope per service, written individually, encrypted again with the strong Android hardware keys generated on-device, never leaving it, and only ever decrypted transiently in memory to perform the single task they were stored for, on your request. No credential you place into the CredentialVault is ever displayed back on screen unmasked, and none is exported to us or anywhere else.

No analytics, no telemetry, no crash-reporting service exists in the app's source. When the app crashes, the resulting stack trace is copied to your own clipboard so you can inspect it; it is not delivered anywhere by GURU itself.

The app never requests or records any unique device fingerprint, IMEI, phone number, or advertising identifier, in its code path or anywhere in its storage. There is nothing to correlate you with.

## 2. What we (Unus Lumen) serve to your app, and no more

The app connects unauthenticated out of the box to the Unus Lumen publisher server at api.unuslumen.com. This is the "supply line" of the product and the one place it must connect. Our publisher supplies:

- the assembled numen (your GURU's "system prompt", how your specific instance thinks and knows it is a GURU on your phone)
- your chosen model's runtime configuration (which model id to connect to, thinking parameters, maximum context)
- the settings controlling which on-device sensory details (like date/time, battery, weather and location names, screen state, nearby motion and similar "here's what's true of me now" context GURU works from) get offered as context
- the global skills library contents (optional skills your numen can choose install)
- the artwork ("ad pack") content shown inside GURU's animation spinner, which by design runs every few seconds during idle streaming and is served anonymously

For each of these, the connection is GET-shaped and anonymous fetch by design. The device sends back no data we keep as anything like a user record: no name, no unique device or install identifier, no location, no profile of use, and no cookies are ever persisted by you or read back after this app's fetches.

These fetches go out through your device's own Tor network connection — a private-networking engine routed through multiple relays so that the webserver hosting them sees the Tor-relay-facing network identity, not your personal device's public home-network IP address. The fetches, in other words, are anonymous even in network terms.

For the publisher-hosted model engine: in current public builds there is no "UnusLumen" cloud LLM provider option that comes shipped as a built-in AI model by default: it stays a listed-but-future-mode choice on the onboarding model-selection screen. What actually runs today against inference is your own connected model (point 3).

## 3. Model inference — what gets sent to an AI service

GURU does not run a language model on your device unless you point at one. It talks to whatever your selected "brain" is: a remote or on-LAN model endpoint.

- Local LLM mode (your device's own home network or a private endpoint such as your own ollama server, LM Studio, llama.cpp, a self-hosted OpenAI-compatible engine on your network, or any OpenAI-speaking model on your own Wi-Fi network, in particular): model traffic stays entirely on your LAN. Nothing crosses the global internet or reaches any cloud provider for inference — your model is already private.
- Cloud model providers you connect yourself (OpenAI, Anthropic (Claude), Google (Gemini),xAI (Grok)): GURU sends prompts, conversation context and attachments to these service providers' model inference endpoints in exactly the amount the feature needs. They receive:
  - the full text context of each AI conversation turn (the current prompt and preceding turns)
  - on-screen captures (image attachments) when you choose /vision and take a screenshot or image of anything — only while the app has your active screen-recording permission in place
  - any explicit file images, camera captures, PDFs or videos you share with your chat while using GURU
  - your model-server API access credentials (not for anything else — only for using the model)

You choose this provider with your key and it is exclusively your own choice; the keys are stored on-device as noted above. What they do with your messages and attachments after inference is governed by those services' own user agreements and privacy policies, independent of this document — we do not operate or control cloud model inference providers. This policy explicitly recommends you consider the trade-offs as stated in the README.

- Whichever model you connect, your messages, your GURU's on-device memory context, and images are sent (where relevant) to ONLY that endpoint. GURU never sends model conversation streams through us if you are running a model yourself (BYO mode) directly — they only stream through our servers if/when using a UnusLumen-hosted gateway inference option (which today is unavailable).

## 4. External internet tools

Your numen can, when invoked as tools by GURU, connect to external websites. When it does, no user-identifying data goes out beyond what the specific action itself requires:

- `web search`: through the bundled Tor path. Queries go to the SearXNG instances we ship (which are independent public front-ends that themselves hit mainstream engines), and in fallback cases to the public Brave, Ahmia and Torch search mirrors. All queries travel as anonymous text query only. SearXNG sees only a query plus the Tor exit exit network's identity.
- `web fetch` and `web browsing (headless WebView)`: any web page the numen is asked to fetch, and web pages it is asked to fully browse (loading page JavaScript, clicking elements etc) — fetch the page itself on your instructions only, through Tor.
- GitHub via real API + your own GitHub login (OAuth "device flow", one of your own accounts you sign into manually): we operate only a public, non-secret client registration ID (that is a standard OAuth "device flow" credential that carries no account access by itself). Your own private GitHub connection token stays in your device's CredentialVault, never uploaded to or seen by Unus Lumen in any way.
- Trello (via your own per-user key/token pair, entered manually once in GURU by you, saved encrypted via CredentialVault in Trello's own API with no copy ever shipped, visible, or synced by us; all calls travel over real Trello API endpoints.)
- Notion (exactly the same shape as above with Notion's own official API and your personal API token stored encrypted on your device).

## 5. Some capabilities and how privacy works on each

- Shell and Python engine: your own numen may execute user-approved command-line work on the device through on-device, embedded binary engines (a bundled python runtime, termux linux root, toybox/unix utils). Shell executions are run locally on your own OS processes and are never sent elsewhere as data (other than that the model may need to read/write data or produce tool actions it was asked for).
  - Any direct termux `apt install *` fetches package updates from packages-cf.termux.dev (Termux upstream). It is a normal package mirror fetch.
- WhatsApp and calling actions are not GURU-driven APIs. When your numen is asked "send this to WhatsApp," the app builds an Android intent for your existing WhatsApp application to handle the send like a normal human would; no WhatsApp or other app is contacted through Unus Lumen's infrastructure. There is no WhatsApp data ever transmitted by GURU to its servers.
- Discord / Slack / X (Twitter) / Gmail tools: these are not real integrations as of version 3.6. Every one of these requests only returns a request to configure one; they never send data.
- Accessibility screen and device control, camera image, microphone and screenshot: no capability is silent or granted to us. Each one is granted manually at the individual Android system permission level and can be revoked in your system's permission settings at any time — nothing GURU ships with or does by default can trigger these capabilities by itself. Camera captures get captured through the phone's own camera capture interface where the standard system dialog confirms a photo can be taken, and they are stored locally. Screenshot captures for "vision" can only be performed after Android's own screen-cast confirmation prompt (a separate Android consent popup the user has to accept), not automatically or from within the app.

## 6. When GURU connects through your phone to your own network scan tools

Discovery of your home network and the nearby devices around it (mDNS/Bonjour, SSDP/UniversalPlugPlay devices on the home network such as chromecast/tv/sonos; local Wi-Fi access-points visible around your physical location; Bluetooth pairing and nearby device discovery; local network ping/scan, port checks, etc.) happens strictly on the network side (local Wi-Fi segment and Bluetooth). These local networks probes never leave your local network; no discovery results are uploaded to Unus Lumen servers.

## 7. What is NOT collected

To be explicit, GURU's shipped code has NO form of these anywhere:

- analytics of any kind, click tracking, product-usage logging
- crash-reporting or error-reporting upload services of any kind
- advertising SDK integrations, ad networks, ad identifier or advertising personal profiles
- location uploads, GPS logs, activity fingerprints, device fingerprints, IMEI, phone number, or OS installation IDs — not stored nor collected, never read by us
- accounts, emails, or passwords in any Unus Lumen database
- contact lists, contacts or similar uploaded anywhere. Contact reads happen from your own on-device contact books and only for use by its on-device LLM reasoning.

And Unus Lumen as a company never "sells data", buys user logs, brokers a data-marketplace with your personal information or data-mines your activity. None of that exists in GURU version 3.6, whether in the Android app source, its gradle dependency tree, or the publisher.

## 8. Deleting everything

There is no retention or archiving by Unus Lumen, none at all, as we receive none. All of GURU, its conversations, its memories, its tool history, your connected-service tokens, and any files it was asked to keep live exclusively on your physical phone storage:
- Uninstalling GURU erases all of it permanently from your device. The only copy of anything from GURU that persists to anyone else's devices is what you specifically exported or sent somewhere while using your numen, which is a deliberate, user-initiated act that GURU never performs on its own for personal content.
- In-app deletion of any single item you no longer want (your conversations, tasks, memories, contact records, and any single piece a tool stored, the credentials, per-service tokens stored under CredentialVault) is available and immediate in the user interface of any specific screen you see them and in the memory/settings tools, with nothing preserved anywhere after deleting.

## 9. Age restriction

Given the breadth of your numen's device powers and its ability to automate your day, GURU is not meant for users aged 13 and below.

## 10. Changes to this policy

A new effective date will be set at any future change, alongside the release that first carries it. Since there are no accounts, users will simply read the updated text (its first page notes only changes; nothing more) or continue to use GURU on their own version they've installed.

---

**GURU: built by Steven Newman** — Unus Lumen, Bristol, United Kingdom
steven@unuslumen.com