# Contributing to GURU

## The Short Version

GURU is AGPL-3.0, open source, and built by one person. I'm asking the community to help make it better, safer, and more private. If you can code, submit a PR. If you can't code, file issues, test features, write docs, spread the word. Everything helps.

## Building GURU

Clone the repo, open GURUbeta-FULL in Android Studio, and build:

```
cd GURUbeta-FULL && ./gradlew assembleDebug 2>&1 | tail -500
```

Min SDK 26 (Android 8.0). Target SDK 35. Compile SDK 37. Kotlin with Jetpack Compose throughout. Koin for dependency injection. Room for the database. Native C/C++ via CMake. Rust cerebrum server in the cerebrum directory.

If the build fails, check that you have the right NDK version (27.0.12077973) and that your local.properties points at a valid Android SDK path.

## Project Structure

30 Gradle modules. Each feature module follows Clean Architecture with three layers:

- data: repositories, implementations, database DAOs
- domain: models, use cases, repository interfaces
- presentation: Compose screens, view models, UI components

The core modules: database, alarm, notification, preferences, UI, util, DI, widget.

The portal module is the AI and tool system. This is where most of the action happens. Tools, brain engines, memory, streaming, Tor, Luxify skills, automation, hooks, jobs, prompts, discovery.

The app module ties everything together and owns the AndroidManifest, the native code, the accessibility service, and the navigation graph.

## Adding a New Tool Set

GURU's tool system is extensible. Every tool set follows the same pattern with four files:

1. ToolDefinitions: The JSON schema definitions that get sent to the LLM. This tells the model what tools exist and what parameters they accept.
2. ToolExecutor: The implementation. This is where the actual work happens. It receives parameters from the LLM and returns results.
3. ToolResults: The result data classes. What the tool returns to the LLM after execution.
4. ToolSet: The registration. This wires the definitions, executor, and results together and registers them with the ToolRegistry.

Look at any existing tool set (e.g. AlarmToolSet, ShellToolSet, CameraToolSet) for the pattern. Copy it, rename it, implement your logic, register it. The ToolRegistry handles the rest.

Follow the naming convention: {Name}ToolDefinitions, {Name}ToolExecutor, {Name}ToolResults, {Name}ToolSet.

## Coding Standards

Kotlin. Compose for all UI. Clean Architecture. No fragments, no XML layouts. Everything is Compose.

Use Koin for dependency injection. Define your modules in the relevant DI file (e.g. AiDataModule, MainPresentationModule). Don't use Hilt or Dagger. Koin only.

Room for database. Every new table needs an entity, a DAO, a migration if the schema version bumps, and registration in the Room database class. Check RoomMigrations.kt for the pattern.

Use coroutines and Flow for async. No RxJava. No callbacks. Coroutines throughout.

Keep the layers clean. Data doesn't know about presentation. Domain doesn't know about data implementations. Presentation talks to domain through use cases and repository interfaces. This is not optional. If you cross a layer, your PR will be rejected.

## Filing Issues

Found a bug? File an issue. Include:

- What happened
- What you expected to happen
- Steps to reproduce
- Device and Android version
- Log output if you have it

Found a security issue? Don't file a public issue. Email steven@unuslumen.com .

## Pull Requests

1. Fork the repo
2. Create a branch named after what you're doing (e.g. fix-heartbeat-cold-fire, add-new-tool-set, improve-tor-stability)
3. Make your changes. Keep commits clean and descriptive.
4. Build and test: `./gradlew assembleDebug 2>&1 | tail -500` must pass
5. Open a PR with a clear description of what you changed and why
6. Reference any related issues

Keep PRs focused. One thing per PR. If you're fixing three things, open three PRs. Makes review faster and rollback cleaner.

## What Needs Help

- Heartbeat cold-fire defect: the alarm chain works but first fire after cold boot has a silent issue
- Vosk transcription: the pipeline is built but transcription is broken on real audio clips
- Device protection: blocking other apps from data farming at the OS level
- Ottio marketplace: the backend, the transaction system, the UI
- Masks: the mask system needs completion before masks can be sold through Ottio
- Privacy hardening: anything that makes GURU more private is welcome
- Documentation: if something isn't clear, fix it or flag it
- Testing: the current test coverage is minimal. Writing tests is a massive help.

## License

By contributing, you agree that your contributions are licensed under AGPL-3.0. Everything in this repo is AGPL. Your PR is AGPL. No exceptions.

## The Spirit

GURU exists because one person was sick of being data farmed, lied to, and told no by AI products that charge extortionate amounts for substandard experiences. The whole point is to hand power back to people. Every contribution, no matter how small, moves that forward.

Don't gatekeep. Don't elitist. Don't be a dick. If someone's PR isn't great, help them improve it. If someone's question seems basic, answer it. The community this builds is the community this becomes.

Category defining tech should be community driven. Open source is the only road now. Let's build something that belongs to everyone.