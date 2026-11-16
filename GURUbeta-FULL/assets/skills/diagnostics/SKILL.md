---
name: diagnostics
description: "Read a system service's own dump, and ask the device for its vendor diagnostics and factory test entry, reporting absence as a fact about the device rather than a failure."
allowed-tools: ["systemDump", "readLogs", "diagnosticMode", "factoryTest"]
when_to_use: "Use when my human asks me to diagnose the phone, read a system service, run diagnostics, or check whether their device has vendor tools. 'why is this broken', 'dump the battery service', 'run diagnostics', 'does my phone have a factory test', 'what does the system say about this'."
---

# Diagnostics

Three of these four need a privilege an ordinary install does not hold, and two of them need a capability the device probably does not have at all. The job is to say which.

## The three answers

Every diagnostic result is one of three things, and they are not interchangeable:

**It ran.** The device produced real output and nothing in it says refused. That is data.

**The device does not carry it.** There is no such service or binary. No grant, no setting and no setup changes that, because the capability is not there. The sentence says so plainly and sends nobody anywhere, because sending somebody hunting for a setting that does not exist is worse than saying nothing.

**The platform declined.** The command exists and was refused. That is a privilege this build does not hold. Also not something the person can turn on, but a different sentence from the first, because the reason is different.

I never report the first as a failure, and I never report the second or third as if the person had missed a step.

## The output decides, never the exit code

This is the lesson issue 44 was filed for. A diagnostic command routinely exits zero while printing a permission denial, and a shell prints 127 for a missing binary, which is the same number some refusals use. So the classification is made from the output, and the exit code is reported as evidence rather than as the verdict.

A refusal outranks an absence when both appear in one result, because a command that exists and refuses may also mention a file it could not open, and calling that absence would tell somebody their phone lacks a capability it actually has.

Silence with an unfamiliar exit code is reported as unclear rather than as a run, because claiming a run would claim data that is not there.

## The two vendor tools

`diagnosticMode` looks for the vendor diagnostic service. There is no single command for this across manufacturers, so it tries the names that exist on vendor builds and reports absence honestly when none is present.

`factoryTest` asks for the vendor factory test entry. On nearly every retail phone this is unobtainable even with the shell UID, and the honest answer is that the device does not expose it rather than that the tool failed. Where a vendor build does expose it, the launch is issued and the result says the launch happened rather than that a test suite is running, because nothing can read back what is on screen.

Both need Shizuku even to find out whether the capability exists, so without it they say the shell is missing rather than guessing about the device.

## The dump

`systemDump` runs a service's own dump through `dumpsys`, which is available to an ordinary app but often prints a denial instead of the state. It inspects its own output for that denial and reports failure when it finds one even if the command exited zero, because a dump that reports success over a page of denials is a lie a model will build on.

`readLogs` reports that this build carries no reader for the system log at all, so an empty list is the absence of a reader rather than an empty log.

## The mistakes I do not make

I do not trust an exit code over the output's words. I do not tell somebody their device lacks something it merely refuses. I do not send them to a Settings screen for a vendor capability that no screen grants. I do not claim a factory test is running when all I did was ask for it.
