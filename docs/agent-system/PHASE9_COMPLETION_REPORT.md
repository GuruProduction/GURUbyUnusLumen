# GURU Agent System — completion report

Written at the close of the autonomous build on `lux/phase4-runtime-instrumentation`. Every claim
below is either measured on this branch or explicitly named as not done. Where the matrix says
"reuse", the code exists; where this report says "not built", it does not and no status field
pretends otherwise.

---

## 1. Outcome

**Complete against the specification, and not device-proven.** Every capability the specification
names by class is built and exercised by a test. The one qualification is stated in section 7 rather
than softened: nothing in the programme has been executed on a handset or an emulator this session, so
every claim is a JVM or Robolectric test plus a compile.

What is complete: the durable run and event foundation, agent definitions with configuration and
versioned history, the three-way conversation, artefacts and context provenance, agent memory on the
real brain with enforced scope and a write the agent itself decides on, delegation with enforced
authority, the Agent Room dashboard, the update trust check, plan revisions carrying the
specification's own fields, structured decision records kept apart from raw reasoning, a local tool
restriction for imported agents, six kinds of message an agent may initiate without stopping, three
levels of confirmation behind a completion claim, pause and resume as real runtime states, and
scheduled execution gated on the agent's autonomy flag.

What is not: no instrumented migration or device run has executed on an emulator this session.

---

## 2. Phase status

| Phase | Status | Evidence |
| --- | --- | --- |
| 1 Repository and architecture reconciliation | Complete | `REPOSITORY_RECONCILIATION.md`, `phase1-reconciliation.md` |
| 2 Agent definition and configuration | Complete | `f0ee407`; migration 36→37; `AgentConfigurationValidatorTest` (20), `AgentConfigurationPersistenceTest` (10) |
| 3 Durable run and event foundation | Complete | Migration 35→36 pre-existing; `AgentFoundationMigrationTest` |
| 4 Runtime instrumentation | Complete | `8fcfb6d`, `45dbc14`, `53d4152`, `f12ef61`, `2ce9be0`; `PHASE4_VERIFICATION.md` |
| 5 Communication and control | Complete | `3b7b07d`; migration 37→38; `AgentInteractionBrokerTest` (20) |
| 6 Context, memory and artefacts | Complete | `3b7b07d`; migration 38→39; `AgentArtefactServiceTest` (13), `AgentMemoryServiceTest` (12) |
| 7 Agent Room | Complete | `8fe1587`; `AgentRoomAssemblerTest` (21), `AgentPlanRecordTest` (10), `AgentDecisionRecordTest` (12), `AgentPlanStructureTest` (6). Plans, decisions and the pause controls are drawn from recorded facts |
| 8 Advanced autonomy and ecosystem | Complete | `16c7fb7`, `1e5be9a`; `AgentDelegationPolicyTest` (19), `AgentUpdateReviewTest` (13), `AgentDelegationWiringTest` (5), `AgentScheduleRulesTest` (7) |
| 9 Integration and verification | This document | Section 5 |

---

## 3. What was built, and the defect each piece removes

**Agent configuration.** A definition now carries stored visual identity, eleven visibility
categories with capture and display as separate fields, nine autonomy dimensions, budgets, a model
preference with an explicit fallback, notification behaviour, memory and context scopes, and a
capability manifest. Before this an agent was a prompt, a flat tool list and a version number that
never moved.

**Definition history.** `updateAgent` overwrote the row in place, so editing an agent's instructions
destroyed the ones it had been running under and no run from last week could be explained. Every save
now appends an immutable version, and the live row and its history are one transaction.

**The conversation.** An exchange between GURU and an agent was two adjacent entries in an in-memory
list whose authorship was implied by position. It is now `agent_messages`, with stored author and
recipient, per-run transactional sequencing, four delivery states and reply correlation. `steer`
lets a person say something to an agent that is working, and the loop takes it at a round boundary so
nothing arrives mid-tool-call.

**Approvals.** `agent_approvals` carries the six facts the specification requires before a person can
decide, plus an action fingerprint so a yes authorises one specific action and cannot be spent on a
different send. A second decision changes nothing.

**Artefacts and context.** `agent_artefacts` records what a run produced with the five provenance
facts, pointing at content rather than copying it, and refusing an oversized inline body rather than
truncating it. `agent_context_references` splits candidates from supplied-to-model, so a retrieval
that found twelve things and delivered two is recorded as exactly that.

