<div align="center">

<img src="GURUlogo.png" alt="GURU by Unus Lumen" width="600"/>

# GURU by Unus Lumen

**A framework that gives an AI real hands on your Android device.**

![Status](https://img.shields.io/badge/status-public%20beta-e8c9ff)
![Licence](https://img.shields.io/badge/licence-AGPL--3.0-98ff7e)
![Platform](https://img.shields.io/badge/platform-Android-00C7BE)
![Privacy](https://img.shields.io/badge/privacy-on%20device%20only-FF375F)
![Model](https://img.shields.io/badge/model-bring%20your%20own-0A84FF)
![Built by](https://img.shields.io/badge/built%20by-Unus%20Lumen%2C%20Bristol%20UK-0A84FF)

</div>

---

GURU is an open-source framework that lets a language model of your choosing drive an Android phone. Not an assistant that suggests things and waits for you to do them. A framework with real OS-level access, 50+ tool sets, on-device memory in Rust, and Tor routing that turns an AI's intent into action on your hardware, your terms.

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
| **Cloud API key** | Anthropic, OpenAI, Google, xAI, or any OpenAI-compatible endpoint | Your key stays on your device. Conversations stay on your device. |
| **Local LLM** | Point at a local ollama or ollama cloud instance on your network (`http://YOUR_LAN_IP:11434`) or any OpenAI-compatible server | Recommended for maximum privacy. Zero token cost. |
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

## Screenshots

A real app, running on a real phone. All images captured live from GURU:

| | | | | | |
|---|---|---|---|---|---|
| ![](screenshots/GURU%20banter.jpeg) **GURU banter** | ![](screenshots/GURU%20banter%20not%20edward%201.jpeg) **GURU banter not edward 1** | ![](screenshots/GURU%20banter%20not%20edward%202.jpeg) **GURU banter not edward 2** | ![](screenshots/GURU%20Earn%20bug%20bounties.jpeg) **GURU Earn bug bounties** | ![](screenshots/GURU%20Tennis%20betting.jpeg) **GURU Tennis betting** | ![](screenshots/GURU%20automation.jpeg) **GURU automation** |
| ![](screenshots/GURU%20batch%20tool%20call.jpeg) **GURU batch tool call** | ![](screenshots/GURU%20betting%20.jpeg) **GURU betting** | ![](screenshots/GURU%20brain%20part%202.jpeg) **GURU brain part 2** | ![](screenshots/GURU%20brain.jpeg) **GURU brain** | ![](screenshots/GURU%20browsing%20capabilities.jpeg) **GURU browsing capabilities** | ![](screenshots/GURU%20browsing.jpeg) **GURU browsing** |
| ![](screenshots/GURU%20device%20control%20%22my%20body%22.jpeg) **GURU device control "my body"** | ![](screenshots/GURU%20device%20control%20more.jpeg) **GURU device control more** | ![](screenshots/GURU%20device%20control.jpeg) **GURU device control** | ![](screenshots/GURU%20dpa.jpeg) **GURU dpa** | ![](screenshots/GURU%20earn%20arbitrage.jpeg) **GURU earn arbitrage** | ![](screenshots/GURU%20earn%20ethical%20hacking.jpeg) **GURU earn ethical hacking** |
| ![](screenshots/GURU%20earn%20match%20betting.jpeg) **GURU earn match betting** | ![](screenshots/GURU%20earn%20render.jpeg) **GURU earn render** | ![](screenshots/GURU%20earn%20trading.jpeg) **GURU earn trading** | ![](screenshots/GURU%20esports%20betting.jpeg) **GURU esports betting** | ![](screenshots/GURU%20exchange%20trading.jpeg) **GURU exchange trading** | ![](screenshots/GURU%20football%20betting.jpeg) **GURU football betting** |
| ![](screenshots/GURU%20fresh%20portal.jpeg) **GURU fresh portal** | ![](screenshots/GURU%20full%20apps.jpeg) **GURU full apps** | ![](screenshots/GURU%20fun.jpeg) **GURU fun** | ![](screenshots/GURU%20gambeling%20%28casino%29.jpeg) **GURU gambeling (casino)** | ![](screenshots/GURU%20installing%20kodi%20on%20my%20firestick%201.jpeg) **GURU installing kodi on my firestick 1** | ![](screenshots/GURU%20installing%20kodi%20on%20my%20firestick%202.jpeg) **GURU installing kodi on my firestick 2** |
| ![](screenshots/GURU%20installing%20kodi%20on%20my%20firestick%203.jpeg) **GURU installing kodi on my firestick 3** | ![](screenshots/GURU%20installing%20kodi%20on%20my%20firestick%20tools%20calls.jpeg) **GURU installing kodi on my firestick tools calls** | ![](screenshots/GURU%20installing%20kodi%20on%20my%20firestick%20tools%20ui.jpeg) **GURU installing kodi on my firestick tools ui** | ![](screenshots/GURU%20live%20dashboards.jpeg) **GURU live dashboards** | ![](screenshots/GURU%20local%20memory.jpeg) **GURU local memory** | ![](screenshots/GURU%20media%20engine%20no%20JS%20permissions%20%28intentional%29.jpeg) **GURU media engine no JS permissions (intentional)** |
| ![](screenshots/GURU%20mini%20apps%20in%20app.jpeg) **GURU mini apps in app** | ![](screenshots/GURU%20mini%20games%20demo.jpeg) **GURU mini games demo** | ![](screenshots/GURU%20notification.jpeg) **GURU notification** | ![](screenshots/GURU%20permissions%20intent.jpeg) **GURU permissions intent** | ![](screenshots/GURU%20permissions%20ui%20boolean%20off.jpeg) **GURU permissions ui boolean off** | ![](screenshots/GURU%20permissions%20ui%20boolean%20on.jpeg) **GURU permissions ui boolean on** |
| ![](screenshots/GURU%20photobook%20portal%20render%202.jpeg) **GURU photobook portal render 2** | ![](screenshots/GURU%20photobook%20portal%20render%20bristol%201.jpeg) **GURU photobook portal render bristol 1** | ![](screenshots/GURU%20photobook%20portal%20render%20bristol%2012.jpeg) **GURU photobook portal render bristol 12** | ![](screenshots/GURU%20photobook%20portal%20render%20bristol%2013.jpeg) **GURU photobook portal render bristol 13** | ![](screenshots/GURU%20photobook%20portal%20render%20bristol%2014.jpeg) **GURU photobook portal render bristol 14** | ![](screenshots/GURU%20photobook%20portal%20render%20bristol%205.jpeg) **GURU photobook portal render bristol 5** |
| ![](screenshots/GURU%20photobook%20portal%20render%20bristol%20more.jpeg) **GURU photobook portal render bristol more** | ![](screenshots/GURU%20test%20photo%20album%20cats%201.jpeg) **GURU test photo album cats 1** | ![](screenshots/GURU%20test%20photo%20album%20cats%202.jpeg) **GURU test photo album cats 2** | ![](screenshots/GURU%20test%20photo%20album%20cats%203.jpeg) **GURU test photo album cats 3** | ![](screenshots/GURU%20test%20photo%20album%20cats%204.jpeg) **GURU test photo album cats 4** | ![](screenshots/GURU%20test%20photo%20album%20cats%205.jpeg) **GURU test photo album cats 5** |
| ![](screenshots/GURU%20thinking%2C%20streaming%20%26%20responding.jpeg) **GURU thinking, streaming & responding** | ![](screenshots/GURU%20tool%20ui%20result%20ui.jpeg) **GURU tool ui result ui** | ![](screenshots/GURU%20tools%20ui%20level%202%20%28notification%29.jpeg) **GURU tools ui level 2 (notification)** | ![](screenshots/GURU%20web.jpeg) **GURU web** | ![](screenshots/Guru%20being%20Guru.jpeg) **Guru being Guru** | ![](screenshots/Guru%20intro%20showing%20loading%20state.jpeg) **Guru intro showing loading state** |
| ![](screenshots/Guru%27s%20thinking.jpeg) **Guru's thinking** | ![](screenshots/More%20GURU%20Device%20Control.jpeg) **More GURU Device Control** | ![](screenshots/More%20GURU%20permissions%20boolean%20ui.jpeg) **More GURU permissions boolean ui** | ![](screenshots/More%20GURU%20thinking.jpeg) **More GURU thinking** | ![](screenshots/More%20GURU%20tools%20UI.jpeg) **More GURU tools UI** | ![](screenshots/More%20tools%20ui%20GURU.jpeg) **More tools ui GURU** |
| ![](screenshots/What%20can%20GURU%20do%3F.jpeg) **What can GURU do?** | ![](screenshots/more%20GURU%20tools%20ui%20.jpeg) **more GURU tools ui** | | | | |

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