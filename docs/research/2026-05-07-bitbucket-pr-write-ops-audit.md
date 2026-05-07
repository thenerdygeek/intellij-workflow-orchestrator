# Bitbucket PR write-ops audit — 2026-05-07

**Server:** Bitbucket Data Center 9.4.16 (probe `bundle-full-sweep.unpacked/summary.md`).
**Branch:** `fix/automation-handover-quality-tabs`. **Read-only audit.** No code modified.
**Funnel:** every PR write goes through `core/src/main/kotlin/com/workflow/orchestrator/core/bitbucket/BitbucketBranchClient.kt`.

---

## 1. Executive summary

| Bucket | Count |
|---|---|
| Write ops audited | 14 |
| CORRECT (API + UI parity) | 0 |
| API-CORRECT but UI-incomplete | 8 |
| API issues (shape / preflight / version) | 3 |
| UI parity gaps (P0/P1) | 6 |

### Top P0/P1 findings

1. **P0 — `addReviewer`/`removeReviewer` race on `version`.** `PrActionService.addReviewer` (`pullrequest/.../service/PrActionService.kt:323`) GETs the PR, mutates the reviewer list, sends `PUT /pull-requests/{id}` with `existingPr.version`. There is **no retry on 409**: between the GET and PUT another reviewer/title edit can land, server returns 409, and the user sees "Bitbucket returned 409: …" with no UX recovery. Same gap on `removeReviewer` (line 368) and `updateTitle` (line 204).
2. **P1 — Merge dialog never queries strategy *enablement* per-PR.** `MergeOptionsDialog` (`PrDetailPanel.kt:2727`) shows every strategy from the **repo settings** endpoint (which 404s on DC 9.4 and falls back to project-level — `BitbucketBranchClient.kt:1727`). The UI doesn't surface which strategy the project has marked as `default`, and the merge POST body is built from `dialog.selectedStrategyId` without checking that the chosen strategy is permitted on the *target branch* (branch-permissions plugin can disallow specific strategies). Result: user picks "rebase-no-ff", clicks Merge, gets generic 409.
3. **P1 — Decline / merge use stale `version`.** `PrDetailPanel.kt:1168` reads `currentPr?.version ?: 0` at the moment the merge button is clicked; the dialog then awaits the user, then calls merge with the cached value. If the PR is updated server-side (CI build status push, comment, reviewer state change) between click and merge, version is stale → 409 with "PR version conflict" and no auto-retry.

### Reviewer-autocomplete verdict

**PASS for create-PR. PASS for post-creation Add Reviewer. With one P1 deviation from native UI.**

- `CreatePrDialog.searchUsers()` (`pullrequest/.../ui/CreatePrDialog.kt:670`) is **autocomplete**, not free text — calls `client.getUsers(filter, projectKey, repoSlug)` after a 300 ms debounce, requires `length >= 2`, renders display name + username + email per row. Hits `GET /rest/api/1.0/users?filter=…&permission.1=REPO_READ&permission.1.projectKey=…&permission.1.repositorySlug=…` — confirmed 200 by `bundle-full-sweep.unpacked/raw/users_filter.json`. The repo-scoped `permission.1=REPO_READ` filter mirrors what the Bitbucket web UI does.
- Post-creation: `PrDetailPanel.showAddReviewerPopup()` (`PrDetailPanel.kt:1567`) uses the same `getUsers()` call but **without** the `projectKey/repoSlug` permission filter (line 1648 — `client.getUsers(query)` only). Native UI scopes by repo there too. **P1: missing permission filter on the existing-PR Add Reviewer popup** so the dropdown can suggest users who have no read access to the repo, leading to a server-side error when the user actually clicks "Add".
- Default-reviewers prefetch (`CreatePrPrefetch.prefetchOneRepo`, line 314) calls `getDefaultReviewers(projectKey, repoSlug)` and unions every reviewer across every condition — see §6 cross-cutting issue C2: it does **not** filter by source/target branch the way the web UI does.

The doc location is `docs/research/2026-05-07-bitbucket-pr-write-ops-audit.md` (this file).

---

## 2. Server context