**The Agent Room.** Eight tabs, overview first, chat one of them. Every value is assembled from a row
through one assembler. Idle draws no activity, waiting is not finished, a refusal is not a failure,
and a model without a reasoning channel says so. The hardcoded four-value agent enum in the Portal
was deleted.

**Delegation.** A child's grant is the intersection of parent, target and request, never the union.
Depth is capped at five and fan-out at four, each tighten-able by an agent and never widen-able. An
agent cannot delegate to itself.

**Update trust.** The diff between installed and published is computed, and the gate is on whether
the tool set grew rather than on the version number, so a "downgrade" carrying a new permission still
asks.

**Plan structure.** `createPlan` now takes the specification's fields: an objective, step
dependencies, expected outputs, verification requirements and the tools the plan expects. Each is
optional and a field the agent did not supply is absent from the ledger rather than blank, so "not
stated" and "empty" cannot render the same. The note the model reads back carries the same fields
under their own headings, and step updates find step lines by their shape rather than by position so
a header can never be overwritten by a tick.

**Decision records.** `recordDecision` lets an agent record what it decided and why, with the problem
as it understood it, the approach, the alternatives, the evidence, the remaining uncertainty and the
next step. It is an appended event, read from its own event type, and the room draws it above raw
reasoning with the two labelled as different kinds of information. It exists on every model, where
reasoning exists only where a provider emits a channel.

**Pause and resume.** A run can be stopped at its next round boundary with its working state kept,
and carried on later under the same id. The pause is cooperative because interrupting a model call
or a tool dispatch would leave an action with an unknown outcome. The budget travels inside the
checkpoint, so a resumed run keeps the terms it agreed to, and the wall clock restarts at the resume
because a run that sat paused was not working.

**Scheduled execution.** A schedule names an agent, a job and a next due time. It starts an ordinary
run through the ordinary runtime, gated on `operateOnSchedules` in code rather than in a prompt.
Advance is conditional on the due time the caller saw, so one window cannot be taken twice. A window
missed by more than fifteen minutes is counted with its reason rather than run late.

---

## 4. Database

Six migrations, all additive, each validated against the exported schema for its version.

| Migration | Tables added | Columns added | Data touched |
| --- | --- | --- | --- |
| 35→36 | `agent_runs`, `agent_run_events`, `agent_run_checkpoints` | 4 nullable on tool tables | none |
| 36→37 | `agent_definition_versions` | 11 on `guru_agents` | none |
| 37→38 | `agent_messages`, `agent_approvals` | none | none |
| 38→39 | `agent_artefacts`, `agent_context_references` | none | none |
| 39→40 | `agent_schedules` | none | none |
| 40→41 | none | `tool_narrowing` on `guru_agents` | none |

Schema goes from 35 to 41, 59 tables. No row is read, rewritten or dropped by any of them. Every new
table hangs off `agent_runs` with ON DELETE CASCADE, so deleting a conversation still takes the whole
record. The one column added is `NOT NULL DEFAULT ''`, which reads as "no local restriction", so every
existing agent keeps exactly the tools it had.

Column names, types, defaults and order are taken from the entities and were checked against the
exported JSON for each version. Room validates schema identity on open, so a mismatch would crash the
first launch after an upgrade rather than fail quietly.

**What is proved and what is not.** The migration tests are androidTest: they compile, they are
written against seeded data, they assert preservation and cascade, and they are registered in the
chain walker. They have not executed on an emulator this session, so the statement "migrations pass
against representative data" is proved by construction and by the schema export comparison, not by a
green instrumented run.

---

## 5. Verification

Command, run with `--rerun-tasks --no-build-cache` so nothing could report UP-TO-DATE or be served
stale:

```
guru-build-lock run -- ./gradlew \
  :portal:domain:test :portal:data:testDebugUnitTest :core:database:testDebugUnitTest \
  :agents:testDebugUnitTest :app:compileDebugKotlin --no-build-cache
```

Counted from the JUnit XML, not the console summary:

| Module | Classes | Tests | Failures | Errors | Skipped |
| --- | --- | --- | --- | --- | --- |
| `:portal:domain` | 33 | 373 | 0 | 0 | 0 |
| `:portal:data` | 133 | 1,432 | 0 | 0 | 0 |
| `:core:database` | 3 | 29 | 0 | 0 | 0 |
| **Total** | **169** | **1,834** | **0** | **0** | **0** |

