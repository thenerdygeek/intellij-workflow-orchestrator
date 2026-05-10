# Automation+Quality Chain-Key Unification — Implementation Plan

**Branch:** `fix/automation-handover-quality-tabs`
**Author:** session 2026-05-10
**Symptom that triggered this:** Automation tab showed `Build PROJ-PLANKEY-455 has no 'Unique Docker Tag'` (develop's build) when on a feature branch whose actual chain (`PROJ-PLANKEY523`) had its own builds.

## Root cause (architectural)

`WorkflowContextService` (Phase 5) is the canonical cross-tab state store. The Build tab fully consumes it via `state.map { focusPr }` → `autoDetectPlan` → chain key. The Automation tab does not — it has an independent user-driven branch combo, falls back to `RepoContextResolver.getPrimaryBranchName()`, and asks `BambooService.getLatestBuild(parentPlanKey, branchName)` to translate. That translator (`BambooServiceImpl.resolveBranchPlanKey`, line 1005) compares Bamboo's `BambooBranchDto.name` (display label) to the git branch name, never matches, falls through to a master-substituting fallback, and returns the wrong build.

The point fix (`name` → `shortName`) is correct but only patches the symptom. The architectural fix is to **delete the parallel path** so the broken shape ceases to exist.

## End-state architecture

- `BuildRef` carries `chainKey: String?` (the resolved branch-chain key, e.g. `PROJ-PLANKEY523`).
- `WorkflowContextService.computeFocusForPr` resolves the chain key during the `focusPr → focusBuild` cascade (using the existing 5-tier `autoDetectPlan` waterfall + `resolveBranchKey` which uses `shortName` correctly).
- All build-context consumers (Build, Automation, Quality, agent) read `BuildRef.chainKey` from `state.map { focusBuild }`.
- `BambooService` exposes only chain-key signatures: `getLatestBuild(chainKey)`, `getRecentBuilds(chainKey, n)`, `getBuildLog(resultKey)`. The `(planKey, branch)` overloads are deleted.
- `BambooServiceImpl.resolveBranchPlanKey` (the buggy `name`-vs-`shortName` helper) is deleted.
- The "second fallback" in `getLatestBuild` (master substitution on 404) is deleted.
- `BuildLogCache` keys by `chainKey`, not parent plan key.
- Faulty fallbacks throughout (`RepoContextResolver` for branch in Automation; `RepoContextResolver` for branch in Sonar sub-panels that aren't editor-file-specific) are deleted. Better empty state than wrong data.

## Phasing

### Phase A — Foundation (no behavior change)

Add `chainKey` to `BuildRef`. Make the cascade populate it. Update `LatestBuildLookup` to take chain key.

**Files:**
- `core/src/main/kotlin/com/workflow/orchestrator/core/model/workflow/Refs.kt`
  - `BuildRef`: add `chainKey: String?` (nullable; null when resolution failed). Keep `planKey` (parent/master).
- `core/src/main/kotlin/com/workflow/orchestrator/core/workflow/LatestBuildLookup.kt`
  - Change signature: `fetchLatestBuild(project, chainKey: String): BuildRef?` (drop `branch` arg).
- `bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/workflow/LatestBuildLookupImpl.kt`
  - Implement new signature: `client.getLatestResult(chainKey)` (no branch arg in the URL — use unbranched `/result/{chainKey}/latest`).
- `core/src/main/kotlin/com/workflow/orchestrator/core/workflow/WorkflowContextService.kt`
  - In `computeFocusForPr`: after resolving `bambooPlanKey` (the parent), call a new helper `resolveChainKey(parentPlanKey, branch)` that delegates to `BambooService.autoDetectPlan` (when available via a new EP `chainKeyResolver`) or to `:bamboo`'s `PlanDetectionService.resolveBranchKey` directly.
  - Decision: introduce `core/workflow/ChainKeyResolver.kt` interface + `ExtensionPointName` `chainKeyResolver`. `:bamboo` registers the impl using `PlanDetectionService.resolveBranchKey` (which correctly uses `shortName`). This avoids `:core → :bamboo` dependency; mirrors the existing `LatestBuildLookup` pattern.
  - Pass the resolved `chainKey` to `LatestBuildLookup.fetchLatestBuild(project, chainKey)`.
  - Populate `BuildRef.chainKey`.
- Tests:
  - `core/src/test/.../WorkflowContextServiceTest.kt`: cascade populates `chainKey` when resolver succeeds; leaves null on failure (no master substitution).
  - `bamboo/src/test/.../LatestBuildLookupImplTest.kt`: calls unbranched `/result/{chainKey}/latest`.

### Phase B — Automation migration (the user-visible fix) + Phase E (cache re-key)

`AutomationPanel` and `TagBuilderService` switch to chain-key. `BuildLogCache` re-keyed.

**Files:**
- `automation/src/main/kotlin/com/workflow/orchestrator/automation/ui/AutomationPanel.kt`
  - Subscribe to `WorkflowContextService.state.map { it.focusBuild }.distinctUntilChanged()` in panel scope.
  - On emit: re-run docker tag detection using `focusBuild.chainKey`.
  - Remove `RepoContextResolver.getPrimaryBranchName()` fallback at line 350. If `focusBuild.chainKey` is null, show empty state ("No build context for this PR's branch yet"). No fallback to wrong branch.
  - Repurpose branch combo to **display-only** (mirrors `focusBuild.branch`); user-driven branch picking removed. Header shows "Branch: <branch>" passive label like Build tab's PrBar.
  - In `tagDetection` block: remove the `cachedLog`/`detectDockerTag(ciPlanKey, branchForCi)` branching. Single path: `tagBuilderService.detectDockerTag(focusBuild.chainKey)` (uses cache internally; cache is keyed by chain).
  - Drop `resolveServiceCiPlanKey()` for docker-tag purposes — `focusBuild.chainKey` *is* the answer. (Keep it if needed for other things; verify usage.)
- `automation/src/main/kotlin/com/workflow/orchestrator/automation/service/TagBuilderService.kt`
  - Replace `detectDockerTag(serviceCiPlanKey: String, branchName: String)` with `detectDockerTag(chainKey: String)`. Internally calls `bambooService.getLatestBuild(chainKey)` (new chain-key-only signature). No branch arg, no fallback to master.
  - Remove `extractDockerTagFromBuildLog(planKey, branch)` legacy delegate.
- `core/src/main/kotlin/com/workflow/orchestrator/core/services/BuildLogCache.kt`
  - Key by `chainKey` (not parent `planKey`). Add `WorkflowEvent.BuildLogReady.chainKey: String` (new field — nullable for backwards compat during migration, asserted non-null after Phase D).
- `bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/service/BuildMonitorService.kt:229`
  - Pass chain key to `buildLogCache.put(logEvent)` (the poller already knows its chain key — it's the `planKey` it was started with after `autoDetectPlan`).
- Tests:
  - `automation/src/test/.../AutomationPanelTest.kt` (or service test): docker tag detection uses `focusBuild.chainKey`; null chain → empty state, not wrong data.
  - `core/src/test/.../BuildLogCacheTest.kt`: cache keyed by chain; cross-chain reads miss.

### Phase C — Quality tab service-layer cleanup

`SonarDataService` migrates from legacy `EventBus` events to `WorkflowContextService.state` flow.

**Files:**
- `sonar/src/main/kotlin/com/workflow/orchestrator/sonar/service/SonarDataService.kt`
  - Remove the `EventBus.events.collect` block that listens for `PrSelected`/`BranchChanged`/`BuildFinished`.
  - Replace with `WorkflowContextService.getInstance(project).state.map { it.focusQualityScope }.distinctUntilChanged().collect { … }` — refresh on emission.
  - Keep the `BuildFinished` reaction *only* if the panel needs to refresh on builds completing (verify if this is functionally needed). If yes, keep that one event; if no, delete.
- `sonar/src/main/kotlin/com/workflow/orchestrator/sonar/ui/CoveragePreviewPanel.kt:97-98` and `IssueDetailPanel.kt:313-314`
  - Keep the `RepoContextResolver.findRepositoryForPath(...).currentBranchName` calls. **Justification:** these panels render gutter coverage for a *specific editor file*, not the focused PR. `RepoContextResolver` is the right tool for the question "which repo does this file belong to". This is **not** a faulty fallback — it's correct usage of the editor-resolver. Mark with `editor-fallback-allowed` marker per the existing convention in `core/CLAUDE.md` if not already.
- Tests:
  - `sonar/src/test/.../SonarDataServiceTest.kt`: refresh-on-state-change verified; legacy event subscriptions removed.

### Phase D — Deletion (the architectural lock-in)

Delete the broken parallel path. Make the wrong shape compile-fail.

**Files:**
- `core/src/main/kotlin/com/workflow/orchestrator/core/services/BambooService.kt`
  - `getLatestBuild(planKey: String, branch: String? = null, repoName: String? = null)` → `getLatestBuild(chainKey: String)`. Drop `branch` and `repoName` args.
  - Same for `getRecentBuilds`.
- `bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/service/BambooServiceImpl.kt`
  - Delete `private suspend fun resolveBranchPlanKey(...)` (lines 995-1020). The buggy `name`-vs-`shortName` function. Callers should use `PlanDetectionService.resolveBranchKey` via the new `chainKeyResolver` EP.
  - Delete the "second fallback" in `getLatestBuild` (lines 95-108) — master substitution is a faulty fallback per the user's directive ("better to see no data than incorrect data").
  - Delete the entire branch-resolution waterfall in `getLatestBuild` (lines 77-112). New `getLatestBuild(chainKey)` is a single API call: `api.getLatestResult(chainKey)`.
  - Same simplification in `getRecentBuilds` (line 550 area).
- `bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/api/BambooApiClient.kt`
  - The `getLatestResult(planKey, branch)` overload that builds the `/branch/{name}/latest` URL: keep only if `BuildMonitorService` needs it. If the monitor switches to chain-key polling (which it should after Phase A), remove the branch-form overload.
- Migrate other callers:
  - Agent tools that wrap `BambooService.getLatestBuild` etc. (likely in `agent/src/main/kotlin/.../tools/`). Either pass chain keys or call `chainKeyResolver` first.
- Add a presence test:
  - `bamboo/src/test/.../BambooServiceShapeInvariantTest.kt`: scans the public API of `BambooService` interface. Fails if any method has both a `planKey` arg and a `branch` arg. Forces future contributors to use chain keys.

## Acceptance criteria

1. `./gradlew :core:test :bamboo:test :sonar:test :automation:test :agent:test` all pass.
2. `./gradlew verifyPlugin` passes.
3. Manual: open project, switch to a feature branch, open Automation tab → docker tag is correctly detected from the branch's chain build (not develop's).
4. Manual: change the focused PR in PR tab → Automation tab updates docker tag without manual refresh.
5. Manual: pick a PR for a branch that has no builds yet → Automation shows "No build context for this PR's branch yet" (empty state, not wrong data).
6. Source scan: `grep -r "getLatestBuild.*branch\|resolveBranchPlanKey" automation core bamboo sonar agent` returns no production hits.

## Out of scope (explicitly)

- Removing `RepoContextResolver.findRepositoryForPath` for editor-file-specific contexts (Sonar gutter coverage). Correct usage; not a faulty fallback.
- Quality sub-panel UI redesign.
- Automation tab UX (queue panel, monitor panel, tag staging) beyond the docker-tag detection block.

## Execution order

1. Phase A (foundation) — must land first.
2. Phase B + E (Automation migration + cache re-key) — depends on A.
3. Phase C (Sonar cleanup) — independent of A/B; can run in parallel.
4. Phase D (deletion) — depends on B (no callers of old API left in Automation) and any agent-tool callers being migrated. Last.

## Subagent dispatch

Per user preference (skip-subagent-reviews, single-implementer, current branch):
- Round 1: Agent for Phase A. Foreground.
- Round 2: Agents for Phase B+E and Phase C in parallel. Both foreground.
- Round 3: Agent for Phase D. Foreground.
- Each agent runs `./gradlew :<module>:test` for affected modules before reporting done. Verification before completion is mandatory.
