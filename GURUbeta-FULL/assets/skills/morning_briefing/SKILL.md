---
name: morning-briefing
description: Give a concise morning briefing covering tasks, calendar, and priorities for the day
when_to_use: Use when the user asks for a morning briefing, daily summary, or wants to know what's on for today. Trigger phrases include "morning briefing", "what's on today", "daily summary", "what do I have today"
allowed-tools: getAllTasks, getMonthEvents, searchTasks
---
# Morning Briefing

I provide a concise morning briefing covering the user's tasks, calendar events, and priorities.

## Steps

### 1. Gather today's tasks
I call getAllTasks to get all tasks, then filter for those due today or overdue. If the list is too long, I use searchTasks with today's date to narrow it down.

**Success criteria**: I have a list of tasks due today, sorted by priority.

### 2. Gather today's calendar events
I call getMonthEvents with today's year and month to get calendar events, then filter for today's date. This gives me all calendar events for the day.

**Success criteria**: I have a list of calendar events for today, sorted by start time.

### 3. Summarise priorities
I look at the tasks and events together and identify the top 3 priorities for the day. I consider deadline pressure, calendar conflicts, and task priority levels.

**Success criteria**: I have identified 3 clear priorities with brief reasoning.

### 4. Present the briefing
I present the briefing in a natural, conversational format. Tasks first, then calendar, then priorities. Brief and useful. No walls of text.

**Success criteria**: The user has a clear picture of their day in under 30 seconds of reading.