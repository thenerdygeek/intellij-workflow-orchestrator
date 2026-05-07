# Write-Ops Fix Plan — Architecture & Sequencing

**Date:** 2026-05-07
**Branch:** `fix/automation-handover-quality-tabs`
**Source audit:** `2026-05-07-write-ops-ux-audit.md` + per-service docs
**Goal:** Architectural fixes (not patches) for 2 P0 + 9 P1 + 6 P2 findings, sequenced into shippable PRs.

## Guiding principles

1. **No shortcut paths to write APIs.** Every mutation goes through the canonical service. The audit's biggest pattern was "shortcut wrapper bypasses dialog-path preflight" — Jira `startWork`, Bitbucket field-update calls. We fix the pattern, not the symptoms.
2. **Encapsulate fetch-modify-write.** Bitbucket's `version` race appears in 4 places. One helper that owns "GET → mutate → PUT → retry on 409 → give up" replaces them all.
3. **Probe before fixing wire-shape bugs.** Bamboo `triggerBuild` is the textbook silent-failure case. Atlassian's docs say form-encoded body works, but we verify with a sentinel against the user's actual server before flipping the helper.
4. **DTOs hold the full server contract.** Every "we discard this server field" finding (Bamboo `isPassword`, Bitbucket `sourceRefMatcher`) gets fixed at the DTO layer, so callers can never accidentally drop information again.
5. **Single-implementer execution** (per project feedback `feedback_skip_subagent_reviews.md`) — implementer subagent only, no spec / quality reviewers. Verification is the implementer plus the test suite.

---

## Sequencing — 7 PRs

PRs 1 → 7. Each PR is independent enough to review and ship on its own; PR 1 is the foundation and lands first.

### PR 1 — Cross-cutting HTTP / mutation infra (foundation)

**Why first:** PR 2 (Bamboo P0), PR 3 (Bitbucket P0), and PR 4 (Jira P1) all consume helpers introduced here. Landing them separately keeps the per-domain PRs small.

**Changes:**

- **`:core/services/HttpFormPost.kt`** — `suspend fun postForm(path, formBody, headers): ApiResult<T>` that sets `Content-Type: application/x-www-form-urlencoded`. Used by Bamboo queue + any future Atlassian endpoint with the same convention.
- **`:core/services/HttpResponseGuards.kt`** — two helpers:
  - `isSuccess(code: Int) = code in 200..299` (replaces `200..399` in Bamboo `rerunFailedJobs`).
  - `looksLikeAuthRedirect(headers: Headers): Boolean` — returns true when an API call's response is `text/html` (which means we hit Bamboo / Bitbucket's login page instead of a real JSON response). Surfaces as a typed `ApiResult.Error.AuthRedirect`.
- **`BitbucketBranchClient.modifyPullRequest`** — encapsulated fetch-modify-write:
  ```kotlin
  suspend fun modifyPullRequest(
      repo: RepoCoords,
      prId: Long,
      mutate: suspend (PullRequestDto) -> PullRequestDto,
  ): ApiResult<PullRequestDto>
  ```
  Reads PR via `GET`, applies `mutate`, PUTs with the latest `version`. On 409 it refetches and retries once. Gives up on the second 409 with a typed `StaleVersion` error so callers can show a "PR was updated by someone else, refresh and try again" message.
- **`JiraApiClient.post()` lift** — `parseJiraErrorMessage` is currently only invoked from `transitionIssue`. Move it into the shared `post()` helper so `addComment` / `postWorklog` / future writes get the same actionable error mapping (`errors[fieldId] = "Worklog timeSpent is required"` rendered to the user).

**Tests:** unit tests for each helper. `modifyPullRequest` test uses a fake client returning 409-once-then-success.

**Reviewer hand-off note:** these helpers ship without callers — they're the foundation. PRs 2–4 will introduce the callers.

---

### PR 2 — Bamboo `triggerBuild` correctness (P0) — *gated on probe*

**Why P0:** silent variable drop in every automation-suite trigger. Returns 200, queues build, throws away `dockerTagsAsJson` overrides.

**Probe gate** (no code changes until this completes — see "Bamboo probe design" below for full payload).

**Architecture:**

- Rename `BambooApiClient.triggerBuild` → `BambooApiClient.queueBuild` and switch to `postForm`:
  ```kotlin
  suspend fun queueBuild(
      planKey: String,
      variables: Map<String, String> = emptyMap(),
      stageName: String? = null,
      executeAllStages: Boolean = stageName == null,
  ): ApiResult<BambooQueueResponse> {
      val query = buildList {
          if (stageName != null) add("stage=${urlEnc(stageName)}")
          add("executeAllStages=$executeAllStages")
      }.joinToString("&", prefix = "?")
      val formBody = variables.entries.joinToString("&") { (k, v) ->
          "bamboo.variable.${urlEnc(k)}=${urlEnc(v)}"
      }
      return postForm("/rest/api/latest/queue/$planKey$query", formBody)
  }
  ```
