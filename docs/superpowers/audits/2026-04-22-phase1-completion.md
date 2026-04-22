# Phase 1 Completion Summary (2026-04-22)

## Work landed

### F-HIGH fixes (6)
1. `add_inline_comment` — `fileType` now derived from `lineType` (REMOVED → FROM; ADDED / CONTEXT → TO) via `InlineCommentAnchor.deriveFileType`. Companion on the private DTO. No longer silently mis-anchors comments on deleted lines.
2. `add_inline_comment` — `srcPath` now propagated for rename support (added to DTO + method signature).
3. `getPullRequestChanges` — pagination loop in client layer; `nextPageStart` added to response DTO; `srcPath` added to `BitbucketPrChange` + `PrChangeData` domain model; service mapping propagates srcPath. Safety cap: 20 pages / ~2000 files.
4. `getPullRequestActivities` — pagination loop in client layer; `nextPageStart` added to response DTO. Safety cap: 20 pages / ~1000 activities.
5. `getPullRequestCommits` — pagination loop in service layer; `start` param added to client. Safety cap: 20 pages / ~1000 commits.
6. `get_my_prs` / `get_reviewing_prs` — service now resolves username (ConnectionSettings first, `api.getCurrentUsername()` fallback) and passes it to the client. DC no longer returns all-repo PRs.

### F-MED fixes (3)
1. `set_reviewer_status` — `approved: Boolean` added to `ReviewerStatusRequest`; derived as `status == "APPROVED"`.
2. `merge_pr` — tool description corrected from `merge-commit, squash, ff-only` to `no-ff, squash, rebase-no-ff` (the LLM-facing contract now matches DC's actual enum).
3. `getPullRequestDiff` — size cap at 327,680 chars (~80K tokens) at CLIENT layer with truncation marker. Rationale: agent session has ~190K input-token budget; leaves room for system prompt + Jira ticket + tool definitions.

### New foundation
- `PrComment` domain model (`core/model/PrComment.kt`) — domain type used by service interface
- 6 new `BitbucketService` methods: `listPrComments`, `getPrComment`, `editPrComment`, `deletePrComment`, `resolvePrComment`, `reopenPrComment` — all returning `ToolResult<T>`
- 6 new `BitbucketBranchClient` methods + supporting DTOs (`BitbucketPrCommentResponse`, `BitbucketPrCommentList`, etc.). Edit/delete surface 409 as `STALE_VERSION` error for optimistic-lock retry semantics.
- `PrReviewFindingsStore` interface + `PrReviewFindingsStoreImpl` — disk-backed local findings store for Phase 2+ AI Review sub-tab. Atomic two-file writes, Mutex-guarded, project-scoped.

## Tests added

| Test class | Tests | Scope |
|---|---|---|
| `BitbucketBranchClientInlineAnchorTest` | 5 | fileType derivation + srcPath for renames |
| `BitbucketBranchClientPaginationTest` | 5 | changes + activities pagination |
| `BitbucketBranchClientCommentsTest` | 10 | all 6 comment methods + 409 STALE_VERSION |
| `BitbucketBranchClientReviewerStatusTest` | 3 | approved field per status |
| `BitbucketBranchClientGetMyPrsTest` | 3 | username threading regression guards |
| `BitbucketBranchClientGetReviewingPrsTest` | 3 | ditto for REVIEWER |
| `BitbucketBranchClientDiffSizeCapTest` | 2 | size cap + marker |
| `BitbucketServiceImplCommitsPaginationTest` | 5 | commits pagination client-layer guards |
| `PrCommentTest` | 2 | model shape |
| `PrReviewFindingsStoreImplTest` | 6 | atomic writes, Mutex, archive/push semantics, 20-way concurrent adds |
| `BitbucketPrToolTest` (regression guard added) | 1 | merge_pr strategy description ids |

Total new tests added: ~45.

## Final gate results

- `./gradlew :core:test`: BUILD SUCCESSFUL
- `./gradlew :pullrequest:test`: BUILD SUCCESSFUL
- `./gradlew :agent:test`: BUILD FAILED — 2998 tests completed, 1 failed (pre-existing `RunCommandStreamingWiringTest` failure only; unrelated to Phase 1). `BitbucketReviewToolTest` + `BitbucketPrToolTest` pass in isolation.
- `./gradlew verifyPlugin`: BUILD SUCCESSFUL (verified against IU-251, IU-252, IU-253; warnings are pre-existing internal API usages, not new regressions)
- `./gradlew buildPlugin`: BUILD SUCCESSFUL — `build/distributions/intellij-workflow-orchestrator-0.83.13-beta.zip` (33 MB)

## Not in scope for Phase 1 (deferred to Phase 2)

- `ai_review` meta-tool (local findings staging via agent)
- `bitbucket_review` meta-tool extensions (wire service methods to the 6 new comment actions)
- `code-reviewer` persona tweak (+ `ai_review` in tools list; Phase 6 conditional instruction)
- UI: Comments sub-tab, AI Review sub-tab, "Run AI review" button
- `PrReviewTaskBuilder` + `PrReviewSessionRegistry`
- Settings, if any

## Next step

Write Plan 3 (Phase 2: agent tools + persona tweak).
