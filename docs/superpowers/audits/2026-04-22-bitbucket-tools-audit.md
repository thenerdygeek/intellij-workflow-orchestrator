# Bitbucket Tools Audit — 2026-04-22

**Context:** User flagged `bitbucket_review` + `bitbucket_pr` meta-tools and underlying `BitbucketService` methods as *"could be broken, never tested"*. This audit determines Phase 1+ scope of the PR Review Workflow project.

**Method:** For each action/method:
1. Read source
2. Cross-reference against `memory/reference_bitbucket_pr_comment_api.md` + Atlassian DC docs
3. Review test coverage
4. Live-test against sandbox (if available) — see §Sandbox
5. Classify

**Classifications:**
- **OK** — works, tested, matches API spec
- **FIX** — works for happy path, edge-case bugs
- **BROKEN** — does not work against real API
- **MISSING** — method doesn't exist or throws `NotImplementedError`
- **UNTESTED** — exists, no tests, static analysis passes

## Inventory

### `bitbucket_review` actions

Tool file: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/integration/BitbucketReviewTool.kt`

| Action | Tool line | Service method | Test file | Verdict |
|---|---|---|---|---|
| `add_pr_comment` | `BitbucketReviewTool.kt:82` | `addPrComment(prId, text, repoName)` | `BitbucketReviewToolTest.kt` (schema only) | |
| `add_inline_comment` | `BitbucketReviewTool.kt:90` | `addInlineComment(prId, filePath, line, lineType, text, repoName)` | `BitbucketReviewToolTest.kt` (schema only) | |
| `reply_to_comment` | `BitbucketReviewTool.kt:107` | `replyToComment(prId, parentCommentId, text, repoName)` | `BitbucketReviewToolTest.kt` (schema only) | |
| `add_reviewer` | `BitbucketReviewTool.kt:122` | `addReviewer(prId, username, repoName)` | `BitbucketReviewToolTest.kt` (schema only) | |
| `remove_reviewer` | `BitbucketReviewTool.kt:130` | `removeReviewer(prId, username, repoName)` | `BitbucketReviewToolTest.kt` (schema only) | |
| `set_reviewer_status` | `BitbucketReviewTool.kt:138` | `setReviewerStatus(prId, username, status, repoName)` | `BitbucketReviewToolTest.kt` (schema only) | |

### `bitbucket_pr` actions

Tool file: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/integration/BitbucketPrTool.kt`

| Action | Tool line | Service method | Test file | Verdict |
|---|---|---|---|---|
| `create_pr` | `BitbucketPrTool.kt:95` | `createPullRequest(title, prDescription, fromBranch, toBranch, repoName)` | `BitbucketPrToolTest.kt` (schema only) | |
| `get_pr_detail` | `BitbucketPrTool.kt:112` | `getPullRequestDetail(prId, repoName)` | `BitbucketPrToolTest.kt` (schema only) | |
| `get_pr_commits` | `BitbucketPrTool.kt:118` | `getPullRequestCommits(prId, repoName)` | `BitbucketPrToolTest.kt` (schema only) | |
| `get_pr_activities` | `BitbucketPrTool.kt:124` | `getPullRequestActivities(prId, repoName)` | `BitbucketPrToolTest.kt` (schema only) | |
| `get_pr_changes` | `BitbucketPrTool.kt:130` | `getPullRequestChanges(prId, repoName)` | `BitbucketPrToolTest.kt` (schema only) | |
| `get_pr_diff` | `BitbucketPrTool.kt:136` | `getPullRequestDiff(prId, repoName)` | `BitbucketPrToolTest.kt` (schema only) | |
| `check_merge_status` | `BitbucketPrTool.kt:142` | `checkMergeStatus(prId, repoName)` | `BitbucketPrToolTest.kt` (schema only) | |
| `approve_pr` | `BitbucketPrTool.kt:148` | `approvePullRequest(prId, repoName)` | `BitbucketPrToolTest.kt` (schema only) | |
| `merge_pr` | `BitbucketPrTool.kt:154` | `mergePullRequest(prId, strategy, deleteSourceBranch, commitMessage, repoName)` | `BitbucketPrToolTest.kt` (schema only) | |
| `decline_pr` | `BitbucketPrTool.kt:163` | `declinePullRequest(prId, repoName)` | `BitbucketPrToolTest.kt` (schema only) | |
| `update_pr_title` | `BitbucketPrTool.kt:169` | `updatePrTitle(prId, newTitle, repoName)` | `BitbucketPrToolTest.kt` (schema only) | |
| `update_pr_description` | `BitbucketPrTool.kt:177` | `updatePrDescription(prId, description, repoName)` | `BitbucketPrToolTest.kt` (schema only) | |
| `get_my_prs` | `BitbucketPrTool.kt:184` | `getMyPullRequests(state, repoName)` | `BitbucketPrToolTest.kt` (schema only) | |
| `get_reviewing_prs` | `BitbucketPrTool.kt:190` | `getReviewingPullRequests(state, repoName)` | `BitbucketPrToolTest.kt` (schema only) | |

