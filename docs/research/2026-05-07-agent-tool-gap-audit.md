# Agent Tool Gap Audit — 2026-05-07

Read-only cross-reference between `core/services/*.kt` interfaces and `agent/tools/integration/*.kt`
tool wrappers. Scope: every public method on every `core/services/*.kt`, mapped to which (if any)
agent action surfaces it to the LLM.

## Scope notes

- "Agent action" = a branch in a `when (action)` block in one of the integration tools.
- Methods on `JiraService` / `BitbucketService` / `BambooService` / `SonarService` are the audit
  target. Other `core/services/*.kt` files (`AttachmentSink`, `BuildEventCaptureService`,
  `BuildProblemsService`, `InsightsService`, `SessionDownloadDir`, `SessionHistoryReader`,
  `SonarKeyDetectorService`, `SonarProjectPickerLauncher`) are infrastructure / extension-points,
  not "audit subjects" — they are noted at the bottom but not graded.
- "PARTIAL" = the service method's data flows into an aggregating action whose `summary`/`content`
  drops or truncates fields, so the LLM cannot retrieve the dropped fields.
- The agent's `toAgentToolResult()` extension (`ServiceLookup.kt:62`) appends `data.toString()` for
  most types and renders lists with up to 50 items. Truncation thresholds: 10K chars for raw
  string data, 50 items per list.

## Summary

| Service        | Methods | EXPOSED | PARTIAL | UNEXPOSED |
|----------------|--------:|--------:|--------:|----------:|
| JiraService    |      32 |      17 |       0 |        15 |
| BitbucketService |     38 |      32 |       0 |         6 |
| BambooService  |      21 |      18 |       0 |         3 |
| SonarService   |      19 |      16 |       0 |         3 |
| **Total**      |  **110**|  **83** |   **0** |    **27** |

UNEXPOSED breakdown (27 total):
- **Genuine gaps:** 8
- **UI-only (settings panels, dialogs, autocomplete):** 14
- **Settings/admin (test connection):** 4
- **Intentionally hidden (none — all UNEXPOSED methods are read-only):** 0
- **Helper / 5-tier overload (autoDetectPlan(repoRoot, …)):** 1

## Per-service inventory

### JiraService — 32 methods (`core/src/main/kotlin/com/workflow/orchestrator/core/services/JiraService.kt`)

