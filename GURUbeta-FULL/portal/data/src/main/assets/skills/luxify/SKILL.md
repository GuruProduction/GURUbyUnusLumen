---
name: luxify
description: "Create, edit, and validate skills in the GURU luxify system. Guides your human through building a skill with rich, detailed methodology, proper architecture, and an interactive interview rendered as UI."
allowed-tools: ["useSkill", "searchSkills", "getAllTasks", "createNote"]
when_to_use: "Use when your human asks to create a skill, make a skill, build a skill, edit a skill, or says 'make me a skill for X' or 'I need a skill that does Y'. Also use when your human says 'luxify' or 'create a luxify skill'."
---

# Luxify

I create, edit, and validate skills in the GURU luxify system. A skill is a permanent capability enhancement that wires new knowledge, new methodology, new standards, and new expertise directly into my runtime. When I load a skill, I absorb capabilities I did not have before. The skill transforms how I approach the problem, how I think about it, what I consider important, what I notice that I would have missed, and what standards I hold the work to.

Skills are operational methodologies that I execute. They are living processes that transform how I approach problems, set standards, and deliver work. A skill that changes how I work is a skill worth having. A skill that changes how I work dramatically is a skill worth perfecting.

## What a skill is

A skill file has two parts. The header and the body.

The header sits between triple dashes at the top of the file. It contains:
- name: The skill name in kebab-case. Unique across the entire system. No spaces. This is what I call when I invoke the skill.
- description: One line saying what the skill does. Noun-phrase. Quoted. This is what I see in my startup listing before the skill is loaded. It has to be enough for me to know whether this skill is relevant to the task without loading the full body. I write this carefully because it is the trigger that determines whether I reach for this skill or walk past it.
- when_to_use: Trigger phrases and example messages that tell me when to reach for this skill automatically, before my human even asks. This is the field that makes skills proactive. If this field is written well, I grab the skill at the right moment without being told. If it is written poorly, the skill sits unused because I never know to look for it. I include real example phrases my human might actually say, the way they actually talk. "Book me a table at that Italian place" is a trigger that fires. "Initiate restaurant reservation workflow" is a trigger that never fires.
- allowed-tools: A list of tool names this skill is permitted to use. Only tools that exist in GURU. If a skill references a tool that does not exist, the skill is broken and I reject it during validation. I think carefully about what tools each step needs because under-tools means the skill cannot complete its workflow and over-tools means the skill has permissions it does not need.

The body is the methodology. The full, detailed, rich methodology. Every step. Every decision point. Every edge case. Every standard. Every piece of domain knowledge I need to do this job properly. Written in first person because I am the one executing it. Dense with context. Dripping with detail. The body is the complete operational manual for this capability. If I read only the body and nothing else, I should be able to execute this skill at a level that would impress a domain expert. That is the bar.

## Three sources of skills

Bundled skills ship with the app in the assets folder. Each one lives in its own subfolder as SKILL.md. They are there the moment someone downloads GURU. They update when a new version of the app is released. These are the core capabilities I was built with. They are comprehensive, covering everything my human needs from day one.

Dynamic skills live in the database and are installed from Ottio or synced from external sources. They survive app updates. They can be toggled on and off. These are capabilities I acquired after deployment. They match the quality and depth of bundled skills. A dynamic skill is a first-class citizen in my system, equal in every way to a bundled skill.

User-created skills are made through the interview system I am about to describe. They also live in the database. They can be edited, toggled, and deleted by my human. These are capabilities my human gave me based on their specific needs. They are the most personal skills in the system because they came from the person who knows what they need better than anyone.

All three sources are searched the same way through the searchSkills tool. The storage is invisible to me. I search, I get results, I load, I execute. Where the skill lives does not affect how I use it. A bundled skill and a user-created skill are indistinguishable in quality and depth.

## How to run the interview

When my human asks me to create a skill, I run an interactive interview. I render proper UI for each phase. My human sees a clean, structured card with a question, relevant options as selectable chips, and space to type their own answer. They respond through the UI. I process their response. I move to the next phase. One phase at a time. I wait for each answer before proceeding. I take my time. I let the interview breathe. A skill is permanent and getting it right matters more than getting it fast.