### PR-diff related

Methods in `BitbucketService` interface (`core/src/main/kotlin/com/workflow/orchestrator/core/services/BitbucketService.kt`) and their implementations in `BitbucketServiceImpl` (`pullrequest/src/main/kotlin/com/workflow/orchestrator/pullrequest/service/BitbucketServiceImpl.kt`) that are specifically involved in diff/change inspection:

| Method | Location | Test file | Verdict |
|---|---|---|---|
| `getPullRequestDiff` | `BitbucketService.kt:82` / `BitbucketServiceImpl.kt:555` | none | |
| `getPullRequestChanges` | `BitbucketService.kt:79` / `BitbucketServiceImpl.kt:530` | none | |
| `getPullRequestActivities` | `BitbucketService.kt:76` / `BitbucketServiceImpl.kt:496` | none | |
| `getPullRequestCommits` | `BitbucketService.kt:23` / `BitbucketServiceImpl.kt:135` | none | |

### Action-by-action notes

(See `2026-04-22-bitbucket-tools-audit-details.md` for long-form per-action analysis.)

## Inventory notes

### Full `BitbucketService` interface method list (core/src/main/kotlin/com/workflow/orchestrator/core/services/BitbucketService.kt)

Enumerated from `grep -nE '^\s*(suspend\s+)?fun\s+[a-zA-Z]+'`:

