---
name: steering-queue-exit-drain
description: "AgentLoop steering queue drains only at iteration boundaries, so messages enqueued during the final pre-attempt_completion stream needed an exit-time drain to survive"
metadata: 
  node_type: memory
  type: project
  originSessionId: 2a32efca-77f2-42bc-9b83-5d746c1d3ae2
---

**SHIPPED 2026-05-18: Pre-exit steering drain in AgentLoop**

**Why:** Users reported that messages typed into the chat input while the agent was streaming its final `attempt_completion` response would silently disappear. Root cause: `AgentLoop.run` drains the steering queue only at Stage 0.5 of each iteration (~line 791). The `attempt_completion` exit path is a bare `return LoopResult.Completed(...)` (~line 2184) with no successor iteration, so anything enqueued between Stage 0.5 and the completion was dropped. `AgentController.onComplete` then `steeringQueue.clear()` (line 2420) deleted it before any consumer could see it. Same shape existed at `SessionHandoff` (line ~2201) and at every `makeFailed` return site.

**How to apply:** Two helpers in `AgentLoop`:
1. `drainSteeringIntoContextOnExit()` (suspend) — drains the queue, injects the text as a user message via `contextManager.addUserMessage(withEnvDetails(STEERING_MESSAGE_PREFIX + ...))`, fires `onSteeringDrained`, returns true. Caller sets `userInputReceivedInToolCall = true` and `return null` from `executeToolCalls` to continue the loop.
2. `promoteSteeringQueueOnFailure()` — drains and fires `onSteeringDrained` only (no context injection — continuing into a hard failure would just re-fail). Called from `makeFailed`.

Wiring:
- Completion branch (non-feedback): collapses pair first (so steering lands after a clean assistant turn, not a `tool_result`), then drains and continues.
- SessionHandoff branch: drains and continues; the LLM can re-issue `new_task` on a later turn if it still wants to.
- All `LoopResult.Failed` returns: chokepointed through `makeFailed`, which calls the failure helper.
- `LoopResult.Cancelled` is intentionally not patched — user pressed Stop.

Pinned by `AgentLoopExitDrainTest`. The `feedbackEnabled` branch (line 2147-2171) was always immune because it already continues the loop.

Related: see `[[feedback_dont_cite_claude_md]]` — CLAUDE.md described this architecture (steering invariant section) but the actual drain semantics need to be checked in code; CLAUDE.md drift would mislead. The fix was found by grepping `agent/src/main/kotlin/.../loop/AgentLoop.kt` directly.
