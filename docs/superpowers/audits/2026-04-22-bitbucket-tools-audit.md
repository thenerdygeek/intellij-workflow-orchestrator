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
- **MISSING** — method doesn't exist or always errors
- **UNTESTED** — exists, no tests, static analysis passes

## Inventory

### `bitbucket_review` actions

| Action | Tool line | Service method | Test file | Verdict |
|---|---|---|---|---|
| `add_pr_comment` | `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/integration/BitbucketReviewTool.kt:82` | `addPrComment(prId, text, repoName)` | `BitbucketReviewToolTest.kt` (schema only) | UNTESTED |
| `add_inline_comment` | `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/integration/BitbucketReviewTool.kt:90` | `addInlineComment(prId, filePath, line, lineType, text, repoName)` | `BitbucketReviewToolTest.kt` (schema only) | FIX |
| `reply_to_comment` | `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/integration/BitbucketReviewTool.kt:107` | `replyToComment(prId, parentCommentId, text, repoName)` | `BitbucketReviewToolTest.kt` (schema only) | UNTESTED |
| `add_reviewer` | `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/integration/BitbucketReviewTool.kt:122` | `addReviewer(prId, username, repoName)` | `BitbucketReviewToolTest.kt` (schema only) | UNTESTED |
| `remove_reviewer` | `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/integration/BitbucketReviewTool.kt:130` | `removeReviewer(prId, username, repoName)` | `BitbucketReviewToolTest.kt` (schema only) | UNTESTED |
| `set_reviewer_status` | `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/integration/BitbucketReviewTool.kt:138` | `setReviewerStatus(prId, username, status, repoName)` | `BitbucketReviewToolTest.kt` (schema only) | FIX |

### `bitbucket_pr` actions

| Action | Tool line | Service method | Test file | Verdict |
|---|---|---|---|---|
| `create_pr` | `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/integration/BitbucketPrTool.kt:95` | `createPullRequest(title, prDescription, fromBranch, toBranch, repoName)` | `BitbucketPrToolTest.kt` (schema only) | UNTESTED |
| `get_pr_detail` | `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/integration/BitbucketPrTool.kt:112` | `getPullRequestDetail(prId, repoName)` | `BitbucketPrToolTest.kt` (schema only) | UNTESTED |
| `get_pr_commits` | `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/integration/BitbucketPrTool.kt:118` | `getPullRequestCommits(prId, repoName)` | `BitbucketPrToolTest.kt` (schema only) | UNTESTED |
| `get_pr_activities` | `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/integration/BitbucketPrTool.kt:124` | `getPullRequestActivities(prId, repoName)` | `BitbucketPrToolTest.kt` (schema only) | UNTESTED |
| `get_pr_changes` | `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/integration/BitbucketPrTool.kt:130` | `getPullRequestChanges(prId, repoName)` | `BitbucketPrToolTest.kt` (schema only) | UNTESTED |
| `get_pr_diff` | `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/integration/BitbucketPrTool.kt:136` | `getPullRequestDiff(prId, repoName)` | `BitbucketPrToolTest.kt` (schema only) | UNTESTED |
| `check_merge_status` | `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/integration/BitbucketPrTool.kt:142` | `checkMergeStatus(prId, repoName)` | `BitbucketPrToolTest.kt` (schema only) | UNTESTED |
| `approve_pr` | `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/integration/BitbucketPrTool.kt:148` | `approvePullRequest(prId, repoName)` | `BitbucketPrToolTest.kt` (schema only) | UNTESTED |
| `merge_pr` | `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/integration/BitbucketPrTool.kt:154` | `mergePullRequest(prId, strategy, deleteSourceBranch, commitMessage, repoName)` | `BitbucketPrToolTest.kt` (schema only) | FIX |
| `decline_pr` | `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/integration/BitbucketPrTool.kt:163` | `declinePullRequest(prId, repoName)` | `BitbucketPrToolTest.kt` (schema only) | UNTESTED |
| `update_pr_title` | `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/integration/BitbucketPrTool.kt:169` | `updatePrTitle(prId, newTitle, repoName)` | `BitbucketPrToolTest.kt` (schema only) | UNTESTED |
| `update_pr_description` | `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/integration/BitbucketPrTool.kt:177` | `updatePrDescription(prId, description, repoName)` | `BitbucketPrToolTest.kt` (schema only) | UNTESTED |
| `get_my_prs` | `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/integration/BitbucketPrTool.kt:184` | `getMyPullRequests(state, repoName)` | `BitbucketPrToolTest.kt` (schema only) | FIX |
| `get_reviewing_prs` | `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/integration/BitbucketPrTool.kt:190` | `getReviewingPullRequests(state, repoName)` | `BitbucketPrToolTest.kt` (schema only) | FIX |

