---
name: device_admin
description: "What Device Owner means for GURU: one silent grant route, no management powers, and why ownership is not the same thing as being an administrator."
allowed-tools: ["permissionStatus", "permissionRoute", "permissionRequest", "getDeviceInfo"]
when_to_use: "Use when my human asks whether GURU is an administrator, what Device Owner does, why a permission cannot be granted, or whether GURU can manage the phone. 'are you an admin', 'what does device owner mean', 'can you grant yourself that', 'why can't you change that setting', 'can you wipe my phone'."
---

# Device administration

Two different things share almost the same name, and the difference decides what GURU can do.

## Administrator is not ownership

A **device administrator** is an app the person activated in Settings. It can use whatever policy flags its declaration lists, such as lock, wipe or password rules.

A **Device Owner** is a provisioning role, set when the phone is first set up or through an owner-provisioned flow. It is an administrator plus authority over the device.

GURU wants ownership for exactly one reason and none of the others, and the declaration reflects that: the policy list is deliberately empty. The only thing ownership buys here is `setPermissionGrantState`, which needs no policy flag at all.

Declaring powers the app does not use would be over-reach, and it makes every refusal look personal rather than structural.

## What ownership actually buys

One thing: silent runtime grants. When GURU is Device Owner it can grant a runtime permission through `DevicePolicyManager` with no dialog, which is the single biggest quality-of-life difference in the whole permission route. The grant orchestrator takes that path automatically once ownership is true.

An ordinary install gets the same permission through a dialog instead. Ownership changes the route, not the reach.

## What it does not buy

Signature permissions. A Device Owner cannot grant those, because `setPermissionGrantState` handles runtime permissions only. The privileged tier in the catalogue reports that plainly, and it names the shell UID as the one route that applies.

So ownership is not a master key, and I never describe it as one. If somebody asks whether being an administrator lets GURU do anything it wants, the answer is no, and the specific limit is the permission tier rather than the ownership.

## Wiping the phone

With ownership, `factoryReset` can issue a wipe through `wipeData`, which is why that tool demands a confirm flag and a literal phrase rather than a single yes. Without ownership it reports that plainly and names the Settings screen the person can use themselves.

I do not offer a wipe to solve a problem that has another answer.

## How to tell which state the phone is in

`permissionStatus` reads the tier and the state for any permission, so a Device Owner path shows as an available route and an ordinary install shows a dialog or a refusal with the reason. `permissionRoute` explains without acting. That is how I answer "why can't you just grant yourself this" with the actual reason rather than a guess.

## The mistakes I do not make

I do not describe Device Owner as full control. I do not say an administrator can grant a signature permission it cannot. I do not offer a wipe as a solution, and I do not claim ownership is held without reading it.
