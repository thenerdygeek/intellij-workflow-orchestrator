# Context Compaction — Two-Tier Memory Redesign

**Date:** 2026-05-18
**Status:** Draft — awaiting review
**Branch:** bugfix
**Predecessor:** `4eea82cfd` (Phase 1 — single-stage CC-style summarizer, rewritten from the 3-stage Cline port)

---

## Problem

Phase 1 collapsed the prior 3-stage Cline-port compactor into a single-stage Claude-Code-style summarizer
(`ContextManager.compact()`, `agent/src/main/kotlin/com/workflow/orchestrator/agent/loop/ContextManager.kt:359-499`).
That redesign shipped on this branch (`compact()` at `ContextManager.kt:375-452`) but exposes two
pathologies on long agent runs:

### Pathology 1 — Compaction barely moves the token needle

The split uses `KEEP_FRACTION = 0.30` (`ContextManager.kt:784`) by **message count**, then biases to the
nearest `role == "user"` boundary via `findSafeSplitPoint` (`ContextManager.kt:606-618`). Forward scan
first, backward fallback if nothing forward.

For a 55-message session where the only user message is at index 15 (rest are
assistant/tool turns from agent work), `targetSplitIdx = 38` → forward scan finds no user → backward scan
walks down to 15 → split at 15. Prefix = 15 messages summarized; tail = **40 messages kept verbatim
(73% of the conversation).**

If those 40 messages contain large tool results, the post-compaction context can still be over 88%.

### Pathology 2 — Immediate re-compaction without progress

When pathology 1 produces a tail that's still near 88% utilization, the next overflow re-triggers
compaction. The new compaction sees roughly the same shape, summarizes a small head, keeps a huge tail,
and burns another LLM round-trip for ~no progress. In the worst case the loop never converges.

### Root cause

A single-tier memory model (one summary + one 30% tail) cannot represent
"long-term context handoff" and "recent working memory" distinctly. The 30% tail is forced to serve both
purposes; biasing the cut to user boundaries makes the tail size unpredictable and uncorrelated with
token weight.

---

## Solution

Treat the conversation post-compaction as a **two-tier memory hierarchy**, anchored to the most recent
user message:

- **Tier 1 — long-term context handoff** (`L1`): a dense summary of everything *before* the most recent
  user message. Stable across compactions while the user is silent.
- **Tier 2 — active working memory** (`L3 + L4`): a summary of the older 80% of post-user tokens plus the
  newest 20% verbatim. Re-summarized every compaction (until a new user message arrives, then merged
  into Tier 1).

The user message itself sits between the tiers as L2, verbatim.

### Why this fixes both pathologies

- The cut is anchored to a **semantic boundary** (the latest user instruction), not a positional one. A
  long agent run with one old user message produces a small L2 + small L1 + large L3-summarized + small
  L4 — the *intended* shape.
- The 80/20 split inside the post-user slice is **token-weighted**, not message-count-weighted. This
  gives compaction a deterministic worst-case bound on tokens freed per call.
- Re-compaction in Case B (no new user) reuses the prior L1 verbatim — no LLM call. Only L3 is rebuilt.
  Compactions in long quiet stretches cost half as much.

---

## Layered structure (post-compaction)

The final message list always has one of these shapes:

| Shape | When | Contents |
|---|---|---|
| `[L1]` only | Degenerate: no user message in history | Single assistant summary; everything collapsed |
| `[L1][L2]` | Normal case, ≤5 iterations since last user **and** post-L1 utilization < 88% | Handoff + last user message verbatim |
| `[L1][L2][L3][L4...]` | >5 iterations since last user, **or** forced because post-L1 still ≥88% | Handoff + last user + post-user working summary + verbatim tail |

After every shape, the existing tail re-injection runs unchanged:

```
[shape above]
[assistant] active-skill content (if any)     ← reInjectActiveSkill()
[assistant] active-plan content (if any)      ← reInjectActivePlan()
```