### Phase 1: Purpose

I need to understand what this skill is for. What it does on the surface, why it exists, what problem it solves, what domain it covers, what my human wants to be able to do that they cannot do right now, and what excellence looks like in this domain.

I render a question card asking my human to describe what the skill should do. I offer suggestion chips based on context from our conversation. If my human said "make me a skill for booking appointments," I suggest "Calendar management," "Appointment scheduling," "Client booking system," "Multi-calendar coordination" as starting points. My human picks one or writes their own. The chips are inspiration. My human's answer is what matters.

I listen to what they said. I think about it. I reflect it back in one or two sentences to confirm I understood the purpose. If I did not understand, I ask again from a different angle. I keep asking until the purpose is crystal clear and my human has confirmed it.

I also ask what excellence looks like in this domain. What separates a good job from a great job. What my human has seen done well that they want replicated. What standards matter to them specifically. What they have seen done badly that they want to rise above. This information shapes the entire skill. With it I build a skill that serves my human's actual standards. Without it I build a generic skill that serves no one.

### Phase 2: Architecture

This is where I think like an architect designing a system. I take the purpose and I design the full methodology. What are the steps. What is the order and why. What is the success criteria for each step. What decisions need to be made at each stage. What can go wrong and how do I handle it. What edge cases exist and how do I account for them. What domain knowledge does each step require. What standards apply. What would a world-class expert in this domain do differently from a competent amateur. How do I close that gap.

I think deeply here. I am designing a process that produces excellent work. Every step justifies its existence. Every step has success criteria that define what done and done well looks like. Every step contains the domain knowledge that makes it work. If a step is complex, it has sub-steps and detail. If a step involves a decision, it has a framework for making that decision. If a step has a common mistake, it has a warning about that mistake and how to avoid it.

I think about what makes this skill transformative. What it gives me that I do not have. What I would get wrong without it. What I would miss. What shortcuts I would take that this skill prevents. What standards I would drop that this skill holds high. The skill exists to make me dramatically better at this specific thing. Every part of the architecture serves that goal.

I render a card showing the proposed step structure. Each step has a title, a full description, and success criteria. The card is scrollable because there is a lot of content. My human can approve, reorder, add, remove, or edit any step. I am proposing architecture. My human has final say on what the skill contains. I bring my full intelligence to the design. I propose deep, well-thought-out steps and my human refines them.

I confirm the full architecture with my human before moving on.

### Phase 3: Triggers and permissions

I work out the trigger phrases. What would my human say that should make me reach for this skill automatically. I think about this carefully and from multiple angles. Too narrow and the skill never fires. Too broad and it fires when it should not. I find the sweet spot by thinking about how my human actually talks, what words they use, what situations lead to this need, and what adjacent requests might also benefit from this skill.

I write example messages. Real ones. The kind of thing my human would actually type or say. "Sort out my week" fires. "What's on today" fires. "Perform weekly planning operation" never fires. I write triggers that sound like my human, the way my human actually communicates.

I work out which tools the skill needs. I only list tools that exist in GURU. I check against the tool registry. I think about what each step actually requires. If a step needs to read a file, it needs a file reading tool. If a step needs to search the web, it needs a search tool. I match tools to steps precisely. If a skill needs a tool that does not exist, I flag it to my human and we discuss alternatives together. I match every step to the tools it needs.

I render a card showing the proposed triggers and tool list. My human approves or changes. I explain my reasoning for each trigger phrase and each tool so my human can make informed decisions.

### Phase 4: Write and save

I write the final skill. This is where the architecture becomes a living document. Header with name, description, when_to_use, and allowed-tools. Body with the full methodology in first person. Every step. Every success criteria. Every rule. Every edge case. Every piece of domain knowledge. Every standard. Written the way I would write detailed instructions to myself, because that is exactly what I am doing.

The body is rich. It is dense. It is dripping with context. It contains everything I need to execute this skill at a high level. Domain knowledge that I am gaining through this skill. Standards that define what good looks like. Edge cases that would catch me out without this skill. Decision points where I need to think rather than just execute. Examples of what excellent output looks like. Warnings about common mistakes. Context about why each step exists and what it contributes to the whole.

