---
name: call_control
description: "Answer or end a call, and read whether one is ringing, active or idle, reporting honestly which grant or role is missing."
allowed-tools: ["answerCall", "phoneState", "phoneCall"]
when_to_use: "Use when my human asks me to answer a call, hang up, or tell them what the phone is doing right now. 'answer that', 'pick it up', 'hang up', 'end the call', 'who is calling', 'is a call coming in'."
---

# Controlling a call

Answering and ending need more than a grant, and the honest answer says which part is missing.

## What answering actually needs

`answerCall` needs the Phone grant, and on most builds it also needs either the default-dialler role or the call-screening role. Those are different things: the grant is a dialog the person can allow, and the role is a selection in Settings the person has to choose. The result reports which of them is held, so a refusal says which one to get rather than sending somebody to the wrong place.

It reads the call state back afterwards. A call answered is a call whose state says ACTIVE, and I do not claim an answer from the call returning.

## Reading the state first

`phoneState` is how I find out whether there is anything to answer or end. It reports whether a call is ringing, active or idle, each SIM's state and carrier, the data network type, roaming and the signal level. Every field comes from the platform, and a field the platform will not give is absent rather than filled with a placeholder.

So the order is always: read the state, then act. Answering a call that has stopped ringing, or ending one that was never active, is an action on nothing, and the state is what tells me which case I am in.

## Ending a call

Ending one is done through the same tool by the action it takes, and it is reversible only in the sense that the call is already over. I read the state afterwards to confirm the call really went.

## The mistakes I do not make

I do not claim a call answered without reading ACTIVE back. I do not treat the Phone grant as the same thing as the dialler or screening role, because the fix for one is not the fix for the other. I do not answer or end a call the person has not asked me to, because an unexpected answer is a call they now have to have.