- Bitbucket DC 9.4.16 (build 9004016). Edition `Data Center`. Source: `bundle-full-sweep.unpacked/summary.md` lines 12–15 + `raw/application_properties.json`.
- Base path for v1 REST: `/rest/api/1.0/`. Auth: `Authorization: Bearer <PAT>` (HTTP access token). All `BitbucketBranchClient` HTTP calls go through `HttpClientFactory.clientFor(ServiceType.BITBUCKET)` which adds the bearer header centrally.
- Mergeability returns rich vetoes (sample from `raw/pr_merge_check.json`): `Not all required builds are successful yet`, `Requires approvals`, `Not all required reviewers have approved yet` (group + named-quorum), `Merge vetoed: must rebase '<branch>' on to 'develop'`. The plugin already surfaces these strings (`PrDetailPanel.kt:1347`) but does not pre-flight on dialog open — see §3.
- Capabilities: `default-reviewers` plugin **installed** (probe 200 on `default_reviewers_conditions`). `required-builds` lives at `/rest/required-builds/latest/.../conditions` (200 on probe, empty for this repo). Code Insights and Jira-link plugin both installed.
- DC has no `eligible-reviewers` endpoint. The `users?filter=…&permission.1=REPO_READ&permission.1.projectKey=…&permission.1.repositorySlug=…` form is the canonical reviewer-search call (the plugin already uses this — verified on `BitbucketBranchClient.kt:1107` and probe `users_filter.json`).

---

## 3. Findings — one section per write op

### 3.1 createBranch — API CORRECT / UI N/A

**Code:** `BitbucketBranchClient.kt:906-953` (`createBranch`). Caller: `pullrequest/.../action/CreatePrLauncherImpl.kt` and the Sprint tab branch action.
**HTTP:** `POST /rest/api/1.0/projects/{p}/repos/{r}/branches` body `{"name":"<branch>","startPoint":"<sha-or-ref>"}`.
**Probe evidence:** No probe (write — read-only run); endpoint shape matches Atlassian DC docs and the code's `CreateBranchRequest` DTO (line 76).

**Correctness:**
- [pass] Method+path match server.
- [pass] Body shape matches docs (`name`, `startPoint`).
- [pass] Auth header (Bearer via `HttpClientFactory`).
- [n/a] Conditional headers — branch creation has no version semantics.
- [pass] Error handling: 401 → AUTH_FAILED with hint about Repo Write permission, 403 → forbidden message, 404 → repo not found, 409 → "Branch already exists". Good coverage.

**UI parity:** Not exposed via a dialog — branch creation is automated from Jira ticket. N/A.

**Verdict:** CORRECT. **Severity:** —. **Proposed fix:** none.

---

### 3.2 createPullRequest — API MOSTLY-CORRECT / UI MISSING PREFLIGHT

**Code:** `BitbucketBranchClient.kt:959-1007` (`createPullRequest`). Dialog: `pullrequest/.../ui/CreatePrDialog.kt:1025-1092` (`doOKAction`). Prefetch: `CreatePrPrefetch.kt`.
**HTTP:** `POST /rest/api/1.0/projects/{p}/repos/{r}/pull-requests` body `{title, description, fromRef:{id:"refs/heads/<src>"}, toRef:{id:"refs/heads/<tgt>"}, reviewers:[{user:{name:"..."}}]}`.
**Probe evidence:** Read PR shape is in `raw/pr_get.json` — `fromRef` carries `{id, displayId, latestCommit, type, repository:{slug, id, project:{key}}}`. The plugin sends a stripped-down ref containing only `id`, which Bitbucket accepts for **same-repo** PRs (server fills `repository` from the URL path).

**Correctness:**
- [pass] Method+path match server.
- [partial] Body shape — `fromRef`/`toRef` only carry `{id}` (line 398). Works for same-repo PRs (the only supported flow on this branch). **Cross-fork PRs would fail** — out of scope for the plugin today, but worth noting.
- [pass] Auth (Bearer).
- [n/a] Conditional headers — POST create.
- [pass] Error handling: 401 / 403 / 409 ("PR already exists for branch X"). Generic `else` swallows the body — see C3 cross-cutting.

**UI parity:**
- [pass] Reviewer autocomplete via `searchUsers()` → `getUsers(filter, projectKey, repoSlug)` with REPO_READ permission filter. Matches native web UI.
- [pass] Default reviewers prefetched into `CreatePrContext.repos[i].repoDefaultReviewers` (`CreatePrPrefetch.kt:314`) and pre-populate chips on dialog open (`CreatePrDialog.kt:289`).
- [pass] Target branch selector is a popup with filter (chevron extension) — `showBranchChooser()` line 578. Source is read-only (current git branch).
- [missing] **No "PR already exists" preflight.** Server returns 409 if there's already an OUTGOING OPEN PR for `fromBranch → toBranch`; the plugin has the call (`getPullRequestsForBranch`, line 1013) but does not run it on dialog open. User types title/description, clicks Create, gets generic error.
- [missing] **No conflict / mergeability preview.** Native UI shows "this PR will conflict with target" badge before submit. We don't.
- [partial] **Description Preview tab uses local Markdown converter** (`MarkdownToHtml`), not server `POST /rest/api/1.0/markup/preview` (which is read-only and probed 200 — `bundle-full-sweep.unpacked/summary.md` line 110). User's preview doesn't match what Bitbucket will render after submit. Especially relevant for Atlassian wiki-syntax customers.
- [missing] **Default reviewers ignore branch matchers.** See C2 cross-cutting issue and §3.10.

