---
title: Automation Baseline Cache + Refresh-on-Demand
date: 2026-05-11
branch: fix/automation-handover-quality-tabs
status: design — awaiting implementation plan
---

# Automation Baseline Cache + Refresh-on-Demand

## Goal

Make the Automation tab's "find the baseline `DockerTagsAsJson`" scan **one API call instead of eleven**, persist its result across IDE restarts, and give the user explicit control over when fresh data is fetched. Switching between suites in the dropdown must not trigger a scan.

## Why

Today, opening the Automation tab — or switching suites in the dropdown — runs `TagBuilderService.scoreAndRankRuns(planKey)`, which issues:

- 1 × `getRecentResults(planKey, 10)` (already expands `variables.variable` inline)
- 10 × `getBuildVariables(buildResultKey)` (one per build returned)

The 10 per-build calls are **redundant** — the variables are already present in the list response, but `BambooServiceImpl.getRecentBuilds` drops them in the DTO→`BuildResultData` mapping, forcing a re-fetch. Every suite change re-pays this cost. There is no cross-session memory of the last good baseline, so the panel goes blank on tab-open until the round-trip completes.

The user's mental model: scan the last 10 builds **once per tab session**, remember the result locally, expose a manual Refresh for explicit re-scans (also the safety valve for the one Bamboo edge case the cache can't detect — see *Non-goals*).

## Background — Bamboo immutability model

A `buildResultKey` like `PROJ-PLANA-1660` is a content-addressed identifier in Bamboo. Once the build's `lifeCycleState` reaches `Finished` (with `state ∈ {Successful, Failed, NotBuilt}`), its variables and stage outcomes are immutable **modulo one case**: an admin can re-run a single stage of a completed build, mutating that stage's state and outputs while keeping the same `buildResultKey`. Bamboo does not expose a `lastModifiedDate` or version stamp on the `result` REST surface that would let us detect this. The architectural answer is to make this case a **user-driven invalidation** (Refresh button), not silent reconciliation.

## Design overview

Two changes, layered:

1. **Plumb `variables` through `BuildResultData`** — N+1 elimination. Independent value even without caching.
2. **Add `BaselineCacheService`** — in-memory + on-disk persistence of the last good `BaselineLoadResult` per suite plan key. Suite-switch reads cache (no API call). Tab-open uses cache for instant render, reconciles in background. Refresh button bypasses cache.

## Components

| Component | Module | Responsibility |
|---|---|---|
| `BambooServiceImpl.getRecentBuilds` | `:bamboo` | Populate `BuildResultData.variables` from `dto.variables.variable`. |
| `BuildResultData` | `:core/model/build/` | Add `variables: Map<String, String> = emptyMap()` field. |
| `TagBuilderService.scoreAndRankRuns` | `:automation` | Replace per-build `getBuildVariables(resultKey)` call with `build.variables`. |
| `BaselineCacheService` (new) | `:automation` | Project-level service. In-memory map + JSON-backed persistence. API: `get(planKey)`, `put(planKey, BaselineLoadResult)`, `invalidate(planKey)`. |
| `AutomationPanel` | `:automation` | Tab-open / suite-switch / refresh wiring (see *Behavioral contracts*). |
| Refresh button (new) | `:automation` UI | Icon button in status row; click → bypass-cache fetch for the currently-selected suite. |

## Cache schema & storage

**Location:** `~/.workflow-orchestrator/{dirName}-{first6OfSHA256}/automation/baseline-cache.json`

Matches the existing per-project storage convention (`agent/`, `TagHistoryService`). Survives `.idea/` wipes. Single-writer per IDE process; last-writer-wins across IDE instances (acceptable — this is presentation data, not coordination state).

**Schema (v1):**

