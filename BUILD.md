# BUILD.md — Building GURU by Unus Lumen

GURU is fully open source. You can build the Android app yourself from source. No accounts, no gatekeepers, nothing hidden.

The sync server this app talks to is Unus Lumen's own infrastructure and is closed source (the reasoning is in the [Roadmap](ROADMAP.md)). Be honest about what that means: the publisher feeds the app. Prompts, skills, packs and agent templates arrive over sync — **a GURU disconnected from every publisher boots but does not work as intended.** Pointing it at a publisher you control (or hand-installing content) is a job for technical users who want that isolation. The protocol is documented plain HTTP, so any compatible publisher works.

---

## Part 1 — The Android App (sideloaded APK)

### Prerequisites

- Android Studio (Ladybug or newer) OR the Android SDK command line tools
- JDK 17
- Android SDK platform 35 (target) and 24 (minimum)
- ~5 GB free disk space

### Build it

```bash
git clone https://github.com/GuruProduction/GURUbyUnusLumen.git
cd GURUbyUnusLumen
```

Option A (Android Studio):

1. Open the `GURUbeta-FULL` project folder (the Gradle root lives there, not at the repo top level)
2. Wait for Gradle sync
3. Build > Build App Bundle(s) / APK(s) > Build APK(s)
4. Find the APK under `GURUbeta-FULL/app/build/outputs/apk/`

Option B (command line, release build):

```bash
cd GURUbeta-FULL
./gradlew assembleRelease
```

Sign the APK for sideloading:

```bash
# Use your own keystore, or a debug keystore for personal testing
apksigner sign --ks /path/to/keystore.jks GURUbeta-FULL/app/build/outputs/apk/release/app-release-unsigned.apk
```

### Sideload it

1. Transfer the APK to your Android device
2. Settings > Apps > Special app access > Install unknown apps > allow your file manager
3. Tap the APK, install
4. First launch shows the **bootstrap model connect** screen: point GURU at your chosen model

### Choosing a model (BYO)

- **Cloud API key:** add your key for Anthropic (Claude), OpenAI (GPT), Google (Gemini), xAI (Grok) etc. in Settings
- **Local LLM:** point the app at an Ollama instance on your network (`http://YOUR_LAN_IP:11434`) or any OpenAI-compatible endpoint (llama.cpp, LM Studio, vLLM)

The app never routes through any Unus Lumen dependency for model inference. Your key stays on your device. Your conversations stay on your device.

### Optional but recommended: ADB wireless debugging

GURU's deepest capabilities (reading other apps' databases, installing and stripping APKs, full shell) need either root or ADB with shell UID:

1. Settings > Developer options > Wireless debugging > Pair device with pairing code
2. Enter the code when GURU asks, and it handles the rest

### Permissions to expect on first run

- Accessibility Service (screen reading and input)
- Notification listener (watching notifications)
- Contacts, calendar, storage, microphone, camera
- All Files Access (filesystem tools)

Each is explained by GURU itself in the onboarding. Grant what you want, GURU degrades gracefully to whatever it has.

---

## Part 2 — Model Connect and Sync (the only setup)

The publisher server side of sync is Unus Lumen infrastructure (closed source — [why](ROADMAP.md)). The sync *protocol* is plain, documented HTTP; any compatible publisher works. Setup is just a model plus sync — one is chosen here, the other arrives preconfigured:

### How sync works

The app ships preconnected to the Unus Lumen publisher. First launch just pulls everything it publishes: prompt pack, skills, tool definitions, agent templates, canvas packs, config defaults. Nothing to configure. Install, connect your model, use the app.

**Point GURU elsewhere (for the technical):** the sync target is just a URL and the protocol is plain HTTP. You can point at a publisher you run yourself — that's real and supported. But understand the trade before you pick it: prompts, skills, tool definitions, agent templates, canvas packs and config defaults all arrive over sync. **A GURU with no publisher at all boots, connects to your model, and then underperforms badly — the content that makes it work isn't there.** Going publisher-less means hand-installing content and knowing what you're doing. That isolation is a deliberate expert choice, not the happy default the privacy-maximalist framing might suggest. No feature is locked behind our server technically — but feeding your own GURU yourself is real work.

---

## Repository layout

```
app source and the rest of the app repo: this repository (published here now)
sync protocol: documented over-the-wire contract, plain HTTP
publisher server: Unus Lumen infrastructure, closed source (why in ROADMAP.md)
```

## Troubleshooting

- **Gradle sync fails on JDK version:** check File > Settings > Build Tools > Gradle > Gradle JDK = 17
- **Permission denials on device:** GURU surfaces a live status screen listing every granted/denied permission
- **ADB pair rejected:** make sure both devices share the network and the debug pair dialog is open at the moment you submit the code
- **Ollama not reachable from phone:** run `OLLAMA_HOST=0.0.0.0:11434 ollama serve` so it listens on the LAN, not localhost only

Questions and security: steven@unuslumen.com