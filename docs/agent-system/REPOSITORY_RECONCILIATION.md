# GURU Agent System — repository reconciliation

Measured on branch `lux/phase4-runtime-instrumentation` at `2ce9be0`, which sits on
`origin/main` `6ff8c94` (3.79, 103) via `0f4dee9` (3.83, 107). Every classification
below cites a file, a class, a table or a command's output. Nothing here is inferred
from a name.

The purpose of this document is to establish, before any further implementation, what
already satisfies the GURU Agent System specification and what does not. It is the
Phase 1 deliverable the plan requires and the input to the traceability matrix.

---

## 1. Repository baseline

| Fact | Value |
| --- | --- |
| Shared checkout | `<HOME>/GURU-dev`, on `main` at `6ff8c94` |
| Work branch | `lux/phase4-runtime-instrumentation` in `<HOME>/<WORKTREES>/phase4` |
| Branch head | `2ce9be0`, five commits ahead of `origin/main` |
| Database | Room, `guru_db`, schema version 36 |
| Migrations | 1 to 36, hand-registered in `DatabaseModule.kt` |
| Schema JSONs | `core/database/schemas/com.unuslumen.app.database.guruDatabase/{4..36}.json` |
| Build module root | `GURUbeta-FULL` (gradlew lives there, never at the repo root) |
| Existing uncommitted work in the shared checkout | a deleted `embedding_gemma_no_normalize_q8.tflite`, modified `LlmConfigFetcher.kt`, an untracked `LlmConfigCacheTest.kt`, and 30 untracked screenshots |

The unrelated working-tree changes in the shared checkout are preserved and untouched.
All work in this programme happens in the phase4 worktree.

---

## 2. Required architectural entities

The specification's section 1.1 lists fifteen entities. Each is classified against the
repository as it stands.

| Entity | Status | Evidence |
| --- | --- | --- |
| Agent Definition | **Extend** | `GuruAgentEntity` in `guru_agents`; eleven fields, no visibility, autonomy, manifest or definition history |
| Agent Configuration | **Build** | No configuration service. `updateAgent` overwrites in place and does not bump `version` |
| Agent Run | **Reuse** | `AgentRunEntity` in `agent_runs`, migration 35 to 36, immutable requested and effective snapshots, lease columns |
| Agent Session | **Reuse** | In-memory `AgentRuntime.Session` plus `AgentRunCheckpointEntity` for durable restore |
| Agent Event | **Reuse** | `AgentRunEventEntity` in `agent_run_events`, append-only, per-run unique sequence, idempotency key |
| Agent Message | **Build** | No message entity. `MESSAGE_CREATED` is declared with no producer. Phase 5 |
| Agent Plan | **Build** | No plan entity. The only structured plan is a note row titled `[PLAN] ` written by the planning tools, with no step identity and no history |
| Tool Invocation | **Extend** | Dispatcher already records `tool_results` and `tool_observations`; run-scoped invocation events exist; no single invocation row keyed by run |
| Approval Record | **Build** | No approval entity, no approve or reject event, no pending-approval state. Phase 5 |
| Agent Memory | **Build** | Cerebrum is rich for the main chat; `AgentRuntime` has no memory lane at all. Phase 6 |
| Context Reference | **Build** | `memory_injections` records main-chat provenance; nothing records what an agent was given. Phase 6 |
| Agent Artefact | **Build** | `media_library`, `project_documents`, `notes` and `project_facts` exist; nothing links any to an agent or run. Phase 6 |
| Session Checkpoint | **Reuse** | `AgentRunCheckpointEntity`, sequence-bounded, written before restore is possible |
| Delegation Relationship | **Extend** | `agent_runs.parent_run_id` exists with a self-referencing foreign key; no policy, depth or authority scoping. Phase 8 |

---

## 3. Existing execution path, traced

### 3.1 The spawn path

`AgentToolExecutor.spawnAgent` resolves the agent by slug then name through
`GuruAgentRepository.resolveAgent`. `AgentRuntime.spawn` then resolves the wiring from
device preferences, builds the allowed tool set from `agent.toolPermissions` filtered
against `AgentRuntime.NEVER_SPAWNABLE` and the registry, constructs a session, and runs
a turn.

Each round builds a chat prompt from the session's messages, streams it through either
`UnusLumenStreamingClient` or a koog executor depending on the provider, accumulates
text and tool calls, and then executes every proposed tool through
`ToolDispatcher.dispatch`. The loop ends when the model answers without proposing a
tool, or when it calls `askAssistant`, or when it throws.

### 3.2 The project agent path

`ProjectAgentRepositoryImpl` runs a project-scoped agent. It previously executed tools
by looking them up in the registry and calling `execute` directly, which bypassed the
dispatcher entirely. Commit `45dbc14` routed it through `ToolDispatcher.prepare` and
`executePrepared`, with the dispatcher as a constructor dependency and no fallback to
direct execution. That closed issue 537.

### 3.3 What survives a process restart

