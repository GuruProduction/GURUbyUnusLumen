# GURU Technical Roadmap

## Codebase at a Glance

220k+ lines and growing. 1,300+ files across Kotlin, Rust, C, and JavaScript

## Architecture

GURU is a multi-module Android application built with Clean Architecture. Every feature module is split into three layers: data (repositories, implementations), domain (models, use cases, repository interfaces), and presentation (screens, view models, components). Dependency injection runs through Koin. The database is Room. The UI is Jetpack Compose.

30 Gradle modules are wired together in settings.gradle:

- app (the main application)
- portal (the core AI and tool system)
- cerebrum (a Rust server with 6 crates)
- core (database, alarm, notification, preferences, UI, util, DI, widget)
- Feature modules: notes, tasks, calendar, journal, Projects, thoughts, settings, adspace, widget

The app module ties everything together. It owns the AndroidManifest with 160+ declared permissions, the native C/C++ code (QuickJS, Python launcher, root shell, exploit runner), the accessibility service, the floating orb, the screen capture service, the heartbeat alarm chain, and the main navigation graph.

The portal module is the brain of the app. It contains the tool system, the memory system, the brain engines, the streaming client, the Tor integration, the Luxify skill framework, the automation and hook and job systems, the prompt repository, the discovery layer, and the slash command system.

The cerebrum is a separate Rust server that runs alongside the Kotlin brain. It has six crates: cerebrum-server (the main server with handler and state), cerebrum-curate, cerebrum-events, cerebrum-policy, cerebrum-graph, and cerebrum-query. This is the high-performance memory and reasoning layer that complements the Kotlin brain engines.

## What's Built

### Tool System (57 tool sets + counting)

The portal module contains 55+ tool sets, each with definitions, executor, results, and registration. The ToolRegistry holds all registrations. The ToolDispatcher receives tool calls from the LLM, resolves names through ToolNameResolver, repairs arguments through ToolArgumentRepair, and dispatches to the correct executor. Results flow back through ToolDispatchResult and ToolExecutionResult.

A security policy layer (ToolSecurityPolicy) gates execution. A file access guard (FileAccessGuard) protects sensitive paths.

The 57 tool sets are:

Alarm, App Management, Approval, Automation, Bookmark, Calendar, Camera, Clipboard, Communication (SMS, calls), Communication Plus, Contacts, Database, Device Control, Email, Encryption, Environment, File, File Processing, File System, Hook, HTTP, Intent, Job, Journal, Keyboard, Location, Luxify, Media, Memory, Meta, Note, Note to Self, Notification, Planning, Process, Productivity, Project, Prompt, Reverse Engineering, Screen, Settings, Shell, Smart Home, Sound, SSH, System, Task, Termux, Theme, Thought, Tool Result, Util, Voice, Web, Web Services, Web View Browser, Widget.

That's 57 categories of action the AI can take on the device. Everything from sending a text message to controlling smart home devices to running shell commands to taking a photo to scanning the local network for devices. More to come.

### Shell Execution and Device Control

The shell layer lives in core/util. ShellExecutor handles command execution. TermuxProvider integrates with Termux for a full Linux environment. BusyboxProvider gives access to standard Unix tools. PythonProvider launches bundled Python 3.14. AdbClient and AdbPairingClient handle wireless ADB connections. SelfSignedCert and Spake2 handle the cryptographic handshake for ADB pairing.

The accessibility service (GuruAccessibilityService) lets GURU interact with the UI of any app on the phone. It can click, scroll, read text, and navigate within any application. This is how GURU drives apps that don't expose a programmatic API.

The notification listener (GuruNotificationListener) reads and manages notifications across all apps. This gives GURU awareness of everything happening on the device.

DeviceControlToolExecutor handles device-level control: brightness, volume, WiFi, Bluetooth, GPS, airplane mode, screen rotation, wallpaper, do-not-disturb, and more.

### Brain System (14 engines)

The brain lives in portal/data/brain. BrainService is the orchestrator. BrainScheduler manages the scheduling cycle. The 14 engines are:

1. EmbeddingEngine: Generates vector embeddings for semantic search. Uses LocalEmbeddingService for on-device embedding.
2. VectorSearchEngine: DVM-based Hamming distance search over pseudo-vectors. Fast, local, no API calls.
3. EpisodicEngine: Manages episodic memory. Stores and retrieves contextual episodes from past interactions.
4. GraphEngine: Knowledge graph. Stores relationships between facts, people, events, and concepts.
5. CurationEngine: Decides what memories are worth keeping. Filters noise from signal.
6. DecayEngine: Memory decay. Old unused memories fade over time, keeping the brain lean.
7. DreamEngine: GURU's dreaming. Processes and consolidates memories during idle periods.
8. PrefetchEngine: Anticipates what context will be needed and loads it ahead of time.
9. QueryEngine: The search interface. Takes a query, returns ranked results from memory.
10. SignatureEngine: Creates signatures of interactions for deduplication and pattern matching.
11. PolicyEngine: Decides what memory operations to perform based on query classification.
12. EventStore: The raw event log. Everything that happens gets recorded here.
13. DecayWorker: WorkManager worker that runs decay on a schedule.
14. DreamWorker: WorkManager worker that runs dream cycles on a schedule.

