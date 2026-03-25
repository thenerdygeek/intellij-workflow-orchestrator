# Multi-Repo Selector Fixes — Design Spec

**Date:** 2026-03-25
**Modules affected:** `:jira` (SprintDashboardPanel, StartWorkDialog), `:pullrequest` (PrDashboardPanel, PrDetailPanel)

## Problem

In multi-repo projects, two key actions always operate on the primary repo instead of the intended repo:

1. **Sprint Start Work** — reads `settings.state.bitbucketProjectKey` (legacy scalar), ignoring which repo the user is working in
2. **PR Create** — reads `settings.bitbucketProjectKey` (legacy scalar), ignoring the repo filter dropdown

## Fix 1: Sprint Start Work Dialog — Repo Selector

### Current Flow
```
SprintDashboardPanel.onStartWork()
  → reads settings.state.bitbucketProjectKey / repoSlug (legacy scalars)
  → passes static "projectKey / repoSlug" string to StartWorkDialog
  → all branch operations use that single repo
```

### New Flow
```
SprintDashboardPanel.onStartWork()
  → RepoContextResolver.resolveFromCurrentEditor() to detect repo
  → passes all configured repos + detected repo index to StartWorkDialog
  → StartWorkDialog shows repo selector dropdown (pre-selects detected repo)
  → changing selection re-fetches branches from selected repo's Bitbucket coordinates
  → branch creation uses selected repo's projectKey/repoSlug
```

### Changes

**SprintDashboardPanel.kt** (lines 786-826):
- Replace `settings.state.bitbucketProjectKey` / `repoSlug` with `RepoContextResolver` resolution
- Collect all configured repos: `settings.getRepos().filter { it.isConfigured }`
- Pass `repos: List<RepoConfig>` and `selectedIndex: Int` to `StartWorkDialog`

**StartWorkDialog.kt**:
- Add constructor param: `repos: List<RepoConfig>`, `initialRepoIndex: Int`
- Add repo selector `ComboBox<String>` at the top of the dialog (using `repo.displayLabel`)
- Pre-select `initialRepoIndex`
- On repo change:
  - Update source branch dropdown by re-fetching branches from selected repo
  - Update `repoDisplay` label
- `StartWorkResult` gains a `repoConfig: RepoConfig` field so the caller knows which repo was selected
- The caller in SprintDashboardPanel uses `result.repoConfig` for branch creation and override storage

## Fix 2: PR Create Form — Use Selected Repo

### Current Flow
```
PrDashboardPanel → CreatePrAction → detailPanel.showCreateForm()
  → PrDetailPanel reads settings.bitbucketProjectKey (legacy scalar)
  → fetches branches + creates PR against primary repo only
```

### New Flow
```
PrDashboardPanel → CreatePrAction → detailPanel.showCreateForm(repoConfig)
  → resolves repo from activeRepoFilter dropdown, or RepoContextResolver fallback
  → PrDetailPanel uses provided RepoConfig for branch fetch + PR creation
```

### Changes

**PrDashboardPanel.kt** (CreatePrAction, line 384):
- Resolve `RepoConfig` before calling `showCreateForm()`:
  - If `activeRepoFilter` is set (not "All Repos"): find matching repo by `displayLabel`
  - Else: use `RepoContextResolver.resolveFromCurrentEditor()` or primary
- Call `detailPanel.showCreateForm(repoConfig)`

**PrDetailPanel.kt** (`showCreateForm`, lines 411-488):
- Change signature: `fun showCreateForm(repoConfig: RepoConfig? = null)`
- Use `repoConfig.bitbucketProjectKey` / `repoConfig.bitbucketRepoSlug` instead of `settings.bitbucketProjectKey`
- Use `repoConfig.localVcsRootPath` to find the right Git repo for current branch detection
- DefaultBranchResolver already handles multi-repo via `getRepoForPath()`

## Files Changed

| File | Change |
|------|--------|
| `jira/ui/SprintDashboardPanel.kt` | Use RepoContextResolver, pass repos to dialog |
| `jira/ui/StartWorkDialog.kt` | Add repo selector dropdown, re-fetch on change |
| `pullrequest/ui/PrDashboardPanel.kt` | Resolve RepoConfig, pass to showCreateForm |
| `pullrequest/ui/PrDetailPanel.kt` | Accept RepoConfig param, use for all operations |

## Testing

- **Start Work with multi-repo:** Open file from repo 2, click Start Work → repo 2 pre-selected, branches fetched from repo 2
- **Start Work repo switch:** Change dropdown to repo 1 → branches refresh, branch created on repo 1
- **PR Create with filter:** Select "repo-1" in PR filter, click Create PR → form uses repo-1 coordinates
- **PR Create with "All Repos":** Filter on "All Repos", click Create PR → uses context-detected repo
- **Single repo project:** No dropdown shown, behavior unchanged
