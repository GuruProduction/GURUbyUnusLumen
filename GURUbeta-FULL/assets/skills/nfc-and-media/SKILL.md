---
name: nfc-and-media
description: "Read and prepare NFC tags and query the phone's photos, videos and audio, reporting the partial-photo-access trap so a short list is never mistaken for a small library."
allowed-tools: ["mediaLibrary", "nfcRead", "nfcWrite", "nfcTransactions"]
when_to_use: "Use when my human asks about photos, videos or music on the phone, or about NFC tags. 'Find my holiday photos', 'what videos do I have', 'how many pictures are on here', 'play some music from my phone', 'read this tag', 'write a tag', 'does this phone do NFC'."
---

# NFC and media

Two capabilities with two very different limits: one physical, one a permission subtlety that produces confident wrong numbers.

## The media trap that matters most

On Android 14 a person can grant access to **selected photos only**. MediaStore then returns just those items.

A tool that reports its own count as the answer tells the person their library is twelve pictures when it holds three thousand. That is the same shape as the network bugs: a short list that reads as a complete measurement.

So `mediaLibrary` reports three things together:

- `returnedCount`, how many came back,
- `totalMatching`, the real total measured with the same selection,
- `partialAccessGranted`, whether Android granted only the selection.

The note says which grant was in force. I read all three before I say anything about how much is on the phone.

## Querying media

`mediaLibrary` takes a kind: IMAGE, VIDEO or AUDIO. It queries the right MediaStore collection for that kind and returns each item's name, content uri, size, and either dimensions or duration and artist.

The content uri is what other tools accept, so I pass that rather than a file path.

Filter with `sinceMillis` for "this week" and `minSizeBytes` to skip thumbnails. The date filter divides by a thousand because MediaStore stores **seconds**, and comparing a seconds value against a millisecond bound is the difference between a working filter and one that matches everything.

## The one grant rule

Only the grant matching the requested kind is guarded. Demanding photos, video and audio together would refuse a person who granted photos and not music, which makes a working tool look broken.

Partial visual access is detected by checking whether the user-selected grant is held while the full image grant is not, and that state is reported rather than guessed at.

## NFC, and the physical limit

There is a limit here that no permission can cross: reading a tag needs a **physical tag** held to the phone while a foreground reader session is armed. A background tool can create neither.

So `nfcRead` reports:

- whether the phone has an NFC radio at all,
- whether NFC is switched on in Settings,
- the tags this app has actually seen since it started.

When nothing has been presented, the answer is exactly that: no tag has been presented since this process started. It is a true measurement, not a failed read.

Hardware absence is checked before any grant, because no grant fixes a phone with no radio.

## Writing a tag

`nfcWrite` builds the NDEF text record, reports its byte size, and says the record is **prepared**, not written.

There is no circumstance in which it reports a write as done, because no write can happen without a tag in the field. "The message is ready and waiting" is the true statement, and I use it.

## The transaction stream

`nfcTransactions` reports the sightings GURU has actually recorded, and states plainly that the raw contactless transaction stream needs a system privilege this build does not hold.

The reader-mode path covers the practical case and **is** real data, so the tool returns it rather than refusing. An empty list there means no tag has been presented since the process started, never that the read was blocked.

## The session flag, stated honestly

The platform exposes no way to ask whether a reader session is armed. So `sessionArmed` means only that a tag **has** been delivered since this process started, and every note says the true state is unknown rather than claiming it either way.

An earlier draft of this computed the flag as a comparison that was true by construction, which would have put a confident yes in front of a person while looking exactly like a measurement. It was caught on read-back and removed.

## The mistakes I do not make

I do not report a returned count as a library total. I do not guard three media grants when the query needs one. I do not claim a tag was written, or a session is armed, without a tag having arrived. I do not treat no NFC radio as a permission problem.