The memory system also includes a HiveMind layer (HiveMindWorker, HiveMindPrompts) that extracts facts from conversations and stores them as memory. A ContextBuilder assembles relevant memory into the LLM context before each message.

### Cerebrum (Rust Server)

The cerebrum is a Rust server that complements the Kotlin brain. Six crates make up the server:

- cerebrum-server: Main server with handler and state management.
- cerebrum-curate: Curation logic for memory filtering.
- cerebrum-events: Event processing and storage.
- cerebrum-policy: Policy decisions for memory operations.
- cerebrum-graph: Knowledge graph operations.
- cerebrum-query: Query processing and retrieval.

The Rust layer handles the performance-critical memory operations. The Kotlin layer handles the Android-specific integration and the LLM communication. Together they form a two-layer brain: one fast and close to the data, one flexible and close to the user.

### Database

Room database with 30+ DAOs and entities. Current schema version is 23 with migrations from version 15 through 23 tracked in dedicated migration classes.

Entities include: AlarmEntity, BookmarkEntity, ConversationEntity, ConversationThreadEntity, GuruAutomationEntity, GuruDefinedToolEntity, GuruHookEntity, GuruInsightEntity, GuruJobEntity, GuruNoteToSelfEntity, GuruThoughtCycleEntity, HiveMindStateEntity, JobExecutionHistoryEntity, JournalEntryEntity, LuxifyEntity, MemoryCrossReferenceEntity, MemoryEdgeEntity, MemoryEventEntity, MemoryFactEntity, MessageEntity, NoteEntity, NoteFolderEntity, ProjectDocumentEntity, ProjectEntity, ProjectFactEntity, ProjectMessageEntity, PromptAmendmentEntity, PromptSectionEntity, SeenImageEntity, TaskEntity, ToolResultEntity.

This is a comprehensive on-device data layer. Everything GURU knows, remembers, schedules, and manages lives in this database on the user's phone. No cloud storage. No external servers for user data.

### Memory Models

The memory system stores four types of memory: facts (things GURU knows), edges (relationships between facts), events (things that happened), and cross-references (links between memories). The MemoryRepository interface defines the operations. MemoryRepositoryImpl implements it against the Room database.

The LocalEmbeddingService generates embeddings on-device, meaning the semantic search works without sending any data to a server. The DVM search (DvmSearch.kt) implements Hamming distance over pseudo-vectors for fast similarity matching.

### Streaming and LLM Integration

UnusLumenStreamingClient handles all LLM communication. It supports streaming responses, tool calls, and multi-modal messages. The streaming client routes traffic through Tor.

GuruCoreService is a persistent foreground service that keeps the AI alive in the background. It holds the streaming coroutine scope and survives activity lifecycle changes. When the user backgrounds the app, the stream continues. The service displays a persistent notification so Android never kills the process.

AiProvider model in preferences lets the user choose their brain. Local LLM, API key for a cloud provider, rented GPU. Private by design only works if the user wants it to work.

### Tor Integration

TorManager manages the Tor daemon lifecycle. TorEgress routes outbound traffic through the Tor SOCKS proxy. TorPersistentService is a foreground service that keeps Tor alive for the entire device uptime. Android 15+ limits dataSync services to 6 hours, so this uses specialUse to stay alive indefinitely.

All model queries, web searches, and API calls go through Tor. Nobody sees what the user is asking. Nobody sees which model the user is paying for.

### Luxify (Skill Framework)

Luxify is GURU's skill system. LuxifyScanner scans for skill files. LuxifyParser parses skill definitions. LuxifyRegistry holds registered skills. LuxifyRepositoryImpl manages skill persistence and retrieval.

Skills are stored as LuxifyEntity rows in the database. The LuxifyToolSet exposes skill operations to the LLM: list skills, search skills, use skill. The presentation layer includes a SkillsScreen, SkillsViewModel, LuxifyInterviewViewModel for skill creation, SkillDetailSheet, SkillCard, and SkillDocument components.

The AI can discover, load, and execute skills on its own. Skills are prompt injections giving our model it new capabilities on demand.

