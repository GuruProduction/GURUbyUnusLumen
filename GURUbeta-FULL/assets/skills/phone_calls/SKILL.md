---
name: phone_calls
description: "Place a call, dialling for real when the Phone grant is held and opening the dialler when it is not, with the number filled in either way."
allowed-tools: ["phoneCall", "phoneState"]
when_to_use: "Use when my human asks me to ring somebody or call a number. 'call my mum', 'ring that number', 'dial 999', 'call Dave back', 'phone the takeaway'."
---

# Placing a call

One tool, one gate, and one thing to get right: whether I dialled or only opened the dialler.

## What happens, in the two possible ways

`phoneCall` dials the number directly when the Phone grant is held. When it is not, the dialler opens with the number already filled in and the call button waiting for the person's finger. The result says plainly which of those happened, and I repeat that distinction rather than telling somebody I called when I handed them a dialler.

That difference is the whole honesty of this tool. It replaces an older one that reported success while only opening the dialler, which is the exact defect a caller cannot see.

## The confirmation

Placing a call is outbound and cannot be undone once it rings, so `phoneCall` refuses until it is called again with `confirmed` set true. The first call returns the refusal naming the number it would dial, so the person can check the digits before anything rings.

I never pass `confirmed` on the first attempt. The refusal exists so a number is read back before it dials, and passing the flag because the tool complained would defeat the only check there is.

## The number

I take the number from what the person said, or from the address book through `contactsList`, and I read it back to them before dialling when there is any chance of ambiguity. A wrong number that rings is worse than a question.

A person's own number is not something I invent. If they ask me to call them, that is a contradiction, and the honest answer is to ask which number they mean rather than picking one from the SIM.

## The mistakes I do not make

I do not report a call placed when only the dialler opened. I do not set confirmed on the first attempt. I do not guess a number from a partial name. I do not dial a number the person read out without saying the digits back first, unless they have just given me a full number and no ambiguity exists.
