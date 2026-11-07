<div align="center">

<img src="GURUlogo.png" alt="GURU by Unus Lumen" width="600"/>

# GURU by Unus Lumen

**A privacy focused AI framework that gives an your model real hands on your Android device.**

![Status](https://img.shields.io/badge/status-public%20beta-e8c9ff)
![Licence](https://img.shields.io/badge/licence-AGPL--3.0-98ff7e)
![Platform](https://img.shields.io/badge/platform-Android-00C7BE)
![Privacy](https://img.shields.io/badge/privacy-on%20device%20only-FF375F)
![Model](https://img.shields.io/badge/model-bring%20your%20own-0A84FF)
![Built by](https://img.shields.io/badge/built%20by-Unus%20Lumen%2C%20Bristol%20UK-0A84FF)

</div>

---

GURU is a privacy focused open-source framework that lets a language model of your choosing drive an Android phone. A framework with real OS-level access, 50+ tool sets, on-device memory in Rust, and Tor routing that turns an AI's intent into action on your hardware, your terms.

The brain is yours to choose. GURU is the house that brain lives in. You connect a cloud API key, a local LLM, or a rented GPU, and GURU gives that brain real hands on your device.

## What GURU does

<details>
<summary><strong>Drives your Android phone through natural language</strong></summary>

57 tool sets covering shell, file system, databases, contacts, calendar, camera, smart home, SSH, ADB, web browsing, location, notifications, automation, and more. 160+ Android permissions, all through native APIs, all user-toggleable. The connected AI picks up what you want done and carries it through to completion using the framework's tools.
</details>

<details>
<summary><strong>On-device memory built in Rust</strong></summary>

A custom cognitive architecture that stores, retrieves, merges, and decays memories locally. Nothing goes to the cloud. The memory lives on your device across every session, reboot, and update. 20+ times faster than pgvector. The more you use it, the more it knows you.
</details>

<details>
<summary><strong>Controls devices on your network</strong></summary>

GURU scans WiFi, Bluetooth, mDNS, SSDP, ARP, and builds a live map of everything near you. Controls lights, TVs, Fire Sticks, servers, smartwatches, speakers, doorbells. Anything with a computer and a connection. API keys where they exist, ADB where the machine runs Android, SSH for arbitrary machines, and accessibility-level UI control when nothing else is available.
</details>

<details>
<summary><strong>Routes all traffic through Tor</strong></summary>

Every web request, model query, and API call goes through an embedded Tor engine. Nobody sees what you ask or what you search. Fails closed when Tor is not ready rather than leaking your IP. Loopback and LAN addresses stay direct so local model servers work with zero setup.
</details>

<details>
<summary><strong>Automates your digital life</strong></summary>

Reusable automations with scheduling that runs on intervals, cron, or triggers. The connected AI builds the sequence through the framework, and it runs on its own, even when the app is not open. Daily, weekly, monthly, or full cron expressions for total precision. Results get stored, compressed, and kept so you can see what happened across every run.
</details>

<details>
<summary><strong>Builds new tools at runtime</strong></summary>

If the connected AI encounters something it cannot do, it builds the tool, registers it locally, and reuses it forever. If a tool breaks, the AI fixes it or schedules an automation to maintain it. The toolkit is alive. It grows itself, repairs itself, and evolves.
</details>

<details>
<summary><strong>Private by design</strong></summary>

GURU runs as a locally installed APK. Memory lives on your device. All external traffic routes through Tor. Files are encrypted with AES-256-GCM with fresh IVs on every operation, keys never leave the device. No data farmers, no middlemen, no company holding a copy of your life. The only people who ever see your data are you and your GURU.
</details>

## Free and open source

GURU is free. Fully free and open source under AGPL-3.0. No accounts, no subscription, no premium tier. You type in your api key and start talking to your numen. That is the only setup.

**Honest state: this is a public beta.** The full app source is in this repo and the security hardening pass is still in progress. Permission boundaries, on-device encryption, and Tor routing have not yet had a professional audit. There are known bugs, especially in areas outside the author's daily use. The first people to install GURU are beta testers. Install it knowing that, report what you find, and you are helping ship it. Every issue raised here helps.

The sync server is Unus Lumen's own infrastructure and stays closed source. It is a deliberately stateless publisher that holds no accounts and captures no conversations, with nothing to leak either way. The publisher feeds the app: prompts, skills, tool definitions, agent templates, and canvas packs arrive over sync. A GURU disconnected from out api boots but does not work as intended, youll need to write and regester your own prompts for this. Running it publisher-less means being technical enough to operate your own compatible publisher or hand-install the content that the app requires ro woek. The sync protocol is plain documented HTTP.

## Bring your own model

GURU asks once, on first launch, where your model lives. After that it never asks again.

| Option | How | Notes |
|---|---|---|
| **Cloud API key** | Anthropic, OpenAI, Google, xAI, or any OpenAI-compatible endpoint | (INSECURE) Your key stays on your device. Conversations DO NOT stay on your device. |
| **Local LLM** | Point at a local ollama or ollama cloud instance on your network (`http://YOUR_LAN_IP:11434`) or any OpenAI-compatible server | Recommended for maximum privacy. Zero token cost. | Your key stays on your device. Conversations stay on your device. YOU ARE PROTECTED |
| **Rented GPU** | Find a server on [vast.ai](https://cloud.vast.ai), pick a model from Hugging Face or Ollama, set up a Cloudflare tunnel | Cheap unlimited usage. (BETTER) Still private through Tor. |

The app never routes model inference through any Unus Lumen dependency. Be honest about cost: an agentic framework like this burns tokens at agentic volumes. Flagship cloud models billed per token add up fast. Big-tech chat logging is also a trust problem for a companion that knows your whole life. Local LLMs or Ollama Cloud are the most cost-effective choices. Ollama Cloud is almost a cheat code: massive multimodal models, smaller agentic coders, brilliant selection, and cheap.

## Who is Unus Lumen

Unus Lumen is a tech startup based in Bristol, UK. Founded by Steven Newman. One person, unfunded, from a blank slate with no reference material. No team, no backers, no one teaching the rules. Just someone who was sick of being told no and decided to build the thing anyway.

We test the limits on what AI is capable of because we know the possibilities are endless and we refuse to be told no. No one taught us the rules, so we didn't learn where the boundaries are supposed to be. Just a team that builds what shouldn't be possible and ships it anyway.

## What it becomes

Right now GURU is a proof of concept that works. It drives an Android phone through natural language with real permissions, real shell access, real execution.

What it becomes is a full ecosystem. Every app on your phone becomes a dependency of GURU. You only ever need to open one app. The connected AI handles your messages, your emails, your calendar, your smart home, your searches, your bookings, your finances, your creative tools, everything. The UI for all of it lives inside GURU.

Inside that ecosystem, users create. They build toolkits and skills and masks and agents and games. They sell them through Ottio, our marketplace. They sell their own data on their own terms. They trade and prosper and Unus Lumen takes a small cut of every transaction. The economy runs itself. We just keep the lights on.

Masks will let users change the personality and behaviour of their numen completely. Your GURU can be whoever you want it to be. Serious and professional. Funny and crude. Dark and twisted. Warm and gentle. Whatever fits you.

The vision is this. You get an idea before you get in the shower. By the time you're dressed, it's already in motion. Your numen is already working on it. Already building. Already making calls. Already putting things in place. Technology working for people instead of fighting them. Humans being humans again. Machines getting on with the work.

## Read more

| Document | What it covers |
|---|---|
| [Vision](VISION.md) | The philosophy, the numen concept, why GURU exists |
| [Architecture](ARCHITECTURE.md) | The real system structure, modules, and design constraints |
| [Technical Roadmap](TECHNICAL_ROADMAP.md) | What is built, what is in progress, what is planned |
| [Business Model](BUSINESS_MODEL.md) | Revenue streams, Ottio marketplace, creator economy |
| [Contributing](CONTRIBUTING.md) | How to build, add tools, submit PRs, what needs help |
| [Build Guide](BUILD.md) | Build the APK from source, step by step |
| [Roadmap](ROADMAP.md) | Why we went free and open source, where the work stands |
| [Security](SECURITY.md) | Vulnerability reporting and attack surface priorities |
| [NOTICE](NOTICE.md) | Trademarks and licensing posture for forks |

## Build

```bash
cd GURUbeta-FULL && ./gradlew assembleDebug 2>&1 | tail -500
```

Full build instructions in [BUILD.md](BUILD.md). JDK 17, Android SDK 35/24. Prebuilt signed APK on the [Releases](../../releases) page with a SHA-256 checksum so you can verify what you sideload.

## Licence

AGPL-3.0 for the app. GURU name and branding are trademarks of Unus Lumen Ltd, registered in Bristol, UK. Forks rename and do not imply endorsement. The licence governs the code; the trademark governs the name. See [NOTICE.md](NOTICE.md) for the full trademark posture.

## Videos

Watch GURU work, live:

- [GURU Portal mini game: Snake](videos/GURU-portal-mini-game-snake.mp4)
- [GURU Portal mini game: Space Shooter](videos/GURU-portal-mini-game-space-shooter.mp4)
- [GURU external control demo](videos/GURU-external-control-demo.mp4)
- [GURU images rendering](videos/GURU-images-rendering.mp4)

## Ottio, the agent market

Ottio is the marketplace where GURU users publish agents and buy each other's. It runs inside the app: browse the shelf, read exactly what an agent is allowed to use, buy it once, and keep it even if the author withdraws it later. Captured live on a phone.

<table>
<tr>
<td width="33%"><img src="screenshots/marketplace/01-market.png" alt="The Ottio market front"><br><b>The market</b><br>Search, categories, editor's pick, and everything published.</td>
<td width="33%"><img src="screenshots/marketplace/02-agent-archivist.png" alt="An agent's detail page"><br><b>An agent's page</b><br>What it does, what it may use, who made it, and what it costs.</td>
<td width="33%"><img src="screenshots/marketplace/03-checkout.png" alt="Checkout"><br><b>Checkout</b><br>One price, VAT shown, paid once through Stripe.</td>
</tr>
<tr>
<td width="33%"><img src="screenshots/marketplace/04-purchase-done.png" alt="Purchase confirmation"><br><b>Yours</b><br>Switched on and in your agents, ready for work straight away.</td>
<td width="33%"><img src="screenshots/marketplace/05-your-agents.png" alt="Your agents"><br><b>Your agents</b><br>Updates waiting, things you bought, things you got free.</td>
<td width="33%"><img src="screenshots/marketplace/06-shelf.png" alt="Your shelf"><br><b>Your shelf</b><br>What you have published, what is in review, what is retired.</td>
</tr>
<tr>
<td width="33%"><img src="screenshots/marketplace/07-publish.png" alt="Publishing an agent"><br><b>Publishing</b><br>One line, a category, a price. You keep 80 percent.</td>
<td width="33%"><img src="screenshots/marketplace/11-earnings.png" alt="Earnings"><br><b>Earnings</b><br>What sold, what you keep, and when Stripe pays out.</td>
<td width="33%"><img src="screenshots/marketplace/10-maker-profile.png" alt="A maker's profile"><br><b>A maker's profile</b><br>Who built it, what else they made, and whether it was reviewed.</td>
</tr>
<tr>
<td width="33%"><img src="screenshots/marketplace/08-market-offline.png" alt="The market unreachable"><br><b>Nothing hidden</b><br>When the market cannot be reached it says so, and everything you own still works.</td>
<td width="33%"><img src="screenshots/marketplace/09-search-empty.png" alt="An empty search"><br><b>An honest empty</b><br>No results means no results, not a broken connection.</td>
<td width="33%"></td>
</tr>
</table>

## The Spirit

I was sick of being data farmed, lied to, and told no by AI products that charge extortionate amounts for substandard experiences. I wanted ultimate control of my own life backed by superintelligence with total privacy. So I built it.

Category defining tech should be community driven. Open source is the only road now. I've shown that this level of capability, control, and privacy on an ordinary phone is possible. Now help me build it.

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
