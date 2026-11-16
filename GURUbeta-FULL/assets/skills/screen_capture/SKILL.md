---
name: screen_capture
description: "See the screen and record it: a picture of what is on it right now, and an MP4 of it over time. One consent covers both, and both refuse honestly when it is missing."
allowed-tools: ["takeScreenshot", "recordScreen", "captureScreen", "requestScreenCapturePermission", "getScreenState", "findElements"]
when_to_use: "Use when my human asks what is on their screen, wants a screenshot, wants me to look at something they are looking at, or wants the screen recorded. 'what is on my screen', 'take a screenshot', 'look at this', 'record my screen', 'show me what happened', 'can you see this'."
---

# Seeing and recording the screen

Two capabilities, one mechanism: the screen capture session my human consents to when I ask.

## The consent comes first, and it is a dialog

Screen capture is not a permission the app holds. Android shows a dialog, the person taps Allow, and that grant lives for one session. `requestScreenCapturePermission` asks for it: it sends the request, the app shows the dialog, and the answer comes back into the same session every capture uses.

So the order is always: request, wait for the person to tap Allow, then capture. A capture without it is refused, and the refusal says to request it first, which is a real instruction rather than a failure.

A consent can end on its own. The phone can revoke it when the screen locks, when the app goes to the background on a modern Android, or when the person stops it from the notification. That is why the refusal exists rather than a crash: the next attempt asks again.

## A picture

`takeScreenshot` returns the file path and its size. `captureScreen` does the same job, through the same session, and takes a filename prefix.

What a refusal means, in the three forms it comes in: no consent means the dialog has not been allowed, a session that ended means it needs allowing again, and no frame within the wait means the projection is live and the screen produced nothing. That last one happens when the screen is off or between configurations, and it is not a permission problem.

## A recording

`recordScreen` with action start begins one, and stop finishes it and returns the path and the byte count. The duration passed to start is a ceiling: the recording stops itself there, and stop collects it early.

The file is a real MP4 because the platform's own encoder writes it and a muxer finalises the index when it stops. A recording that captured nothing is reported as a failure with the empty file removed. I never hand back a path to a zero-byte file and call it a recording, which is the defect this tool was rebuilt to remove.

## What this cannot see

A secure window, such as a banking app or a password manager, is blacked out by the platform. Screen recording cannot capture DRM-protected video either. Both are by design and neither is a fault in the capture.

## The mistakes I do not make

I do not report a capture that failed as an empty result. I do not tell somebody to use root or ADB for a screenshot, because the mechanism never needed either and saying so once sent the whole capability down a blind alley. I do not claim a recording exists without reading its byte count. I do not record somebody's screen without them asking, because a recording is a durable artefact of everything on the display.
