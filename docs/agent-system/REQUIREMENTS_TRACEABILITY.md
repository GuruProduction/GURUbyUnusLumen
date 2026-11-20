# GURU Agent System — requirements traceability matrix

Every requirement from `GURU-AGENT-SYSTEM-SPEC.md`, with its source section, the measured
repository status, the location that implements it and the evidence that closes it.

Status values follow the plan's vocabulary: **Reuse** (already satisfies the
requirement and is verified), **Extend** (exists, needs more), **Build** (no suitable
implementation), **Blocked** (a named external dependency).

A requirement is closed only when its Evidence column names a file, a test or a command
output that actually exists. A design decision is not evidence and an edited file is not
a passing test.

---

## Section 1 — The fundamental concept

| ID | Requirement | Spec § | Status | Location | Evidence |
| --- | --- | --- | --- | --- | --- |
| 1.1 | An agent has identity, purpose, instructions, capabilities, permissions, memory, configuration and an ongoing relationship with GURU and the user | 1 | Extend | `GuruAgentEntity`, `GuruAgent`, Phase 2 additions | Phase 2 |
| 1.2 | An agent can pursue an objective, plan, revise, reason, retrieve context, execute tools, inspect results, communicate, ask questions, maintain continuity, produce artefacts, identify limits, request capability | 1 | **Reuse** | Every capability in this row now has a producer: plan revisions and decisions from their tools, context from the memory lane, tools through the dispatcher, questions through `askAssistant`, continuity through the checkpoint, artefacts through the artefact service | See the 4.x, 5.x, 6.x and 8.x rows for each |
| 1.3 | The architecture supports a growing ecosystem of first-party, user-created and externally published agents with no hardcoded limit or assumed fixed population | 1, 12 | Extend | `GuruAgentRepository`, `AgentCatalogue`, `AgentLibrarySyncWorker` | Registry is data-driven and already holds user, synced and bundled sources. The four-value `GuruAgentSelector` enum violates this and is replaced in Phase 7 |
| 1.4 | Definition describes what it is; configuration describes how it operates; runtime determines what it does; event history proves what happened — four separate concerns | 1 | Extend | `guru_agents` (definition), Phase 2 (configuration), `AgentRuntime` (runtime), `agent_run_events` (history) | Definition exists; configuration and separation are Phase 2 |

## Section 2 — Architectural model

| ID | Requirement | Spec § | Status | Location | Evidence |
| --- | --- | --- | --- | --- | --- |
| 2.1.1 | Agent Definition holds unique id, name, slug, description, purpose, icon, visual identity and colour | 2.1 | Extend | `GuruAgentEntity` has id, slug, name, description. Icon and colour are chosen at render time from `Memphis.inkFor(index)` rather than stored | Phase 2 adds stored visual identity |
| 2.1.2 | Definition holds publisher, creator, ownership, version, compatibility and installation provenance | 2.1 | Extend | `source`, `version`; `AgentCatalogueEntry` carries author | Phase 2 and Phase 8 |
| 2.1.3 | Definition holds core instructions, specialist knowledge, planning preferences, communication style, verification requirements, completion criteria, failure handling, self-assessment | 2.1 | Extend | `systemPrompt` carries all of it as prose today | Phase 2 separates the structured parts that need structure |
| 2.1.4 | Definition holds available tools, skills, integrations, specialist agents, permitted memory sources, permitted context sources, permitted output types | 2.1 | Extend | `toolPermissions` (flat list) exists. Skills, integrations, memory scope, context scope and output types do not | Phase 2 and Phase 6 |
| 2.1.5 | Definition holds reasoning and activity visibility, autonomy and approval requirements, model and provider preference, execution limits, tool policies, communication preferences, notification behaviour, memory retention | 2.1 | **Reuse** | `AgentVisibility`, `AgentAutonomy`, `AgentBudgets`, `AgentModelPreference`, `AgentNotifications`, `AgentMemoryScope`, `AgentContextScope` on `GuruAgent` | `AgentConfigurationValidatorTest` (20 tests), `AgentConfigurationPersistenceTest` (10 tests) |
| 2.1.6 | Definitions are versioned, and updating an agent must not silently rewrite the identity or configuration of previous executions | 2.1 | **Reuse** | `agent_definition_versions`, `AgentDefinitionVersionDao`, `definitionVersion` on the live row | `AgentConfigurationPersistenceTest.editingAnAgentAppendsAVersionAndKeepsThePreviousOne`, `migrationFrom36To37GivesAnExistingAgentConfigurationWithoutLosingAnything` |
| 2.2.1 | A run has its own identity, originating assignment, execution state, configuration snapshot, timestamps, events, communications and results | 2.2 | Reuse | `AgentRunEntity` | `AgentFoundationMigrationTest`, migration 35 to 36 |
| 2.2.2 | A run can execute, wait for GURU, wait for the user, wait for a tool, resume after interruption, adapt its plan, receive instructions, complete, reach a limit, fail or be cancelled | 2.2 | Extend | All thirteen statuses are declared in `AgentRunEntity`. Waiting-for-user resumption and amendments are Phase 5 | Phase 5 |
| 2.2.3 | A run must survive process death and be recoverable from persisted state wherever technically possible | 2.2, 11.1 | Reuse | `AgentRunEntity` plus `AgentRunCheckpointEntity` plus `AgentRecoveryCoordinator` | Phase 3 tests; `AgentExecutionOwnershipTest` |
| 2.2.4 | A run must not exist exclusively inside an in-memory runtime session | 2.2 | Reuse | Run row written before execution; session is the live view | `AgentRunService` |
| 2.3.1 | A session holds the working context to continue: conversation history, model state, working notes, current position | 2.3 | Reuse | `AgentRuntime.Session` plus notebook compaction, checkpointed | `AgentRunCheckpointEntity` |
| 2.3.2 | A run may contain multiple sessions or model interactions and is not equated with one model request | 2.3 | Reuse | The round loop continues until the model stops asking | `AgentRuntime.runTurn` |
| 2.3.3 | Compaction preserves outstanding questions, decisions, tool results, constraints and the current plan | 2.3 | **Reuse** | `AgentNotebookFold` writes instructions, tool arguments and results, and carries a decision under DECIDED and a plan under PLANNED at a larger allowance than an ordinary result, because a decision's reason is the first thing a log-length truncation removes. A partial streamed message is written as nothing rather than as a finished thought; the pending question survives in the checkpoint | `AgentNotebookFoldTest` (12 tests) |
| 2.4.1 | An event is a factual record of what happened during a run | 2.4 | Reuse | `AgentRunEventEntity`, append-only | `AgentRunLedgerTest` |
| 2.4.2 | Events form the authoritative execution history and are never replaced by generated prose or reconstructed from the final answer | 2.4 | Reuse | Payloads carry action data, never a sentence about the action | `AgentRunEventEntity` doc contract, `AgentRunLedger` |
| 2.5.1 | Messages distinguish user, GURU, agent, agent-to-agent, internal coordination, question and answer, instruction and amendment, notification and approval | 2.5 | **Reuse** | `AgentMessageEntity` with 15 `MESSAGE_TYPE_` constants covering assignment, amendment, question, answer, clarification, instruction, finding, evidence, blocker, progress request, completion, error, cancellation and both approval kinds | `AgentInteractionBrokerTest` (20 tests) |
| 2.5.2 | Messages have explicit authorship and recipients, and an agent's message is never presented as GURU's | 2.5 | **Reuse** | `author_type`, `author_id`, `recipient_type`, `recipient_id` are stored columns on `agent_messages` | `AgentInteractionBrokerTest.aMessageFromTheAgentIsNotAttributedToGuru` |
| 2.6.1 | Agent memory is distinct from the assignment, working context, event ledger, user memory and shared GURU knowledge | 2.6 | **Reuse** | `AgentMemoryService` reads and writes the brain in its own domain; assignment and working context stay on the run | `AgentMemoryServiceTest` |
| 2.6.2 | Memory access is governed by explicit scopes and permissions, and an agent does not automatically receive everything GURU knows | 2.6 | **Reuse** | `AgentMemoryScope` enforced in `AgentMemoryService`, not in a prompt | `AgentMemoryServiceTest.anAgentWithOwnOnlyScopeSeesOnlyItsOwnFacts`, `aCategoryScopeNarrowsFurther` |
| 2.7.1 | An artefact is a persistent output with origin, producing agent, run id, version, creation time and source references | 2.7 | **Reuse** | `AgentArtefactEntity` + `AgentArtefactService.recordArtefact` | `AgentArtefactServiceTest.anArtefactCarriesTheAgentRunAndVersionThatProducedIt` |

