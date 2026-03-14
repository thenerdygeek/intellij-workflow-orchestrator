# Build Tab: PR-Driven Redesign

## Problem

The Build tab uses a permanent 30% vertical splitter for the PR creation form, wasting space after the PR is created. The tab should be PR-centric: the selected PR determines which Bamboo builds to show.

## Design

The PR bar is a thin single-line strip (~40px) at the top of the Build tab, below the header. It adapts to three states:

### State 1: No PR exists for current branch

```
┌─────────────────────────────────────────────────────┐
│ feature/PROJ-123-fix-order            PROJ/my-service│
├─────────────────────────────────────────────────────┤
│ 🔀 No pull request for this branch   [ Create PR ]  │  ← Blue banner, 1 line
├─────────────────────────────────────────────────────┤
│                                                      │
│              No builds yet                           │
│    Bamboo builds will appear after PR is created      │
│                                                      │
└─────────────────────────────────────────────────────┘
```

Clicking "Create PR" expands the banner inline to show title + description fields. After creation, transitions to State 2. Expandable form has a ✕ Cancel button.

### State 2: Single PR exists (auto-selected)

```
┌─────────────────────────────────────────────────────┐
│ feature/PROJ-123-fix-order            PROJ/my-service│
├─────────────────────────────────────────────────────┤
│ ✓ PR #42  PROJ-123: Fix order NPE  → develop  OPEN │  ← Green bar, 1 line
├─────────────────────────────────────────────────────┤
│ Stages (35%)      │ Log/Detail (65%)                 │
│ ✓ Compile    1:12 │ [INFO] All stages passed         │
│ ✓ Unit Test  2:03 │ [INFO] Coverage: 87.3%           │
│ ⟳ Sonar Scan ...  │ [INFO] Quality gate: PASSED      │
│ ○ Deploy          │                                  │
├─────────────────────────────────────────────────────┤
│ IN PROGRESS — 3m 15s                                 │
└─────────────────────────────────────────────────────┘
```

Build stages show the latest Bamboo build for this PR's branch. "Open in browser ↗" link on the right.

### State 3: Multiple PRs (dropdown selector)

```
┌─────────────────────────────────────────────────────┐
│ feature/PROJ-123-fix-order            PROJ/my-service│
├─────────────────────────────────────────────────────┤
│ ✓ [ PR #42 PROJ-123: Fix NPE → develop      ▾ ]   │  ← Dropdown, 1 line
├─────────────────────────────────────────────────────┤
│ Stages (35%)      │ Log/Detail (65%)                 │
│ ...               │ ...                              │
└─────────────────────────────────────────────────────┘
```

Dropdown shows all PRs for the current branch in the current repo. Each entry shows: PR number, title, target branch. Selecting a PR switches the build view to that PR's Bamboo builds.

## Data Source

PR detection uses `BitbucketBranchClient.getPullRequestsForBranch()` from `:core`, which queries the Bitbucket REST API directly:

```
GET /rest/api/1.0/projects/{proj}/repos/{repo}/pull-requests?direction=OUTGOING&at=refs/heads/{branch}&state=OPEN
```

This respects the architecture rule (`:bamboo` depends only on `:core`, never on `:jira`). No Jira dev-status API is used here — Bitbucket is the source of truth for PR existence.

### DTO changes required

`BitbucketPrResponse` needs `fromRef` and `toRef` fields added to display the target branch in the PR bar:

```kotlin
@Serializable
data class BitbucketPrResponse(
    val id: Int,
    val title: String,
    val state: String,
    val links: BitbucketLinks,
    val fromRef: BitbucketPrRef? = null,  // NEW
    val toRef: BitbucketPrRef? = null     // NEW
)

@Serializable
data class BitbucketPrRef(
    val id: String,                        // e.g., "refs/heads/feature/PROJ-123"
    val displayId: String = ""             // e.g., "feature/PROJ-123"
)
```

## PR Bar Behaviors

| Trigger | Action |
|---|---|
| Tab opened / branch changed | Fetch PRs via `BitbucketBranchClient.getPullRequestsForBranch()` on `Dispatchers.IO` → update state on EDT |
| No PRs found | Show blue "No PR" banner |
| 1 PR found | Show green info bar, auto-select, load builds |
| N PRs found | Show green dropdown bar, select first, load builds |
| "Create PR" clicked | Expand inline form below banner |
| PR created successfully | Collapse form, emit `PullRequestCreated` event via EventBus, transition to State 2 |
| PR dropdown selection changed | Call `BuildMonitorService.switchBranch()` to poll builds for selected PR's branch |
| "Open in browser" clicked | Open PR URL via `BrowserUtil.browse()` |

## Create PR Expanded Form

When expanded, shows below the banner:

- **Target branch**: from settings (default: `develop`)
- **Title**: auto-populated from active ticket via `PrService.buildPrTitle()` (`:core`)
- **Description**: text area, pre-filled via `PrService.buildFallbackDescription()` (`:core`)
- **Buttons**: "Create PR" (primary) + "Regenerate Description" + "✕ Cancel"

"Regenerate Description" calls `PrService.buildEnrichedDescription()` from `:core`, which uses reflection to optionally access `PsiContextEnricher` (from `:cody`) and `MavenProjectsManager` at runtime. No compile-time cross-module dependency — all modules share the same plugin classloader. The enriched description includes affected Maven modules and `@RestController` endpoints.

After successful creation: form collapses, banner transitions to green State 2.

## Build Stages

Build stages are tied to the selected PR. When a PR is selected:

1. Extract the branch name from `pr.fromRef.displayId`
2. Call `BuildMonitorService.switchBranch(planKey, branchName, interval)` — this method already exists and handles polling restart
3. Display stages + logs in the existing splitter layout (unchanged)

No changes needed to `BuildMonitorService`.

## Threading

- **PR fetching**: `BitbucketBranchClient.getPullRequestsForBranch()` runs on `Dispatchers.IO` (already a `suspend fun`)
- **UI state transitions**: Banner color, form expand/collapse, dropdown updates — all via `invokeLater` on EDT
- **PR creation**: Button disables, fires coroutine on `Dispatchers.IO`, result applied on EDT
- **Event emission**: `PullRequestCreated` emitted from coroutine scope after successful creation

## Files to Modify

| File | Change |
|---|---|
| `bamboo/ui/BuildDashboardPanel.kt` | Replace outer splitter with PrBar + build splitter |
| `bamboo/ui/PrCreationPanel.kt` | Rewrite as `PrBar` — thin adaptive bar with 3 states + expandable create form |
| `core/bitbucket/BitbucketBranchClient.kt` | Add `BitbucketPrRef` DTO, add `fromRef`/`toRef` to `BitbucketPrResponse` |

## Edge Cases

- **No Bamboo configured**: Build area shows "Configure Bamboo plan in Settings"
- **No Bitbucket configured**: PR bar hidden entirely, build tab works as before (current monitoring only)
- **PR created but Bamboo not triggered yet**: Stages list empty with "Waiting for build..."
- **Branch has no active ticket**: PR title populated from branch name only
- **On main/develop branch**: PR bar shows "No PR" banner with "Create PR" visible (supports release flows like develop → main)
- **PR creation succeeds**: Instant transition to State 2 via EventBus subscription (no re-fetch needed)