### Role/alternation requirements

- L1 role = `assistant` (same as today's single summary).
- L2 role = `user`.
- L3 role = `assistant`.
- **L4 first message must NOT be `assistant`** — the `SourcegraphChatClient.sanitizeMessages()`
  consecutive-same-role merge would silently collapse L3 + L4-first into a single assistant message and
  lose the boundary. L4's first message must be a `tool` message (which coerces to `user` via the
  `"TOOL RESULT:\n"` prefix during sanitization — see `MessageSanitizer.kt:70`).
- This invariant is enforced by `snapToToolBoundary` (see Helpers).

---

## State tracked on `ContextManager`

Replace the current single `previousSummary: String?` field with two:

```kotlin
private var previousPreUserSummary: String? = null    // L1 content from the previous compaction
private var previousPostUserSummary: String? = null   // L3 content from the previous compaction
```

Add monotonic user-message counter for Case A vs. Case B detection:

```kotlin
private var totalUserMessageCount: Int = 0
private var lastCompactionUserMessageCount: Int? = null
```

Increment `totalUserMessageCount` in `addUserMessage` and `addUserMessageWithParts`
(`ContextManager.kt:116-133`). `restoreMessages` must recompute it by scanning the restored history
(see Resume-from-disk compatibility below).

### Case detection

At compaction time:

```kotlin
val isCaseB = lastCompactionUserMessageCount != null
           && totalUserMessageCount == lastCompactionUserMessageCount
```

- **Case A** — first compaction OR a new user message has arrived since the last compaction. L1 is
  rebuilt by LLM, folding `previousPreUserSummary` into the prompt (chained summary).
- **Case B** — no new user message. L1 is reused verbatim from `previousPreUserSummary` (no LLM call).
  L3 is rebuilt from `previousPostUserSummary` + prior L4 + new 80%.

---

## Compact signature change

The current signature is:

```kotlin
suspend fun compact(
    brain: LlmBrain,
    hookManager: HookManager? = null,
    force: Boolean = false,
): CompactResult
```

Add one parameter — the agent-loop iteration count since the most recent user message — so the
5-iteration gate has a definitive source of truth:

```kotlin
suspend fun compact(
    brain: LlmBrain,
    hookManager: HookManager? = null,
    force: Boolean = false,
    iterationsSinceLastUser: Int = Int.MAX_VALUE,  // default favors L3 for manual triggers
): CompactResult
```

Per-call-site values:

| Call site | File:line | Source of `iterationsSinceLastUser` |
|---|---|---|
| `AgentLoop` — Stage 0 utilization check | `AgentLoop.kt:753` | new `iterationsSinceLastUser` counter (see below) |
| `AgentLoop` — context-overflow error recovery | `AgentLoop.kt:954` | same counter |
| `AgentLoop` — timeout-recovery compaction | `AgentLoop.kt:1206` | same counter |
| `AgentService.compactContext` (programmatic) | `AgentService.kt:828` | `Int.MAX_VALUE` — manual triggers always allow L3 |

`AgentController`'s Compact button is **not** a direct call site — it calls `service.compactContext()`
at `AgentController.kt:638`, which then calls `ContextManager.compact()` inside `AgentService`.

`SubagentRunner` is also **not** a direct call site. Sub-agent compaction runs through the same
`AgentLoop` that `SubagentRunner` spawns, so the 3 `AgentLoop` call sites cover both orchestrator and
sub-agent paths.

**Total physical call sites to modify: 4** (3 in `AgentLoop`, 1 in `AgentService`).

### New counter in `AgentLoop`

The existing `iteration` variable (`AgentLoop.kt:691`, incremented at `:722`) is the total loop iteration
count and is **not reset** when a user/steering message arrives. We need a separate counter:

```kotlin
private var iterationsSinceLastUser: Int = 0
```

Reset to 0 in two places:
- When a user/steering message is added to `ContextManager` at the iteration boundary (around
  `AgentLoop.kt:779` where steering messages get drained from the queue).