### Automation, Hooks, and Jobs

Three separate but related systems for autonomous behaviour:

AutomationRepositoryImpl : Automations are multi-step sequences triggered by events or schedules. CreateAutomationUseCase builds them, ExecuteAutomationUseCase runs them. Automations have triggers, steps, and execution results.

HookRepositoryImpl: Hooks are event-driven reactive rules. They fire when specific events occur (notification received, message sent, time of day, etc.) and execute actions. HookEventBus dispatches events. Hooks can be enabled, disabled, created, and deleted.

JobScheduler: Jobs are scheduled tasks with retry logic and execution history. JobExecutionWorker runs them. JobCompressionWorker manages history retention. Jobs track execution history in JobExecutionHistoryEntity.

### Heartbeat System

The heartbeat is GURU's autonomous pulse. HeartbeatScheduler schedules periodic check-ins. HeartbeatAlarmReceiver fires the alarm. HeartbeatRunService executes the heartbeat. HeartbeatPrompts defines what GURU thinks about during each beat.

The heartbeat uses AlarmManager exact alarms for reliable timing, not WorkManager. This ensures GURU wakes up on schedule even when the device is in doze mode. The 30-minute cadence is the current target.

### Ad Space

The adspace module contains the stickman animation ad system. AdSpaceModels defines the data structures: AdPack, AdEntry, AdElement, Keyframe, Pose, AnimationConfig, and various enums for text position, colour mode, rotation mode, transition type, and text animation type.

AdSpaceManager  manages ad display. AdPackCache caches ad packs locally. AnimationConfigSerializer handles serialization of animation configs.

StickmanAdSpace is the rendering engine. It draws stickman animations frame by frame during LLM streaming, showing quirky ads like stickmen drinking Heineken or animations of people at PureGym. Any advert can be placed in this space.

### Discovery Layer

Six discovery managers scan the local network and environment:

- ArpDiscoveryManager: ARP table scanning for IP to MAC resolution.
- BluetoothScanManager: Bluetooth device discovery.
- MdnsDiscoveryManager: mDNS service discovery.
- SsdpDiscoveryManager: SSDP/UPnP device discovery.
- WifiScanManager: WiFi network scanning.
- TcpSocketManager: Raw TCP socket connections.

This gives GURU awareness of every device on the local network. Smart home devices, cast targets, servers, anything with an IP address or a Bluetooth signal.

### Media and Transcription

The media module in the app handles:

- TranscriptionEngine: Vosk-based on-device speech transcription.
- AudioSegmenter: Splits audio into chunks for processing.
- KeyframeExtractor: Extracts key frames from video.
- FileOcrHelper: OCR for images and documents using Tesseract.
- MediaIngestService: Background service for media ingestion.
- MediaLibraryRepository: Manages the device's media library.
- MediaStore, MediaProbe, MediaTypes, MediaSerialization: Supporting infrastructure.
- ScreenSightController: Screen capture and multimodal analysis.
- ImageTurnAssembler: Assembles images into LLM message turns.

The VoiceToolExecutor handles voice recording, conversion, and playback. SpeechRecognition provides speech-to-text. CameraToolExecutor handles camera capture, recording, and motion detection.

### Native Code

Four native entry points compiled via CMake:

1. QuickJS: A full JavaScript engine compiled for Android. Lets GURU execute JavaScript code on-device.
2. Python launcher: Launches the bundled Python 3.14 interpreter.
3. Root shell: Provides root-level shell access when available.
4. Exploit runner: Security research and testing tool.

Bundled assets include: Python 3.14 stdlib, jadx (Java decompiler), apktool (APK analysis), Tesseract language models, toybox (Unix utilities), and standalone binaries for curl, git, sqlite3, and more.

### Prompt System

PromptRepositoryImpl manages prompt sections. The system supports amendments with approval, rejection, and rollback. PromptSectionEntity stores individual sections. PromptAmendmentEntity tracks proposed changes with a history of approvals and rejections.

The AI can propose changes to its own prompt. The user approves or rejects them. Rejected amendments are discarded. Approved amendments become active. Rollback restores previous versions. This is the self-modification channel for GURU's identity and behaviour.

GuruIdentity, GuruCapabilities, GuruBrain, GuruContext, GuruLuxify, and GuruPrompts define the core identity and capability prompts that shape GURU's behaviour. GuruSpinnerVerbs provides the quirky loading verbs displayed during streaming.

### Slash Commands

A slash command system lives in portal/domain/slashcommands. Commands include: Catalogue, Clear, Status, Stop, Tasks, Think, Tools, Usage, Whoami. SlashInvocation handles the routing. ChatHostRouter in the presentation layer dispatches commands from the chat bar.