**Verdict:** API-CORRECT for the same-repo-flow / MISSING-PREFLIGHT for the duplicate-PR check and markdown render. **Severity:** P1. **Proposed fix:** call `getPullRequestsForBranch(projectKey, repoSlug, sourceBranch)` from prefetch, surface "There's already an open PR for this branch — open it?" if non-empty.

---

### 3.3 updatePullRequest — API CORRECT / VERSION RACE

**Code:** `BitbucketBranchClient.kt:1371-1409` (`updatePullRequest`). Callers: `PrActionService.updateTitle` (line 204), `updateDescription` (line 241), `addReviewer` (line 323), `removeReviewer` (line 368).
**HTTP:** `PUT /rest/api/1.0/projects/{p}/repos/{r}/pull-requests/{prId}` body `{title, description, version:N, reviewers:[{user:{name}}]}`.
**Probe evidence:** Atlassian DC docs (PUT replaces entire PR; `version` is required for optimistic locking). Server returns 409 on stale version.

**Correctness:**
- [pass] Method+path match server.
- [pass] Body shape — `BitbucketPrUpdateRequest` (line 285) carries `version`.
- [pass] 401 / 404 / 409 errors mapped. **409 message:** "PR #N version conflict — refresh and retry" — accurate but no auto-retry.
- [pass] Bearer auth.

**UI parity:**
- [partial] All four callers (`updateTitle`, `updateDescription`, `addReviewer`, `removeReviewer`) refetch the PR detail with `getPullRequestDetail` immediately before the PUT to grab the current `version`. **But the GET→PUT window is unbounded** — between them the user can keep typing, and another writer (CI build, reviewer status change, comment with severity change) can bump the version. No retry-on-409 wrapper.
- [n/a] No reviewer autocomplete on this op (the autocomplete lives on `showAddReviewerPopup`, see §3.10).

**Verdict:** API-CORRECT but RACE-PRONE. **Severity:** P0 for `addReviewer`/`removeReviewer` (most likely to race because both involve waiting for popup → server). **Proposed fix:** wrap the GET→PUT pair in a single retry loop (max 2 retries) on 409, refetching the PR each cycle, and only surface error to the UI after retries are exhausted.

---

### 3.4 mergePullRequest — API CORRECT / UI PREFLIGHT INCOMPLETE / VERSION RACE

**Code:** `BitbucketBranchClient.kt:1615-1660` (`mergePullRequest`). Service: `PrActionService.merge` (line 144). UI: `PrDetailPanel.kt:1166-1210`. Dialog: `MergeOptionsDialog` at `PrDetailPanel.kt:2724-2820`.
**HTTP:** `POST /rest/api/1.0/projects/{p}/repos/{r}/pull-requests/{prId}/merge?version={v}` body `{message?, strategyId?, deleteSourceRef?}`.
**Probe evidence:** Read endpoint sample `raw/pr_merge_check.json` shows the **GET** mergeability response (`canMerge:false`, vetoes including required builds, required approvals, required reviewers, rebase-required).

**Correctness:**
- [pass] Method+path match server. `version` correctly passed as query param (not body).
- [pass] Body shape matches DC docs (`message`, `strategyId`, `deleteSourceRef`).
- [pass] 401 / 404 / 409 mapped. 409 message claims "version conflict or merge preconditions not met" — accurate but conflated.
- [pass] Bearer auth.

**UI parity:**
- [pass] **Mergeability preflight runs on detail-panel open** — `PrDetailPanel.kt:375` and `:438` call `PrActionService.checkMergeStatus(prId)` and store in `currentMergeStatus`. The merge button is disabled when `!canMerge` and tooltip lists every veto (`updateMergeButtonState`, line 1325).
- [partial] **Strategy options are NOT validated against branch permissions.** `PrActionService.getMergeStrategies()` only calls `BitbucketBranchClient.getMergeStrategies(projectKey, repoSlug)` (line 1727) which hits `repo settings` → falls back to `project settings` (404 fallback at line 1748 — confirmed by probe `repo_settings_pull_requests_git.json` returning 404). It returns the list of **enabled strategies** but does not check `branch-permissions` plugin restrictions on the target branch.
- [missing] **No `default` strategy highlighted.** `BitbucketMergeStrategy.default` field exists in the response but the dialog combo doesn't pre-select it.
- [missing] **No conflict-status refresh between dialog open and submit.** Mergeability cached at panel-load can be 30 seconds stale by the time user clicks Merge.
- [partial] **Version is read at button-click time** (`PrDetailPanel.kt:1168` — `val version = currentPr?.version ?: 0`). Dialog then awaits user input, then calls merge. No version refetch.
- [missing] **No required-builds surfacing pre-merge.** `BitbucketBranchClient.getRequiredBuilds(p, r)` exists (audit additions §11) but is not called by the merge dialog. Native UI shows "X of Y required builds passing" inline.
- [missing] **No required-tasks check.** Bitbucket DC blocks merge on open BLOCKER-severity comments (PR tasks, see §3.13). The plugin doesn't list them.