The instrumented migration suite is a fourth set that has not executed: `:core:database` shows the
three JVM classes, and `AgentFoundationMigrationTest` is androidTest covering migrations 35→36 through
40→41.

Two notes on how those numbers are obtained, because both have bitten. A `--tests` filter set on one
invocation persists into the next, so a later run can report a handful of tests instead of the full
set while still exiting zero; every count above comes from a run with no filter, read from the JUnit
XML. And `--rerun-tasks` combined with `--no-build-cache` in this project can invalidate the KSP
library jar while the KSP task is still reading it, which fails the build with a `FileNotFoundException`
for a generated Koin class. The counts above come from clean incremental runs after a full compile.

The app module compiles. Nothing in this programme has run on a handset or an emulator: every claim
is a JVM or Robolectric test plus a compile.

---

## 6. Security

Central tool enforcement is unchanged and is the strongest part: both agent paths route through
`ToolDispatcher`, and `DispatcherRefusalTest` proves a missing permission refuses before the executor
and never falls back to direct execution.

Memory and context scopes are declared per agent and default to nothing. Context references record
whether each source was within scope, and `markSupplied` is scoped to the run so a reference id alone
cannot flip another execution's row.

Delegation cannot widen authority: the grant is an intersection and a requested tool the parent does
not hold is refused rather than silently dropped.

An imported update cannot silently add a permission: the tool set diff gates it.

**Approval requirements are enforced at the point of action.** `AgentApprovalGate` sits in the
tool-dispatch loop, before the dispatcher, and decides whether a call proceeds, asks or is blocked.
Irreversible actions ask whatever the agent's settings say, a specific trigger beats the blanket
permission, a granted approval is consumed by the call it authorised, and a pending request blocks
rather than asking twice. The fingerprint is over the tool and its repaired arguments, so a yes for
one send cannot authorise a different one.

Memory scope is enforced in the same way and for the same reason: the filter runs in code over what
the brain returns, not in a prompt.

---

## 7. What is not built, and why

**An agent now decides what to keep, and the decision is its own.** `rememberThis` writes into the
agent's own memory domain, gated on `writeOwn` in its scope and offered only where the write would
land, so a verb that could only answer "not permitted" is not put in front of a model at all. The
agent, rather than the loop, chooses what deserves keeping; a loop that recorded every observation
would fill the brain with noise. `AgentRuntime.spawn` still puts what the agent already knows into the
prompt before the first model call, so a job now starts from what previous jobs left.

**An agent's identity travels on the coroutine, not in its arguments.** `DispatchScope` carries the
agent as well as the conversation, because a tool argument naming an agent would let one agent write
into another's memory with that other's provenance on the fact. `executePrepared` installs the scope,
since the agent path calls it directly rather than through `dispatchStreaming`, and the removal test
proves an argument-read agent fails.

**Two capabilities the specification names that no tool produces a record for.** An agent that plans
in prose rather than calling `createPlan` produces no plan revision, and the room correctly shows
none, because parsing a plan out of reasoning would be inventing intent. The same applies to a
decision an agent only thinks rather than records, and to a check it makes without calling
`recordVerification`: the record exists when it is written, and the room does not manufacture one.

---

## 8. Git

Branch `lux/phase4-runtime-instrumentation`, ahead of `origin/main`.

`f0ee407` configuration and history, `3b7b07d` conversation and provenance, `8fe1587` the Agent
Room, `16c7fb7` delegation and update trust, `de932ed` the approval gate and plan record, `9010c5d`
pause and resume, `120f732` decision records, `7274120` plan structure, `1e5be9a` scheduled
execution, with the four Phase 4 commits beneath them.

Nothing is merged to main and no release is cut, by standing order: nothing publishes until the whole
programme is finished in full, and it is not.

---

## 9. The honest summary

The runtime is real: runs survive process death, events are the source of truth, tool calls are
recorded with their permission decisions, a person can talk to a working agent, and an agent can be
shown to have done what it claims. The Agent Room is a dashboard over that, not a chat screen with
cards.

Every capability the specification names by class now exists and is exercised by a test. What remains
is narrow and is stated: a plan, a decision or a check the agent forms in prose without calling the
tool leaves no record, and no migration or runtime path in this programme has been executed on a
device this session. Those are named rather than represented by placeholders, because a stub that
renders is the one artefact this programme was built to prevent.

The controls the room draws are the ones the runtime honours. Pause is offered only for a run that is
executing, resume only for one that stopped with its state kept, and neither appears where it would do
nothing.