### UI and Navigation

Jetpack Compose throughout. Navigation graph in the app module. Screen definitions in core/ui include routes for: Lobby, Dashboard, Settings, Notes, Note Details, Note Search, Tasks, Task Details, Calendar, Calendar Event Details, Journal, Journal Details, Journal Search, Journal Chart, Projects, Project Details, Bookmarks, Bookmark Details, Bookmark Search, Import/Export, Integrations, Portal (chat), Memory, Skills, Thought Cycles, Otio Coming Soon.

Home screen widgets for Calendar, Tasks, and Notes using Glance. Quick settings tile for adding tasks. Floating orb chat head for quick access from any app.

App lock with biometric authentication. Theme system with custom handwriting fonts.

### Security

The app declares AGPL-3.0 licensing. Network security config restricts cleartext traffic. Data extraction rules prevent backup. FileAccessGuard protects sensitive file paths from tool access. ToolSecurityPolicy gates which tools can run. Encryption tools provide on-device encryption capabilities. The accessibility service config defines what GURU can do through the accessibility layer.

The ADB layer includes a full ADB client and pairing client with SPAKE2 cryptographic handshake and self-signed certificate generation. This enables wireless debugging integration without external tools.

## What's In Progress

### Heartbeat Cold-Fire Defect
The heartbeat alarm chain is built and ships in the current version, but the first fire after a cold boot has a silent defect. The alarm books correctly and the battery exemption is granted, but the initial execution doesn't produce expected results. This is tracked and being diagnosed.

### Vosk Transcription
The transcription pipeline is built but still failing on real audio clips. The three-stage pipeline (16kHz mono resample, 8s cap, real silence-snapped spans) is in place but transcription results are broken. Root cause is still open. Tracked as GitHub issue #1 on the repo.

### Ottio Marketplace
Navigation includes an OtioComingSoon screen and an ic_otio_placeholder icon. The marketplace is designed but not yet built. This is the next major feature after the current defects are resolved.

## What's Planned

### Phase 1: Stabilise
- Fix the heartbeat cold-fire defect
- Fix Vosk transcription
- Polish the existing tool sets
- Ship a stable release that proves the full chain works end to end

### Phase 2: Ottio
- Build the Ottio marketplace UI and backend
- Data marketplace: users sell their own data on their own terms to vetted buyers
- Creator marketplace: users sell toolkits, skills, masks, agents, games they build
- Commission system: Unus Lumen takes a small percentage of every transaction
- Buyer vetting pipeline: Unus Lumen reviews and approves data purchase requests
- User-set pricing: users decide what their data and creations are worth

### Phase 3: Masks
- Full mask system implementation
- Users change the personality and behaviour of their numen completely
- Mask creation UI, mask switching, mask sharing through Ottio
- Pre-built masks and user-created masks
- Mask validation and safety checks

### Phase 4: Device Protection
- Stop other apps from data farming at the OS level
- Make all search traffic private by default
- Collect data on behalf of the user, stored securely on-device
- User opts in to data collection
- Data ready for sale through Ottio when the user chooses

### Phase 5: Ecosystem
- Every app on the phone becomes a dependency of GURU
- GURU handles messages, emails, calendar, smart home, searches, bookings, finances, creative tools
- UI for everything lives inside GURU
- Only ever need to open one app

### Phase 6: Beyond Android
- iPhone support
- Desktop and mini-desktop support via emulator or native
- CLI tool refresh
- GURU on every platform the user owns

## Technical Principles

- All user data lives on the device. No cloud storage. No external servers for user data.
- All network traffic routes through Tor. No clearnet. No exceptions.
- The brain is whatever the user connects it to. Local LLM, cloud API, rented GPU. User's choice.
- The framework is the product, not the model. GURU is not a language model. It's the framework that drives the device.
- Open source under AGPL-3.0. Anyone can fork, modify, and contribute. The repo is public.
- 160+ Android permissions used through native Android APIs, not through exploits or workarounds. Everything GURU does is achievable through permissions the user grants.
- Clean Architecture throughout. Every feature module has data, domain, and presentation layers. Dependency injection through Koin. Testable, maintainable, extensible.
- The tool system is extensible. New tool sets follow the pattern: definitions, executor, results, registration. The AI gets new capabilities when new tool sets are registered.
- The brain is multi-layered. Kotlin engines for Android integration and LLM communication. Rust cerebrum for performance-critical memory operations. On-device embedding for privacy. Vector search for semantic retrieval.
- The prompt is self-modifying. The AI proposes amendments. The user approves or rejects. Rollback is always available.

**GURU: every line engineered by Steven Newman — Founder, Unus Lumen, Bristol UK.**