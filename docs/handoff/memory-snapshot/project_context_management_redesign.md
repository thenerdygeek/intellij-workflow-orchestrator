---
name: context-management-redesign-two-tier-shipped-2026-05-18
description: Two-tier memory model (L1 pre-user handoff + L2 anchor user + L3 optional post-user working memory + L4 verbatim tail) shipped 2026-05-18 on bugfix branch in commits 02da6f6b2 → 7d9447af6. Replaces the 2026-05-17 single-stage CC-style compactor, which itself replaced the 3-stage Cline port. Manual smoke pending user.
metadata:
  node_type: memory
  type: project
  originSessionId: 5c18cf1a-7e75-4ed9-aa55-b97c23ed7d38
---

# Context Management — Two-Tier Redesign SHIPPED 2026-05-18

**Branch:** `bugfix`
**Spec:** `docs/superpowers/specs/2026-05-18-context-compaction-two-tier-design.md`
**Plan:** `docs/superpowers/plans/2026-05-18-context-compaction-two-tier.md`

## 10 commits (all on bugfix)

| # | Commit | Subject |
|---|---|---|
| 1 | `02da6f6b2` | ContextManager user-message counter and two-tier state fields |
| 2 | `6abde230c` | ContextManager.findLastUserIndex helper |
| 3 | `438a42185` | ContextManager.findTokenWeightedCutForLayer4 helper |
| 4 | `377bee01c` | ContextManager.snapToToolBoundary helper |
| 5 | `13c5a561b` | Split summarizePrefix into summarizePreUser + summarizePostUser |
| 6 | `8f81f859d` | Rewrite ContextManager.compact() with two-tier layered summarization |
| 7 | `8339864ad` | Two-tier compaction Case A/B, edge cases, failure modes tests |
| 8 | `ac2794a9d` | AgentLoop iterationsSinceLastUser counter wired into compact() calls |
| 9 | `04e8e43af` | AgentService.compactContext passes iterationsSinceLastUser=MAX |
| 10 | `7d9447af6` | docs(agent): document two-tier compaction flow in CLAUDE.md |
| 11 | `48eddb835` | fix: exclude synthetic nudges from Case B compaction counter (review fix) |
| 12 | `2ff4b867f` | fix: reset iterationsSinceLastUser on user-input channel arrivals (review fix) |
| 13 | `640225208` | test: partial-success Compacted outcomes (review fix) |

## Why the rewrite (pathologies in the 2026-05-17 single-stage design)

The previous CC-style design used `KEEP_FRACTION = 0.30` **by message count** and biased the cut to the nearest `role == "user"` boundary via `findSafeSplitPoint`. Two pathologies on long agent runs:

1. **Compaction barely moves the needle.** A 55-message session with one old user at index 15 → forward scan from index 38 finds no user → backward scan walks to 15 → 40 messages (73%) kept verbatim. Tokens-freed-per-compaction had no floor.
2. **Immediate re-compaction without progress.** Pathology 1's huge tail can leave utilization still ≥88% → next iteration re-triggers compaction → same shape → no progress.

Root cause: single-tier memory model (one summary + one 30% tail) cannot represent "long-term handoff" and "recent working memory" distinctly.

## New layered structure

```
[L1 assistant]  Pre-user handoff summary (omitted if pre-user prefix is empty)
[L2 user]       The most recent user message, verbatim
[L3 assistant]  Post-user working memory summary (omitted if iters ≤ 5 AND util OK)
[L4 tool/...]   Most recent ~20% of post-user TOKENS, verbatim (token-weighted!)
```

L4 is **token-weighted** (not message-count) — `findTokenWeightedCutForLayer4` walks backward from end summing `estimateMessageTokens` until 20% of `effectiveMaxInputTokens()` reached. Then `snapToToolBoundary` ensures the cut lands on a `tool` message so L4 doesn't start with `assistant` (avoids the `MessageSanitizer.kt:70` consecutive-assistant merge).

## Case A vs Case B (the headline cost optimization)

Driven by `totalUserMessageCount == lastCompactionUserMessageCount`:

- **Case A** — new user message since last compaction (or first compaction). LLM-summarize L1 fresh (folding in `previousPreUserSummary`). Build L3 from scratch.
- **Case B** — no new user. **Reuse `previousPreUserSummary` verbatim — no L1 LLM call.** L3 is re-summarized over `previousPostUserSummary` + accumulated new messages.

Long-quiet sessions go from 2 LLM calls per compaction (L1 + L3) to 1 (L3 only) — **50% cheaper**.

## The 5-iteration gate

L3 only builds if `iterationsSinceLastUser > 5` OR estimated post-L1 utilization ≥ 88%. The gate is a cost optimization; over-budget pressure always forces L3.

`iterationsSinceLastUser` is a NEW counter in `AgentLoop` (not the existing `iteration`, which doesn't reset on user/steering messages). It increments alongside `iteration++`, resets to 0 on steering drain.

## State tracked on ContextManager

```kotlin
private var previousPreUserSummary: String? = null
private var previousPostUserSummary: String? = null
private var totalUserMessageCount: Int = 0
private var lastCompactionUserMessageCount: Int? = null
```

`addUserMessage` / `addUserMessageWithParts` increment the counter. `restoreMessages` recomputes from saved history + nulls all three summary/counter state fields (one extra LLM call on first post-resume compaction is acceptable; persistence is out of scope).

## compact() signature change

```kotlin
suspend fun compact(
    brain: LlmBrain,
    hookManager: HookManager? = null,
    force: Boolean = false,
    iterationsSinceLastUser: Int = Int.MAX_VALUE,  // NEW
): CompactResult
```

Default `Int.MAX_VALUE` so manual/programmatic triggers always allow L3.

**Call sites — 4 physical (not 5/6):**
- `AgentLoop.kt:761` — Stage 0 utilization check
- `AgentLoop.kt:963` — context-overflow error recovery
- `AgentLoop.kt:1215` — timeout-recovery compaction
- `AgentService.kt:828` — `compactContext()` (manual Compact button delegates here)

`AgentController` Compact button is indirect (calls `AgentService.compactContext`). `SubagentRunner` has no direct call (shares `AgentLoop`).

## Two real spec bugs caught by Sonnet implementers

Worth remembering — both shipped fixed in the code:

1. **Step-14 `Failed` predicate gap** (Task 6 subagent): the spec's `l1Failed && l3Failed → Failed` check misses the case where L1 fails AND L3 was never *attempted* (e.g., post-user slice too small for the 20% token target → no L3 LLM call). Literal predicate evaluates `true && false = false` → wrongly returns `Compacted(summaryChars=0)`. **Fix in code:** "if any summarization was attempted AND none succeeded → `Failed`."

2. **`"RESULT of {toolName}:"` is wrong** — actual `MessageSanitizer.kt:70` prefix is `"TOOL RESULT:\n"` (plain, no tool name, no XML). Caught by the verification subagent before implementation started. Spec updated, code is correct.

## Two review-driven follow-up fixes (commits 11–13)

1. **`addNudgeMessage` is the API for AgentLoop's 7 synthetic-nudge call sites** (TEXT_ONLY_NUDGE, EMPTY_RESPONSE_ERROR, LOOP_SOFT_WARNING, length-cut, upstream-timeout, stripped-completion, feedback-request). It adds a `role="user"` message WITHOUT incrementing `totalUserMessageCount`. `addUserMessage` is now reserved for **real user input only**: session start, steering drain, `userInputChannel.receive()` arrivals. If you add a new nudge code path in AgentLoop, **use `addNudgeMessage`** or Case B will silently degrade.

2. **`iterationsSinceLastUser` reset for plan-card approval uses a class flag, not a direct assignment.** The other two `userInputChannel.receive()` sites (plan-mode reply at `AgentLoop.kt:1533`, max-mistakes feedback at `:1546`) reset the counter directly. But the plan-card approval at `:2210` lives inside `executeToolCalls` (a private class method), where the `iterationsSinceLastUser` local var in `run()` is out of scope. Workaround: a class field `userInputReceivedInToolCall` (Boolean) is set inside `executeToolCalls` and checked-and-reset in `run()` after `executeToolCalls` returns. Semantically equivalent because the counter is only read inside `compact()` which only fires at the top of the next loop iteration. If you refactor AgentLoop to move the counter into a state class, you can collapse this indirection.

## Three sneaky test-setup gotchas

For future test writers in this area:

1. **Message-size lower bound on L3.** A `"x".repeat(350)` message (~100 tokens via char/3.5) doesn't trigger L3 on a 10K-budget test — the slice's total tokens don't cross the 2000-token L4 target, so the token-weighted cut returns `sliceStart` and `snapToToolBoundary` returns the `messages.size` sentinel. Use `"x".repeat(1050)` (~300 tokens/msg) to actually exercise L3.
2. **MockK matchers need to match the full signature.** `LlmBrain.chat` has 4 params (`messages`, `tools`, `maxTokens`, `toolChoice`); `coEvery { brain.chat(any(), any()) }` won't match — must be `any(), any(), any(), any()`.
3. **`ApiResult.Error` is a sealed class.** Constructor needs `ErrorType.SERVER_ERROR, "msg"`, not just `"msg"`.

## What stayed unchanged

- `CompactResult` sealed class (Skipped / Cancelled / Failed / Compacted)
- 88% trigger threshold
- `deduplicateFileReads()` pre-pass
- `stripImagePartsFromAllMessages()`
- `reInjectActiveSkill()` / `reInjectActivePlan()`
- `slidingWindow(0.3)` Failed-fallback wiring at all caller sites
- `effectiveMaxInputTokens()` / `utilizationPercent()` / `lastPromptTokens` token tracking
- 28-method public `ContextManager` API (zero call-site signature breaks outside the 4 above)

## Header rename

L1 messages now use `"[Context Handoff — earlier conversation was compacted]\n…"`.
L3 messages use `"[Working Memory — agent activity since the user's last message was compacted]\n…"`.
The old `"[Context Summary — earlier conversation was compacted]"` header is gone. Two assertions in `ContextManagerSummarizationTest` updated to match.

## Verification status (2026-05-18)

- `:agent:test` — 3753 tests, 8 skipped, 2 failures. **Both failures are pre-existing and unrelated** (`AgentDebugControllerTest` flaky coroutine timing; `ToolDslSchemaParityTest` Jira DSL drift). Zero new regressions.
- `verifyPlugin` ✅ across IU-251 / IU-252 / IU-253
- **Code review:** Final-implementation review run by Opus subagent. Verdict: APPROVE WITH NITS. 0 critical / 2 important / 5 notes. Both important findings fixed in commits 11–12; coverage gap closed in commit 13. Full report at `docs/superpowers/reviews/2026-05-18-context-compaction-two-tier-review.md` (not committed).
- Manual IDE smoke — pending user. Trigger compaction in a long session, verify `[L1?][L2][L3?][L4...]` layered shape, verify Case B (re-compact without new user) reuses L1 verbatim (single LLM call instead of two).

## History (3 redesigns deep now)

1. **Event-sourced design** on `feature/context-management` — ABANDONED 2026-04-12 (~226 tests landed before abandonment).
2. **Cline 3-stage port** — shipped, replaced 2026-05-17.
3. **CC-style single-stage** (commits `4eea82cfd`/`edf979fb0`/`b995bef6a`/`e85f2bf91`) — shipped, replaced 2026-05-18.
4. **Two-tier (this redesign)** — current. Pathology fixes for long agentic runs.

## Critical references

- Spec: `docs/superpowers/specs/2026-05-18-context-compaction-two-tier-design.md`
- Plan: `docs/superpowers/plans/2026-05-18-context-compaction-two-tier.md`
- Code: `agent/src/main/kotlin/com/workflow/orchestrator/agent/loop/ContextManager.kt`
- Tests: `ContextManagerTwoTierTest.kt` (behavior) + `ContextManagerTwoTierHelpersTest.kt` (pure helpers) + `ContextManagerSummarizationTest.kt` (preserved)
- Docs: `agent/CLAUDE.md` → "Context Management" → "Flow" section
- See also [[feedback_no_model_fallback_for_empties]] for `CompactResult.Failed` philosophy
- See also [[feedback_skip_subagent_reviews]] for why no code review was run
