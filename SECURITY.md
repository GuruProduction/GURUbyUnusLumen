# Security Policy — GURU by Unus Lumen

GURU takes its security posture seriously: an on-device cognitive system holding someone's daily life deserves nothing less. If you find a vulnerability, we want to hear from you, quickly and quietly.

## Reporting a vulnerability

**Email: steven@unuslumen.com**

Include everything a fix needs: what you found, how you found it, which build you tested (version name from the APK, plus your Android version and device if relevant), and reproduction steps. Screenshots, adb logs and PoC scripts all welcome.

- You will get a human acknowledgment within **72 hours** of your email.
- Report privately first. Do not open a public GitHub issue describing a live vulnerability, and do not publish details until a fix is released and we agree on disclosure timing together.
- Credit: if you want your name in the release notes and this file's hall of thanks, say so. Anonymous reports equally welcome.

Please stay inside the spirit of research. Do not run GURU's systems, or anyone else's GURU install, in a way that damages them or reads anyone else's data.

## What matters most

The attack surface worth probing, in rough priority:

1. **The permission boundaries** — GURU's Accessibility service, notification listener, ADB with shell UID, and All Files Access. Each grant widens what tools can reach; look for grants leaking reach the user did not intend.
2. **On-device encryption** — AES-256-GCM with per-operation fresh IVs, keys held on device. Any path where key material, plaintext memory or conversations land somewhere they should not.
3. **The Tor routing and fail-closed policy** — external requests must never leak when Tor is not ready. Loopback and LAN addresses stay direct by design; anything else refusing rather than leaking is the contract. Prove us wrong.
4. **The sync protocol** — the app speaks plain documented HTTP to the publisher. Look at what arrives, how it is validated, and what a hostile publisher could push.
5. **The sideloaded toolchains** — the embedded Python, busybox/toybox, OpenSSH, QuickJS assets are powerful by design. They should never be reachable by another app on the device through a path the user did not grant.

## What this release is, plainly

This is a public beta. The source is public, the hardening audit (permission boundaries, encryption, Tor routing) is still ahead, and there are known bugs in corners of the app the author's daily use never touches. The first people to install GURU are beta testers. Report what you find and you are directly helping ship it.

## Supported versions

Security fixes land on the latest tagged release. Older tags and commits receive fixes on a best-effort basis; sideloaded apps update by reinstalling a newer APK from the [Releases](../../releases) page, which will always carry a SHA-256 checksum so you can verify your download.

---

**Unus Lumen** — Bristol, UK

steven@unuslumen.com