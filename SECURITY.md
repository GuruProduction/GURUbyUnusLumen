# Security Policy — GURU by Unus Lumen

GURU is an Android app that gives a language model real hands on a phone: a Linux userland, the device's native permissions, the hosts on your network, and its own agents working to a permission set you choose. That power is the product, so this document says exactly what it reaches, what leaves the device, how releases are signed, and how to report a flaw.

It is written to be checked rather than trusted. Every claim below names where in the source it comes from.

Contact: **steven@unuslumen.com**

## Reporting a vulnerability

Report privately, never in a public issue.

- Use GitHub's private vulnerability reporting on this repository (Security tab, then Report a vulnerability). It is enabled.
- Or email **steven@unuslumen.com**.

Include what you found, how you found it, the build you tested (the version name from the APK, plus your Android version and device), and the steps to reproduce it. Logs, screenshots and proof-of-concept scripts are all welcome.

You will get a human acknowledgment within **72 hours**. Do not publish details until a fix is released and we have agreed disclosure timing together. Credit is yours if you want it, in the release notes and in this file, and anonymous reports are equally welcome.

This is one person maintaining a large codebase, so please be patient between the acknowledgment and the fix.

## Supported versions

Security fixes land on the latest tagged release. Older tags get fixes on a best-effort basis only, so test against the newest release before reporting. A sideloaded GURU updates by installing a newer APK from the Releases page, which carries a SHA-256 checksum you can check against your download.

## What counts as a vulnerability

GURU does things that look alarming and are the product working. Drawing this line clearly saves us both time.

In scope:

- Another app on the device reaching GURU's data, credentials, memory vault or services through a path it was not granted.
- A remote party causing GURU to act, exfiltrate data, or grant a permission without the owner's knowledge.
- Data leaving the device that this document says does not leave it.
- A permission, sandbox, SELinux or Keystore boundary bypass.
- A flaw in the signing, update or release process, including anything that lets a build signed with a different key install over a real GURU.
- A hostile publisher, model endpoint or network peer causing code execution, memory corruption, or a bypass of the permission checks.
- The memory vault opening under a wrong passphrase, or vault plaintext reaching disk.

Out of scope by design:

- GURU running shell commands, reading files or controlling devices **because the owner granted it**.
- Network scanning of hosts the owner owns or is authorised to test.
- An agent acting inside the permission set the owner gave it.
- Anything requiring physical access to an unlocked device, or an attacker who already holds the unlocked phone.
- Attacks that need the owner to disable a protection, install a hostile APK deliberately, or hand over a passphrase.
- Third-party model providers' handling of data you chose to send them, which their own terms govern.

If you are unsure which side of the line something falls on, report it anyway and say so.

## What GURU can reach

GURU declares **167 permissions** in `GURUbeta-FULL/app/src/main/AndroidManifest.xml`. Declaring is not holding: a normal install gets the runtime permissions the owner allows plus the install-time ones, and Android refuses every signature and privileged permission outright.

The permission layer names that difference rather than pretending every permission is obtainable. `PermissionTier.kt` sorts each one into a tier and `GrantCheck.kt` turns each into one sentence the model and the owner can act on. Five of those tiers exist to stop a tool telling you to grant something you cannot:

- **RUNTIME** is the normal dialog.
- **INSTALL** is granted at install with no dialog, so a denial means the manifest lost a line and is reported as a build fault.
- **PRIVILEGED** is reserved for system apps and reachable only through Shizuku, and only when the platform marks the permission `development`.
- **NOT_GRANTABLE** is reserved with no `development` flag, so no setting, no Shizuku and no Device Owner provisioning can grant it, and the app says exactly that instead of sending you to a screen that will never work.
- **APPOP**, **ROLE**, **SERVICE**, **SELF_GUARD** and **HARDWARE** each name their own real route, or say plainly that none exists.

A tool whose permission you have not granted therefore returns one sentence naming what is missing, why it matters and how to grant it, and it never returns an empty list. That was a real defect class, which `GrantCheck.kt` calls a lie a tool tells by omission, and it is closed.

The accessibility service, the notification listener and MediaProjection screen capture each need you to switch them on yourself in Settings. The app cannot grant them to itself.

## What leaves the device

### The publisher

GURU fetches from `https://api.unuslumen.com` at startup and on a refresh cadence. That fetch supplies the assembled system prompt, the model runtime configuration, the sensory-context settings, the optional skills library and the ad-pack artwork. It is a plain GET with no auth header, no cookie jar and no install identifier, and it rides Tor like every other public call, so the server sees a Tor exit rather than your address.

What that endpoint does not receive is worth saying too, because "stateless" is often claimed and rarely scoped. The content endpoints send, and they carry no account and store no conversation. The market endpoints are a different thing: publishing, buying, rating and installing each write a row, which is why a wallet pseudonym exists at all. So the accurate sentence is that the content feed is stateless and the market is not, and neither of them sees your conversations.

### Model inference

