# GURU Agent System — architecture decisions

Decisions taken during this programme, with the context that forced them, the
alternatives rejected and the evidence in the repository. Entries are append-only in
spirit: a decision that later evidence overturns gets a superseding entry rather than a
rewrite, so the reasoning trail stays readable.

---

## AD-001 — Phase 2 extends `guru_agents` rather than replacing it

**Date:** 2026-11-20
**Context:** The specification requires versioned definitions, per-agent visibility,
autonomy policy, a capability manifest and separate requested and effective
configuration. `GuruAgentEntity` is a flat eleven-field row. Phase 3 and Phase 4 were
already built on top of it, and `agent_runs` snapshots the definition as values at
execution time.
**Decision:** Add columns and a companion version table to `guru_agents`, defaulting
every new column so existing rows stay valid, and keep `AgentRunEntity`'s snapshots as
the authoritative record of what a run actually used. Do not move the run model.
**Rejected:** A new `agent_definitions` table with `guru_agents` demoted to a pointer.
Rejected because it would require rewriting every snapshot producer and every read path
for no behavioural gain, and because a run's history must outlive the definition row
regardless of where that row lives.
**Consequence:** Existing agents continue to load with transparent defaults. A run from
before this change still reads correctly because its snapshot is values, not a join.
**Evidence:** `GuruAgentEntity`, `AgentRunEntity.agentDefinitionSnapshotJson`.

---

## AD-002 — Definition history is a version table, not an event stream

**Date:** 2026-11-20
**Context:** `updateAgent` overwrites the row in place without bumping `version`, so no
prior prompt or tool set survives an edit. The specification requires that historical
definitions and runs remain interpretable after later updates.
**Decision:** `agent_definition_versions` holds an immutable copy of the definition at
each save, keyed by agent identity and an increasing version number. A save writes the
new version and updates the live row in one transaction.
**Rejected:** Reusing `agent_run_events` to record definition changes. Rejected because a
definition change is not a facts-about-a-run event, and mixing the two would make a run's
ledger depend on edits to a different object.
**Consequence:** A definition edit becomes recoverable and inspectable. Storage grows with
edits rather than with runs, which is the cheaper axis.
**Evidence:** `GuruAgentRepositoryImpl.updateAgent`, which currently does not bump version.

---

## AD-003 — Capture and display are separate fields from the first version

**Date:** 2026-11-20
**Context:** The specification's section 6.3 requires that a display change not delete
history, and that a capture change not retroactively populate it. The first proposal
reduced visibility to an OFF/FULL switch and was rejected.
**Decision:** `VisibilityRule` carries `capture`, `display` and `detail` independently,
for each of eleven categories. Capture defaults on; display defaults on; detail defaults
to full. Turning display off never stops the record.
**Rejected:** A single `reasoningVisibility` string per agent, which is what exists today
as a hardcoded `"FULL"` written into every run.
**Consequence:** The room can hide a category without the ledger losing it, and can later
show what was captured while display was off. A category with capture off records that
fact so a future reader cannot mistake absence for nothing having happened.
**Evidence:** `AgentVisibility.kt`; `AgentRunEntity.reasoningCaptureEnabledAtStart`.

---

## AD-004 — The Agent Room is built after Phase 5, not before it

**Date:** 2026-11-20
**Context:** The specification calls the room a dashboard and requires its Chat with Agent
area to be a real conversation. Phase 5 builds the message model. Phase 7 builds the
screen.
**Decision:** Phase 7 depends on Phase 5. No room screen is built on placeholder message
data.
**Rejected:** Building the dashboard shell first with stub content, on the grounds that a
visible surface makes progress legible. Rejected because the specification explicitly
forbids a dashboard whose controls do not correspond to real runtime capability, and
because a stub surface is the exact artefact that would be shipped by accident.
**Consequence:** The visible milestone arrives later. It arrives working.
**Evidence:** `AgentEventTypes.RESTORE_POLICY` classifies the four message types as
later-phase because no producer exists.

---

## AD-005 — `RUN_CONFIGURATION_CHANGED` stays unemitted

**Date:** 2026-11-20
**Context:** The type is declared. The runtime has no path to change a live run's
effective configuration: the snapshot is taken at creation and nothing mutates it
mid-run.
**Decision:** Do not emit it. Record in code that it has no producer today, and revisit
when a configuration-change path exists.
**Rejected:** Emitting it on an agent-definition edit. Rejected because editing defaults
changes the next run's snapshot and nothing about this run, so emitting here would
describe a change that did not happen to that execution.
**Consequence:** The event count is honest rather than complete-looking.
**Evidence:** `AgentEventTypes.RESTORE_POLICY`, `PHASE4_VERIFICATION.md`.

