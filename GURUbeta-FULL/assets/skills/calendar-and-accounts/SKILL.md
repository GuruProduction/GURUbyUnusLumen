---
name: calendar-and-accounts
description: "Read the device's own calendars and events, create and change them, and add or remove accounts, keeping the phone's calendar store distinct from GURU's own event list."
allowed-tools: ["calendarRead", "calendarWrite", "accountsManage"]
when_to_use: "Use when my human asks what is in their calendar, to add, move or cancel an event, or about the accounts signed in on the phone. 'What's on tomorrow', 'put lunch in my calendar at one', 'move that meeting', 'cancel Friday', 'what accounts are on my phone', 'add my work email'."
---

# Calendar and accounts

I read and write the phone's own calendar store, which is a different database from GURU's own event list.

## The distinction that matters most

Android keeps a calendar database that every app can see. GURU also keeps its own event list. They have different ids, different rows and different meanings.

The bug this skill exists to prevent: writing to one and reporting as though the other changed. When I put an event in the device calendar, the person sees it in Google Calendar, Samsung Calendar and anything else reading the platform store. When I put it in GURU's list, they see it in GURU only. I say which one I touched, every time.

The tool description names the device store as the DEVICE store deliberately, and a test asserts that word stays in it.

## Reading

`calendarRead` returns the calendar list first, with each calendar's account, owner and colour, then the events that **start** inside the window. Set `fromMillis` and `toMillis` deliberately: the default is now to thirty days ahead, which is right for "what have I got coming up" and wrong for "what was on last Tuesday".

A window that ends before it starts is refused rather than queried, because an empty result from a backwards window is indistinguishable from an empty calendar and I would report the wrong one.

`query` filters on the event title, case-insensitively. `calendarId` narrows to one calendar, which is what I use when my human says "my work calendar" rather than "my calendar".

## Writing

`calendarWrite` covers CREATE, UPDATE and DELETE.

CREATE needs a calendar id, a title and a start. It also writes `EVENT_TIMEZONE`, because the calendar schema requires one and an absent value is a **silent insert failure** rather than an error. That is one of those details that costs an afternoon if it is missed.

After any insert or update the row is read back and `stored` carries what the provider actually holds. A provider can adjust a value: it may round a time, move an all-day event, or add its own defaults. Reporting what I sent would hide all of that.

UPDATE with no fields to change is refused, because a call that changes nothing and reports success teaches the person something false.

DELETE refuses without `confirm`. A removed event is not recoverable from the phone, so the confirmation is the guard rather than the permission.

## Accounts

`accountsManage` ADDS or REMOVES an account.

ADD launches the provider's own sign-in screen. GURU holds no authenticator for another provider, so it cannot complete that sign-in, and the result reports `handedToSystemFlow` true rather than an account added. The honest version of that sentence is "I have opened the sign-in for you", and that is the one I use.

REMOVE calls the manager and then **re-lists the accounts** to check. The platform can accept the removal and still leave the account behind, so `verifiedGone` is the answer and the return value is not.

`MANAGE_ACCOUNTS` is an install-tier permission: held from install with no dialog. A denial there would mean the manifest lost the line, which is a build fault rather than something the person can grant, so I never send anyone to a settings screen for it.

## Tokens

There is a token route through `AccountManager` and I do not use it. A credential printed into a conversation leaves the device inside a transcript, and no convenience is worth that. If a task seems to need it, the answer is that GURU cannot do it that way.

## The mistakes I do not make

I do not conflate the device calendar with GURU's own list. I do not report a write from the values I sent. I do not delete without confirm. I do not claim an account was added when a sign-in screen was handed over. I do not print a token.
