# Bitbucket implementation commit — execution plan

**Branch:** `fix/automation-handover-quality-tabs` (the API-audit branch — already has Jira/Bitbucket/Nexus probe artifacts; one commit per service)
**Scope:** Single Bitbucket implementation commit landing the validated swaps + Bamboo bridge + selected Phase 4 feature adds.
**Source of truth:** `docs/research/2026-05-07-bitbucket-recommendations.md` §1, §2, §3, §4, §8.
**Probe bundles:** `tools/atlassian-probe/Result_Bitbucket/bundle-{versions-only-uncompressed, full-sweep-compressed, audit-followup-compressed}.txt`.

> Read this whole plan before starting any task. Tasks are independent except where noted; dispatch can be sequential or parallel within a phase. Skip spec/quality reviewers — implementer-only per project policy. No Co-Authored-By trailer on commits.

---

## 0. Pre-flight (one-time, before any task)

1. Read these files to ground context (do not edit yet):
   - `CLAUDE.md` (project root) — module map, threading rules, ToolResult contract
   - `core/src/main/kotlin/com/workflow/orchestrator/core/bitbucket/BitbucketBranchClient.kt` — the funnel, ~1900 lines, every Bitbucket call lives here
   - `core/src/main/kotlin/com/workflow/orchestrator/core/bitbucket/PrService.kt` — service interface returning `ToolResult<T>`
   - `pullrequest/src/main/kotlin/com/workflow/orchestrator/pullrequest/service/BitbucketServiceImpl.kt` — implementation
   - `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/integration/BitbucketPrTool.kt`, `BitbucketReviewTool.kt`, `BitbucketRepoTool.kt` — agent wrappers
2. Run baseline build: `./gradlew :core:test :pullrequest:test :bamboo:test :agent:test verifyPlugin` and confirm green. Anything failing here is pre-existing — note it but don't fix it as part of this commit.
3. Architectural rule (re-state for emphasis): every NEW feature exposed to the agent must follow `core/services/Xxx.kt` → `ToolResult<T>` → impl in feature module → agent tool wrapper. New methods on `BitbucketBranchClient` are NOT enough — they must surface through the `:core` interface for the agent to call them.

Tests use `./gradlew :<module>:test`. Use `--rerun --no-build-cache` if you change a `suspend` lambda signature (per CLAUDE.md "Build-cache trap").

---

## Phase 1 — P0 bug fixes (must ship)

### Task 1.1 — `getPullRequestCommentsThreads()` returns 400 on DC 9.4

**Source:** Recommendations doc §1.1.

**Files:**
- `core/src/main/kotlin/com/workflow/orchestrator/core/bitbucket/BitbucketBranchClient.kt` — lines 1741–1752 (first variant) and line 1777 (second variant `getPullRequestCommentsThreads`)
- Test file: `core/src/test/kotlin/com/workflow/orchestrator/core/bitbucket/BitbucketBranchClientTest.kt` (or wherever existing client tests live — find with `find core/src/test -name 'Bitbucket*Test.kt'`)
- UI consumer: `pullrequest/src/main/kotlin/com/workflow/orchestrator/pullrequest/ui/CommentsTabPanel.kt` — verify it can consume the new return shape

**Change:** Remove direct `GET /pull-requests/{id}/comments` call (it returns 400 on DC 9.4 because the endpoint now requires `path` or `count=true`). Replace with activities-derived comments — the plugin already calls `/activities` (line 1261) which returns the activity timeline including every COMMENTED action with full comment bodies. Filter activities for `action == "COMMENTED"` and extract the comment objects.

**Acceptance:**
- `getPullRequestCommentsThreads(prId)` returns the same DTO shape it returned before (existing UI consumers unchanged)
- New unit test: mock `/activities` returning a payload with mixed action types (RESCOPED + COMMENTED + APPROVED), assert only COMMENTED items become comments
- Existing tests for `getPullRequestCommentsThreads` updated to mock `/activities` instead of `/comments`
- `./gradlew :core:test :pullrequest:test` green
- Run the plugin (`./gradlew :runIde`), open a PR with comments in the Comments tab, verify comments still render

### Task 1.2 — `getPullRequestMergeStrategies()` 404 fallback

**Source:** Recommendations doc §1.2.

