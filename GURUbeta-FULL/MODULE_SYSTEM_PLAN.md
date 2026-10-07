# GURU Module Vessel & Automation Observatory — Build Plan

Owner: Steven Newman. Builder: Lux, hands-on-keyboard, no subagent coders.
Gates: bug workflow (recon ✅ → this plan → approval → build → verify). Debug APK to Steven's phone; NO release chain (version bump, sign, checksum, GitHub Releases) until Steven tests the UX on the device and is happy.

## Context

GURU today does the on-demand half of "automate your entire digital life": say something, the numen wakes, fires tools. The session doctrine (saved to memory as `guru_module_vessel_doctrine`) extends the core self-evolution promise one layer up: the app's UI itself should grow, per user, per instance. GURU composes its own rooms through conversation, saves them persistently, and they appear as lobby doors. The first room is the Automation Observatory: see automations live as they run, their reasoning, their full traced history, categorised by the shape of the user's life. Nobody else has this because nobody else has a mind behind the dashboard.

Recon (full-file reads, complete 2026-10-06) established: automations exist end-to-end but executions are NOT persisted (returned in-memory, only runCount bumped) — the Observatory's data floor is genuine new work. The AI-designed-UI mechanism already exists: PortalCanvas/PortalWebView render GURU-authored HTML/CSS/JS in a WebView with a bidirectional bridge (PortalBridge.sendEvent native←JS, evaluateJavascript native→JS), theme injected as CSS vars, fonts from guru_fonts, script tags in portal content are executed. Skills (luxify) are markdown bodies with allowed-tools validated against the real registry. Room DB at version 24, migrations hand-written in a clean established pattern.

Doctrine law the skills carry:
- No "failed" status on screen. Statuses: completed / self-healed / needs-you. Healing is ON DISPLAY (a core selling point).
- Money rooms: verify-before-retry semantics designed in from day one.
- Time-given-back stat surfaced front page.
- Categories = the user's life in their own words (Home, Money, Comms), never plumbing (MANUAL/SCHEDULED/EVENT).
- UI is a projection, never source of truth. View-rooms read sovereign stores (automations, runs, reasoning, money) through tools, never shadow them. Own-rooms keep their data in their own persistent space.
- Rooms are living things the numen tends over time, not compose-once.
- Rollback verb: every composition persists as history so rollback is nearly free.
- It's all prompt architecture: tools + skills teach the numen HOW to build/decorate/persist rooms and talk to the phone live. We do not tell the LLM what to do.

## Phase 0 — Mandatory full reads before any edit
- portal/data/.../automation: GuruAutomationDao.kt, GuruJobDao.kt, JobExecutionHistoryDao.kt, GuruHookDao.kt, GuruDefinedToolDao.kt
- portal/data/.../di/AiDataModule.kt (Koin bindings for new repos/DAOs)
- portal/data/.../luxify/LuxifyParser.kt, LuxifyRegistry.kt
- portal/presentation/.../components/ChatToHtml.kt, ChatTextConfig.kt
- gradle files touched: portal/data/build.gradle.kts, app/build.gradle.kts, core/database/build.gradle.kts, portal/domain/build.gradle.kts, portal/presentation/build.gradle.kts
- Every file under "Files modified" gets a full Read immediately before its first edit. House law, no exceptions.

## Phase 1 — Data floor: migrations 24→25
New entities + DAOs in core/database (entity + dao packages), Migration24To25.kt following the Migration23To24 pattern exactly:
1. `GuruModuleEntity` (guru_modules): id, name (kebab, unique), displayName, description, category (user-worded), compositionHtml/Css/Js TEXT, dataJson TEXT (own persistent data space), iconPath TEXT?, revision INTEGER, status TEXT (draft/active/retired), sortOrder INTEGER, source TEXT, timestamps. Indexes: name, status, sortOrder.
2. `GuruModuleRevisionEntity` (guru_module_revisions): id, moduleId, revision, compositionHtml/Css/Js, dataJson, createdAt. Full snapshot per save → rollback restores snapshot.
3. `GuruAutomationRunEntity` (guru_automation_runs): id, automationId, automationName, trigger TEXT (manual/job/hook path), startedAt, durationMs, status TEXT (running/completed/self_healed/needs_attention), stepsTraceJson TEXT (per-step index, tool, params, RESULT, error, durationMs), createdAt. Indexes: automationId, startedAt, status.
4. `GuruTileOrderEntity` (guru_tile_order): id (tile key: "module:<id>" or "static:<key>"), sortOrder INTEGER, updatedAt. THE unified order store — static AND dynamic tiles share one persisted order so the whole lobby drag-drops as one surface.
DAOs: GuruModuleDao, GuruModuleRevisionDao, GuruAutomationRunDao, GuruTileOrderDao (CRUD + Flow queries, GuruJobDao patterns).
Registered in guruDatabase (@Database v25), migrations list in DatabaseModule, single { } exposures added.

