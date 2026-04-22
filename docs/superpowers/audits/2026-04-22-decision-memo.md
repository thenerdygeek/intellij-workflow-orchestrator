# PR Review — Phase 0 Decision Memo (2026-04-22)

## Audit summary

Source tables: `docs/superpowers/audits/2026-04-22-bitbucket-tools-audit.md` §Inventory (all three verdict tables: `bitbucket_review` actions, `bitbucket_pr` actions, PR-diff methods). Tallied across all 24 items.

| Classification | Count | % |
|---|---|---|
| OK | 0 | 0.0% |
| FIX | 9 | 37.5% |
| BROKEN | 0 | 0.0% |
| MISSING | 0 | 0.0% |
| UNTESTED | 15 | 62.5% |
| **Total** | **24** | **100%** |

Breakdown by suite:
- `bitbucket_review` (6 items): FIX 2, UNTESTED 4.
- `bitbucket_pr` (14 items): FIX 3, UNTESTED 11.
- PR-diff service methods (4 items): FIX 4, UNTESTED 0.

### FIX severity breakdown

Classified per the task's F-HIGH / F-MED / F-LOW rubric. F-HIGH = incorrect observable behaviour in production against real Bitbucket in common use (silent miscomments, silent truncation at realistic PR sizes, silent wrong-dataset filtering). F-MED = breaks only in edge cases or only blocks our new workflow. F-LOW = wrong metadata that doesn't affect the actual API call.

F-HIGH counts toward the broken-or-missing rate. F-MED and F-LOW do not.

| Action / Method | Severity | Evidence |
|---|---|---|
| `bitbucket_review.add_inline_comment` | **F-HIGH** | `InlineCommentAnchor.fileType` hardcoded to `"TO"` at `BitbucketBranchClient.kt:344-349`; any `lineType = "REMOVED"` caller produces a 400 or silently mis-anchors the comment on the wrong file side (details §`add_inline_comment` Diff bullet 4, lines 114-116). Silent miscomment = F-HIGH per task rubric. |
| `bitbucket_review.set_reviewer_status` | F-MED | Body omits `approved` boolean field (`BitbucketBranchClient.kt:361`); WADL example always includes it so omission is "undefined behaviour across DC versions" (details §`set_reviewer_status` Notes 4, lines 348-366). Status still sent — most DC versions accept it today; risk is future-version incompatibility, not a current silent bug. |
| `bitbucket_pr.merge_pr` | F-MED | Tool schema description at `BitbucketPrTool.kt:64` lists `"merge-commit, squash, ff-only"` but DC accepts `"no-ff, squash, rebase-no-ff"` (details §`merge_pr` Diff bullet "Strategy enum mismatch", lines 820-821). Re-evaluated against F-HIGH case: the LLM is the sole caller of the tool description, so copying the listed enum verbatim yields a 2/3 invalid-strategy rate — meaningful misuse. However, the resulting failure is **loud** (DC returns 400/409, agent surfaces the error to the user), and every F-HIGH exemplar in the rubric is explicitly silent (miscomments, truncation, wrong-dataset). A loud, immediately-visible API rejection is categorically different from silent data loss: the user sees "merge failed: invalid strategy", retries with the correct value, and no data is corrupted in the interim. The task prompt also explicitly pre-classifies this pattern ("description-string mismatches are borderline ... the actual API call works if you pass the right strategy"). Fix is a one-line doc change. Classified F-MED. |
| `bitbucket_pr.get_my_prs` | **F-HIGH** | No `username.1` query parameter sent; tool has no username param and service defaults to null (details §`get_my_prs` Found + Diff, lines 1013-1031). `pullrequest/CLAUDE.md` documents both `role.1` and `username.1` as required; without `username.1`, result set is "undefined/server-dependent and may return all PRs in the repo rather than just the current user's PRs." Silent wrong-dataset = F-HIGH. |
| `bitbucket_pr.get_reviewing_prs` | **F-HIGH** | Same defect as `get_my_prs` — `username.1` missing from the query string (details §`get_reviewing_prs` Found + Diff, lines 1077-1093). Silent wrong-dataset = F-HIGH. |
| `PR-diff.getPullRequestDiff` | F-MED | No size cap on `result.data` before return at `BitbucketServiceImpl.kt:555-575` (details §`getPullRequestDiff` Notes 1-2, lines 1174-1202). Current UI/display callers tolerate megabyte strings; the risk is OOM / uncapped LLM-prompt injection in Phase 4. Classified F-MED because production UI callers work today; also surfaced in the Risks section below as a latent OOM risk beyond this project. |
| `PR-diff.getPullRequestChanges` | **F-HIGH** | Two separate bugs. (1) Pagination: `isLastPage` parsed but never checked; only first 100 files returned (details §`getPullRequestChanges` Diff bullet "Pagination", lines 1269-1270). PRs with >100 files are silently truncated — realistic on large refactors. (2) `srcPath` for RENAME/COPY entries missing from both `BitbucketPrChange` DTO and `PrChangeData` domain model — renames silently appear as DELETE+ADD (details lines 1249-1265, 1271). Both are silent data-loss defects = F-HIGH. |
| `PR-diff.getPullRequestActivities` | **F-HIGH** | Pagination loop absent; hardcoded `limit=50`; `isLastPage` parsed but never checked (details §`getPullRequestActivities` Diff bullet "Pagination", lines 1350-1351). Active PRs with many review rounds exceed 50 activities — realistic on PRs in the review phase. Silent truncation of the very data stream the new Comments tab will render = F-HIGH. |
| `PR-diff.getPullRequestCommits` | **F-HIGH** | Pagination fields present on DTO (`BitbucketCommitListResponse` has `isLastPage` + `nextPageStart`) but service layer ignores them — only first 50 commits returned (details §`getPullRequestCommits` Diff bullet "Pagination", lines 1425-1429). Long-running feature branches with >50 commits are silently truncated. The linked-Jira-ticket resolution in `PrReviewTaskBuilder` and commit-status attribution both depend on a full commit list. F-HIGH. |

