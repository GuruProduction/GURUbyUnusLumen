# GURU

[![Release](https://img.shields.io/github/v/release/GuruProduction/GURUbyUnusLumen?include_prereleases&label=release)](https://github.com/GuruProduction/GURUbyUnusLumen/releases) [![Licence](https://img.shields.io/github/license/GuruProduction/GURUbyUnusLumen)](LICENSE) [![Android 8.0+](https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white)](#install) [![Stars](https://img.shields.io/github/stars/GuruProduction/GURUbyUnusLumen)](https://github.com/GuruProduction/GURUbyUnusLumen/stargazers) [![Last commit](https://img.shields.io/github/last-commit/GuruProduction/GURUbyUnusLumen)](https://github.com/GuruProduction/GURUbyUnusLumen/commits)

**GURU is an Android app that ships a Linux shell and 390 tools, so a language model can work in it and act on your phone and your network.**

You bring the model: Ollama on your own hardware, or your own key for Anthropic, OpenAI, Google or xAI. It runs commands, calls tools, reads what comes back and keeps going until the job is done. No root, no adb for normal use, no account. Free, AGPL-3.0, sideloaded APK.

Public beta. One maintainer, a lot of it AI-written, nobody outside has audited it. If all you want is a shell, use Termux.

## What it does

You give the model a job. It picks a tool and calls it. GURU checks the call against the permissions you've granted, runs it and hands the result back. The model reads the result and picks the next call. That repeats until the job's done or it hits something it can't do.

A failed step goes back as the actual error. If a command isn't found, the model can run apt and try again. If a permission is missing, the tool says which one, why it matters and how to grant it.

Termux gives you a shell and you type into it. GURU puts a model on the other end.

<!-- Recorded run goes here: the prompt, every tool call with its arguments, every result, failures included, exported from the app's own log. Link the full logs under /runs. Don't add a "see below" line until this exists. -->

## What's in the box

| Part | What it gives the model |
|---|---|
| **Shell** | A Linux userland in the app's own storage: bash, apt, git, Python 3.14, ffmpeg, sqlite, curl, ssh, tesseract. Built on Termux. |
| **Phone** | Reads and taps the screen through the accessibility service. Notifications and screen capture. |
| **Network** | Finds hosts on your network and controls them: routers, Sonos, Hue, DIAL, UPnP. |
| **Memory** | An encrypted store with a Rust core, kept across sessions. |
| **Self-extension** | When it can't do something it writes a tool or a skill for it and keeps it. |

The tools sit behind a permission layer built on 167 declared Android permissions. Declaring isn't holding, so you grant what you want. The accessibility service, the notification listener and screen capture each have to be switched on by you in Settings. GURU can't do that for itself.

## Where your data goes

Your prompts, the conversation and any image you attach go to the model endpoint you chose and nowhere else. Point it at Ollama on your LAN and none of it leaves your network.

Public traffic goes over Tor by default. LAN and loopback go direct. If Tor has no circuit, GURU waits up to 30 seconds for one, then fails the request. It never falls back to a direct connection on its own. Anything carrying a credential goes over Tor regardless. `/clearnet` overrides the default and `/tor` locks it on.

API keys sit in the Android Keystore, one AES-256-GCM key per service, non-exportable. Memory is encrypted at rest with Argon2id and XChaCha20-Poly1305. With a cloud model, whatever it recalls goes into the prompt and leaves the phone. Cloud backup is off.

There are no analytics SDKs, no advertising ID, no IMEI and no ANDROID_ID. 

Prompts, skills, tool definitions and agent templates come from `api.unuslumen.com`, so a new capability reaches you the day it's built with no APK update. The fetch is a plain GET with no login, no cookie and no install ID, and it goes over Tor. The server is closed source. What it serves isn't secret, because all of it lands on your phone and you can read it in the app. If the server's down GURU still boots, and you write your own prompts.

## Limits

This is a beta and I'm one person. What's weaker than you'd assume:

- **No outside audit.** The encryption and the Tor routing have had my review and nobody else's.
- **No CI.** Tests run on my machine and I check releases on my own phone. About 160 JVM test files, 40 instrumented and 43 Rust files with tests.
- **A lot of the code is AI-written.** Every changed file gets read in full before it's edited. A test only counts if it fails when I put the bug back. A pre-commit gate keeps IPs, MAC addresses, keys and machine paths out of the repo.
- **The feed isn't signed.** Whoever controls the server controls what the model is told. What it can do is still bounded by the permissions you've granted.
- **Builds aren't reproducible.** Check the SHA-256 and the signing key.
- **Cleartext HTTP is allowed app-wide,** because LAN gear speaks plain HTTP. Nothing stops a tool being pointed at an http:// address.
- **It's an ordinary Android app.** Android refuses signature-level and privileged permissions to ordinary apps. A few privileged ones are reachable through Shizuku and the rest can't be granted by anything. The tool says so, instead of sending you to a settings screen that won't work.
- **It's a lot of recent code.** Assume there are bugs I haven't found.

## Install

GURU isn't on the Play Store and won't be. Take the signed APK from [Releases](https://github.com/GuruProduction/GURUbyUnusLumen/releases) and allow unknown sources when Android asks. It's 743 MB because the userland, the Python runtime, the reverse-engineering toolchain and the native binaries are inside it, so nothing gets fetched later. Android 8.0 or later.

Check it before you install. The SHA-256 is on the release page.

```
sha256sum GURU.apk
apksigner verify --print-certs GURU.apk
```

Every release is signed with one key. The certificate SHA-256 should be:

```
3E:58:42:37:B1:41:8E:12:01:C6:A4:ED:7C:07:B5:8A:F7:B7:C5:A1:FB:E2:E6:BE:F7:6B:03:A2:BA:E8:CD:3C
```

Build from source with `cd GURUbeta-FULL && ./gradlew assembleDebug`. You need JDK 17 and the Android SDK. [CONTRIBUTING.md](CONTRIBUTING.md) has the rest.

### Connect a model

First launch asks where your model lives. After that it doesn't ask again.

| Option | Setup |
|---|---|
| **Ollama on your network** | `http://YOUR_LAN_IP:11434`. Free per token and your conversations stay on your hardware. The best option. |
| **Cloud API key** | Anthropic, OpenAI, Google, xAI or any OpenAI-compatible endpoint. |
| **Rented GPU** | A vast.ai server running Ollama behind a Cloudflare tunnel. About 50p an hour. |

No model runs on the phone and nothing goes through Unus Lumen. I don't see your prompts and I don't take a cut of your tokens. A loop burns a lot of tokens, so watch the bill on cloud models. Small models lose the thread on long jobs.

GURU adds no content filter of its own. A cloud provider applies its own rules. A local model does whatever its weights do. The prompts are readable in the app, so check.

## Otio

Otio is the market inside the app. You build an agent, publish it and set a price. Buyers pay once and keep it, even if you pull it later. Unus Lumen runs the market and holds the money. The split is 80% to the maker and 20% plus payment costs to Unus Lumen, and that's what keeps GURU free. It isn't on-chain. You trade under a pseudonym with no sign-up.

The market keeps records on the server: installs, ratings, purchases. Reading the feed doesn't. The agent market is live. Payments aren't switched on yet. The rest of the plan is in [VISION.md](VISION.md).

## Why

I got sick of being data farmed and told no by products that charge extortionate amounts for a worse experience. I wanted full control of my own phone and network with a good model behind it. So I built it.

I'm Steven, Unus Lumen, Bristol. One person, no funding, no team. Break it and tell me what's wrong. Open an issue or send a PR. Security problems go through [SECURITY.md](SECURITY.md), privately.

## Docs

[Vision](VISION.md) · [Architecture](ARCHITECTURE.md) · [Contributing](CONTRIBUTING.md) · [Security](SECURITY.md) · [Privacy policy](PRIVACY_POLICY.md) · [Notice](NOTICE.md) · [A note from Steven](A-NOTE-FROM-STEVEN.md)

## Licence

AGPL-3.0 for the app. The GURU name and branding are trademarks of Unus Lumen Ltd, so a fork renames. [NOTICE.md](NOTICE.md) lists every bundled component and its licence.

[Website](https://www.unuslumen.com) · [YouTube](https://www.youtube.com/@introducingguru) · [X](https://x.com/introducingguru) · [TikTok](https://www.tiktok.com/@introducingguru) · [Instagram](https://www.instagram.com/introducingguru) · [Kick](https://kick.com/introducingguru) · steven@unuslumen.com
