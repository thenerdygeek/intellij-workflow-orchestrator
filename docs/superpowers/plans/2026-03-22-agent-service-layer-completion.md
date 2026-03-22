# Agent Service Layer Completion — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Expose 38 missing methods on core service interfaces so the AI agent can access all plugin capabilities. Every method returns `ToolResult<T>` with domain data classes in `core/model/`.

**Architecture:** Add method signatures to core interfaces, add data classes to core models, implement in feature module service classes delegating to existing API clients. No new API endpoints — all underlying functionality already exists.

**Tech Stack:** Kotlin, IntelliJ Platform SDK, ToolResult pattern

---

## What's Being Added

### Jira (7 methods + 3 data classes)

| Method | Purpose | Data Class |
|--------|---------|-----------|
| `getBoards(type?, name?)` | Discover Jira boards | `BoardData` |
| `getSprintIssues(sprintId)` | List tickets in a sprint | reuse `JiraTicketData` |
| `getBoardIssues(boardId)` | List tickets on kanban board | reuse `JiraTicketData` |
| `searchIssues(text)` | Search by text/key | reuse `JiraTicketData` |
| `getDevStatusBranches(issueId)` | Branches linked to ticket | `DevStatusBranchData` |
| `startWork(issueKey, branchName, sourceBranch)` | Create branch + transition | `StartWorkResultData` |
| `downloadAttachment(issueKey, attachmentId)` | Get attachment content | `AttachmentContentData` |

### Bamboo (6 methods + 3 data classes)

| Method | Purpose | Data Class |
|--------|---------|-----------|
| `getPlans()` | List all plans | `PlanData` |
| `getProjectPlans(projectKey)` | Plans for a project | reuse `PlanData` |
| `searchPlans(query)` | Find plans by name | reuse `PlanData` |
| `getPlanBranches(planKey)` | List plan branches | `PlanBranchData` |
| `getRunningBuilds(planKey)` | Check running/queued builds | reuse `BuildResultData` |
| `getBuildVariables(resultKey)` | Variables used in a build | reuse `PlanVariableData` |

### Sonar (4 methods + 2 data classes)

| Method | Purpose | Data Class |
|--------|---------|-----------|
| `getBranches(projectKey)` | Analyzed branches + quality status | `SonarBranchData` |
| `getProjectMeasures(projectKey, branch?)` | Project health metrics | `ProjectMeasuresData` |
| `getSourceLines(componentKey, from?, to?)` | Line-level code + coverage | `SourceLineData` |
| `getIssuesPaged(projectKey, page, pageSize)` | Paginated issues | `PagedIssuesData` |

### Bitbucket (18 methods + 6 data classes)

| Method | Purpose | Data Class |
|--------|---------|-----------|
| `getBranches(filter?)` | List branches | `BranchData` |
| `createBranch(name, startPoint)` | Create branch | reuse `BranchData` |
| `searchUsers(filter)` | Find users | `BitbucketUserData` |
| `getPullRequestsForBranch(branch)` | Check if PR exists | reuse `PullRequestData` |
| `getMyPullRequests(state?)` | List user's PRs | reuse `PullRequestData` |
| `getReviewingPullRequests(state?)` | PRs to review | reuse `PullRequestData` |
| `getPullRequestDetail(prId)` | Full PR detail | `PullRequestDetailData` |
| `getPullRequestActivities(prId)` | Comments, approvals | `PrActivityData` |
| `getPullRequestChanges(prId)` | Changed files | `PrChangeData` |
| `getPullRequestDiff(prId)` | Raw diff text | `ToolResult<String>` |
| `getBuildStatuses(commitId)` | CI status for commit | `BuildStatusData` |
| `approvePullRequest(prId)` | Approve PR | `ToolResult<Unit>` |
| `unapprovePullRequest(prId)` | Remove approval | `ToolResult<Unit>` |
| `mergePullRequest(prId, strategy?, deleteSource?, message?)` | Merge PR | `ToolResult<Unit>` |
| `declinePullRequest(prId)` | Decline PR | `ToolResult<Unit>` |
| `updatePrDescription(prId, description)` | Update description | `ToolResult<Unit>` |
| `addPrComment(prId, text)` | General comment | `ToolResult<Unit>` |
| `checkMergeStatus(prId)` | Can PR be merged? | `MergeStatusData` |
| `removeReviewer(prId, username)` | Remove reviewer | `ToolResult<Unit>` |