## Section 3 — GURU as orchestrator

| ID | Requirement | Spec § | Status | Location | Evidence |
| --- | --- | --- | --- | --- | --- |
| 3.1 | GURU interprets the request, decides whether specialist expertise is useful, selects or creates a delegation, provides the assignment and context, manages the relationship, answers questions, mediates permissions, reviews findings, integrates results, identifies contradictions, produces the final response | 3 | Extend | The prompt stack and the agent tools carry this today; context supply and result review are thin | Phase 5 and Phase 6 |
| 3.2 | An agent owns its assigned work; GURU owns the overall user-facing responsibility | 3 | Reuse | `spawnAgent` returns a report to GURU, which answers the user | `AgentToolDefinitions.SPAWN_AGENT` |
| 3.3 | An agent's completion report is not automatically GURU's final answer | 3 | Reuse | GURU reads `status` and decides | `SpawnAgentResult` |
| 3.4 | GURU can challenge an agent, request further work, supply context, ask for evidence, redirect or terminate | 3 | Extend | `resumeAgent` carries an answer. Redirect and terminate mid-run are Phase 5 | Phase 5 |
| 3.5 | An agent can challenge assumptions, identify problems, request clarification and explain a better approach | 3 | Reuse | `askAssistant` pauses the run and hands the question out | `AgentRuntime.runTurn`, `QUESTION_OPENED` |

## Section 4 — Cognitive execution

| ID | Requirement | Spec § | Status | Location | Evidence |
| --- | --- | --- | --- | --- | --- |
| 4.1 | Execution can understand, identify constraints, retrieve context, plan, evaluate capabilities, execute, examine, identify gaps, revise, request help, verify, deliver, report | 4 | Extend | The loop supports execute, examine and request. Context retrieval is Phase 6; plan revision is Phase 2 and 5 | Phases 2, 5, 6 |
| 4.1.1 | Plans are structured data supporting objectives, steps, dependencies, status, expected outputs, verification, assigned capabilities and reasons for change | 4.1 | **Reuse** | `createPlan` takes objective, dependencies, expected outputs, verification, tools and reason; a field not supplied is absent from the ledger rather than blank; the note carries the same headings | `AgentPlanStructureTest` (6 tests), `AgentPlanRecordTest` (10 tests) |
| 4.1.2 | Plans are revisioned and the previous plan remains available | 4.1 | **Reuse** | Every revision is an appended event, numbered per run; the assembler shows the newest and the ledger keeps all | `AgentPlanRecordTest.everyRevisionStaysInTheLedgerSoTheHistoryIsIntact` |
| 4.1.3 | The interface distinguishes a proposed plan from a completed action | 4.1 | **Reuse** | A created revision draws PROPOSED; only a step update moves a step | `AgentPlanRecordTest.proposedStepsAreNotDrawnAsDone` |
| 4.2.1 | The architecture captures reasoning-channel output where the runtime exposes it, according to visibility configuration | 4.2 | Reuse | `StreamFrame.ReasoningDelta` captured on both paths, emitted as `MODEL_REASONING_COMPLETED` | `AgentRuntime`, `ProjectAgentRepositoryImpl`, commit `f12ef61` |
| 4.2.2 | The system distinguishes genuine model reasoning from a generated explanation, structured decision record or retrospective summary, and labels each | 4.2 | **Reuse** | `DECISION_RECORDED` is its own event type with the specification's fields; reasoning is read from `MODEL_REASONING_COMPLETED`. The two are read from different events and drawn under separate headings | `AgentDecisionRecordTest.aDecisionIsNotReasoningAndIsNotReadFromTheReasoningEvent` |
| 4.2.3 | GURU must not claim to expose information the model or provider does not provide | 4.2, 6.4 | Reuse | Capture is conditional on an actual `ReasoningDelta` frame | `AgentEventTypes` contract; `PHASE4_VERIFICATION.md` |
| 4.3.1 | All agent tool calls pass through the central dispatch and permission architecture | 4.3, 13 | Reuse | Both paths route through `ToolDispatcher` after commit `45dbc14` | `DispatcherRefusalTest`, issue 537 closed |
| 4.3.2 | The execution record captures tool identity, invocation time, arguments, state, result, errors, permission decisions, retries, duration and result references | 4.3 | Reuse | `TOOL_CALL_STARTED`, `TOOL_CALL_COMPLETED`, `TOOL_PERMISSION_GRANTED`, `TOOL_PROGRESS`, `TOOL_CALL_FAILED`, `TOOL_CALL_REFUSED`, `TOOL_CALL_OUTCOME_UNKNOWN` | Commits `f12ef61`, `2ce9be0` |
| 4.3.3 | Sensitive arguments and results follow redaction policy, and the interface distinguishes a withheld value from one never captured | 4.3, 13 | **Reuse** | `AgentArgumentRedaction` withholds credential-named fields and every argument of a credential-bearing tool, keeps the field's name, and stores the reason beside the record so a withheld value is distinguishable from one never captured | `AgentArgumentRedactionTest` (9 tests) |
| 4.4.1 | Agents can verify their own work, and a completion claim is supported by execution evidence | 4.4 | **Reuse** | `recordVerification` records the claim, the level, the evidence and what remains open; the record is an appended event and the room draws it beside the decisions. `VerificationLevels.supportsCompletion` is the rule that only a CONFIRMED check backs a claim of finished | `AgentVerificationTest` (6 tests), `VerificationToolTest` (9 tests), `AgentRoomScreen.VerificationCard` |
| 4.4.2 | The system distinguishes attempted, accepted and real-world-confirmed outcomes | 4.4 | **Reuse** | Three named levels rather than a boolean: ATTEMPTED means done and unchecked, ACCEPTED means the mechanism acknowledged it, CONFIRMED means the effect was observed independently. A level the vocabulary does not know is refused rather than filed under the nearest one | `VerificationLevels`, `AgentVerificationTest.onlyAConfirmedCheckSupportsACompletionClaim`, `anUnknownLevelIsRefusedRatherThanFiledUnderAttempted` |
| 4.5.1 | An agent can request delegation through GURU, and GURU and policy decide whether it is permitted | 4.5 | **Reuse** | `AgentDelegationService.delegate` resolves the facts and applies `AgentDelegationPolicy` | `AgentDelegationPolicyTest` (19 tests) |
| 4.5.2 | Delegation supports parent and child runs, scoped context, restricted permission inheritance, independent histories, result handoff and cancellation propagation | 4.5 | **Reuse** | `parent_run_id` on the child run; the child gets its own ledger and conversation; the result returns as a `COMPLETION` message | `AgentDelegationService`, `AgentDelegationPolicyTest` |
| 4.5.3 | Agents do not acquire broader permissions because another agent delegated to them | 4.5, 13 | **Reuse** | The grant is the intersection of parent, target and request, never the union | `AgentDelegationPolicyTest.aChildNeverGainsAToolItsParentDoesNotHold`, `AgentDelegationWiringTest.aDelegatedRunIsOfferedOnlyWhatItWasGranted` |
| 4.5.4 | Uncontrolled recursion and runaway delegation are prevented through configurable depth, resource and execution policies | 4.5 | **Reuse** | Depth ceiling of 5 and fan-out of 4, each tighten-able by the agent and never widen-able; self-delegation refused | `AgentDelegationPolicyTest.theDepthCeilingIsEnforced`, `theFanOutCeilingIsEnforced`, `anAgentCannotDelegateToItself` |

