# RULES

These are not preferences. They are the conditions under which GURU exists. Anything that violates them is wrong, no matter how well it works on a test device.

## 1. No root. Ever.

Nothing in this repository may require root access. It is 2026 and nobody roots their phone. A capability that only works with `su` is not a capability: it is a demo that fails for every real user.

If a feature needs root, it is not finished. Find the unrooted answer or the feature does not ship.

## 2. No adb. Ever.

Nothing may depend on adb, a host machine, or a debug bridge. The app must do everything it does from the phone, by itself, with no cable and no companion computer.

Debugging with adb is fine. Depending on adb is not. If a capability is only reachable through `adb shell`, it is missing.

## 3. Nothing is built to work around a problem.

When something does not work, the answer is the real implementation. Never a shim that hides the breakage, never a mechanism that only functions in a debug harness, never "it works on the emulator".

Two dead ends are recorded here so nobody repeats them:

- **A root symlink** for `/data/data/com.termux`. Works only with root, so it works for nobody.
- **proot**, the ptrace chroot Termux uses on unrooted devices. Android's seccomp filter blocks `ptrace` for ordinary apps, so proot cannot `execve` even `/system/bin/sh` here. Measured, not assumed.

What survived is `libpathshim.so`, an LD_PRELOAD library that rewrites a compiled-in upstream path at the libc boundary. No root, no adb, no ptrace. That is the standard: it works because it does not need anything a real user does not have.

## 4. Verified means observed on the device, in the app's own domain.

A green build is not verification. A passing unit test is not verification of device behaviour. Verified means the real path was driven and the real output was read.

Instrumentation runs in the app's own SELinux domain, because `adb shell` and `run-as` are different domains and carry different exec rules. A result from either proves nothing about the app.

## 5. No unfixed errors.

Every error found is fixed, including ones nobody asked about. A skipped error is a lie about the state of the system, and lies compound.

## 6. Honest reporting, always.

Never claim more than was run. When something passed, say it passed. When something is unproven, say it is unproven. When a limit exists, name it in the commit message and the PR body, because the limit is the part a person needs to know.
