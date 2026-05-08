# Code review — `sonar(action="local_analysis")` hardening

**Date:** 2026-05-08
**Branch:** `fix/automation-handover-quality-tabs`
**Implementation plan:** `docs/superpowers/plans/2026-05-07-sonar-local-analysis-hardening.md`
**Commits reviewed:** 11 (Tasks 1-10 + 1 type-correction). SHA range: `ca27e7df..287ad0f3`.

## Setup

Three Sonnet sub-agents reviewed the work in parallel, each with a different lens:

1. **Correctness** — logic bugs, type errors, missed edge cases, race conditions, spec deviations.
2. **Test quality** — coverage gaps, brittle assertions, vacuous tests, untestable production behavior.
3. **Code quality** — naming, complexity, readability, idiomatic Kotlin, code smells.

Each reviewer received: the full commit list, the implementation plan path (for context on intent), background on the two `ToolResult` types in the codebase, the inferred-vs-probe-confirmed distinction for the NEW-1 fix, and a structured output format. None of them edited code.

## Findings summary

**14 findings total**, severity-distributed per the reviewers' own classification:

- 4 🔴 CRITICAL (per reviewers — see "filtered severity" below for my recalibration)
- 8 🟡 HIGH
- 2 🟢 MEDIUM

After my own filtering of which findings actually needed action: **6 worth acting on now** (Tier 1), **4 worth comment-only fixes** (Tier 2), **4 defensible to accept as-is** (Tier 3, push-back).

## Tier 1 — Acted on (6 findings, 5 commits, 10 fixes total because each commit bundles related fixes)

| # | From | Title | Severity | Commit |
|---|---|---|---|---|
| 1 | Correctness | `isForbidden` substring `"permission"` is too loose — false-positives on proxy/network errors mentioning permission | 🟡 HIGH | `e50693cb` |
| 2 | Code-quality | `SonarRetry.shouldRetry` parameter name is inverted from natural reading — default `{ true }` and call-site negation `{ r -> !isForbidden(...) }` confuse readers | 🟡 HIGH | `e50693cb` |
| 3 | Code-quality | `bySeverity = listOf("BLOCKER", ..., "INFO")` in SonarTool duplicates `IssueSeverity.ORDER` (reversed) — divergence risk if a new severity is added | 🟡 HIGH | `30115305` |
| 9 | Correctness | `IssueSeverity.meetsMinSeverity` has asymmetric defaults (unknown sev → INFO; unknown threshold → no filter) — safe today but contract should be explicit | 🟢 MEDIUM | `30115305` |
| 4 | Code-quality | `fetchRuleHowToFix` re-implements `stripAndTrim`'s whitespace-collapse + length-cap inline — DRY violation introduced in the same PR | 🟡 HIGH | `7c059f4f` |
| 10 | Correctness | `stripAndTrim` regex would strip `<String>` if Sonar ever returns unescaped HTML; should HTML-unescape | 🟢 MEDIUM | `7c059f4f` |
| 7 | Code-quality | `runInterruptible(kotlinx.coroutines.Dispatchers.IO)` uses fully-qualified name; `Dispatchers` should be imported | 🔴 CRITICAL (per reviewer; ⚪ in my classification) | `b5968b1c` |
| 8 | Correctness + Code-quality | `mutableMapOf` cache thread-safety is sequential-by-construction but fragile — comment the invariant | 🔴 CRITICAL (per reviewer; 🟢 in my classification) | `b5968b1c` |
| 5 | Test-quality | `SonarRetryTest` doesn't actually verify backoff doubling — a regression replacing `*= 2` with `+= 10` would pass | 🟡 HIGH | `6952beb1` |
| 6 | Test-quality | `IssueSeverityTest` doesn't pin adjacent severity-level boundaries — a swap of MAJOR ↔ CRITICAL would not be caught | 🟡 HIGH | `6952beb1` |

### Severity recalibration

The correctness reviewer marked items #8 (cache thread-safety) and item-from-me-not-listed-above (`reader.join(1000)` blocks dispatcher) as 🔴 CRITICAL. On close reading both are "real bugs that don't fire under current usage" — future-proofing rather than current breakage. I reclassified to 🟢 MEDIUM and ⚪ LOW respectively. This is normal calibration noise in fresh-eyes reviews; flagging it transparently.

## Tier 2 — Comment-only fixes (no behavior change)

These shipped alongside Tier 1 in the 5 fix commits:

- **#7 — Dispatchers import** — pure cosmetic; matches kotlinx.coroutines import style elsewhere in the file.
- **#8 — Cache sequential-invariant comment** — documents why `mutableMapOf` is safe today and what a future parallelization would need (`ConcurrentHashMap` with sentinel handling for nullable values).
- **#9 — `IssueSeverity` asymmetry KDoc** — explicit contract that callers must validate `minSeverity` via `isValid` before calling.
- **#10 — `stripAndTrim` HTML unescape** — added `HtmlEscape.unescapeHtml()` in `:core` as the natural inverse of `escapeHtml()`, applied after tag-stripping in `stripAndTrim`.

## Tier 3 — Push-back (defensible to accept as-is, not addressed)