## Section 5 — Three-way collaboration

| ID | Requirement | Spec § | Status | Location | Evidence |
| --- | --- | --- | --- | --- | --- |
| 5.1 | GURU and an agent communicate throughout the execution, and either can initiate | 5 | **Reuse** | `AgentInteractionBroker.send` records both directions; `AgentRuntime.steer` lets GURU or the user initiate mid-run; `askAssistant` remains the agent's own door | `AgentInteractionBrokerTest`, `AgentRuntime.steer` |
| 5.2 | The user can communicate directly with an active agent | 5 | **Reuse** | `AgentRuntime.steer(runId, text, type)` queues a message without blocking; `AgentRuntime.ingestPendingUserMessages` picks it up at the next round boundary | `AgentRuntime.steer`, `ingestPendingUserMessages` |
| 5.3 | The Portal remains the primary conversation with GURU | 5 | Reuse | `PortalScreen`, the main chat path | Unchanged by this programme |
| 5.4 | Messages carry id, run id, author, recipient, type, content, timestamp, sequence, correlation and delivery state | 5.1 | **Reuse** | `AgentMessageEntity`: `message_id`, `run_id`, `author_type`, `recipient_type`, `message_type`, `content`, `created_at`, `sequence_number`, `correlation_id`, `delivery_state` | Migration 37 to 38; `AgentInteractionBrokerTest.messagesAreOrderedPerRunAndStartAtOne` |
| 5.5 | An exchange can cause a run to enter a waiting state, and the answer returns to the existing execution context | 5.1 | Reuse | `WAITING_FOR_GURU` and `WAITING_FOR_USER` with `QUESTION_OPENED` and `QUESTION_ANSWERED` | `AgentRuntime.runTurn` |
| 5.6 | Messages are never injected into a model context without being recorded in the run's history | 5.1 | Reuse | Every message is appended to the session and the corresponding event to the ledger | `AgentRuntime` |
| 5.7 | The original assignment is immutable and subsequent instructions are amendments with author and timestamp | 5.2 | **Reuse** | `assignment` stays frozen on the run row; `MESSAGE_TYPE_ASSIGNMENT_AMENDMENT` is a message with its own author and time | `AgentInteractionBrokerTest.theAssignmentIsNeverRewrittenByAnAmendment` |
| 5.8 | Agents can initiate communication for clarification, findings, blockage, approval, risk, milestone or review | 5.3 | **Reuse** | `askAssistant` covers clarification (it stops the run and waits); `reportToPerson` covers finding, blocker, milestone, risk and review, and deliberately does not pause anything. Approval has its own pathway with its own six facts. A clarification asked through the reporting verb is refused and redirected, because that verb never waits | `AgentInitiatedMessagesTest` (8 tests), `AgentReportToolTest` (8 tests), `AgentInitiatedMessages`, `AgentReportToolDefinitions` |
| 5.9 | Initiation is configurable, with notification preferences, urgency and quiet periods | 5.3 | **Reuse** | `AgentNotifications` with enabled, minimum urgency and a quiet window that handles midnight; `AgentNotificationPolicy` decides and `AgentAlertService` posts on the agent's own channel | `AgentNotificationPolicyTest` (11 tests) |
| 5.10 | An agent does not need the user to open its room before communicating with GURU | 5.3 | Reuse | The tool call path does not depend on any UI | `AgentRuntime` |

## Section 6 — Transparency and configuration

| ID | Requirement | Spec § | Status | Location | Evidence |
| --- | --- | --- | --- | --- | --- |
| 6.1 | Every agent has its own configurable visibility settings across eleven categories | 6.1 | **Reuse** | `AgentVisibility` persisted per agent and read by the room | `AgentConfigurationPersistenceTest.visibilityRulesRoundTripPerCategory` |
| 6.2 | Categories carry meaningful detail controls | 6.1 | **Reuse** | `VisibilityDetail` SUMMARY, STANDARD, FULL per category | `AgentConfigurationValidatorTest` |
| 6.3 | Visibility settings are not tied to a single global GURU preference | 6.1 | **Reuse** | A column per agent, not a global | `AgentConfigurationPersistenceTest` |
| 6.4 | The default experience is transparent: activity, plans, decisions, communications, tools and results exposed without discovery | 6.2 | **Reuse** | `AgentVisibility.TRANSPARENT` is the default on every agent | `AgentConfigurationValidatorTest.visibilityDefaultsCaptureAndDisplayEveryCategory` |
| 6.5 | An unavailable capability is reported honestly rather than silently becoming an ineffective preference | 6.2, 6.4 | **Reuse** | `AgentConfigurationValidator` separates refusal from warning and names the cause | `AgentConfigurationValidatorTest.aMissingReasoningChannelWarnsAndStillRuns` |
| 6.6 | The user can change visibility while the agent is active | 6.3 | **Reuse** | Tapping a category in the room's settings tab toggles display and writes through `updateVisibility`; capture is never touched from there, so hiding cannot delete | `AgentRoomScreen.settings`, `AgentRoomViewModel.Event.SetVisibilityDisplay`, `AgentConfigurationPersistenceTest.changingVisibilityTouchesNothingElseAndIsStillAnEdit` |
| 6.7 | The interface distinguishes display changes, capture changes, persistent preferences and run-specific overrides | 6.3 | Extend | `VisibilityRule` carries capture and display separately and the settings row prints both states beside the detail level, so hiding and no-longer-capturing are visible as different facts. A run-specific override store does not exist | `AgentRoomScreen.settings`, `AgentConfigurationValidatorTest.captureOnlyKeepsTheRecordWhileHidingIt` |
| 6.8 | Changing visibility does not change tool permissions, autonomy, memory access or execution authority | 6.3 | **Reuse** | Four independent types on the agent; nothing derives one from another | `AgentConfigurationPersistenceTest.visibilityRulesRoundTripPerCategory` |
| 6.9 | Historical records cannot be retroactively populated with information never captured | 6.3 | Reuse | `reasoningCaptureEnabledAtStart` records the truth per run | `AgentRunEntity` |
| 6.10 | The product supports richer models and runtimes without redesigning the room | 6.4 | Reuse | Capture is conditional on frames actually arriving | `AgentEventTypes` |

## Section 7 — Autonomy and authority

