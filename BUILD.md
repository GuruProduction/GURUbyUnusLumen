# BUILD.md — Building GURU by Unus Lumen

GURU is fully open source. You can build the Android app and the open sync server yourself. No accounts, no gatekeepers, nothing hidden.

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

1. Open the project folder
2. Wait for Gradle sync
3. Build > Build App Bundle(s) / APK(s) > Build APK(s)
4. Find the APK under `app/build/outputs/apk/`

Option B (command line, release build):

```bash
./gradlew assembleRelease
```

Sign the APK for sideloading:

```bash
# Use your own keystore, or a debug keystore for personal testing
apksigner sign --ks /path/to/keystore.jks app/build/outputs/apk/release/app-release-unsigned.apk
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

## Part 2 — The Open Sync Server

A deliberately stateless content publisher in Rust. Serves prompts, skills, tool definitions, agent templates, canvas packs and config defaults. No accounts, no conversation capture, nothing to leak.

### Prerequisites

- Rust 1.80+ (`curl https://sh.rustup.rs -sSf | sh`)
- Git

### Build and run

```bash
git clone https://github.com/GuruProduction/GURUbyUnusLumen-Server.git
cd GURUbyUnusLumen-Server
cargo build --release
cargo run --release
```

The server binds by default on the configured port (see `guru-server` config) and serves everything from the `guru-content` directory.

### How sync works

The app ships preconnected to the UnusLumen open server. First launch just pulls everything it publishes: prompt pack, skills, tool definitions, agent templates, canvas packs, config defaults. Nothing to configure. Install, connect your model, use the app.

Run your own server instead (optional, privacy maximalist option):

```bash
cd GURUbyUnusLumen-Server && cargo run --release
```

Then Settings > Sync > server URL (`http://YOUR_LAN_IP:PORT`). Works fully offline too, the app carries a bundled core pack and keeps whatever it last synced.

---

## Repository layout

```
guru-content/   prompts, skills, tool definitions, agent templates served by the server
guru-db/        server-side database (catalog / review pipeline)
guru-server/    Rust server source (axum-based, stateless)
app/            Android application source (in the app repo at code drop)
```

---

## Troubleshooting

- **Gradle sync fails on JDK version:** check File > Settings > Build Tools > Gradle > Gradle JDK = 17
- **Permission denials on device:** GURU surfaces a live status screen listing every granted/denied permission
- **ADB pair rejected:** make sure both devices share the network and the debug pair dialog is open at the moment you submit the code
- **Ollama not reachable from phone:** run `OLLAMA_HOST=0.0.0.0:11434 ollama serve` so it listens on the LAN, not localhost only

Questions: hello@unuslumen.com
Security: steven@unuslumen.com