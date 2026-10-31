# RULES

These are not preferences. They are the conditions under which GURU exists. Anything that violates them is wrong, no matter how well it works on a test device.

## 1. No root. Ever.

Nothing in this repository may require root access. It is 2026 and nobody roots their phone. A capability that only works with `su` is not a capability: it is a demo that fails for every real user.

If a feature needs root, it is not finished. Find the unrooted answer or the feature does not ship.

## 2. Nothing REQUIRES adb. adb is the last resort, and it must still exist.

The default path is the phone alone: no cable, no companion computer, no debug bridge. Every capability must work from the phone by itself on a stock handset.

adb is the LAST resort, not a write-off. Power users exist, and this app has to outlive the Android restrictions of the day, so the adb tier and the Shizuku tier stay fully built, fully wired and fully working. They are not stubs and they are not allowed to rot.

The distinction is exact:

- A feature must be reachable without adb, or it is missing.
- When a permission genuinely cannot be granted without adb or Shizuku, that path is built properly and documented, because for some permissions it is the only door that exists.
- Never build a feature that works ONLY with adb when a real unrooted answer exists.

Root stays an absolute no. adb does not.

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

## 6. Finish the job: test it, verify it, then report it.

Never claim more than was run. When something passed, say it passed.

There is no such thing as an "honest caveat". A caveat is a half-finished job written down politely. If something can be tested, test it. If it can be driven on the device, drive it. If a limit is real and physical, prove that it is real by measuring it, and then say what was measured. "I did not verify this" is not a caveat, it is an unfinished task, and the correct next action is to finish it.

The standard for every change: the real path was driven, the real output was read, and the result is stated in one line that a person can act on.
