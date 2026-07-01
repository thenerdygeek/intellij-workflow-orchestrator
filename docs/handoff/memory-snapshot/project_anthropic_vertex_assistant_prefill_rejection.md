---
name: anthropic-vertex-assistant-prefill-rejection
description: "2026-05-19 — Anthropic-via-Vertex rejects requests ending with assistant (\"This model does not support assistant message prefill\"). Triggered post-attempt_completion drain-at-exit + compact + reInjectActiveSkill/Plan. Fixed by MessageSanitizer Phase 6 (tail-is-user guard)."
metadata: 
  node_type: memory
  type: project
  originSessionId: 820af812-50be-4054-aadc-b22c8df53473
---

**Symptom:** `Server error (500): Anthropic (Sourcegraph Model Provider): unexpected status code 400: invalid_request_error "This model does not support assistant message prefill. The conversation must end with a user message." req_vrtx_...`

**Why:** The triggering path was opened up by the [[project_steering_queue_exit_drain]] commit (`13c01c8d0`):
1. LLM emits attempt_completion. Pre-13c01c8d0, the loop exited here.
2. `drainSteeringIntoContextOnExit` (Branch B at `AgentLoop.kt:2203`) now lets the loop continue.
3. Next iter calls `ContextManager.compact()`. The rebuild clears `messages` and emits `[L1, L2, L3?, L4...]`, so the previous "[Active Skill]" / "[Active Plan]" marker that survived in the middle of history is gone.
4. `reInjectActiveSkill` / `reInjectActivePlan` (`ContextManager.kt:882`, `:910`) append a fresh assistant marker at the very end.
5. Sanitizer ships request with assistant tail → Vertex rejects.

Secondary trigger (independent of skill/plan): L4 verbatim tail naturally ends with an assistant turn (e.g. partial-failure recovery paths that addAssistantMessage without a follow-up nudge).

**Fix:** `MessageSanitizer.sanitizeForAnthropic` Phase 6 — if `result.last().role == "assistant"`, append `ChatMessage(role="user", content="[Continue]")`. Symmetric counterpart to Phase 4's "starts with user" guard. Applies to both `/.api/llm/chat/completions` (SourcegraphChatClient) and `/.api/completions/stream` (BrainRouter) since both call the same sanitizer.

**Why:** This is an API-boundary invariant — every upstream path that could land assistant-at-tail (compaction reInject, verbatim L4, future regressions) is caught at the single sanitization seam. Phase 4 already has the symmetric head guard.

**How to apply:** Don't reintroduce code paths that append assistant messages at the end of `ContextManager.messages` expecting some downstream step to fix the tail — Phase 6 will rescue the request but the synthetic `[Continue]` user is wasted context. Prefer inserting markers (skill, plan) before the last user/tool message at the source.

**Files touched:**
- `core/src/main/kotlin/com/workflow/orchestrator/core/ai/MessageSanitizer.kt` — added Phase 6
- `core/src/test/kotlin/com/workflow/orchestrator/core/ai/MessageSanitizerTest.kt` — 4 new regression tests
- `agent/src/test/kotlin/com/workflow/orchestrator/agent/migration/CanonicalFormatScenarioTest.kt` — `wire.last()` → `wire.last { it.role == "assistant" }`
- `agent/src/test/kotlin/com/workflow/orchestrator/agent/migration/MixedCanonicalAndDriftScenarioTest.kt` — same adjustment

**Test pinning:** `MessageSanitizerTest.conversation ending with assistant gets a synthetic user suffix` plus 3 sibling cases.
