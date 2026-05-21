# Two-Tier Compaction Code Review

**Date:** 2026-05-18
**Reviewer:** Opus subagent
**Scope:** 10 commits from 02da6f6b2 to 7d9447af6 on bugfix
**Spec:** docs/superpowers/specs/2026-05-18-context-compaction-two-tier-design.md

## Verdict

**APPROVE WITH NITS**

Implementation faithfully follows the 14-step algorithm; the rewritten step-14 predicate is sound;
persistence-state save/restore is complete. Two correctness drifts around the `iterationsSinceLastUser`
counter and `totalUserMessageCount` increment in AgentLoop are efficiency issues only — they degrade
Case-B reuse and add stray L3 LLM calls but never produce incorrect output or corrupt history. Safe
for manual smoke; recommend addressing the synthetic-nudge counter drift before declaring "no more
re-compaction loops" in production.

## Findings

### Critical (must fix before manual smoke)

*(none)*

### Important (should fix soon)

1. **Synthetic-nudge user messages inflate `totalUserMessageCount`, making Case B almost unreachable
   in long stalled sessions.**

   `agent/src/main/kotlin/com/workflow/orchestrator/agent/loop/ContextManager.kt:141-160` —
   both `addUserMessage` and `addUserMessageWithParts` unconditionally `totalUserMessageCount++`.

   `agent/src/main/kotlin/com/workflow/orchestrator/agent/loop/AgentLoop.kt` calls
   `contextManager.addUserMessage(...)` for 7 synthetic-nudge sites that are NOT real user input:

   - `1373` length-cut-short nudge (truncated tool call)
   - `1409` upstream gateway-timeout nudge
   - `1504` "you can't combine attempt_completion with other tools" nudge
   - `1562` `TEXT_ONLY_NUDGE`
   - `1595` `EMPTY_RESPONSE_ERROR`
   - `1702` `LOOP_SOFT_WARNING`
   - `2151` feedback request

   Every one of these bumps `totalUserMessageCount`. The case-detection predicate at `:447-448`
   then declares Case A — even though no real user instruction arrived since the last compaction —
   and we burn an LLM call to rebuild L1 that Case B would have skipped. The headline cost win for
   Case B ("Case B is 50% cheaper when the user is silent", spec line 456) silently disappears in
   any session that hit even one nudge.

   This is the spec's literal language ("`addUserMessage` ⇒ increment") working against its intent
   (Case B = "the user has been silent since last compaction"). Worth a focused fix: increment only
   on the public entry points (`AgentService.executeTask`'s initial seeding + `userInputChannel`
   receives in `AgentLoop` at `:1533/:1546/:2210` + the steering drain at `:787`). Nudges should
   call a separate "system-injected user turn" helper that does not bump the counter, OR the
   counter should move out of `ContextManager` entirely and be driven by the loop.

   *Suggested fix:* introduce `ContextManager.addSystemNudgeAsUser(text)` that appends without
   incrementing `totalUserMessageCount`, and have the 7 nudge sites use it. Alternatively, gate the
   increment in `addUserMessage` on an explicit "real user" flag.

2. **`iterationsSinceLastUser` does not reset on real user input arriving mid-loop via
   `userInputChannel.receive()`.**

   `AgentLoop.kt:1533`, `:1546`, `:2210` — three sites where the loop suspends waiting for the
   user to type into the chat (plan-mode reply, max-mistakes feedback, plan-card approval flow).
   When the user does send a real reply, the loop calls
   `contextManager.addUserMessage(withEnvDetails(receivedTask))` but does NOT set
   `iterationsSinceLastUser = 0`.

   Effect: the next compaction will see `iterationsSinceLastUser > 5` and force an L3 LLM call,
   even though the user *just* spoke and L3's working-memory slice is fresh. This is the
   efficiency-loss direction (extra L3 call), not the correctness-loss direction (skipping needed
   L3), so it's not catastrophic. But it directly contradicts the spec's "Reset to 0 in two places"
   intent — `executeTask` entry is one (covered by initial `var iterationsSinceLastUser = 0` at
   `:698`); steering drain is the other (covered at `:788`). The interactive channel arrivals were
   missed.

   *Suggested fix:* add `iterationsSinceLastUser = 0` next to each of the three `userInputChannel`
   sites. Same one-line edit each time.

### Notes (informational, no action required)