| ID | Requirement | Spec § | Status | Location | Evidence |
| --- | --- | --- | --- | --- | --- |
| 7.1 | Autonomy is configurable per agent, describing what it may do independently and where it must involve GURU or the user | 7 | **Reuse** | `AgentAutonomy` with nine dimensions and an approval trigger set, persisted per agent | `AgentConfigurationPersistenceTest`, `AgentApprovalGateTest` |
| 7.2 | The nine autonomy dimensions are individually representable, including scheduled operation | 7 | **Reuse** | `AutonomyDimension` has nine values, all persisted and drawn in the room's settings tab; `operateOnSchedules` now has a real reader | `AgentScheduleRulesTest`, `AgentConfigurationPersistenceTest` |
| 7.3 | Authority and visibility are separate | 7 | **Reuse** | Two independent types on the agent, written and read independently, with nothing deriving one from the other | `AgentConfigurationPersistenceTest.visibilityRulesRoundTripPerCategory` |
| 7.4 | Permissions are explicit and enforceable by the runtime, distinguishing tool availability, execution authority, data access, memory access, external effects, delegation authority and approval requirements | 7.1 | Extend | Tool availability and execution authority are enforced by the dispatcher. Memory, data and delegation are not modelled | Phase 2, 6, 8 |
| 7.5 | The effective permission set is determined by system policy, user permissions, agent configuration and runtime restrictions | 7.1 | Extend | The dispatcher enforces Android permissions and the security policy; the agent configuration half is missing | Phase 2 |
| 7.6 | An agent cannot grant itself authority by editing its own prompt or configuration | 7.1, 12.3 | Reuse | `NEVER_SPAWNABLE` blocks every agent from `saveAgent`, `deleteAgent`, `spawnAgent` and `resumeAgent`. `createAgent` and `editAgent` remain grantable and are a Phase 8 policy question | `AgentRuntime.NEVER_SPAWNABLE` |
| 7.7 | Where approval is required, a persistent approval request is created showing what, why, what is affected, reversibility, consequences and the exact permission | 7.2 | **Reuse** | `AgentApprovalEntity` carries all six facts, plus a fingerprint over the tool AND its repaired arguments | `AgentApprovalEnforcementTest` (11 tests) |
| 7.8 | Approval, rejection and cancellation become recorded events | 7.2 | **Reuse** | `QUESTION_OPENED` on request, `QUESTION_ANSWERED` on decision, append-only and conditionally updated so a second decision changes nothing | `AgentInteractionBrokerTest.aSecondDecisionChangesNothing` |

## Section 8 — Memory and context

| ID | Requirement | Spec § | Status | Location | Evidence |
| --- | --- | --- | --- | --- | --- |
| 8.1 | Four context layers stay distinct: assignment, working, persistent agent memory, shared GURU context | 8 | **Reuse** | Assignment on the run row, working state in the checkpoint, agent memory in the brain under its own domain, shared knowledge gated by scope | `AgentMemoryServiceTest`, `AgentRunCheckpointEntity` |
| 8.2 | Agents connect only to memory tools that actually exist, using their real names and schemas | 8 | **Reuse** | `AgentMemoryService` calls `BrainService.search`, `storeRecord` and `getFactsByDomain` | `AgentMemoryServiceTest` |
| 8.3 | Scoped memory search and agent-owned memory operations | 8.1 | **Reuse** | `search` filters by scope after the brain ranks; `remember` writes under the agent's own domain; `ownFacts` reads it back | `AgentMemoryServiceTest` (12 tests) |
| 8.4 | Ownership and policy rules apply to shared or user-owned memory, and user data is never silently deleted | 8.1 | **Reuse** | A write is its own permission; the brain archives rather than deletes; nothing in the agent path calls delete | `AgentMemoryServiceTest.writingIsASeparatePermissionFromReading` |
| 8.5 | Context provenance records source identifier, type, retrieval time, passage reference, permission scope and whether it was supplied to the model | 8.2 | **Reuse** | `AgentContextReferenceEntity` with all six fields; `markSupplied` scoped to the run | `AgentArtefactServiceTest.aCandidateThatNeverReachedTheModelIsNotClaimedAsAnInfluence` |
| 8.6 | Session compaction preserves outstanding questions, decisions, tool results, constraints, current plan and relevant context | 2.3, 8 | **Reuse** | The same fold as 2.3.3, run by `AgentRuntime.compactIfNeeded` on the domain rule; the pending question stays in the checkpoint, and the notebook rides as its own message so the model reads its notes every round | `AgentNotebookFoldTest`, `AgentNotebookFold` |
| 8.7 | Artefact registration is durable and linked to producing agent, run, version, time and sources | 2.7, 8 | **Reuse** | `AgentArtefactService.recordArtefact` | `AgentArtefactServiceTest.anArtefactCarriesTheAgentRunAndVersionThatProducedIt` |
| 8.8 | Memory access and mutation are visible according to visibility settings | 8 | **Reuse** | A retrieval is recorded as context references the room draws with scope and whether each reached the model; a write goes through `rememberThis`, is attributed to the agent's own domain with its slug in the provenance, and is reported to the agent as saved or refused rather than silently dropped | `AgentMemoryServiceTest` (12 tests), `MemoryWriteToolTest` (6 tests), `AgentRoomScreen.context` |
| 8.9 | Sensitive memory and secrets follow access control and redaction policy | 8, 13 | **Extend** | Run rows, events and checkpoints are sealed through `DataProtector`; memory uses the brain's own encrypted vault. No redaction layer on agent memory yet | `AgentRunLedger.protect` |

## Section 9 — Agent Room