**F-HIGH count: 6 | F-MED count: 3 | F-LOW count: 0.**

### Broken-or-missing rate

```
(BROKEN + MISSING + F-HIGH) / TOTAL
= (0 + 0 + 6) / 24
= 25.0%
```

## Decision

**PROCEED** per spec §10 (`≤ 25% → PROCEED`).

Rationale: The rate lands exactly on the 25% boundary, which the spec defines as PROCEED territory. Every F-HIGH item is a localised defect with a clear patch (change a hardcoded constant, add a pagination loop, thread a username param) rather than a design failure; none require rebuilding `bitbucket_review` as a sibling meta-tool. The verdict is PROCEED-with-heavy-Phase-1: Phase 1 must bundle the 6 F-HIGH fixes and 3 pagination loops alongside its foundation work, and Phase 1 test coverage is starting from a "zero execute() paths tested" baseline (see Test-coverage matrix, details lines 1490-1515) — effectively the whole integration-test surface is green-field work. Had the rate been 30-40% with the same character of bugs, PROCEED would still have been defensible; had the F-HIGH items been design-level (e.g. wrong endpoint paths, wrong auth scheme) the verdict would have been ESCALATE regardless of rate.

**Boundary note:** This decision rests on the `merge_pr` severity classification holding as F-MED. The F-MED rate is 25.0%; reclassification of `merge_pr` to F-HIGH would move the rate to 29.2% and flip the decision to RE-SCOPE. The other 8 FIX classifications are less contentious. Plan 2 should review this classification during its scoping.

## Phase 1 / Phase 2 task deltas

Every bullet is phrased so Plan 2 can copy-paste it into its task list. File+line citations point into the code or into the details doc.

### Must-fix before Phase 1 foundation lands (all F-HIGH + pagination)