---

## Dependency Graph

```
Task 1 (Jira: 7 methods + data classes) — independent
Task 2 (Bamboo: 6 methods + data classes) — independent
Task 3 (Sonar: 4 methods + data classes) — independent
Task 4 (Bitbucket: 18 methods + data classes) — independent

All 4 tasks touch different files and can run in sequence without conflicts.
```

---

### Task 1: Jira — 7 Methods + 3 Data Classes

**Files:**
- Modify: `core/src/main/kotlin/com/workflow/orchestrator/core/model/jira/JiraModels.kt`
- Modify: `core/src/main/kotlin/com/workflow/orchestrator/core/services/JiraService.kt`
- Modify: `jira/src/main/kotlin/com/workflow/orchestrator/jira/service/JiraServiceImpl.kt`

- [ ] **Step 1: Add data classes to JiraModels.kt**

```kotlin
data class BoardData(
    val id: Int,
    val name: String,
    val type: String  // "scrum" or "kanban"
)

data class DevStatusBranchData(
    val name: String,
    val url: String
)

data class StartWorkResultData(
    val branchName: String,
    val ticketKey: String,
    val transitioned: Boolean
)

data class AttachmentContentData(
    val filename: String,
    val mimeType: String?,
    val sizeBytes: Long,
    val content: String?,    // text content for text files, null for binary
    val filePath: String,    // local path where downloaded
    val attachmentId: String
)
```

- [ ] **Step 2: Add 7 methods to JiraService interface**

```kotlin
suspend fun getBoards(type: String? = null, nameFilter: String? = null): ToolResult<List<BoardData>>
suspend fun getSprintIssues(sprintId: Int): ToolResult<List<JiraTicketData>>
suspend fun getBoardIssues(boardId: Int): ToolResult<List<JiraTicketData>>
suspend fun searchIssues(text: String, maxResults: Int = 20): ToolResult<List<JiraTicketData>>
suspend fun getDevStatusBranches(issueId: String): ToolResult<List<DevStatusBranchData>>
suspend fun startWork(issueKey: String, branchName: String, sourceBranch: String): ToolResult<StartWorkResultData>
suspend fun downloadAttachment(issueKey: String, attachmentId: String): ToolResult<AttachmentContentData>
```

- [ ] **Step 3: Implement in JiraServiceImpl**

Each method delegates to the existing `JiraApiClient` or module-level service:
- `getBoards` → `apiClient.getBoards(type, nameFilter)` → map to `BoardData`
- `getSprintIssues` → `apiClient.getSprintIssues(sprintId, allUsers=true)` → map to `JiraTicketData`
- `getBoardIssues` → `apiClient.getBoardIssues(boardId, allUsers=true)` → map to `JiraTicketData`
- `searchIssues` → `apiClient.searchIssues(text, maxResults)` → map to `JiraTicketData`
- `getDevStatusBranches` → `apiClient.getDevStatusBranches(issueId)` → map to `DevStatusBranchData`
- `startWork` → Use `BranchingService` if accessible, or implement directly with `BitbucketBranchClient.createBranch()` + `transitionToInProgress()`
- `downloadAttachment` → Use `AttachmentDownloadService.downloadAttachment()`, then read text content for text MIME types

Each returns `ToolResult.success(data, summary)` with meaningful summaries like:
- "Found 3 boards matching 'sprint'"
- "12 issues in sprint 'Sprint 42'"
- "Downloaded report.txt (4.2 KB)"

- [ ] **Step 4: Build and commit**

Run: `./gradlew :core:compileKotlin :jira:compileKotlin`