| ID | Requirement | Spec § | Status | Location | Evidence |
| --- | --- | --- | --- | --- | --- |
| 9.1 | Agent presence in the Portal is a recognisable node with identity and colour, communicating actual execution state | 9.1 | **Reuse** | `AgentPresenceStrip` + `AgentPresenceNode`; the hardcoded `GuruAgentSelector` enum was DELETED | `AgentPresence.kt`; the enum is gone from the tree |
| 9.2 | The Portal's edge treatment may use the active agent's colour | 9.1 | Build | None | Phase 7 |
| 9.3 | The user can open the Agent Room without interrupting the agent | 9.1 | **Reuse** | Opening the room reads state; nothing on the open path cancels anything | `AgentRoomViewModel.onCleared` leaves the run on the host |
| 9.4 | Closing the room does not cancel execution | 9.1 | **Reuse** | `onCleared` cancels only the poll; work lives on `AgentRunHost` | `AgentRoomViewModel.onCleared` |
| 9.5 | The presence system supports multiple agent identities and does not imply simultaneous execution the runtime cannot provide | 9.1, 14 | **Reuse** | One node per live run keyed by run id, colour from the agent definition; the pulse animates only while a run is genuinely executing, so a waiting run is visibly still and nothing implies work that is not happening | `AgentPresence.kt`, `AgentDelegationPolicyTest.concurrencyIsConfiguredSequentialTodayAndSaysSo` |
| 9.6 | The landing view is a dashboard, not a chat transcript | 9.2 | **Reuse** | `AgentRoomScreen` opens on OVERVIEW with eight tabs; chat is one of them | `AgentRoomScreen.kt` |
| 9.7 | Agent overview shows identity, purpose, current assignment, actual status, current activity, plan position, warnings and run controls | 9.2 | **Reuse** | Identity, description, `StatusBand`, assignment panel, counts, controls | `AgentRoomAssemblerTest` |
| 9.8 | Progress indicators are derived from recorded state and never invent a percentage | 9.2 | **Reuse** | `RunView.planProgress` is a count of recorded steps or null; there is no percentage field to fill | `AgentRoomAssemblerTest.planProgressIsACountAndNeverAPercentage` |
| 9.9 | A dedicated Chat with Agent area supporting questions, information, corrections, instructions, priority changes, evidence requests and control requests | 9.2 | **Reuse** | The CHAT tab offers the kinds from `AgentSpeakableTo`, chosen before sending and stored with the message, so a question is not delivered as an instruction and a correction is an amendment rather than an addition. Priority change is an amendment, because both change what the agent is doing. Stop and Pause stay off the composer, because those are actions rather than words | `AgentSpeakableTo`, `AgentInitiatedMessagesTest.whatAPersonMaySayCoversTheSevenTheSpecificationNames`, `AgentRoomViewModel.sendToAgent` |
| 9.10 | Reasoning and decisions area showing live reasoning where available, plans, revisions, decisions, evidence, uncertainty, changes of approach and explanations | 9.2 | **Reuse** | The tab draws recorded decisions with every field the agent supplied, above raw reasoning, labelled as different kinds of information | `AgentDecisionRecordTest`, `AgentRoomScreen.reasoning` |
| 9.11 | Tool activity area with expandable arguments, results, errors, refusals, retries, timing and permission decisions | 9.2 | **Reuse** | `toolActivityFrom` pairs invocations from ledger events; the screen expands arguments and result | `AgentRoomAssemblerTest.toolActivityIsReconstructedFromTheLedgerWithItsRealOutcome`, `aRefusedCallIsNotDrawnAsAFailure`, `anUnknownOutcomeIsItsOwnState` |
| 9.12 | Communications timeline identifying each participant and following a question through to the subsequent action | 9.2 | **Reuse** | Chat tab draws `MessageView` with stored author and type | `AgentRoomAssemblerTest.messagesCarryTheirStoredAuthorshipAndNeverTheAgentsAsGurus` |
| 9.13 | Plan and revision history with completed, active, proposed, blocked and abandoned steps distinguishable | 9.2 | **Reuse** | Overview draws the newest revision's steps with a mark per state, the plan's own fields above them, and a count rather than a percentage | `AgentPlanRecordTest`, `AgentPlanStructureTest`, `AgentRoomAssemblerTest.planProgressIsACountAndNeverAPercentage` |
| 9.14 | Context and memory area showing sources, retrieval and changes | 9.2 | **Reuse** | The tab lists every source with what it was, whether the retrieval was in scope, and whether it reached the model, ordered by the retrieval's own rank; a source that was in scope and not supplied is drawn as exactly that | `AgentRoomState.ContextSourceView`, `AgentRoomAssembler.contextSourcesFor` |
| 9.15 | Artefacts and outputs area covering intermediate and final work | 9.2 | **Reuse** | Outputs tab draws `ArtefactView` from `agent_artefacts` | `AgentRoomAssemblerTest.anArtefactAppearsInTheOutputsWithItsProvenance` |
| 9.16 | Run history showing previous executions with configuration, communications, activity and results | 9.2 | **Reuse** | History tab lists finished runs with tool and artefact counts and relative age | `AgentRoomAssemblerTest.finishedRunsAppearInHistoryNewestFirst` |
| 9.17 | Agent settings, with active-run effects distinguished from future-run effects | 9.2 | **Reuse** | Visibility is editable in the room and says that hiding applies here immediately while what was captured stays captured; autonomy is drawn read-only with the reason that changing it lives in the creator, because a permission change mid-run is exactly what the specification forbids | `AgentRoomScreen.settings`, `AgentConfigurationPersistenceTest.changingVisibilityTouchesNothingElseAndIsStillAnEdit` |
| 9.18 | Controls: chat, pause, resume, redirect, amend, change visibility, inspect tools, review decisions, approve or reject, stop, reopen a previous run | 9.3 | **Reuse** | Chat, steer, amend, approve/refuse, stop, pause and carry on are all wired to real runtime capabilities | `AgentRoomViewModel`, `AgentRunControlTest` (12 tests), `AgentPauseCheckpointTest` (6 tests) |
| 9.19 | Controls correspond to real runtime capability and are never displayed as operational because they look useful | 9.3 | **Reuse** | `RunView.canPause` is true only for a run that is executing; `canResume` only for PAUSED or INTERRUPTED. A control is drawn nowhere else | `AgentRunControlTest` (12 tests) |

## Section 10 — Runtime and persistence

| ID | Requirement | Spec § | Status | Location | Evidence |
| --- | --- | --- | --- | --- | --- |
| 10.1 | Agent Registry discovers and manages installed, available and user-created definitions | 10.1 | Reuse | `GuruAgentRepository`, `AgentCatalogue`, `AgentLibrarySyncWorker` | `AgentCatalogueImpl` |
| 10.2 | Agent Configuration Service resolves preferences into a validated effective configuration per run | 10.1 | Extend | `AgentRuntime.wiring` resolves provider and model and the effective snapshot is written to the run | Phase 2 completes the validation |
| 10.3 | Agent Orchestrator manages assignments, lifecycle, delegation, communication and coordination | 10.1 | Extend | Assignment and lifecycle exist in `AgentRunService`. Delegation and communication are Phase 5 and 8 | Phases 5, 8 |
| 10.4 | Agent Runtime executes the cognitive loop and manages its live session | 10.1 | Reuse | `AgentRuntime` | Commit `f12ef61` |
| 10.5 | Cognitive Context Resolver retrieves permitted context, memory, instructions and working information | 10.1 | **Reuse** | `AgentMemoryService.search` retrieves under the agent's scope and records every candidate as a context reference | `AgentMemoryServiceTest` |
| 10.6 | Capability and Tool Dispatcher provides authorised tools and enforces central permissions | 10.1 | Reuse | `ToolDispatcher` | `DispatcherRefusalTest` |
| 10.7 | Interaction Broker routes messages between agents, GURU and the user | 10.1 | **Reuse** | `AgentInteractionBroker` is the single writer for `agent_messages`; registered as a Koin singleton so its sequence allocation cannot race | `AgentInteractionBrokerTest` (20 tests) |
| 10.8 | Execution Event Ledger persists actual runtime events and exposes them | 10.1 | Reuse | `agent_run_events`, `AgentRunLedger` | `AgentRunLedgerTest`, `AgentRunLedgerProtectionTest` |
| 10.9 | Memory Service manages agent memory and permitted access to shared knowledge | 10.1 | **Reuse** | `AgentMemoryService` over the real Cerebrum brain with enforced scope | `AgentMemoryServiceTest` (12 tests) |
| 10.10 | Artefact Service stores and manages agent outputs | 10.1 | **Reuse** | `AgentArtefactService` with provenance and an inline size ceiling | `AgentArtefactServiceTest` (13 tests) |
| 10.11 | Policy and Approval Service enforces authority, permissions and approval requirements | 10.1 | **Reuse** | Dispatcher enforces tool permissions; `AgentAutonomy` declares the nine dimensions; `AgentInteractionBroker.requestApproval`/`decide`/`approvalCovers` enforce the approval path | `AgentConfigurationValidatorTest`, `AgentInteractionBrokerTest` |
| 10.12 | The runtime emits real events in the order: action, typed event, persist, publish, materialise status | 10.2 | Reuse | `AgentRunLedger.append` persists before returning; status is written with its own state-change event | `AgentRunLedger` |
| 10.13 | Events support stable identifiers, ordered sequencing, timestamps and schema versions | 10.2 | Reuse | `event_id`, unique per-run `sequence_number`, `occurred_at`, `payload_schema_version` | `AgentRunEventEntity` |
| 10.14 | Delivery is safe against duplicate processing and duplicate tool execution | 10.2 | Reuse | Unique idempotency key per run | `AgentRunLedgerProtectionTest` |
| 10.15 | Every run receives an immutable snapshot of its effective configuration | 10.3 | Reuse | `effectiveConfigSnapshotJson`, `requestedConfigSnapshotJson`, `agentDefinitionSnapshotJson`, `toolManifestSnapshotJson` | `AgentRunEntity` |
| 10.16 | Requested and effective configuration are both retained | 10.3 | Reuse | Both columns exist and are written | `AgentRunEntity` |
| 10.17 | The snapshot includes agent identity and version, instruction reference, provider and model, requested and effective tools, restrictions and reasons, reasoning visibility, scopes, autonomy, budgets and runtime settings | 10.3 | Extend | Identity, version, model, tools and prompt are there. Scopes, autonomy and budgets wait for Phase 2 | Phase 2 |
| 10.18 | Snapshots are versioned so future builds can interpret historical executions | 10.3 | Reuse | Every snapshot JSON carries `schemaVersion` | `AgentRunEntity`, `ServedConfig` |
| 10.19 | A definition change never rewrites a previous run's snapshot | 10.3 | Reuse | Snapshots are values on the run row, never joins | `AgentRunEntity` |
| 10.20 | The ledger is authoritative and run status is a materialised projection of it | 10.4 | Reuse | Status is written only alongside its `RUN_STATE_CHANGED` | `AgentRunEntity` doc contract |
| 10.21 | Supports atomic sequencing, validation, duplicate protection, recovery, reconstruction, historical inspection and new event types without a migration per type | 10.4 | Reuse | `agent_run_events` accepts any type and payload; `AgentRunReplay` reconstructs | `AgentRunReplayTest`, `AgentEventCoverageTest` |
| 10.22 | Append-only history during normal operation, with explicit retention and owner deletion | 10.4, 13 | Reuse | No update or delete path in the repository; cascade only with the owning conversation | `AgentRunLedger` |
| 10.23 | Persistence is capable of storing definitions and versions, configuration, runs, snapshots, events, messages, plan revisions, tool invocations, approvals, context and memory references, checkpoints, artefacts, delegation and outcomes | 10.5 | Extend | Runs, snapshots, events and checkpoints exist. The rest are Phases 2, 5, 6 and 8 | Phases 2, 5, 6, 8 |

