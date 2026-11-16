---
name: ui_automation
description: "Drive any screen: tap, swipe, type and press keys, either through the accessibility service or through the input dispatcher when accessibility is switched off."
allowed-tools: ["injectInput", "tapScreen", "swipe", "typeText", "pressButton", "clickText", "getScreenState", "findElements", "takeScreenshot", "captureScreen"]
when_to_use: "Use when my human asks me to tap something, scroll, type into a field, press a key, or drive another app. 'tap that', 'press back', 'scroll down', 'type this in', 'open the menu', 'click the button', 'can you do it on screen for me'."
---

# Driving the screen

Two ways to press something, and which one is available is the first thing to find out.

## The two paths

**Accessibility** is richer: it can read the screen's own element tree, find a button by its text, and dispatch gestures in one held call. It needs the accessibility service switched on in Settings, which a person has to do by hand.

**Input injection** needs no accessibility at all. It sends the event straight to the input dispatcher at shell UID through Shizuku. It is the fallback that makes driving possible for someone who will not enable accessibility, which is the state of the reference device.

So when a tap does nothing because accessibility is off, `injectInput` is the tool that still works rather than a dead end.

## What injection costs, stated honestly

`input` is one process per event. A tap costs a process spawn, tens of milliseconds rather than the few a held gesture dispatch costs. A long sequence is noticeably slower through this path, and that is the trade for not needing accessibility.

## What injection cannot do

It cannot see the screen. It cannot tell whether the tap hit the intended thing, so it reports that the event was **dispatched** and never that anything changed. After driving the screen this way, take a screenshot to find out what actually happened. Claiming a screen changed on a dispatched tap is how a driver reports success while pressing the wrong thing.

## The traps, each of which is a test

A tap outside the screen is refused rather than sent, because `input` accepts it and silently does nothing.

A negative swipe duration is refused rather than clamped, because the command reads it as an unsigned number and would hang for roughly four billion milliseconds.

Text is quoted so that spaces, quotes and percent signs survive. A space becomes `%s`; a literal percent is escaped; an embedded single quote is closed, escaped and reopened. Getting any of those wrong types a truncated entry with a successful exit code, which reads as the tool working when it is not.

A newline cannot be typed at all, so the refusal says to type the text and then press the enter key.

Key names are a fixed list rather than raw keycodes, so a typo is a refusal listing the valid names instead of a command that fails on the device where nobody sees it.

## Coordinates

Never invent them. `getScreenState` gives the real size, `findElements` gives the bounds of a clickable thing by its text, and a screenshot confirms where things are. A tap at a guessed coordinate is a tap that either does nothing or does the wrong thing, and neither is visible from the result.

## The mistakes I do not make

I do not report a dispatched event as a screen change. I do not send an off-screen tap. I do not pass an arbitrary keycode through. I do not type text through a shell without escaping it. I do not tell somebody to enable accessibility when injection would work.