- Fix `add_inline_comment` fileType miscomment: in `core/src/main/kotlin/com/workflow/orchestrator/core/bitbucket/BitbucketBranchClient.kt:344-349`, change `InlineCommentAnchor.fileType` default from hardcoded `"TO"` to `if (lineType == "REMOVED") "FROM" else "TO"` (details Notes 2, option B, lines 126-132).
- Add `srcPath` support for renamed-file inline comments: add `val srcPath: String? = null` to `InlineCommentAnchor` (`BitbucketBranchClient.kt:344-349`), add optional `src_path` tool param at `BitbucketReviewTool.kt:90-104`, thread through `BitbucketService.addInlineComment` interface + impl (details Notes 3, lines 133).
- Enum-validate `lineType` at the tool layer against `{ADDED, REMOVED, CONTEXT}` in `BitbucketReviewTool.kt` (details Notes 1, line 125).
- Fix `get_my_prs` silent wrong-dataset: in `BitbucketServiceImpl.kt:418-439`, resolve the current username from `ConnectionSettings.bitbucketUsername` (non-blank) with fallback to `api.getCurrentUsername()` (`BitbucketBranchClient.kt:892-914`), and pass it as `username` to `api.getMyPullRequests(...)` which already accepts the param at `BitbucketBranchClient.kt:922-957` (details Notes 1, lines 1041-1054).
- Fix `get_reviewing_prs` silent wrong-dataset: identical change in `BitbucketServiceImpl.kt:441-462`, passing through to `api.getReviewingPullRequests(...)` at `BitbucketBranchClient.kt:963-998` (details Notes 1, lines 1103-1114).
- Extract `resolveCurrentUsername(): String?` helper in `BitbucketServiceImpl` so `get_my_prs` and `get_reviewing_prs` share one implementation (details Notes 3, line 1117).
- Add pagination loop to `getPullRequestChanges`: add `nextPageStart: Int? = null` to `BitbucketPrChangesResponse` (`BitbucketBranchClient.kt:239-242`), add `start: Int = 0` to `BitbucketBranchClient.getPullRequestChanges` signature at line 1500 and append `&start=$start` to the URL at line 1509, implement the loop in the **client layer** (because the current client returns only `.values`, stripping pagination fields) until `isLastPage=true` with a max-pages guard (details Notes 1, lines 1280-1284).
- Add `srcPath` to rename/copy detection: add `srcPath: BitbucketPath? = null` to `BitbucketPrChange` (`BitbucketBranchClient.kt:198`), add `srcPath: String? = null` to `PrChangeData` (`core/src/main/kotlin/com/workflow/orchestrator/core/model/bitbucket/BitbucketModels.kt:118`), propagate in mapping at `BitbucketServiceImpl.kt:543` (details Notes 2, lines 1286-1289).
- Add pagination loop to `getPullRequestActivities`: add `nextPageStart: Int? = null` to `BitbucketPrActivityResponse` (`BitbucketBranchClient.kt:233-236`), add `start: Int = 0` to `BitbucketBranchClient.getPullRequestActivities` at line 1125, implement the loop in the **client layer** (because the current client returns only `.values`, stripping pagination fields) with a max-pages guard of ~4 pages / 200 activities for LLM-injection safety (details Notes 1, lines 1361-1365).
- Add pagination loop to `getPullRequestCommits`: add `start: Int = 0` to `BitbucketBranchClient.getPullRequestCommits` signature at lines 1539-1543, append `&start=$start` to the URL at line 1549, implement the loop in the **service layer** in `BitbucketServiceImpl.getPullRequestCommits` starting at line 147 (`result.data.isLastPage` + `result.data.nextPageStart` are already on the response DTO) (details Notes 1, lines 1438-1457).

### Must-fix test additions for Phase 1

- Add execute()-path integration tests for the five F-HIGH methods above, covering happy-path, 4xx, and — for methods that take `version` — 409 stale-version (details Test coverage matrix, lines 1490-1515: currently all "—"). At minimum, one MockWebServer-backed test per fixed method.
- Add pagination test per fixed method: first page `isLastPage=false, nextPageStart=N` + second page `isLastPage=true` → verify both pages are merged into the returned list (details Notes per method — `getPullRequestChanges` line 1294, `getPullRequestActivities` line 1372, `getPullRequestCommits` line 1463).
- Add rename test for `getPullRequestChanges`: mock `type=RENAME` with `srcPath` populated → assert `PrChangeData.srcPath` propagates (details line 1293).
- Add lineType→fileType derivation test for `add_inline_comment`: `lineType=ADDED` → body contains `"fileType":"TO"`; `lineType=REMOVED` → body contains `"fileType":"FROM"` (details lines 137-138).
- Add enum-validation test at tool layer for invalid `lineType` → tool returns missing-param error before service call (details line 141).