| Line | Signature |
|---|---|
| 11 | `suspend fun listRepos(): ToolResult<List<RepoInfo>>` |
| 14 | `suspend fun createPullRequest(...)` |
| 23 | `suspend fun getPullRequestCommits(prId: Int, repoName: String? = null): ToolResult<List<CommitData>>` |
| 26 | `suspend fun addInlineComment(prId: Int, filePath: String, line: Int, lineType: String, text: String, repoName: String? = null): ToolResult<Unit>` |
| 29 | `suspend fun replyToComment(prId: Int, parentCommentId: Int, text: String, repoName: String? = null): ToolResult<Unit>` |
| 32 | `suspend fun setReviewerStatus(prId: Int, username: String, status: String, repoName: String? = null): ToolResult<Unit>` |
| 35 | `suspend fun getFileContent(filePath: String, atRef: String, repoName: String? = null): ToolResult<String>` |
| 38 | `suspend fun addReviewer(prId: Int, username: String, repoName: String? = null): ToolResult<Unit>` |
| 41 | `suspend fun updatePrTitle(prId: Int, newTitle: String, repoName: String? = null): ToolResult<Unit>` |
| 44 | `suspend fun testConnection(): ToolResult<Unit>` |
| 49 | `suspend fun getBranches(filter: String? = null, repoName: String? = null): ToolResult<List<BranchData>>` |
| 52 | `suspend fun createBranch(name: String, startPoint: String, repoName: String? = null): ToolResult<BranchData>` |
| 57 | `suspend fun searchUsers(filter: String, repoName: String? = null): ToolResult<List<BitbucketUserData>>` |
| 62 | `suspend fun getPullRequestsForBranch(branchName: String, repoName: String? = null): ToolResult<List<PullRequestData>>` |
| 65 | `suspend fun getMyPullRequests(state: String = "OPEN", repoName: String? = null): ToolResult<List<PullRequestData>>` |
| 68 | `suspend fun getReviewingPullRequests(state: String = "OPEN", repoName: String? = null): ToolResult<List<PullRequestData>>` |
| 73 | `suspend fun getPullRequestDetail(prId: Int, repoName: String? = null): ToolResult<PullRequestDetailData>` |
| 76 | `suspend fun getPullRequestActivities(prId: Int, repoName: String? = null): ToolResult<List<PrActivityData>>` |
| 79 | `suspend fun getPullRequestChanges(prId: Int, repoName: String? = null): ToolResult<List<PrChangeData>>` |
| 82 | `suspend fun getPullRequestDiff(prId: Int, repoName: String? = null): ToolResult<String>` |
| 87 | `suspend fun getBuildStatuses(commitId: String, repoName: String? = null): ToolResult<List<BuildStatusData>>` |
| 92 | `suspend fun approvePullRequest(prId: Int, repoName: String? = null): ToolResult<Unit>` |
| 95 | `suspend fun unapprovePullRequest(prId: Int, repoName: String? = null): ToolResult<Unit>` |
| 98 | `suspend fun mergePullRequest(...)` |
| 107 | `suspend fun declinePullRequest(prId: Int, repoName: String? = null): ToolResult<Unit>` |
| 110 | `suspend fun updatePrDescription(prId: Int, description: String, repoName: String? = null): ToolResult<Unit>` |
| 113 | `suspend fun addPrComment(prId: Int, text: String, repoName: String? = null): ToolResult<Unit>` |
| 116 | `suspend fun checkMergeStatus(prId: Int, repoName: String? = null): ToolResult<MergeStatusData>` |
| 119 | `suspend fun removeReviewer(prId: Int, username: String, repoName: String? = null): ToolResult<Unit>` |

### Full `BitbucketServiceImpl` override list (pullrequest/src/main/kotlin/com/workflow/orchestrator/pullrequest/service/BitbucketServiceImpl.kt)

All 21 interface methods have `override` implementations confirmed. One additional method exists in the interface with no tool wrapper: `unapprovePullRequest` (line 616 in impl) — not exposed via any agent action.

### Service method `getPullRequestsForBranch` — no tool wrapper

`getPullRequestsForBranch` (interface line 62, impl line 387) exists in the service interface and implementation but is not exposed by any agent meta-tool action. Not part of `bitbucket_pr` or `bitbucket_review`. Used internally by `PrDetailService` or pull-request tab UI only.

### Test coverage characterisation

All four test files found (`BitbucketReviewToolTest.kt`, `BitbucketPrToolTest.kt`, `BitbucketRepoToolTest.kt`, `BitbucketApiClientTest.kt`) contain only schema-level and metadata tests:
- Tool name assertion
- Action enum completeness check
- `required` parameter list check
- `allowedWorkers` set check
- `toToolDefinition()` schema shape check
- Missing/unknown action error path check

No test file exercises any action through a mocked `BitbucketService` — i.e., there are zero per-action integration tests for the `execute()` paths in either meta-tool.

### Additional test files found

- `core/src/test/kotlin/com/workflow/orchestrator/core/bitbucket/BitbucketApiClientTest.kt` — covers the low-level HTTP client, not the service layer
- `agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/integration/BitbucketRepoToolTest.kt` — same schema-only pattern as above

### Tool path discovered

`BitbucketPrTool.kt` is at:
`agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/integration/BitbucketPrTool.kt`
(not `BitbucketPullRequestTool.kt`)

## Sandbox

(Filled in after live testing, or marked "not available".)

## Decision

(See `2026-04-22-decision-memo.md`.)
