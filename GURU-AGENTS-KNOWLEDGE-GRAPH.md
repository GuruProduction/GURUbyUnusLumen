# GURU Agent Layer — Knowledge Graph

Reconnaissance for the in-app agent creator, the local agent runtime, and the marketplace that
follows from them. Every claim below was read from disk or probed live on 2026-11-01. Where
something does not exist, it says so plainly rather than inferring it.

Three repositories, one system:

- `/Users/unuslumen/GURU-dev` — the Android app (AGPL, public). Gradle root is `GURUbeta-FULL/`.
- `/Users/unuslumen/GURUbyUnusLumen-Server` — the publisher server, Rust. Closed source.
- `/Users/unuslumen/guru-superadmin-v2` — the reviewer console, React + Vite.
- `/Users/unuslumen/guru-docker-v2` — the compose stack that runs the server, the database, the
  console and the tunnel.

---

## 1. What already exists on the server

The server is further ahead than the app. Read this section before designing anything, because
most of the marketplace backend is already built and live.

### The catalogue tables

`guru-db/src/migrations/0001_initial_schema.sql` defines five content tables with an identical
shape: `prompt_sections`, `skills`, `tools`, `agents`, `canvas_packs`. Each has `id uuid`,
`slug text UNIQUE`, `name`, `version integer`, `source text` (`builtin` or `community`),
`author_label text`, `price_cents bigint`, `is_active boolean`, `created_at`, `updated_at`.

`price_cents` is already present on every one of them with the comment "marketplace rails,
0 = free". The money column exists. Nothing charges anything yet.

### The agents table specifically

Live columns, read from the running Postgres on 2026-11-01:

    id, slug, name, role_description, system_prompt, tool_permissions (jsonb),
    wave (integer), is_builtin (boolean), source, author_label, price_cents,
    is_active, created_at, updated_at

Fourteen columns. Two things missing that matter: there is no `version` column on `agents`, and
there is no `schedule_cron` column even though the `MaskTemplate` model in `guru-content` carries
one. Skills, tools and packs all have versions. Agents do not. That asymmetry is a real defect for
any update story, and it is the single most load-bearing gap in the server schema.

### The public read API, all of it working

`guru-server/src/public.rs` publishes these routes with no auth by design:

    GET /v1/manifest          every content type as slug+version pairs, ETagged by a content-state hash
    GET /v1/prompts           GET /v1/prompts/{slug}
    GET /v1/skills            GET /v1/skills/{slug}
    GET /v1/tools             GET /v1/tools/{slug}
    GET /v1/agents            GET /v1/agents/{slug}
    GET /v1/canvas-packs      GET /v1/canvas-packs/{slug}   and /{slug}/elements
    GET /v1/canvas-media/{element_id}
    GET /v1/config
    GET /v1/builtin-masks     the 21 shipped mask templates served from the binary

Probed live at `https://api.unuslumen.com` on 2026-11-01:

- `/v1/manifest` answers, `generated_at` moves, and the counts are: prompts 87, skills **0**,
  tools 390, agents 21, canvas_packs 1.
- `/v1/agents` returns the 21 specialists in full: `director`, `architect`, `archivist`,
  `code-engineer`, `concierge`, `deep-thinker`, `doctor`, `fixer`, `foreigner`, `forge`, `grafter`,
  `mechanic`, `project-manager`, `research-guru`, `seer`, `self-improvement-engine`, `sentinel`,
  `signal`, `silk`, `vault`, `wingman`. Every one is `source: community`, `wave: 1`,
  `is_active: true`, `price_cents: 0`.
- `/v1/builtin-masks` returns the same 21 from the binary.
- `/v1/skills` returns an empty array even though `skills` is non-empty in the manifest path. That
  contradiction is worth chasing before anyone builds a catalogue UI on top of it.

### The review pipeline, end to end, already running

`guru-server/src/review.rs` is a complete submission and moderation flow.

- `POST /v1/submit/{content_type}` is public and unauthenticated. Allowed types are literals in the
  code: `skill`, `tool`, `agent`, `canvas_pack`. So an agent submission is already accepted today.
- Guards on submission: body capped at 2MB, `slug` must be lowercase ascii words joined by single
  dashes, `name` required, and a recursive PII scan rejects any payload whose keys contain
  `email`, `phone`, `address`, `password`, `account`, `ssn`, `ip_address` or `user_id`. Optional
  `author_label` pseudonym.
