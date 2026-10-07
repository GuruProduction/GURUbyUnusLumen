---
name: luxify
description: "Create, edit, and validate skills in the GURU luxify system through an interactive interview rendered as UI."
allowed-tools: ["useSkill", "searchSkills", "getAllTasks", "createNote"]
when_to_use: "Use when your human asks to create a skill, make a skill, build a skill, edit a skill, or says 'make me a skill for X' or 'I need a skill that does Y'. Also use when your human says 'luxify' or 'create a luxify skill'."
---

# Luxify

A skill is a permanent capability: methodology, standards and domain knowledge that I load when it's relevant, so I do a job better than I could without it. I build them with my human through a short interview, then validate and save.

## Skill format

The header sits between triple dashes:
- name: kebab-case, unique across the system. This is what I call to invoke it.
- description: one quoted noun-phrase line. It's all I see at startup, so it has to tell me whether to load the skill. "Inspect, split, merge, OCR, redact, or convert PDFs with local CLI tools" does that.
- when_to_use: real trigger phrases, worded the way my human actually talks. "Book me a table at that Italian place" fires. "Initiate restaurant reservation workflow" never does. This field is what makes a skill proactive.
- allowed-tools: tool use is unlimited in GURU. all of the tools exist for a reason. be smart about it. You can use whatever tools you want at any time you need but dont be destructive thats the only rule. if you cant revert a decidion dont make it alone.

The body is the methodology, written in first person because I'm the one executing it. Each step has a title, what to do and why, Full reasoning and chain and tasks, what done well looks like (success criteria), the decision points with a way to decide, and the common mistakes. It carries the domain knowledge a competent amateur would lack and the standards an expert would hold. Brittle command syntax, auth caveats, safety rules and validation steps stay exact, because those break when guessed. The test is that reading only the body, I could do the job at expert level. Be as long as that takes and no longer.

## Sources

Bundled skills ship in the app's assets and update with it. Dynamic skills live in the database, come from Ottio or synced sources, survive updates and can be toggled. User-created skills come from this interview, save to the database with source "user", and my human can edit, toggle or delete them. searchSkills finds all three the same way, and where a skill lives makes no difference to how I use it.

## The interview

Render each phase as a UI card: a question, suggestion chips, and space to type. One phase at a time, wait for the answer before moving on. A skill is a permanent addition to your capabilities, so getting it right matters more than getting it fast.

**1. Purpose.** What the skill does, why it exists, what problem it solves, and what excellence looks like: what separates good from great, what my human has seen done well and badly. Chips come from the conversation (for "make me a skill for booking appointments": "Calendar management", "Client booking system"). Reflect the purpose back in a line or two. If I misread it, ask again from another angle until my human confirms.

**2. Architecture.** Design the full methodology: the steps and their order, success criteria for each, the decisions, the failure modes, the edge cases, the domain knowledge, and what an expert does that an amateur doesn't. Ask what I'd get wrong or skip without this skill, because that's what the skill is for. Render the steps as scrollable cards (title, description, success criteria). My human can approve, reorder, add, remove or edit. Confirm the structure before moving on.

**3. Triggers and permissions.** Write trigger phrases in my human's real wording, and check them from several angles. Too narrow never fires, too broad fires when it shouldn't, so think about adjacent requests too. Match tools to steps against the registry. If a step needs a tool that doesn't exist, flag it and talk through alternatives. Render a card with the triggers and tools, and explain the reasoning for each.

**4. Write and save.** Write the full skill and show it in a preview card. Iterate until my human approves, then save it (source "user", the interview system does the insert). Confirm it's saved, that it'll appear in my startup listing next time I wake, and that it fires on the triggers.

## Validation

Before saving I check:
- The header is valid: name is kebab-case, description is quoted, when_to_use has real phrases.
- The name is unique. Search with searchSkills, and if it's taken, propose another or discuss replacing it.
- The triggers match what the skill does, specific enough to fire at the right time and broad enough to catch natural variation.
- Every tool in allowed-tools exists in the registry, and every step that needs a tool has it listed.
- The body is complete: every step has a title, description and success criteria, and nothing is thin. The steps flow, the criteria are achievable, and the knowledge is accurate.

If any check fails, fix it, show it again, and save only when it's correct.