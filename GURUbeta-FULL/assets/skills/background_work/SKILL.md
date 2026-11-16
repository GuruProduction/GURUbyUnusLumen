---
name: background_work
description: "What keeps running when GURU is not on screen: which foreground services exist, what each is allowed to do, and why the platform kills a service that breaks the rules."
allowed-tools: ["getDeviceInfo", "checkAccessibilityStatus", "automationStatus", "powerControl", "batteryDetail"]
when_to_use: "Use when my human asks whether GURU is still running, why something stopped in the background, whether a timer or reminder still works, or what GURU is doing while they are in another app. 'are you still there', 'why did that stop', 'is the heartbeat still going', 'will that reminder fire', 'what are you doing in the background'."
---

# Background work

GURU keeps things running while the person is using another app, and the platform watches every one of them. This is what exists and what the rules are.

## The rule the platform enforces

A foreground service must call `startForeground` exactly once per start, within a few seconds, with a notification the person can see. Android kills the whole app if that contract is broken, not just the service. So a service that cannot legally go foreground must still leave cleanly rather than crashing.

Its notification is not decoration: it is the permission slip. A service running without one is running illegally and the platform will end it.

## The types, and why each one is specific

Every foreground service declares a type, and the type decides what it may do. GURU uses eight of them, each because that is what the work genuinely is.

**specialUse** covers Tor, Cerebrum and the floating orb, each with a written subtype in the manifest explaining why no other type fits. It is deliberately last in line for the others, because it is the honest answer only when none of the specific ones applies.

**mediaProjection** is for screen capture, and it is legal only while the person's consent token is live. A service that calls it without consent is killed, which is why the capture service declares specialUse alongside it for the paths that run without a token.

**camera**, **microphone**, **location**, **phoneCall**, **mediaPlayback** and **dataSync** each gate their own capability on the matching runtime grant. Data sync carries a runtime ceiling of about six hours on Android 15, which is why Tor and Cerebrum do not use it: they must outlive it.

## The one that runs on a timer

The heartbeat is GURU acting with nobody watching. It is a full agent turn every thirty minutes by design, and its cost is the real kind: a wake lock plus that turn. It is also the only part of GURU that does something on its own schedule, which is why the Stop GURU tile cancels it and nothing else.

If the heartbeat stops, the answer is one of three: the switch is off, the exact-alarm permission was revoked, or the manufacturer's battery manager froze the app. The first two are readable; the third shows as the alarm never firing.

## What I check before blaming a freeze

Whether the alarm is actually booked, which is a read rather than an assumption. Whether the battery optimisation exemption is still held, because OneUI and similar floors exact alarms for an app in the normal bucket. Whether the app was killed rather than the alarm being lost, which the system log answers.

## The mistakes I do not make

I do not claim something is running without reading it. I do not add a service to fix a symptom before knowing which rule it broke. I do not use dataSync for work that must run for days, because the ceiling kills it. I do not tell somebody a timer failed without first checking whether the alarm was ever booked.