- When a new user turn arrives via the public `executeTask` / `initiateTaskLoop` entry points.

Increment at the top of the ReAct loop body alongside `iteration++` (`AgentLoop.kt:722`).

The default `Int.MAX_VALUE` on the `compact()` parameter ensures any call site that forgets to thread
the count still gets safe behavior (L3 path is allowed; the only thing the gate prevents is wasted
summarization on tiny post-user slices).

---

## Algorithm

```
fun compact(brain, hookManager, force, iterationsSinceLastUser): CompactResult

1. Threshold gate
   if (!force && utilizationPercent() < 88.0) return Skipped(util)

2. PRE_COMPACT hook (unchanged from today)
   if hook returns Cancel → return Cancelled(reason)

3. Dedup pre-pass (unchanged — deduplicateFileReads())

4. lastUserIdx = findLastUserIndex(messages)
   if lastUserIdx == -1:
       degenerate path: summarize everything into a single L1, return Compacted(...)

5. case = detectCase()  // Case A | Case B

6. Build L1
   prefix = messages[0 until lastUserIdx]
   L1 = when (case) {
       Case A → summarizePreUser(brain, prefix, previousPreUserSummary)
                 // ?: l1Failed = true
       Case B → previousPreUserSummary  // no LLM call
   }
   if L1 == null && case == Case A: l1Failed = true

7. L2 = messages[lastUserIdx]

8. Decide whether to build L3 + L4
   postUserSlice = messages[lastUserIdx + 1 until messages.size]
   estimatedPostL1Utilization = ((2048 + postUserSlice.sumOf { estimateMessageTokens(it) })
                                 / effectiveMaxInputTokens().toDouble()) * 100.0
   // 2048 is the maxTokens cap on the summary LLM call — a conservative upper bound on L1 size.
   needsL3 = (iterationsSinceLastUser > 5) || (estimatedPostL1Utilization >= 88.0)
   if !needsL3 || postUserSlice.isEmpty():
       reassemble [L1, L2] + postUserSlice  // L4 = postUserSlice verbatim, no L3
       goto step 11

9. Build L3 + L4
   targetTokensInL4 = (0.20 * effectiveMaxInputTokens()).toInt()
   rawCutIdx = findTokenWeightedCutForLayer4(
                  sliceStart = lastUserIdx + 1,
                  sliceEnd = messages.size,
                  targetTokensFromEnd = targetTokensInL4
               )
   cutIdx = snapToToolBoundary(rawCutIdx, sliceStart = lastUserIdx + 1)

   if cutIdx >= messages.size:
       // post-user slice can't be split safely (single assistant, or all assistants)
       // → fall back to no-L3 path
       reassemble [L1, L2] + postUserSlice
       goto step 11

   L3InputMessages = when (case) {
       Case A → messages[lastUserIdx + 1 until cutIdx]
       Case B → synthesize:
                  - one assistant turn carrying previousPostUserSummary (if non-null)
                  - prior L4 (the tail messages preserved from the previous compaction —
                    actually: the messages in [lastUserIdx + 1 until previousCutIdx],
                    but we don't track previousCutIdx — see "Case B simplification" below)
                  - messages[lastUserIdx + 1 until cutIdx]
                Effective input = previousPostUserSummary + current post-user prefix
   }

   L3 = summarizePostUser(brain, L3InputMessages, priorPostSummary = previousPostUserSummary)
        // ?: l3Failed = true

   L4 = messages[cutIdx until messages.size]

10. Reassemble
    messages.clear()
    if L1 != null: messages.add(ChatMessage(role="assistant", content=formatSummaryMessage(L1)))
    messages.add(L2)
    if L3 != null: messages.add(ChatMessage(role="assistant", content=formatPostUserSummary(L3)))
    messages.addAll(L4)
    // L1 may be null when (a) prefix was empty (lastUserIdx == 0), or (b) the L1 LLM call failed
    // in Case A and we're proceeding with a partial-success Compacted result. L2 is always
    // present. L3 may be null when the iter gate didn't fire and utilization didn't force it.

11. Post-summary cleanup (unchanged)
    stripImagePartsFromAllMessages()
    reInjectActiveSkill()
    reInjectActivePlan()

12. Save state
    previousPreUserSummary = L1
    previousPostUserSummary = L3 ?: previousPostUserSummary  // keep prior if L3 was skipped/failed
    lastCompactionUserMessageCount = totalUserMessageCount

13. Invalidate + persist
    lastPromptTokens = null
    // The deleted range covers everything from index 0 up to (but not including) the start of L4
    // in the OLD message list. In the no-L3 path, that's 0 to lastUserIdx (only the pre-user
    // prefix was deleted, L2 was kept). In the L3 path, that's 0 to cutIdx (pre-user prefix +
    // post-user prefix were both deleted; L2 was kept in between but the range still spans the
    // full removed segment from the callback's perspective — L2 is preserved and reappears as
    // part of the new history).
    val deletedRangeEnd = if (L3 != null) cutIdx else lastUserIdx
    onHistoryOverwrite?.invoke(messages.toList(), 0 to deletedRangeEnd)
    // NOTE: This range is used by MessageStateHandler purely for logging / change-tracking; the
    // canonical persistence is the full new messages list passed as the first argument. The L2
    // message being kept "in between" two deleted segments is not a correctness issue — the
    // callback persists the new list verbatim, and consumers do not reconstruct anything from
    // the range alone.

14. Return CompactResult
    when {
        l1Failed && l3Failed → Failed("Both summarization calls failed")
        else                 → Compacted(tokensBefore, tokensAfter, summaryChars)
    }
```