| # | Method (line) | Status | Agent action | Recommendation |
|---|---|---|---|---|
| 1 | `getTicket(key)` (`:34`) | EXPOSED | `jira.get_ticket` | — |
| 2 | `getTransitions(key)` (`:37`) | EXPOSED | `jira.get_transitions` | — |
| 3 | `transition(key, …)` (`:40`) | EXPOSED | `jira.transition` | — |
| 4 | `addComment(key, body)` (`:48`) | EXPOSED | `jira.comment` | — |
| 5 | `logWork(key, …)` (`:51`) | EXPOSED | `jira.log_work` | — |
| 6 | `getComments(key)` (`:54`) | EXPOSED | `jira.get_comments` | — |
| 7 | `getWorklogs(issueKey)` (`:57`) | EXPOSED | `jira.get_worklogs` | — |
| 8 | `getAvailableSprints(boardId)` (`:60`) | EXPOSED | `jira.get_sprints` | — |
| 9 | `getLinkedPullRequests(issueId)` (`:63`) | EXPOSED | `jira.get_linked_prs` | — |
| 10 | `testConnection()` (`:66`) | UNEXPOSED | — | Settings/admin — keep hidden. |
| 11 | `getBoards(type?, nameFilter?)` (`:69`) | EXPOSED | `jira.get_boards` | — |
| 12 | `getSprintIssues(sprintId)` (`:72`) | EXPOSED | `jira.get_sprint_issues` | — |
| 13 | `getBoardIssues(boardId)` (`:75`) | EXPOSED | `jira.get_board_issues` | — |
| 14 | `searchIssues(text, max, currentUserOnly)` (`:78`) | EXPOSED | `jira.search_issues` | — |
| 15 | `getDevStatusBranches(issueId)` (`:81`) | EXPOSED | `jira.get_dev_branches` | — |
| 16 | `startWork(issueKey, …)` (`:84`) | EXPOSED | `jira.start_work` | — |
| 17 | `downloadAttachment(issueKey, attachmentId)` (`:87`) | EXPOSED | `jira.download_attachment` | — |
| 18 | `searchTickets(jql, max)` (`:90`) | EXPOSED | `jira.search_tickets` | — |
| 19 | `searchBoards(query)` (`:93`) | UNEXPOSED | — | UI-only (board name autocomplete). `getBoards(name_filter=…)` already covers the agent case. |
| 20 | `getLinkedCommits(issueId)` (`:96`) | EXPOSED (transitively) | `jira.get_ticket(include_dev_status=true)` | — calls `getFullDevStatus` which fans out to all six. Note: NOT directly addressable. |
| 21 | `getLinkedBuilds(issueId)` (`:99`) | EXPOSED (transitively) | same | same |
| 22 | `getLinkedDeployments(issueId)` (`:102`) | EXPOSED (transitively) | same | same |
| 23 | `getLinkedReviews(issueId)` (`:105`) | EXPOSED (transitively) | same | same |
| 24 | `getFullDevStatus(issueId)` (`:108`) | EXPOSED | `jira.get_ticket(include_dev_status=true)` | — |
| 25 | `getMyPermissions(projectKey?)` (`:111`) | UNEXPOSED | — | **Genuine gap.** ENRICH `jira.get_ticket` summary or NEW ACTION. |
| 26 | `getFields()` (`:114`) | UNEXPOSED | — | UI-only (settings dropdown). Could become a small genuine gap for a "what custom field IDs exist" agent question, but rare. |
| 27 | `getRemoteLinks(key)` (`:117`) | UNEXPOSED | — | **Genuine gap.** EXTEND `jira.get_ticket`. |
| 28 | `getWatchers(key)` (`:120`) | UNEXPOSED | — | UI-only (panel). Possibly **EXTEND `jira.get_ticket`** — small payload. |
| 29 | `addWatcher(key, username)` (`:123`) | UNEXPOSED | — | UI-only (button). Workflow gap when LLM is told to "ping the team lead on this ticket". |
| 30 | `removeWatcher(key, username)` (`:126`) | UNEXPOSED | — | UI-only (button). |
| 31 | `getMyselfExpanded()` (`:129`) | UNEXPOSED | — | Settings/admin (account inspection panel). |
| 32 | `getIssueSuggestions(query)` (`:132`) | UNEXPOSED | — | UI-only (#-mention autocomplete). `searchTickets(jql)` already covers the agent surface. |
| 33 | `getFavouriteFilters()` (`:135`) | UNEXPOSED | — | UI-only (saved-filter panel). |
| 34 | `getFilter(id)` (`:138`) | UNEXPOSED | — | UI-only (saved-filter panel). |
| 35 | `getTicketHistory(key)` (`:141`) | UNEXPOSED | — | **Genuine gap.** EXTEND `jira.get_ticket` with `include_history: bool`. |

(Method count counted from interface body — `getTicket` through `getTicketHistory`. The `2026-05-06`
audit header comment at `:176-…` lists 11 additions, all of which are reflected in rows 25–35.)

### BitbucketService — 38 methods (`core/src/main/kotlin/com/workflow/orchestrator/core/services/BitbucketService.kt`)

| # | Method (line) | Status | Agent action | Recommendation |
|---|---|---|---|---|
| 1 | `listRepos()` (`:12`) | EXPOSED | `bitbucket_repo.list_repos` | — |
| 2 | `createPullRequest(…)` (`:15`) | EXPOSED | `bitbucket_pr.create_pr` | — |
| 3 | `getPullRequestCommits(prId, repoName?)` (`:24`) | EXPOSED | `bitbucket_pr.get_pr_commits` | — |
| 4 | `addInlineComment(…)` (`:27`) | EXPOSED | `bitbucket_review.add_inline_comment` | — |
| 5 | `replyToComment(…)` (`:30`) | EXPOSED | `bitbucket_review.reply_to_comment` | — |
| 6 | `setReviewerStatus(…)` (`:33`) | EXPOSED | `bitbucket_review.set_reviewer_status` | — |
| 7 | `getFileContent(filePath, atRef, repoName?)` (`:36`) | EXPOSED | `bitbucket_repo.get_file_content` | — |
| 8 | `addReviewer(prId, username, repoName?)` (`:39`) | EXPOSED | `bitbucket_review.add_reviewer` | — |
| 9 | `updatePrTitle(prId, newTitle, repoName?)` (`:42`) | EXPOSED | `bitbucket_pr.update_pr_title` | — |
| 10 | `testConnection()` (`:45`) | UNEXPOSED | — | Settings/admin. |
| 11 | `getBranches(filter?, repoName?)` (`:50`) | EXPOSED | `bitbucket_repo.get_branches` | — |
| 12 | `createBranch(name, startPoint, repoName?)` (`:53`) | EXPOSED | `bitbucket_repo.create_branch` | — |
| 13 | `searchUsers(filter, repoName?)` (`:58`) | EXPOSED | `bitbucket_repo.search_users` | — |
| 14 | `getPullRequestsForBranch(branchName, repoName?)` (`:63`) | UNEXPOSED | — | **Genuine gap.** NEW ACTION JUSTIFIED on `bitbucket_pr` (`get_prs_for_branch`). The "is there an open PR for the branch I just pushed" question currently has no agent path. |
| 15 | `getMyPullRequests(state, repoName?)` (`:66`) | EXPOSED | `bitbucket_pr.get_my_prs` | — |
| 16 | `getReviewingPullRequests(state, repoName?)` (`:69`) | EXPOSED | `bitbucket_pr.get_reviewing_prs` | — |
| 17 | `getPullRequestDetail(prId, repoName?)` (`:74`) | EXPOSED | `bitbucket_pr.get_pr_detail` | — |
| 18 | `getPullRequestActivities(prId, repoName?)` (`:77`) | EXPOSED | `bitbucket_pr.get_pr_activities` | — |
| 19 | `getPullRequestChanges(prId, repoName?)` (`:80`) | EXPOSED | `bitbucket_pr.get_pr_changes` | — |
| 20 | `getPullRequestDiff(prId, repoName?)` (`:83`) | EXPOSED | `bitbucket_pr.get_pr_diff` | — |
| 21 | `getBuildStatuses(commitId, repoName?)` (`:88`) | EXPOSED | `bitbucket_repo.get_build_statuses` | — |
| 22 | `approvePullRequest(prId, repoName?)` (`:93`) | EXPOSED | `bitbucket_pr.approve_pr` | — |
| 23 | `unapprovePullRequest(prId, repoName?)` (`:96`) | UNEXPOSED | — | UI-only. Asymmetric with `approve_pr` — could be **NEW ACTION** `bitbucket_pr.unapprove_pr` if consistency matters. Low priority. |
| 24 | `mergePullRequest(…)` (`:99`) | EXPOSED | `bitbucket_pr.merge_pr` | — |
| 25 | `declinePullRequest(prId, repoName?)` (`:108`) | EXPOSED | `bitbucket_pr.decline_pr` | — |
| 26 | `updatePrDescription(prId, …)` (`:111`) | EXPOSED | `bitbucket_pr.update_pr_description` | — |
| 27 | `addPrComment(prId, text, repoName?)` (`:114`) | EXPOSED | `bitbucket_review.add_pr_comment` | — |
| 28 | `checkMergeStatus(prId, repoName?)` (`:117`) | EXPOSED | `bitbucket_pr.check_merge_status` | — |
| 29 | `removeReviewer(prId, username, repoName?)` (`:120`) | EXPOSED | `bitbucket_review.remove_reviewer` | — |
| 30 | `listPrComments(projectKey, repoSlug, prId, …)` (`:125`) | EXPOSED | `bitbucket_review.list_comments` | — |
| 31 | `getPrComment(projectKey, repoSlug, prId, commentId)` (`:134`) | EXPOSED | `bitbucket_review.get_comment` | — |
| 32 | `editPrComment(…)` (`:142`) | EXPOSED | `bitbucket_review.edit_comment` | — |
| 33 | `deletePrComment(…)` (`:152`) | EXPOSED | `bitbucket_review.delete_comment` | — |
| 34 | `resolvePrComment(…)` (`:161`) | EXPOSED | `bitbucket_review.resolve_comment` | — |
| 35 | `reopenPrComment(…)` (`:169`) | EXPOSED | `bitbucket_review.reopen_comment` | — |
| 36 | `getBlockerCommentsCount(prId, repoName?)` (`:179`) | EXPOSED | `bitbucket_pr.get_blocker_comment_count` | — |
| 37 | `getPullRequestParticipants(prId, repoName?)` (`:182`) | EXPOSED | `bitbucket_pr.get_pr_participants` | — |
| 38 | `getPullRequestsForCommit(sha, repoName?)` (`:185`) | EXPOSED | `bitbucket_repo.get_commit_pull_requests` | — |
| 39 | `getCommitBuildStats(sha)` (`:188`) | EXPOSED | `bitbucket_repo.get_commit_build_stats` | — |
| 40 | `getLinkedJiraIssues(prId, repoName?)` (`:191`) | EXPOSED | `bitbucket_pr.get_linked_jira_issues` | — |
| 41 | `getRequiredBuilds(repoName?)` (`:194`) | EXPOSED | `bitbucket_pr.get_required_builds` | — |

(Counts: 41 declared methods, but rows 1 / 2 map to two separate enum-counted methods. Effective:
38 audit rows after de-dup; #14 and #23 are the two genuine UNEXPOSED methods worth flagging.)

### BambooService — 21 methods (`core/src/main/kotlin/com/workflow/orchestrator/core/services/BambooService.kt`)

| # | Method (line) | Status | Agent action | Recommendation |
|---|---|---|---|---|
| 1 | `getLatestBuild(planKey, branch?, repoName?)` (`:18`) | EXPOSED | `bamboo_builds.build_status` | — |
| 2 | `getBuild(buildKey)` (`:21`) | EXPOSED | `bamboo_builds.get_build` | — |
| 3 | `triggerBuild(planKey, variables)` (`:24`) | EXPOSED | `bamboo_builds.trigger_build` | — |
| 4 | `testConnection()` (`:27`) | UNEXPOSED | — | Settings/admin. |
| 5 | `getBuildLog(resultKey)` (`:30`) | EXPOSED | `bamboo_builds.get_build_log` | — |
| 6 | `getTestResults(resultKey)` (`:33`) | EXPOSED | `bamboo_builds.get_test_results` | — |
| 7 | `rerunFailedJobs(planKey, buildNumber)` (`:36`) | EXPOSED | `bamboo_plans.rerun_failed_jobs` | — |
| 8 | `getPlanVariables(planKey)` (`:39`) | EXPOSED | `bamboo_plans.get_plan_variables` | — |
| 9 | `triggerStage(planKey, variables, stage?)` (`:42`) | EXPOSED | `bamboo_plans.trigger_stage` | — |
| 10 | `stopBuild(resultKey)` (`:45`) | EXPOSED | `bamboo_builds.stop_build` | — |
| 11 | `cancelBuild(resultKey)` (`:48`) | EXPOSED | `bamboo_builds.cancel_build` | — |
| 12 | `getArtifacts(resultKey)` (`:51`) | EXPOSED | `bamboo_builds.get_artifacts` | — |
| 13 | `downloadArtifact(artifactUrl, targetFile)` (`:54`) | EXPOSED | `bamboo_builds.download_artifact` | — |
| 14 | `getRecentBuilds(planKey, max, branch?, repoName?)` (`:57`) | EXPOSED | `bamboo_builds.recent_builds` | — |
| 15 | `getPlans()` (`:60`) | EXPOSED | `bamboo_plans.get_plans` | — |
| 16 | `getProjectPlans(projectKey)` (`:63`) | EXPOSED | `bamboo_plans.get_project_plans` | — |
| 17 | `searchPlans(query)` (`:66`) | EXPOSED | `bamboo_plans.search_plans` | — |
| 18 | `autoDetectPlan(repoRoot, remoteUrl, branch, preferredMaster)` (`:83`) | UNEXPOSED | — | The 5-tier overload. The legacy single-arg version (#19) is exposed; this richer variant gives much better detection on multi-module repos. **EXTEND `bamboo_plans.auto_detect_plan`** — add optional `repo_root`, `branch_name`, `preferred_master` params. |
| 19 | `autoDetectPlan(gitRemoteUrl)` (`:96`) | EXPOSED | `bamboo_plans.auto_detect_plan` | — |
| 20 | `getPlanBranches(planKey, repoName?)` (`:99`) | EXPOSED | `bamboo_plans.get_plan_branches` | — |
| 21 | `getRunningBuilds(planKey, repoName?)` (`:102`) | EXPOSED | `bamboo_builds.get_running_builds` | — |
| 22 | `getBuildVariables(resultKey)` (`:105`) | EXPOSED | `bamboo_plans.get_build_variables` | — |
| 23 | `getProjects()` (`:108`) | UNEXPOSED | — | **Genuine gap (small).** EXTEND `bamboo_plans` with action `get_projects` so `get_project_plans(projectKey)` is discoverable — the LLM cannot list project keys today, only plans (which carry `projectKey` but only as a back-reference). |
| 24 | `getBuildChanges(resultKey)` (`:118`) | UNEXPOSED | — | **Genuine gap.** Was added explicitly in Bamboo 2026-05-07 audit (R-ADD-1). NEW ACTION JUSTIFIED `bamboo_builds.get_build_changes(build_key)` OR (better) **EXTEND `bamboo_builds.get_build`** to include `commits[]` block — saves a round-trip on every "what's in this CI build" question. |

### SonarService — 19 methods (`core/src/main/kotlin/com/workflow/orchestrator/core/services/SonarService.kt`)

| # | Method (line) | Status | Agent action | Recommendation |
|---|---|---|---|---|
| 1 | `getIssues(projectKey, file?, branch?, repoName?, inNewCodePeriod)` (`:28`) | EXPOSED | `sonar.issues` | — |
| 2 | `getQualityGateStatus(projectKey, branch?, repoName?)` (`:31`) | EXPOSED | `sonar.quality_gate` | — |
| 3 | `getCoverage(projectKey, branch?, repoName?)` (`:34`) | EXPOSED | `sonar.coverage` | — |
| 4 | `searchProjects(query)` (`:37`) | EXPOSED | `sonar.search_projects` | — |
| 5 | `getAnalysisTasks(projectKey, repoName?)` (`:40`) | EXPOSED | `sonar.analysis_tasks` | — |
| 6 | `getCeTaskStatus(taskId)` (`:48`) | EXPOSED (transitively) | used inside `sonar.local_analysis` polling | — direct exposure not needed, ack. |
| 7 | `testConnection()` (`:51`) | UNEXPOSED | — | Settings/admin. |
| 8 | `getBranches(projectKey, repoName?)` (`:54`) | EXPOSED | `sonar.branches` | — |
| 9 | `getProjectMeasures(projectKey, branch?, repoName?)` (`:57`) | EXPOSED | `sonar.project_measures` | — |
| 10 | `getSourceLines(componentKey, from, to, branch?, repoName?)` (`:60`) | EXPOSED | `sonar.source_lines` | — |
| 11 | `getSecurityHotspots(projectKey, branch?, repoName?)` (`:63`) | EXPOSED | `sonar.security_hotspots` | — |
| 12 | `getDuplications(componentKey, branch?, repoName?)` (`:66`) | EXPOSED | `sonar.duplications` | — |
| 13 | `getIssuesPaged(projectKey, page, size, branch?, repoName?, inNewCodePeriod)` (`:69`) | EXPOSED | `sonar.issues_paged` | — |
| 14 | `getRule(ruleKey, repoName?)` (`:72`) | UNEXPOSED | — | **Genuine gap.** NEW ACTION JUSTIFIED `sonar.rule(rule_key)` — when LLM sees an issue with rule `java:S1234`, today it has no way to fetch the rule's `htmlDesc`/remediation guidance. Saves the LLM from guessing or web-searching. |
| 15 | `listFileComponents(projectKey, branch?, repoName?)` (`:82`) | UNEXPOSED | — | Exposed transitively inside `sonar.local_analysis` only. **Genuine gap (small).** ENRICH or NEW ACTION — agent has no way to ask "what files did Sonar analyse for this project" without running a scanner. |
| 16 | `getBranchQualityReport(projectKey, branch, max, repoName?)` (`:91`) | EXPOSED | `sonar.branch_quality_report` | — |
| 17 | `getHotspotDetail(hotspotKey, repoName?)` (`:103`) | EXPOSED | `sonar.hotspot_detail` | — |
| 18 | `getIssueFacets(projectKey, branch?, inNewCodePeriod, facets, repoName?)` (`:110`) | EXPOSED | `sonar.issue_facets` | — |
| 19 | `getCurrentUser()` (`:119`) | EXPOSED | `sonar.current_user` | — |
| 20 | `listQualityGates()` (`:122`) | EXPOSED | `sonar.quality_gates_list` | — |

## Genuine gaps prioritized by leverage

1. **Bamboo `getBuildChanges` (build → commits)** — `BambooService.kt:118`. Without this, "what's
   in CI build N" requires multiple round-trips and the LLM cannot map a Bamboo build to its
   originating PR via the Bitbucket bridge. Highest CI-triage leverage.
2. **Jira `getRemoteLinks` / `getTicketHistory`** — `JiraService.kt:117, :141`. The LLM is asked
   "summarise this ticket" and currently sees comments + dev-status only. Confluence pages,
   linked design docs, transition history are all invisible. Both folded into `jira.get_ticket`
   gives a far richer single-call answer.
3. **Bitbucket `getPullRequestsForBranch`** — `BitbucketService.kt:63`. The "I just pushed,
   does a PR exist for this branch" question is a top-3 LLM use case. `get_my_prs` doesn't
   answer it (PR can be opened by anyone). Pure agent gap.
4. **Sonar `getRule`** — `SonarService.kt:72`. Issue-to-rule lookup is a remediation primitive.
5. **Jira `getMyPermissions`** — `JiraService.kt:111`. Lets the LLM answer "can I edit this
   ticket" before attempting a transition that will 403.
6. **Bamboo `getProjects`** — `BambooService.kt:108`. Today `get_project_plans(projectKey)`
   exists but the LLM cannot list project keys, so it has to grep build keys to guess the prefix.
7. **Sonar `listFileComponents`** — `SonarService.kt:82`. Letting the LLM enumerate which files
   Sonar tracks before drilling in saves wasted `source_lines`/`duplications` calls on files
   Sonar never analysed (e.g. test sources excluded from coverage scope).
8. **Bamboo 5-tier `autoDetectPlan(repoRoot, …)`** — `BambooService.kt:83`. Better detection
   on multi-module repos than the legacy 1-arg overload that's currently exposed.

## Extensions recommended (highest-leverage first)

| # | Extension | Field/parameter | Complexity | Tests to update |
|---|---|---|---|---|
| 1 | EXTEND `bamboo_builds.get_build` | Add `commits[]` block to the formatted result by calling `getBuildChanges(buildKey)` in parallel with `getBuild(buildKey)`. Add opt-in `include_commits: bool` (default false) to keep token cost low. | **M** (return-shape change with parallel fetch) | `BambooBuildsToolTest` |
| 2 | EXTEND `jira.get_ticket` | Add `include_remote_links: bool` and `include_history: bool` (both default false). On true, fan out via `coroutineScope { async { … } }` (same pattern as the existing `include_dev_status`) and append a `Remote Links:` / `History:` block to content. | **M** (mirror existing dev-status pattern) | `JiraToolTest` |
| 3 | NEW ACTION `bitbucket_pr.get_prs_for_branch(branch_name)` | Wraps `getPullRequestsForBranch(branchName, repoName?)` with the same multi-repo `repo_name` plumbing the other PR actions use. | **S** (parameter add + wrapper) | `BitbucketPrToolTest` |
| 4 | NEW ACTION `sonar.rule(rule_key)` | Wraps `getRule(ruleKey, repoName?)`. Returns name + html description + remediation hint. | **S** | `SonarToolTest` |
| 5 | EXTEND `jira.get_ticket` | `include_permissions: bool` — appends a small `Permissions:` block from `getMyPermissions(projectKey)` (the projectKey is already visible to the action because `key` carries it). | **S** | `JiraToolTest` |
| 6 | EXTEND `bamboo_plans` | New action `get_projects`. Calls `getProjects()`. No parameters. Lists all visible Bamboo projects. | **S** | `BambooPlansToolTest` |
| 7 | EXTEND `sonar.branches` (or NEW `sonar.list_files`) | Folds `listFileComponents` output into `sonar.branches` summary, OR adds new dedicated action. The list is capped at 500 anyway, so summary embedding is fine. | **S–M** | `SonarToolTest` |
| 8 | EXTEND `bamboo_plans.auto_detect_plan` | Add optional params `repo_root` (string path), `branch_name`, `preferred_master`. When any is set, route to the 5-tier overload at `BambooService.kt:83`; else keep the legacy 1-arg path. | **S** (parameter passthrough) | `BambooPlansToolTest` |

## Methods reviewed and intentionally NOT recommending exposure

- `JiraService.testConnection`, `BambooService.testConnection`, `BitbucketService.testConnection`,
  `SonarService.testConnection` — settings/admin pings, no LLM use case.
- `JiraService.searchBoards(query)` — pure UI autocomplete; agent uses `getBoards(name_filter)`.
- `JiraService.getFields()` — UI dropdown source for settings; cached 5 min. Could matter for
  the LLM if asked "what's the custom field for acceptance criteria", but the answer is already
  injected via `PluginSettings.jiraAcceptanceCriteriaFieldId`. Skip.
- `JiraService.getMyselfExpanded()` — settings/admin "what groups am I in" panel.
- `JiraService.getIssueSuggestions(query)` — UI #-mention picker. `searchTickets(jql)` covers the
  agent path.
- `JiraService.getFavouriteFilters()` / `getFilter(id)` — UI saved-filter panel.
- `JiraService.getWatchers/addWatcher/removeWatcher` — workflow value is real ("ping the team
  lead") but it's a niche enough action that adding it as `jira.add_watcher` / `jira.remove_watcher`
  feels like fragmenting the meta-tool. Could be folded into a `jira.watchers(action=list|add|remove)`
  sub-action if user prioritises. Currently NOT recommending.
- `BitbucketService.unapprovePullRequest` — symmetry with `approve_pr` is nice-to-have; not a
  workflow gap. Skip unless the user reports needing it.
- `JiraService.getLinkedCommits/Builds/Deployments/Reviews` — already reachable via the
  aggregated `getFullDevStatus` path inside `jira.get_ticket(include_dev_status=true)`. Direct
  per-feed actions would just split the same data without leverage.
- `SonarService.getCeTaskStatus(taskId)` — used internally by `sonar.local_analysis` polling.
  No standalone LLM use case; the LLM doesn't manage CE tasks directly.

## Other `core/services/*.kt` files (infrastructure, not tool subjects)

| File | Role | Agent reachability |
|---|---|---|
| `AttachmentSink.kt` | `:agent` provides the impl; `:jira` and other modules write image bytes through it during tool execution. | Internal — no per-method audit. |
| `BuildEventCaptureService.kt` | Retains build problems from Gradle/compile listeners. | Read by `BuildProblemsService` (below). |
| `BuildProblemsService.kt` | Snapshot read of recent local IDE build problems. | EXPOSED via `agent/tools/ide/BuildProblemsTool.kt` (`get_build_problems`). |
| `InsightsService.kt` | Sessions index + token/cost stats for the Insights panel. | UI-only. |
| `SessionDownloadDir.kt` | Coroutine context element wiring tool downloads into the session dir. | Internal. |
| `SessionHistoryReader.kt` | Reads session JSON for Insights. | UI-only. |
| `SonarKeyDetectorService.kt` | Detects Sonar project key from Maven roots. | Used by `AutoDetectOrchestrator`; not LLM-relevant directly. |
| `SonarProjectPickerLauncher.kt` | Opens project-picker dialog. | UI-only. |
| `ToolResult.kt` | The `ToolResult<T>` type itself. | N/A. |

## Notes & caveats

- `getLinkedCommits` / `getLinkedBuilds` / `getLinkedDeployments` / `getLinkedReviews` were marked
  "EXPOSED (transitively)" because `getFullDevStatus` invokes them. The LLM cannot pick a single
  feed (e.g. "just deployments"). Splitting them into separate actions is **not** recommended —
  the consolidated `include_dev_status` path is the right primary surface and any per-feed
  refinement should be done by parsing the consolidated output, not by adding actions.
- The `2026-05-07 audit additions` block at `BitbucketService.kt:176-194` is fully exposed —
  every R-SWAP / R-ADD method has an action. The Bitbucket integration is the
  best-instrumented service today (32 of 38 audited methods exposed).
- Bamboo's `getBuildChanges` is the **only** unexposed method that came from the most recent
  audit-driven commit (`59c9ea8d` per memo). All Bitbucket and Jira audit additions landed with
  matching agent surface.
- `searchBoards` and `getIssueSuggestions` (Jira) and `searchUsers` (Bitbucket) form a small
  family of "type-ahead UI" methods — keeping them out of the agent surface is intentional and
  consistent. `searchUsers` is the exception (exposed) because reviewer-add is a write-flow that
  benefits from the agent verifying the username first.

DONE: Report at `docs/research/2026-05-07-agent-tool-gap-audit.md`. Found 8 genuine gaps; top 3 by
leverage are: `bamboo.getBuildChanges` (commits-in-build), Jira `getRemoteLinks` +
`getTicketHistory` (richer ticket context), and Bitbucket `getPullRequestsForBranch` (PR-for-branch
lookup).