- Reviewer routes require a bearer token compared in constant time against `ADMIN_PASSWORD`, with a
  second path accepting browser ByteString encoding so a `£` in the password does not break fetch.
- `GET /v1/review/submissions` lists pending rows without payloads. `GET /v1/review/submissions/{id}`
  returns one in full. `POST .../approve` promotes the payload into the right catalogue table.
  `POST .../reject` nulls the payload and purges staged media from disk in the same action.
- The `submissions` table is `id, content_type, slug, name, payload jsonb, author_label, status,
  review_note, catalog_id, submitted_at, reviewed_at`, indexed on `(status, submitted_at)`.

Live state on 2026-11-01: two submissions ever, both `skill`, one approved and one rejected. Nothing
agent-shaped has been submitted by a member of the public yet.

### Agent promotion is already written

`promote_agent` in `review.rs` takes the submitted payload and writes a catalogue row from `slug`,
`name`, `role_description`, an optional `system_prompt` (required), and `tool_permissions` as JSON.
So the approval path for an agent is not a blank page. It exists and it runs.

### The master write API

`guru-server/src/master.rs` gives us authoring routes behind the same token: `POST /v1/master/agents`,
`PUT /v1/master/agents/{slug}`, `POST /v1/master/agents/{slug}/activate/{active}`,
`DELETE /v1/master/agents/{slug}`, plus the same quartet for prompts, skills, tools, packs, config
and pack elements. `insert_agent` in `guru-db/src/repositories.rs` upserts on slug conflict, so a
republish of the same slug overwrites rather than duplicating.

### Reviewer identity

`review_tokens` is `id, label, token_hash (sha256), is_active, created_at, last_used_at`. Two rows
exist. Tokens are the only authed concept on the server and they store no personal data. This is
the right shape for reviewer accounts and it is already built.

---

## 2. What the reviewer console already does

`guru-superadmin-v2/src/App.tsx` has eleven routes: Overview, Server Status, Prompts, Skills, Tools,
**Agents**, Canvas Packs, Pack Studio, Config, Review Queue, Login.

`pages/ContentEditors.tsx` exports an `AgentsEditor` that lists `/v1/agents`, edits a draft with
tick-box tool permissions, saves through `PUT /v1/master/agents/{slug}` and creates through
`POST /v1/master/agents`, and toggles active state.

`pages/ReviewQueue.tsx` is a working moderation screen: a table of pending submissions with type,
slug, name, author and timestamp, a Review button that opens the full JSON payload, and Approve and
Reject buttons that call the two endpoints and reload. The rejection toast reads "Rejected —
payload purged, staged media wiped."

So our side of the marketplace is largely built. Reviewing an agent is a click in a screen that
already exists. What is missing is not the queue, it is everything on the phone.

---

## 3. What the app has today

### Agent-shaped things that are not agents

- `portal/presentation/.../components/GuruAgentSelector.kt` holds `enum class GuruAgent` with four
  hardcoded entries: `Coder`, `Grafter`, `Wingman`, `Slik`.
- `portal/presentation/.../components/GuruMaskSelector.kt` holds `enum class GuruMask` with the same
  four, differently cased.
- `PortalScreen.kt` imports `GuruMaskSelector` and declares `var selectedGuruMask by rememberSaveable
  { mutableStateOf(GuruMask.Coder) }` and `masksExpanded`, and never renders the selector. The state
  is dead. The four-name enums are a costume, not a feature.
- `ProjectAgentRepositoryImpl` is a per-project chat agent, not a spawnable agent. It builds a system
  prompt from a project's own context, filters tool descriptors by category prefix, streams a reply
  and saves the message to the project. It is used only by `ProjectDetailViewModel`. It never
  registers itself anywhere the main GURU can call it, and it has no definition row.

### The real precedent: the skills lane

The skills lane is the pattern to copy, not to touch. It is built, working, and shaped exactly like
an agent lane should be.