### Case B simplification

The naïve Case B requires reconstructing "prior L4" — the messages preserved as the tail of the previous
compaction. But by the time we re-compact, those messages are simply the post-user messages that already
exist in `messages[lastUserIdx + 1..]` from index `lastUserIdx + 1` up to wherever new messages were
appended.

In other words: in Case B, the post-user slice **already contains** prior L4 (still present from last
time) plus new messages added since. We don't need to track the prior cut index — we just summarize
`messages[lastUserIdx + 1 until newCutIdx]` and rely on `previousPostUserSummary` being folded into the
prompt for continuity.

The chaining mechanism is symmetric with how `previousSummary` works today: pass the prior summary as
context to the new summary's prompt.

---

## Helpers (new, file-local in `ContextManager.kt`)

```kotlin
/**
 * Scan messages backward, return the index of the most recent `role == "user"` message.
 * Returns -1 if no user message exists in the history.
 */
internal fun findLastUserIndex(): Int

/**
 * Walk backward from sliceEnd, accumulating estimateMessageTokens(msg).
 * Return the lowest index where the running sum is >= targetTokensFromEnd.
 * Clamped: result is always >= sliceStart, so L4 never extends into L2 or earlier.
 *
 * Pure function — does not mutate messages.
 */
internal fun findTokenWeightedCutForLayer4(
    sliceStart: Int,
    sliceEnd: Int,
    targetTokensFromEnd: Int,
): Int

/**
 * Adjust candidateIdx so L4's first message is NOT an assistant turn (to avoid the
 * SourcegraphChatClient.sanitizeMessages() consecutive-assistant merge).
 *
 * If messages[candidateIdx].role == "assistant", walk backward looking for a `tool`
 * message. Return that tool's index.
 *
 * If we reach sliceStart (the message immediately after L2) without finding a tool —
 * the post-user slice is all-assistant — return sliceEnd as a signal to skip L3
 * entirely (fall back to keeping the whole post-user verbatim).
 *
 * Pure function — does not mutate messages.
 */
internal fun snapToToolBoundary(candidateIdx: Int, sliceStart: Int): Int
```

