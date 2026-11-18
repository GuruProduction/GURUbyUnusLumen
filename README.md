<div align="center">

<img src="GURUlogo.png" alt="GURU by Unus Lumen" width="600"/>

# GURU by Unus Lumen

**A language model with a Linux shell, 390 tools and your network to work with.**

![Status](https://img.shields.io/badge/status-public%20beta-e8c9ff)
![Version](https://img.shields.io/github/v/release/GuruProduction/GURUbyUnusLumen?label=version&color=FFD60A)
![Licence](https://img.shields.io/badge/licence-AGPL--3.0-98ff7e)
![Platform](https://img.shields.io/badge/platform-Android%208.0%2B-00C7BE)

</div>

---

GURU gives a language model a shell and about 390 tools on your Android phone, and lets it use them. It runs a command, reads the result, decides the next step, and keeps going until the job is done. It reads and taps the screen. It finds and controls devices on your network.

The userland sits in the app's own storage: bash, apt, git, Python, ffmpeg, sqlite, curl, ssh, tesseract, and a reverse-engineering toolchain. No root, no adb, no "go and install Termux". A path shim makes prebuilt Linux binaries run on a stock phone.

You bring the model: Ollama on your own hardware, or your own key for Anthropic, OpenAI, Google or xAI. AGPL-3.0, no account, no analytics SDKs, sideloaded APK.

Public beta. One maintainer, largely AI-written, no outside security audit. If you only want a shell, use Termux.

## What it does that a shell does not

A shell waits for you. This does not.

The model chooses a tool, calls it, reads what came back, chooses again. Twenty calls deep it is still going, and a failure mid-run is information rather than the end of the turn. Point it at a problem, walk away, come back to it solved or to an account of why it is not.

That loop is the difference between a terminal on your phone and an agent holding one.

<details>
<summary><strong>390 tools across the phone</strong></summary>

167 declared permissions, sorted into eight grant tiers that separate a denial from a privileged permission, absent hardware, a build fault, or something no setting can fix.

A tool whose permission you have not granted returns one sentence: what is missing, why it matters, how to grant it. Never an empty list. "No messages match" and "I am not allowed to read your messages" are different facts.

The accessibility service reads and taps the screen, so any app with a UI becomes scriptable. The notification listener sees what arrives. MediaProjection captures the display with your consent. Clipboard, files, SMS, calls, contacts, calendar, camera, microphone, sensors, Bluetooth, NFC, location, alarms, settings, installed apps, device admin: each a gated domain.

</details>

<details>
<summary><strong>Your whole house</strong></summary>

Protocols devices use, not a list of brands I partnered with. UPnP and SOAP. DIAL for casting and app launch, including a device's app list and the difference between a refusal and an unanswered request. Wake-on-LAN for anything asleep. Android TV Remote over its own protobuf, so a Sony or Philips or Shield is drivable with no emitter. Roku ECP. Samsung's websocket remote. LG webOS. Xbox SmartGlass down to the ECDH exchange and the AES-CBC channel the console demands. SNMP for hardware that will never have an app. IR through a Broadlink blaster or the phone's own emitter. SSH for any machine with a shell.

Televisions, Fire Sticks, consoles, lights, speakers, blinds, doors, sensors, the router, the printer, the thermostat. On the network, GURU sees it, names it, and finds the way in.

Every write is followed by a read, and the device's answer is compared against the value written. A command against a capability the device does not report is refused, naming both. A silent no and a success look identical.

</details>

<details>
<summary><strong>Discovery that tells you the truth</strong></summary>

Most scanners hand you addresses, miss anything that does not answer a broadcast, and call it a day.

GURU sweeps every port rather than a curated list, because a curated list is the bug. It reads mDNS and SSDP with the multicast lock arranged. It fingerprints endpoints by HTTP response, certificate, DIAL description and UPnP document, so it names what a thing is. It keeps the map and notices what is new.

When it cannot reach something it says so, and it tells a refusal apart from an absence. An empty list never means "I did not check".

</details>

<details>
<summary><strong>Memory that persists, encrypted on device</strong></summary>

The brain is a Rust engine running in-process, sealed in an encrypted vault. Argon2id at 256 MiB and three passes derives the key, XChaCha20-Poly1305 seals every record, and the passphrase sits in an Android Keystore envelope the app cannot read.

Inside is a cognitive architecture, not a vector table. Facts enter a buffer layer, promote to episodic when recalled, settle into semantic when strong and repeated. Retrieval strengthens. A daily consolidation pass merges duplicates and archives what goes weak. A graph records typed edges: semantic, temporal, causal, and cross-references marking two memories as contradicting, superseding or duplicating.

Nothing is deleted. Deleting archives, because a trail you can rewrite is not a trail.

If the Keystore envelope is lost while a vault exists, the app refuses to boot and leaves every byte alone.

With a cloud model, recalled memories enter the prompt and leave the phone with the conversation. With a local model they do not.

</details>

<details>
<summary><strong>It builds its own capabilities</strong></summary>

A fixed assistant is a dead assistant.

GURU writes tools and registers them, permanently. It writes skills and automations. It adds rooms to its own lobby, each starting empty and composed by talking to it. It can rewrite the prompt stack it runs on.

Self-extension runs through the same permission layer, so a generated tool is bounded by what you granted and no more.

You should not wait on a company to decide your problem is worth solving. Describe the problem and let it build the thing.

</details>

<details>
<summary><strong>Rooms, not one screen</strong></summary>

The lobby is a page of tiles you drag into order, or reorder by voice. Name a room and it appears; compose it by conversation.

**Portal** is the chat: streaming replies, tool calls you can watch, attachments, slash commands (`/help`, `/status`, `/tools`, `/think`, `/stop`, `/tor`, `/clearnet`).

**Agents** is where you make your own. A name, instructions, the tools it may use. The picker shows which tools it holds and offers nothing else. One press grants all or none.

**Inner Mind** is a living graph of everything GURU holds about you: memories, conversations, tasks, notes, projects, agents, skills, thoughts, people, devices. One tissue to explore, not a list to scroll.

**Heartbeat** is the clock. **Skills** are imported capabilities. **Notes**, **Journal**, **Projects**, **Calendar** and **Thoughts** are workspaces with their own home-screen widgets. **Settings** configures the brain and the model. **Media** ingests, probes, transcribes and indexes what you throw at it, including OCR and keyframe extraction on video. **Otio** is the market.

</details>

<details>
<summary><strong>Lives on its own clock</strong></summary>

The heartbeat is the difference between an assistant and a presence.

It fires every thirty minutes into your newest conversation with a prompt you write. Yours, in the database, editable, with a preview of exactly what a beat will inject.

The room shows when a beat last arrived and when the next is booked. A booking proves an alarm was written. Firing proves one was delivered. Without both, "scheduled and dead" looks like "scheduled and working".

The switch cannot be turned on without a prompt. Off always works, and nothing but you turns it off.

</details>

<details>
<summary><strong>Egress: Tor for the internet, direct for your house</strong></summary>

Public traffic rides an embedded Tor engine. Loopback and LAN stay direct, because Tor cannot route private space and asking it to is a guaranteed failure.

Three postures, set with `/tor` and `/clearnet`. **Automatic** is the default: a public host rides Tor. **Tor only** locks it and refuses the override. **Clearnet** is yours to turn on, and every result that went that way says so.

With no circuit, a public request waits rather than falling back to direct. Past the window it fails closed and says why. A request carrying a credential always forces Tor and ignores the override.

One policy, one place, enforced in code. The [security policy](SECURITY.md) states what it does and where the limits are.

</details>

## The instructions are published

Every prompt, skill, tool definition, agent template and canvas pack GURU receives sits in a public repo: **[GuruProduction/GURU-content](https://github.com/GuruProduction/GURU-content)**, mirrored from the live database on every publish. Read the instruction set that ships to your phone and diff it month to month.

Content is served from a publisher rather than compiled into the APK, so a new capability reaches you the day it is built instead of the day the next release ships. The serving infrastructure stays closed. The payload does not, and it was never secret: a synced prompt sits readable in that phone's storage.

Read it, change it on your install, point GURU at your own server. The sync protocol is documented HTTP.

## Bring your own model

GURU asks once, on first launch, where your model lives. Then never again.

| Option | How | What it means |
|---|---|---|
| **Local model** | Ollama on your network, `http://YOUR_LAN_IP:11434`, or any OpenAI-compatible server | Zero token cost, and conversations never leave hardware you own. |
| **Cloud API key** | Anthropic, OpenAI, Google, xAI, or any OpenAI-compatible endpoint | Your key stays on your device. Conversations go to that provider under their terms. |
| **Rented GPU** | A server on [vast.ai](https://cloud.vast.ai), a model with Ollama, a Cloudflare tunnel | Cheap unlimited usage, private through Tor, about 50p an hour. |

No inference runs through any Unus Lumen dependency. I take no cut of your tokens and see none of your prompts.

An agentic framework burns tokens at agentic volumes, and flagship cloud models billed per token add up. A 120-billion-parameter open model on rented hardware beats most paid tiers, and free tiers are small models that feel like a goldfish with amnesia.

GURU adds no restrictions of its own. A cloud provider applies theirs, and local weights do whatever they do.

## Install

Not on the Play Store and will not be. Take the signed APK from the [latest release](../../releases/latest) and allow unknown sources.

About 743 MB, and the size is the point: the Python runtime, the userland, the reverse-engineering toolchain and the native binaries are inside, so none of it is fetched later. Android 8.0 or later.

Verify before you sideload. The release page carries a SHA-256, and the signing certificate's fingerprint is in [SECURITY.md](SECURITY.md).

```bash
sha256sum guru-v3.77-release.apk
apksigner verify --print-certs guru-v3.77-release.apk
```

Connect a model before GURU can do anything useful. Onboarding walks you through it.

## Otio

One layer is live: the creator market, where you sell what you build. Agents today; the same machinery carries skills, tools and packs. Buy once, keep it even if the author withdraws it, and read every tool an agent may use before you pay.

Every fact on that screen is served, not invented. Counts come from install and rating tables, makers from publisher records, prices from the catalogue. Where the server has nothing to say the screen says nothing, so a rating nobody gave shows no stars instead of a fake zero. When the market cannot be reached it says so, and that everything you own still works.

**Payments are not switched on.** A priced agent tells you so rather than giving itself away and implying the price was never real.

Two further layers are designed and unbuilt: a data marketplace where you sell your own captured data, and a decentralised social layer. They live in [VISION.md](VISION.md) until they exist.

Identity is a wallet, not an account. No sign-up, no email capture. You trade under a pseudonym. Unus Lumen holds the funds and pays creators, 80 percent to the maker, 20 percent to Unus Lumen.

## Honest state

Public beta, one maintainer, no CI, no static analysis gate, no outside security audit. The code is largely AI-written, reviewed by tests and by me. That is not a stranger's review. Bugs exist, particularly outside my daily use.

Every release is a beta. The [security policy](SECURITY.md) lists every known gap.

Report what you find.

## Who is Unus Lumen

A tech company in Bristol, UK, founded by Steven Newman. One person, unfunded.

I test the limits of what AI can do because the possibilities are endless and I refuse to be told no. Nobody taught me where the boundaries belong, so I never learned to build them in.

## Read more

| Document | What it covers |
|---|---|
| [Vision](VISION.md) | What GURU is for and why it exists |
| [Architecture](ARCHITECTURE.md) | The parts, the runtime paths and the invariants |
| [Contributing](CONTRIBUTING.md) | How to build, test, and get a pull request merged |
| [Security](SECURITY.md) | Reporting, measured posture, every known gap |
| [Privacy Policy](PRIVACY_POLICY.md) | What leaves the device, and what does not |
| [Content](https://github.com/GuruProduction/GURU-content) | Every prompt, skill, tool and agent the server sends |
| [NOTICE](NOTICE.md) | Every third-party component inside the APK, and its licence |
| [A note from Steven](A-NOTE-FROM-STEVEN.md) | Why I built this, in my own words |

## Build

```bash
cd GURUbeta-FULL && ./gradlew assembleDebug
```

JDK 17 and the Android SDK. The wrapper lives inside `GURUbeta-FULL`, not at the root. Build lock and verification layers are in [CONTRIBUTING.md](CONTRIBUTING.md).

## Licence

AGPL-3.0 for the app. The GURU name and branding are trademarks of Unus Lumen Ltd, Bristol, UK. A fork renames and implies no endorsement. [NOTICE.md](NOTICE.md) has the full posture and every bundled component's terms.

## The spirit

I was sick of being data farmed, lied to, and told no by products that charge extortionate amounts for a substandard experience. I wanted ultimate control of my own life, backed by superintelligence, with total privacy. So I built it.

Category defining tech should be community driven. Open source is the only road now. This level of capability, control and privacy on an ordinary phone is possible. Now help me build it.

## Connect

- Website: **[www.unuslumen.com](https://www.unuslumen.com)**
- X: [@introducingguru](https://x.com/introducingguru)
- TikTok: [@introducingguru](https://www.tiktok.com/@introducingguru)
- Instagram: [@introducingguru](https://www.instagram.com/introducingguru)
- YouTube: [@introducingguru](https://www.youtube.com/@introducingguru)
- Kick: [kick.com/introducingguru](https://kick.com/introducingguru)

---

**GURU: built by Steven Newman** — Unus Lumen, Bristol, UK

steven@unuslumen.com
