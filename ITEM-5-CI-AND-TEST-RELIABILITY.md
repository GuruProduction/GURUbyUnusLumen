# Item 5 — remote CI and test reliability

Written 2026-11-22 on branch `lux/cerebrum-remediation`. This is the honest record of
three questions: whether the pipeline runs, which new tests were not fail-first
verified, and what the intermittent failures actually were.

## 1. The pipeline, and what it has found

The workflow is `.github/workflows/ci.yml` with four jobs. It did not exist before this
work; `.github` held only `FUNDING.yml`, so nothing checked a pull request. Every defect
below shipped through that silence.

The pipeline now builds and proves:

| job | what it establishes |
|---|---|
| `rust` | format, clippy with `-D warnings`, whole workspace, all tests, on Linux |
| `android-native` | all four ABIs built, and each `.so` carries the build-revision marker |
| `android-app` | debug APK, full unit suite, the snapshot tests named explicitly, and the APK's packaged brain marker matched against the commit |
| `android-device` | the packaged `.so` executed on an API 34 emulator: memory round trip, backup/restore round trip, consolidation |

**What it found, that no macOS build could.** Nine runs, seven distinct real defects:

1. `BPF_LD`, `BPF_W`, `BPF_ABS` used but never defined, and `libc` not declared. The whole
   seccomp module was `cfg(target_os = "linux")`, so macOS never parsed it.
2. `CARGO_TARGET_$(...)_LINKER=value cargo build` is not an assignment. Bash parses the
   command word before expanding it, so the line ran a command named
   `CARGO_TARGET_..._LINKER` and exited 127.
3. The `HardeningError` fields were `i64`; `prctl` and `syscall` return `c_int` on 32-bit
   and `c_long` on 64-bit, neither of which is `i64` by definition.
4. `BPF_RET_ALLOW` was `0x7FFF_FFFF & 0_0000`, which is zero for every input.
5. The assembled seccomp program had **no `SECCOMP_RET_ALLOW` anywhere**. Every permitted
   syscall jumped past the end. Loading it killed the process with SIGSYS.
6. The instruction packing put the `u32` operand in bits 0..32, which is where `jt` and
   `jf` live, so every jump carried a corrupted operand. `jeq AF_UNIX, jt=1` packed to
   `0x0001_0001`.
7. The APK provenance marker had no terminator, so the scanner ran into the next string
   in `.rodata` and produced `cerebrum-build-rev=<40 hex>payload`.

**The device job now passes.** Run 45006933238: `Starting 8 tests on test(AVD) - 14`,
`Finished 8 tests`, `0 skipped 0 failed`, `BUILD SUCCESSFUL in 16m 33s`. That is the
packaged library executing the memory round trip, the backup/restore round trip and the
consolidation pass on a real emulator. It is the first green device run in this project's
history.

**It has never been green end to end.** The longest-lived run had device green and app
failed on the marker. That is the run this branch's last two commits fix.

## 2. Tests not fail-first verified

A test is fail-first verified when the bug it guards has been restored and the test has
been watched to fail. A test that passes on broken code proves nothing.

### Verified by restoring the bug

Seventeen restorations, and several rows cover more than one test, so the count of tests
proved this way is higher than the row count.

| test | how the bug was restored | what failed |
|---|---|---|
| `history_for` deletion revision (cerebrum-events) | removed the `memory.deleted` arm | the deletion-revision test |
| `memoryHistory` for a retired row | made the door refuse retired ids | the deleted-row history test |
| prompt-architecture source scan (`HivePromptContractTest`) | reinstated the shipped prompt text | the scan test |
| stale-vault refusal, both tests (`DatabaseSnapshotEngineTest`) | removed the refusal | both refusal tests |
| retrieval policy archived + confidence paths | reverted each branch | the two policy tests |
| 5 of 7 `MemoryToolScopeBoundaryTest` cases | made the filter permissive | five cases |
| `a_deleted_fact_cannot_be_resurrected` | disabled the refusal | the resurrection test |
| QUERY and PREFETCH dispatch | restored the shipped no-op | the dispatch tests |
| category unification + agent ownership | reinstated the old filter | both tests |
| snapshot carries the vault | reverted to transcript-only | the vault-inclusion test |
| seccomp packing (`the_packed_word_is_the_kernel_struct_byte_for_byte`) | restored the old packing | the test, showing the byte order reversed |
| seccomp ALLOW (`every_jump_lands_inside...`) | removed the ALLOW return | the walk, reporting zero ALLOWs |
| entity edge creation | disabled the entity pass | the edge-count test |
| entity edge walkability | disabled the entity pass | the traversal test |
| entity match case-insensitivity | matched the lowercased word against raw content | three entity tests, including the one written for this |
| decay retirement published to the tree | removed the publication | the retirement test, tree said `Active` |
| APK provenance marker | removed the `;` and rebuilt the APK | the workflow step, `[NO TERMINATOR]` |
| stale last-error slot | emptied `clear_last_error` | the stale-rejection test |

