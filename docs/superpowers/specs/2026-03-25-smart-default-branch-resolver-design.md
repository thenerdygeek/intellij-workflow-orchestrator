# Smart DefaultBranchResolver — Design Spec

**Date:** 2026-03-25
**Modules affected:** `:core` (resolver, settings, git util, Bitbucket client), `:jira` (CurrentWorkSection, SprintDashboardPanel), `:bamboo` (BuildDashboardPanel, PrBar, CreatePrDialog), `:sonar` (SonarDataService), `:pullrequest` (PrDetailPanel)

## Problem

The plugin currently resolves the target/default branch using a hardcoded fallback chain: `settings.state.defaultTargetBranch ?: "develop"`. This fails for:

- Branches created outside the plugin (e.g., from Bitbucket UI)
- Branches off long-running feature branches (not off develop/main)
- Repos where the default branch isn't "develop"

## Solution

A 6-priority cascading resolver that combines local data, user overrides, Bitbucket API, and git merge-base to determine the correct target branch.

## Priority Chain

| Priority | Source | Cost | Reliability |
|----------|--------|------|-------------|
| 1 | Per-branch override (Start Work + manual edit) | Free (local) | 100% (user-set) |
| 2 | Existing PR target for current branch | Single API call | 100% (authoritative) |
| 3 | Merge-base against PR branches in repo | API call + local git | ~90% (heuristic) |
| 4 | Bitbucket repo default branch API | Single API call | High (server config) |
| 5 | origin/HEAD symbolic ref | Free (local) | Depends on clone freshness |
| 6 | Settings `defaultTargetBranch` fallback | Free (local) | User-configured |

Each priority short-circuits — first non-null result wins. Network failures are swallowed and fall through to the next priority.

## Architecture

### DefaultBranchResolver — Project-Level Service

```kotlin
@Service(Service.Level.PROJECT)
class DefaultBranchResolver(private val project: Project) : Disposable {

    suspend fun resolve(repo: GitRepository): String
    fun setOverride(repoPath: String, branch: String, target: String)
    fun getOverride(repoPath: String, branch: String): String?
    fun removeOverride(repoPath: String, branch: String)
    fun clearCache()

    companion object {
        fun getInstance(project: Project): DefaultBranchResolver
    }
}
```

- Subscribes to `BranchChanged` events via `EventBus` for cache invalidation
- Lifecycle tied to project via `Disposable`

### Bitbucket Credential Resolution

Priorities 2, 3, and 4 require Bitbucket `projectKey` and `repoSlug`. The resolver obtains these from the `RepoConfig` matched to the `GitRepository`:

```kotlin
val repoConfig = PluginSettings.getInstance(project).getRepoForPath(repo.root.path)
    ?: PluginSettings.getInstance(project).getPrimaryRepo()
val projectKey = repoConfig?.bitbucketProjectKey.orEmpty()
val repoSlug = repoConfig?.bitbucketRepoSlug.orEmpty()
```

If `projectKey` or `repoSlug` are blank, network-dependent priorities (2, 3, 4) are skipped entirely and resolution falls through to local priorities (5, 6).

### Storage — Per-Branch Override Map

New field in `PluginSettings.State`:

```kotlin
var branchTargetOverrides by string("")  // JSON: {"repoPath||branchName": "targetBranch", ...}
```

Key format: `repoPath||branchName` (e.g., `/path/to/repo||feature/ABC-123`). Uses `||` as separator since `:` appears in Windows paths (e.g., `C:\Users\...`) and `/` appears in branch names.

**JSON parse safety:** Override map is parsed with `try/catch`. On malformed JSON, the map is treated as empty and falls through to Priority 2. A warning is logged.

**Auto-populated by:** `SprintDashboardPanel` after `StartWorkDialog` returns a `StartWorkResult` with non-blank `sourceBranch`. When `sourceBranch.isBlank()` (existing branch selected), no override is stored.

**Manually populated by:** Pencil icon in `CurrentWorkSection`.

**Concurrency:** The override map and cache are accessed from multiple threads (EDT for `setOverride`, pooled threads for `resolve`). The in-memory override map uses `ConcurrentHashMap`. Cache fields are `@Volatile`.

### Priority 1 — Per-Branch Override

