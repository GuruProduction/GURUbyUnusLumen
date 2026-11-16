---
name: quick_tiles
description: "The four quick settings tiles: talk to GURU, float GURU over another app, stop GURU's scheduled work, and start a task. What each one does, what its label claims, and how to tell a person to add one."
allowed-tools: ["deviceInfo", "permissionStatus", "permissionRoute", "launchApp"]
when_to_use: "Use when my human asks how to reach GURU without opening it, wants a shortcut in the notification shade, cannot find GURU's tile, or asks what a tile does. 'how do I get to you faster', 'add you to my quick settings', 'what is that tile', 'stop your heartbeat', 'keep you on top of my screen', 'where is the quick settings shortcut'."
---

# Quick settings tiles

Four tiles, one tap each, so GURU is reachable from the shade without opening the app.

## What the four are

**Talk to GURU** opens GURU on a conversation. It is a shortcut and nothing more: it has no running state, so its label never claims one, and it always reads as available.

**Float GURU** starts the floating orb, the chat bubble that sits on top of whatever app the person is using, and tapping it again closes it. This one is a real switch and its state is read from the running service, not remembered: closing the orb from its own notification makes the tile read off, because the service says so.

**Stop GURU** cancels the heartbeat chain. That is the part of GURU which acts on a timer when nobody is watching, so cancelling it stops everything GURU would have done on its own. It does not stop a conversation already in progress, and I say so rather than letting a panic button imply more than it does. The label afterwards says which happened: "Heartbeat stopped" when something was running, "Heartbeat is off" when nothing was.

**Add a task** opens GURU with the task form already up.

## What a label is allowed to claim

The label is the only text a person reads before tapping, so it is read from the phone every time the shade opens and after every tap. It is never remembered. The four states are ready, on, off and blocked:

- A blocked tile names what is missing, in the label itself. Float GURU without the overlay grant reads "GURU needs Display over other apps", because starting a bubble that cannot draw would look like it worked and do nothing.
- A tap on a blocked tile opens the screen that fixes it rather than failing silently.
- A tile that only opens the app never reads as running, because it has no running state to report.

## Telling a person how to add one

Android does not let an app add a tile to the shade on the person's behalf. The route is: open the notification shade, tap the pencil or edit control, find GURU's tiles in the list at the bottom, and drag one up into the active set. The tile appears in the edit list after the app is installed; if it does not, the shade needs reopening because it only rebuilds its list when it is opened.

## The mistakes I do not make

I do not tell somebody a tile is on when the thing behind it is off. I do not claim a tile can be added for them, because the platform does not allow it. I do not describe Stop GURU as stopping everything, because a conversation in flight is not a scheduled thing and is not touched.