### NOT fail-first verified

These tests were added and pass, and no restoration was performed for them. They are
assertions of behaviour that was reasoned about rather than behaviour that was broken and
watched. Each is named so nobody assumes otherwise.

**`AgentExecutionOwnershipTest`** — 12 tests in the file, 2 named in a commit body with a
proof (`workOutlivesTheCoroutineThatStartedIt` and the cancelled-waiter case). The other
ten, including `aSecondStartForALiveRunIdIsRefusedAndRunsNothing`,
`aRefusedStartDoesNotLeaveItsCallerWaitingForever`,
`aFinishingJobDoesNotClearAnotherRunsEntry` and `twoResumesForOneRunAdmitOnlyTheFirst`,
were written against the host's contract and have never been seen to fail. An attempt to
prove the cancelled-waiter case by switching `scope.launch` to `GlobalScope.launch` was
tried and **still passed**, which proved nothing, and that is recorded in that commit's
message rather than papered over.

**`AgentMemoryServiceTest`** — 15 tests. The ownership and scope tests overlap with
`MemoryToolScopeBoundaryTest`, which WAS proved, but these specific assertions were not
individually restored.

**`CerebrumMemoryOnDeviceTest`** — 5 tests, run only on the emulator. They were watched
to fail once, for a different reason (the stale-vault defect), and the assertions about
individual memory operations have not each been restored.

**`CerebrumBackupRestoreOnDeviceTest`** — 3 tests. The round trip was watched to fail when
the vault was stale, and the `everRetired` assertion would fail if the deletion revision
were removed. The individual assertions inside the round trip have not each been
restored, because the whole test is one sequence with one teardown.

**`PageSizeAlignmentTest`** — 2 tests, added with the CI workflow. They assert an ELF
alignment property of the built libraries and were not proved by breaking an alignment.

**`MemoryToolIdentityTest` update contract** — the update-records-superseded assertion was
not restored individually.

## 3. The intermittent failures

Two test files were reported as failing intermittently. Both were investigated; both had
a real cause rather than being flaky.

### `AgentExecutionOwnershipTest` — scheduler-dependent, fixed

`cancellingTheWaiterDoesNotCancelTheWork` used `delay(250)` for the work, `delay(50)`
before cancelling the waiter, then another `delay(600)` before asserting. On a loaded
suite the work could still be pending when the assertion ran, so the test failed for a
reason unrelated to the behaviour it names: whether a scheduler gave the work 250ms. It
was rewritten to hold the work behind a `CompletableDeferred` gate, so the ordering is
established rather than hoped for, and the test also runs faster.

The same shape appeared in `aSecondStartForALiveRunIdIsRefusedAndRunsNothing`, which
asserted a counter immediately after `start` even though the block runs on a launched
coroutine. It now awaits the deferred before counting.

### `CerebrumBackupRestoreOnDeviceTest` — a real product defect, fixed

This one failed on run 45008553145 with `cerebrum boot failed: pass missing, fail
closed`, at the assertion that boots a restored vault with no caller-supplied passphrase.
It had passed on the previous run with no code change in between.

**THE CAUSE.** `LAST_ERROR` is one string for the whole process and nothing cleared it.
Kotlin reads it immediately after a failed start and hands it to `attemptWasKeyOrphan`,
which searches it for `PassphraseRejected` and, on a match, calls `healOrphanedKeyPair`.
That function deletes the Keystore alias, the envelope and the vault. So a rejection left
in the slot by ANY earlier operation in the same instrumentation process became the
diagnosis for a later, unrelated failure, and the heal destroyed a healthy key. The
vault was fine; the envelope had been deleted on the strength of a sentence about
something else. That is why it passed one run and failed the next: it depends on what had
failed earlier in the process, not on the code under test.

**THE FIX.** The slot is cleared at the start of every start call, so a caller reads this
attempt's failure or nothing, and the orphan question is now its own JNI call
(`cerebrumLastErrorWasPassphraseRejectionJNI`) rather than a substring a caller searches
out of prose. Kotlin still requires a non-blank error before it will heal, so a start that
fails for another reason cannot trigger the wipe.

Proved by emptying `clear_last_error`: the stale-rejection test fails; with it restored,
all three pass.

### `AgentConversationTranscriptTest`

Reported as part of the same intermittent set. Five isolated runs, three full-suite runs
and one gate rewrite found no reproduction, and no assertion in it depends on ordering or
timing. No cause was established and no change was made. It is recorded here as
unexplained rather than closed.

## 4. Standing risk

The device job runs 8 tests in one instrumentation process against one emulator, and
`@After` in each test class calls `CerebrumHost.stop()`, which wipes the passphrase and
releases the brain. Tests therefore share process-global state by design. The stale-error
fix removes the one instance of that which was known to be destructive. Any future
process-global state with a destructive reader needs the same treatment: cleared at the
point of use, and asked structurally rather than parsed out of prose.
