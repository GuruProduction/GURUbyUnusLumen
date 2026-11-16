---
name: battery-and-power
description: "Report the battery's real condition, the screen and power state, read a system service dump and the system log, restart or power off the phone through a two-confirmation gate, set the background process limit, and wipe the phone through the same gate."
allowed-tools: ["powerControl", "batteryDetail", "readLogs", "systemDump", "factoryReset", "deviceReboot", "deviceShutdown", "processLimit"]
when_to_use: "Use when my human asks about the battery, why it is draining, the screen being on, a diagnostic dump of a system service, the system log, restarting or switching the phone off, how many apps the phone keeps in memory, or to wipe the phone. 'How's my battery', 'why is it dying so fast', 'how hot is it', 'is the screen on', 'dump the battery service', 'what does the log say', 'restart my phone', 'power it off', 'free up some memory', 'wipe this phone'."
---

# Battery and power

Six of these eight need a privilege an ordinary install does not hold. The rule is to report what **is** observable rather than an empty result that reads as a measurement.

## Restart and power off

`deviceReboot` and `deviceShutdown` each demand two confirmations, the same shape as the wipe: a `confirm` boolean that must be true, and a literal phrase. The phrases are different on purpose — `RESTART THIS PHONE` for a restart, `POWER OFF THIS PHONE` for a shutdown — so a model cannot confirm one action with the other's words and power the phone off when somebody asked for a restart.

Both run `reboot` at shell UID, which means Shizuku. With no Shizuku the result names which of its three states it is in: not installed, not running, or not authorised. Those are three different actions and "the reboot failed" would leave the person with nothing to do.

**A command that was accepted is not a device that restarted.** The process that issued it does not outlive the call, so nothing here can see the boot. The note says the command was issued and no more, and I never tell somebody their phone restarted when all I know is that I asked it to.

## The process limit

`processLimit` GET reads `max_cached_processes` from the platform's own settings, which needs no permission. SET writes it at shell UID and then **reads it back**, because a settings write returning no error has not necessarily landed.

Lowering the limit frees memory and costs background apps their state; raising it keeps more alive and costs memory. A device that reports the value unchanged after a write is refusing the change, and that is what gets reported rather than the value I sent.

A read of `null` means the key is unset on that device, which is different from a limit of zero. I do not report an unset key as zero.

## The rule, stated once

When a privilege is absent there are two wrong answers and one right one. The wrong ones are refusing the whole capability, and returning an empty list. The right one is reporting what the phone will actually tell me, and naming precisely what it will not.

`batteryDetail` is the clearest case. The per-uid wakelock statistics are privileged. But charge, temperature, voltage, current, health and charging source are all readable with no permission at all, so refusing the whole tool would throw away true data to make a point.

## Battery

`batteryDetail` returns the full unprivileged reading always: percentage, temperature in tenths and in Celsius, voltage, current draw, charge and energy counters, health word, plugged word and status word.

Then it says which privileged parts are present and which are absent. A zero charge counter usually means the device does not report that property, not that the battery is empty, and the note says so rather than letting a model read a zero as a fact about the battery.

## Power state

`powerControl` STATUS needs no permission and reports the real interactive flag, power-save mode, doze state and charging status.

SCREEN_ON takes a bright wake lock and reports the platform's interactive flag afterwards, not the intent to wake. A wake lock that did not wake the display is the platform's answer, usually deep doze, and it is reported as that rather than as a permission problem.

SCREEN_OFF needs a privilege. Unless GURU is Device Owner, the honest answer is that an ordinary app cannot force the screen off and the person can lock it themselves.

## Logs

`readLogs` needs a system privilege, and even when it is held this build has no reader for the system log, because the platform's log-reading class is hidden.

So the result says exactly that: the empty list is the absence of a reader, not an empty log. Returning an empty list without that sentence would look like a quiet log, which is a different and false claim.

## Dumps, and the lesson from issue 44

`systemDump` runs a service's own dump. This is the tool that carries the hardest-won lesson in the directory.

**An exit code of zero proves the command ran, not that it was permitted.** Issue 44 shipped a dump that reported `exitCode 0` and `success: true` while every line of its output was a permission denial.

So the tool inspects its own output for the denial signature and reports failure when it finds one **even when the exit status is clean**. That inspection is the whole reason the tool exists. A dump that reports success over a page of denials is a lie a model will build on.

## Factory reset, and the gate

`factoryReset` erases everything and has no undo. It demands **two** confirmations:

- `confirm` must be true,
- `alsoConfirmText` must equal exactly `WIPE THIS PHONE`.

A boolean is something a model can pass by accident while reasoning about a schema. A literal phrase is not.

Both are checked **before** the permission guard, so a call lacking them is refused on its own terms rather than being reported as a permission problem.

Even with both, the wipe only happens when GURU is Device Owner. Otherwise the result says that, and names the Settings screen the person can use. `wipeIssued` is false in every case where nothing was wiped, and a test asserts a fully-confirmed reset on a non-owner device fails closed.

I do not offer this tool to solve a problem that has another answer.

## The mistakes I do not make

I do not refuse a whole reading because one part is privileged. I do not report a zero as a measurement when it means unsupported. I do not trust an exit code over the output's own words. I do not wipe anything without both confirmations, and I do not treat Device Owner as guaranteed.
