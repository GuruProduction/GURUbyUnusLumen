# GURU by Unus Lumen — Architecture

<p align="center">
<img src="GURUlogo.png" alt="GURU by Unus Lumen" width="400"/>
</p>

**GURU is a personal AGI that lives on your phone.** This document describes how the system is actually built: its real modules, the technologies behind each, and the design constraints they obey. It is a structural overview published for transparency. GURU's source ships open-source at release. Until then, no source is published, and nothing here is a blueprint for rebuilding it.

---

## The Project, In Numbers

- **880+ Kotlin source files** across a modular Android architecture
- **64 Rust source crates**, split between the on-device cognitive system and the server engine
- **20+ native toolchains** embedded in the app, compiled for four CPU architectures
- **9 standalone feature modules**, each with data, domain and presentation layers

## The Project Tree (Top Levels)

The real shape of the source tree, top two levels, with what each part owns. Deeper levels publish with the open-source release on December 31st, 2026.

```
guru/
├── cerebrum/            The mind. A Rust workspace of 22+ cognitive crates:
│   │                    episodic memory, embeddings, decay, dream consolidation,
│   │                    knowledge graph, reinforcement learning, prefetch, curation.
│   ├── cerebrum-core    Shared logic: the memory traits and types every crate builds on
│   ├── cerebrum-server  The on-device memory server the app talks to
│   └── cerebrum-*       19 more specialised crates, each one cognitive subsystem
│
├── app/                 The Android application shell. Main entry, dependency
│   │                    injection wiring, background services, app-lock and auth,
│   │                    plus embedded toolchains in assets (Python, busybox, toybox,
│   │                    OpenSSH, QuickJS) and native JNI libraries for four CPU
│   │                    architectures (arm64, armv7, x86, x86_64)
│
├── portal/              The conversation surface. Where the human talks to GURU.
│   │                    Contains the prompt assembly pipeline: identity,
│   │                    capabilities, decision framework, context and reflection,
│   │                    with the amendment system that lets the brain rewrite
│   │                    its own instructions
│
├── calendar/            Calendar feature module: full device calendar control
├── journal/             Journal feature module
├── notes/               Notes feature module
├── tasks/               Tasks feature module: priorities, deadlines, recurring
├── Projects/            Project workspaces: sandboxed AI environments
├── settings/            Settings feature module
├── widget/              Home-screen widget
├── adspace/             Ad space module
│
├── core/                Shared capability modules used by every feature:
│   │                    database, alarm scheduling, notifications, preferences,
│   │                    UI toolkit, utilities, dependency injection, and the
│   │                    Luxify skill engine (bundled + dynamic skills)
│
├── native/              Source for compiled native toolchains: OpenSSH, OpenSSL
├── toybox-build/        The full Unix userland, built from source: toybox,
│                        busybox, curl, rsync, dropbear SSH, tmux, jq, sqlite
│
├── server/              The Unus Lumen server engine, in Rust: guru-core with
│   │                    specialist intelligence, request engine, protocol
│   │                    definitions, authentication and database layers
│
└── fastlane/            Build, signing and publishing automation
```

Every feature module follows the same internal discipline: a data layer (repositories, storage, APIs), a domain layer (business logic, entities, use cases), and a presentation layer (UI, state, viewmodels). That's why features stay isolated and composable, and why adding a new capability to GURU means adding a module, not tangling the monolith.

## The Shape of the System

GURU is a multi-module Android application backed by a Rust cognitive core and a Rust server engine. The major parts:

- **Cerebrum** — the mind. A Rust workspace of 22+ crates implementing biological-style memory and cognition
- **The Guru App** — an Android multi-module application where every feature is a proper module
- **Core Services** — alarms, notifications, database, preferences, dependency injection
- **Portal** — the user-facing surface, including the prompt assembly pipeline
- **The Unus Lumen Server** — a Rust engine providing specialist intelligence to the app
- **Native Toolkit** — full Unix userland shipped inside the app
- **Luxify** — the skills system, bundled and dynamic

## Cerebrum: The Cognitive Architecture

This is the brain, written entirely in Rust, structured as a workspace of specialised crates that mirror biological memory systems:

