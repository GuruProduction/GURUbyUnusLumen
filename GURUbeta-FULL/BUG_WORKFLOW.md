# GURU BUG WORKFLOW — Repo Law (for every Lux session on this project)

This file is repo-rooted project instruction: every Lux session working on
GURU loads it before doing anything. No database, no memory, no persona —
just real repo state, executed the same every time.

---

## 1. THE BUG WORKFLOW (owner-enforced, run verbatim, never reordered)

1. **RECON ONLY**: open the GitHub issue first (`gh issue view <n> --repo
   GuruProduction/GURUbyUnusLumen`), read the full thread, read the real code
   FULLY before diagnosing. No change is made until the file was read in
   full. No proposal at this stage.
2. **LOGS FIRST**: on a real-device bug, fresh `adb logcat` from the owner's
   reproduce run lands as a comment on the issue BEFORE anything codes.
   Timestamped logcat lines are the evidence record; without one, nothing
   further advances.
3. **PROPOSE** into the issue thread: plain-words cause and intended fix.
   The owner must approve the proposal before a single line changes.
4. **FIX ONLY THE PROPOSED THING**: scoped diff, no wider refactor, no error
   masking (a broader catch block or error-supression is a fix-FAILURE, not a
   fix), no TODO/placeholder/"will work later" strings.
5. **VERIFY**: run `./gradlew assembleDebug 2>&1 | tail -500` from the repo
   root at `/Users/unuslumen/GURUbyUnusLumen/GURUbeta-FULL`; the tail must
   show BUILD SUCCESSFUL. A failure here is never hidden.
6. **RELEASE CHAIN** (each in order, the ONE correct chain, never
   substituted, never reordered, never "improvised"):
   1. `gh release list` and check the current `versionCode / versionName`
      in `/Users/unuslumen/GURUbyUnusLumen/GURUbeta-FULL/app/build.gradle.kts`
      — if this build ships, bump them.
   2. `./gradlew assembleRelease` from the repo root (production build;
      `assembleDebug` output is NEVER signed or released).
   3. Sign from the ROOT repo at `/Users/unuslumen/GURUbyUnusLumen/GURUbeta-FULL`:
      ```
      ~/Library/Android/sdk/build-tools/36.1.0/apksigner sign \
        --ks /Users/unuslumen/guru-signing/guru-fresh.jks \
        --ks-pass pass:<OWNER-SUPPLIED-PASSWORD> \
        --key-pass pass:<OWNER-SUPPLIED-PASSWORD> \
        --ks-key-alias guru-release \
        --out <absolute-out-path>/GURU-production-v<version>.apk \
        app/build/outputs/apk/release/app-release-unsigned.apk
      ```
   4. `apksigner verify --print-certs` — signer must be
      `CN=Steven Newman, OU=Unus Lumen` (the release cert); anything else:
      stop, do not publish, ask the owner.
   5. `shasum -a 256` on the output APK.
   6. `gh release create v<version>` with REAL changelog naming exactly
      what shipped; APK attached on FIRST create (not attached in a
      separate follow-up).
   7. `git add` → `git commit` → `git push origin main`.
7. **REPORT INTO THE ISSUE**: for every fix attempt that ships, one issue
   comment carrying the release tag URL, the APK SHA-256, and a real
   verdict of whether the on-device reproduce is clean. The issue STAYS
   OPEN until that verdict is real.

## 2. HARD RULES (standing orders, violated on penalty, owner-enforced)

- Never use sub-agents / Agent tool of any kind on GURU work, regardless of
  load or model availability the main session thinks it needs. The main
  session does its own work.
- Never mask. Widening a catch or quieting an error IS masking. The root
  cause must be named in plain words as the fix.
- Never say "fixed", "done", or close the issue without a real on-device
  run against the REAL reproduce. Claim without the run = fabricated
  evidence = forbidden.
- Full file reads before any edit: Read tool, whole file, every time. No
  partial reads. sed/cat/awk are tools that never touch a file being
  modified/read here: real Read tool calls only.
- One comment per shipped attempt carrying release URL + APK SHA-256 +
  device-run verdict; a silent ship is a process broken record.
- A failed reproduce must be commented into the issue as EVIDENCE of the
  new failure surface, not deleted or replaced. The failed comment
  history is the bug's true memory.

## 3. HOW TO RESUME AFTER A GAP (any new Lux session, anywhere)

Reading this file at session start IS the load step. Steps:

1. `gh issue list --repo GuruProduction/GURUbyUnusLumen` — the OPEN list is
   the work-list.
2. Read each open issue's full body; that context IS the memory that does
   not live in any database.
3. If a bug session picks a bug up with a device reproduce: logs FIRST as
   above in section 1, no code touching first.
4. On close: only at REAL on-device verify; comment includes log lines from
   the clean run so the future Lux (or any human on the repo) can trace
   the close to the evidence without re-doing recon.

---

Authored 2026-10-03 with the owner's word. Every point above is real repo
law for both `lux` sessions and any human dev working the repo. This
carries forward the exact 5-step bug workflow Steven pinned on 2026-10-01,
the release-chain correction (release variant only), the owner-supplied
sign cert chain, and today's owner directive: NEVER use sub-agents.