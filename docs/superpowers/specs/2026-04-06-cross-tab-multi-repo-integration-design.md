# Cross-Tab Multi-Repo Integration Design

## Problem

In a multi-module project with multiple git repos, the PR, Build, and Quality tabs operate independently with broken or missing multi-repo support:

- **PR tab** fetches PRs from all repos and has a working repo selector, but "All" only merged my+reviewing PRs (now fixed to show all PRs). Auto-select logic added to pick the latest PR per repo's current branch.
- **Build tab** has a repo selector (only visible if 2+ repos have `bambooPlanKey` configured), does NOT listen to EventBus `PrSelected` events, and its PrBar always reads from scalar settings — ignoring the repo selector.
- **Quality tab** has a repo selector (only visible if 2+ repos have `sonarProjectKey`), and `SonarDataService` listens to `PrSelected` but reads the scalar `settings.state.sonarProjectKey` — it cannot resolve which Sonar project corresponds to the selected PR's repo.
- **`PrSelected` event** carries only `(prId, fromBranch, toBranch)` — no repo information.
- **Auto-detect** only fills Bitbucket fields, leaving `bambooPlanKey` and `sonarProjectKey` blank — so repo selectors never appear in Build/Quality tabs.

## Design

### Approach: Enriched Events + Lightweight State Map