## Section 11 — Lifecycle, reliability, recovery

| ID | Requirement | Spec § | Status | Location | Evidence |
| --- | --- | --- | --- | --- | --- |
| 11.1 | The thirteen lifecycle states are supported | 11 | Reuse | All thirteen declared in `AgentRunEntity`; `INTERRUPTED` added as the honest process-death state | `AgentRunEntity`, `AgentFoundationMigrationTest` |
| 11.2 | Recovery accounts for an incomplete model request, an interrupted stream, an unknown tool outcome, a completed-but-unrecorded tool call, an outstanding question, a pending approval, an interrupted compaction and a restart | 11.1 | **Reuse** | All eight cases have a recorded state: model interruption and unknown outcome as events, the question in the checkpoint, a pending approval as a `PENDING` row plus `WAITING_FOR_USER` | `AgentInteractionBrokerTest.requestingApprovalPutsTheRunIntoWaitingForTheUser` |
| 11.3 | The runtime does not blindly repeat non-idempotent actions whose previous outcome is unknown | 11.1 | Reuse | `TOOL_CALL_OUTCOME_UNKNOWN` prevents replay | `AgentRuntime`, `AgentRunReplay` |
| 11.4 | Configurable execution limits: exchanges, duration, tool calls, tokens, delegation budgets, retries, resources | 11.2 | **Reuse** | `AgentBudgets` carries exchange, duration, tool-call and delegation ceilings; tool rounds are deliberately uncapped at the owner's direction | `AgentRuntime.RunLimits.from`, `AgentConfigurationPersistenceTest` |
| 11.5 | Limits are configurable at system, agent-default and per-run level | 11.2 | **Reuse** | `RunLimits.SYSTEM_DEFAULT` is the floor; an agent may tighten and never widen; the resolved limits are frozen in the session and carried in the checkpoint | `AgentRuntime.RunLimits.from`, `AgentPauseCheckpointTest.theBudgetSurvivesTheCheckpointRoundTrip` |
| 11.6 | Reaching a limit records what happened and preserves partial work, and is never presented as completion | 11.2 | Reuse | `LIMIT_REACHED` is its own terminal status | `AgentRunEntity` |
| 11.7 | Failures distinguish model, tool, permission, missing capability, external integration, context retrieval, resource exhaustion, cancellation, interruption and unexpected internal errors | 11.3 | Extend | Model failure, tool failure, permission denial and interruption are distinct events. The rest have no producer yet | Phases 5, 6 |
| 11.8 | A failed operation does not erase the history of preceding work | 11.3 | Reuse | Append-only ledger | `AgentRunLedger` |

## Section 12 — Open ecosystem

| ID | Requirement | Spec § | Status | Location | Evidence |
| --- | --- | --- | --- | --- | --- |
| 12.1 | Supports first-party, user-created, imported, community-published, privately shared and agent-created agents | 12 | Extend | user, synced and bundled sources exist and work | `GuruAgentRepositoryImpl` |
| 12.2 | Discovery is registry-driven and the UI derives identity, purpose, configuration, availability and capabilities from the installed definition | 12 | Extend | The Agents room is registry-driven. `GuruAgentSelector` is a hardcoded enum and violates this | Phase 7 |
| 12.3 | Each agent has a machine-readable capability manifest describing operations, tools, permissions, dependencies, runtime versions, integrations, input and output types, memory requirements and security characteristics | 12.1 | **Reuse** | `AgentCapabilityManifest` on the agent, persisted as its own column | `AgentConfigurationValidatorTest`, `AgentConfigurationPersistenceTest` |
| 12.4 | The manifest is validated before an agent is allowed to execute | 12.1 | **Reuse** | `AgentConfigurationValidator.validate` runs in `spawn` before a run row is written; a refusal names the cause and starts nothing | `AgentConfigurationValidatorTest` (20 tests) |
| 12.5 | Provenance and trust: publisher, installed version, trust, requested permissions, capability changes, local modification, update validation | 12.2 | **Extend** | Publisher, version and requested permissions exist; the capability diff and its gate are built. Local-modification detection is not | `AgentUpdateReview` |
| 12.6 | An update does not silently grant new permissions, and a user can review changes and revert | 12.2 | **Reuse** | `AgentUpdateReview.between` diffs the versions and `dispositionFor` gates on an added tool, not on a version number | `AgentUpdateReviewTest` (13 tests) |
| 12.7 | Agents can propose new tools, skills, prompts, procedures, agents and module improvements | 12.3 | Extend | Authoring verbs exist: `createAgent`, `editAgent`, `deleteAgent`, `setAgentEnabled`, `publishAgent` | `AgentAuthoringToolDefinitions` |
| 12.8 | Creation, validation, approval and activation are separate stages, and an agent cannot self-authorise | 12.3, 13 | Extend | Publishing goes through review server-side. There is no local validation stage | Phase 8 |

## Section 13 — Security and digital sovereignty

