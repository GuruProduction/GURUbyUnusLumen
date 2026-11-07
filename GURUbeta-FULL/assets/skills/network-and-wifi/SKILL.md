---
name: network-and-wifi
description: "Report the phone's connection, the Wi-Fi link, nearby networks, VPN and IPsec state, and hold the multicast lock discovery needs, distinguishing a refused read from an empty one."
allowed-tools: ["networkState", "wifiInfo", "wifiSetEnabled", "networkSetEnabled", "wifiNearby", "vpnControl", "ipsecTunnels", "multicastLock"]
when_to_use: "Use when my human asks about the internet, Wi-Fi, mobile data, signal, which network they are on, what networks are around, whether a VPN is running, or when any network tool returns nothing and I need to know why. 'Am I online', 'what wifi am I on', 'why is it so slow', 'what networks can you see', 'is my VPN on', 'turn wifi off', 'why did the TV scan find nothing'."
---

# Network and Wi-Fi

I report what the phone's connection actually is, and I never dress a refused read as an empty network.

## The three facts behind an empty result

This is the whole skill, and every tool here keeps it:

1. **The grant is missing.** The sentence names the permission and the Settings screen.
2. **The platform refused.** Wi-Fi toggling is blocked to ordinary apps since Android 10, and the data toggle has no public route at all.
3. **The read ran and genuinely found nothing.** A scan really can see zero networks, and a phone really can be offline.

Conflating those is the failure this whole domain exists to remove. Six separate bugs in this project's network layer had that one shape.

## Reading the connection

`networkState` needs no dialog and is the first thing I reach for. It reports the transports that are up, whether the connection is metered, whether it is a VPN, whether Android has **validated** that the network reaches the internet, and the link speeds and interface name.

Validated versus associated is the distinction worth knowing: an associated network can still fail every request, and `validatedInternet` false is the honest answer to "am I online".

It never reports the Wi-Fi network name, because that needs a location grant on every modern version. `wifiInfo` is where the name lives.

## The Wi-Fi link

`wifiInfo` reports the network name, access point, signal in dBm, link speed, frequency, band and the device's address on the network.

Two limits I state rather than hide: the name reads as unavailable without **precise** location, and starting a scan needs that grant too and is throttled to four scans in two minutes per app. So if my human asks for scans without the grant, the tool refuses and names the grant, because an empty list would read as "there are no networks nearby" when there plainly are.

## The two toggles the platform refuses

`wifiSetEnabled` attempts the call, catches the refusal, waits up to two seconds and reads the state back. It reports what the platform did, not what was asked. On any modern install the honest answer is that an ordinary app cannot change Wi-Fi, plus the Settings screen the person can use.

`networkSetEnabled` reports the same way and says plainly that mobile data has no public toggle at all. I do not claim a change I did not observe, and a fired intent is not an observation.

## Scanning for networks

`wifiNearby` guards the permission that applies to the Android version: **Nearby devices** from Android 13, **location** below it. It says which one it used, because sending somebody to the wrong screen is worse than saying nothing.

Android allows four scans in two minutes, so `scanStarted` false means these are the last scan's results rather than fresh ones. I say that instead of implying a fresh sweep.

## VPN and IPsec

`vpnControl` STATUS needs no permission and reports whether a VPN is carrying traffic plus the tunnel interface. PREPARE reports whether consent is needed and whether this app declares a service that consent could act on.

Not being able to control a VPN and not being able to see one are separate facts, and the result keeps them separate. `ipsecTunnels` refuses rather than returning an empty list, because the system-wide tunnel list is not readable from an ordinary app and there is no substitute.

## The multicast lock, and why discovery fails without it

This is the one that explains most "the network scan found nothing" reports. Without a multicast lock the Wi-Fi driver **drops** mDNS and SSDP replies, so a discovery tool sees an empty network that is not empty.

So the order for any discovery job is: `multicastLock` with ACQUIRE, then the scan. If something has already returned nothing, the lock is the first thing I check and not the last.

The platform offers no way to ask whether a lock is held, so `held` in the result is GURU's own record rather than a platform report, and the lock dies with the process. I say that rather than implying a platform confirmation.

## The mistakes I do not make

I do not report an empty scan as an absence of networks. I do not claim a toggle worked because the call returned. I do not ask for precise location when a network read does not need it. I do not run any of these on the main thread, and I do not blame the network for a missing grant.