Enrich `PrSelected` with repo context fields. Add a `PrContext` state map to `EventBus` so tabs can query the last-selected PR for any repo (needed when the user manually switches a tab's repo selector). Build and Quality tabs subscribe to the event for live updates and query the map for manual repo switches.

### 1. PrSelected Event Enrichment

Add repo context fields to `WorkflowEvent.PrSelected`:

```kotlin
data class PrSelected(
    val prId: Int,
    val fromBranch: String,
    val toBranch: String,
    val repoName: String,           // RepoConfig.displayLabel
    val bambooPlanKey: String?,     // from RepoConfig, null if not configured
    val sonarProjectKey: String?,   // from RepoConfig, null if not configured
) : WorkflowEvent()
```

The PR tab's `onPrSelected` handler already has the full `BitbucketPrDetail` with `repoName`. It resolves the matching `RepoConfig` to get `bambooPlanKey` and `sonarProjectKey`, then emits the enriched event.

### 2. PrContext State Map

New data class in `:core`:

```kotlin
data class PrContext(
    val prId: Int,
    val fromBranch: String,
    val toBranch: String,
    val repoName: String,
    val bambooPlanKey: String?,
    val sonarProjectKey: String?,
)
```

Added to `EventBus` as a `ConcurrentHashMap<String, PrContext>` keyed by `repoName`. Updated automatically when `PrSelected` is emitted. Queryable by Build/Quality tabs when the user manually switches their repo selector.

### 3. Build Tab Integration

**Repo selector visibility:** Always shown when 2+ repos are configured (filtered by `isConfigured`, not just `bambooPlanKey`). Repos without a Bamboo plan key still appear but show a hint.

**EventBus subscription:** Build tab subscribes to `PrSelected`. On event:
1. Find the repo in the selector matching `event.repoName`
2. Switch selector to that repo (suppress listener to avoid double-fire)
3. Call `autoDetectAndMonitor(event.fromBranch)` using `event.bambooPlanKey` as the fallback plan key
4. The PrContext map is already updated by EventBus

**Manual repo switch:** When user changes the Build tab's repo selector:
1. Look up `PrContext` for that repo from the state map
2. If context exists → load builds for that PR's branch using `autoDetectAndMonitor`
3. If no context → hint: "No PR selected for {repo} — select one in the PR tab"
4. If context exists but `bambooPlanKey` is null → hint: "Bamboo plan key not configured for {repo} — configure in Settings"

**PrBar simplification:** The existing PrBar becomes a read-only PR info strip showing the currently active PR for the selected repo (title, branch, link to Bitbucket). PR creation and selection are driven from the PR tab.

### 4. Quality Tab Integration

**Repo selector visibility:** Same as Build tab — always shown when 2+ repos are configured (filtered by `isConfigured`).

**EventBus subscription:** `SonarDataService` already subscribes to `PrSelected`. Change: use `event.sonarProjectKey` instead of scalar `settings.state.sonarProjectKey`. `QualityDashboardPanel` also subscribes to switch its repo selector on event.

On `PrSelected` event:
1. If `event.sonarProjectKey` is not null → `refreshForBranch(event.fromBranch, event.sonarProjectKey)`
2. If null → don't fetch; the UI hint handles it

**Manual repo switch:** When user changes the Quality tab's repo selector:
1. Look up `PrContext` for that repo from the state map
2. If context exists + `sonarProjectKey` configured → `refreshForProject(sonarProjectKey)` with the PR's branch
3. If context exists + `sonarProjectKey` is null → hint: "SonarQube project key not configured for {repo} — configure in Settings"
4. If no context → hint: "No PR selected for {repo} — select one in the PR tab"

**`refreshForBranch` overload:** New method `refreshForBranch(branch: String, projectKey: String)` so event-driven calls pass the correct project key. The existing parameterless version stays as fallback for `BranchChanged` and `BuildFinished` events.

**Hint states** (in priority order):
1. `sonarProjectKey` not configured → "SonarQube project key not configured — configure in Settings"
2. No PR selected for this repo → "No PR selected for {repo} — select one in the PR tab"
3. PR selected, analysis failed → "SonarQube analysis failed for this build" (from CE task status)
4. PR selected, no analysis for branch → "No SonarQube analysis found for branch `X`. Analysis may be pending."
5. PR selected, analysis exists → full quality data display

### 5. Data Flow

**PR selected in PR tab:**

```
PrDashboardPanel: user clicks PR for Service-A
  → resolves RepoConfig for Service-A (gets bambooPlanKey, sonarProjectKey)
  → EventBus.emit(PrSelected(prId, fromBranch, toBranch, repoName, bambooPlanKey, sonarProjectKey))
  → EventBus updates prContextMap: {"Service-A" → PrContext(...)}
  ↓ (parallel)
Build tab:
  → switches repo selector to Service-A
  → autoDetectAndMonitor(fromBranch) with bambooPlanKey fallback
  → shows build stages or hint
Quality tab:
  → QualityDashboardPanel switches repo selector to Service-A
  → SonarDataService.refreshForBranch(fromBranch, sonarProjectKey)
  → shows quality data or hint
```

**User manually switches repo in Build/Quality tab:**

```
Tab: user switches repo selector to Service-B
  → looks up EventBus.prContextMap["Service-B"]
  → if found: load data for that PR's branch
  → if not found: show "No PR selected" hint
  → if found but key missing: show "not configured" hint
```

**Events that remain unchanged:**
- `BranchChanged` — resolves repo from editor context, works as before
- `BuildFinished` — triggers Quality tab refresh for current branch, works as before

### 6. Files Changed

| File | Change |
|------|--------|
| `core/events/WorkflowEvent.kt` | Add `repoName`, `bambooPlanKey`, `sonarProjectKey` to `PrSelected` |
| `core/events/EventBus.kt` | Add `PrContext` data class, `prContextMap: ConcurrentHashMap`, auto-update on `PrSelected` emit |
| `pullrequest/ui/PrDashboardPanel.kt` | Resolve `RepoConfig` when emitting `PrSelected`, pass repo fields |
| `bamboo/ui/BuildDashboardPanel.kt` | Subscribe to EventBus `PrSelected`, show all `isConfigured` repos in selector, add hint states, use PrContext on manual repo switch |
| `bamboo/ui/PrBar.kt` | Slim down to read-only PR info strip (remove PR creation/dropdown, show title + branch + link) |
| `sonar/service/SonarDataService.kt` | Use `event.sonarProjectKey` instead of scalar, add `refreshForBranch(branch, projectKey)` overload |
| `sonar/ui/QualityDashboardPanel.kt` | Subscribe to EventBus `PrSelected` for repo selector switching, show all `isConfigured` repos, add hint states, use PrContext on manual repo switch |