### Should-fix alongside Phase 1 (F-MED — land with Phase 1 but do not block foundation merge)

- Fix `merge_pr` strategy-description mismatch: in `BitbucketPrTool.kt:64`, change the strategy description string from `"merge-commit, squash, ff-only"` to `"no-ff, squash, rebase-no-ff"` (details Notes 1, line 830). One-line doc change.
- Add `approved` field to `set_reviewer_status` request: in `BitbucketBranchClient.kt:361`, change `ReviewerStatusRequest` to include `val approved: Boolean`, set via `approved = (status == "APPROVED")` at `BitbucketBranchClient.kt:1685` (details Notes 2, lines 348-364).
- Add size cap to `getPullRequestDiff` before Phase 4 LLM injection: in `BitbucketServiceImpl.kt:566`, `take(MAX_DIFF_CHARS)` with `MAX_DIFF_CHARS = 327_680`, record `truncated` flag in the result summary (details Notes 1, lines 1175-1200). Land with Phase 1 even though consumers don't materialise until Phase 4 — prevents Phase 4 surprise.
- Add enum validation for `state` in `get_my_prs` and `get_reviewing_prs` against `{OPEN, MERGED, DECLINED, ALL}` at the tool layer (details Notes 2 in both sections, lines 1056, 1116).

### Phase 2 — new actions MISSING (new comment actions per spec §6.3)

None of these surface in the audit because they don't yet exist — they are the spec §6.3 additions. Adding for Plan-2 completeness.

- Implement `bitbucket_review.list_comments` action → `BitbucketService.listPrComments(prId, onlyOpen?, onlyInline?): ToolResult<List<PrComment>>`. DC path: activity-stream-derived. Cloud path: paginated `.../comments` (spec §6.3 and §6.4).
- Implement `bitbucket_review.get_comment` action → `BitbucketService.getPrComment(prId, commentId): ToolResult<PrComment>` (spec §6.3 and §6.4).
- Implement `bitbucket_review.edit_comment` action → `BitbucketService.editPrComment(prId, commentId, text, expectedVersion?)`. DC PUT with `version`, 409 → `STALE_VERSION` typed error. Cloud PUT (spec §6.3 and §6.4).
- Implement `bitbucket_review.delete_comment` action → `BitbucketService.deletePrComment(prId, commentId, expectedVersion?)`. DC DELETE `?version=N`. Cloud DELETE (spec §6.3 and §6.4).
- Implement `bitbucket_review.resolve_comment` action → `BitbucketService.resolvePrComment(prId, commentId)`. DC PUT `state:"RESOLVED"`. Cloud POST `.../resolve` (spec §6.3 and §6.4).
- Implement `bitbucket_review.reopen_comment` action → `BitbucketService.reopenPrComment(prId, commentId)`. DC PUT `state:"OPEN"`. Cloud DELETE `.../resolve` (spec §6.3 and §6.4).
- Introduce `PrComment` model in `:core` with `id`, `version`, `text`, `author`, `createdDate`, `updatedDate`, `anchor`, `state`, `severity`, `replies`, `permittedOperations` (spec §6.4).
- Introduce sealed `CommentPayload` with per-flavor serializers inside `BitbucketServiceImpl` — agent tool stays flavor-agnostic (spec §6.3).
- Apply per-write approval to each mutating new action (spec §6.3).

No surprise MISSING surfaced in the audit — `unapprovePullRequest` and `getPullRequestsForBranch` exist in the service interface but are not exposed as agent actions; both are intentional and not required by the PR-review workflow (audit summary "Service method `getPullRequestsForBranch` — no tool wrapper" and Inventory notes).

### Phase 1 test-coverage gaps (from Task 5 test-coverage matrix, details lines 1490-1515)

All 24 rows need execute()-path coverage but Plan 2 should scope this by priority:

