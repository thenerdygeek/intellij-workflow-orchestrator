# Unified retry backoff with jitter — design

**Status:** Approved, ready for implementation plan
**Branch:** `fix/automation-handover-quality-tabs`
**Touches:** `agent/src/main/kotlin/com/workflow/orchestrator/agent/loop/AgentLoop.kt`

## Problem

`AgentLoop` has five retry layers but only one of them paces attempts with a delay:

| Retry layer | Trigger | Current delay |
|---|---|---|
| Layer 1 — API error | RATE_LIMITED / SERVER_ERROR / NETWORK_ERROR / TIMEOUT | Exponential 1/2/4/8/16s, honors `Retry-After`, cap 30s. **No jitter.** |
| Layer 2 — Compaction retry on timeout exhaustion | Timeout retries exhausted | 0 |
| Layer 3 — Empty response (Case C) | HTTP 200 + empty content + no tools | 0 |
| Layer 4 — Text-only nudge (Case B) | HTTP 200 + text only, no tools | 0 |
| Doom-loop detector | 5 identical consecutive tool calls | N/A — circuit breaker, not a retry |

Two problems:

1. **Layers 2-4 have no pacing.** They fire the next LLM call immediately. Even when a brief gap would let a flaking gateway recover or break a burst pattern, we don't wait.
2. **Layer 1 has no jitter.** Concurrent agent sessions hitting a rate limit retry in lockstep at 1s/2s/4s wall-clock offsets. The Sourcegraph Cody gateway has been observed to issue aggressive `Retry-After` values during model warm-up; without jitter, multiple sessions cluster on the boundary.

## Goals

1. Add bounded exponential backoff with jitter to Layers 2, 3, 4.
2. Add jitter to existing Layer 1 in the same change.
3. Extract the backoff math into one helper so all four sites use the same formula.
4. Preserve all existing semantics: caps, counter-reset rules, nudge content, temperature escalation, cancellation behavior.

## Non-goals

- Changing retry caps (`MAX_API_RETRIES = 5`, `MAX_CONSECUTIVE_EMPTIES = 3`, `maxConsecutiveMistakes = 3`, `MAX_COMPACTION_RETRIES = 2`) — out of scope.
- The four other items in `memory/project_loop_exit_improvements.md` (max-iter state dump, model fallback escalation, cancel revert, malformed-tool-call format examples) — out of scope.
- Doom-loop detector — it's a circuit breaker, not a retry.
- Per-tool transient retry (HTTP failures inside individual tools) — different layer, different scope.

## Design

### The helper

A single private function on `AgentLoop`'s companion object:

```kotlin
/**
 * Computes a jittered backoff delay for retry pacing.
 *
 * Full-jitter shape (AWS pattern): returns a random value in [0, computed],
 * where `computed = min(baseMs * 2^(attempt-1), capMs)` unless `retryAfterMs`
 * overrides it (capped to capMs, still jittered).
 *
 * @param attempt 1-based retry attempt number.
 * @param baseMs initial delay; defaults to INITIAL_RETRY_DELAY_MS = 1000ms.
 * @param capMs maximum *computed* (pre-jitter) delay; defaults to MAX_RETRY_DELAY_MS = 30_000ms.
 * @param retryAfterMs server-provided override (Retry-After header), capped to capMs.
 */
private fun computeBackoffMs(
    attempt: Int,
    baseMs: Long = INITIAL_RETRY_DELAY_MS,
    capMs: Long = MAX_RETRY_DELAY_MS,
    retryAfterMs: Long? = null,
): Long {
    val computed = retryAfterMs?.coerceAtMost(capMs)
        ?: (baseMs * (1L shl (attempt - 1).coerceAtMost(30))).coerceAtMost(capMs)
    return Random.nextLong(computed + 1)
}
```

Note: `(attempt - 1).coerceAtMost(30)` guards against `1L shl 63` overflow at pathological attempt counts. With Layer 1's cap of 5 attempts this isn't reachable today, but defends against future cap bumps.

### Application sites

| Site | Current code shape | New code shape |
|---|---|---|
| Layer 1 (API error, `AgentLoop.kt:1097-1111`) | Inline `INITIAL_RETRY_DELAY_MS * (1L shl …)` | `computeBackoffMs(apiRetryCount, retryAfterMs = apiResult.retryAfterMs)` |
| Layer 2 (compaction, `~1116-1128`) | No delay | `delay(computeBackoffMs(compactionRetries, baseMs = 200))` immediately before the `continue` at the end of the compaction block |
| Layer 3 (Case C empty, `~1452-1453`) | No delay | `delay(computeBackoffMs(consecutiveEmpties))` as the line *after* `addUserMessage(EMPTY_RESPONSE_ERROR)` — pre-existing `pruneAllNudgePairs` call must remain before `addUserMessage` |
| Layer 4 (Case B text-only, `~1419-1421`) | No delay | `delay(computeBackoffMs(consecutiveMistakes))` as the line *after* `addUserMessage(TEXT_ONLY_NUDGE)` — pre-existing `pruneAllNudgePairs` call must remain before `addUserMessage` |