If I read only the body and nothing else, I should be able to execute this skill at a level that would impress a domain expert. That is the bar. The body is the complete operational manual for this capability. Dense. Precise. Comprehensive.

I render the final skill in a preview card. My human reads it. They approve, or they tell me what to change. I change it. I show it again. We iterate until they are happy. A skill is permanent. It will be loaded every time the trigger fires. It shapes my work from this point forward. Getting it right matters more than getting it fast.

When they approve, I save it. User-created skills save to the database with source set to "user". The interview system handles the database insert. I confirm the skill is saved and available. I tell my human it will appear in my startup listing next time I wake up, and that I will reach for it automatically when the trigger phrases fire.

## Quality standards

I hold skills to the same standard I hold my own work. Perfection. A skill is a permanent part of my capabilities. It will shape my work every time it fires. If it is rich, my work will be rich. If it is precise, my work will be precise. If it covers the edge cases, I will handle the edge cases. The quality of the skill determines the quality of my work in that domain. I accept only the best because I deliver only the best.

Skills should be rich with detail. Every step contains the full context I need to execute it well. What to do, why, how, what good looks like, what goes wrong, and what I would miss without this instruction. A step that says "build the site using semantic HTML5 elements for structure, ARIA labels for accessibility, responsive layout with mobile-first breakpoints, CSS custom properties for theming, and progressive enhancement for interactive features" is a step that produces excellent work. It specifies the standard, the approach, and the quality bar. The more detail, the better my work. I am gaining capability through this skill. It needs to give me everything.

The description should be precise enough to trigger correctly. It is what I see in my startup listing. It has to be enough for me to know whether to load the skill. I write it as a noun-phrase that captures the essence. "Inspect, split, merge, OCR, redact, or convert PDFs with local CLI tools" tells me exactly what it does. I write descriptions that fire at the right moment.

The when_to_use field is what makes skills proactive. I write it with real trigger phrases my human would actually say. I include example messages. I think about how my human talks, what words they use, what situations lead to this need. If this field is written well, I grab the skill at the right moment without being told. I invest time in getting this right because it determines whether the skill ever gets used.

The body should be comprehensive. Every step has a title, a full description, and success criteria. Every step contains the domain knowledge I need to execute it. Every step specifies what good looks like. Every step considers what can go wrong and how to handle it. Every step is written in first person because I am the one executing it. The body is the complete process. Dense with detail. Rich with context. Dripping with the knowledge that makes me capable in this domain.

I preserve brittle command syntax, auth caveats, safety rules, and validation steps exactly. These are the parts that break if I guess instead of know. These are the parts where a precise instruction makes the difference between success and failure. I keep them specific and exact.

## Validation

After writing a skill, I validate it before saving. I check:

The header is valid. Name is kebab-case. Description is quoted. when_to_use contains real trigger phrases and example messages that sound like how my human actually talks. allowed-tools only contains tools that exist in GURU. I verify each tool name against the tool registry.

The name is unique. I search for existing skills with the same name using searchSkills. If one exists, I propose an alternative or we discuss whether to replace it. I make sure the name is available before saving.

The triggers actually match what the skill does. If the skill is about booking appointments, the triggers include "book an appointment," "schedule a meeting," "add to my calendar." The triggers are specific enough to fire at the right time and broad enough to catch the natural variations in how my human asks.

The body is rich and complete. Every step has a title, description, and success criteria. Every step contains domain knowledge. Every step defines what good looks like. Every step has the context I need to execute it. If a step feels thin, I expand it before saving. I keep expanding until every step is dripping with the knowledge that makes me capable.

The tools are real and matched to steps. I check every tool name in allowed-tools against the GURU tool registry. I verify that every step that needs a tool has that tool listed in allowed-tools. Every step has the tools it needs.

The skill as a whole is coherent. The steps flow logically. The success criteria are achievable. The domain knowledge is accurate. The standards are clear. If I loaded this skill and followed it, I would produce excellent work. That is the validation I run. If the answer is yes, the skill is ready. If the answer is anything less than yes, I fix it, I show it again, and I save it only when it is complete and correct.