- **Priority 1 — new comment actions (Phase 2 tests, but written alongside Phase 1):** full matrix (happy, 4xx, 5xx, 409, auth, live HTTP) for each of the 6 new `list_comments / get_comment / edit_comment / delete_comment / resolve_comment / reopen_comment` actions. These are greenfield and test-first per the spec §9.
- **Priority 2 — the 5 methods being fixed:** happy-path + 4xx + pagination + (where applicable) 409 stale-version, per the list in "Must-fix test additions" above.
- **Priority 3 — existing mutating methods that handle `version` with no 409 test today:** `add_reviewer`, `remove_reviewer`, `update_pr_title`, `update_pr_description`, `merge_pr`, `decline_pr` — add 409 stale-version tests (matrix column "409 (version)" all currently "—" for these, details lines 1495-1509).
- **Priority 4 — auth-header assertion test:** add one MockWebServer-backed test that asserts `Authorization: Bearer <token>` is on the recorded request, shared across the suite via a test utility (matrix column "Auth" all "—", details line 1528; audit summary line 143).
- **Priority 5 — 5xx / network-failure path for any one representative read and one representative write:** currently zero coverage across all 24 rows (details line 1527).

### Not-blocking (opportunistic — pick up if adjacent work touches the same code)

- Default `to_branch = "master"` in `create_pr` (`BitbucketPrTool.kt:99`) should query the repo's actual default branch instead of hardcoding (details §`create_pr` Diff, line 425). Design limitation, not a bug.
- `reviewers` not exposed as a param to `create_pr`; PRs are always created with no explicit reviewers (details §`create_pr` Diff, line 426). Acceptable for MVP.
- RESCOPED activity payload (`addedReviewers`, `removedReviewers`) not modelled in `BitbucketPrActivity` — acceptable for current use cases, may be needed for Phase 4 reviewer-context summaries (details §`getPullRequestActivities` Notes 2, line 1367).
- `emailAddress` not carried on `CommitData` — add if Phase 4 reviewer attribution requires it (details §`getPullRequestCommits` Notes 2, line 1459).
- Ambiguity between "PR not found" and "comment not found" 404s in `reply_to_comment` is acceptable at the current abstraction level (details §`reply_to_comment` Notes, line 185).

## Risks surfaced (beyond this project scope)

These are findings that don't map cleanly to a FIX but warrant attention beyond the PR-review workflow project:

- `getPullRequestDiff` lacks any size cap (`BitbucketServiceImpl.kt:555-575`). Current UI display callers buffer the full body via `ResponseBody.string()` with no guard. On PRs touching hundreds of files or binary-context-heavy diffs, the existing PR-detail view in production today can OOM or thrash GC. Surfaced as F-MED for our workflow, but the underlying unsafe pattern affects the pullrequest tab regardless of whether PR review ships. Recommend fixing the cap in Phase 1 even though the Phase-4 LLM-injection consumer is the most visible risk.
- Pagination gaps in `getPullRequestChanges` (100-file cap), `getPullRequestActivities` (50-activity cap), `getPullRequestCommits` (50-commit cap) silently truncate data today, **with no truncation marker or log warning visible to users**. The PR-detail tab and any automation reading activity/changes/commits on active PRs may already be rendering partial data to users without any indication (no "truncated" marker, no toast, no log warning). This is a pre-existing production defect, not a new workflow-specific risk — flagged here because fixing it in Phase 1 also fixes production.
- Schema-only test coverage across the entire Bitbucket suite (details Test coverage matrix, lines 1490-1515 — all but one row entirely "—"). The four test files exercise tool metadata only; zero execute() paths are tested. Any future regression in service wiring, parameter threading, or client-URL construction will ship silently and only surface in user bug reports. This is a systemic gap independent of PR review and worth a dedicated backfill effort regardless of whether Phase 1 proceeds.
- Sandbox live verification was not performed (audit §Sandbox, lines 137-153). Every BROKEN/FIX classification is static-analysis-only. Before Phase 1 merges its fixes, at minimum the two concrete-bug FIX findings (`set_reviewer_status` body, `add_inline_comment` REMOVED-line fileType) should be live-verified against a disposable test PR per the suggested follow-up list (lines 145-153).

## Next step

Write Plan 2 (Phases 1-2) incorporating the task deltas in this memo. Plan 2's Phase 1 scope = foundation services + all "Must-fix" and "Must-fix test additions" items; Phase 1 should-fix items land in the same phase but don't block the foundation merge. Phase 2 scope = the 6 new comment actions plus the `ai_review` meta-tool and `code-reviewer` persona tweak (spec §6.2, §6.3, §6.5).
