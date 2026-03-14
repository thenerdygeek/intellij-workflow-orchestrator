# Branch Reuse in Start Work Dialog

## Problem

When starting work on a Jira ticket that already has a branch (created previously via Jira's "Create branch" button or by a prior Start Work), the plugin always creates a new branch. The user should be able to reuse the existing branch instead.

## Solution

Add a dual-mode Start Work dialog: "Use existing branch" vs "Create new branch". Detect existing branches by querying Jira's dev-status API (the same data shown in the Development Panel on the issue). Fall back to searching Bitbucket branches by ticket key if the API is unavailable.

## Data Flow

```
StartWorkAction (SprintDashboardPanel)
  ├── 1. Fetch remote branches from Bitbucket (existing — for source branch dropdown)
  ├── 2. Fetch linked branches from Jira dev-status API (NEW)
  │     GET /rest/dev-status/1.0/issue/detail?issueId={numericId}&applicationType=stash&dataType=branch
  │     ├── Filter: only branches from the configured repo (projectKey/repoSlug)
  │     └── Fallback: if API fails, search Bitbucket branches containing ticket key
  └── 3. Show StartWorkDialog
        ├── If existing branches found → dual-mode (radio buttons)
        └── If no existing branches → create-only mode (current behavior)
```

## Jira Dev-Status API

**Endpoint:** `GET /rest/dev-status/1.0/issue/detail?issueId={numericId}&applicationType=stash&dataType=branch`

- `issueId` is the **numeric** issue ID (`JiraIssue.id`), not the key
- `applicationType` must be `stash` (Bitbucket Server's internal name), case-sensitive
- `dataType=branch` returns branches linked to the issue
- This is an internal/unsupported API but has been stable since Jira 7.x and powers Jira's own Development Panel

**Expected response structure:**
```json
{
  "detail": [
    {
      "branches": [
        {
          "name": "feature/PROJ-123-fix-order-service",
          "url": "https://bitbucket.example.com/...",
          "repository": {
            "name": "repo-name",
            "url": "https://bitbucket.example.com/projects/PROJ/repos/repo-name"
          },
          "lastCommit": {
            "message": "...",
            "authorTimestamp": "..."
          }
        }
      ]
    }
  ]
}
```

**Fallback:** If the dev-status API returns an error (404, 403, network error), search the already-fetched Bitbucket branch list for branches containing the ticket key (e.g., `PROJ-123`).

## Dialog UI

### When existing branches found (dual-mode):

```
┌─ Start Work — PROJ-123 ─────────────────────────┐
│                                                   │
│  Repository: PROJECT / repo-name                  │
│                                                   │
│  ● Use existing branch                            │
│    [ feature/PROJ-123-fix-order-service    ▼ ]    │
│                                                   │
│  ○ Create new branch                              │
│    Source: [ develop                       ▼ ]    │
│    Name:  [ feature/PROJ-123-fix-order... ]       │
│    ⟳ Cody generating branch name…                 │
│                                                   │
│  Branch will be checked out locally               │
│                                                   │
│               [ Cancel ]  [ Start Work ]          │
└───────────────────────────────────────────────────┘
```

### When no existing branches (current behavior, unchanged):

```
┌─ Start Work — PROJ-123 ─────────────────────────┐
│                                                   │
│  Repository: PROJECT / repo-name                  │
│                                                   │
│  Source branch: [ develop                  ▼ ]    │
│  New branch name: [ feature/PROJ-123-... ]        │
│                                                   │
│               [ Cancel ]  [ Create Branch ]       │
└───────────────────────────────────────────────────┘
```

### UI rules:

1. **Radio default**: "Use existing branch" selected by default (common case: resuming work)
2. **Single branch**: Show branch name as a label, no dropdown
3. **Multiple branches**: Dropdown to choose from
4. **"Create new branch" radio**: Enables the source branch + branch name fields; disables them when "Use existing" is selected
5. **Cody generation**: Only triggered when "Create new branch" is selected AND pattern requires Cody. Not triggered at all if user stays on "Use existing".
6. **OK button text**: "Start Work" when dual-mode, "Create Branch" when create-only (unchanged)

## Use Existing Branch Flow

When user selects "Use existing branch" and clicks Start Work:

1. `git fetch origin` — safe, only updates remote tracking refs
2. Check if branch exists locally:
   - **Yes**: `git checkout <branch>` — preserves any local commits ahead of origin
   - **No**: `git checkout -b <branch> origin/<branch>` — creates local tracking branch
3. Transition Jira ticket to "In Progress" (same as current flow)
4. Set active ticket in status bar (same as current flow)

No branch creation on Bitbucket. No force-pull. Local commits preserved.

## Files to Modify

| File | Change |
|---|---|
| `jira/api/JiraApiClient.kt` | Add `getDevStatusBranches(issueId: String)` method |
| `jira/api/dto/JiraDtos.kt` | Add `DevStatusResponse`, `DevStatusDetail`, `DevStatusBranch`, `DevStatusRepo` DTOs |
| `jira/service/BranchingService.kt` | Add `fetchLinkedBranches()` (dev-status + fallback), `useExistingBranch()` (fetch + checkout, no create) |
| `jira/ui/StartWorkDialog.kt` | Add radio buttons, conditional panel visibility, `StartWorkResult.useExisting` flag |
| `jira/ui/SprintDashboardPanel.kt` | Fetch linked branches before showing dialog, handle both result paths |

## DTOs

```kotlin
@Serializable
data class DevStatusResponse(
    val detail: List<DevStatusDetail> = emptyList()
)

@Serializable
data class DevStatusDetail(
    val branches: List<DevStatusBranch> = emptyList()
)

@Serializable
data class DevStatusBranch(
    val name: String,
    val url: String = "",
    val repository: DevStatusRepo? = null
)

@Serializable
data class DevStatusRepo(
    val name: String,
    val url: String = ""
)
```

## StartWorkResult Changes

```kotlin
data class StartWorkResult(
    val sourceBranch: String,
    val branchName: String,
    val useExisting: Boolean = false  // NEW
)
```

## Error Handling

- Dev-status API failure → silently fall back to Bitbucket branch search
- Bitbucket branch search failure → show create-only dialog (current behavior)
- Checkout failure → show error, don't transition Jira
- Both fetch methods run in background with loading indicator

## Testing

- Unit test: `DevStatusResponse` deserialization from fixture JSON
- Unit test: fallback filtering (branches containing ticket key)
- Unit test: `StartWorkResult.useExisting` flag propagation
- Manual: verify dialog shows existing branches, checkout works, Jira transitions
