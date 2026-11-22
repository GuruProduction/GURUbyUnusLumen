# Completion report — Cerebrum remediation, the five outstanding items

Branch `lux/cerebrum-remediation`, 37 commits ahead of `origin/main` at `366208c`, draft PR
547. No release, tag, version bump or publication, as instructed. Written 2026-11-22.

## Standing constraints, honoured

The 14-commit implementation was preserved and extended, not restarted or replaced. No
release, tag, application version bump or publication. The prompt architecture is the
API-managed one with persistent local copies, and nothing was invented to replace it.

## Item 1 — no competing hardcoded or offline-fallback prompt behaviour

**Done.** `HiveStructuredFallback` is deleted, along with `bundleOrStructuredFallback` and
the three files that held only empty prompts (`GuruPrompts.kt`, `GuruReflection.kt`,
`HiveMindPrompts.kt`). `HivePromptFetcher` reads only what the API delivered, returns an
empty string when nothing has been delivered, and the lanes that consume it skip when it is
empty rather than substituting text. The server route was rewritten to address the four
real slugs, because it had been querying a shape the database does not hold.

**Evidence.** `HivePromptContractTest` scans the source for the deleted symbols and for the
shipped prompt text; both were reinstated and the scan failed, then passed when removed. A
blank served prompt produces a skipped lane and a log line, not a fallback.

## Item 2 — the encrypted backup and restore round trip

**Done, on a device.** `CerebrumBackupRestoreOnDeviceTest` stores a keeper fact, a record
with a relation, an in-place update that records superseded text, and a deletion that
retires a row. It seals a snapshot, stops the brain, deletes the vault, extracts, installs,
and then boots **with no caller-supplied passphrase**, which only succeeds if the Keystore
envelope is what unseals it. It asserts the facts, the graph connection, the superseded
text and its reason, the deletion revision, that the retired row is not live but is in the
archive listing, and that the restored file is not plaintext.

A defect was found and fixed while proving this: the snapshot read the vault from disk
while the brain held state in memory, so every fact written since the last save was lost
inside a snapshot that verified perfectly. `DatabaseSnapshotEngine` now refuses a snapshot
when no flush is registered and flushes before reading one.

## Item 3 — the history frame is reachable from a production tool

**Done.** `HISTORY` is a frame, `CerebrumBrainApi.history` sends it, `BrainService` and
`MemoryRepository` expose it, and `memoryHistory` is a registered tool the model can call.
The executor deliberately does not use `resolveFactOrError`, because that answers "Fact not
found" for a retired row and the deleted case is the whole reason a trail exists.

**Evidence.** The Rust test reaches history through `handle_frame`, not the handler
directly, so disabling the route fails it. The Kotlin tests cover a revision list, a
deleted row, a non-brain id, and an id with no trail.

## Item 4 — entity edges and consolidation retirement through the shipped library

**Done at two levels.** On the device, consolidation runs through the packaged `.so` and
reports its own steps, and every live memory is asserted to still resolve after a pass.

The device assertion was not enough on its own, because it checked the report decoded and a
report is written by a pass that ran every step and by one that returned immediately. So
the behaviour is now proved in the Rust suite through real frames:

- an entity edge exists between two memories naming the same proper noun, and the specific
  pair is joined rather than just a non-zero count;
- that edge is walkable from the shared name, because an edge that exists and cannot be
  walked passes a count and fails the user;
- the match is case-insensitive on both sides, which is how the pass actually matched and
  what the defect was;
- a memory retired by decay is refused by search, by the live listing, by retrieve-by-id,
  and answers on HISTORY with an `Archived` revision carrying its reason and text, while
  remaining in the archive listing the deduplication pass reads.

Each was proved by restoring the defect first: disabling the entity pass fails three tests,
removing the tree publication fails the retirement test with the tree saying `Active`, and
matching a lowercased word against raw content fails all three entity tests.

## Item 5 — the pipeline, the fail-first record and the intermittent failures

**The pipeline runs, and at the time of writing no CI run has concluded green end to end.**
Four jobs, none of which existed before; `.github` held only `FUNDING.yml`. Fifteen CI runs
so far: ten cancelled because a later push superseded them, four failed on a real defect,
one in flight. Across them it found eight distinct real defects that no macOS build could
see, including that the seccomp filter had never been installed on any build and that the
packaged-APK provenance marker could not be read. The device job went green for the first
time in this project's history: 8 tests, 0 skipped, 0 failed, on an API 34 emulator.

The count of fifteen is the CI workflow only. The repository also has security-scanning
workflow runs that conclude success, and those are not this pipeline.

One failure in that set is environmental and must not be read as a defect in the code. The
device job failed in 35 seconds at configuration time with

    Plugin [id: 'com.google.devtools.ksp', version: '2.3.4'] was not found

while two earlier runs on the same branch, with the same version catalog, resolved KSP and
reached the tests. The stage is the tell: this fails before a module is compiled, so it says
nothing about what is being tested. Nothing was changed in response, because a version
change would have been a guess at a lookup that returned nothing.

**The intermittent failures had real causes, both fixed.** `AgentExecutionOwnershipTest`
used `delay` where the ordering needed establishing, so a loaded suite could assert before
the work it named had run. `CerebrumBackupRestoreOnDeviceTest` failed on one run and passed
the next because a process-global error slot was never cleared, and the heal path reads a
`PassphraseRejected` in it as permission to delete the Keystore alias and the vault: a
stale sentence from an earlier operation destroyed a healthy key. `AgentConversationTranscriptTest`
is recorded as unexplained, because five isolated runs and three full-suite runs found no
reproduction.

**The fail-first record is in `ITEM-5-CI-AND-TEST-RELIABILITY.md` and it names what is
missing.** Eighteen restorations are recorded with the test each proved. Six files are
named where tests were added and never watched to fail, including ten
`AgentExecutionOwnershipTest` cases and all fifteen `AgentMemoryServiceTest` cases. Naming
them is the point: a test nobody has seen fail is an assertion, not evidence.

## Measured state

| what | result |
|---|---|
| Rust workspace | 593 passed, 0 failed, 1 ignored |
| Rust clippy, host and `x86_64-unknown-linux-gnu` | clean with `-D warnings` |
| `cargo fmt --all -- --check` | clean |
| Device job | 8 tests, 0 skipped, 0 failed, on API 34 |
| Native ABIs | all four built, provenance marker asserted in each |
| APK provenance step | passes against a real local build; fails with a named diagnosis when the marker loses its terminator |

## What is not done

Nothing was released or published, by instruction. `AgentConversationTranscriptTest` has no
established cause. The fifteen `AgentMemoryServiceTest` cases and ten
`AgentExecutionOwnershipTest` cases are unproven by restoration. The seccomp filter still
refuses to install, and now for a reason that is about the platform rather than the shape
of the program: every syscall constant is x86_64 numbering, and aarch64 numbers the same
calls differently, so an aarch64 table has to exist before it can run on a phone.