```
key = "repoPath||currentBranchName"
lookup branchTargetOverrides map → return if found
```

### Priority 2 — Existing PR Target

```
BitbucketBranchClient.getPullRequestsForBranch(projectKey, repoSlug, currentBranchName)
→ if any OPEN PR exists, return toRef.displayId
→ if multiple PRs, pick the most recently updated
```

Uses existing `getPullRequestsForBranch(projectKey: String, repoSlug: String, branchName: String): ApiResult<List<BitbucketPrResponse>>` method.

### Priority 3 — Merge-Base Against PR Branches

Has a **5-second timeout**. If merge-base computation exceeds this budget, the best result found so far is returned (or falls through to Priority 4).

```
1. Fetch all open PRs for the repo via new getAllPullRequests(projectKey, repoSlug)
2. Collect unique branches from all fromRef.displayId + toRef.displayId, excluding current branch
3. Order candidates:
   a. PR source branches whose toRef matches origin/HEAD (highest priority)
   b. Remaining branches sorted by frequency in PRs
4. Cap at 20 candidates
5. For each candidate, run: git merge-base <current> <candidate>
6. Pick the candidate with the fewest diverging commits from current branch
7. If timeout reached, return best candidate found so far (or null to fall through)
```

### Priority 4 — Bitbucket Repo Default Branch

```
New method: BitbucketBranchClient.getDefaultBranch(projectKey, repoSlug)
→ GET /rest/api/1.0/projects/{proj}/repos/{repo}/default-branch
→ return displayId
```

### Priority 5 — origin/HEAD

```
Existing logic: repo.branches.remoteBranches → find origin/HEAD symbolic ref
→ extract branch name from nameForLocalOperations, remove "origin/" prefix
```

### Priority 6 — Settings Fallback

```
PluginSettings.state.defaultTargetBranch → fallback to "develop"
```

## New API Methods

### BitbucketBranchClient

**`getDefaultBranch(projectKey: String, repoSlug: String): ApiResult<BitbucketBranch>`**

```
GET /rest/api/1.0/projects/{proj}/repos/{repo}/default-branch
```

Returns the repo's configured default branch.

**`getAllPullRequests(projectKey: String, repoSlug: String, state: String = "OPEN", limit: Int = 100): ApiResult<List<BitbucketPrResponse>>`**

```
GET /rest/api/1.0/projects/{proj}/repos/{repo}/pull-requests?state=OPEN&limit=100
```

No role or username filter — returns all PRs for the repo. Used to build the candidate branch list for merge-base.

### GitMergeBaseUtil (new, in :core)

**`findMergeBase(project: Project, root: VirtualFile, branch1: String, branch2: String): String?`**

Uses `Git.getInstance().runCommand()` to execute `git merge-base <branch1> <branch2>`. Returns the commit hash or null on failure.

**`countDivergingCommits(project: Project, root: VirtualFile, from: String, mergeBase: String): Int`**

Executes `git rev-list --count <mergeBase>..<from>`. Returns how many commits the branch has ahead of the merge-base.

## Caching Strategy

**Cache structure:**
```kotlin
private val cache = ConcurrentHashMap<String, String>()  // "repoPath||branch" → resolved target
```

Supports multi-repo without cache thrashing.

**Cache hit:** When `repoPath||currentBranch` exists in the map, return immediately.

**Invalidation triggers:**
- `BranchChanged` event from EventBus — clears entire cache (event doesn't carry `repoPath`, so targeted eviction isn't possible)
- `setOverride()` call — clears entire cache
- `removeOverride()` call — clears entire cache

## UI Changes

### CurrentWorkSection — Target Branch Indicator

Current:
```
Currently Working On
ABC-123  Fix payment timeout
[branch-icon] feature/ABC-123
```

New:
```
Currently Working On
ABC-123  Fix payment timeout
[branch-icon] feature/ABC-123 → develop [edit-icon]
```

- `→ develop` — `JBLabel` with `SECONDARY_TEXT` color
- `[edit-icon]` — clickable `JBLabel` using `AllIcons.Actions.Edit`, opens searchable branch picker popup (same pattern as `CreatePrDialog.filterBranches()`)
- On selection, calls `setOverride()` and updates label
- Target branch resolved async in coroutine scope + `invokeLater` to update UI

### Start Work Dialog — Auto-Store Source Branch

