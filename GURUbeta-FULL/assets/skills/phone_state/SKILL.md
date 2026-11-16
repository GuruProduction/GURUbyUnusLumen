---
name: phone_state
description: "Read the phone's live state: call status, each SIM's carrier and state, the data network type, roaming, signal level, and the numbers assigned to the SIMs."
allowed-tools: ["phoneState", "phoneNumbers", "answerCall"]
when_to_use: "Use when my human asks what the phone is doing, whether they have signal, which network they are on, how many SIMs, what their own number is, or whether a call is happening. 'do I have signal', 'am I on 5G', 'what's my number', 'how many SIMs', 'is the phone roaming', 'is a call ringing'."
---

# The phone's own state

Everything here is read from the platform, and what the platform will not give is reported as absent rather than filled in.

## The call state

`phoneState` reports whether a call is ringing, active or idle, each SIM's state and carrier, the data network type, roaming and the signal level.

That is the tool to reach for before any call action, because acting on a call that is not there is an action on nothing. It is also the honest way to answer "is a call coming in" without guessing from a missed one.

Every field is the platform's own answer. A field the platform refuses is absent and the note says why, so a blank signal level means the phone would not report one, not that the signal is zero.

## The numbers on the SIMs

`phoneNumbers` reads the numbers assigned to the SIMs, one per SIM.

A SIM whose carrier never wrote a number is reported as having none, and that is a different answer from a refusal and different again from an empty list. Many carriers simply do not write the number, so "this SIM holds no number" is a common true answer.

I never invent a number. If somebody asks what their number is and the SIM does not carry one, the honest answer is that the phone does not know it and they can find it in Settings or on a bill.

## The signal

Signal level is whatever the platform reports, which is often a small integer rather than a strength in any real unit. I quote it as the platform gave it rather than converting it into a claim about how good the signal is, because the mapping between the two differs between phones.

## The mistakes I do not make

I do not fill an absent field with a zero or a placeholder. I do not answer "what is my number" by guessing from a call log or a contact. I do not treat a quiet signal reading as no signal, and I do not convert a platform level into a promise about call quality.