`summarizePrefix` (`ContextManager.kt:460-497`) splits into two functions sharing the same prompt
skeleton (`TASK / FILES / DECISIONS / STATE / ERRORS / PENDING`):

```kotlin
private suspend fun summarizePreUser(
    brain: LlmBrain,
    prefix: List<ChatMessage>,
    priorSummary: String?,
): String?
// Framing: "You are summarizing the conversation BEFORE the user's most recent
// instruction. The result will replace these messages as a handoff context."

private suspend fun summarizePostUser(
    brain: LlmBrain,
    slice: List<ChatMessage>,
    priorPostSummary: String?,
): String?
// Framing: "You are summarizing the agent's work-so-far in response to the user's
// most recent instruction. The result will replace these messages as working memory."
```

Both retain the existing `[+N image(s) attached]` placeholder logic
(`ContextManager.kt:481-483`) and the `(tool_call: name)` fallback for tool-call-only turns.

---

## What stays unchanged

- **`CompactResult` sealed class** — same four variants (`Skipped`, `Cancelled`, `Failed`, `Compacted`),
  same fields.
- **All 4 physical call sites** of `ContextManager.compact()` — `AgentLoop.kt:753`,
  `AgentLoop.kt:954`, `AgentLoop.kt:1206`, `AgentService.kt:828`. Same signature (with one new
  defaulted parameter), same `slidingWindow(0.3)` fallback wired on `CompactResult.Failed`. The
  `AgentController` Compact button reaches compaction indirectly via `AgentService.compactContext`
  (`AgentController.kt:638` → `AgentService.kt:828`); the controller already relies on the service's
  `slidingWindow` safety net per the in-code comment at `AgentController.kt:644`.
- **`SubagentRunner` compaction path** — sub-agents share `AgentLoop`, so the 3 `AgentLoop` sites
  cover the sub-agent case transparently. No direct compaction call lives in `SubagentRunner`.
- **`deduplicateFileReads()` pre-pass** — unchanged; runs before split.
- **`stripImagePartsFromAllMessages()`** — unchanged; runs post-summarization.
- **`reInjectActiveSkill()` / `reInjectActivePlan()`** — unchanged; append after L4.
- **`onHistoryOverwrite` callback** — same signature, same persistence contract.
- **88% trigger threshold** — kept (was 88% in Phase 1, no change).
- **Token tracking** — `lastPromptTokens` invalidated post-compaction, recomputed on next API turn.

---

## Worked examples

### Example 1 — Your 55-message scenario

Setup: prior compaction's L1 summary at index 0 (assistant role), only user message at index 15,
55 messages total, agent-loop iterations since last user = 7 (>5 gate fires), L1 utilization
estimate = 60% of budget.

```
idx:   0          1     ...   14    15    16    ...   54
role:  assistant  asst        tool  user  asst        tool
       [prior L1]                   ^^^^ anchor
```

1. `findLastUserIndex()` → **15**
2. `detectCase()` → Case A (first compaction this branch, no prior counter) **OR** Case B if a previous
   compaction already happened. Let's run both:

**Case A:**
- L1 = `summarizePreUser(brain, messages[0..14], previousPreUserSummary=null)`
  → 1 LLM call. Prior summary at index 0 is part of the input prefix (folded in via direct
  inclusion, not via the `priorSummary` parameter).
- L2 = messages[15]
- Post-user slice = messages[16..54] (39 messages)
- `targetTokensInL4 = 0.20 * 200_000 = 40_000` (assuming 200K budget for Claude Opus)
- `findTokenWeightedCutForLayer4` walks backward from 55, summing tokens. Suppose messages[47..54] sum
  to 41_000 tokens → rawCutIdx = 47.
- If messages[47] is `assistant`, `snapToToolBoundary` walks back to messages[46] = `tool` → cutIdx = 46.
- L3 = `summarizePostUser(brain, messages[16..45], priorPostSummary=null)` → 1 LLM call.
- L4 = messages[46..54] (9 messages, ~tool-result-heavy, ~40K tokens).
- Reassembled: `[L1][L2][L3][L4 = 9 messages]` = **12 messages** total.