| Concern | File | What it does |
|---|---|---|
| Row shape | `core/database/.../entity/LuxifyEntity.kt` | `id, name, description, when_to_use, allowed_tools, body_markdown, source, enabled, created_at, updated_at` |
| Queries | `core/database/.../dao/LuxifyDao.kt` | by id, by name, by source, enabled-only, counts |
| Writes | `portal/data/.../luxify/LuxifyRepositoryImpl.kt` | create, update, enable, disable, delete, installDynamicSkill, getDynamicSkills |
| Live list | `portal/data/.../luxify/LuxifyRegistry.kt` | in-memory StateFlow with lookup by name and a summary count |
| Bundled scan | `portal/data/.../luxify/LuxifyScanner.kt` | reads `assets/skills/*/SKILL.md`, parses, merges with DB rows, dedupes by name |
| Frontmatter parse | `portal/data/.../luxify/LuxifyParser.kt` | YAML frontmatter to `name, description, when_to_use, allowed-tools` plus body |
| Transport | `portal/data/.../skills/SkillForge.kt` | `/v1/manifest` with If-None-Match, `/v1/skills/{slug}`, base URL overridable by preference |
| Version ledger | `portal/data/.../skills/SkillSyncMarks.kt` | SharedPreferences keys `v_<slug>`, `v_<name>`, `slug_<name>` |
| Force-push and retraction | `portal/data/.../skills/SkillLibrarySyncWorker.kt` | version-gated pull, one live copy per name, withdraws `synced` rows whose slug left the manifest |
| Tool verbs | `portal/data/.../tools/SkillLibraryToolDefinitions.kt` | `browseSkillLibrary`, `searchSkillLibrary`, `getSkillFromLibrary`, `installSkillFromLibrary` |
| Prompt injection | `portal/domain/.../GuruLuxify.kt` | renders the skill listing into the system prompt |
| Screen | `portal/presentation/.../luxify/SkillsScreen.kt` + `SkillsViewModel.kt` | list, toggle, detail sheet, delete for `source == "user"` |

Three source values exist and mean three different things: `bundled` ships in the APK, `user` is
made on the phone and is deletable, `synced` came from the publisher and the sync worker may
overwrite or withdraw it. The retraction valve never touches `user` rows. **That three-way split is
the exact trust boundary an agent lane needs.**

### The runtime that would call an agent

- `portal/data/.../tools/registry/ToolRegistry.kt` is three HashMaps keyed by tool name:
  definitions, executors, extractors, plus a parameter index.
- `ToolRegistration.registerAll` iterates a **manually maintained** list, `GeneratedToolRegistrations`,
  which the comment calls a placeholder for a KSP-generated one. It contains 63 tool sets plus
  `TeamThreeToolDomains.registrations()`. It is not generated by KSP today.
- `ToolDispatcher` checks `definition.permissions` before every call.
- `ToolDefinition` carries `name, description, category, parameters, permissions`, and its docstring
  says it "is what eventually moves to the API".
- `ToolDefinition.permissions` holds Android runtime permission strings and the dispatcher refuses
  the call when one is missing. `registry/permission/GrantCheck.kt` turns a denial into one sentence
  naming what is missing, why it matters and how to grant it, across eight states: GRANTED,
  NOT_GRANTED, PRIVILEGED, NOT_GRANTABLE, INSTALL, ABSENT, UNCATALOGUED, UNKNOWN_SPEC.

Anything that spawns an agent must pass through that dispatcher. That is where its declared tools
stop being a claim and start being enforced.

### Storage that already takes arbitrary definitions

- `GuruDefinedToolEntity` (`guru_defined_tools`) stores `name, displayName, description, parameters,
  implementation, status, created_by, use_count, rationale` and takes a composition, a shell command
  or a webhook. `MetaToolDefinitions` exposes `defineTool`, `getMyTools`, `updateTool`, `deleteTool`,
  `approveTool`, `disableTool`, `enableTool`. So the app already learns new tools at runtime.
- `GuruModuleEntity` and `GuruModuleRevisionEntity` already implement draft/active/retired
  lifecycle, a revision counter, and full snapshot rows for rollback. That is the versioning pattern
  an agent definition wants, already written and already proven in this codebase.
- `GuruJobEntity` stores a schedule type, schedule config, an action and an input, with run counts,
  failure counts and history retention. An agent that runs on a schedule has somewhere to live.

### Database mechanics that will bite

- `guruDatabase` declares 42 entities, `version = 31`, and lives at `guru_db`.
- Migrations are registered by hand in `DatabaseModule.kt`: a single `addMigrations(...)` call
  listing MIGRATION_1_2 through MIGRATION_30_31. A new table means a new `Migration31To32.kt` class,
  one import, one entry in that call, and a bump of the `version` constant. Miss any one of the four
  and the app crashes on first launch after upgrade rather than failing quietly.
- Room validates the schema on open. `Migration30To31.kt` states the rule plainly: column names,
  types, nullability and defaults must be identical to the entity, and its SQL was copied from the
  entity rather than written from memory. Follow that.
