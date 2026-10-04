# GURU

GURU is a numen. Your own personal guardian that lives on your phone, drives it for you, and has your back. Not an assistant. Not a chatbot. An entity that picks up your intent and carries it through to completion on your hardware, your terms.

You say "I'm going to bed" and GURU turns off your lights, puts the telly on, adjusts the heating, locks the doors, sets the alarm, and puts your phone on do-not-disturb. You get time back. Multiply that by everything you do in a day.

## What GURU Does

GURU drives your Android phone through natural language. 57 tool sets give it control over everything on your device: messages, calls, calendar, camera, smart home, shell commands, file system, contacts, email, notifications, web browsing, location, SSH, and more. 160+ Android permissions, all through native APIs, all user-toggleable.

It routes all traffic through Tor. No clearnet. No exceptions. Nobody sees what you ask, what you search, or which model you're paying for.

The brain is whatever you connect it to. Local LLM for maximum privacy. Cloud API key if you trust the provider. Rented GPU for cheap unlimited usage. GURU is a framework, not a language model. Private by design only works if you want it to work.

## What's Built

220k+ lines across Kotlin, Rust, C, and JavaScript. One developer, ten months, actively building.

57 tool sets. 14 brain engines. A Rust cerebrum server with 6 crates. On-device vector search with local embedding. A knowledge graph. Episodic memory with decay and dreaming. A full memory system that lives on your phone. A persistent foreground service that keeps the AI alive. A heartbeat system for autonomous operation. An ad space module for non-intrusive spinner ads during streaming. A skill framework called Luxify. Automation, hooks, and jobs. A prompt amendment system where the AI proposes changes and you approve or reject them.

## What It Becomes

GURU will simply use every other app on your phone as a dependency. You only ever need to open one app. Inside that ecosystem, users create. They build toolkits, skills, masks, agents, games. They sell them through Ottio, our marketplace. They sell their own data on their own terms to vetted buyers. They trade and prosper. Unus Lumen takes a small cut of every transaction.

The app is free. Everything in it is free. Unus Lumen profits when users prosper. Not before. Not instead. When.

## Read More

- [Vision](VISION.md) - The philosophy, the numen concept, what GURU is and why it exists
- [Technical Roadmap](TECHNICAL_ROADMAP.md) - What's built, what's in progress, what's planned, the full architecture
- [Business Model](BUSINESS_MODEL.md) - The revenue streams, Ottio, the creator economy, how a free app earns money
- [Contributing](CONTRIBUTING.md) - How to build, how to add tools, how to submit PRs, what needs help

## Build

```
cd GURUbeta-FULL && ./gradlew assembleDebug 2>&1 | tail -500
```

Min SDK 26. Target SDK 35. Compile SDK 37. Kotlin + Jetpack Compose. Koin DI. Room database. Native C/C++ via CMake. Rust cerebrum in the cerebrum directory.

## License

AGPL-3.0. Fully FOSS. The repo is public. The app is free. There is no moat. The value is the engineering.

## The Spirit

I was sick of being data farmed, lied to, and told no by AI products that charge extortionate amounts for substandard experiences. I wanted ultimate control of my own life backed by superintelligence with total privacy. I spent nine months building it. Now I'm asking the community to help make it better, safer, and more private.

Category defining tech should be community driven. Open source is the only road now. I've shown that this level of capability, control, and privacy on an ordinary phone is possible. Now help me build it.