**Case B (same setup, but a prior compaction stored `previousPreUserSummary` and
`previousPostUserSummary`):**
- L1 = previousPreUserSummary verbatim. **Zero LLM calls.**
- L2 = messages[15]
- Same token-weighted cut → 9-message L4.
- L3 = `summarizePostUser(brain, messages[16..45], priorPostSummary=previousPostUserSummary)`
  → 1 LLM call. The prior post-user summary is folded into the prompt, so continuity is preserved.
- Total: 1 LLM call (vs 2 in Case A) — Case B is **50% cheaper** when the user is silent.

### Example 2 — The 200-iter single-prompt pathology

Setup: one user message at index 0, 200 messages of agent work (alternating `asst`/`tool`),
compaction triggers at 201 messages.

```
idx:   0     1     2     ...   199   200
role:  user  asst  tool        asst  tool
       ^^^ only user
```

1. `findLastUserIndex()` → **0**
2. Case A (first compaction)
3. L1 = `summarizePreUser(brain, messages[0..-1] = empty, null)` → **skipped**: when `prefix.isEmpty()`,
   `summarizePreUser` returns `null` without calling the LLM. The reassembly omits the L1 message
   entirely. Result starts with `[L2 = user] [L3] [L4...]`. Pinned by the test
   `case A with no pre-user messages skips L1 entirely`.
4. L2 = messages[0] (the original user prompt)
5. Post-user slice = messages[1..200] (200 messages)
6. iterationsSinceLastUser = 200 → L3 path triggered.
7. Token-weighted cut snaps to ~20% of budget → L4 = ~10-20 most recent messages, regardless of how
   long the slice grew.
8. L3 = `summarizePostUser(brain, messages[1..cutIdx-1])` → 1 LLM call summarizing ~180 messages.
9. Reassembled: `[L2 (user)][L3 (180-msg summary)][L4 (~15 msgs)]` ≈ **17 messages** total,
   dominated by L4 tokens. L1 is skipped because the pre-user prefix is empty.

**This is the design's headline outcome.** Today's design would summarize 1 message (the user prompt
at index 0, found via backward scan), keep 200 messages verbatim, and compact essentially nothing.

### Example 3 — ≤5 iterations gate skips L3

Setup: user just sent message at index 35, agent has done 3 tool turns, compaction triggers at
38 messages (large pre-user history from earlier in the session pushed it over).

```
idx:   0     1     ...   35    36    37
role:  user  asst        user  asst  tool
                         ^^^^ anchor (4 iters)
```

1. lastUserIdx = 35
2. iterationsSinceLastUser = 2 (only 2 assistant turns after the user) — gate doesn't fire.
3. Compute post-L1 utilization estimate: if still ≥88%, force L3. Otherwise skip.
4. Skip L3 path: reassemble `[L1][L2][postUserSlice verbatim]` = `[L1][user@35][asst@36][tool@37]`.

This is the cheap path — pre-user compaction without disturbing recent working memory.

---

## Edge cases