### `reader.join(1000)` blocks dispatcher on cancellation
**Reviewer claim** (correctness #2, marked 🔴 CRITICAL): on cancellation, the 1-second `Thread.join` blocks the IO dispatcher, violating fast-cancel.
**Push-back rationale:** the join exists to flush the daemon thread's final output. `runInterruptible` around the join would address it but adds complexity. Prior behavior (no cancellation handling at all) was strictly worse. The 1s delay is bounded; in practice OS-level pipe cleanup is fast. Accept as-is.

### `permissionDenied: Boolean` should be a sealed class
**Reviewer claim** (code-quality §B, 🟡 HIGH): three-state outcome (success / forbidden / other-error) modeled as one boolean.
**Push-back rationale:** the third state isn't actually "other-error" — that's the inner `else` branch with its own `break` and dedicated emit. The flag really only models "did we hit the forbidden path?" Two states. Boolean is fine. A sealed class would be over-engineering.

### `CeTaskIdParser` regex unanchored
**Reviewer claim** (correctness #7, 🟢 MEDIUM): could false-match `docs/api/ce/task?id=` in a log line.
**Push-back rationale:** Sonar scanner output format is highly predictable. The prior `substringAfter` had identical exposure. If a log line ever contains a docs URL, the regex captures the wrong ID, the CE poll fails with "task not found", and the fallback wait kicks in — graceful degradation. Not worth tightening.

### Pre-flight `sonarService` not hoisted
**Reviewer claim** (correctness #6, 🟡 HIGH): the `run { }` block creates a local `sonarService` for pre-flight; the second `ServiceLookup.sonar(project)` at the post-scan stage could fail after a 5-min scan if the service somehow becomes unavailable.
**Push-back rationale:** `ServiceLookup.sonar(project)` returns null only if the `:sonar` plugin module isn't loaded. That's a startup-time check that doesn't change mid-IDE-session. Race window is theoretical. Accept as-is.

## Tier 4 — Deferred for future (not addressed, low ROI now)

- **`SUCCESS` with `null data` emits "UNKNOWN"** (correctness #3) — defensive fallback; cosmetic concern; Sonar service contract probably guarantees non-null data on SUCCESS.
- **2s delay before 403 detected on retry 2+** (correctness #4) — by-design retry behavior; minor nuisance only.
- **`utLineHits` field unused** (mentioned in earlier audit, not flagged in this review) — separate metric from `lineHits`; would be a separate enhancement.
- **Extract `isForbidden` and `stripAndTrim` to `internal` for unit testing** (test-quality §C) — solid suggestion but small refactor; bundle with future work.

## Methodology notes

### What worked

1. **Three lenses caught different things.** The cache thread-safety concern was flagged by both correctness and code-quality reviewers via different framings — that convergence was a useful signal. The test-coverage gaps came only from the test-quality reviewer; the `shouldRetry` semantic concern came only from the code-quality reviewer. Each lens caught its specialty.
2. **Sonnet was sufficient for review.** No model-tier escalation needed — the findings were grounded in concrete code excerpts with file:line citations.
3. **Parallel dispatch.** Three reviewers ran concurrently in ~3 minutes total. A serial dispatch would have taken ~10 minutes.

### What I'd do differently

1. **Trust-but-verify reviewer claims about existing infrastructure.** The code-quality reviewer claimed `HtmlEscape.unescape()` already existed in `:core`. It didn't — only `escapeHtml`. I caught this before Fix 3 dispatch and added `unescapeHtml` as a clean addition, but a worse outcome would have been dispatching a fix that called a non-existent function. Verify "X exists" claims with grep before acting.
2. **Severity inflation is normal.** Reviewers tend to mark "real bug that doesn't fire under current usage" as critical. Recalibrate when synthesizing rather than treating reviewer severities as ground truth.
3. **One reviewer flagged something specific to a coroutines-test API quirk.** `kotlinx-coroutines-test` 1.10.1 doesn't expose `currentTime` directly on `TestScope`; you have to go through `testScheduler.currentTime`. The implementer caught this during Fix 5 execution. Worth saving as a future memory entry for virtual-time test patterns.

## What shipped

5 review-fix commits on `fix/automation-handover-quality-tabs`, ordered:

| SHA | Findings addressed | Type |
|---|---|---|
| `e50693cb` | #1 + #2 | fix |
| `30115305` | #3 + #9 | refactor |
| `7c059f4f` | #4 + #10 | refactor |
| `b5968b1c` | #7 + #8 | refactor |
| `6952beb1` | #5 + #6 | test |

**Net diff: +107 / -29 across 6 files.** ~30% tests, ~50% production logic, ~20% comment/KDoc.

All `:agent:test`, `:core:test`, and `:sonar:test` green at HEAD. `verifyPlugin` green.

## State at end of review cycle

Combined state on `fix/automation-handover-quality-tabs`:
- 15 implementation commits (4 editor-bug fixes + 10 local-analysis hardening + 1 type-correction)
- 5 review-fix commits
- 27 unit tests added across all the work
- All build + verifier checks green

Open items per Tier 4: low-ROI enhancements that can be picked up if/when the local_analysis surface needs further iteration.