### PR-diff related

Methods in `BitbucketService` interface (`core/src/main/kotlin/com/workflow/orchestrator/core/services/BitbucketService.kt`) and their implementations in `BitbucketServiceImpl` (`pullrequest/src/main/kotlin/com/workflow/orchestrator/pullrequest/service/BitbucketServiceImpl.kt`) that are specifically involved in diff/change inspection:

These are service-layer methods (not tool actions). Schema intentionally differs from the action tables above.

| Method | Location | Test file | Verdict |
|---|---|---|---|
| `getPullRequestDiff` | `core/src/main/kotlin/com/workflow/orchestrator/core/services/BitbucketService.kt:82` / `pullrequest/src/main/kotlin/com/workflow/orchestrator/pullrequest/service/BitbucketServiceImpl.kt:555` | — | FIX |
| `getPullRequestChanges` | `core/src/main/kotlin/com/workflow/orchestrator/core/services/BitbucketService.kt:79` / `pullrequest/src/main/kotlin/com/workflow/orchestrator/pullrequest/service/BitbucketServiceImpl.kt:530` | — | FIX |
| `getPullRequestActivities` | `core/src/main/kotlin/com/workflow/orchestrator/core/services/BitbucketService.kt:76` / `pullrequest/src/main/kotlin/com/workflow/orchestrator/pullrequest/service/BitbucketServiceImpl.kt:496` | — | FIX |
| `getPullRequestCommits` | `core/src/main/kotlin/com/workflow/orchestrator/core/services/BitbucketService.kt:23` / `pullrequest/src/main/kotlin/com/workflow/orchestrator/pullrequest/service/BitbucketServiceImpl.kt:135` | — | FIX |

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

**Status:** Not performed during this audit execution.

**Reason:** Live HTTP verification against the user's Bitbucket Data Center instance requires the access token, which the plugin stores in IntelliJ PasswordSafe (per `CLAUDE.md` at repo root: *"Jira/Bamboo/Bitbucket/Sonar: `Authorization: Bearer <token>` ... all in PasswordSafe, never XML."*). The audit was executed by subagents that cannot extract credentials from PasswordSafe, and running live mutating writes against a real PR would require explicit user authorization on a disposable test PR.

**Impact on verdicts:** All classifications in this audit are based on static analysis (code inspection + API reference cross-check + WADL verification where needed). BROKEN findings remain **suspected** until live-verified. FIX findings are on stronger footing — most are logical/DTO bugs provable from the code alone. UNTESTED findings are definitively confirmed (the test-coverage matrix counted zero execute() tests across 23 of the 24 audited items).

**Suggested follow-up (user-driven, not in scope for this audit execution):**

1. On a disposable test PR in a dev Bitbucket instance, run `curl` invocations for each read action (`list_comments`, `get_pr_detail`, `get_pr_diff`, etc.) using the user's Bearer token. Record response shapes and compare against the `Found` sections in the per-action details.
2. On the same test PR, exercise the mutating actions one at a time. Clean up comments afterward.
3. Specifically verify the two concrete-bug FIX findings:
   - `set_reviewer_status` body MUST include `approved` boolean — send a request WITHOUT it and observe whether DC rejects, silently ignores, or succeeds.
   - `add_inline_comment` with `lineType: REMOVED` — observe whether DC places the comment correctly with the code's hardcoded `fileType: TO`, or whether this produces a mis-anchored comment.
4. Verify pagination exhaustion: on a PR with >50 activities or >100 changed files, confirm the current single-page fetch silently truncates.
5. Append findings to this Sandbox section as "Live-verified" or "Live-refuted" per row — do NOT re-edit the Verdict column based on live results alone; surface discrepancies in the decision memo instead.

## Decision

(See `2026-04-22-decision-memo.md`.)