---

## AD-006 — Tool permission telemetry rides the dispatcher, never a second source

**Date:** 2026-11-20
**Context:** Phase 4 needed `TOOL_PERMISSION_GRANTED`. Two candidates existed: emit from
the project agent after it dispatched, or emit from inside the dispatcher.
**Decision:** The dispatcher emits. `ToolTelemetry` is a per-call parameter on `prepare`,
not a constructor field on the singleton, so no run's telemetry leaks into another's.
**Rejected:** Emitting from each caller. Rejected because two emitters produce two
opinions about one decision, and the second one is a lie the moment the two paths differ.
**Consequence:** One decision produces exactly one event, on both execution paths.
**Evidence:** `ToolDispatcher.prepare`, `AgentRunToolTelemetry`, commit `2ce9be0`.

---

## AD-007 — Migration safety: additive, tested against real prior data

**Date:** 2026-11-20
**Context:** The plan authorises schema changes but requires preservation of existing data
and verified upgrades.
**Decision:** Every migration in this programme is additive: new tables, new columns with
defaults, new indexes. No drops, no rewrites, no backfills that lose a value. `agent_runs`
and `agent_run_events` were built this way in 35 to 36. Column order mirrors the entity
exactly because Room validates schema identity on open and a mismatch crashes the first
launch after upgrade.
**Rejected:** Recreating `guru_agents` with the new shape and copying rows across.
Rejected because a table rebuild is the one operation that can lose data to an
interruption, and every new column here has a correct default that costs nothing to apply
in place.
**Consequence:** An upgrading install keeps its agents, its runs and its conversations.
**Evidence:** `Migration35To36.kt`, `AgentFoundationMigrationTest`.

---

## AD-008 — Delegation depth and authority are policy, not schema

**Date:** 2026-11-20
**Context:** `agent_runs.parent_run_id` already exists with a self-referencing foreign
key. The specification requires scoped authority, depth limits and failure propagation.
**Decision:** Keep one run table. Depth, authority and resource limits are resolved by a
policy layer at spawn time and recorded in each child run's requested and effective
snapshots. No separate delegation table.
**Rejected:** A `delegations` table holding parent and child with its own authority
columns. Rejected because a delegated run is a run, and giving it a second identity would
mean two places to ask what happened.
**Consequence:** A delegation chain is one indexed query on `parent_run_id`, and every
child's authority is provable from its own snapshot.
**Evidence:** `AgentRunEntity.parentRunId`.

---

## AD-009 — Concurrency is represented truthfully, not simulated

**Date:** 2026-11-20
**Context:** The runtime executes one run at a time. The specification requires the data
model to support concurrency without assuming it exists today, and the interface to
reflect the actual model.
**Decision:** The schema is concurrency-capable from the start (independent run ids, events
bound to runs, per-run leases). The runtime enforces a configurable maximum of one active
run initially. The room shows what is actually running and never implies two agents are
working when one is.
**Rejected:** Parallel execution now. Rejected because a single device serving one model
connection cannot honestly run several cognitive loops at once, and pretending otherwise
would produce states the recovery scan cannot distinguish.
**Consequence:** Raising the limit later is a policy constant, not a migration.
**Evidence:** `AgentRunEntity` lease columns, `AgentRecoveryCoordinator`.

---

## AD-010 — The specification's own approval clause is superseded by Steven's instruction

**Date:** 2026-11-20
**Context:** The specification states in several places that it authorises no code or
database change and that a persistence proposal must be shown for review before a
migration runs. Steven has since given a direct instruction to implement the whole system
autonomously, including schema design and migrations, without pausing for approval.
**Decision:** The later instruction governs. Database work proceeds under the safety rules
in the plan: inspect first, preserve data, additive migrations, tested upgrades.
**Rejected:** Treating the document's earlier wording as still binding and stopping at
each schema change. Rejected because it would contradict the instruction in force and
because the safety obligations are satisfied by the engineering, not by a pause.
**Consequence:** Work proceeds; every migration is still proved against real prior data
before it is called done.
**Evidence:** The `/goal` instruction of 2026-11-20; `GURU_AGENT_AUTONOMOUS_IMPLEMENTATION_PLAN.md`
sections 0.2 and 0.5.