**Import:** `import kotlin.random.Random` must be added to `AgentLoop.kt`'s import block. `Random.Default` (singleton, thread-safe under JBR 21+ via `ThreadLocalRandom`) is used implicitly by the static `Random.nextLong(bound)` companion call — no instance state to manage.

**Base values:**
- Layer 1: 1000ms (unchanged from `INITIAL_RETRY_DELAY_MS`)
- Layers 3, 4: 1000ms (matches Layer 1 — user choice from brainstorming)
- Layer 2 (compaction): 200ms (shorter because compaction itself takes 5-15s; we just want a small breather)

**Worst-case added latency** (mean of full jitter ≈ computed/2):

| Layer | Attempts | Computed delays | Mean total added | Max total added |
|---|---|---|---|---|
| Layer 1 (was 1/2/4/8/16, sum 31s) | 5 | 0-1s, 0-2s, 0-4s, 0-8s, 0-16s | ~15.5s | 31s (unchanged) |
| Layer 2 (compaction) | 2 | 0-200ms, 0-400ms | ~300ms | 600ms |
| Layer 3 (empty) | 3 | 0-1s, 0-2s | ~1.5s | 3s |
| Layer 4 (text-only) | 3 | 0-1s, 0-2s | ~1.5s | 3s |

**Layer 1 actually gets faster on average** because jitter halves expected wait.

### Cancellation

Free. `delay()` is a cancellable coroutine suspension. When the coroutine is cancelled mid-wait, `CancellationException` propagates straight out of `AgentLoop.run()` — the function is `suspend` and does not catch `CancellationException`. The outer caller (`AgentService.executeTask`) handles cancellation; the loop's own `while (!cancelled.get())` head check is only relevant on the *next* iteration, which is moot once the function has unwound. No new code needed and no risk of "stuck in delay" — Kotlin coroutine cancellation is preemptive at `delay()`.

### What is NOT changed

- Retry caps (`MAX_API_RETRIES`, `MAX_TIMEOUT_RETRIES`, `MAX_CONSECUTIVE_EMPTIES`, `maxConsecutiveMistakes`, `MAX_COMPACTION_RETRIES`).
- Counter-reset rules (only Case A — real tool call — resets `consecutiveEmpties` and `consecutiveMistakes`; plan-mode conversational branch still resets both).
- Nudge content (`EMPTY_RESPONSE_ERROR`, `TEXT_ONLY_NUDGE`).
- Temperature escalation in Case C — backoff is *added*, not *replacing*.
- Plan-mode conversational branch — uses `userInputChannel.receive()`, not a retry path.
- Sub-agent no-channel fail-fast — still hard-fails at max mistakes.
- `iteration--` semantics on Layer 1 — API retries still don't burn the iteration budget. Content retries (B, C) still do, unchanged.

## Testing

Three test files in `agent/src/test/kotlin/.../loop/`:

### 1. `AgentLoopBackoffHelperTest` (new) — pure-function unit tests

- `attempt=1, base=1000` → result in `[0, 1000]`
- `attempt=2, base=1000` → result in `[0, 2000]`
- `attempt=5, base=1000, cap=30000` → result in `[0, 16000]`
- `attempt=10, base=1000, cap=30000` → result in `[0, 30000]` (exponent saturated by cap)
- `attempt=100, base=1000, cap=30000` → result in `[0, 30000]` (no shift overflow)
- `retryAfterMs=5000` → result in `[0, 5000]` regardless of attempt
- `retryAfterMs=999_999_999` → result in `[0, capMs]` (caps the override)
- Statistical sanity: 1000 samples at `attempt=3, base=1000` → mean within ±10% of 2000ms

### 2. `AgentLoopRetryGapTest` (new) — `runTest` with virtual time

Use Kotlin's `kotlinx.coroutines.test.TestScope` to assert delays without real wall-clock waits:

- Case C (empty): two consecutive empty responses observe `currentTime` advance by ≥ 0 and ≤ 1000ms between them
- Case B (text-only): two consecutive text-only responses observe `currentTime` advance similarly
- Layer 2 compaction: two compaction retries observe `currentTime` advance by ≤ 200ms first, ≤ 400ms second

### 3. Existing test catalogue — no updates needed

Reviewer confirmed (2026-05-11): no existing test in `agent/src/test/kotlin/.../loop/` captures `onRetry`'s `delayMs` argument or asserts exact delay values tied to Layer 1's backoff. The `onRetry` callback is defined but not test-instrumented today. So the Layer 1 jitter change has no existing-test impact; the two new test files are the entire test surface.

**Total new test methods:** ~11.

## Risk / rollback

- **UX risk:** Empty/text-only retries now take up to 3s longer worst-case. Acceptable — three failures in a row is already a stalled session; the extra few seconds before bailing don't materially worsen anything.
- **Test fragility:** Any existing test that asserted exact `delayMs` values must be updated to range assertions. Catalogued before merge.
- **Rollback:** Single-file change. `git revert <commit>` returns to today's behavior.

## Self-review

- [x] No placeholders or TBDs.
- [x] Internally consistent — application table matches helper signature; latency table matches caps.
- [x] Scoped for one implementation plan (single file, single helper, four call sites, three test files).
- [x] No ambiguous requirements — base values, caps, and jitter shape are specified.