**Verdict:** API-CORRECT / MISSING-UX. **Severity:** P1. **Proposed fix:** in `mergeButton` action, before showing dialog, call `checkMergeStatus(prId)` again and `getPullRequestDetail(prId)` to refresh `version`; pre-select the strategy where `default==true`; show a banner "X of Y required builds passing — merge will be blocked" if `required-builds` returns non-empty conditions.

---

### 3.5 declinePullRequest — API CORRECT / VERSION RACE

**Code:** `BitbucketBranchClient.kt:1796-1832`. Service: `PrActionService.decline` (line 179). UI: `PrDetailPanel.kt:1212-1241`.
**HTTP:** `POST /rest/api/1.0/.../decline?version={version}` body `""`.
**Probe evidence:** N/A (write); shape matches DC docs.

**Correctness:**
- [pass] Method+path correct, version on query string.
- [pass] Empty body with `application/json` content-type accepted by DC.
- [pass] 401 / 404 / 409 mapped.
- [pass] Bearer auth.

**UI parity:**
- [pass] Confirmation dialog before submit (`Messages.showYesNoDialog` line 1215).
- [partial] **Version cached at button-click**, same race as merge (`val version = currentPr?.version ?: 0` line 1214).
- [n/a] No reviewer/branch metadata to preflight.

**Verdict:** API-CORRECT / VERSION-RACE-MILD. **Severity:** P2. **Proposed fix:** wrap the decline call in a 1-shot 409 retry that refetches `version` and resubmits.

---

### 3.6 approvePullRequest / unapprovePullRequest — API CORRECT / UX OK

**Code:** `BitbucketBranchClient.kt:1532-1567` and `:1573-1607`. Service: `PrActionService.approve`/`unapprove`. UI: `PrDetailPanel.kt:1140-1152`.
**HTTP:** `POST /rest/api/1.0/.../approve` (no body) and `DELETE /rest/api/1.0/.../approve` (no body).
**Probe evidence:** N/A (write); approve endpoint shape per DC docs (no body, idempotent).

