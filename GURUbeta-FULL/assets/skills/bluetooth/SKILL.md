---
name: bluetooth
description: "Report the Bluetooth radio's real state, scan for classic and Low Energy devices, pair and unpair, and advertise GURU to nearby devices, saying plainly which of those the platform allows."
allowed-tools: ["bluetoothAdapter", "bluetoothScan", "bluetoothList", "bleScan", "bluetoothPair", "bluetoothUnpair", "bluetoothAdvertise"]
when_to_use: "Use when my human asks about Bluetooth, whether it is on, to switch it on, to find a device, to pair something, to forget a pairing, or what is connected. 'is bluetooth on', 'turn on bluetooth', 'what bluetooth devices are near', 'pair my headphones', 'forget that speaker', 'find my tracker'."
---

# Bluetooth

Five capabilities and one rule that matters more than the others: whether the answer is about the radio, the permission, or the platform refusing.

## The three answers, kept apart

This phone has no radio, the radio is off, and this app is not allowed to use it. Those need three different responses and only the last is a permission. `bluetoothAdapter` STATUS answers the first two with no permission at all, which is why it runs before any check rather than after.

## Switching it on

`bluetoothAdapter` ON and OFF ask the platform to change the state, and since Android 13 it always declines for an ordinary app: the permission that used to allow it became signature-only, so no dialog and no setting grants it.

So the honest answer is that Android will not let an app do this, and the quick settings tile is the switch the person can reach. I do not send anybody to a permission screen for it, because the screen does not have it.

When the platform does accept the request, I read the adapter state back rather than trusting the call's return. A toggle is asynchronous and can be blocked by policy or a connected device, so the state is the answer.

## Scanning

`bluetoothScan` is a classic discovery sweep: it returns whatever answered, once, with one signal reading each. `bluetoothList` reports the paired devices. `bleScan` answers a different question: it records the signal strength of every advertisement over a window, so each device carries a real history and a mean rather than one arbitrary number, and an optional service UUID narrows it to one sensor or beacon.

An empty scan is a fact about the room, and a filtered-out room looks identical to an empty one. I remove the filter before concluding anything about the devices around me.

## Pairing

`bluetoothPair` starts bonding and polls the bond state, because pairing is asynchronous and usually needs the person to accept a prompt on the phone. A result still bonding after the wait says so rather than claiming success. `bluetoothUnpair` does the same in reverse and reads the state back, because removing a bond has no public API and a refused removal reads the same as a successful one from the outside.

## Advertising

`bluetoothAdvertise` announces a service UUID for a bounded window and then stops. It reports whether the platform's own callback confirmed the start, because a request accepted and then silently refused by the controller looks identical from outside. Whether a second device saw it can only be answered by that device.

## The mistakes I do not make

I do not report Bluetooth off as a permission problem, or the reverse. I do not claim a toggle landed without reading the adapter back. I do not tell somebody to grant a permission Android no longer hands out. I do not present an empty filtered scan as an empty room.
