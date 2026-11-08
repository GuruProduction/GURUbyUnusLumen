# Contributing to GURU

GURU is an Android application that gives a language model real control of a phone: the tools, the permissions, the memory and the network policy all live on the handset. It is licensed under the GNU AGPL v3.0-or-later, and the text is in `LICENSE`. There is no proprietary half.

## Layout

`GURUbeta-FULL` is the Android build and the Rust brain. `factory` is a Bun and TypeScript harness that boots emulators and replays recorded failures. `scripts` holds the privacy gate and its git hook.

The Gradle wrapper is at `GURUbeta-FULL/gradlew`, never at the repository root. Every Gradle command runs from inside `GURUbeta-FULL`.

Before your first build, copy `GURUbeta-FULL/local.properties` from an existing checkout. It is gitignored and holds the SDK path plus the release signing credentials, and Gradle cannot find the Android SDK without it.

## Building and testing

Gradle is the bottleneck, so one build runs at a time and every build goes through the lock wrapper:

```
guru-build-lock run /path/to/GURUbeta-FULL -- ./gradlew :portal:data:testDebugUnitTest
guru-build-lock run release /path/to/GURUbeta-FULL -- ./gradlew assembleRelease
```

The wrapper is at `$HOME/.local/bin/guru-build-lock` and it releases the lock on success, on failure, on Ctrl-C and on SIGTERM. Do not take the lock by hand with `mkdir`. The owner file records a PID so a dead holder can be identified, and reclaiming one is a `mv` so two waiters cannot both win. `guru-build-lock status` says who holds it.

Verification has three layers, and you pick the cheapest one that can actually answer the question.

JVM tests live beside the code, for example `portal/data/src/test/java/com/unuslumen/app/data/tor/EgressClassifierTest.kt`. Put decision logic in an Android-free object and test that. These run in seconds.

Robolectric covers anything that genuinely needs the Android framework: permission checks, `PackageManager` lookups, sensor presence, `Context` services. The dependencies are already wired in `portal/data/build.gradle.kts`. Two traps cost time once and should not cost it again. `grantPermissions` and `denyPermissions` are public on `ShadowContextWrapper`, and Robolectric ships only part of the permission database, so never assert that the framework declares a permission or that a tier matches it. Those facts need a real device.

The emulator is allowed and it is last. Use it when the subject is the device: permission grants landing, exec and file modes, SELinux refusals, sockets, accessibility, MediaProjection, or a tool returning real data. One emulator at a time, never one per parallel agent. Start it through Terminal.app with `osascript`, because a GUI process started from a shell dies with the shell.

The factory harness runs with `bun run src/cli/main.ts` from `factory`. `bun run typecheck` checks it.

## What gets a pull request merged

Claim the issue before you start, and say so on the issue. One branch per piece of work, cut from `main`. Work happens in a worktree, never in a shared checkout, and each worktree needs its own `local.properties`.

Read a file in full before you edit it, and read the files it calls and the files that call it. A partial read is how a change breaks something three modules away. Do not use shell tools to read or edit code; use the editor and the search tools.

No stubs, no `TODO` comments standing in for work, no placeholder returns, no empty catch blocks, and no hardcoded value pretending to be logic. If you cannot finish something, say what is missing rather than shipping something that looks finished.

Handle the error paths. A tool that reports a refusal as an empty result is a defect, which is why `GrantCheck` separates a denial from a privileged permission, absent hardware, a build fault and a permission outside the catalogue.

A green Gradle task proves nothing about the artefact. `UP-TO-DATE` means it did not compile. Verify the thing you claim to have verified.

## The pull request body

The body states what you ran and what you did not. That statement is the basis for the release test, so overstating it breaks the only gate that exists. Name the modules you changed, the tests you ran, and the paths you could not prove, with the reason.

Detekt is configured in `GURUbeta-FULL/config/detekt` with a baseline, and the root build declares the plugin as `apply false` so each module can opt in with `alias(libs.plugins.detekt)`. No module opts in yet, so detekt does not run today. Do not treat it as a gate until a module enables it.

## The privacy gate

Git runs `scripts/hooks/pre-commit` on every commit, and that hook runs `scripts/privacy-scan.mjs`. The scanner refuses a commit that would publish an IP address that is not a documentation address, a MAC address, a credential, a private key or a path that names your machine.

Install it in your checkout before your first commit:

```
scripts/install-hooks.sh
scripts/install-hooks.sh --all-worktrees
```

The hook fails closed. A missing scanner or a missing Node and Bun binary refuses the commit rather than allowing an unscanned one. `--no-verify` is banned. If the gate is wrong, fix the rule in `scripts/privacy-scan.mjs` and explain why in the commit message.

The allowlist inside the scanner is the only place a documented exception belongs.

## Questions

Open an issue. Say what you were trying to do, what happened, and what you expected. A report with the exact wording of an error and the build number is worth ten without.