A run survives. `AgentRunEntity` carries the assignment, both configuration snapshots,
the resolved system prompt, the tool manifest, the model identity and the lease. Events
survive in `agent_run_events`. The cognitive state survives in
`AgentRunCheckpointEntity`.

An in-memory `AgentRuntime.Session` does not survive, and is not supposed to: it is the
live representation of a run whose durable truth is the run row and its ledger.
`AgentRecoveryWorker` scans non-terminal runs whose lease owner is gone and whose
heartbeat has expired, and marks them interrupted rather than failed.

### 3.4 Memory tools, by their real names

The memory subsystem is Rust `cerebrum` over JNI through `CerebrumHost.executeFrame`,
surfaced by `CerebrumBrainApi` (storeRecord, upsert, query, curate, retrieve, search,
graphTraverse, consolidate, list) and `BrainService`. Tables are `memory_facts`,
`memory_edges`, `memory_events`, `memory_cross_references` and `memory_injections`.

`AgentRuntime` calls none of it. An agent receives its system prompt, the job string and
its own message history. Phase 6 connects this, and it must use these real names rather
than inventing a parallel memory API.

### 3.5 Security enforcement, traced

Every agent tool call reaches `ToolDispatcher`, which resolves the name, looks up the
definition, checks every declared Android permission, runs `ToolSecurityPolicy.check`,
repairs the arguments, and only then calls the executor through
`executeWithProgress`. A tool outside the agent's declaration is refused before the
dispatcher. The dispatcher is the single door on both paths after `45dbc14`.

The remaining bypass routes are capability authoring rather than execution: an agent
holding `createAgent`, `editAgent` or `publishAgent` can author, but `NEVER_SPAWNABLE`
prevents it spawning or deleting. Whether authoring should also be refused to agents is
a Phase 8 policy question, recorded here rather than decided.

### 3.6 Portal and Agent Room today

`GuruAgentSelector.kt` holds a four-value enum rendered as chips, which the
specification forbids in section 12 and which the phase1 reconciliation already
recorded as a defect to replace with the live registry.

`AgentsScreen.kt` is registry-driven and is the creator and list. There is no Agent
Room, no presence node, no perimeter treatment, no run history and no dashboard. No
file named `AgentRoom` or `AgentDashboard` exists anywhere in the tree.

---

## 4. Database as it stands

Schema version 36. The agent substrate is three tables from migration 35 to 36:

- `agent_runs` — durable execution identity, immutable snapshots, materialised status,
  lease.
- `agent_run_events` — append-only ledger, unique `(run_id, sequence_number)`, unique
  `(run_id, idempotency_key)`, cascade delete with the run.
- `agent_run_checkpoints` — sequence-bounded recoverable working state.

`guru_agents` is unchanged from migration 31 to 32 and holds the flat definition.

The migration framework is Room with hand-written `Migration` objects registered in
`DatabaseModule.kt`, additive `CREATE TABLE IF NOT EXISTS` statements whose column order
mirrors the entity exactly. `core/database/src/test` contains
`AgentFoundationMigrationTest` exercising the 35 to 36 hop against real representative
data. No `MigrationTestHelper` usage exists for the older hops, which is a known gap for
Phase 9 rather than for this phase.

---

## 5. Android process lifecycle, as it actually is

No WorkManager and no foreground service owns agent execution. Heartbeat work fires on
AlarmManager. `AgentRunHost` and `AgentRecoveryWorker` exist; the recovery worker
interrupts runs whose owner is gone.

The specification's section 11 requires that a run survive process death and be
recoverable. That is satisfied by persisted state plus the recovery scan, which is the
honest ceiling on a phone: an app process that Android kills cannot keep executing.
Phase 8's "scheduled and event-triggered execution" is where a foreground service or
WorkManager would be added, and it is not claimed today.

---

## 6. Tests and build

The build lock is `guru-build-lock`. Gradle runs from `GURUbeta-FULL` only, one build at
a time, never from an agent.

Baseline suite for this programme is `:portal:domain:test`,
`:portal:data:testDebugUnitTest` and `:core:database:testDebugUnitTest`. The measured
result of that run is recorded in `PHASE4_VERIFICATION.md` rather than restated here,
because a count copied into two documents is a count that drifts.

---

## 7. What this reconciliation changes about the plan

Three measured facts alter the ordering the plan assumed.

**Phase 4 is nearly complete and Phase 2 is entirely absent.** The plan lists them in
dependency order with Phase 2 earlier, but Phase 3 and Phase 4 were built first. That
means the run substrate exists while the definition model it snapshots is still flat.
Phase 2 must therefore be built as an additive extension of `guru_agents` that the
existing snapshots can continue to represent honestly, not as a replacement of the run
model.

**Four message events belong to Phase 5 and have no producer.** They are declared and
classified as later-phase in `AgentEventTypes.RESTORE_POLICY`. Phase 5 gives them their
producer and their reducer branches.

**The Agent Room cannot be built before Phase 5.** Both Phase 5 and Phase 7 read the same
ledger, and the room's Chat with Agent area is a message surface. Building the screen
first would produce exactly the mock-data dashboard the specification forbids.