## Phase 2 — Trace persistence + event flow (ALL automation paths)
- Domain models: GuruAutomationRun, AutomationStepTrace (kotlinx.serialization, AutomationToolResults style).
- `AutomationRunRepository` interface (portal/domain) + Impl (portal/data, @Single): startRun, traceStep, completeRun(status,duration), getRunsForAutomationFlow, getRecentRunsFlow, getRun, retentionPrune(runCount/cutOff).
- `AutomationRepositoryImpl.executeAutomation` gains the trace hook: run row on start, step traces as each executes, finalise status. Existing per-step try/catch writes error into trace; status becomes needs_attention with error captured. Single choke point: jobs execute via executeAutomationByName (JobRepositoryImpl) and hooks via executeAction → same repository path (HookRepositoryImpl). One hook, ALL paths traced.
- Retention (uncut #2): every run trace write enforces a per-automation rolling cap (default latest 200 runs, constant not config in v1) pruned in the same transaction batch; job/hook-sourced runs identical treatment. Nothing grows unbounded.

## Phase 3 — Module tools (real code, ToolRegistration pattern)
portal/data/tools/: ModuleToolResults.kt, ModuleToolDefinitions.kt, ModuleToolExecutor.kt. Verbs: createModule, listModules, getModule, renameModule, saveModuleComposition (bumps revision + snapshots to revisions), saveModuleData, getModuleData, rollbackModule(toRevision), registerModule (door appears), retireModule, setModuleIcon, reorderModules(orderedIds).
Delete (uncut #3): `deleteModule` hard-deletes the vessel ONLY with explicit confirm param the numen must relay to the human (owner approval, conversational); purges module row + revisions + dataJson by moduleId. Runs table survives (sovereign history of automations, not of rooms) unless the module IS an automation's room — runs belong to automations regardless of rooms.
ModuleRepository interface + Impl (@Single) wrapping DAOs; Koin in AiDataModule; use cases per verb following CreateAutomationUseCase shape where the flow needs them.

## Phase 4 — Icon capability
`IconToolDefinitions/IconToolExecutor`: downloadIcon (Tor-routed via TorEgress.httpClient — existing sovereign egress, fails closed), importIconFromMedia, listSavedIcons. Saved under filesDir/guru_module_icons/. Modules reference by iconPath; lobby/ModuleCanvas render from local path, bundled default fallback.
Format/size validation (common web formats, size cap, dimensions) so a 40MB raw file can't become a door.

## Phase 5 — Render: ModuleCanvas + Screen + FULL lobby drag-drop
- `ModuleScreen(moduleId)` route in core/ui Screen.kt, composable in GuruApp.kt NavHost (slide transitions, existing pattern).
- `ModuleCanvas.kt` (portal/presentation/components): full-screen WebView from PortalCanvas patterns — theme CSS vars, custom fonts, bidirectional bridge, script execution, setComposition/clear commands. Composes from ModuleRepository Flow keyed by moduleId. Broken-room recovery (uncut #5): composition loads in a guarded try/catch; a rendering error (bad HTML/JS from the numen) surfaces an honest on-screen "This room needs attention — ask me to fix it" state feeding a portal event back to the conversation, where the doctrine's repair loop re-composes from the last good revision. Nothing stays broken silently; the screen self-heals the same way everything else does.
- `ModuleViewModel` (Koin): composition state, data state, runs flow for observatory rooms, bridge-event → tool routing (module-scoped), drag state exposure.
- Lobby (FULL surface, uncut #1): LobbyScreen grid reads guru_tile_order Flow + modules Flow + static tile manifest; every tile — static and grown — drags. Press-and-hold enters reorder mode (home-screen feel: lift, shake-settle, drop-to-reorder), persisting unified sortOrder via reorderModules/tool-backed repository call on drop. GURU reorders by voice through the same reorderModules tool — user and numen share one order store. New module doors join at the top by default, per Steven's answer, before static tiles when no order exists yet.
- LobbyViewModel: + module flow, + tile order flow.

## Phase 6 — Doctrine skills (the product lives here)
Bundled in portal/data/src/main/assets/skills/:
1. `module-forge/SKILL.md` — how to build a room: createModule vessel, composition in GURU's design language (portal aesthetic; theme CSS vars; chart.js/mermaid/katex available in canvas; icons from icon tooling), saveModuleComposition persistence, human-approval register step, tending (living things), rollback/redesign, live phone interaction via bridge + tools. Includes: projection law, no-failure language, user-worded categories, time-given-back stat, verify-before-retry money design, RETENTION AWARENESS (rooms query recent runs, prune display sensibly — deep history available via getRun), trace-privacy discipline (uncut #4: never render credentials, keys, one-time codes or message bodies verbatim in compositions — params render as tool names + human summaries; full params exist in chat where they belong).
2. `icon-smith/SKILL.md` — how the numen finds/sources/imports/icons for doors + decoration, honouring theme and format rules.
Headers in luxify SKILL.md format; bundled scanner auto-discovers (LuxifyScanner confirmed). First person, luxify-standard methodology depth.
CRON honesty (uncut #6 surfaced in recon, here is where it lands): module-forge teaches the numen that GURU's cron scheduling today resolves expressions loosely (next-day-same-time semantics in JobRepositoryImpl) so Observatory rooms must surface "runs on schedule" plainly from lastRunAt/nextRunAt truth rather than promising exact cron times; a hard-coded proper cron parser is logged in DEVnotes as future engineering, not this build.
3. Exam iteration protocol (uncut #7): the doctrine carries an explicit calibration loop — compose → render on phone → if ugly or wrong, Steven names what's off → doctrine amended → re-compose. Phase 7 is structured as up to 5 doctrine-revision cycles; if GURU produces a room Steven is unhappy with after cycle 5, we regroup in conversation rather than pushing a release.

## Phase 7 — Verification & the exam (gates everything)
1. Full reads before edits (Phase 0 + per-file law).
2. `./gradlew assembleDebug` from GURUbeta-FULL root, clean tail.
3. DB migration safety (uncut #6): Room v24→v25 hand-written migration executed against the REAL app upgrade path in an emulator first — install current build (v24 schema), populate automations/jobs rows, upgrade, confirm Room opens and old rows intact — THEN Steven's phone, existing data preserved, zero data loss, before the exam even starts.
4. On-device exam: create automation in chat → run → Observatory traces it live; GURU composes the first Automations room through conversation (module-forge doctrine); door appears top of lobby with chosen icon; press-hold drag-drop reorder across static+dynamic tiles persists; numen reorders by voice; numen re-saves composition → room updates live; rollback to prior revision works; a deliberately broken composition shows the needs-attention surface and heals via conversation; a needs-you run shows human-framed state; no "failed" wording anywhere on screen; retention prune confirmed on bulk runs.
5. GURU re-composes Observatory until Steven is happy (≤5 cycles per exam protocol, then regroup conversation).
6. Steven's UX verdict gates the release chain (build.gradle.kts version check → bump → assembleRelease → sign → checksum → GitHub Releases → changelog).

## Files modified (summary)
New: GuruModuleEntity/Dao, GuruModuleRevisionEntity/Dao, GuruAutomationRunEntity/Dao, GuruTileOrderEntity/Dao, Migration24To25, run/trace models, AutomationRunRepository(+impl), ModuleRepository(+impl), ModuleToolResults/Definitions/Executor, IconToolDefinitions/Executor, ModuleCanvas.kt, ModuleViewModel.kt, module-forge SKILL.md, icon-smith SKILL.md, use cases.
Edited: GuruDatabase.kt (v25), DatabaseModule.kt, AiDataModule.kt, ToolRegistration.kt, AutomationRepositoryImpl.kt (trace hook), Screen.kt, GuruApp.kt, LobbyScreen.kt, LobbyViewModel.kt, DEVnotes.md (cron honesty note).

## Previously "out of scope" — all 7 restored into scope above
1. Full lobby static+dynamic unified drag-drop — Phase 5 (guru_tile_order).
2. Trace retention — Phase 2 (rolling 200-run prune, all paths).
3. Module deletion lifecycle — Phase 3 (deleteModule w/ conversational owner confirm; runs survive as sovereign).
4. Observatory trace privacy — Phase 6 doctrine (no credentials/bodies verbatim in rooms) + params-as-summary discipline.
5. Broken-room recovery loop — Phase 5 (guarded render, needs-attention surface, doctrine repair cycle).
6. DB migration hardening — Phase 7 step 3 (emulator upgrade test before phone; cron stub honestly documented in doctrine + DEVnotes).
7. Exam iteration protocol — Phase 6 item 3 (≤5 cycles then regroup).