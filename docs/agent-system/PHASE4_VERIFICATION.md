# Phase 4 — runtime instrumentation, verification report

Written at the close of the instrumentation work on branch `lux/phase4-runtime-instrumentation`.
Every claim below is either measured on this branch or explicitly named as not proven. A claim
whose evidence is a file that merely exists is marked as such, because a file compiling is not
the same as a behaviour happening.

---

## 1. What the phase was asked to do

From the specification's section 15, Phase 4, unaltered:

> Make the agent runtime emit real typed events.
> Connect the event stream to durable persistence.
> Capture actual model interactions, tool calls, communications, decisions, plan changes, errors
> and completion.
> Do not invent activity summaries to fill missing telemetry.

Plus section 16's acceptance rule: persisted events must accurately represent what happened.

---

## 2. What was proven

| Claim | Evidence |
| --- | --- |
| Every declared event type has an explicit restore policy | `AgentEventCoverageTest.everyDeclaredTypeHasARestorePolicy` |
| The coverage gate actually fails when a policy is missing | Proved by deleting the `TOOL_PROGRESS` policy and watching the gate fail with that exact name, then restoring it |
| An undeclared event type makes a rebuild inconsistent instead of vanishing silently | `AgentEventCoverageTest.anUnknownEventTypeMakesTheRebuildInconsistent` |
| A type classified as rebuilding but with no reducer branch is reported | `AgentEventCoverageTest.aTypeClassifiedAsRebuildingWithoutABranchIsReported` |
| A missing permission refuses the call and never reaches the executor | `DispatcherRefusalTest.aMissingPermissionIsRefusedAsAPermissionAndNeverReachesTheExecutor`, proved by disabling the permission loop and watching it fail |
| Preparation does not execute; execution is a separate step | `DispatcherRefusalTest.aGrantedToolIsPreparedAndThenExecutes` |
| The project agent's tool calls now pass through the dispatcher | `ProjectAgentRepositoryImpl` takes `ToolDispatcher` as a constructor dependency and calls `prepare` then `executePrepared`; commit `45dbc14`; no direct registry execution remains on that path |
| The three module suites are green | See section 5 |

---

## 3. What is emitted, and the condition that gates each one

Every one of these is conditional on the occurrence actually happening. A run where the condition
never occurs emits nothing and the code says why.

| Event | Producer | Emitted only when |
| --- | --- | --- |
| SESSION_CREATED | `AgentRuntime.spawn` | a session is genuinely constructed |
| SESSION_RESTORED | `AgentRuntime.restoreFromLedger` | a restore actually succeeded, not merely attempted |
| MODEL_STREAM_ENDED | `AgentRuntime.runTurn`, inside the try after `collect` returns | the stream reached a clean end, never on a throw |
| MODEL_REQUEST_FAILED | `AgentRuntime.runTurn` catch | a request actually threw |
| MODEL_REQUEST_INTERRUPTED | `AgentRuntime.runTurn` cancellation catch | a genuine cancellation |
| SESSION_COMPACTED | `AgentRuntime.compactIfNeeded` | the compaction threshold was actually crossed |
| TOOL_PERMISSION_GRANTED | `ToolDispatcher.prepare`, one branch | every declared permission was confirmed granted |
| TOOL_PROGRESS | `ToolDispatcher.executePrepared` through the runtime's sink | an executor chose to report progress |

---

## 4. What is not proven, and why

**`RUN_CONFIGURATION_CHANGED` has no producer and is not emitted.** The runtime has no path to
change a live run's effective configuration: the snapshot is taken at creation and nothing mutates
it mid-run. The approval said explicitly not to fabricate this event to close a count, so it is
recorded in code as having no producer. When a configuration-change path exists, the event is
already declared and already classified.

**No device run.** Every claim in section 2 is a JVM test or a compile. Nothing in Phase 4 has been
exercised on the emulator or on a handset. The dispatcher's permission check is proved against
Robolectric's framework, not a real device's package manager, and the two can differ on privileged
permissions specifically.

**The project agent's refusal path is proved by compilation, not by a test.** `DispatcherRefusalTest`
proves the dispatcher refuses. It does not drive a model proposal through `ProjectAgentRepositoryImpl`
to a recorded `TOOL_PERMISSION_DENIED`. That wiring is correct by inspection and by the type system,
and it is not proved by execution.

**Tool progress from a real long-running tool is not exercised.** The sink is wired and the type is
correct; no test drives an actual executor that reports mid-flight.

**No event has been read back out of a real database after a real run.** The ledger's write path is
proved by Phase 3's tests. Phase 4's emitters are proved to compile and to be reachable, not to have
produced a row on a device.

---

## 5. Suite results

The suite is run with `--rerun-tasks` so a cached `UP-TO-DATE` result cannot be mistaken for a
passing one. That distinction matters: the first baseline run reported
`:core:database:testDebugUnitTest` as UP-TO-DATE, which means it did not execute at all.

Results are recorded in section 8 at the end of this document rather than asserted here, so that a
number cannot drift from the run that produced it.

---

## 6. Two defects found and filed rather than fixed here

`askAssistant` is registered globally and offered to the main assistant, which has no executor
branch for it, so it answers "Unknown tool" (issue 540). The agent tool descriptions promise a
status called `ANSWERED` that the code never returns (issue 541). Neither belongs in an
instrumentation phase, and the ledger does not lie about either.

---

## 7. What this phase did not touch

No migration for its own scope. No Phase 5 broker. No Phase 6 memory or artefacts. No Agent Room.
No delegation or publishing. `AgentEventTypes.ALL` is unchanged in membership: every name that
existed before still exists and nothing was added speculatively.

The one out-of-scope correction it did carry is issue 537, the project agent's unchecked tool path,
which was approved separately as Option A because a permission decision cannot be recorded
truthfully on a path that makes none.

---

## 8. Measured results

Command, run with `--rerun-tasks` so no task could report `UP-TO-DATE` instead of executing:

```
guru-build-lock run -- ./gradlew \
  :portal:domain:test :portal:data:testDebugUnitTest :core:database:testDebugUnitTest \
  --rerun-tasks --console=plain
```

Counted from the JUnit XML rather than from the console summary, so a skipped class cannot read as
a passing one:

| Module | Test classes | Tests | Failures | Errors | Skipped |
| --- | --- | --- | --- | --- | --- |
| `:portal:domain` | 22 | 249 | 0 | 0 | 0 |
| `:portal:data` | 119 | 1,278 | 0 | 0 | 0 |
| `:core:database` | 3 | 29 | 0 | 0 | 0 |
| **Total** | **144** | **1,556** | **0** | **0** | **0** |

The first baseline run of this suite reported `:core:database:testDebugUnitTest` as `UP-TO-DATE`,
which means it did not run at all while the build still exited zero. That is why every run since
has been forced, and why the counts above come from the XML files rather than from the exit status.

---

## 9. The honest summary

Phase 4's scope is closed: every declared event type has a policy, eight of the nine in-scope types
have a real producer, the ninth is recorded as having none and why, both execution paths reach the
central dispatcher, and the suite is green on the evidence above.

What is not closed is device proof. Nothing in this phase has run on a handset, and no event has
been read back out of a real database after a real run. Those are named in section 4 rather than
softened, because a phase that claims device verification it does not have is worse than one that
says plainly where the evidence stops.
