# Bamboo plan/build identity subsystem — API-driven, key-shape-agnostic

**Date:** 2026-05-27
**Module:** `:bamboo` (+ small `:core` model touch for `BuildRef`)
**Status:** Design approved; ready for implementation plan
**Branch:** `feature/cross-ide-delegation`

---

## 1. Problem

The Build tab stopped showing stages and jobs. With a PR selected, the panel shows
only the green PR bar; after clicking Refresh, build numbers appear (build-history
list) but stages/jobs never render.

### Root cause (confirmed)

Commit `def4cda98` ("replace fragile branch-digit heuristic in getLatestResult with
regex") introduced `BRANCH_PLAN_KEY_REGEX = Regex("^.+-.+-\\d+$")` to classify whether
a plan key is a branch plan key. That regex requires **three** dash-separated segments
with a trailing pure-digit segment (e.g. `PROJ-PLAN-7`). The project's **real** Bamboo
branch plan keys append the branch number to the second segment with **no** dash — e.g.
`PROJ-PLAN138`, `PROJ-PLAN523`, `PROJ-SERVICE514`, `PROJ-WEB138` (confirmed: 23+
occurrences of `PROJ-PLAN138` in the codebase's own fixtures; resultKey example
`PROJ-PLAN138-UNIT-4` in `bamboo/CLAUDE.md`). The `PROJ-PLAN-7` shape exists **only** in
the test that commit wrote.

Consequently `"PROJ-PLAN138".matches("^.+-.+-\\d+$")` is `false`, so `getLatestResult`
treats an already-resolved branch plan key as a master key and builds
`/result/PROJ-PLAN138/branch/{branch}/latest` — a branch-of-a-branch URL that fails.

**Both symptoms share this single cause.** Of the three `getLatestResult` call sites,
only `BuildMonitorService.pollOnce` passes a non-null `branch`, so only it runs the
heuristic. The build-history list uses a different endpoint
(`/result/{key}?includeAllStates=…`), which is why build numbers appear while stages
do not.

### Why string heuristics are the wrong foundation

A master plan key ending in a digit (`PROJ-BUILD2`, the case `def4cda98` was trying to
fix) and a branch plan key (`PROJ-PLAN138`) are **structurally identical strings**. No
regex can reliably distinguish them, because the distinction is *provenance* (how the
key was obtained), not *text*. The old `.last().isDigit()` heuristic guessed one way and
broke `PROJ-BUILD2`; the regex guessed the other way and broke `PROJ-PLAN138`. Each fix
moves the breakage. The fix is to stop discarding the provenance the API already gives us.

---

## 2. Probe ground truth (Bamboo DC 10.2.14)

Source: `docs/research/2026-05-07-bamboo-audit-recommendations.md`, two committed probe
bundles run 2026-05-07 against the org's live Bamboo (build 100220). The design relies
only on endpoints validated there.

| Endpoint | Probe result | Role in this design |
|---|---|---|
| `GET /rest/api/latest/plan/{key}/branch?max-results=100` | **200**, 95 branches; matches `BambooBranchListResponse`; entries carry `key` + `shortName` | **Authoritative branch-plan-key source.** |
| `GET /rest/api/latest/result/{key}/latest?expand=stages.stage.results.result` | **200**; `stages.stage[].results.result[]` populated | **The only build-fetch URL form used.** |
| `GET /rest/api/latest/result/{key}/branch/{branch}/latest?expand=…` | **404** when the branch is the master plan's tracked branch; **omitted from the 10.2.14 swagger** | **Deleted.** Never used. |
| `GET /rest/api/latest/plan/{key}` | **200** (used today by `validatePlan`, body discarded) | Read the master's tracked-branch label to detect the `MasterTrackedBranch` case. |
| `GET /rest/api/latest/result/byChangeset/{sha}?expand=results.result.plan` | **200**; entries include `plan.key` and a `master` block | Not required by this design; noted as the branch→master walk if ever needed. |

**Bamboo's real three-state model** (from probe §3.1): for a given `(masterKey,
branchName)` exactly one holds —
1. a **child branch plan** exists → it has its own distinct key (from `/plan/{key}/branch`);
2. the branch **is the master's tracked branch** (e.g. `develop`) → there is no separate
   branch-plan key; the master plan key *is* the build target (this is correct Bamboo
   semantics, not a fallback);
3. **neither** → no Bamboo build exists for this branch.

The old code collapsed all three into one string guess.

---

## 3. Goals / non-goals

**Goals**
- Remove every plan/branch key string-shape heuristic in `:bamboo`
  (`BRANCH_PLAN_KEY_REGEX`, `.last().isDigit()`, the `Regex("^.+-.+-\\d+$")` guards in
  `PlanDetectionService`).
- Resolve plan/branch identity exclusively from API responses.
- One build-fetch URL form (`/result/{key}/latest`), probe-validated.
- One stage/job mapper, replacing three duplicated mappings.
- Strict empty state when no Bamboo build exists for a branch (state 3) — no silent
  master substitution.
- The `MasterTrackedBranch` case (state 2) renders correctly (it regressed too: `develop`
  builds were unreachable via the branch URL per probe §3.1).

**Non-goals**
- The plan `variableContext` `key`-vs-`name` DTO mismatch (probe §3.5) — flagged, not
  fixed here.
- Deployment projects, agents, global queue, per-build labels (probe DEFER/REJECT items).
- The webview/JCEF chat surface and agent tool docs (separate).

---

## 4. Components

### 4.1 `BambooPlanRef` — typed identity (in `:bamboo/model`)

Constructed **only** from API responses; `planKey` is always a verbatim API value.

```kotlin
sealed interface BambooPlanRef {
    val planKey: String