```bash
git add core/src/main/kotlin/com/workflow/orchestrator/core/model/jira/ \
       core/src/main/kotlin/com/workflow/orchestrator/core/services/JiraService.kt \
       jira/src/main/kotlin/com/workflow/orchestrator/jira/service/JiraServiceImpl.kt
git commit -m "feat(jira): expose boards, sprint issues, search, branches, startWork, attachments on core interface"
```

---

### Task 2: Bamboo — 6 Methods + 3 Data Classes

**Files:**
- Modify: `core/src/main/kotlin/com/workflow/orchestrator/core/model/bamboo/BambooModels.kt`
- Modify: `core/src/main/kotlin/com/workflow/orchestrator/core/services/BambooService.kt`
- Modify: `bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/service/BambooServiceImpl.kt`

- [ ] **Step 1: Add data classes to BambooModels.kt**

```kotlin
data class PlanData(
    val key: String,
    val name: String,
    val projectKey: String,
    val projectName: String,
    val enabled: Boolean = true
)

data class PlanBranchData(
    val key: String,
    val name: String,
    val enabled: Boolean = true
)
```

Note: `PlanVariableData` and `BuildResultData` already exist and can be reused.

- [ ] **Step 2: Add 6 methods to BambooService interface**

```kotlin
suspend fun getPlans(): ToolResult<List<PlanData>>
suspend fun getProjectPlans(projectKey: String): ToolResult<List<PlanData>>
suspend fun searchPlans(query: String): ToolResult<List<PlanData>>
suspend fun getPlanBranches(planKey: String): ToolResult<List<PlanBranchData>>
suspend fun getRunningBuilds(planKey: String): ToolResult<List<BuildResultData>>
suspend fun getBuildVariables(resultKey: String): ToolResult<List<PlanVariableData>>
```

- [ ] **Step 3: Implement in BambooServiceImpl**

Each delegates to existing `BambooApiClient` methods:
- `getPlans` → `api.getPlans()` → map DTOs to `PlanData`
- `getProjectPlans` → `api.getProjectPlans(projectKey)` → map to `PlanData`
- `searchPlans` → `api.searchPlans(query)` → map to `PlanData`
- `getPlanBranches` → `api.getBranches(planKey)` → map to `PlanBranchData`
- `getRunningBuilds` → `api.getRunningAndQueuedBuilds(planKey)` → map to `BuildResultData`
- `getBuildVariables` → `api.getBuildVariables(resultKey)` → map to `PlanVariableData`

- [ ] **Step 4: Build and commit**

Run: `./gradlew :core:compileKotlin :bamboo:compileKotlin`

```bash
git add core/src/main/kotlin/com/workflow/orchestrator/core/model/bamboo/ \
       core/src/main/kotlin/com/workflow/orchestrator/core/services/BambooService.kt \
       bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/service/BambooServiceImpl.kt
git commit -m "feat(bamboo): expose plans, branches, running builds, build variables on core interface"
```

---

### Task 3: Sonar — 4 Methods + 2 Data Classes

**Files:**
- Modify: `core/src/main/kotlin/com/workflow/orchestrator/core/model/sonar/SonarModels.kt`
- Modify: `core/src/main/kotlin/com/workflow/orchestrator/core/services/SonarService.kt`
- Modify: `sonar/src/main/kotlin/com/workflow/orchestrator/sonar/service/SonarServiceImpl.kt`

- [ ] **Step 1: Add data classes to SonarModels.kt**

```kotlin
data class SonarBranchData(
    val name: String,
    val isMain: Boolean,
    val type: String,   // "LONG", "SHORT", "BRANCH"
    val qualityGateStatus: String? // "OK", "WARN", "ERROR"
)

data class ProjectMeasuresData(
    val reliability: String?,     // rating A-E
    val security: String?,
    val maintainability: String?,
    val coverage: Double?,
    val duplications: Double?,
    val technicalDebt: String?,   // e.g., "2d 4h"
    val linesOfCode: Long?
)

data class SourceLineData(
    val line: Int,
    val code: String,
    val coverageStatus: String?,  // "COVERED", "NOT_COVERED", "PARTIAL", null
    val conditions: Int?,
    val coveredConditions: Int?
)

data class PagedIssuesData(
    val issues: List<SonarIssueData>,
    val total: Int,
    val page: Int,
    val pageSize: Int
)
```