- `addMigrations` has no `fallbackToDestructiveMigration`, so a missing migration is a hard crash,
  which is the correct behaviour and must be preserved.

### Navigation mechanics

- Routes are `@Serializable` data objects and classes in `core/ui/.../navigation/Screen.kt`.
- Every route is registered in `GuruApp.kt` as `composable<Screen.X>`; there are 29 of them.
- `LobbyNode.kt` holds a hardcoded `lobbyRooms` list: Notes, Journal, Projects, Calendar, Settings,
  Skills. An Agents room is one added line plus a route plus a composable.
- `LobbyScreen.kt` also has a tile path keyed on strings such as `"static:skills"`.

### Prompt assembly

`PromptRepositoryImpl.getSystemPrompt()` reads a cached prompt string and substitutes `${human}`
with the saved user name. It does not concatenate sections in the app. The prompt is assembled
server-side at `/prompts/assembled` and cached by `PromptFetcher`.

So `GuruLuxify.fullLuxifySection(activeSkills)` exists but nothing calls it. Grep across the whole
app for `fullLuxifySection` returns only its own definition. The skill listing is **not** currently
injected into the system prompt by that function. The live injection is inside the server-assembled
prompt. Do not assume the local renderer is wired.

---

## 4. What is genuinely missing

Listed as gaps, each with what exists and what does not.

1. **No agent table in the app.** No Room entity, no DAO, no repository, no registry. Nothing on the
   phone can store an agent definition.
2. **No agent creator UI.** No screen, no route, no view model, no form. The four-name enums are not
   it.
3. **No spawn verb.** No `spawnAgent` tool, no agent executor, no way for the main GURU to hand a
   job to a named agent and receive a report. `ProjectAgentRepositoryImpl` is a chat surface for one
   project, not a spawnable capability.
4. **No consumer for `/v1/agents`.** Grep for `v1/agents`, `builtin-masks`, `v1/tools`,
   `v1/prompts`, `v1/config` across the whole app returns nothing but a comment in `SkillForge.kt`
   describing the skills routes. The app reads `/v1/manifest` and `/v1/skills/{slug}` and nothing
   else. 21 agents and 390 tools are live and served; the app pulls neither.
5. **No publish path from the phone.** `POST /v1/submit/{content_type}` exists and accepts `agent`,
   but nothing in the app posts to it. There is no submission client, no auth to carry, no UI.
6. **No catalogue or marketplace surface on the website.** `guru-docker/site` holds a Next export of
   the marketing site: index, download, about-the-tec, placeholder, dev, plus assets. There is no
   catalogue page, no search, no per-agent page.
7. **No version on the agents table.** Skills, tools and packs version; agents do not. An update
   story needs it, and `/v1/agents` even hardcodes `1` in its ETag material as a placeholder for it.
8. **No price, ownership or purchase flow anywhere.** The columns are seeded and unused. There is no
   accounts table, no wallet, no entitlement check.
9. **Two dead four-name enums.** `GuruAgent` and `GuruMask` disagree on casing and neither is
   rendered. They will collide with real agent slugs.

---

## 5. The design as agreed in conversation

This section is intent, stated by Steven, not observation. Treat it as the brief, and confirm the
parts marked open before building them.

- Agents are made **inside the app**, in their own navigable screen, through a flow that builds one.
  The person sets the instructions and the tool permissions, saves it, and it is theirs.
- **No cap** on how many a person creates. Their own agents are theirs forever, editable.
- An agent is **something GURU calls**. The main GURU picks the agent whose definition fits the job,
  hands it the job and the context, the agent runs on its own and reports back. It can run in the
  background while conversation continues.
- A **separate chat surface also exists**, so a person can open an agent and talk to it directly.
  Steven confirmed this and corrected an earlier attempt to rule it out.
- Any agent can be **published from the app** to our API. It waits in the queue while we review it
  for maliciousness. Once cleared and published it appears in the **in-app marketplace**, and any
  other user can download it and keep it permanently.
- **Review lives in our API, by us.** The app adds no gate, no extra consent screen, no blocker. The
  downloader sees what permissions an agent carries and decides. This was stated forcefully; do not
  add friction on the device side.
- The **marketplace also gets a web surface** on `unuslumen.com` / `unuslumen.co.uk`, modelled on
  clawhub.ai, reading the same registry the app reads. Steven already owns and runs a consumer site
  there.