    /** A child branch plan with its own distinct key (from /plan/{master}/branch). */
    data class BranchPlan(
        override val planKey: String,
        val parentPlanKey: String,
        val branchShortName: String,
    ) : BambooPlanRef

    /** The branch is the master plan's tracked branch; the master key is the build target. */
    data class MasterTrackedBranch(
        override val planKey: String,   // == master key
        val branchShortName: String,
    ) : BambooPlanRef

    /** A raw master plan with no branch context (plan picker / no branch supplied). */
    data class Master(override val planKey: String) : BambooPlanRef
}
```

### 4.2 `BambooPlanResolver` — resolution authority (`:bamboo/service`, `@Service(PROJECT)`)

```kotlin
suspend fun resolve(masterKey: String, branchName: String?): BambooPlanRef?
```

Algorithm (no string-shape inspection anywhere):
1. `branchName` null/blank → `Master(masterKey)`.
2. `getPlanBranches(masterKey)` (→ `/plan/{master}/branch?max-results=100`, the
   existing offset-echo-safe pagination loop). Match an entry by
   `shortName == branchName` (case-sensitive, mirrors current `resolveBranchKey`):
   - match → `BranchPlan(entry.key, masterKey, entry.shortName)`.
3. No branch match → determine the master's tracked branch from a single deterministic
   API source: `getPlanInfo(masterKey)` (`/plan/{master}`, probe-validated 200). The
   exact tracked-branch field is confirmed against the captured `/plan/{key}` body during
   implementation; if the field is absent on this DC, the implementation falls straight to
   step 4 (no speculative second source):
   - tracked branch == `branchName` → `MasterTrackedBranch(masterKey, branchName)`.
4. Otherwise → `null` (state 3, strict empty state).

Caching: per-master branch map cached with a short TTL and invalidated on
`WorkflowEvent.BranchChanged`. Resolution failures (network) are not cached.

### 4.3 `BambooApiClient.getLatestResult` — single URL form

```kotlin
suspend fun getLatestResult(planKey: String): ApiResult<BambooResultDto>   // /result/{planKey}/latest?expand=stages.stage.results.result
```

The `branch` parameter, the `/branch/{name}/latest` URL branch, `BRANCH_PLAN_KEY_REGEX`,
and the companion regex constant are **deleted**. All callers pass a key already resolved
to a `BambooPlanRef.planKey`.

### 4.4 `BambooBuildStructureMapper` — one stage/job mapper (`:bamboo/service`)

Pure mapper: `BambooResultDto → BuildState` (and the UI `StageState` flattening). Reads
`stages.stage[].results.result[]`; each job's `buildResultKey` is carried verbatim for
per-job log fetch. Replaces:
- `BuildMonitorService.mapToBuildState`
- the stage mapping in `BambooServiceImpl`
- the `loadHistoricalBuild` stage mapping in `BuildDashboardPanel`

### 4.5 Provenance through the call chain

- `PlanDetectionService.resolveBranchKey` / `resolveBranchKeyOrNull` return `BambooPlanRef`
  (or `BambooPlanRef?`) instead of bare `String`; their `Regex("^.+-.+-\\d+$")` guards are
  deleted. `resolveBranchKeyOrNull` returns `null` for states 3 (preserving the
  no-master-substitution directive); `MasterTrackedBranch` is a valid non-null result.
- `ChainKeyResolverImpl` / `LatestBuildLookupImpl` consume the typed ref.
- `core.model.workflow.BuildRef` carries the resolved `planKey` (already does); no new
  field strictly required, but the resolver guarantees it is a real branch/target key.
- `BuildMonitorService.pollOnce(ref)` / `startPolling(ref, …)` take the resolved key.

---

## 5. Data flow (the fix)

1. PR focused → `WorkflowContextService.focusPr` → `computeFocusForPr` →
   `ChainKeyResolver` → `BambooPlanResolver.resolve(master, branch)` →
   `BranchPlan(PROJ-PLAN138, …)`.
2. `focusBuild` set from `LatestBuildLookup.fetchLatestBuild(PROJ-PLAN138)` →
   `/result/PROJ-PLAN138/latest` (200) → build populated on **initial load**.
3. `BuildMonitorService` focusBuild subscription → `startPolling(PROJ-PLAN138)` →
   `pollOnce` → `/result/PROJ-PLAN138/latest` → `mapToBuildState` → stages render.
4. Manual Refresh hits the identical path. Build-history list (`getRecentBuilds`) is
   unchanged.

State 2 (`develop` on a master-tracked branch): resolver returns
`MasterTrackedBranch(PROJ-PLAN)` → `/result/PROJ-PLAN/latest` (200). State 3: resolver
returns `null` → dashboard shows "No Bamboo branch plan for `<branch>` (master: `<key>`)".

---

## 6. Error handling

| Condition | Behavior |
|---|---|
| Resolver returns `null` (state 3) | Dashboard empty state naming the master plan; `focusBuild` stays null (existing strict directive). |
| `/plan/{key}/branch` non-200 | Resolver returns `null` (cannot confirm a branch plan); logged. Not cached. |
| `/result/{key}/latest` non-200 | Existing error surface in `pollOnce` (`log.warn`, stateFlow unchanged). |
| Master tracked-branch lookup fails | Treat as state 3 (`null`) — we cannot prove the master is the target. |

---

## 7. Testing

- **`BambooPlanResolverTest`** — three states from `plan_branches`-shaped fixtures:
  child branch plan (returns `BranchPlan` with the entry key); master-tracked branch
  (returns `MasterTrackedBranch`); no plan (returns `null`). Plus cache hit/invalidation
  on `BranchChanged`.
- **`BambooApiClientLatestResultTest`** (replaces `…HeuristicTest`) — `getLatestResult`
  emits exactly one `/result/{key}/latest` path with the stages expand and **no**
  `/branch/` segment, for keys of every shape including `PROJ-PLAN138` and `PROJ-BUILD2`.
- **`BambooBuildStructureMapperTest`** — stages→jobs flattening, `buildResultKey`
  carried verbatim, manual-stage flag, empty-stages case.
- **Regression test** — a `PROJ-PLAN138`-shaped branch key produces non-empty stages
  (the exact reported bug).
- Delete `BambooApiClientLatestResultHeuristicTest` (asserts the wrong `PROJ-PLAN-7`
  shape and the deleted regex).

---

## 8. Implementation constraints

- **The entire change lands in a SINGLE commit.** New types, resolver, API-client URL
  change + regex deletion, mapper consolidation, `PlanDetectionService` typed returns,
  call-site updates, all tests, and `bamboo/CLAUDE.md` doc updates are one atomic commit
  on `feature/cross-ide-delegation`.
- No `Co-Authored-By` trailer (project policy).
- `runBlocking` banned in `main/` (use `runBlockingCancellable`); resolver is `suspend`.
- Update `bamboo/CLAUDE.md` (Architecture + UI sections) in the same commit: document the
  three-state model, the resolver as the single key authority, and that
  `/branch/{name}/latest` is intentionally not used (probe-justified).
- Verification before completion: `./gradlew :bamboo:test --rerun --no-build-cache` +
  `./gradlew :core:test` + `verifyPlugin` green.

---

## 9. Files touched (estimate)

- `bamboo/model/BambooPlanRef.kt` (new)
- `bamboo/service/BambooPlanResolver.kt` (new)
- `bamboo/service/BambooBuildStructureMapper.kt` (new)
- `bamboo/api/BambooApiClient.kt` (delete regex + branch URL; `getLatestResult` signature)
- `bamboo/service/BuildMonitorService.kt` (use mapper + typed ref)
- `bamboo/service/BambooServiceImpl.kt` (use mapper)
- `bamboo/service/PlanDetectionService.kt` (typed returns; delete regex guards)
- `bamboo/workflow/{ChainKeyResolverImpl,LatestBuildLookupImpl}.kt` (typed ref)
- `bamboo/ui/BuildDashboardPanel.kt` (empty-state for state 3; historical mapping via mapper)
- `bamboo/CLAUDE.md` (doc)
- tests as in §7