3. **State save is unconditional even on `Failed`; can leave stale chained summaries on the next
   Case B.**

   `ContextManager.kt:516-518` — `previousPreUserSummary = l1Content ?: previousPreUserSummary`
   and same for L3 plus the unconditional `lastCompactionUserMessageCount = totalUserMessageCount`.
   When both summarizations fail (`Failed`), the caller fires `slidingWindow(0.3)` which clobbers
   the message list, but `previousPreUserSummary` / `previousPostUserSummary` keep pointing at
   summaries that no longer describe any visible history. The next compaction may then dial up
   Case B (counter matches) and reuse a stale L1 against a freshly-truncated message list.

   The defensive fallback at `:455` (`isCaseB && previousPreUserSummary != null` → reuse;
   otherwise rebuild) protects against the *null* case but not the *stale* case. In practice this
   produces a slightly-off L1 the user won't notice, then the next user message recovers to
   Case A. Acceptable per the spec's "first-resume re-summarization is acceptable cost" framing,
   but worth a note for posterity.

4. **The `compactDegenerate` path (no user message ever) shares `previousPreUserSummary`
   with the normal path.**

   `ContextManager.kt:551, :558` — `compactDegenerate` reads `previousPreUserSummary` in and out.
   If a session somehow has no user message in history (history wholly synthesized?), and a prior
   degenerate compaction stored an L1, the next normal compaction's `isCaseB` check could
   accidentally reuse the degenerate-path L1 as a "pre-user prefix" summary. Real-world
   reachability is near-zero (every executeTask seeds a user turn at `:680/:686` before the loop
   runs), but the code path exists.

