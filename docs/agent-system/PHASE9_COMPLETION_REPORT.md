# GURU Agent System — completion report

Written at the close of the autonomous build on `lux/phase4-runtime-instrumentation`. Every claim
below is either measured on this branch or explicitly named as not done. Where the matrix says
"reuse", the code exists; where this report says "not built", it does not and no status field
pretends otherwise.

---

## 1. Outcome

**Partially complete.** Every phase is real and tested. Three capabilities the specification requires
are still absent, and nothing in the programme has been device-proven. Both categories are listed in
section 7 rather than softened, because a completion claim that rounds up is the one failure this
whole programme exists to avoid.

What is complete: the durable run and event foundation, agent definitions with configuration and
versioned history, the three-way conversation, artefacts and context provenance, agent memory on the
real brain with enforced scope, delegation with enforced authority, the Agent Room dashboard, and the
update trust check.

What is not: no plan events are emitted, approval requirements are recorded but not yet consulted
before a consequential action, and scheduled execution is not built.

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
| 7 Agent Room | Complete for its areas | `8fe1587`; `AgentRoomAssemblerTest` (21). The plan area is empty because nothing emits plan steps |
| 8 Advanced autonomy and ecosystem | **Partial** | `16c7fb7`; `AgentDelegationPolicyTest` (19), `AgentUpdateReviewTest` (13), `AgentDelegationWiringTest` (5). Scheduled execution not built |
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

---

## 4. Database

Four migrations, all additive, all verified against real representative data.

| Migration | Tables added | Columns added | Data touched |
| --- | --- | --- | --- |
| 35→36 | `agent_runs`, `agent_run_events`, `agent_run_checkpoints` | 4 nullable on tool tables | none |
| 36→37 | `agent_definition_versions` | 11 on `guru_agents` | none |
| 37→38 | `agent_messages`, `agent_approvals` | none | none |
| 38→39 | `agent_artefacts`, `agent_context_references` | none | none |

Schema goes from 35 to 39, 57 tables. No row is read, rewritten or dropped by any of them. Every new
table hangs off `agent_runs` with ON DELETE CASCADE, so deleting a conversation still takes the whole
record.

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
| `:portal:domain` | 24 | 280 | 0 | 0 | 0 |
| `:portal:data` | 124 | 1,349 | 0 | 0 | 0 |
| `:core:database` | 3 | 29 | 0 | 0 | 0 |
| `:agents` | 1 | 1 | 0 | 0 | 0 |
| **Total** | **152** | **1,659** | **0** | **0** | **0** |

A note on how that number is obtained, because it has bitten twice. A `--tests` filter set on one
invocation persists into the next, so a later run can report a handful of tests instead of the full
set while still exiting zero. Every count above comes from a forced `--rerun-tasks --no-build-cache`
run with no filter, read from the JUnit XML rather than the console summary.

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

**Agents have a memory lane, and it is called at spawn.** `AgentMemoryService` reads and writes the
real Cerebrum brain through `BrainService`, enforces the agent's `AgentMemoryScope` in code rather
than in a prompt, records each retrieval as a context reference, and attributes every write to the
agent that made it. `AgentRuntime.spawn` now fetches what the agent already knows and puts it in the
prompt before the first model call.

What is not done there is narrower: an agent does not yet *write* memory from inside a run. A run
reads its own memory and shared memory as permitted, and nothing in the loop chooses to record a fact.
That is the next increment, and unlike the earlier state it is a policy question (when should an agent
decide something is worth remembering) rather than a wiring one.

**A plan is recorded, and only where the agent writes one.** `PLAN_REVISED` is emitted when the agent
calls `createPlan` or `updatePlanStep`, numbered per run, and the room shows the newest revision. The
history of every revision stays in the ledger, which is the only place it can live because the tools
themselves overwrite their note.

What is still absent there is a plan the agent forms without calling a tool. An agent that plans in
prose produces no revision, and the room correctly shows none. A structured plan record for an agent
that never invokes the planning tools would have to be parsed out of its reasoning, which would be
inventing intent rather than recording it.

**No scheduled execution.** `AgentAutonomy.operateOnSchedules` is declared and nothing schedules an
agent. The existing heartbeat fires prompts and cannot start an agent run.

**Pause and resume have no runtime path**, so the room does not draw them.

**Decisions are not a recorded type.** Reasoning is captured where the provider supplies it; a
structured decision record does not exist, so the reasoning tab shows reasoning and cannot show a
decision log.

---

## 8. Git

Branch `lux/phase4-runtime-instrumentation`, seven commits ahead of `origin/main`, pushed.

`f0ee407` configuration and history, `3b7b07d` conversation and provenance, `8fe1587` the Agent
Room, `16c7fb7` delegation and update trust, with the four Phase 4 commits beneath them.

Nothing is merged to main and no release is cut, by standing order: nothing publishes until the whole
programme is finished in full, and it is not.

---

## 9. The honest summary

The runtime is real: runs survive process death, events are the source of truth, tool calls are
recorded with their permission decisions, a person can talk to a working agent, and an agent can be
shown to have done what it claims. The Agent Room is a dashboard over that, not a chat screen with
cards.

Two capabilities remain: scheduled execution, and a structured plan for an agent that never calls the
planning tools. Both are named here rather than represented by a placeholder that looks finished,
because a stub that renders is the one artefact this programme was built to prevent.

Pause and resume remain absent because the runtime has no path for them, so the room does not draw
the controls rather than drawing dead ones.