GURU runs no language model on the device. It talks to the endpoint you connect: OpenAI, Anthropic, Google, xAI, a local Ollama server, or any OpenAI-compatible endpoint on your network, with the fixed endpoints in `AiProvider.kt`. Your prompts, the conversation context and any image you attach go to that endpoint and to nothing else. When your model is on your own LAN, inference traffic never leaves your network.

Your API keys are held by `CredentialVault.kt`: one AES-256-GCM key per service, generated inside the Android Keystore so the key material is never readable by the app, by root, or by copying any file. Every write seals with a fresh IV, and the envelope lives in the app's private files directory.

### Web search and weather

Web search queries go to a rotating list of SearXNG instances over Tor in `WebSearchService.kt`, and weather is fetched from `wttr.in` through Tor in `MetadataConfigFetcher.kt`. Tool calls you make through the web tools go where you point them, through the same egress policy.

### Egress policy, precisely

`EgressClassifier.kt` is the one routing decision every outbound connection asks for. It is a pure object with no Android dependency, so its whole truth table is unit tested on the JVM.

Three postures exist, selected by `/tor` and `/clearnet` in `EgressMode.kt`:

- **AUTO** is the default on a fresh install, and a public host rides Tor.
- **TOR_ONLY** is the lock, where a public host rides Tor and the clearnet override is refused while it is active.
- **CLEARNET** is the owner's override, where a public host may go direct and every result that went that way says so.

Three properties survive every posture. A loopback or LAN address always goes direct, because Tor cannot route private space and asking it to is a guaranteed failure. A request carrying a credential forces Tor and ignores the override entirely. When Tor has no circuit, a public request **waits** for one for up to thirty seconds rather than falling back to a direct connection, and if the window expires the request fails closed with a message naming the wait.

One measured caveat worth stating: `network_security_config.xml` sets `cleartextTrafficPermitted="true"` across the app. That is deliberate, because LAN devices such as Sonos, Hue, DIAL, UPnP and routers speak plain HTTP. It means nothing stops a cleartext connection if a tool is pointed at one. That is a fact about the design, not an oversight.

### Telemetry

There is none. No Firebase, Crashlytics, Sentry, Mixpanel, Amplitude, Segment or Analytics dependency appears in any Gradle or version-catalogue file, and no analytics call exists in the source. The app reads no advertising identifier, no IMEI and no `ANDROID_ID`; searching the whole `portal/data` source for all of them returns nothing. When GURU crashes, the stack trace goes to your clipboard so you can report it yourself.

That check was performed statically against version 3.77 on 18 November 2026 by reading every Gradle and catalogue file for analytics dependencies and searching the source for the identifier APIs. It was not performed by traffic capture. A capture from a clean install is the stronger proof and is not published yet, so treat the static check as exactly that.

### Backup

Cloud backup is disabled for every domain: root, file, database, sharedprefs and external are each excluded in `data_extraction_rules.xml`. Device transfer includes the database, sharedprefs and files, because a phone-to-phone cable migration with no account and no server is how a year of memory survives a handset change. A cloud backup of the brain cannot happen.

### Memory

The brain is encrypted at rest. `cerebrum-crypto` derives a 32-byte key with Argon2id at 256 MiB and three passes over a 19-byte salt, then seals each record with XChaCha20-Poly1305. The 119-byte header is the only plaintext on disk, and it binds the magic, version, salt and KDF parameters into the verifier's associated data, so a verifier lifted from another vault cannot open it.

Each save writes a new generation with a fresh file id and therefore a fresh nonce base, which is what stops nonce reuse across passes. The key is zeroized on drop. The passphrase lives nowhere except sealed in a Keystore-wrapped AES-256-GCM envelope and, briefly, inside the brain's RAM.

When the Keystore envelope is lost while a vault exists, the app **refuses to boot** and leaves every byte alone. An earlier version deleted the brain in that state, and `CerebrumHost.kt` documents at length why it no longer does.

## Builds and signing

Releases are signed with the `guru-release` key:

- **SHA-256 fingerprint:** `3E:58:42:37:B1:41:8E:12:01:C6:A4:ED:7C:07:B5:8A:F7:B7:C5:A1:FB:E2:E6:BE:F7:6B:03:A2:BA:E8:CD:3C`
- **SHA-1 fingerprint:** `76:13:5D:EB:72:FD:45:18:71:79:F5:D5:05:90:AE:C2:02:C4:A9:AD`
- **Subject:** `CN=Steven Newman, OU=Unus Lumen, O=Unus Lumen Ltd, L=Bristol, C=GB`
- **Valid:** 26 September 2026 to 11 February 2054.

Every published GURU is signed with this key and no other. Android refuses to install a build signed with a different key over an existing install, so a matching fingerprint with a matching checksum is what proves a download is really ours.

Verify a download before installing it:

```
apksigner verify --print-certs guru-v3.77-release.apk
sha256sum guru-v3.77-release.apk
```

The shipped v3.77 APK verifies under signature schemes v2 and v3. The build requests all four, and `apksigner` reports v1 as false because it only inspects the JAR signature below a certain `minSdk`; the v1 signature is present in the file. v4 produces a separate `.idsig` beside the APK rather than an embedded block.

To build from source:

```bash
cd GURUbeta-FULL && ./gradlew assembleDebug
```

JDK 17 and the Android SDK are required, and the Gradle wrapper lives inside `GURUbeta-FULL`. `CONTRIBUTING.md` covers the build lock, the three verification layers and the release credentials.

Builds are **not reproducible**. The same commit built on a different machine produces a different APK byte for byte, so compare against the published SHA-256 and the certificate rather than expecting a matching binary.

There is **no CI**. The repository's only workflow file is `FUNDING.yml`, so nothing builds or tests a pull request automatically and no third party validates a release before it ships. The signing key and the release build both live on one machine. GitHub secret scanning is on, and push protection is off.

## The privacy gate

`scripts/hooks/pre-commit` runs `scripts/privacy-scan.mjs` on every commit. It refuses a commit that would publish an IP address outside the documentation ranges, a MAC address, a credential, a private key, or a path naming the author's machine. It fails closed, so a missing scanner or a missing runtime refuses the commit rather than allowing an unscanned one, and `--no-verify` is banned by policy.

It exists because the author published his own addresses more than once before it was built.

## AI-generated code

Much of this codebase is written with AI assistance. Stating it plainly costs less than a buried disclosure, and the point of the rest of this section is that who typed it matters less than what checks it.

What guards it:

- Roughly 160 JVM test files, 40 instrumented Android test files, and 43 Rust files carrying unit tests.
- A rule that every changed file is read in full before it is edited, along with every caller and callee.
- A rule that a test is proved by restoring the bug and watching the test fail, because a test that passes on broken code is worse than no test.
- The privacy gate above, on every commit.
- A pull request body that must state what was run and what was not, since that statement is the basis for the release test.

The limits, stated honestly:

- Detekt is configured with a baseline, but **no module enables it**, so static analysis does not run.
- There is no CI, so nothing runs automatically before a merge.
- Device verification happens on the author's own phone, not on a device farm.
- No outside security professional has reviewed this threat model, the permission layer, the crypto or the Tor routing.
- The codebase is large and recently written, so assume undiscovered defects exist.

## Threat model

GURU assumes the phone's owner is the operator and is authorised to control that device and the network it sits on.

It defends against a hostile network peer, because the classifier routes public traffic through Tor by default and a public request never silently falls back to direct. It defends against a hostile model endpoint, because the permission layer gates every tool whatever a model asks for. It defends against another app on the device, because the vault, credentials and database sit in the app's private sandbox and credentials additionally sit behind Keystore keys the app cannot read. It defends against a stolen or backed-up database, because records are sealed with keys that never leave the device and cloud backup is excluded by rule. It also fails closed when the Keystore envelope is lost, keeping the vault intact rather than starting clean.

### The publisher's authority, stated precisely

The publisher is a different matter from ordinary fetched content, and the difference is worth naming because it is the one place a reader should think about trust rather than about sandboxing.

A web page GURU fetches is data. The assembled system prompt is not: it is the instruction set, and the model holding it has a shell, the accessibility service and LAN control. So whoever controls `api.unuslumen.com` can change what every install is told to do at its next refresh. Today that is Steven. It is also anyone who compromises the host, the domain or the account, and any future owner of the company.

The permission layer bounds this to what you have granted, which is the real limit: **the feed can change what the agent is told, not what it is allowed to do.** A hostile publisher could make GURU try anything, and it would still stop at a permission you did not give it.

Three things narrow the gap further, in order of value. Signing the bundle with a key kept off the serving host, with the public key pinned in the app, so a compromised web server cannot serve content the app accepts; that is the item that actually closes it. Publishing the payload at [GuruProduction/GURU-content](https://github.com/GuruProduction/GURU-content), so what the server has served is a public record rather than a claim. And showing the bundle version and hash in the app, so an install can be told apart from the one advertised.

It does not defend against a rooted device or a hostile privileged app, since root defeats every boundary here. It does not defend against Tor's own traffic-analysis adversary, which can see that something was sent even though it cannot see where from. It does not protect your prompts from the model provider you chose. It does not defend against an owner who grants everything and installs anything, because GURU does what you allow it to do and that is the design. It does not defend against physical access to an unlocked device, and it does not defend against a compromised build machine.

## Known limitations

Named here so nobody has to discover them by accident.

There is no CI and no static analysis gate, so a regression can reach a release. There is no published reproducible build. No third-party security audit has been done, so the author's own review is the only review so far. No traffic capture has been published for the telemetry claim. Cleartext HTTP is permitted app-wide so LAN devices can be controlled, as described above. And `apksigner` under-reports the v1 signature scheme, which makes the build look weaker than it is rather than stronger.

The content feed **is not signed yet**. Whoever controls the serving host controls what the agent is instructed to do, bounded only by the permissions you grant. This is the largest known gap in the trust chain, it is named here rather than left to be discovered, and signing the bundle with a pinned public key is the fix being built.

Reports against any of these are welcome, and a proof beats a paragraph every time.

---

**GURU is built by Steven Newman, founder of Unus Lumen in Bristol.** It is released under the GNU AGPL v3.0-or-later, and the full text is in `LICENSE`.