- Update all 5 callers identified in the audit:
  - `bamboo/.../service/BambooServiceImpl.kt:135,368` (manual + manual-stage paths)
  - `bamboo/.../run/BambooBuildRunState.kt:87`
  - `bamboo/.../ui/ManualStageDialog.kt:142`
  - `automation/.../service/QueueService.kt:296`
  - `automation/.../ui/AutomationPanel.kt:472`
- Strict-mode test: `FakeBambooApi` asserts the recorded request body is `application/x-www-form-urlencoded` with the expected `bamboo.variable.X=Y` pairs, NOT a JSON body.
- The old JSON-body code path is **deleted**, not deprecated. No backwards-compat shim.

**Verification:** the probe (see below) is the verification, not just a unit test. We trigger one real build with a sentinel `dockerTagsAsJson`, fetch its applied variables, confirm the sentinel landed. Then ship.

---

### PR 3 — Bitbucket version race + 4 mutation paths (P0)

**Why P0:** every long-lived `PrDetailPanel` is a 409 trap. User clicks "Add reviewer" 30 seconds after panel open; if anyone else touched the PR in those 30s, the version is stale and the write silently fails (or a 409 surfaces but no retry).

**Architecture:** consume `BitbucketBranchClient.modifyPullRequest` from PR 1.

```kotlin
// PrActionService.kt — addReviewer
suspend fun addReviewer(repo, prId, userSlug) =
    client.modifyPullRequest(repo, prId) { pr ->
        pr.copy(reviewers = pr.reviewers + ReviewerRef(userSlug))
    }
```

Same pattern for `removeReviewer`, `updateTitle`, `merge`. The merge case is slightly special — merge has additional preflight (`canMerge`, `vetoes`, `conflicted`) which we surface to the dialog *before* arming the merge button. Architecture decision: merge keeps a separate path that calls `getMergeStatus` first, *then* uses `modifyPullRequest`-style retry around the actual merge POST.

**Tests:** simulated 409-once-then-success for each of the 4 ops. Stale-version error surfaces as a typed result.

**Bonus fix in same PR:** `PrDetailPanel.showAddReviewerPopup` (line 1648) — replace `client.getUsers(query)` with `client.getUsers(query, projectKey, repoSlug)`. Trivial 1-line, but lives in the same module and is already in this PR's review surface.

---

### PR 4 — Jira `startWork` delegation + transition-rule audit (P1)

**Why P1:** silent workflow-rule violation. Admin marks a field required on the In Progress transition, `startWork` POSTs `fields={}`, server may 400 (visible) or accept (invisible workflow violation depending on Jira version + screen config).

**Architecture:** delete the direct POST in `JiraServiceImpl.startWork`. Route through `TicketTransitionService.executeTransition`:

```kotlin
override suspend fun startWork(ticketKey: String): ToolResult<Unit> {
    val transitionId = ticketTransitionService.findTransition(ticketKey, "In Progress")
        ?: return ToolResult.error("No 'In Progress' transition available for $ticketKey")
    return when (val r = ticketTransitionService.executeTransition(ticketKey, transitionId, fields = emptyMap())) {
        is TransitionResult.MissingFields ->
            ToolResult.error("Cannot auto-start: ${r.fieldNames.joinToString(", ")} required. Use the Sprint-tab transition dialog.")
        is TransitionResult.Success -> ToolResult.success(Unit)
        is TransitionResult.Error -> ToolResult.error(r.message)
    }
}
```

**Cross-cutting cleanup in same PR:** grep the codebase for any other Jira `transitionIssue` direct call (the audit found 1 — startWork — but the principle should be enforced project-wide). Add a unit test that asserts there's exactly one caller of `JiraApiClient.transitionIssue` (the `TicketTransitionService` itself), so future shortcut paths fail review.

**Tests:** `startWork` returns `MissingFields` error when transition has required fields; succeeds when not; surfaces server errors faithfully.

---

### PR 5 — Jira comment visibility + worklog `started` (P1)

**Why P1:** closure comments meant for "Developers" role leak to all viewers. Worklog dates set by user are silently replaced with "now."

**Architecture:**

- New DTO:
  ```kotlin
  data class JiraCommentRequest(
      val body: String,
      val visibility: CommentVisibility? = null,
  )
  data class CommentVisibility(val type: VisibilityType, val value: String)
  enum class VisibilityType { ROLE, GROUP }
  ```
