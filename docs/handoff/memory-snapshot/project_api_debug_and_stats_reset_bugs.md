---
name: Mid-session resets — api-debug counter, token/cost totals, sub-agent approval gate
description: Three related bugs where state that should be conversation-scoped is instead narrowly-scoped (executeTask, AgentLoop, sub-agent), causing overwrites/resets/re-prompts. Failing tests landed on feature/telemetry-and-logging.
type: project
originSessionId: 634a83e9-43b9-4fd2-b6de-7542a157be3e
---

Three bugs, same root cause shape: state that should live at **session/conversation** scope is instead scoped to a narrower lifetime (executeTask closure, AgentLoop instance, or sub-agent run), so a layer above keeps resetting it.

## Bug 1 — api-debug `call-NNN-*.txt` overwritten

`AgentService.executeTask()` at `agent/.../AgentService.kt:1042` allocates
`val sharedApiCounter = java.util.concurrent.atomic.AtomicInteger(0)` locally. Every
user message triggers a new `executeTask` → fresh counter at 0, same `sessions/{sid}/api-debug/`
directory → turn 2's first dump clobbers `call-001-*.txt` from turn 1.

Fix shape: lift the counter to session scope (e.g., keyed by sessionId in `AgentService`
or on the `Session` model) so multiple `executeTask` invocations in the same session
share one monotonic counter.

Failing test: `core/src/test/kotlin/.../ApiDebugCounterResetBugTest.kt`

## Bug 2 — token/USD totals snap back mid-conversation

`AgentLoop.totalInputTokens` / `totalOutputTokens` / `totalCostUsd` are instance fields
(`agent/.../loop/AgentLoop.kt:354-360`), initialized to 0/null. `AgentService.executeTask()`
builds a fresh `AgentLoop` per turn at line 1358. Turn 2's first API call invokes
`onSessionStats(modelId, tokensIn=<just-this-call>, ...)`, which hits
`chatStore.updateSessionStats` → `set({ sessionStats: stats })` (replace, not accumulate).
UI displays the reduced number.

Persisted totals in `sessions.json` (via `MessageStateHandler.updateGlobalIndex` summing
api history) are correct — only the live UI signal is wrong.

Fix shape: seed the new `AgentLoop` with the session's running totals, or accumulate in
`AgentController` before forwarding to the dashboard.

Failing test: `agent/src/test/kotlin/.../loop/SessionStatsResetBugTest.kt`

## Bug 3 — sub-agent re-prompts approval for tools already ALLOWED_FOR_SESSION

`SubagentRunner.kt:197-267` builds the sub-agent's `AgentLoop` and forwards the parent's
`approvalGate` (line 256) but does NOT forward the parent's `SessionApprovalStore`. The
loop falls back to `AgentLoop.kt:293` default — a brand-new empty store. So every
sub-agent spawn re-asks the user for edit_file / create_file / revert_file even when the
parent already clicked "Allow for session".

Parent-only path is fine — `AgentController.sessionApprovalStore` is a `val` that is
cleared only on `newChat()` / `resetForNewChat()` / `dispose()` / `new_task` handoff
(AgentController.kt:1718, 1817, 2863). Multi-turn parent approvals persist correctly
(sanity test confirms).

Fix shape: add `sessionApprovalStore` to `SubagentRunner` constructor, forward the
parent's store all the way from `AgentService` / `SpawnAgentTool`, and pass it to
the sub-agent's `AgentLoop()`. Sanity-check: the defense-in-depth test (third test in
the bug file) verifies that after the fix, sub-agent approvals ALSO propagate up to
parent's subsequent turns, since both sides share one store reference.

Failing test: `agent/src/test/kotlin/.../loop/ApprovalGateReprompingBugTest.kt`

**Why:** all three surfaced from user observation of the agent tab. Not hypothetical.
**How to apply:** when implementing the fix, treat this as a conversation-lifecycle
  invariant — state that must persist across follow-up messages OR across sub-agent
  boundaries lives on the session/controller, not the turn or the worker.