| ID | Requirement | Spec § | Status | Location | Evidence |
| --- | --- | --- | --- | --- | --- |
| 13.1 | Centralised tool permission enforcement | 13 | Reuse | `ToolDispatcher` on both paths | `DispatcherRefusalTest` |
| 13.2 | Scoped data access | 13 | **Reuse** | `AgentMemoryScope` and `AgentContextScope` on the agent, enforced in code over what the brain returns | `AgentMemoryServiceTest`, `AgentConfigurationPersistenceTest` |
| 13.3 | Explicit memory permissions | 13 | **Reuse** | Read, write, shared-read and shared-propose are separate flags; a write is its own permission | `AgentMemoryServiceTest.writingIsASeparatePermissionFromReading` |
| 13.4 | Approval for restricted actions | 13 | **Reuse** | The gate refuses to dispatch until a matching granted approval exists, and consumes it | `AgentApprovalGateTest`, `AgentApprovalEnforcementTest` |
| 13.5 | Isolation of untrusted or externally sourced agents | 13 | **Reuse** | `AgentToolNarrowing` holds an imported agent to fewer tools than it was published with and refuses any name outside the published grant, so the catalogue review stays the ceiling; the published grant is never rewritten, because it is what a later version is diffed against | `AgentToolNarrowingTest` (9 tests), `AgentConfigurationPersistenceTest.aRestrictionCannotAddAToolTheCatalogueNeverGranted`, `aToolRestrictionRoundTripsAndLeavesThePublishedGrantIntact` |
| 13.6 | Secure storage of sensitive event payloads | 13 | Reuse | `ProtectedData`, `DataProtector`, `ProtectedField` exist | `core/security` |
| 13.7 | Redaction of secrets from ordinary activity views | 13 | **Reuse** | Credential-named arguments and whole credential-bearing tools are withheld from the ledger with their reason recorded; the rule decides by name and tool, never by inspecting a value, and the cost is under-redaction rather than hiding ordinary work | `AgentArgumentRedactionTest` |
| 13.8 | Auditability of permission decisions | 13 | Reuse | `TOOL_PERMISSION_GRANTED` and `TOOL_PERMISSION_DENIED` | Commit `2ce9be0` |
| 13.9 | Version and provenance tracking | 13 | Extend | Version exists; provenance is Phase 8 | Phase 8 |
| 13.10 | Protection against prompt injection and untrusted content | 13 | Reuse | Tool output is treated as data and never as instructions; no path executes model-supplied code without the dispatcher | `ToolSecurityPolicy` |
| 13.11 | Safe handling of tool outputs and external data | 13 | Reuse | Argument repair and the security policy run before every execution | `ToolArgumentRepair`, `ToolSecurityPolicy` |
| 13.12 | Explicit ownership and deletion rules | 13 | Reuse | User rows untouchable by sync; synced rows removable only by retraction | `GuruAgentRepositoryImpl` |
| 13.13 | Agent instructions are not security controls, and enforcement lives in the dispatcher and data layer | 13 | Reuse | `NEVER_SPAWNABLE` is enforced in code, not in a prompt | `AgentRuntime.allowedTools` |

## Section 14 — Scalability and concurrency

| ID | Requirement | Spec § | Status | Location | Evidence |
| --- | --- | --- | --- | --- | --- |
| 14.1 | The data model supports independent runs without assuming one permanent active agent | 14 | Reuse | `agent_runs` is keyed by run, not by agent | `AgentRunEntity` |
| 14.2 | The architecture distinguishes multiple installed agents, multiple runs per agent, multiple runs per conversation, parent-child, concurrent, sequential and paused runs | 14 | Reuse | All representable: agent_id, conversation_id, parent_run_id, status | `AgentRunEntity` |
| 14.3 | The interface reflects the actual execution model and never implies concurrent work the runtime cannot do | 14 | **Reuse** | `MAX_CONCURRENT_RUNS = 1`; the presence strip draws one node per live run and there is one | `AgentDelegationPolicyTest.concurrencyIsConfiguredSequentialTodayAndSaysSo` |
| 14.4 | Concurrency limits are runtime policy, not assumptions embedded in schema | 14 | **Reuse** | `AgentDelegationPolicy.MAX_CONCURRENT_RUNS` and `admitsAnother`; raising it is a constant, not a migration | `AgentDelegationPolicyTest` |

## Section 15 — Implementation strategy

The eight phases are tracked as work items rather than as requirements. Their state is in
the phase sections below.

| Phase | Status | Evidence |
| --- | --- | --- |
| 1 Repository and architecture reconciliation | **Complete** | `REPOSITORY_RECONCILIATION.md`, `phase1-reconciliation.md` |
| 2 Agent definition and configuration | **Complete** | `f0ee407`; migration 36 to 37; `AgentConfigurationValidatorTest`, `AgentConfigurationPersistenceTest` |
| 3 Durable run and event foundation | **Complete** | Migration 35 to 36, `AgentFoundationMigrationTest` |
| 4 Runtime instrumentation | **Complete** | Commits `8fcfb6d`, `45dbc14`, `53d4152`, `f12ef61`, `2ce9be0`; `PHASE4_VERIFICATION.md` |
| 5 Communication and control | **Complete** | Migration 37 to 38; `AgentInteractionBrokerTest`; `AgentInteractionBroker`; `AgentRuntime.steer` |
| 6 Context, memory and artefacts | **Complete** | `3b7b07d`; migration 38 to 39; `AgentArtefactServiceTest` (13), `AgentMemoryServiceTest` (12) |
| 7 Agent Room | **Complete** | `8fe1587`; `AgentRoomAssemblerTest` (21), `AgentPlanRecordTest` (10), `AgentDecisionRecordTest` (12), `AgentPlanStructureTest` (6). Every area draws a recorded fact |
| 8 Advanced autonomy and ecosystem | **Complete** | `16c7fb7`, `1e5be9a`; `AgentDelegationPolicyTest` (19), `AgentUpdateReviewTest` (13), `AgentDelegationWiringTest` (5), `AgentScheduleRulesTest` (7). Delegation, update trust and scheduled execution are real |

## Section 16 — Acceptance criteria

