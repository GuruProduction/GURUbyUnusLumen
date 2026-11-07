---
name: alarms-and-automation
description: "Set an alarm in the system clock app, and report the four separate grants that decide whether scheduled work fires at all, with a fix per switched-off grant."
allowed-tools: ["setSystemAlarm", "automationStatus", "bootReceiverState"]
when_to_use: "Use when my human asks for an alarm, or when something scheduled did not fire and I need to find out why. 'Wake me at seven', 'set an alarm for half six', 'why didn't my reminder go off', 'is my automation working', 'why does nothing run in the background', 'check my automation'."
---

# Alarms and automation

An alarm that actually lands in the clock app, and the four separate grants that decide whether anything scheduled ever fires.

## Why this is not `createAlarm`

A `createAlarm` tool already exists for GURU's own alarms. This one reaches the **system clock app** through the public alarm intent.

That distinction matters because the intent route needs no privilege at all: any app can fire it, and the clock app creates the alarm itself. So this works on a normal install where a privileged route would not.

Two tools with one name would make the registry silently keep whichever loaded last, so the name is different on purpose.

## Setting the alarm

`setSystemAlarm` takes hour and minute and refuses anything outside 0 to 23 and 0 to 59 before touching anything.

It then **resolves the intent first**, because on a device with no clock app the intent silently does nothing, and reporting that as a success would be exactly the defect this whole batch removes.

Then it fires, and then it reads `AlarmManager.nextAlarmClock` back. That readback is the answer, not the intent firing. If the platform reports no next alarm after a successful intent, the alarm did not land, and the result says so.

`daysOfWeek` takes MON through SUN for a repeating alarm. `skipUi` asks the clock app not to show its editor, but the app may ignore that, and the readback is what tells me whether anything happened.

## The four grants, and why they are four

`automationStatus` needs no permission at all, because reading whether a grant is held needs none. It reports four separate facts, each with its own fix:

1. **Boot receiver declared.** If the manifest has no receiver for the boot broadcast, nothing can be restored after a restart. That is a build fault, not a setting, and no screen fixes it.
2. **Battery optimisation.** If GURU is not exempt, scheduled work gets frozen. The fix is a Settings route.
3. **Exact alarms.** From Android 12 this is its own toggle, and below Android 12 the permission is granted at install, so that line reports true by the platform's own rule.
4. **Multicast lock.** Whether a discovery-capable lock is held.

**One all-clear boolean would hide exactly the one that is switched off**, which is why they stay separate: `bootReceiverDeclared`, `batteryOptimisationExempt`, `exactAlarmsAllowed`, `multicastLockHeld`, plus a `routes` map with one line per thing that needs fixing.

That shape is asserted in a test, so a refactor that collapsed it would fail rather than quietly remove the tool's value.

## The manifest check

`bootReceiverState` asks the platform which receivers would handle the boot broadcast, and lists the foreground service types the manifest declares.

It asks the **platform**, not the manifest text, because `ActivityInfo.intentFilters` is not a public field and reading it does not compile. The platform query is also the more useful answer: it reports what the build would actually do, not what the file says.

This is a build-health check, so its value is catching a manifest that lost a line. A scheduled thing that silently never runs is exactly what a missing boot receiver looks like from the outside.

## The mistakes I do not make

I do not claim an alarm was set without the next-alarm readback. I do not fire an unresolvable intent and call it done. I do not collapse the four grants into one answer. I do not report a manifest's intention instead of the platform's behaviour. I do not duplicate a tool name that already exists.