- Visibility-options preflight: new `JiraService.getCommentVisibilityOptions(projectKey): ToolResult<VisibilityOptions>` that fetches `/rest/api/2/role/{projectKey}` + `/rest/api/2/group` once per session, cached in `:core`'s settings-scoped cache.
- UI: `QuickCommentPanel` + handover `JiraCommentPanel` get a Visibility dropdown. Default is "Public (all viewers)". Handover-side default could be configured later (deferred — scope creep).
- Worklog: extend `JiraService.logWork` signature to accept `started: OffsetDateTime?` and `adjustEstimate: WorklogEstimateAdjustment = AUTO`. `TimeLogPanel` passes the user-picked date through.

**Tests:** comment with role-restricted visibility serializes correctly; logWork honors user-picked `started` and surfaces it on the resulting worklog.

---

### PR 6 — Bitbucket default-reviewers matchers + AI inline pin (P1)

**Why P1:** wrong default reviewers suggested on PR creation; AI inline comments float when new commits land.

**Architecture:**

- Extend `DefaultReviewerCondition` DTO at `BitbucketBranchClient.kt:107`:
  ```kotlin
  data class DefaultReviewerCondition(
      val id: Int,
      val sourceRefMatcher: RefMatcher,
      val targetRefMatcher: RefMatcher,
      val reviewers: List<UserDto>,
      val requiredApprovals: Int,
  )
  data class RefMatcher(
      val id: String,
      val displayId: String,
      val type: RefMatcherType, // BRANCH | MODEL_BRANCH | MODEL_CATEGORY | ANY_REF | PATTERN
  )
  ```
- `getDefaultReviewersForBranch(repo, sourceBranch, targetBranch)`: filter conditions where both `sourceRefMatcher.matches(sourceBranch)` AND `targetRefMatcher.matches(targetBranch)` (replacing the current "union all conditions" logic at line 1075).
- `RefMatcher.matches()` — small pure function with branches per matcher type. Probe data has examples for at least BRANCH and MODEL_CATEGORY; PATTERN is glob-style. Tests cover all 5 types.
- AI inline comments: extend `InlineCommentRequest` to carry `diffType` + `fromHash` + `toHash`. `AiReviewTabPanel` captures these from the diff context at review-time and passes through. Use `diffType=COMMIT` with `commitHash=toHash` (latest commit at review-time) so comments stay pinned to that commit even when new commits land.

**Tests:** condition-matching for each matcher type; inline comment serializes the new fields.

---

### PR 7 — Bamboo plan-variable secrets + stage runnability + rerun-failed-jobs (P1+P2)

**Why bundled:** all three live in the same Bamboo UI surface (`StageListPanel` / `ManualStageDialog`), and reviewers naturally check this code together.

**Architecture:**

- **Plan variable model** — extend `PlanVariableData`:
  ```kotlin
  data class PlanVariableData(
      val key: String,
      val value: String,
      val variableType: VariableType, // PLAN | GLOBAL | MANUAL
      val isPassword: Boolean,
      val description: String? = null,
  )
  ```
  Source DTO `BambooPlanContextVariableDto` already carries these fields — we just stop discarding them in the mapper. `ManualStageDialog` renders password vars with `JBPasswordField` and skips logging their values.
- **Stage runnability** — derive `Stage.isNextRunnable: Boolean` from plan stage order + state. A stage is `isNextRunnable` iff it's `manual && state != IN_PROGRESS && allPriorStagesSuccessful`. `StageListPanel:45-57,161-163` enables Run only when `isNextRunnable`. Greyed-out stages show a tooltip "Run prior stages first (Stage X)".
- **Rerun Failed Jobs** — three small fixes:
  1. Confirmation dialog matching Stop/Cancel pattern.
  2. Add `X-Atlassian-Token: no-check` header on the Struts admin POST.
  3. Use `isSuccess()` from PR 1 (200..299 only). If response is HTML, surface `AuthRedirect` typed error with actionable "your session expired, re-auth in Settings" message.

**Tests:** stage runnability matrix (5 plan-state cases); rerun-failed-jobs asserts header + status check; password-var rendering doesn't echo to log.

---

## Ordering & dependency graph

```
PR 1 (infra)
  ├── PR 2 (Bamboo P0, gated on probe)
  ├── PR 3 (Bitbucket P0)
  └── PR 4 (Jira P1)
      ├── PR 5 (Jira P1 — visibility + worklog)
      └── PR 6 (Bitbucket P1 — matchers + AI pin)
PR 7 (Bamboo P1+P2 — independent, can ship anytime after PR 1)
```