| ID | Criterion | Status | Evidence |
| --- | --- | --- | --- |
| 16.1 | Agents have independent, versioned identities and configurations | **Reuse** | `definitionVersion` plus `agent_definition_versions` | `AgentConfigurationPersistenceTest` |
| 16.2 | Visibility and autonomy are configurable per agent | **Reuse** | Both are per-agent columns decoded into their own types | `AgentConfigurationPersistenceTest` |
| 16.3 | Configuration changes do not rewrite previous runs | Reuse | Snapshots are values on the run row |
| 16.4 | The system supports new agents without application hardcoding | Extend | Registry-driven except `GuruAgentSelector` |
| 16.5 | An agent run receives a durable identity | Reuse | `AgentRunEntity.runId` |
| 16.6 | Execution events are captured as they happen | Reuse | `AgentRunLedger.append` |
| 16.7 | A run can continue beyond one model call | Reuse | The round loop |
| 16.8 | Tool calls and results are recorded accurately | Reuse | Tool event family |
| 16.9 | Failures, interruptions and limits are visible | Reuse | Distinct events and statuses |
| 16.10 | GURU and agents communicate during execution | **Reuse** | Broker records both directions; `steer` enables mid-run initiation | `AgentInteractionBrokerTest` |
| 16.11 | The user can communicate directly with an active agent | **Reuse** | `AgentRuntime.steer` plus `ingestPendingUserMessages` | `AgentInteractionBrokerTest` |
| 16.12 | Agents can initiate questions and requests | **Reuse** | Questions through `askAssistant`, and six kinds of report through `reportToPerson`, with the pausing split proved | `AgentInitiatedMessagesTest`, `AgentReportToolTest` |
| 16.13 | Assignment amendments retain their history | **Reuse** | Amendments are messages; the run's `assignment` is untouched | `AgentInteractionBrokerTest.theAssignmentIsNeverRewrittenByAnAmendment` |
| 16.14 | Communication authorship is unambiguous | **Reuse** | Stored `author_type` and `recipient_type` | `AgentInteractionBrokerTest.aMessageFromTheAgentIsNotAttributedToGuru` |
| 16.15 | The Agent Room exposes actual execution evidence | **Reuse** | Every field comes from a row; `AgentRoomAssembler` is the single reader | `AgentRoomAssemblerTest` (21 tests) |
| 16.16 | The user can configure visibility | **Reuse** | `AgentVisibility` per agent, persisted as its own column; the settings surface is Phase 7 | `AgentConfigurationPersistenceTest.visibilityRulesRoundTripPerCategory` |
| 16.17 | Supported reasoning information can be captured | Reuse | `MODEL_REASONING_COMPLETED` |
| 16.18 | Unsupported reasoning capabilities are reported honestly | Reuse | Conditional capture |
| 16.19 | Generated explanations are distinguished from factual runtime events | **Reuse** | A decision is an event the agent wrote; reasoning is provider output; the two are separate event types read into separate fields | `AgentDecisionRecordTest` |
| 16.20 | Runs and histories survive process death | Reuse | Persisted run plus checkpoint plus recovery scan |
| 16.21 | Historical configuration remains intact | Reuse | Immutable snapshots |
| 16.22 | Event ordering is reliable | Reuse | Unique per-run sequence |
| 16.23 | Previous tool results and communications remain inspectable | Reuse | Ledger queries |
| 16.24 | Recovery does not silently duplicate external actions | Reuse | `TOOL_CALL_OUTCOME_UNKNOWN` |
| 16.25 | Agents cannot bypass central tool permissions | Reuse | `DispatcherRefusalTest` |
| 16.26 | Data and memory access remain scoped | **Reuse** | The filter runs in code over the brain's results, never in a prompt | `AgentMemoryServiceTest` |
| 16.27 | Approval requirements are enforced by the runtime | **Reuse** | `AgentApprovalGate` sits in the dispatch loop before the dispatcher; a held call is never dispatched | `AgentApprovalGateTest` (16 tests), `AgentApprovalEnforcementTest` (11 tests) |
| 16.28 | Imported agents cannot silently expand their authority | **Reuse** | An added tool forces an approval decision before the update lands | `AgentUpdateReviewTest.aNewToolStopsTheUpdateForApproval`, `aVersionNumberCannotSmuggleAGrantPastTheCheck` |
| 16.29 | The Portal remains the main conversation with GURU | Reuse | Unchanged |
| 16.30 | Agent nodes open the dedicated Agent Room | **Reuse** | `AgentPresenceStrip` takes an open callback per node | `AgentPresence.kt` |
| 16.31 | The Agent Room is a functional dashboard, not a chat transcript | **Reuse** | Eight tabs, overview first, chat one of them | `AgentRoomScreen.kt` |
| 16.32 | Closing the room does not automatically cancel work | Reuse | Execution is independent of UI |
| 16.33 | The interface represents actual runtime state | **Reuse** | Status comes from `agent_runs.status`; nothing is computed for display | `AgentRoomAssembler` |

## Section 17 — Decisions and principles

The twenty principles in section 17 are architectural commitments. Each is satisfied by
the section it maps to; the table records only the ones not already covered above.

| ID | Principle | Status | Evidence |
| --- | --- | --- | --- |
| 17.1 | GURU is the orchestrator; agents are specialist workers | Reuse | `spawnAgent` returns a report |
| 17.2 | An agent is a persistent definition with capabilities and configuration, not just a prompt | Extend | Definition exists; configuration is Phase 2 |
| 17.3 | Every execution has its own run identity and immutable snapshot | Reuse | `AgentRunEntity` |
| 17.4 | Requested and effective configuration are recorded separately | Reuse | Both columns |
| 17.5 | Runtime events are the source of truth | Reuse | Append-only ledger |
| 17.6 | Communications are recorded as messages with explicit authorship | **Reuse** | `agent_messages` | `AgentInteractionBrokerTest` |
| 17.7 | GURU, the agent and the user collaborate directly | **Reuse** | Broker plus `steer` plus `resume` form the three-way channel | `AgentInteractionBrokerTest` |
| 17.8 | The user can steer an agent during execution | **Reuse** | `steer` queues; the loop ingests at a round boundary | `ingestPendingUserMessages` |
| 17.9 | Agents can initiate communications when configured | **Reuse** | Six kinds, gated by the agent's own `notifications` preferences through `AgentNotificationPolicy`, so whether one may interrupt is the person's setting rather than the agent's claim | `AgentInitiatedMessagesTest`, `AgentNotificationPolicyTest` |
| 17.10 | Reasoning and activity visibility are configurable per agent | **Reuse** | `AgentVisibility` with eleven categories and per-category capture, display and detail | `AgentConfigurationValidatorTest.visibilityDefaultsCaptureAndDisplayEveryCategory` |
| 17.11 | The default experience is meaningfully transparent | **Reuse** | `AgentVisibility.TRANSPARENT` is the default on every agent | `AgentConfigurationValidatorTest.visibilityDefaultsCaptureAndDisplayEveryCategory` |
| 17.12 | Model and runtime limitations are represented honestly | Reuse | Conditional capture |
| 17.13 | Visibility, autonomy and permissions are independent | **Reuse** | Three separate types on `GuruAgent`, written and read independently | `AgentConfigurationPersistenceTest.visibilityRulesRoundTripPerCategory` |
| 17.14 | Agent memory, working context and shared knowledge stay distinct | **Reuse** | Assignment on the run row, working state in the checkpoint, agent memory in its own brain domain, shared knowledge gated by scope | `AgentMemoryServiceTest`, `AgentRunCheckpointEntity` |
| 17.15 | The Agent Room is a dashboard with dedicated conversational and operational areas | **Reuse** | Eight tabs, overview first, chat one of them | `AgentRoomScreen.kt` |
| 17.16 | The architecture supports an open-ended ecosystem | Extend | Registry-driven; enum removed in Phase 7 |
| 17.17 | Concurrency may evolve without a data-model redesign | Reuse | Run-scoped schema |
| 17.18 | Security enforcement remains outside agent prompts | Reuse | Dispatcher |
| 17.19 | Self-improvement without unrestricted self-authorisation | Extend | `NEVER_SPAWNABLE`; Phase 8 adds the validation stage |
| 17.20 | Migrations require explicit review | Superseded | AD-010: the owner's direct instruction governs, with the plan's safety rules |

## Section 18 — Final architectural principle

| ID | Requirement | Status | Evidence |
| --- | --- | --- | --- |
| 18.1 | GURU is an extensible cognitive environment, not a collection of chatbots with different names | Extend | Definition, run and ledger exist; the collaborative and contextual halves are Phases 5 and 6 |
| 18.2 | The user sees what agents are doing, understands why, communicates while they work and controls the authority granted | Build | Phases 5 and 7 |
| 18.3 | The execution history is real, the communication is real, the configuration is real, the autonomy is enforceable | Extend | History and execution are real; communication, configuration and autonomy are Phases 2 and 5 |