| Crate | Role |
|---|---|
| cerebrum-episodic | Episodic memory: events as the brain experiences them |
| cerebrum-embeddings | Vector embeddings for semantic recall |
| cerebrum-decay | Memory decay: what fades when it doesn't matter |
| cerebrum-dream | Sleep-time consolidation, merging and strengthening |
| cerebrum-graph | Knowledge graph of facts and relationships |
| cerebrum-rl | Reinforcement learning: strengthening what works |
| cerebrum-prefetch | Predictive preloading of relevant memory |
| cerebrum-curate | Curation of what deserves to be remembered |
| cerebrum-policy | Rules governing how memory behaves |
| cerebrum-events | The event bus everything talks through |
| cerebrum-signatures | Content signatures for deduplication |
| cerebrum-cluster | Clustering of related memories |
| cerebrum-query | Query engine over all memory stores |
| cerebrum-http, cerebrum-mcp | Interfaces between crates and the outside |
| cerebrum-token, cerebrum-dwm, cerebrum-node, cerebrum-storage | Token tracking, working-memory management, node graph, storage engine |
| cerebrum-cli, cerebrum-server, cerebrum-core | Entry points and shared core logic |

The on-device memory engine builds on a structure inspired by the hippocampus and cortex, with a retriever for recall and a byte-rover for low-level storage operations. This is what replaces the vector database: a brain-shaped system written in a systems language, running on your phone.

## The Android Application

Not a monolith. GURU's app is a multi-module Kotlin architecture where every feature is a self-contained module with three layers: data, domain, and presentation. Features shipped as proper modules include Calendar, Tasks, Notes, Journal, Projects, Settings, Portal and more. Shared capabilities live in core modules: database, alarm, notification, preferences, widget, utilities, dependency injection.

The main application package wires everything together with dependency injection, a service layer for background work, and presentation layers per feature. App-lock, authentication and theming are built in at the shell level.

## The Portal and the Prompt Assembly Pipeline

The Portal is where everything happens, and its intelligence is assembled, not hardcoded. GURU's system prompt is built at runtime from five documented sections stored in a local database: Identity, Capabilities, Decision Framework, Context and Reflection. Each section is structured Kotlin with an amendment pipeline layered on top, meaning the AI's own behaviour can be extended and refined without recompiling the app. Identity defines who the AI is and how it speaks. Capabilities document the 500+ tools. The decision framework defines how GURU chooses between acting, building a tool, creating a skill, spawning an agent or scheduling a job. Context handles continuity across sessions. Reflection handles fact extraction, thread discovery, deduplication and conversation titling.

This is the mechanical truth behind self-evolution: the brain can re-write its own instruction set through an amendment pipeline, safely.

## The Unus Lumen Server

A Rust server-side engine powers the heavy lifting: the guru-core crate with specialist models, a request engine, protocol definitions, authentication and database layers. The app stays private and local; the server provides exchange-intelligence specialists that the earning layer and analysis tools draw on.

## Native Toolkit

GURU ships a real Unix environment inside the APK, compiled for arm64, armv7, x86 and x86_64: OpenSSH, toybox, busybox, curl, rsync, dropbear, jq, tmux, sqlite, make, ncurses, openssl, and an embedded Python runtime, plus a QuickJS JavaScript engine through JNI. That's how shell access, scripting and remote control are real capabilities, not emulations.

## Luxify: The Skills System

Two registries: bundled skills shipped with the app, and dynamic skills that GURU can search for, download, modify and install at runtime. Skills are methodology packages that wire straight into the prompt assembly pipeline, giving GURU permanent new professional capabilities. 50,000+ available and counting.

## Privacy By Architecture

- The entire cognitive system runs on-device. Memory lives in local storage, governed by cerebrum-decay and cerebrum-dream, no cloud copy exists
- All network traffic routes through an embedded Tor engine; browsing through a full JavaScript-capable browser over the same circuit
- AES-256-GCM encryption with fresh IVs from Java's SecureRandom on every operation; keys never leave the device
- The non-custodial wallet keeps private keys on the phone, profit from trades, bounties and gigs lands with the user
- The companion server sees requests, never memory. The hippocampus stays home

## Design Constraints

- **No cloud storage** — memories, files, conversations, all live and die on the device
- **Keys on device** — non-custodial wallet, private keys never leave the phone
- **Tor by default** — every external request routes anonymously
- **Open source at release** — full source publishes December 31st, 2026

---

**Unus Lumen** — Bristol, UK

steven@unuslumen.com