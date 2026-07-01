---
name: Loop exit trigger improvements — 4 of 5 remaining
description: Research-backed improvements to agent ReAct loop exit handling. Item 1 shipped 2026-03 (empty-response retry) and 2026-05-11 (added jitter + delay to all retry layers). Items 2-5 still pending.
type: project
originSessionId: d5f73307-a75f-42c8-9788-27a9597b6bd2
---
## Status: 1 of 5 SHIPPED, 4 remaining

Research completed 2026-03-30 comparing 9 open-source agentic tools (Claude Code, Cline, Aider, OpenHands, SWE-agent, Codex CLI, Continue.dev, Goose, Amazon Q CLI) against our SingleAgentSession loop exit triggers. Code has since moved from `SingleAgentSession.kt` to `AgentLoop.kt`.

## What Was Already Fixed (v0.40.1)

- **Text-only responses**: Added 4-nudge escalation before allowing implicit completion. Novel approach — no other tool does this.
- **get_run_output empty console**: Fixed lazy editor init + wrapper unwrapping.

## Improvements To Implement (ordered by priority)

### 1. SHIPPED — Empty LLM Response: Retry With Jittered Backoff
- ✅ **2026-03**: Retry now happens up to `MAX_CONSECUTIVE_EMPTIES = 3` times with `EMPTY_RESPONSE_ERROR` nudge + temperature escalation to 1.0 (OpenHands pattern). After 3 consecutive empties → `FailureReason.EMPTY_RESPONSES`. Path: `AgentLoop.kt` Case C (~1454-1490).
- ✅ **2026-05-11**: Added jittered exponential backoff between retries via `computeBackoffMs` helper. Same helper added to all 4 retry layers (Layer 1 API errors got jitter, Case B text-only and Case C empty got [0, 1-2s] gaps, compaction got 200ms-base breather). Spec: `docs/superpowers/specs/2026-05-11-retry-backoff-jitter-design.md`.

### 2. MEDIUM — Max Iterations: Best-Effort State Dump Instead of Hard Failed
- **Current**: `SingleAgentSession.kt:557-562` — returns `Failed("Reached maximum iterations")`
- **SWE-agent pattern**: Submits current state as best-effort answer on limit
- **Fix**: Use same `ContextRotated` pattern (externalize rotation-state.json) instead of hard `Failed` when max iterations hit
- **Why**: Consistent with budget exhaustion behavior. Preserves work done.

### 3. ~~MEDIUM — API Error Model Fallback (SWE-agent Pattern)~~ **DROPPED 2026-05-12**
- User stated: "model fallback is not a solution. i was planning to remove it or gate it to a settings."
- Do NOT add new model-fallback recovery paths. Existing `ModelFallbackManager` is on the path to removal or being gated behind a setting.
- Replace this slot with: empty-response recovery via **same-model brain recycle** + **streaming → non-streaming fallback** + **endpoint fallback** (`/.api/llm/chat/completions` → `/.api/completions/stream`). See [[feedback_no_model_fallback_for_empties]].

### 4. LOW — User Cancellation Change Revert (Cline Pattern)
- **Current**: Returns `Completed("cancelled")` — no revert offer
- **Cline**: Offers to undo all session changes on cancel
- **Fix**: On cancellation, show "Revert changes?" option using existing `RollbackManager`
- **Why**: Safety net for mid-edit cancellations. Already have RollbackManager infrastructure.

### 5. LOW — Malformed Tool Call Format Examples (SWE-agent Pattern)
- **Current**: Re-prompts with "your JSON was malformed, retry"
- **SWE-agent**: Injects concrete correct format examples alongside error
- **Fix**: Include a tool call example in the retry prompt
- **Why**: Marginally better recovery rate. Low priority since forceTextOnly fallback exists.

## Key Research Findings (for context)

- Our `ContextRotated` with structured rotation-state.json is **unique** — no other tool does this
- Our 4-nudge escalation for text-only responses is **novel** — no other tool nudges
- Our `CompletionGatekeeper` with multiple gates (plan + self-correction + loop guard) is **unique** — others just accept first completion
- Most modern tools (Claude Code, Cline, Codex CLI) use context-budget-driven termination, not fixed iteration limits
- Our 5-retry API error handling is the most aggressive (others use 3)

## Files To Modify (for remaining items 2-5)

- `agent/src/main/kotlin/com/workflow/orchestrator/agent/loop/AgentLoop.kt` — items 2, 3 (path renamed from SingleAgentSession.kt)
- `agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentController.kt` — item 4 (cancel revert UI)
- `agent/CLAUDE.md` — update docs after changes