When `StartWorkDialog` returns `StartWorkResult`, `SprintDashboardPanel` stores the override only when the source branch is known:
```kotlin
if (result.sourceBranch.isNotBlank()) {
    resolver.setOverride(repoPath, result.branchName, result.sourceBranch)
}
```

### Settings UI — Clear Overrides

A "Clear branch target overrides" button in the General settings page (under the repo config section). Calls `resolver.clearCache()` and clears the `branchTargetOverrides` JSON string in PluginSettings.

## Caller Migration

All 9 call sites across 6 files replace `settings.state.defaultTargetBranch ?: "develop"` with:
```kotlin
DefaultBranchResolver.getInstance(project).resolve(repo)
```

Since `resolve()` is now `suspend`, callers in UI code use coroutine scope:
```kotlin
scope.launch {
    val target = DefaultBranchResolver.getInstance(project).resolve(repo)
    withContext(Dispatchers.EDT) {
        // update UI with target
    }
}
```

**Affected files (9 call sites across 6 files):**
- `bamboo/ui/BuildDashboardPanel.kt` (3 sites)
- `bamboo/ui/PrBar.kt` (2 sites)
- `bamboo/ui/CreatePrDialog.kt` (1 site)
- `sonar/service/SonarDataService.kt` (1 site)
- `pullrequest/ui/PrDetailPanel.kt` (1 site)
- `jira/ui/SprintDashboardPanel.kt` (1 site)

## Testing

### Unit Tests (DefaultBranchResolverTest)

- **Priority 1:** Override set → resolver returns override value
- **Priority 1:** Empty override map → falls through to Priority 2
- **Priority 1:** Malformed JSON in override string → falls through gracefully
- **Priority 2:** Open PR exists for branch → returns PR toRef
- **Priority 2:** Multiple PRs → returns most recently updated toRef
- **Priority 2:** No PR for branch → falls through to Priority 3
- **Priority 3:** Merge-base finds closest candidate → returns correct branch
- **Priority 3:** Timeout exceeded → returns best so far or falls through
- **Priority 4:** Bitbucket default branch API returns value → uses it
- **Priority 5:** origin/HEAD set → returns extracted branch name
- **Priority 6:** Settings fallback → returns configured value or "develop"
- **Full cascade:** All priorities fail → returns "develop"
- **Cache:** Second call returns cached value without network calls
- **Cache invalidation:** BranchChanged event clears cache, next call re-resolves

### Unit Tests (GitMergeBaseUtil)

- `findMergeBase` returns commit hash on success
- `findMergeBase` returns null on git command failure
- `countDivergingCommits` returns correct count

### Override Map Tests

- `setOverride` persists to PluginSettings JSON
- `getOverride` reads from PluginSettings JSON
- `removeOverride` deletes entry
- Concurrent access safety (set from EDT, read from IO)
- Windows path with `:` in key works correctly with `||` separator

## Files Changed/Created

| File | Action |
|------|--------|
| `core/util/DefaultBranchResolver.kt` | Rewrite: object → project service, add 6-priority chain |
| `core/util/GitMergeBaseUtil.kt` | New: merge-base and diverging commit utilities |
| `core/settings/PluginSettings.kt` | Add `branchTargetOverrides` field to State |
| `core/bitbucket/BitbucketBranchClient.kt` | Add `getDefaultBranch()`, `getAllPullRequests()` |
| `core/settings/GeneralConfigurable.kt` | Add "Clear branch target overrides" button |
| `jira/ui/CurrentWorkSection.kt` | Add target branch label + edit icon |
| `jira/ui/SprintDashboardPanel.kt` | Auto-store source branch after Start Work |
| `bamboo/ui/BuildDashboardPanel.kt` | Migrate to async resolver |
| `bamboo/ui/PrBar.kt` | Migrate to async resolver |
| `bamboo/ui/CreatePrDialog.kt` | Migrate to async resolver |
| `sonar/service/SonarDataService.kt` | Migrate to async resolver |
| `pullrequest/ui/PrDetailPanel.kt` | Migrate to async resolver |
| `core/test/.../DefaultBranchResolverTest.kt` | New: unit tests for resolver |
| `core/test/.../GitMergeBaseUtilTest.kt` | New: unit tests for git util |