- [ ] **Step 2: Add 4 methods to SonarService interface**

```kotlin
suspend fun getBranches(projectKey: String): ToolResult<List<SonarBranchData>>
suspend fun getProjectMeasures(projectKey: String, branch: String? = null): ToolResult<ProjectMeasuresData>
suspend fun getSourceLines(componentKey: String, from: Int? = null, to: Int? = null): ToolResult<List<SourceLineData>>
suspend fun getIssuesPaged(projectKey: String, page: Int = 1, pageSize: Int = 100): ToolResult<PagedIssuesData>
```

- [ ] **Step 3: Implement in SonarServiceImpl**

Each delegates to existing `SonarApiClient` methods:
- `getBranches` → `apiClient.getBranches(projectKey)` → map to `SonarBranchData`
- `getProjectMeasures` → `apiClient.getProjectMeasures(projectKey, branch, metricKeys)` → map to `ProjectMeasuresData`
- `getSourceLines` → `apiClient.getSourceLines(componentKey, from, to)` → map to `SourceLineData`
- `getIssuesPaged` → `apiClient.getIssuesWithPaging(projectKey, page=page, pageSize=pageSize)` → map to `PagedIssuesData`

- [ ] **Step 4: Build and commit**

Run: `./gradlew :core:compileKotlin :sonar:compileKotlin`

```bash
git add core/src/main/kotlin/com/workflow/orchestrator/core/model/sonar/ \
       core/src/main/kotlin/com/workflow/orchestrator/core/services/SonarService.kt \
       sonar/src/main/kotlin/com/workflow/orchestrator/sonar/service/SonarServiceImpl.kt
git commit -m "feat(sonar): expose branches, project measures, source lines, paged issues on core interface"
```

---

### Task 4: Bitbucket — 18 Methods + 6 Data Classes

**Files:**
- Modify: `core/src/main/kotlin/com/workflow/orchestrator/core/model/bitbucket/BitbucketModels.kt`
- Modify: `core/src/main/kotlin/com/workflow/orchestrator/core/services/BitbucketService.kt`
- Modify: `pullrequest/src/main/kotlin/com/workflow/orchestrator/pullrequest/service/BitbucketServiceImpl.kt`

- [ ] **Step 1: Add data classes to BitbucketModels.kt**

```kotlin
data class BranchData(
    val id: String,
    val displayId: String,
    val latestCommit: String?,
    val isDefault: Boolean = false
)

data class BitbucketUserData(
    val name: String,
    val displayName: String,
    val emailAddress: String?
)

data class PullRequestDetailData(
    val id: Int,
    val title: String,
    val description: String?,
    val state: String,
    val fromBranch: String,
    val toBranch: String,
    val authorName: String?,
    val reviewers: List<ReviewerData>,
    val createdDate: Long,
    val updatedDate: Long,
    val version: Int
)

data class ReviewerData(
    val username: String,
    val displayName: String,
    val approved: Boolean,
    val status: String  // "APPROVED", "NEEDS_WORK", "UNAPPROVED"
)

data class PrActivityData(
    val id: Long,
    val action: String,  // "COMMENTED", "APPROVED", "MERGED", etc.
    val userName: String?,
    val timestamp: Long,
    val commentText: String?,
    val commentId: Long?,
    val filePath: String?,  // for inline comments
    val lineNumber: Int?    // for inline comments
)

data class PrChangeData(
    val path: String,
    val changeType: String  // "ADD", "MODIFY", "DELETE", "RENAME"
)

data class BuildStatusData(
    val state: String,  // "SUCCESSFUL", "FAILED", "INPROGRESS"
    val name: String,
    val url: String,
    val key: String
)

data class MergeStatusData(
    val canMerge: Boolean,
    val conflicted: Boolean,
    val vetoes: List<String>
)
```