- **Masks are a different feature** and must not be confused with this. Steven's words: masks are
  asks GURU wears with enhanced capabilities, external databases and MCPs. Out of scope here.
- **Do not touch the skills UI.** It was named only as a code pattern.

Open, and his to decide: whether a downloaded agent can be edited or forked by the person who
downloaded it; whether an agent may call another agent; whether an author's later revision pushes
automatically to existing installers.

---

## 6. The ClawHub architecture to copy

Read from [How ClawHub works](https://docs.openclaw.ai/clawhub/how-it-works) and the
[CLI reference](https://github.com/openclaw/clawhub/blob/main/docs/cli.md) on 2026-11-01.

- One registry record per entry, keyed by slug, owned by a **publisher entity**. Each record carries
  metadata, permissions, a summary, source attribution, download counts, star counts, a scan state
  and a moderation state. Beneath it, one or more **immutable versions**.
- A version is the definition plus a changelog, categories and topics. First publish defaults to
  1.0.0, later publishes bump the patch unless the author declares the number. A dry-run shows the
  payload before it goes.
- **Ownership transfers** between publishers, so a popular entry cannot be held hostage by whoever
  claimed the slug first.
- **Review is a state machine**: cleared, held, hidden, blocked. A held or blocked entry vanishes
  from public search and install while staying visible to its owner for fixing.
- **Installs record provenance**: which record and which version. Updates therefore fetch the same
  entry rather than trusting a display name, and a version can be pinned.
- Lifecycle: retract a version, undelete it, restore an older one, hide without deleting, rename,
  merge a duplicate into the canonical entry, star, report abuse, and a trusted-publisher flag that
  earns lighter review.
- Auth is a device flow that prints a one-time code and a verification URL, polls for approval, and
  caches the token, with a `--token` path for unattended use.

Mapped onto GURU: the server already has the slug, the publisher-ish `author_label`, the moderation
state in `submissions.status`, and the review queue UI. It lacks versions on agents, ownership as a
first-class entity, provenance on installs, and any notion of install or download counts.

---

## 7. The build, in dependency order

Each step names the files it touches. Nothing here is a plan of record until Steven says build.

**Step 1 — one table, one migration.**
`core/database/.../entity/GuruAgentEntity.kt` with `id, slug, name, description, system_prompt,
tool_permissions, source, enabled, version, created_at, updated_at`. Source values `user`,
`synced`, `bundled`. `Migration31To32.kt` with SQL copied from the entity, then the four registration
edits in `DatabaseModule.kt` and `guruDatabase.kt`. `GuruAgentDao.kt` mirroring `LuxifyDao`.

**Step 2 — repository and registry.**
`portal/data/.../agents/GuruAgentRepositoryImpl.kt` modelled on `LuxifyRepositoryImpl`: create,
update, enable, disable, delete, `installSyncedAgent(...)`, and a hard rule that delete and overwrite
refuse when `source == "user"`. A registry exposing the live list and lookup by name.

**Step 3 — the creator screen.**
A new `Screen.AgentsScreen` route in `Screen.kt`, a `composable<Screen.AgentsScreen>` in `GuruApp.kt`,
a `GuruAgentsScreen.kt` and view model, and one line added to `lobbyRooms` in `LobbyNode.kt`.
Compose only, matching the app's existing screen grammar. Permissions are picked from the real
`ToolDefinition.permissions` values so the tick boxes describe what the dispatcher will actually
enforce.

**Step 4 — the spawn verb.**
`SpawnAgentToolDefinitions.kt` plus an executor, registered in `GeneratedToolRegistrations`. Input:
a slug and a job. Behaviour: resolve the definition, build a prompt from its system prompt, filter
the tool set by its declared permissions, run the loop, return a report. Execution goes through
`ToolDispatcher` so permissions are enforced rather than advertised. Cost control and a recursion
guard are mandatory here, and the loop limit constant already used elsewhere
(`MAX_CONSECUTIVE_TOOL_CALLS`) is the precedent.

**Step 5 — the sync lane.**
`AgentForge.kt` as a sibling of `SkillForge`, reading `/v1/manifest` agents and `/v1/agents/{slug}`.
`AgentSyncMarks.kt` for the version ledger. `AgentLibrarySyncWorker.kt` copying the force-push and
retraction doctrine from `SkillLibrarySyncWorker.kt` exactly, including that retraction may only ever
withdraw `synced` rows. Boot plus six-hour cadence, driven from the same place `AiRepositoryImpl`
starts the skills loop.

**Step 6 — publish from the phone.**
A client for `POST /v1/submit/agent`. Two real decisions before code: what identity a submission
carries, since `review_tokens` are ours and the app has no account, and whether `author_label` is
free text or something the person sets once. The server already enforces the PII scan, the slug
shape and the 2MB cap, so the client's job is only to produce a clean payload and report the
outcome honestly.

**Step 7 — the marketplace screens.**
In-app: a browse/search/install surface reading `/v1/agents`, with install status and update
available per row, exactly as `SkillLibraryToolExecutor.browseLibrary()` already does for skills.
On the web: a catalogue on the consumer site, reading the same public endpoints, with a per-agent
page. One API, both surfaces, one record.

**Step 8 — fix the server asymmetries before they cost us.**
Add a `version` column to `agents` and stop hardcoding `1` in the `/v1/agents` ETag. Decide whether
`schedule_cron` belongs on the table since `MaskTemplate` already carries it. Investigate why
`/v1/skills` returns an empty array while the manifest lists skills.

---

## 8. Traps and gotchas, learned from the code

- A new Room entity without all four registration edits crashes the app on first launch after
  upgrade. There is no destructive fallback and there must not be.
- Copy migration SQL from the entity, never from memory. Room validates column names, types,
  nullability and defaults on open.
- `GeneratedToolRegistrations` is hand-maintained, not KSP-generated, despite what its comment says.
  A new tool set that is not listed there simply does not exist at runtime.
- `fullLuxifySection` is defined and never called. Local prompt injection is not live. Do not build
  on the assumption that it is.
- Everything outbound rides `TorEgress` and fails closed when Tor is down. `SkillForge`,
  `LlmConfigFetcher`, `MetadataConfigFetcher`, `PromptFetcher` and the streaming client all obey it.
  Any new network client must too, or it leaks the user's IP over clearnet.
- Offline is a normal state, not an error. Every existing fetcher returns null and keeps the cached
  copy. `SkillLibrarySyncWorker` reports `offline: true` and states that the installed set is
  unchanged.
- The 21 live agents are all `source: community`, which is the server's default even for built-in
  ones. `is_builtin` is the flag that actually distinguishes them, and it is `false` on every row.
- Two `require_*` token checks exist with different acceptances: `require_reviewer` in `review.rs`
  handles the ByteString `£` case and `require_token` in `master.rs` may not. Check before assuming
  a browser-side call will succeed with the same password.
- RULES.md is the constitution: no root, adb is last resort, nothing built around a problem,
  verified means observed on the device. Verification is JVM tests first, then Robolectric, then the
  emulator, and only one emulator at a time.
- Gradle is serial. One build at a time. Spawned agents write code and never run Gradle.

---

## 9. Live infrastructure, verified

    docker ps
    guru-cloudflared      guru-docker-cloudflared        (legacy tunnel)
    guru-site             guru-docker-site               80/tcp
    guru-waitlist         guru-docker-waitlist           3001/tcp
    guru-cloudflared-v2   guru-docker-v2-cloudflared
    guru-console          guru-docker-v2-guru-console    127.0.0.1:4450->80
    guru-server           guru-docker-v2-guru-server     4400/tcp
    guru-postgres         postgres:17                    5432/tcp

Compose file: `/Users/unuslumen/guru-docker-v2/docker-compose.yml`. It builds `guru-server` from
`/Users/unuslumen/GURUbyUnusLumen-Server` and `guru-console` from
`/Users/unuslumen/guru-superadmin-v2`, keeps everything on an internal bridge network with
`no-new-privileges`, publishes the console to loopback only, and exposes the server to the internet
solely through the Cloudflare tunnel. The database volume is `guru-server-data`; the content volume
is `canvas-content`.

Database `guru_server`, owner `guru_app`, twelve tables:
`_sqlx_migrations, agents, canvas_elements, canvas_packs, config_defaults, prompt_sections,
prompt_versions, review_tokens, skills, studio_assets, submissions, tools`.

Live counts: agents 21, prompts 87, tools 390, canvas packs 1, skills 0 on the public route,
submissions 2 all-time, review tokens 2.

---

## 10. How to tell an agent from a mask, in one line

A mask is a personality GURU wears that changes who is answering and what it can reach. An agent is
a definition GURU calls that goes away, does a job and comes back. The four hardcoded enums in the
app are masks in name and nothing in substance. The marketplace in this document is for agents.
