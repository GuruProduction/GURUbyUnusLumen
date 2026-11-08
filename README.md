<div align="center">

<img src="GURUlogo.png" alt="GURU by Unus Lumen" width="600"/>

# GURU by Unus Lumen

**The most powerful AI framework on Android. Your model, your phone, your rules.**

![Status](https://img.shields.io/badge/status-public%20beta-e8c9ff)
![Licence](https://img.shields.io/badge/licence-AGPL--3.0-98ff7e)
![Platform](https://img.shields.io/badge/platform-Android-00C7BE)
![Privacy](https://img.shields.io/badge/privacy-on%20device%20only-FF375F)
![Model](https://img.shields.io/badge/model-bring%20your%20own-0A84FF)
![Built by](https://img.shields.io/badge/built%20by-Unus%20Lumen%2C%20Bristol%20UK-0A84FF)

</div>

---

GURU is not a chat app. It is a numen, an on-device agent framework that turns a language model into something with real hands on an Android phone.

The model is the least interesting part of what I built, because any model can be powerful when it is given the substrate to act through. GURU is that substrate. It ships its own Unix userland, so bash, git, Python, ffmpeg, sqlite, tesseract, curl, ssh, tmux and a proper shell are already on your phone the moment you install it. It holds 390 tools across 55 categories, all reachable through native Android APIs and 167 permissions you control. It keeps its memory in an encrypted Rust engine on the device, and it sends every outbound packet through Tor.

You bring the brain. GURU is the house that brain lives in, and the house comes with hands, memory, tools and a way out that does not go through anybody else.

## What GURU does

<details>
<summary><strong>Takes real control of your Android phone</strong></summary>

390 tools across 55 categories covering the shell, the file system, databases, contacts, calendar, camera, microphone, location, notifications, clipboard, alarms, SSH, ADB, web browsing, reverse engineering, media, smart home and more.

Every one goes through a native Android API behind a permission you can see and switch off. GURU reads the screen through an accessibility service, taps it, types into other apps, and drives your whole device by asking for something in plain English and carrying it through to the end.
</details>

<details>
<summary><strong>Its own Unix userland, already installed</strong></summary>

This is the part nobody else does. GURU ships BusyBox, Toybox, a full Termux prefix with bash and apt, CPython 3.14 with numpy and Pillow, git with a working HTTPS transport, ffmpeg, sqlite, OpenSSL, OpenSSH, jq, rsync, tmux and a reverse-engineering toolchain including a JDK, apktool, aapt2, jadx and zipalign.

No root. No setup. No tutorial. No "now go and install Termux". The tools are there because I put them there, and there is a pathshim that makes prebuilt Linux binaries run on a stock Android phone.
</details>

<details>
<summary><strong>Memory that never forgets, in Rust</strong></summary>

A cognitive architecture written in Rust and running in-process: episodic recall, a knowledge graph, consolidation, decay, dreaming, and vector search over your own history. Encrypted at rest with AES-256-GCM, fresh IVs, keys that never leave the device.

Every other assistant resets to zero each session. That is a design choice, and it is the reason none of them ever know you. GURU remembers what you told it in March, and it is 20 times faster than pgvector at finding it again.
</details>

<details>
<summary><strong>Builds its own tools, skills and automations</strong></summary>

I gave GURU the ability to extend itself, because a fixed assistant is a dead assistant. When it hits something it cannot do, it writes the tool, registers it and keeps it forever. Same for skills, automations and whole rooms in its own interface. It can rewrite the prompts it runs on.

You should never wait for a company to decide your problem is worth solving. Describe the problem, let it build the thing.
</details>

<details>
<summary><strong>Sees and drives everything on your network</strong></summary>

GURU scans mDNS, SSDP, ARP, Wi-Fi and Bluetooth, builds a live map of every device nearby, and then talks to them. Your television, your Fire Stick, your lights, your speakers, your doorbell, your servers, your smartwatch.

API keys where an API exists, ADB where the machine runs Android, SSH for anything with a shell, and accessibility-level UI control when there is nothing else. Anything with a computer in it is fair game.
</details>

<details>
<summary><strong>Lives on its own clock</strong></summary>

A heartbeat wakes on a schedule you author, half hour by half hour, and gets on with the work. It is not waiting to be asked. That is the difference between an assistant and a presence.

Alongside it, reusable automations run on intervals, cron expressions or triggers, and they run whether the app is open or not. Every run is recorded, compressed and kept so you can see what happened.
</details>

<details>
<summary><strong>Everything through Tor, and it fails closed</strong></summary>

Every model call, every search and every API request goes through an embedded Tor engine, so nobody sees what you ask. If the circuit is not ready the request throws rather than quietly leaking your IP.

Loopback and LAN addresses stay direct, so a local model keeps working with no configuration at all. One policy, in one place, enforced in code rather than in a promise.
</details>

<details>
<summary><strong>The instructions are yours to read</strong></summary>

Every prompt GURU runs on is pulled from the publisher, saved onto your phone in plain text, and editable. There is no compiled-in instruction set and no hidden layer of rules.

If you want to know why GURU did something, go and look. If you disagree with how it behaves, change it. There is no intellectual property I am protecting in those files, because you could do all of this yourself if you tried hard enough.
</details>

## Free, and open source

GURU is free. AGPL-3.0, no accounts, no subscription, no premium tier, no token markup. You connect a model and start talking to your numen. That is the entire setup.

**Honest state: this is a public beta.** The full source is here. On-device encryption and Tor routing have not yet had a professional security audit, though the security work is real and ongoing. There are bugs, particularly in areas outside my own daily use. The first people to install GURU are beta testers, and I would rather tell you that than dress it up. Install it knowing what it is, report what you find, and you are helping ship it.

The publisher is Unus Lumen's own infrastructure and stays closed source. It is deliberately stateless, holds no accounts and captures no conversations, so there is nothing there to leak. It feeds the app its prompts, skills, tool definitions, agent templates and canvas packs. A GURU that cannot reach a publisher still boots, but you will need to write and register your own prompts. Running publisher-less means operating your own compatible server or hand-installing the content, and the sync protocol is plain documented HTTP.

## Bring your own model

GURU asks once, on first launch, where your model lives. Then it never asks again.

| Option | How | What it means |
|---|---|---|
| **Cloud API key** | Anthropic, OpenAI, Google, xAI, or any OpenAI-compatible endpoint | Your key stays on your device. Your conversations do not. |
| **Local model** | Point at Ollama on your network, `http://YOUR_LAN_IP:11434`, or any OpenAI-compatible server | Recommended. Zero token cost, and your conversations never leave hardware you own. |
| **Rented GPU** | Take a server on [vast.ai](https://cloud.vast.ai), pull a model with Ollama, put a Cloudflare tunnel on it | Cheap unlimited usage, still private through Tor, and about 50p an hour. |

No inference runs through any Unus Lumen dependency, ever.

Be honest about cost with yourself. An agentic framework burns tokens at agentic volumes, and flagship cloud models billed per token add up fast. A 120-billion-parameter open model on rented hardware beats most paid tiers outright, and the free tiers you are used to are small models that feel like you are talking to a goldfish. Ollama Cloud is close to a cheat code: massive multimodal models, brilliant agentic coders, a serious selection and very little money.

## Install

GURU is not on the Play Store and will not be. Take the signed APK from the [latest release](../../releases/latest) and allow installation from unknown sources when Android asks.

The APK is about 743 MB, and that size is the whole point. It carries the Python runtime, the Termux userland, the reverse-engineering toolchain and the native binaries, so none of it has to be downloaded or assembled afterwards. Android 8.0 or later.

Verify the SHA-256 on the release page before you sideload if that matters to you. It should.

You will need a model connected before GURU can do anything useful, and the onboarding walks you through it.

## The market

Otio is the marketplace where GURU users publish agents and buy each other's. It runs inside the app: browse the shelf, read exactly what an agent is allowed to use, buy it once, and keep it even if the author withdraws it later. You keep 80 percent.

Every image below was captured live on a phone.

<table>
<tr>
<td width="33%"><img src="screenshots/marketplace/01-market.png" alt="The Otio market front"><br><b>The market</b><br>Search, categories, editor's pick, and everything published.</td>
<td width="33%"><img src="screenshots/marketplace/02-agent-archivist.png" alt="An agent's detail page"><br><b>An agent's page</b><br>What it does, what it may use, who made it, and what it costs.</td>
<td width="33%"><img src="screenshots/marketplace/03-checkout.png" alt="Checkout"><br><b>Checkout</b><br>One price, VAT shown, paid once.</td>
</tr>
<tr>
<td width="33%"><img src="screenshots/marketplace/04-purchase-done.png" alt="Purchase confirmation"><br><b>Yours</b><br>Switched on and in your agents, ready for work straight away.</td>
<td width="33%"><img src="screenshots/marketplace/05-your-agents.png" alt="Your agents"><br><b>Your agents</b><br>Updates waiting, things you bought, things you got free.</td>
<td width="33%"><img src="screenshots/marketplace/06-shelf.png" alt="Your shelf"><br><b>Your shelf</b><br>What you have published, what is in review, what is retired.</td>
</tr>
<tr>
<td width="33%"><img src="screenshots/marketplace/07-publish.png" alt="Publishing an agent"><br><b>Publishing</b><br>One line, a category, a price. You keep 80 percent.</td>
<td width="33%"><img src="screenshots/marketplace/11-earnings.png" alt="Earnings"><br><b>Earnings</b><br>What sold, what you keep, and when you get paid.</td>
<td width="33%"><img src="screenshots/marketplace/10-maker-profile.png" alt="A maker's profile"><br><b>A maker's profile</b><br>Who built it, what else they made, and whether it was reviewed.</td>
</tr>
<tr>
<td width="33%"><img src="screenshots/marketplace/08-market-offline.png" alt="The market unreachable"><br><b>Nothing hidden</b><br>When the market cannot be reached it says so, and everything you own still works.</td>
<td width="33%"><img src="screenshots/marketplace/09-search-empty.png" alt="An empty search"><br><b>An honest empty</b><br>No results means no results, not a broken connection.</td>
<td width="33%"></td>
</tr>
</table>

## Who is Unus Lumen

A tech company in Bristol, UK. Founded by Steven Newman. One person, unfunded, from a blank slate with no reference material. No team, no backers, nobody teaching me the rules.

I test the limits of what AI can do because the possibilities are endless and I refuse to be told no. Nobody taught me where the boundaries are supposed to be, so I never learned to build them in.

## What it becomes

Right now GURU is a proof of concept that works. It drives an Android phone through natural language with real permissions, real shell access and real execution.

What it becomes is an ecosystem. Every app on your phone becomes a dependency of GURU, and you only ever need to open one app. Your messages, your email, your calendar, your smart home, your searches, your bookings, your finance, your creative work, all of it in one place with one intelligence behind it.

Inside that ecosystem, users create. They build toolkits and skills and masks and agents and games, they sell them on Otio, and they sell their own data on their own terms. Unus Lumen takes a small cut of every transaction and keeps the lights on. The economy runs itself.

Masks will let you change the personality of your numen completely. Serious and professional, funny and crude, dark and twisted, warm and gentle. Whoever you need it to be.

The vision is this. You get an idea in the shower. By the time you are dressed, it is already in motion. Your numen is making the calls, building the thing, putting it in place. Technology working for people instead of against them. Humans being human again, machines getting on with the work.

## Read more

| Document | What it covers |
|---|---|
| [Vision](VISION.md) | What GURU is for and why it exists |
| [Architecture](ARCHITECTURE.md) | The parts, the runtime paths and the invariants |
| [Contributing](CONTRIBUTING.md) | How to build, test, and get a pull request merged |
| [Security](SECURITY.md) | Vulnerability reporting and the attack surface |
| [Privacy Policy](PRIVACY_POLICY.md) | What leaves the device, and what does not |
| [NOTICE](NOTICE.md) | Every third-party component inside the APK, and its licence |
| [A note from Steven](A-NOTE-FROM-STEVEN.md) | Founder's story, in my own words |

## Build

```bash
cd GURUbeta-FULL && ./gradlew assembleDebug
```

JDK 17 and the Android SDK are required, and the Gradle wrapper lives inside `GURUbeta-FULL` rather than at the root. Full instructions, including the build lock and the three verification layers, are in [CONTRIBUTING.md](CONTRIBUTING.md).

## Licence

AGPL-3.0 for the app. The GURU name and branding are trademarks of Unus Lumen Ltd, Bristol, UK. A fork renames and does not imply endorsement. The licence governs the code and the trademark governs the name, and [NOTICE.md](NOTICE.md) has the full posture along with every bundled component's terms.

## The spirit

I was sick of being data farmed, lied to, and told no by products that charge extortionate amounts for a substandard experience. I wanted ultimate control of my own life, backed by superintelligence, with total privacy. So I built it.

Category defining tech should be community driven. Open source is the only road now. I have shown that this level of capability, control and privacy on an ordinary phone is possible. Now help me build it.

## Connect

- Website: **[www.unuslumen.com](https://www.unuslumen.com)**
- TikTok: [@introducingguru](https://www.tiktok.com/@introducingguru)
- Instagram: [@introducingguru](https://www.instagram.com/introducingguru)
- YouTube: [@introducingguru](https://www.youtube.com/@introducingguru)
- X: [@introducingguru](https://x.com/introducingguru)
- Kick: [kick.com/introducingguru](https://kick.com/introducingguru)

---

**GURU: built by Steven Newman** — Unus Lumen, Bristol, UK

steven@unuslumen.com
