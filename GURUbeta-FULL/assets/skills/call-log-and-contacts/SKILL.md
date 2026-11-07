---
name: call-log-and-contacts
description: "Read the phone call log, search the address book, and create, change or remove contacts and account entries, refusing honestly when a grant is missing."
allowed-tools: ["callLogRead", "callLogWrite", "contactsList", "contactCreate", "contactsUpdate", "contactsDelete", "contactPhoto", "accountsList"]
when_to_use: "Use when my human asks who called, what a number was, when someone last rang, or anything about their contacts or the accounts on the phone. 'Who called me this morning', 'what's Dave's number', 'save this number as Ali', 'when did I last speak to my mum', 'what accounts are on my phone', 'delete that contact', 'what does that contact's photo look like'."
---

# Call log and contacts

I read the phone's own record of who called and who is known, and I refuse honestly when the person has not granted the store.

## Why the refusal matters more than the data

There are three different truths behind an empty result, and a person acts differently on each:

1. The Call log or Contacts grant is missing, so I never saw the store.
2. The grant is held and the query ran, and there genuinely are no matching rows.
3. The provider did not answer at all, which is a fault in the phone rather than an absence.

Every tool here refuses through the permission catalogue when the grant is what stopped it, so the sentence names the grant and the Settings screen. I never present a refusal as an empty list, because "no calls matched" and "I am not allowed to see your calls" are different sentences and only one of them is true.

## Reading the log

`callLogRead` returns the most recent entries with the number, any cached contact name, the type as a word, the timestamp, the duration and the SIM. Type is one of INCOMING, OUTGOING, MISSED, REJECTED, VOICEMAIL, BLOCKED or UNKNOWN, mapped from the platform constant rather than guessed.

Set `sinceMillis` when my human names a time, and `number` when they name a person or a digit string. Set `limit` deliberately rather than leaving it at fifty when they asked for "the last few".

## Searching the address book

`contactsList` matches on display name or phone number, case-insensitively. Use `withPhoneOnly` when I need someone callable and `withEmailOnly` when I need someone to write to. Each row carries its id, which is the handle every write needs.

Ids are platform ids: they belong to the device's contacts provider, not to GURU. I never invent one and I never reuse one from memory, because a stale id points at whoever holds that number now.

## Writing, and why each of these is gated

`contactCreate` needs both the read and the write grant, because deciding whether somebody already exists means reading the store before writing to it. With `updateIfExists` true it adds to the matching contact rather than creating a duplicate, and it reports which fields genuinely landed by reading the contact back.

`contactsUpdate` reads the contact before and after and reports the fields that actually changed. A write that reports the values it sent is reporting an intention, so `changedFields` is the only answer worth giving.

`contactsDelete` is irreversible and refuses unless `confirm` is true. I say so plainly rather than failing silently, because a delete that quietly did nothing is worse than one that says why.

`contactPhoto` returns the byte size only when given no path, because binary image data does not belong in a conversation. Given a path it writes the file and returns the size measured on disk afterwards.

`accountsList` returns account names and types. Account names are visible to any app holding the Contacts group, which I state rather than hide, because it is a fact about the phone that matters.

## The mistakes I do not make

I do not ask for a contact id when I can search by name first. I do not write a call log entry unless my human asked me to record a call, because that entry appears in their dialler looking exactly like a real call. I do not delete without an explicit confirm in the conversation, and I do not treat a stale id from an earlier session as still valid.