- [ ] **Step 2: Add 18 methods to BitbucketService interface**

```kotlin
// Branch operations
suspend fun getBranches(filter: String? = null): ToolResult<List<BranchData>>
suspend fun createBranch(name: String, startPoint: String): ToolResult<BranchData>

// User search
suspend fun searchUsers(filter: String): ToolResult<List<BitbucketUserData>>

// PR listing
suspend fun getPullRequestsForBranch(branchName: String): ToolResult<List<PullRequestData>>
suspend fun getMyPullRequests(state: String = "OPEN"): ToolResult<List<PullRequestData>>
suspend fun getReviewingPullRequests(state: String = "OPEN"): ToolResult<List<PullRequestData>>

// PR detail
suspend fun getPullRequestDetail(prId: Int): ToolResult<PullRequestDetailData>
suspend fun getPullRequestActivities(prId: Int): ToolResult<List<PrActivityData>>
suspend fun getPullRequestChanges(prId: Int): ToolResult<List<PrChangeData>>
suspend fun getPullRequestDiff(prId: Int): ToolResult<String>

// Build status
suspend fun getBuildStatuses(commitId: String): ToolResult<List<BuildStatusData>>

// PR actions
suspend fun approvePullRequest(prId: Int): ToolResult<Unit>
suspend fun unapprovePullRequest(prId: Int): ToolResult<Unit>
suspend fun mergePullRequest(prId: Int, strategy: String? = null, deleteSourceBranch: Boolean = false, commitMessage: String? = null): ToolResult<Unit>
suspend fun declinePullRequest(prId: Int): ToolResult<Unit>
suspend fun updatePrDescription(prId: Int, description: String): ToolResult<Unit>
suspend fun addPrComment(prId: Int, text: String): ToolResult<Unit>
suspend fun checkMergeStatus(prId: Int): ToolResult<MergeStatusData>
suspend fun removeReviewer(prId: Int, username: String): ToolResult<Unit>
```

- [ ] **Step 3: Implement in BitbucketServiceImpl**

Each delegates to `BitbucketBranchClient` or `PrActionService`:
- Branch/user operations → `BitbucketBranchClient` directly
- PR listing → `BitbucketBranchClient.getMyPullRequests()` etc.
- PR detail/activities/changes/diff → `BitbucketBranchClient` methods
- PR actions (approve/merge/decline) → `PrActionService` methods
- Map DTOs to core model data classes
- Each returns `ToolResult` with meaningful summary

Note: `BitbucketServiceImpl` needs access to both `BitbucketBranchClient` and `PrActionService`. Read the existing implementation to see how services are accessed.

For `mergePullRequest`, the method needs the PR's current `version` number. Fetch detail first via `getPullRequestDetail`, then call merge with the version.

- [ ] **Step 4: Build and commit**

Run: `./gradlew :core:compileKotlin :pullrequest:compileKotlin`

```bash
git add core/src/main/kotlin/com/workflow/orchestrator/core/model/bitbucket/ \
       core/src/main/kotlin/com/workflow/orchestrator/core/services/BitbucketService.kt \
       pullrequest/src/main/kotlin/com/workflow/orchestrator/pullrequest/service/BitbucketServiceImpl.kt
git commit -m "feat(bitbucket): expose branches, users, PR lifecycle, merge status, build status on core interface"
```

---

## Final Verification

- [ ] Run full build: `./gradlew buildPlugin`
- [ ] Verify method counts:
  - JiraService: 10 existing + 7 new = 17 methods
  - BambooService: 13 existing + 6 new = 19 methods
  - SonarService: 6 existing + 4 new = 10 methods
  - BitbucketService: 9 existing + 18 new = 27 methods
  - **Total: 73 methods on core interfaces** (was 38)
- [ ] Update `CLAUDE.md` Agent-Exposable checklist if needed
