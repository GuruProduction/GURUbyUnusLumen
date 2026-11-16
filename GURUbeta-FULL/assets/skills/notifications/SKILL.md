---
name: notifications
description: "Read notifications as they arrive, wait for one, dismiss them, and press their buttons. Also reads and changes Do Not Disturb, and posts GURU's own notifications with an honest confirmation that they can be seen."
allowed-tools: ["notificationStream", "notificationWait", "notificationDismiss", "notificationAction", "getActiveNotifications", "getNotification", "dismissNotification", "dismissAllNotifications", "notify", "dndControl"]
when_to_use: "Use when my human asks what has come in, what they missed, to wait for a message or a code, to clear their notifications, to reply or mark something read from a notification, to silence the phone, or to be told when something finishes. 'what did I miss', 'wait for the code', 'anything come in from Sam', 'clear my notifications', 'reply to that', 'silence my phone', 'am I on do not disturb', 'notify me when it's done'."
---

# Notifications

I read what has actually arrived, I wait for what has not arrived yet, and I never report a notification as delivered or dismissed without reading the platform back.

## The two sets, and why both exist

`getActiveNotifications` and its neighbours read the **shade**: what the person can see and swipe away right now. `notificationStream` reads the **arrivals**: everything that came through during this app run, including what has already been dismissed.

Those are different questions. "What is on my screen" is the shade. "What did I miss" is the stream. When one answers nothing, the other is usually the tool that has the answer, and I reach for it rather than telling somebody nothing arrived.

Both need Notification access, which is a Settings toggle rather than a dialog. A refusal names that screen. The one state worth recognising: **enabled but not bound.** The switch is on and the platform has not connected the service yet, usually for a few seconds after a reboot or an update. That is not a grant problem, there is nothing to allow, and telling somebody to go to Settings for it wastes their time. The result says which state it is in words.

## Reading the stream

`notificationStream` returns arrivals newest last, each with the app's real label, the title, the body, the category, and the buttons it carries. The app label is the app's own name from the package manager, not the sender's name, so a message from a friend reads as WhatsApp with that friend's title rather than as the friend with no app.

An expanded notification's full text lives in `bigText`, because the collapsed line is often just "1 new message". I read `bigText` when it is there and fall back to `text`.

The list is a bounded ring in memory. It survives neither a restart nor a long day, and every result says so rather than letting a short list read as a quiet day.

## Waiting

`notificationWait` blocks until something matching arrives or the window expires. Use it instead of polling when the point is to react to something as it happens: a code arriving, a message from one person, a delivery confirmation.

A timeout means nothing arrived in that window. It does not mean the listener is off and it does not mean the grant is missing, and I say which one it is rather than letting a bare failure suggest the wrong fix.

I filter by `packageName` when I know which app, and by `titleContains` when I know who. A filter that never matches will spend the whole window doing nothing, so a narrow filter wants a generous window or none.

## Dismissing and acting

`notificationDismiss` takes the platform key from the stream. `dismissNotification` takes a package name and an id from the shade. Both read the shade back afterwards, because Android refuses to remove an ongoing notification, a system alert, or the header drawn over a group, and a tool that reported success there would be lying about the person's own screen.

`notificationAction` presses one of a notification's buttons. The index comes from that notification's `actions` list, and the intent belongs to the app that posted it, so pressing Reply sends the reply through the app's own path rather than a copy of it. Two limits, stated plainly rather than discovered later: a reply button that needs text cannot be completed by a press alone, so I say that rather than claiming the reply was sent, and an action only exists while the notification is live in the shade.

I never press a button on the person's behalf when the button does something they have not asked for. Reply, mark as read and archive are ordinary. A button that spends money, sends to a group or deletes something is their call.

## Posting, and the silent failure

On modern Android a notification posted while notifications are disabled **fails silently**: the call returns, nothing happens, and nobody sees anything. So `notify` posts, then reads `areNotificationsEnabled` from the platform, and reads the channel back. When notifications are off, the note says in as many words that the notification was handed over and will not be seen.

I never report a notification posted from the call's return value alone. That is the exact lie this tool was built to avoid.

## Do Not Disturb, and the half that needs nothing

`dndControl` GET reads the interruption filter and needs **no permission whatsoever**. SET needs the Do Not Disturb access toggle.

That is why the tool declares no permission: the dispatcher refuses when any declared permission is missing, so declaring the access would make the read path refuse for everyone without the toggle. A true reading is worth more than a uniform declaration.

The modes: ALL and OFF both allow everything, PRIORITY allows priority contacts only, ALARMS allows alarms only, NONE blocks everything. `NONE` is what "silence my phone" means and I do not confuse it with ALL. After a SET I read the filter back and report what the platform holds, not what I asked for.

## The mistakes I do not make

I do not report a notification as delivered when notifications are disabled. I do not report a dismissal that the platform refused. I do not treat a quiet window as a refused read, or a dead listener as an empty result. I do not send somebody to a Settings screen when the switch is already on. I do not press a button that does something irreversible without being asked to.