**Files:**
- `core/src/main/kotlin/com/workflow/orchestrator/core/bitbucket/BitbucketBranchClient.kt` — lines 1542–1553 (`getPullRequestMergeStrategies`)
- Test file: same as Task 1.1's test file

**Change:** On `404` from `GET /rest/api/1.0/projects/{p}/repos/{r}/settings/pull-requests/git`, fall back to `GET /rest/api/1.0/projects/{p}/settings/pull-requests/git`. Same DTO at both URLs. Cache the resolution in-memory per (projectKey, repoSlug) for the session.

**Acceptance:**
- New unit test: mock 404 on repo URL → 200 on project URL → assert returned settings come from project URL
- New unit test: mock 200 on repo URL → assert no project URL call made (per-repo override wins)
- `./gradlew :core:test` green

---

## Phase 2 — Architectural swaps

### Task 2.1 + 2.2 — Dashboard PRs (R-SWAP-1, R-SWAP-2)

**Source:** Recommendations doc §3 R-SWAP-1, R-SWAP-2.

**Files:**
- `core/src/main/kotlin/com/workflow/orchestrator/core/bitbucket/BitbucketBranchClient.kt` — `getOpenPullRequestsAuthoredByMe()` (line 1062), `getOpenPullRequestsForReviewByMe()` (line 1103). The per-repo iteration helpers around them (look for the loop that calls `?role.1=AUTHOR` per repo) can be **deleted** rather than deprecated since the new function returns the same shape.
- Service: `core/src/main/kotlin/com/workflow/orchestrator/core/bitbucket/PrService.kt` — interface signature stays the same (returns `ToolResult<List<PullRequest>>`); only the implementation collapses
- Agent tool wrapper: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/integration/BitbucketPrTool.kt` — verify the tool still works with the new impl (no signature change)

**Change:** Replace per-repo iteration with a single call to `GET /rest/api/1.0/dashboard/pull-requests?role={ROLE}&state=OPEN&limit=10` (where ROLE is AUTHOR or REVIEWER). The response shape includes the full PR object with `toRef.repository.{slug,project.key}` so callers can still distinguish per-repo PRs.

**Acceptance:**
- Both functions return the same `List<PullRequest>` they did before, just sourced from the dashboard endpoint
- ~40 lines of per-repo iteration deleted, ~10 added (net: simpler)
- Existing tests adapted; per-repo loop tests deleted (they're testing removed code)
- New unit test: mock dashboard returning PRs across 3 different repos, assert all surface through the function with their repo metadata intact
- Manual verification: open the PR tab in `:runIde`, see the same PRs you saw before

### Task 2.3 — Branches with `details=true` (R-SWAP-3)

**Source:** Recommendations doc §3 R-SWAP-3.

**Files:**
- `core/src/main/kotlin/com/workflow/orchestrator/core/bitbucket/BitbucketBranchClient.kt` — `listBranches()` (line 641)
- Branch DTO: find with `grep -rn "data class.*Branch" core/src/main/kotlin/`. The branch DTO needs a new optional `metadata` field (Bitbucket returns `metadata: { aheadBehind: {ahead, behind}, lastCommit: {...}, ... }` when `details=true`)

**Change:** Append `&details=true` to the URL. Add `metadata: BranchMetadata?` to the Branch DTO (deserializable from Bitbucket's response). Eliminate the N follow-up calls that were fetching `aheadBehind` / `lastModified` per-branch.

**Acceptance:**
- `listBranches` URL now includes `details=true`
- Branch DTO has optional `metadata` field, deserializes correctly
- Any code that was making per-branch follow-up calls for ahead/behind/lastModified now reads from `branch.metadata` directly (find these callsites with grep)
- New unit test: mock branches response with metadata, assert metadata fields populated
- `./gradlew :core:test` green

### Task 2.4 — Blocker comments method (R-SWAP-4)

**Source:** Recommendations doc §3 R-SWAP-4.

**Files:**
- `core/src/main/kotlin/com/workflow/orchestrator/core/bitbucket/BitbucketBranchClient.kt` — add new `getBlockerComments(projectKey, repoSlug, prId)` method
- Service interface: add `suspend fun getBlockerCommentsCount(prId): ToolResult<Int>` to `PrService.kt` (or wherever blocker count is consumed)
- UI: find any code that filters all comments client-side for `severity == "BLOCKER"` and replace with the new endpoint. Likely in `pullrequest/.../ui/CommentsTabPanel.kt` or `PrDetailPanel.kt`.

**Change:** New endpoint — `GET /rest/api/1.0/projects/{p}/repos/{r}/pull-requests/{prId}/blocker-comments?count=true`. Returns `{count: Int, values: [...]}`. Use the count for badges; values for "Show me only blocker comments" filter views.

**Acceptance:**
- New `getBlockerComments` method on `BitbucketBranchClient` returning `ToolResult<BlockerCommentsResponse>`
- Existing client-side filter loops removed (find with grep `BLOCKER`)
- New unit test for the method

### Task 2.5 — `/participants` method (R-SWAP-5)

**Source:** Recommendations doc §3 R-SWAP-5.

**Files:**
- `core/src/main/kotlin/com/workflow/orchestrator/core/bitbucket/BitbucketBranchClient.kt` — add `getPullRequestParticipants(projectKey, repoSlug, prId)`
- Wherever `parsePullRequestReviewers()` lives (grep for it) — replace its parsing of `pr_get.reviewers[]` with a call to the new endpoint

**Change:** New `GET /pull-requests/{id}/participants` returns full reviewer list with `state` (UNAPPROVED/APPROVED/NEEDS_WORK), `lastReviewedCommit`, role. Use this instead of parsing the embedded `reviewers` array on `getPullRequest` responses — explicit endpoint with richer data.

**Acceptance:**
- New method returns participants with state + lastReviewedCommit
- Reviewer-display code in PR detail panel uses participants endpoint
- New unit test
- Manual verification: PR detail panel shows reviewer status correctly

---

## Phase 3 — Bamboo↔Bitbucket bridge (final scope)

### Task 3.1 — Reverse commit→PR lookup (R-ADD-5)

**Source:** Recommendations doc §2 B1, §4 R-ADD-5.

**Files:**
- `core/src/main/kotlin/com/workflow/orchestrator/core/bitbucket/BitbucketBranchClient.kt` — add `getCommitPullRequests(projectKey, repoSlug, sha)`
- Service interface: add `suspend fun getPullRequestsForCommit(repo, sha): ToolResult<List<PullRequest>>` to a `:core` service (decide where: `BitbucketCommitService.kt` is fine if it doesn't exist, or extend `PrService.kt`)
- Agent tool wrapper: extend `BitbucketRepoTool.kt` (or appropriate tool) so the agent can call this

**Change:** New endpoint — `GET /rest/api/1.0/projects/{p}/repos/{r}/commits/{sha}/pull-requests`. Returns paginated list of PRs containing the commit.

**Acceptance:**
- Method exists, returns `ToolResult<List<PullRequest>>`
- Agent tool wrapper available
- New unit test
- Verify in `:runIde` if you can — but the real verification is Phase 3.3 wiring

### Task 3.2 — Single-commit aggregate build stats (R-ADD-12)

**Source:** Recommendations doc §2 B6, §4 R-ADD-12.

**Files:**
- `core/src/main/kotlin/com/workflow/orchestrator/core/bitbucket/BitbucketBranchClient.kt` — add `getCommitBuildStats(sha)`
- DTO: `BuildStatsResponse(successful: Int, failed: Int, inProgress: Int)` somewhere in `core/model/`

**Change:** New `GET /rest/build-status/1.0/commits/stats/{sha}` returns `{successful, failed, inProgress}`. Use for cheap dashboard counters / commit-list badges.

**Acceptance:**
- Method returns `ToolResult<BuildStatsResponse>`
- DTO added
- Unit test

### Task 3.3 — Bamboo build-failure → PR-author notification wiring

**Source:** Recommendations doc §2 — "Bamboo build event → Bitbucket commit-status → reverse-lookup → PR authors notified".

**Files:**
- `bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/...` — find where build failures are detected (likely in a build-status listener / poller). Look for where `WorkflowEvent.BuildFailed` (or similar) is published.
- `core/src/main/kotlin/com/workflow/orchestrator/core/event/EventBus.kt` — already exists; this task wires a new flow through it
- New cross-module flow: `:bamboo` publishes `BuildFailed(commitSha, ...)` event → a listener in `:pullrequest` (or new in `:bamboo`?) subscribes, calls `BitbucketBranchClient.getCommitPullRequests(sha)`, and surfaces the affected PRs via notification or PR-tab annotation.

**Decision needed during implementation** — where the listener lives: in `:bamboo` (close to the source event) or `:pullrequest` (close to the consumer). Default to `:pullrequest` since the result is PR-tab-facing UX. Document the choice in the module's `CLAUDE.md`.

**Acceptance:**
- When a Bamboo build fails for commit X, the plugin shows a notification "Build failed on commit X — affects PRs #N1, #N2, #N3" linking to those PRs
- New integration test: simulate a `BuildFailed` event, mock the reverse-lookup response, assert the notification fires with the right PR ids
- Manual verification: trigger a real failed build (or mock one in `:runIde`) and observe the notification

This is the most architecturally interesting task. If you find the wiring path is messier than expected (e.g. EventBus doesn't currently carry build events), surface that in the implementation — don't hack around it.

---

## Phase 4 — Selected feature adds

### Task 4.1 — Jira link replaces manual regex (R-ADD-11)

**Source:** Recommendations doc §4 R-ADD-11.

**Files (callsites — find with grep):**
- The plugin currently does manual regex over PR titles, branch names, AND commit messages to extract Jira keys. Find these three callsites with: `grep -rn "[A-Z]+-[0-9]\{1,\}" --include='*.kt' core/ pullrequest/ jira/ agent/`. Common locations:
  - `core/src/main/kotlin/com/workflow/orchestrator/core/bitbucket/BitbucketBranchClient.kt` — possibly in commit-message-derivation helpers
  - `pullrequest/src/main/kotlin/com/workflow/orchestrator/pullrequest/...` — title regex
  - `agent/src/.../*.kt` — commit-message-gen tool

**New core method:**
- `core/src/main/kotlin/com/workflow/orchestrator/core/bitbucket/BitbucketBranchClient.kt` — add `getLinkedJiraIssues(projectKey, repoSlug, prId)` calling `GET /rest/jira/1.0/projects/{p}/repos/{r}/pull-requests/{prId}/issues`. Returns `List<JiraKeyRef(key, url)>`.

**Change:** Replace the three regex-based extraction paths with calls to `getLinkedJiraIssues(prId)`. Atlassian's Jira link plugin already extracts + validates keys against Jira; this is *less code* than what's there today.

**Acceptance:**
- New `getLinkedJiraIssues` method
- Three regex callsites replaced (verify with another grep — should find zero PR-key regex extraction in non-test code after this lands)
- Existing behavior unchanged (PRs still show their linked Jira keys; commit messages still get the right ticket prefix)
- New unit test for the method
- E2E test: PR with title "PROJ-123: Fix X" → linked-issues call returns `[{key: PROJ-123}]` → UI shows the key

### Task 4.2 — Required-builds with corrected path (R-ADD-15)

**Source:** Recommendations doc §4 R-ADD-15. Probe found v0 path `/rest/api/1.0/.../required-builds` 404s on DC 9.4; canonical path is `/rest/required-builds/latest/projects/{p}/repos/{r}/conditions`.

**Files:**
- `core/src/main/kotlin/com/workflow/orchestrator/core/bitbucket/BitbucketBranchClient.kt` — find any existing `getRequiredBuilds` / `getMergeChecks` method that uses the wrong path; if none, add new
- Surface in `pullrequest/.../ui/PrDetailPanel.kt` as a "Required builds before merge: ✓ build-X passed, ⏳ build-Y pending" indicator

**Acceptance:**
- New method calls correct path, returns required-builds conditions
- PR detail panel shows the required-builds state inline

---

## Phase 5 — Documentation updates (in same commit)

Per CLAUDE.md: "Update module `CLAUDE.md` + `docs/architecture/` (incl. `index.html`) in same commit as architecture changes."

**Files to update:**
- `core/CLAUDE.md` — note new `BitbucketBranchClient` methods added (1.1 fix + new methods from 2.4, 2.5, 3.1, 3.2, 4.1, 4.2)
- `pullrequest/CLAUDE.md` — note dashboard-PR shift, blocker-comments adoption, participants endpoint
- `bamboo/CLAUDE.md` — note new build-failure → PR-author notification flow (Task 3.3)
- `agent/CLAUDE.md` — note new agent tool capabilities (commit-PRs, commit-build-stats, linked-Jira-issues)
- `docs/architecture/index.html` — refresh the Bitbucket section if it lists endpoints; update the dashboard-PR architecture diagram if one exists

If `docs/architecture/index.html` doesn't have a Bitbucket-specific section, no edit needed.

---

## Phase 6 — Final verification + commit

1. Run full test matrix: `./gradlew :core:test :pullrequest:test :bamboo:test :agent:test --rerun --no-build-cache`
2. `./gradlew verifyPlugin buildPlugin`
3. Run `./gradlew :runIde` and **manually verify** the user-facing changes:
   - PR tab loads (R-SWAP-1/2 dashboard PRs)
   - PR detail shows reviewers (R-SWAP-5 participants)
   - Comments tab renders (Task 1.1 fix)
   - PR detail shows merge strategy (Task 1.2 fallback)
   - Trigger a fake build failure → notification shows affected PRs (Task 3.3)
   - PR title with Jira key shows the link (Task 4.1)
4. Commit message:
   ```
   feat(bitbucket): adopt validated DC 9.4 endpoints + Bamboo bridge phase 1

   Implements the recommendations from
   docs/research/2026-05-07-bitbucket-recommendations.md §8 phases 1-4
   (with Phase 4 scoped to R-ADD-11 + R-ADD-15 per user decision).

   P0 fixes:
   - getPullRequestCommentsThreads switched to activities-derived path
     (DC 9.4's /comments listing now requires path or count=true)
   - getPullRequestMergeStrategies falls back to project-level on 404

   Architectural swaps:
   - Cross-repo dashboard PRs replace per-repo iteration in
     getOpenPullRequestsAuthoredByMe / getOpenPullRequestsForReviewByMe
     (collapses N round-trips into 1)
   - Branches fetched with ?details=true (eliminates N follow-up calls
     for aheadBehind / lastModified)
   - blocker-comments?count=true replaces client-side filter
   - /participants replaces embedded reviewers parsing

   Bamboo bridge phase 1:
   - getCommitPullRequests for reverse SHA→PR lookup
   - getCommitBuildStats for cheap dashboard counters
   - Build-failure → notification listing affected PRs

   Feature adds:
   - getLinkedJiraIssues replaces 3 manual-regex callsites for Jira keys
   - Required-builds at correct /rest/required-builds/latest path

   Deferred (organic constraints): rich build status (Bamboo agents on
   this org publish only basic v1 schema), deployments (no provider
   publishing), bulk stats POST (body shape undetermined). v1
   /rest/build-status/1.0/commits/{sha} stays as the long-term
   build-status fetch.
   ```
5. `git push origin fix/automation-handover-quality-tabs`
6. Once pushed, this plan file (`docs/plans/2026-05-07-bitbucket-implementation.md`) can be deleted in a follow-up commit since the audit is complete.

**Do NOT bump `pluginVersion` as part of this commit** — release timing is a separate user-driven step (per memory `feedback_release_timing.md`).

---

## Risk register

| Risk | Mitigation |
|---|---|
| Task 1.1 — activities timeline doesn't include all comment fields the UI needs | If a field is missing, fall back to per-comment fetches via the existing `/comments/{commentId}` endpoint (which returned 200 in the full sweep) for those specific fields |
| Task 2.1/2.2 — dashboard endpoint returns PRs from repos the user can't access for some other reason | The endpoint already filters by user permission; same as per-repo. No mitigation needed unless tests prove otherwise |
| Task 3.3 — EventBus may not currently carry the build-failure event we need | Acceptable to add a new event type; document the addition in `core/CLAUDE.md` |
| Task 4.1 — `getLinkedJiraIssues` returned 1 result on the test PR; some PRs may have 0 linked issues with no Jira keys in title either | Fall back to title-regex only when the API returns empty AND the title has a clear key pattern. But measure the rate first — if it's <5%, accept the regression |

---

## Parallel-execution dispatch order

If using subagents in parallel, safe groupings:
- **Group A** (independent, no shared files): Tasks 1.1, 1.2, 2.3, 2.4, 2.5, 3.1, 3.2, 4.2 — all touch `BitbucketBranchClient.kt` but on different methods, and can be reconciled
- **Group B** (must sequence after Group A's `getCommitPullRequests` lands): Task 3.3 (depends on 3.1)
- **Group C** (independent of A/B but spans 3 modules): Task 2.1+2.2 paired (they're symmetric)
- **Group D** (independent): Task 4.1 (touches 3 callsites + adds method)

Recommended: dispatch Group A as one composite task to a single implementer (avoids merge conflicts on `BitbucketBranchClient.kt`), then dispatch B/C/D in parallel.
