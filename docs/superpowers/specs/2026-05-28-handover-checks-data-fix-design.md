# Handover Checks — Data Fix ("make it work")

**Date:** 2026-05-28
**Branch:** bugfix
**Scope:** `:handover` (+ read-only consumption of `:core` events/state). No changes to `:bamboo`, `:sonar`, or `:automation`.
**Phase:** 1 of 2. This phase makes the checks *populate correctly*. The visual redesign is **Phase 2** (deferred — see "Deferred").

## Problem

The Handover tab's Checks grid renders almost every row as `—`, even when the underlying work demonstrably happened (a focused PR, a SUCCESS Bamboo build, and three triggered automation suites — confirmed from the user's `idea.log`).

## Verified root causes

Established by code trace + the user's logs (PR focused, **no active Jira ticket**, build already SUCCESS):

1. **Build → `—`**: `HandoverStateService.handleEvent` (`HandoverStateService.kt:212-325`) has **no `BuildLogReady` branch** — it falls through to `else -> return` (line 324). `BuildFinished` (the only event it acts on for build status) fires *only* on a live state transition (`BuildMonitorService.kt:244` — `isTerminal && statusChanged && !isFirstPoll`). A build that finished before monitoring began emits **`BuildLogReady` only** — which handover receives (`[Handover:State] Handling BuildLogReady` in the logs) and discards, even though `BuildLogReady` carries `status: BuildEventStatus`.
2. **Quality → `—`**: SonarQube is never auto-polled; `QualityGateResult` fires only when the Quality tab is opened or `focusQualityScope` changes (`SonarDataService.kt:568`). Going straight to Handover means no emission.
3. **Suites → `—`**: `ChecksTab.applySuites` (`ChecksTab.kt:176-192`) matches suites against **hardcoded name patterns** (`API-SMOKE` / `API-INT` / `E2E`). Real suite plan keys (e.g. `…AUTOTESTSA…`) match nothing, so triggered suites are invisible. They are present in `state.suiteResults` but unmapped.
4. **PR → `—`**: `prCreated` flips only on `PullRequestCreated`, scope-gated on `event.ticketId == activeTicket.key` (`HandoverStateService.kt:171-176`). No active ticket → never matches. Also, focusing an existing PR is **not** a creation event. Wrong semantics: the row tracks "a PR was created for the active ticket this session", not "a PR is focused".
5. **`resetStatusSlices` wrinkle**: on `focusPr` change (`HandoverStateService.kt:112-124`) `buildStatus`, `suiteResults`, `prCreated`, etc. are wiped. Correct in principle (PR-B data must not bleed into PR-A) but assumes events re-arrive to refill — which push-only edge-triggered sources don't guarantee.

### Key alignment finding (de-risks the Build fix)

`focusBuild.planKey == focusBuild.chainKey == resolvedChainKey` (`LatestBuildLookupImpl.kt:37-43`). `BuildMonitorService` polls that same key (`focusBuild.chainKey ?: planKey`, line 135) and stamps `BuildLogReady.planKey == BuildLogReady.chainKey ==` that key (lines 311-317). So a `BuildLogReady` for the focused build **already matches `focusBuild.planKey` exactly** — the existing scope-match predicate works unchanged.

## Decisions (from brainstorming)

- **D1 — Fold Quality into Build.** The Bamboo build's Sonar/quality job fails the build when the quality gate fails, so the overall build status already encodes the gate. Drop the separate Quality row and the Sonar dependency entirely (no `QualityGateResult` reliance, no `focusQualityScope`, no new EP). *(User-stated infra fact; trusted.)*
- **D2 — Pull, don't wait.** Where state already exists in `:core` (`focusPr`, and the `BuildLogReady` that already arrives), read it rather than waiting for an edge-triggered event that may never come.
- **D3 — Dynamic suites.** Render one row per suite actually present in `suiteResults`; drop hardcoded names.
- **D4 — Phase 2 deferred.** Visual redesign is a separate phase. Recorded choices for Phase 2: layout = "grouped, self-explaining" (readiness header + progress + per-section rows with reason + action link); readiness list will **include** the manual handover actions (Jira comment posted, time logged).

## Design

### Pure check-computation core (testable seam)

Introduce a pure function (no Swing, no IntelliJ infra) that maps state → the list of rows to render. This is the TDD target and mirrors existing pure-policy seams (`BuildPlanResolutionPolicy`, `findMatchingPrForBranch`).

```kotlin
enum class CheckState { OK, FAIL, RUNNING, PENDING, NA }   // NA == not applicable / no data

data class CheckRow(
    val label: String,
    val state: CheckState,
    val statusText: String,   // "OK" / "FAIL" / "running" / "—"
    val meta: String,         // e.g. "PROJ-X #10", "#412", "2 repos", ""
)

// Pure. Lives in handover/ui/tabs (or handover/model). No focusQualityScope, no Sonar.
fun computeStatusChecks(state: HandoverState): List<CheckRow>
```

Row construction:

| Row | Source | OK | FAIL | RUNNING | NA (`—`) |
|---|---|---|---|---|---|
| **Pull request** | `state.focusPrId` (new field, synced from `focusPr`) | id present → `#<id>` (meta = `prUrl` if available) | — | — | no focused PR |
| **Build** | `state.buildStatus` (now also set from `BuildLogReady`) | `SUCCESS` | `FAILED` | — | null |
| **Suite: `<suitePlanKey>`** (0..N rows) | each `state.suiteResults[i]` | `passed == true` | `passed == false` | `passed == null` | (no row if list empty → single "Tests" NA row) |
| **Docker tags** | `state.suiteResults.lastOrNull()?.dockerTagsJson` | non-blank → "N repos" | — | — | blank/null |

Quality row is **removed**.

### `HandoverState` change

Add one field, synced from `WorkflowContext.focusPr`:

```kotlin
val focusPrId: Int? = null,   // mirror of WorkflowContextService.state.focusPr?.prId
```

`prUrl` is retained (populated by `PullRequestCreated` when available — a bonus for the link, not required for the check). `prCreated` is retained for the ritual checklist but its PR item now reads `focusPrId != null` (see below). `qualityGatePassed` / `healthCheckPassed` fields stay (dormant) to avoid churn in `ShareTab`/override; they are simply no longer surfaced as a check row.

### `HandoverStateService` changes

1. **Consume `BuildLogReady`.** Add a branch in `handleEvent` that sets `buildStatus = BuildSummary(event.buildNumber, event.status, event.planKey)`. Add a `checkScope` case for `BuildLogReady` mirroring `BuildFinished`: `inScope = focusBuild?.planKey == event.planKey` (chainKey-equivalent, per alignment finding). Keep the existing `BuildFinished` branch (live transitions still update it).
2. **Sync `focusPrId`.** In the `focusPr` collector (currently calls `resetStatusSlices`), set `focusPrId` from the new `focusPr` (and clear it when `focusPr == null`). Seed it at `initialize()` from `workflowService.state.value.focusPr`. Net effect: the PR row reflects the focused PR immediately, independent of any ticket or creation event. `resetStatusSlices` continues to clear PR-specific *status* slices, but `focusPrId` is set to the new value in the same update (not left cleared).

### `ChecksTab` changes

- Replace the fixed `Array(8)` label/status/meta widgets with a **dynamically rebuilt grid** driven by `computeStatusChecks(state)` (rebuild on each `updateState`, same pattern as `rebuildChecklist`). Glyph/colour by `CheckState`: OK→SUCCESS, FAIL→ERROR, RUNNING→WARNING, PENDING/NA→SECONDARY_TEXT.
- Remove `applyQuality` and the hardcoded `applySuites` 3-row logic.
- Ritual checklist: the "PR created" item now reads `state.focusPrId != null` (label may stay "PR" / "Pull request").
- Visual styling otherwise unchanged this phase (two cards, current spacing). Phase 2 restyles.

### `HandoverPanel` changes

- `failedFromState` / override-banner `FailedCheck` computation: drop the Quality entry; derive failures from the new check set (Build FAIL; suites FAIL). Build failure already covers the former quality signal.

## Out of scope / Deferred (Phase 2 — visual redesign)

- The "grouped, self-explaining" layout (readiness header + progress bar + sectioned rows + inline reason/action links).
- Folding ritual actions (Jira comment posted, time logged) into the unified readiness list.
- A manual **Refresh** affordance.
- Prettifying suite labels (strip common prefixes).
- Removing the now-dormant `qualityGatePassed` field and its `ShareTab`/override references (cleanup; only if it stays unused).

## Testing (TDD — tests written from requirements first)

Pure-function tests on `computeStatusChecks` (no IntelliJ infra):

1. Empty `HandoverState` → PR=NA, Build=NA, Docker=NA, single Tests=NA row; **no Quality row ever**.
2. `focusPrId = 412` → PR row OK, meta `#412`.
3. `buildStatus = SUCCESS` → Build OK; `FAILED` → Build FAIL.
4. Three `suiteResults` with distinct `suitePlanKey`, `passed = null` → three RUNNING rows labelled by key (reproduces the user's `AUTOTESTSA/B/C`); arbitrary names (no `API-SMOKE` etc.) still appear.
5. One suite `passed = true`, one `false` → OK + FAIL rows.
6. `dockerTagsJson` with 2 keys → Docker OK, meta "2 repos"; blank → NA.

`HandoverStateService` tests (test constructor, no `BasePlatformTestCase`):

7. Emitting `BuildLogReady(planKey = focusBuild.planKey, status = SUCCESS)` with a matching `focusBuild` in `WorkflowContext` → `buildStatus.status == SUCCESS`. **Regression for the dropped-event bug.**
8. `BuildLogReady` whose `planKey` ≠ `focusBuild.planKey` → dropped (out of scope), `buildStatus` stays null.
9. `focusPr` set to `prId=412` (no active ticket) → `state.focusPrId == 412`. **Regression for the ticket-gating bug.**
10. `focusPr` cleared → `focusPrId == null`; status slices reset.

## Risks

- **Multiple builds polled**: today only the focused build is polled (subscription-driven), so `BuildLogReady` scope-matching to `focusBuild.planKey` is safe. If ambient multi-plan polling is ever added, the planKey match still scopes correctly.
- **Suite labels**: raw `suitePlanKey` is ugly but correct; prettifying is Phase 2.
- **`resetStatusSlices` timing**: a focus change briefly shows Build/suites as `—` until the next poll re-emits `BuildLogReady`/automation events — acceptable, and Phase 2's Refresh will cover manual re-pull.