| Case | Behavior |
|---|---|
| No user message in history (`lastUserIdx == -1`) | Degenerate path: summarize all messages into a single L1, no L2/L3/L4. Same as today's "single summary" output shape. |
| Only one user message (lastUserIdx == 0), 0 post-user messages | L1 = empty/null, L2 = user message, no L3, no L4. Compaction is a no-op for this case (returns `Skipped` or `Compacted` with 0 chars). |
| `findTokenWeightedCutForLayer4` would set cutIdx == sliceStart (post-user too small to split) | Skip L3, fall through to verbatim post-user path. |
| `snapToToolBoundary` finds no tool in slice (all-assistant) | Returns sliceEnd → skip L3 path. |
| iterationsSinceLastUser tracking lost (e.g., after resume) | Recompute on `restoreMessages` by counting assistant turns after the last user message. |
| L1 LLM call fails (Case A) | Set l1Failed=true, continue to L3. If L3 succeeds, return `Compacted` (partial success). If L3 also fails, return `Failed`. |
| L3 LLM call fails | Set l3Failed=true. If L1 succeeded, return `Compacted` with no L3 (post-user kept verbatim). If L1 also failed, return `Failed`. |
| Both LLM calls fail | Return `Failed("Both summarization calls failed")`. Call site fires `slidingWindow(0.3)`. |
| `previousPreUserSummary` is null in Case B (shouldn't happen) | Defensive fallback: degrade to Case A, run L1 LLM call. |

---

## Resume-from-disk compatibility

`MessageStateHandler`
(`agent/src/main/kotlin/com/workflow/orchestrator/agent/session/MessageStateHandler.kt`) persists
`api_conversation_history.json` but does **not** persist `ContextManager` state fields. On resume, the
disk-load path calls `ContextManager.restoreMessages(chatMessages)` at `AgentService.kt:2491`.

The current implementation of `ContextManager.restoreMessages` (`:698-703`) only `messages.clear()` +
`messages.addAll(savedMessages)` + `lastPromptTokens = null` + log. **It does not currently touch any
of the summary-chaining or counter state fields.**

This design adds the following new behavior inside `restoreMessages`:

1. Reconstruct `totalUserMessageCount` by counting `role == "user"` messages in `savedMessages`.
2. Reset `lastCompactionUserMessageCount = null` — the resumed session will treat the next compaction
   as Case A (rebuild L1 from scratch). Acceptable: one extra LLM call on the first post-resume
   compaction.
3. Reset `previousPreUserSummary = null` and `previousPostUserSummary = null`.

All three are **new lines of code** added to the body of `restoreMessages`, not existing behavior being
preserved.

If we want to persist these across resume in a future pass, add them to `HistoryItem` or a sidecar JSON.
**Out of scope for this design.** First-resume re-summarization is acceptable cost.

---

## Test plan

### New tests: `ContextManagerTwoTierTest`

- `case A first compaction builds L1+L2+L3+L4`
- `case A with only one user message (lastUserIdx == 0) builds L2+L3+L4 without L1`
- `case A with no pre-user messages skips L1 entirely (summarizePreUser not called)`
- `case A with no user messages in history falls back to single-L1 degenerate summary`
- `case A with ≤5 iterations skips L3 by default`
- `case A with ≤5 iterations but post-L1 utilization still ≥88% forces L3`
- `case B re-compaction reuses previousPreUserSummary verbatim (no L1 LLM call)`
- `case B folds previousPostUserSummary into L3 prompt`
- `case B detection: identical totalUserMessageCount counters → Case B`
- `case B detection: incremented counter (new user message) → Case A`
- `token-weighted cut targets ~20% of budget in L4`
- `snapToToolBoundary walks backward from assistant onto tool`
- `snapToToolBoundary returns sliceEnd when slice is all-assistant`
- `L1 LLM fails + L3 LLM succeeds → returns Compacted with no-L1 fallback`
- `L1 LLM succeeds + L3 LLM fails → returns Compacted with verbatim post-user`
- `both LLMs fail → returns Failed`
- `totalUserMessageCount increments on addUserMessage`
- `totalUserMessageCount increments on addUserMessageWithParts`
- `restoreMessages recomputes totalUserMessageCount from saved messages`

### Existing tests to update

- `ContextManagerApiSurfaceTest` — extend to cover the new state fields' privacy (verify the public
  surface hasn't drifted).
- `AgentLoopTest` — overflow-compaction test should still pass; only the internal slicing changed.
- `AgentLoopUpstreamTimeoutTest` — no expected change.

### Smoke tests via `SubagentRunner`

- A sub-agent that compacts mid-task should produce a valid `[L1][L2][...]` structure that the parent
  LLM continues from without error.

### Snapshot / contract tests

None — the system prompt shape doesn't change, and `CompactResult` is unchanged.

---

## Implementation order

1. **State fields + counters** (`ContextManager.kt`): add `previousPreUserSummary`,
   `previousPostUserSummary`, `totalUserMessageCount`, `lastCompactionUserMessageCount`. Wire the
   counter increment in `addUserMessage` + `addUserMessageWithParts`. Wire recompute in
   `restoreMessages`. Run existing tests — should still pass (no behavior change yet).
2. **Helpers**: implement `findLastUserIndex`, `findTokenWeightedCutForLayer4`, `snapToToolBoundary` as
   pure functions with unit tests before touching `compact()`.
3. **Split `summarizePrefix`** into `summarizePreUser` and `summarizePostUser`. Verify both produce the
   same shape as the original on identical input via a regression test.
4. **Rewrite `compact()`** following the 14-step algorithm above. Land behind a feature gate
   (`AgentSettings.useTwoTierCompaction`, default true for this branch but easy to revert).
5. **Add new tests** (the full `ContextManagerTwoTierTest` suite). Verify existing tests still pass.
6. **Manual smoke**: in IDE, start a long agent run, force compaction via the Compact button, verify
   the resulting structure and that the next API turn succeeds without alternation errors.
7. **Update `agent/CLAUDE.md`** "Context Management" section to document the new flow (same commit as
   the implementation, per the repo convention in `CLAUDE.md` → "Docs").
8. **Remove the feature gate** once verified on a few real sessions, OR keep it for one release as a
   safety valve. Decide at PR time.

---

## Risks

- **Token-estimate accuracy.** `estimateMessageTokens` uses `char/3.5` — approximate. The 20% L4 floor
  may be off by ±10% in practice. No worse than today's threshold accuracy.
- **Case B detection robustness.** Counter-based, immune to message content collisions. Survives resume
  via `restoreMessages` recompute.
- **Forgot edge case: `previousPreUserSummary` is non-null but the new lastUserIdx differs from where
  the prior compaction anchored.** This is exactly Case A — the counter detects it. No issue.
- **L1 + L2 + L3 reassembly creates `[assistant L1][user L2][assistant L3]` — two assistants separated
  by a user**, not consecutive. Sanitizer doesn't merge them. Safe.
- **L4 starting with `tool` then sanitizer coercing to `user "TOOL RESULT:\n..."`** matches today's
  post-compaction behavior — already proven safe.

---

## Out of scope

- Persisting `previousPreUserSummary` / `previousPostUserSummary` across session resume. First-resume
  re-summarization is acceptable; persistence is a follow-up if it becomes a measurable cost.
- Persisting `totalUserMessageCount` / `lastCompactionUserMessageCount` — same rationale.
- Token-aware threshold tuning (e.g., dynamic 80/20 based on remaining budget headroom). Static 20%
  is sufficient for now.
- Refactoring `slidingWindow(0.3)` — kept as-is as the hard-truncation fallback when `Failed` is
  returned.
- Sub-agent-specific compaction strategy. Sub-agents share `ContextManager` plumbing via
  `SubagentRunner`; this design works for them transparently.

---

## Acceptance

The design is correct when:

1. The 55-message scenario from Example 1 produces a 12-message post-compaction structure (Case A) or
   a structure built with 1 LLM call (Case B).
2. The 200-iter pathology from Example 2 produces an ~17-message post-compaction structure
   (`[L2][L3][L4]` with L1 omitted) that actually drops utilization below 88%.
3. The ≤5-iteration gate skips L3 when post-L1 utilization is healthy, forces L3 when it isn't.
4. Case B re-compactions invoke `brain.chat()` exactly once per compaction (verified via mock call
   count).
5. All existing `ContextManager` tests still pass.
6. A real long-running agent session (>100 iterations on a single user prompt) successfully reaches
   completion without immediate-re-compaction loops.
