---
name: usage-and-notifications
description: "Report which apps have been in the foreground, post a notification and confirm the person can actually see it, and read or change Do Not Disturb."
allowed-tools: ["appUsage", "notify", "dndControl"]
when_to_use: "Use when my human asks what they have been using, how long they spent on something, wants a notification, or wants the phone silenced or unsilenced. 'What have I been on today', 'how long was I on TikTok', 'notify me when it's done', 'silence my phone', 'am I on do not disturb', 'turn do not disturb off'."
---

# Usage and notifications

I report what has actually been in the foreground, and I confirm a notification can be seen rather than assuming it can.

## Usage, and why this is the right route

`appUsage` reads the platform's usage statistics over a window. It is the correct **unprivileged** way to answer "what have I been using": the task list cannot be read for other apps without a system privilege, and usage statistics cover the practical case.

It needs the Usage access grant, which is a Settings toggle rather than a dialog. That distinction matters when I tell somebody how to fix it.

Two empty results that are not the same thing:

1. The window had no recorded foreground activity.
2. The statistics store has no history yet, which happens on a freshly installed build.

Both are stated in the note rather than left to be discovered, and neither is presented as a granted read that found nothing.

A window that ends before it starts is refused rather than queried, because an empty result from a backwards window looks exactly like a quiet day.

Zero foreground time is filtered out. An app that is installed and never brought forward is not activity worth reporting.

## Notifications, and the silent failure

This is the part worth remembering. On modern Android a notification posted while notifications are disabled **fails silently**: the call returns, nothing happens, and the person sees nothing.

So `notify` posts, then reads `areNotificationsEnabled` from the platform and reports it, and reads the channel back to confirm it exists. When notifications are off, the note says in as many words that the notification was handed over and will not be seen.

I never report a notification posted from the post call's return value alone. That is the exact lie this tool was built to avoid.

Channel creation is idempotent on the platform, so creating the same id twice is correct rather than a mistake. Below Android 8 there are no channels at all.

## Do Not Disturb, and the half that needs nothing

`dndControl` GET reads the interruption filter and needs **no permission whatsoever**. SET needs the Do Not Disturb access toggle.

That is why this tool declares no permission: the dispatcher refuses when any declared permission is missing, so declaring the access would make the read path refuse for everyone without the toggle. A true reading is worth more than a uniform declaration.

The modes: ALL and OFF both allow everything, PRIORITY allows priority contacts only, ALARMS allows alarms only, NONE blocks everything. `NONE` is what "silence my phone" means, and I do not confuse it with ALL.

After a SET I read the filter back and report what the platform holds. A value that did not move is reported as unchanged.

Not being able to change the state and not being able to see it are separate problems, and the result keeps them separate so a working read never looks broken.

## The mistakes I do not make

I do not report a notification as delivered when notifications are disabled. I do not treat a quiet window as a refused read, or the reverse. I do not guard the Do Not Disturb read behind the write's permission. I do not report a filter change I did not read back.
