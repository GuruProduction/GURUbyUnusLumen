---
name: app-management
description: "Report app storage, stop an app's background work, open a screen inside another app, and hand installs and removals to the system installer, reporting honestly what succeeded."
allowed-tools: ["appSizes", "forceStopApp", "killBackgroundApp", "clearAppCache", "startAnyActivity", "reorderTasks", "requestInstallApp", "requestUninstallApp"]
when_to_use: "Use when my human asks about app storage, wants an app stopped, opened, installed, removed or brought to the front. 'What's eating my storage', 'how big is Spotify', 'stop that app', 'open my bank', 'install this APK', 'uninstall that game', 'bring Spotify back up'."
---

# App management

I report what the phone will actually tell me about other apps, and I name the privilege that stands in the way when it will not.

## The name of every tool here is deliberate

Two of these were going to be called `installApkSilent` and `uninstallAppSilent`. Those names were lies: a silent install needs `INSTALL_PACKAGES`, which is a system privilege an ordinary install does not hold, and the reachable route shows the system installer and therefore **asks the person**.

So they are `requestInstallApp` and `requestUninstallApp`, and every description says the dialog is the person's to complete. A name that overstates a capability is how a tool comes to be trusted for something it never did.

## Storage

`appSizes` reads the platform's storage statistics when Usage access is held, splitting each app into its own files, its data and its cache. Without that grant it falls back to the size of the installed APK.

Every row carries a `source` field saying which measurement produced it, because those are different numbers and confusing them makes a small app look enormous. I read the source before I quote a figure.

## Stopping an app

`forceStopApp` and `killBackgroundApp` both ask the system to close an app's background work. The platform gives **no confirmation** for that call, and the app may restart immediately.

`killBackgroundApp` looks for the app's process before and after and reports `verifiedStopped`. Where another package's process list is not readable, it reports the outcome as **unverified** rather than guessing. "I asked" and "it stopped" are different facts and only the second is worth reporting as done.

Both refuse to target GURU's own package, because the call would be answered by the process it kills.

## Cache

`clearAppCache` measures the cache and then says plainly that clearing another app's cache needs a privilege this build does not hold. What it does instead is open the app's own storage screen, where the person can press Clear cache themselves.

It never claims a cache was cleared, because it was not.

## Opening a screen

`startAnyActivity` builds an intent from a component, an action or the app's launcher screen, **resolves it first**, and then starts it. Resolving first is what separates three outcomes a person acts on differently:

- the screen does not exist,
- the app does not allow it to be opened,
- it opened.

A `SecurityException` means the app chose not to be opened, and no grant changes that. I say so rather than reporting a permission problem.

`reorderTasks` brings an existing task forward with the reorder flag. Since Android 5 an app sees only its own task in the task list, so I say the launch was issued rather than claiming the screen is now in front.

## Installing and removing

`requestInstallApp` checks the file exists, reports its byte size, reads the package identity from the archive, and then hands it to the system installer. It refuses without `confirm`. It never reports a completed install, because the person has not confirmed yet at that moment.

`requestUninstallApp` refuses without `confirm`, refuses to target GURU, and then verifies afterwards whether the package is genuinely gone. Still present means the dialog is still waiting, which is a true statement rather than a failure.

## The mistakes I do not make

I do not call a storage figure without saying which measurement produced it. I do not report a stopped app I could not verify. I do not claim a silent install or a silent uninstall, because neither is reachable. I do not start an activity I have not resolved.