5. **Spec test plan calls for partial-success tests ("L1 fail + L3 success → Compacted with
   no-L1 fallback" and "L1 success + L3 fail → Compacted with verbatim post-user"); only the
   "both fail → Failed" test landed.**

   `ContextManagerTwoTierTest.kt:247-264` covers both-fail. The two partial-success branches
   are not pinned. The implementer's note about this gap is reasonable — partial success depends
   on reassembly behavior — but the step-14 predicate logic that distinguishes "all attempts
   failed" from "at least one succeeded" is the load-bearing change in this branch and has no
   targeted test. Coverage gap. Recommend adding two short tests that mock brain.chat() to fail
   the first call and succeed the second (and vice versa), then assert `result is Compacted` and
   message-shape (`[L1?][L2][L3?][L4...]`). Each test should be 30-40 lines.

6. **`postUserSlice` is computed once at `:468` from the **pre-clear** message list, but it
   becomes the L4 source when L3 is skipped.**

   `ContextManager.kt:468, :503`. The `messages.subList(postUserStart, messages.size).toList()`
   call at `:468` snapshots the post-user tail. Then `messages.clear()` at `:504` wipes the
   backing list. The `.toList()` is what saves us — without it, the subList would be a live
   view onto a cleared list, and `messages.addAll(postUserSlice)` at `:508` would inject empty.
   Already correct; flagging for posterity since it's a subtle dependency.

7. **`lastCompactionRanSummary = (l1Content != null && !isCaseB) || l3Content != null`** at
   `:519` correctly excludes "Case B + L1 reused verbatim" from "ran summary" (no LLM call
   happened). Good. Note for the UI: the marker may now flip true/false more frequently across
   re-compactions in a long quiet stretch (Case B runs that rebuilt L3 only will set
   `lastCompactionRanSummary=true`; a Case B run that skips L3 will set it false). UI consumers
   should be ready for that.

## Spec-vs-implementation drift

| Spec step | Implementation | Notes |
|---|---|---|
| 1. Threshold gate | ✓ matches | `:413` — `if (!force && !shouldCompact()) return CompactResult.Skipped(utilBefore)` |
| 2. PRE_COMPACT hook | ✓ matches | `:420-434` — cancellable, returns `Cancelled(reason)` |
| 3. Dedup pre-pass | ✓ matches | `:437` — `deduplicateFileReads()` |
| 4. findLastUserIndex + degenerate | ✓ matches | `:440-444` — degenerate routed through `compactDegenerate()` |
| 5. Case A/B detection | ✓ matches | `:447-448` — counter-based; matches spec exactly |
| 6. Build L1 | ✓ matches | `:451-461` — empty prefix → null, Case B → reuse, else summarize |
| 7. L2 | ✓ matches | `:464` — `messages[lastUserIdx]` |
| 8. Decide L3 | ✓ matches | `:467-473` — iter gate OR post-L1 util ≥ 88%; spec language preserved |
| 9. Build L3 + L4 | ✓ matches | `:475-499` — token-weighted cut + tool-boundary snap + skip-if-snap-returns-sentinel |
| 10. Reassemble | ✓ matches | `:501-508` — `[L1?][L2][L3?][L4]` with null L1 omitted |
| 11. Post-cleanup | ✓ matches | `:511-513` — strip images, re-inject skill, re-inject plan |
| 12. Save state | ✓ mostly matches | `:516-518` — `?:` preserves prior on failure (good); see Note 3 about unconditional counter update |
| 13. Invalidate + persist | ✓ matches | `:521-525` — `lastPromptTokens = null`, callback with correct deletedRangeEnd |
| 14. Return CompactResult | ✗ justified drift | Implementation: "any summarization attempted AND none succeeded → Failed". Spec literal: "l1Failed && l3Failed → Failed". The implementation correctly handles the case where L1 fails and L3 is never attempted (which the spec literal would mis-classify as `Compacted` because `l3Failed == false`). All four sub-cases (both-fail, L1-only-fail, L3-only-fail, neither-attempted) produce the right answer. Drift is justified; no test pinning the partial-success arms (see Note 5). |

Two helper functions match spec exactly:

- `findLastUserIndex` — backward scan, -1 on no user. ✓
- `findTokenWeightedCutForLayer4` — backward walk, returns sliceStart if running sum never hits target. Off-by-one: returned index is the FIRST message of L4 (the one that pushes the sum over). Caller uses it consistently. ✓
- `snapToToolBoundary` — assistant → walk back to tool; sentinel `messages.size` on all-assistant slice. Caller at `:487` correctly interprets `snappedCutIdx >= messages.size` as "skip L3, keep post-user verbatim". Pinned by tests at `ContextManagerTwoTierHelpersTest.kt`. ✓

`restoreMessages` resets all four new state fields (`totalUserMessageCount` recomputed,
`lastCompactionUserMessageCount = null`, both summaries null). The single resume-path call site at
`AgentService.kt:2491` is the only entry; no other code path skips the reset. ✓

`clearMessages` at `:928-936` also resets all four. ✓

The 4 call sites are all wired correctly:

| Call site | File:line | iterationsSinceLastUser | Fallback on Failed |
|---|---|---|---|
| Stage 0 auto-compaction | `AgentLoop.kt:761` | counter ✓ | `slidingWindow(0.3)` ✓ |
| Overflow recovery | `AgentLoop.kt:963` | counter ✓ | `slidingWindow(0.3)` + 3-strike abort ✓ |
| Timeout recovery | `AgentLoop.kt:1215` | counter ✓ | `slidingWindow(0.3)` ✓ |
| Manual `compactContext` | `AgentService.kt:828` | `Int.MAX_VALUE` ✓ | `slidingWindow(0.3)` ✓ |

## Test coverage assessment

Coverage matches the contractual surface well, with two gaps:

- **Partial-success paths uncovered** (see Important #5). The new step-14 predicate is the
  load-bearing change; without targeted tests for the L1-only-fail and L3-only-fail arms, a future
  regression could flip the predicate to fail those cases silently.
- **Counter-reset behavior in AgentLoop** is exercised only by integration. There is no unit test
  that pins "iterationsSinceLastUser must reset on steering drain" or "must reset on
  userInputChannel arrival" — both of which are intended invariants per spec. If Important #2 is
  fixed, that fix should land with a small AgentLoop test asserting the counter is 0 after each
  reset trigger.

Spec-listed tests that are missing:
- `case B detection: incremented counter (new user message) → Case A` — pinned only implicitly via
  the Case B test's setup.
- `token-weighted cut targets ~20% of budget in L4` — helpers test pins mechanical token math,
  not the "20% of budget" target derivation.

Spec-listed tests that DID land and look solid:
- Case A first compaction shape (L1+L2+L3+L4)
- Case A no-pre-user (skips L1)
- Case A no-user-at-all (degenerate)
- Case B reuses L1 verbatim, calls brain exactly once for L3
- iter-gate skip + iter-gate force-via-utilization
- both-LLMs-fail → Failed
- counter increments + restore-recompute + restoreMessages clears summary state

Helper unit tests (`ContextManagerTwoTierHelpersTest.kt`) are tight and pin the edge cases the
helpers' contracts depend on (sliceStart-as-tool, all-assistant slice, sliceEnd-too-small,
candidate-already-tool, candidate-walked-back-from-assistant-to-tool).

All existing tests (`AgentLoopTest.kt`, `AgentLoopUpstreamTimeoutTest.kt`,
`ContextManagerApiSurfaceTest.kt`) still pass per the implementer's note (taken as given per the
review instructions).
