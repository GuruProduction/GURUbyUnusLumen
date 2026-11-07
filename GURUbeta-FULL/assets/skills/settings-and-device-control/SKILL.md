---
name: settings-and-device-control
description: "Change system and secure settings, the clock, density, floating overlays, the status bar, the wallpaper, vibration and wake locks, always reporting the value read back rather than the value sent."
allowed-tools: ["settingsWrite", "secureSettingsWrite", "timeSet", "configurationChange", "overlayCreate", "overlayRemove", "expandStatusBar", "setWallpaper", "biometricAuth", "vibrate", "keepAwake"]
when_to_use: "Use when my human asks to change a phone setting, the brightness, the timeout, the language, the clock or time zone, a wallpaper, a floating note on screen, or to check whether fingerprint works. 'Turn the brightness down', 'put a note on screen', 'change my wallpaper', 'open my notifications', 'set the time zone to New York', 'can you use my fingerprint for this', 'buzz my phone'."
---

# Settings and device control

Every write here reports the value the platform holds afterwards, never the value I sent.

## Why the readback is the whole skill

A tool that says "brightness is now 128" because 128 went into the settings table is reporting an intention. The person then looks at a screen that did not change. So every setter here writes, reads back, and compares.

That means `changed` false with `success` true is a real and common answer: the write was accepted and nothing moved. I report it exactly that way rather than reporting it as done.

## The three settings tiers

`settingsWrite` covers what a person can change through the Modify system settings toggle: brightness, screen timeout, font scale, ringtone. It is an ordinary app-op, granted on a Settings screen rather than a dialog.

`secureSettingsWrite` reaches the secure and global tables and needs a system privilege this build does not hold, so on a normal install it refuses and names that. When the privilege IS held it writes and reads back, and it deliberately restricts no keys, because the point of holding it is that any key is writable. The result says what it wrote.

`configurationChange` splits by action: density needs the secure settings privilege, and a system-wide locale is not reachable from an ordinary app at all. Per-app locales exist from Android 13 and belong to a foreground context, so I report the limit rather than faking a change.

## Clocks

`timeSet` needs the set-time or set-time-zone privilege. It attempts the call, then reads `System.currentTimeMillis` and the default time zone back and reports both.

A write the platform accepted but did not apply shows up there as an unchanged value, which is why the readback is not optional.

## Floating overlays

`overlayCreate` needs the Display over other apps toggle, and runs on the main thread because `WindowManager` requires it. It returns an id.

The overlay stays until `overlayRemove` is called with that id, or until the process dies. The registry is in memory, so an id from a previous run cannot be removed, and I say that rather than reporting a removal that did nothing.

## The status bar

`expandStatusBar` uses reflection on the system status bar service, which is the only route available to an app and involves no root. If the reflection fails on a platform version, the result says the platform does not expose it, because there is no permission that would fix it.

## Wallpaper

`setWallpaper` takes an image path or a colour, and downsamples before decoding. That is not fussiness: a modern phone photo decoded at full resolution is tens of megabytes of bitmap, and this project has already shipped a 37 MB decode to paint an 18dp wordmark.

It reports whether a static wallpaper is now set, not what the picture looks like.

## Biometrics, and where the limit really is

`biometricAuth` reports availability and the exact `BiometricManager` constant: success, no hardware, hardware unavailable, nothing enrolled, or a security update needed.

It does **not** raise the prompt. `BiometricPrompt` needs a foreground Activity and a tool has only a Context, so the prompt belongs to the UI layer. I report the availability answer and say plainly that the gate itself is the app's job, because faking a gate is worse than having none.

## Vibration and wake locks

`vibrate` has no readback on this platform at all, so it reports what was requested and states that the platform gives no confirmation. I never claim the phone vibrated.

`keepAwake` takes a partial wake lock and reports the lock's own `isHeld`, which **is** a real platform readback. A lock with no timeout is a genuine battery cost and the process dying releases it, so I say both.

## The mistakes I do not make

I do not report a write from the value I sent. I do not offer a settings route for an install-tier permission, because no screen can grant it. I do not claim a vibration, a biometric prompt or a status-bar action I could not confirm. I do not decode a full-size image into memory.
