# Bug Analysis from Trace Data — 2026-03-21

## 5 Bugs Identified

### BUG 1 (CRITICAL): Token Widget Shows Cumulative API Tokens, Not Context Window

**What user sees:** 369.9K / 190K — makes no sense, context can't exceed limit.

**Root cause:** `SingleAgentSession.totalTokensUsed` (line 124) sums `usage.totalTokens` (prompt+completion) across ALL API calls in the task. If 17 iterations each send ~19K prompt tokens, total = ~323K. This cumulative sum is passed to `onProgress → AgentController.handleProgress() → dashboard.updateProgress()`.

But `maxInputTokens` (190K) is the per-call context window limit, not a session budget.

**The comparison is nonsensical:** cumulative API call total vs per-call context window limit.

**Fix:** Display `contextManager.totalTokens` (current context window usage for the NEXT API call) instead of `totalTokensUsed` (sum of all past calls).

### BUG 2 (HIGH): ReadAction Missing for PSI in detectProjectTools()

**Error:** `Read access is allowed from inside read-action only`

**Root cause:** `DynamicToolSelector.detectProjectTools()` (line 188-200) calls `JavaPsiFacade.findClass()` without `ReadAction` wrapper. Called from `ConversationSession.create()` on `Dispatchers.IO`.

**Fix:** Wrap in `ReadAction.compute<Set<String>, Exception> { ... }`.

### BUG 3 (HIGH): Tool Definitions Change Per Turn But reservedTokens Is Fixed

**Root cause:** `DynamicToolSelector.selectTools()` runs fresh on every user message with different keywords → tool count changes (24 → 62 → 24 → 30). But `ContextManager.reservedTokens` was calculated at session creation with the original tool set.

When 62 tools are sent instead of 24, actual token overhead is much higher than reserved. `reconcileWithActualTokens(promptTokens - reservedTokens)` produces wrong values because `reservedTokens` doesn't match actual tool def tokens sent.

**Fix:** Either stabilize tool set within a session OR recalculate reservedTokens when tool definitions change.

### BUG 4 (MEDIUM): BudgetEnforcer Recreated Each Turn With Stale Budget

**Root cause:** New `BudgetEnforcer` created per `SingleAgentSession.execute()` call using current `contextManager.remainingBudget()`. But if reservedTokens is stale (bug 3), the enforcer's thresholds are wrong.

### BUG 5 (LOW): SessionTrace Logs Wrong Value as "reservedTokens"

The trace field "reservedTokens" is actually `contextManager.currentTokens` (growing context size), not the tool+prompt reservation. Misleading for debugging.

## Token Flow Analysis (From Trace)

```
Session 1: "How does auth work?"
  - 5 iterations, each ~8-12K prompt tokens
  - Total API tokens: 49,537
  - Context window per call: ~8-12K (small, normal)

Session 3: "Disable authentication"
  - 17 iterations (!)
  - Each iteration: 14-22K prompt tokens (growing because context accumulates)
  - Total API tokens: 323,907
  - UI shows: 323.9K / 190K (cumulative vs per-call — apples vs oranges)

The ACTUAL context window never exceeded 22K per call.
The 323K is the sum of 17 API calls, NOT the context size.
```

## Why 17 Iterations?

1. LLM starts exploring → finds Spring Security config
2. Searches broadly → reads many files
3. Context grows → but budget enforcer doesn't trigger compression (thresholds wrong due to bug 3)
4. LLM loses track of earlier discoveries (compressed out) → re-fetches
5. "search_code" with wrong paths → errors → more iterations
6. Eventually makes the edit at iteration 13, then verifies for 4 more iterations
7. EFFICIENCY_RULES in prompt says "limit to 3-5 tool calls for questions" but this is a CODE CHANGE task, so the LLM doesn't self-limit