```json
{
  "version": 1,
  "entries": {
    "PROJ-PLANA": {
      "planKey": "PROJ-PLANA",
      "fetchedAt": "2026-05-11T10:34:00Z",
      "selectedBuildNumber": 1660,
      "ranked": [ /* List<BaselineRun> serialized */ ],
      "diagnostics": { /* BaselineDiagnostics serialized */ }
    }
  }
}
```

**Persistence:** atomic write — serialize to `baseline-cache.json.tmp`, then `Files.move(ATOMIC_MOVE)`. Matches the agent storage convention. Corrupt file on read → log warning, return empty cache, overwrite on next write.

**Eviction:** none. Bound is `N suites × ≤10 BaselineRun entries × ~20 services × short strings` — a few KB. No TTL: terminal-build data is immutable (modulo the documented stage-rerun case, which Refresh handles).

**What is *not* stored:** in-progress / queued builds (their variables don't contain `DockerTagsAsJson` yet). Only entries with at least one `BaselineRun` make it into the cache.

## Behavioral contracts

**"Default suite"** is the first item in `suiteCombo` after `AutomationPanel.loadSuites()` completes — i.e. `suiteCombo.getItemAt(0).planKey`. This is the same auto-selection that exists today (`AutomationPanel.kt:273-275`).

| Trigger | API call | Cache action | UI |
|---|---|---|---|
| Tab opens, cache empty for default suite | `getRecentResults(default, 10)` × 1 | Write entry on success | Show "Loading…", then render result. |
| Tab opens, cache has default suite | `getRecentResults(default, 10)` × 1 in background | Overwrite if fresh result differs | Render cached **immediately**; swap in fresh on success. On fetch error: keep cached value displayed and surface the error in the existing diagnostic banner. |
| Suite-switch in dropdown | none | none | Displayed baseline unchanged (sticky). |
| Refresh button click | `getRecentResults(selected, 10)` × 1 | Overwrite entry on success; leave existing entry intact on error | Replace displayed baseline with fresh result on success; on error, keep current display and surface in diagnostic banner. |
| IDE restart, reopen tab | same as "tab opens, cache has default suite" | — | — |

**Contracts, one sentence each:**

- *Tab-open:* render from cache if hit (instantly), then issue one Bamboo call to reconcile; if cache is cold, issue the call first and render on response.
- *Suite-switch:* no API call, no cache write, no display change. Only re-binds the selected-suite reference.
- *Refresh:* re-fetch the build list for the currently selected suite from Bamboo; overwrite cache and displayed baseline on success.

**Sticky baseline rationale:** the user described wanting the option to reuse one suite's `DockerTagsAsJson` across multiple suites' triggers ("use the same `DockerTagsAsJson` for all three suites"). The displayed baseline therefore does **not** auto-swap when the suite dropdown changes. The dropdown change only re-binds which suite Refresh / Trigger Now will target.

## UI changes

- **New refresh icon button**, JetBrains `AllIcons.Actions.Refresh`, placed inline in the same row as the existing "Baseline build #N" and "Unique Docker Tag" status labels at the top of `AutomationPanel`.
- Tooltip: *"Re-scan recent builds for {suite name}"*.
- Disabled while a fetch is in flight (tied to `loadGeneration` token).
- `onSuiteSelected(planKey)` is **gutted**: it now only updates `currentSuitePlanKey` for downstream consumers (Trigger Now, Refresh target, plan-variable dropdowns). It no longer calls `loadBaselineAndVariables`.
- Tab-open path (initial mount) and Refresh click are the only two callers of `loadBaselineAndVariables`.

## Threading & lifecycle

- Fetch on `Dispatchers.IO`; UI updates via `invokeLater` (existing pattern in `AutomationPanel`).
- `BaselineCacheService` exposes `suspend` getters/setters; reads/writes hit disk on `Dispatchers.IO` guarded by a coroutine `Mutex` (matches `:agent` persistence convention).
- In-memory `Map<String, CachedEntry>` is the source of truth at runtime; disk is the durability layer. Load-on-init at service construction.
- `loadGeneration: AtomicLong` token is preserved — short-circuits stale fetches when the user clicks Refresh twice quickly or switches suites mid-fetch.

## Testing

- **`BaselineCacheServiceTest` (new)** — write/read round-trip, atomic-replace under simulated crash (tmp file present, target absent), corrupt-JSON recovery (parse failure → empty in-memory cache, next write succeeds), schema-version guard (unknown version → ignore).
- **`TagBuilderServiceTest` (existing, extend)** — assert that `scoreAndRankRuns` makes **zero** calls to `BambooService.getBuildVariables` when `BuildResultData.variables` is populated. Verifies the N+1 fix didn't regress.
- **`BambooServiceImplTest` (existing, extend)** — assert `BuildResultData.variables` is populated from the DTO's `variables.variable` collection.
- **`AutomationPanelTest` (existing, extend)** — three scenarios:
  - Suite-switch in dropdown does not invoke `loadBaselineAndVariables`.
  - Refresh button click invokes `loadBaselineAndVariables` with `bypassCache=true`.
  - Tab-open with a populated cache renders the cached baseline synchronously, then overwrites on fresh response.
- **Integration smoke (manual)** — cold IDE start → tab-open → verify cached entry exists on disk and is non-empty → restart IDE → tab-open → verify panel renders instantly with cached baseline before any HTTP activity.

## Non-goals

- **Stage-rerun staleness on unchanged overall state.** Bamboo allows re-running a single stage of a completed build, mutating its variables under the same `buildResultKey` without flipping overall `state`. The `result` REST surface does not expose a stamp that would let us detect this. **Resolution:** Refresh button is the documented escape hatch. No silent reconciliation; no automatic deep-refresh affordance.
- **Multi-IDE write coherence.** Two IDEs editing the same project's cache file race; last writer wins. Acceptable — this is presentation data, not coordination state. The disk format is small enough that a clobbered write isn't expensive to redo on next Refresh.
- **TTL / size-based eviction.** Terminal-build data is immutable; the cache size is bounded by suite count × 10. No need.
- **Per-build sliding-window dedup of cached entries.** Originally on the table; eliminated by the N+1 fix — a "scan" is now one HTTP call carrying everything, so per-build cache hits don't save additional calls.

## Sequence — tab-open happy path

```
User clicks Automation tab
  └─ AutomationPanel.onShown()
       ├─ cacheService.get(defaultSuite)          // synchronous in-memory read
       │    └─ if non-null: render cached entry immediately
       └─ scope.launch(Dispatchers.IO):
            bambooService.getRecentBuilds(defaultSuite, 10)   // ONE call, variables inline
              └─ tagBuilderService.scoreAndRankRuns(...)      // pure compute on inlined vars
                  └─ if result differs from cached:
                       cacheService.put(defaultSuite, result)
                       invokeLater { panel.setBaseline(result) }
```

## References

- Current implementation:
  - `automation/src/main/kotlin/com/workflow/orchestrator/automation/service/TagBuilderService.kt:76` (`scoreAndRankRuns`)
  - `automation/src/main/kotlin/com/workflow/orchestrator/automation/ui/AutomationPanel.kt:338` (`onSuiteSelected`)
  - `automation/src/main/kotlin/com/workflow/orchestrator/automation/ui/AutomationPanel.kt:419` (`loadBaselineAndVariables`)
  - `bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/service/BambooServiceImpl.kt:511` (`getRecentBuilds` — drops variables today)
  - `bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/api/BambooApiClient.kt:275` (`getRecentResults` — already expands variables)
  - `bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/api/dto/BambooDtos.kt:97` (`BambooResultDto` — no `lastModifiedDate` field)
- Storage convention: `CLAUDE.md` → "Agent Storage" section.
- Persistence pattern: atomic two-file JSON write, coroutine `Mutex` (see `:agent` module).