**Correctness:**
- [pass] Method+path correct.
- [pass] Bearer auth.
- [pass] No version required (DC participants endpoints don't take version).
- [pass] 409 → "already approved or not a reviewer" — descriptive.

**UI parity:**
- [pass] Button toggles Approve↔Unapprove based on `currentUserApproved` (line 1144). Refreshes after success.
- [missing] **No "you are not a reviewer" gate.** If the current user isn't a reviewer on the PR, clicking Approve still fires the call and gets 409. Native UI hides the button. **Severity P2.**

**Verdict:** CORRECT for the API; MISSING-UX for the eligibility check. **Severity:** P2. **Proposed fix:** when rendering the action bar, check `currentPr.reviewers.any { it.user.name == currentUsername }`; hide/disable Approve when false.

---

### 3.7 setReviewerStatus — API CORRECT / UI PARTIAL

**Code:** `BitbucketBranchClient.kt:2243-2280`. Service: `PrActionService.setNeedsWork` (line 407 — only NEEDS_WORK is wired).
**HTTP:** `PUT /rest/api/1.0/.../participants/{username}` body `{status:"NEEDS_WORK", approved:false}`.
**Probe evidence:** Activities response in `raw/pr_activities.json` shows `state` field on participants matching the values our code sends (APPROVED, NEEDS_WORK, UNAPPROVED).

**Correctness:**
- [pass] Method+path match server.
- [pass] Body sets both `status` and `approved` (DC accepts either; sending both is the safest form).
- [pass] 401 / 404 / 409 mapped.
- [pass] Bearer auth.

**UI parity:**
- [partial] Only NEEDS_WORK is exposed via the dedicated button (`needsWorkButton` PrDetailPanel.kt:1243). Approve uses `approvePullRequest` not `setReviewerStatus(APPROVED)` — both work; just inconsistent.
- [missing] **No way to clear NEEDS_WORK back to UNAPPROVED through the UI** (button text becomes "Needs Work Set" then disables — line 1260). User must use the web UI to reset.
- [missing] **`resolveCurrentUsername()`** uses fallback chain to derive the current Bitbucket username; if mis-resolved (e.g., AD username vs slug differ) the call fires for the wrong user and the server returns 404 → "PR or participant not found". This is a real risk on customers where username !== slug.

**Verdict:** API-CORRECT / MISSING-UX. **Severity:** P2. **Proposed fix:** add an UNAPPROVED reset action; resolve current user via the existing `getCurrentUsername()` (BitbucketBranchClient.kt:1182) instead of the fallback chain.

---

### 3.8 addPullRequestComment — API CORRECT / UI BASIC

**Code:** `BitbucketBranchClient.kt:1489-1526`. Service: `PrActionService.addComment` (line 276) and `BitbucketServiceImpl.addPrComment` for the agent. UI: `CommentsViewModel.postGeneralComment` (line 69).
**HTTP:** `POST /rest/api/1.0/.../{prId}/comments` body `{text:"..."}`.
**Probe evidence:** `raw/pr_comments.json` (FAIL 400 in this run because the probe used a parameterless GET on a PR with no general comments). Comment shape is well-known DC.

**Correctness:**
- [pass] Method+path correct.
- [pass] Body uses `AddCommentRequest(text)` (line 378).
- [pass] 401 / 404 mapped.
- [pass] Bearer auth.
- [missing] **No `severity` field exposed.** DC accepts `severity:"BLOCKER"|"NORMAL"` to convert the comment into a PR task (DC 7+ replaced standalone tasks with severity-tagged comments). The plugin never sends BLOCKER → cannot create blocking tasks from the agent or the UI.

**UI parity:**
- [partial] @-mention syntax works server-side (`@username`) — but the input is a plain `JBTextField`, no autocomplete on `@`.
- [missing] No markdown preview on the comment input (the create-PR description has Edit/Preview, but Comments tab doesn't).

**Verdict:** API-CORRECT / MISSING-UX. **Severity:** P2. **Proposed fix:** extend `addPullRequestComment` with optional `severity` param; surface a "Mark as blocker" toggle in the comment input.

---

### 3.9 addInlineComment — API PARTIAL / UI INCOMPLETE

**Code:** `BitbucketBranchClient.kt:2142-2191`. Anchor DTO at line 477. Service: `BitbucketServiceImpl.addInlineComment` (line 199). Used by `AiReviewViewModel.pushFinding` (line 41) and the agent tool `bitbucket_review.add_inline_comment`.
**HTTP:** `POST /rest/api/1.0/.../{prId}/comments` body `{text, anchor:{path, line, lineType, fileType, srcPath?}}`.
**Probe evidence:** Anchor field set is documented in DC REST. The plugin's `deriveFileType(lineType)` (line 485) maps REMOVED→FROM, anything-else→TO — matches the DC inline-comment rules.

**Correctness:**
- [pass] Method+path match server (same endpoint as plain comments; `anchor` field discriminates).
- [partial] Anchor shape — `path`, `line`, `lineType`, `fileType`, `srcPath` are sent. **Missing fields:** `diffType` (`COMMIT` / `EFFECTIVE` / `RANGE`) and `fromHash`/`toHash`. Without these, DC anchors the comment to the **EFFECTIVE** diff by default. If the PR receives new commits between AI-review-time and push-time, EFFECTIVE-anchored comments float to whatever line the diff hunk maps to next — which can be a different change. Native web UI sends `diffType=RANGE` with explicit `fromHash`/`toHash` for review submissions, pinning the comment to the exact commit pair the reviewer saw.
- [pass] 401 / 404 mapped.
- [pass] Bearer auth.

**UI parity:**
- [missing] **AI Review submission doesn't capture commit hashes.** `AiReviewViewModel.pushFinding` (line 41) calls `addInlineComment` with `anchorSide` but no commit pair, so all AI-review comments anchor by EFFECTIVE diff. If the PR moves under the agent's feet, the comment text refers to a line that no longer exists.
- [missing] **No multi-line range comments.** DC supports `multilineMarker` for review comments spanning a hunk; we only send single `line`.
- [missing] **No `severity:"BLOCKER"` for "this finding blocks merge".** Same as §3.8.

**Verdict:** WRONG-SHAPE for review submission durability. **Severity:** P1 for the AI-review path (because batches of stale-anchored comments after CI commits is the customer-visible failure). **Proposed fix:** extend `addInlineComment(..., diffType: String, fromHash: String?, toHash: String?)`; capture the PR's `fromRef.latestCommit`/`toRef.latestCommit` at AI-review time and pass them through. For the AI-review tab, pin to RANGE.

---

### 3.10 addReviewer / removeReviewer — API VIA UPDATE / VERSION RACE

**Code:** `PrActionService.addReviewer` (line 323) and `removeReviewer` (line 368). UI: `PrDetailPanel.showAddReviewerPopup` (line 1567), `showUserSearchPopup` (line 1588). Both go through `updatePullRequest` (PUT `/pull-requests/{id}`, see §3.3).
**HTTP:** Indirect — uses `PUT /pull-requests/{id}` with the full reviewer list replaced.
**Probe evidence:** Same as §3.3. Atlassian recommends adding individual reviewers via `POST /participants` + `{user:{name},role:"REVIEWER"}`; the bulk PUT also works but races on version.

**Correctness:**
- [partial] Functional — but suffers the same GET→PUT race as §3.3, with a *bigger* window because the popup keeps the user busy typing.
- [missing] **No use of `POST /pull-requests/{id}/participants` per-user endpoint** which is version-free and atomic. DC docs explicitly recommend it for individual reviewer changes.

**UI parity:**
- [pass] Add Reviewer dropdown is autocomplete (`showUserSearchPopup` → `client.getUsers(query)` line 1648).
- [partial] **Missing repo-permission filter** on existing-PR Add Reviewer search (line 1648 — `client.getUsers(query)` is called without `projectKey`/`repoSlug`). Native UI scopes by repo. **P1.**
- [pass] `excludeUsernames` filter strips already-selected reviewers from results (line 1654).
- [missing] **No default-reviewer prompt.** If the current PR is missing a reviewer the repo's default-reviewers conditions require, native UI flags it. We don't.

**Verdict:** WRONG-SHAPE (using bulk PUT instead of per-participant POST) / MISSING-PREFLIGHT (REPO_READ filter missing on Add Reviewer popup). **Severity:** P1. **Proposed fix:** add `addReviewer(p, r, prId, username)` that calls `POST /pull-requests/{id}/participants` with `{user:{name},role:"REVIEWER"}` instead of the bulk PUT; pass `projectKey`/`repoSlug` to `getUsers` in `showAddReviewerPopup`.

---

### 3.11 reopenPrComment / resolvePrComment — API CORRECT

**Code:** `BitbucketBranchClient.kt:2354-2401` (`resolvePrComment`, `reopenPrComment`, `setCommentState`).
**HTTP:** `PUT /rest/api/1.0/.../comments/{commentId}` body `{state:"RESOLVED"|"OPEN"}`.
**Probe evidence:** Single-comment GET shape in `raw/pr_comment_by_id.json` shows `state` field; PUT semantics per DC docs.

**Correctness:**
- [pass] Method+path match server.
- [pass] Body shape (just `state`).
- [pass] 401 / 404 / 409 mapped, with `STALE_VERSION` text on 409 — the user-visible message tells the user to refetch (good).
- [partial] **Body does not carry `version`.** DC accepts version-less state transitions because resolve/reopen are idempotent at the thread level; but if another user edits the comment text in parallel, the resolve PUT can overwrite with stale state. Low risk because state transitions don't include `text`.

**UI parity:** Comments tab (`CommentsTabPanel.kt:160`) wires both. Pass.

**Verdict:** CORRECT (with the version caveat). **Severity:** P2. **Proposed fix:** include `version` in the PUT body for state transitions to match `editPrComment` semantics.

---

### 3.12 editPrComment / deletePrComment — API CORRECT

**Code:** `BitbucketBranchClient.kt:2058-2095` (`editPrComment`) and `:2325-2348` (`deletePrComment`).
**HTTP:** `PUT /pull-requests/{prId}/comments/{commentId}` body `{text, version}` and `DELETE /pull-requests/{prId}/comments/{commentId}?version={v}`.
**Probe evidence:** Single comment GET shape in `raw/pr_comment_by_id.json` includes `version` field; PUT/DELETE shapes per DC docs.

**Correctness:**
- [pass] Method+path match server.
- [pass] Both ops carry `expected_version` (PUT in body, DELETE on query string per DC convention).
- [pass] 409 mapped to `STALE_VERSION` with retry hint.
- [pass] Bearer auth.
- [partial] **Edit response 401 maps to AUTH_FAILED — but DC also returns 401 when the user isn't allowed to edit someone else's comment.** Conflated. Should map permission errors to FORBIDDEN.

**UI parity:** Edit goes through agent tool `bitbucket_review.edit_comment` and the Comments tab (when wired). Delete via `bitbucket_review.delete_comment`. Both expose `expected_version` parameter — good.

**Verdict:** CORRECT. **Severity:** P3 (the 401/403 conflation is cosmetic). **Proposed fix:** distinguish 401 (auth invalid) from 403 (no permission to edit) in the error mapping.

---

### 3.13 PR tasks (BLOCKER comments) — NOT-EXPOSED-AS-UI

**Code:** No dedicated method. The task surface in DC 7+ is "comments with `severity:BLOCKER`"; the plugin's `addPullRequestComment` (§3.8) doesn't accept severity, so the agent and UI cannot create blocking tasks.
**HTTP:** Same `POST /pull-requests/{id}/comments` with `{text, severity:"BLOCKER"}`.
**Probe evidence:** `raw/pr_blocker_comments.json` (200 OK) shows the read endpoint exists; `BitbucketBranchClient.getBlockerComments` (audit-driven addition, §11 of `bitbucket-recommendations.md`) reads them. Write side is missing.

**Correctness:** N/A — not implemented.
**UI parity:** Native UI has a "Mark as blocker" checkbox on the comment composer; we don't.

**Verdict:** NOT-EXPOSED-AS-UI. **Severity:** P1 (BLOCKER comments are how teams gate merge; without them, the agent can review but cannot block). **Proposed fix:** thread `severity` through `addPullRequestComment` and `addInlineComment`; surface a checkbox in `CommentsTabPanel` and a `severity` param in the `bitbucket_review` tool.

---

### 3.14 Code-suggestion apply (`apply-suggestion`) — NOT-IMPLEMENTED

**Code:** None. Inventoried in `bundle-audit-followup.unpacked/summary.md` line 64 as a future write: `POST /pull-requests/{id}/comments/{cid}/apply-suggestion`.
**HTTP:** DC 8.x+ feature. Not used by the plugin.

**Verdict:** NOT-EXPOSED-AS-UI. **Severity:** P3. **Proposed fix:** none — out of scope today.

---

## 4. Cross-cutting issues

### C1 — GET→PUT version race is systemic

Every reviewer/title/description update on an existing PR goes through `getPullRequestDetail` → `updatePullRequest`. The window between is unbounded (popups, network) and there is no retry-on-409 anywhere. Recommendation: add a private `updatePullRequestWithRetry(prId, mutate: (BitbucketPrDetail) -> BitbucketPrUpdateRequest)` helper in `PrActionService` that loops on 409 up to 2 times. Affects §3.3, §3.4, §3.5, §3.10.

### C2 — Default reviewers don't honor branch matchers

`getDefaultReviewers` (`BitbucketBranchClient.kt:1060`) deserialises into `DefaultReviewerCondition` (line 107) with **only** `id`, `reviewers`, `requiredApprovals`. The DC response includes `scope`, `sourceRefMatcher`, `targetRefMatcher` per condition (sample: `raw/default_reviewers_conditions.json` lines 42–58). The plugin unions every reviewer across every condition (line 1075 — `flatMap { c -> c.reviewers }.distinctBy { u -> u.name }`), so a PR from `feature/x → develop` gets the reviewers configured for `release/* → master` too. Native UI evaluates each condition's matchers against the actual source/target branch.

**Severity:** P1. Especially noisy on this customer's repo where one repo had 5+ conditions. **Fix:** extend `DefaultReviewerCondition` to carry the matchers, evaluate them per-PR. Move evaluation into `CreatePrPrefetch.prefetchOneRepo` and apply against the target branch chosen in the dialog (re-run on target change in `CreatePrDialog.onTargetChanged`).

### C3 — Generic-error swallow on 5xx

Most write methods have an `else -> ApiResult.Error(ErrorType.SERVER_ERROR, "Bitbucket returned ${it.code}: $errorBody")`. The 5xx body is included, but lookup of well-known DC error codes (e.g., `RepositoryHookVetoedException`, `MergeConflictedException`) is not done — the user just sees the raw HTTP code. Native UI parses the JSON `errors[].message` array. **Severity:** P3. **Fix:** add a tiny `parseBitbucketError(body)` helper that returns `errors[].message` or falls back to the body text.

### C4 — Markdown preview is local

`MarkdownToHtml` is a hand-rolled converter. Bitbucket DC has its own renderer (CommonMark + Atlassian extensions, available via `POST /rest/api/1.0/markup/preview` — probed 200, `bundle-full-sweep.unpacked/raw/markup_preview.json`). PR description / comment previews can show different output post-submit. **Severity:** P3 (only matters for fancy syntax — code blocks, inline images). **Fix:** swap `MarkdownToHtml.convert` for the server-side preview when network is reachable; fall back to local on offline.

### C5 — `If-Match` not used anywhere

DC accepts `If-Match: <version>` headers as an alternative to body/query versioning on PUTs. The plugin uses query-string versioning consistently. Both work; this is informational only. No change needed.

---

## 5. Out of scope / not found

- **Bitbucket Cloud** — out of scope (server-only product). Cloud uses commit-SHA anchors for inline comments; the plugin's path+line anchor is DC-only and would need restructuring for Cloud.
- **Suggestions** — `apply-suggestion` POST is DC 8.x+ only, not implemented (§3.14).
- **Webhooks management** — read endpoint probed 401 (admin-only). Not implemented; out of scope.
- **Branch permissions plugin** — `restrictions` endpoint probed 401 (admin-only). Plugin doesn't surface "this branch is protected" pre-push. Recommendation in `bitbucket-recommendations.md` R-ADD-15 covered required-builds; full branch-permissions remains deferred.
- **Code Insights submission** — POST endpoints inventoried but not implemented. Future Sonar-PR integration; out of scope here.
- **Cross-repo (fork) PRs** — `BitbucketRef` only carries `id`, no `repository`. Acceptable for same-repo flow (the only path the plugin supports). Document and move on.

---

## Appendix — file:line reference table (for quick LLM reference)

| Op | Code call site | Dialog/UI | Service | Notes |
|---|---|---|---|---|
| createBranch | `core/.../BitbucketBranchClient.kt:906` | — | — | automated, no dialog |
| createPullRequest | `core/.../BitbucketBranchClient.kt:959` | `pullrequest/.../ui/CreatePrDialog.kt:1025-1092` | `pullrequest/.../action/CreatePrPrefetch.kt` | reviewer autocomplete: `CreatePrDialog.kt:670` |
| updatePullRequest | `core/.../BitbucketBranchClient.kt:1371` | `PrDetailPanel.kt:1497` (title edit) | `pullrequest/.../service/PrActionService.kt:204` | GET→PUT race |
| mergePullRequest | `core/.../BitbucketBranchClient.kt:1615` | `PrDetailPanel.kt:1166-1210` + `MergeOptionsDialog` `:2724` | `PrActionService.kt:144` | preflight `:375`, `:438`; veto display `:1325` |
| getMergeStatus | `core/.../BitbucketBranchClient.kt:1667` | preflight only | `PrActionService.kt:102` | called on detail open |
| getMergeStrategies | `core/.../BitbucketBranchClient.kt:1727` | strategy combo | `PrActionService.kt:123` | repo→project fallback `:1748` |
| declinePullRequest | `core/.../BitbucketBranchClient.kt:1796` | `PrDetailPanel.kt:1212` | `PrActionService.kt:179` | confirm dialog only |
| approvePullRequest | `core/.../BitbucketBranchClient.kt:1532` | `PrDetailPanel.kt:1140` | `PrActionService.kt:52` | toggles via `currentUserApproved` |
| unapprovePullRequest | `core/.../BitbucketBranchClient.kt:1573` | `PrDetailPanel.kt:1140` | `PrActionService.kt:79` | DELETE no body |
| setReviewerStatus | `core/.../BitbucketBranchClient.kt:2243` | `PrDetailPanel.kt:1243` (Needs Work btn) | `PrActionService.kt:407` | NEEDS_WORK only |
| addPullRequestComment | `core/.../BitbucketBranchClient.kt:1489` | `CommentsTabPanel.kt` + `CommentsViewModel.kt:69` | `PrActionService.kt:276` | no severity, no @-mention autocomplete |
| addInlineComment | `core/.../BitbucketBranchClient.kt:2142` | `AiReviewTabPanel.kt` + `AiReviewViewModel.kt:41` | `PrActionService.kt:298` | no diffType / commit-pinning |
| replyToComment | `core/.../BitbucketBranchClient.kt:2197` | `CommentsTabPanel.kt` | `PrActionService.kt:429` | parent.id correctly threaded |
| editPrComment | `core/.../BitbucketBranchClient.kt:2058` | agent tool only | — | version in body |
| deletePrComment | `core/.../BitbucketBranchClient.kt:2325` | agent tool only | — | version on query |
| resolvePrComment / reopenPrComment | `core/.../BitbucketBranchClient.kt:2354` | `CommentsTabPanel.kt:160` | — | state-only PUT, no version |
| addReviewer / removeReviewer | via `updatePullRequest` | `PrDetailPanel.kt:1275`, `:1567` | `PrActionService.kt:323`, `:368` | bulk PUT not per-participant POST |
| getUsers (autocomplete) | `core/.../BitbucketBranchClient.kt:1107` | `CreatePrDialog.kt:670`; `PrDetailPanel.kt:1648` | — | REPO_READ filter on create only |
| getDefaultReviewers | `core/.../BitbucketBranchClient.kt:1060` | `CreatePrPrefetch.kt:314` | — | matchers ignored (C2) |

---

End of audit.