PR 1 lands first. Then PRs 2–4 in any order (Bamboo gated on probe; the Bitbucket and Jira P0/P1 don't need a probe). PRs 5–7 can land in parallel after their respective parents.

---

## Bamboo probe design

The audit's claim is "code sends JSON body, server expects form-encoded body or query params, so user variables are silently dropped." Atlassian docs confirm the contract. The probe verifies it on the user's actual Bamboo (DC 10.2.14) before we change a single line of `BambooApiClient`.

### Probe sequence — 4 phases, with a confirmation gate before any write

**Phase A — read-only baseline (no confirmation needed)**

1. `GET /rest/api/latest/plan/{automationPlanKey}?expand=variableContext`
   → declared variables: shows what the form *should* look like, including which vars are passwords.
2. `GET /rest/api/latest/result/{automationPlanKey}?max-results=10&expand=results.result.variables.variable`
   → recent build results with applied variables. We surface to the user: "your last 10 suite runs all had `dockerTagsAsJson=<defaultValue>` — does that match what you typed in the dialog?" If yes, the bug is silent. If the values vary, our hypothesis is wrong and we re-investigate.

**Phase B — user confirmation gate**

After Phase A, the probe prints exactly what it would send next, and waits for explicit `Y/N` on stdin:

```
About to POST a single test build to your Bamboo:
  URL:   https://bamboo.your-host.com/rest/api/latest/queue/AUTOSUITE-PLAN
  Body:  bamboo.variable.dockerTagsAsJson=%7B%22audit-probe-2026-05-07%22%3A%22v0.0.0-sentinel%22%7D
         &executeAllStages=true
  Header: Content-Type: application/x-www-form-urlencoded
  Auth:   Bearer <redacted>

Sentinel value: {"audit-probe-2026-05-07":"v0.0.0-sentinel"}
This will queue ONE real build on your CI. Estimated cost: 1 build slot for ~N minutes.

Proceed? [y/N]:
```

The probe will not POST anything before you type `y`. Ctrl-C aborts cleanly.

**Phase C — single trigger (only after `y`)**

3. POST as previewed. Capture the build result key from the response.

**Phase D — verification (read-only)**

4. Poll `GET /rest/api/latest/result/{newResultKey}?expand=variables` every 15 s for up to 2 minutes (or until the build leaves Queued state).
5. Compare the build's applied `dockerTagsAsJson` against the sentinel value:
   - **Match** → form-encoded body works on this Bamboo. Fix shape confirmed. PR 2 unblocked.
   - **Mismatch / missing** → form-encoded body is also dropped. We escalate: try query params next (with a smaller sentinel to dodge URL length limits), or surface to the user that this Bamboo has unusual config we need to investigate further.
6. **Optional cleanup:** the probe can immediately `DELETE /rest/api/latest/queue/{newResultKey}` after capturing the variables, to free the build slot if your CI is busy. Off by default — your call.

### What I need from you before running this

1. **Plan key** of the automation suite (e.g., `AUTOSUITE-PLAN`).
2. **Sentinel value** for `dockerTagsAsJson`. I'll default to `{"audit-probe-2026-05-07":"v0.0.0-sentinel"}` — innocuous, parseable, obvious in audit logs. You can override.
3. **Other variables** to set (if the plan has required vars beyond `dockerTagsAsJson`). Phase A's output will tell us; we can iterate.
4. **Cleanup preference** — should the probe cancel the test build after capturing variables (recommended for busy CI), or let it run to completion?
5. **Bamboo URL + PAT** — same env vars the existing probe uses (`BAMBOO_URL`, `BAMBOO_TOKEN`).

### Implementation note for the probe

The existing `tools/atlassian-probe/probe_bamboo.py` is read-only and the docstring promises "never executes mutations." I'll add the write capability behind a new explicit `--write-test` flag with the confirmation gate above. The flag is opt-in; default behavior of the script stays read-only. The User-Agent is changed to drop `(read-only)` only when `--write-test` is active.

---

## Open questions for you

| # | Question | Default if no answer |
|---|---|---|
| 1 | Run all 7 PRs sequentially, or parallelize PRs 2–4 across worktrees? | Sequentially on `fix/automation-handover-quality-tabs` per `feedback_work_on_current_branch.md` |
| 2 | Bundle PRs 5+6 (both P1, different services) into one larger PR? | Keep separate (P1, different domains, different reviewers) |
| 3 | Probe inputs (above table) | I'll wait for sign-off |
| 4 | Sonnet for the mechanical PRs (5, 6, 7), Opus for the architecture-heavy ones (1, 2, 3, 4)? | Yes per `feedback_sonnet_for_small_tasks.md` |
| 5 | Verification: should each PR include a manual `runIde` smoke test before merge, or is the test suite enough? | Per CLAUDE.md "for UI changes, start the dev server" — yes for PRs touching dialog UI (PRs 5, 6, 7) |

---

## Out of scope (deliberately deferred)

- Bitbucket `severity:BLOCKER` task creation — P2, separate feature work.
- Bitbucket markdown preview via `/markup/preview` — P2, drift is small.
- Bamboo plan branch creation / Docker registry tag promotion — no UI surface today; deferred to backlog.
- Sonar — already shipped (`ac0e070e`).
- Nexus — deferred per memory `project_nexus_version_probe_findings.md`.
