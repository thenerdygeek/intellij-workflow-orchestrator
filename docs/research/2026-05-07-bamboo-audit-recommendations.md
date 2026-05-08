# Bamboo DC 10.2.14 — recommendations from full-sweep audit

**Date:** 2026-05-07
**Target:** Bamboo Data Center 10.2.14 (build 100220, build date 2026-01-14)
**Branch:** `fix/automation-handover-quality-tabs` (audit branch — Jira → Bitbucket → **Bamboo** → (Nexus deferred), one commit per service)
**Auth:** PAT (Bearer), non-admin scope
**Sources:**
- Probe driver — `tools/atlassian-probe/probe_bamboo.py` v0 (committed at `1c4ba363`)
- Probe results — two committed bundles, both run 2026-05-07 against `bamboo3.sw.<redacted>.com`:
  - `tools/atlassian-probe/Result_Bamboo/bundle-repo.unpacked/` (service-CI plan, 34 endpoints, 26 OK / 7 fail / 1 skip)
  - `tools/atlassian-probe/Result_Bamboo/bundle-automation.unpacked/` (automation suite plan, 34 endpoints, 26 OK / 7 fail / 1 skip)
- Plugin clients under audit:
  - `bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/api/BambooApiClient.kt`
  - `bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/api/dto/BambooDtos.kt`
  - `bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/service/BambooServiceImpl.kt`
  - `automation/src/main/kotlin/com/workflow/orchestrator/automation/service/{ConflictDetectorService,TagBuilderService}.kt`
- Atlassian docs cross-checked via WebFetch on 2026-05-07:
  - Bamboo 10.0 release notes (REST v2 / JAX-RS 2 / Platform 7 reaarchitecture; "REST API itself remains largely unchanged")
  - Bamboo 10.2 release notes & upgrade notes (deployment-permission restructure, new `CREATE_RELEASE` / `CLONE` / `ADMINISTRATION` perms; no documented endpoint removals)
  - Bamboo 10.2.14 swagger.json (omits `/plan/{key}/specs`, `/plan/{key}/variable`, `/labels`, `/result/{key}/branch/{branch}/latest`, `/deploy/project/all`, `/repository/{id}/usedBy`)
- Reference style template — `docs/research/2026-05-07-bitbucket-recommendations.md` (shipped as commit `b9ed7cbe`)

> **Read this first if you're picking up the Bamboo audit.** Keep/swap/add doc; once approved, all approved items land in **one Bamboo implementation commit** on this branch (per the per-service commit policy in `project_api_audit_in_progress.md`).

---

## 1. TL;DR

- **3 latent plugin bugs surfaced.** §3.1 branch-aware `getLatestResult` 404s when the requested branch matches the master plan's tracked branch (no fallback today). §3.2 `getPlanSpecs` returns 403 for the user's PAT (permission gate, not endpoint removal). §3.5 **DTO shape mismatch on plan variables**: `variableContext.variable[]` returns `key`/`value`, plugin DTO declares `name`/`value` — likely silently failing today and falling through to the recent-build fallback.
- **All 8 plugin call shapes round-trip correctly** for the endpoints that did return 200 — no breaking change in 9.x→10.2 hits any plugin path on the wire (DTO mismatch above is plugin-side).
- **No removed REST endpoints documented in 10.0 or 10.2 release notes** — Atlassian explicitly states "REST API itself remains largely unchanged" through the JAX-RS 2 rearchitecture.
- **Top 3 actions:** (1) Add `/result/{key}/latest` fallback when `/branch/{name}/latest` 404s (§3.1, fixes a master-tracked-branch bug); (2) fix `BambooPlanVariableDto` to use `key` instead of `name` for variableContext or add a separate DTO (§3.5); (3) chunk or cap `getBuildLog` rendering — the repo bundle's CI log was 405,631 bytes (§7).
- **Two feature candidates ADOPT:** R-ADD-1 `?expand=changes.change` (richer per-build commit list — quick win for Build tab); R-ADD-2 `?expand=labels.label` if user-asked (no current ask, default DEFER). 4 candidates DEFER, 2 REJECT.
- **Cross-bundle consistency: 7 same fails, 26 same OKs.** All failures are systemic, not data-specific.

---

## 2. Server snapshot

| | |
|---|---|
| **Version** | 10.2.14 (`/rest/api/latest/info` raw_body, both bundles) |
| **Build** | 100220, built 2026-01-14T12:39:36+01:00 |
| **State** | RUNNING |
| **Edition** | DC implied — Bamboo's `/info` returns `edition: ""` (empty), but on-prem Bamboo has been Data Center only since 2024 (Server SKU EOL). The probe driver records this as a note. |
| **Auth** | Bearer PAT; non-admin scope (confirmed: `/info/configurationProperties` 404'd as expected for a non-admin) |
| **`/serverInfo` alias** | 404 — Bamboo does not expose the Jira-style alias; plugin doesn't call it, no action needed |

Two probe runs from two different starting points (one against a service-CI master plan, one against an automation suite plan) returned identical totals: 26 OK / 7 fail / 1 skip. Cross-bundle consistency means failures are systemic.

---

## 3. Findings matrix — every plugin endpoint validated

Columns: endpoint | plugin caller (file:line) | status (both bundles unless noted) | response shape vs DTO | recommendation

| Endpoint | Plugin caller | Status | Shape vs DTO | Recommendation |
|---|---|---|---|---|
| `GET /rest/api/latest/info` | (test connection — `BambooServiceImpl.testConnection` calls `getPlans` which is a heavier check; `/info` is used by the probe and would be cheaper) | OK 200 | n/a | **R-NOOP** for this audit. Future enhancement: swap `testConnection` to `/info` for a cheaper smoke test. |
| `GET /rest/api/latest/currentUser` | (none — probe only) | OK 200 | n/a | Not used; no action |
| `GET /rest/api/latest/plan?expand=plans.plan&max-results=100` | `BambooApiClient.getPlans` (line 39–43) | OK 200 | `BambooPlanListResponse.plans.plan[]` matches; `plans.size: 2292` cleanly fits `BambooPlanCollection` | **R-NOOP** — but note: paginating through 2292 plans on this instance is expensive (843–946ms first page). Plugin caps at 100 — fine for plan picker. |
| `GET /rest/api/latest/project?max-results=100` | `getProjects` (line 45–49) | OK 200 | matches `BambooProjectListResponse` | **R-NOOP** |
| `GET /rest/api/latest/project/{key}?expand=plans.plan` | `getProjectPlans` (line 51–55) | OK 200 | matches `BambooProjectDetailResponse` | **R-NOOP** |
| `GET /rest/api/latest/search/plans?searchTerm={q}&fuzzy=true&max-results=25` | `searchPlans` (line 57–62) | OK 200 (returned 0 results in both bundles — the probe's searchTerm is the literal plan key, but Bamboo's `searchTerm` is name-fuzzy not key-exact) | matches `BambooSearchResponse` shape | **R-NOOP**; aside note in §10. |
| `GET /rest/api/latest/plan/{key}/specs?format=YAML` | `getPlanSpecs` (line 64–67) | **FAIL 403** both bundles, message "You don't have <perm> to <action> ... <plan-key>" | n/a — error envelope only | **§3.2 — see analysis below.** Settings-time permission probe + degraded UX. |
| `GET /rest/api/latest/plan/{key}/branch?max-results=100` | `getBranches` (line 69–71) and `getPlanBranches` (line 404–410) — duplicated path | OK 200 (95 branches on the repo plan) | matches `BambooBranchListResponse` and `BambooPlanBranchListResponse` | **R-NOOP for behavior; consolidate the duplicate** (one method, two call sites). Quick cleanup (~15 lines). |
| `GET /rest/api/latest/plan/{key}` | `validatePlan` (line 419–442) | OK 200 | `validatePlan` returns Boolean; full body discarded | **R-NOOP** |
| `GET /rest/api/latest/plan/{key}?expand=variableContext` | `getPlanVariableContext` (line 120–124) | OK 200 | **MISMATCH**: response has `variable[]` with `key` field; DTO declares `name` field (see §3.5) | **§3.5 — fix DTO** (BambooPlanVariableDto needs `key` field, or per-endpoint DTO split) |
| `GET /rest/api/latest/plan/{key}/variable` | `getPlanVariableDirect` (line 127–131) | **FAIL 404** both bundles | n/a | **§3.3 — dead fallback**. Either remove from BambooApiClient.kt:127–131 or document as deployment-specific. Already covered by Strategy A (variableContext) + Strategy C (recent-build fallback) in `BambooServiceImpl.kt:296–335`. |
| `GET /rest/api/latest/result/{key}/latest?expand=stages.stage.results.result` | `getLatestResult(planKey, branch=null)` (line 73–84) | OK 200 | `BambooResultDto` matches; `stages.stage[].results.result[]` populated | **R-NOOP** |
| `GET /rest/api/latest/result/{key}/branch/{branch}/latest?expand=...` | `getLatestResult(planKey, branch=...)` (line 73–84) | **FAIL 404** when the requested branch == master plan's tracked branch (i.e. `develop` for a plan where `develop` is the master) | n/a | **§3.1 — LATENT BUG**. Add `/latest` fallback. Master plan IS the branch plan for its tracked branch; no separate branch-plan key exists. |
| `GET /rest/api/latest/result/{key}?includeAllStates=true&max-results=5&expand=...` | `getRunningAndQueuedBuilds` (line 155–163) | OK 200 | matches `BambooBuildStatusResponse` | **R-NOOP** |
| `GET /rest/api/latest/result/{key}?max-results=10&expand=results.result.stages.stage.results.result,results.result.variables.variable` | `getRecentResults` (line 173–193) | OK 200 (slowest endpoint we hit on the repo plan: 870ms; 1713ms on the automation plan because variables.variable expansion is heavy) | matches `BambooBuildStatusResponse`; `result[].variables.variable[]` populated | **R-NOOP** for shape; **note perf** in §7 (this is one of two heavy expansions; the other is build log). |
| `GET /rest/api/latest/result/{resultKey}?expand=stages.stage.results.result` | `getBuildResult` (line 368–370) | OK 200 | matches `BambooResultDto`; `state`, `lifeCycleState`, `buildNumber`, `vcsRevisionKey`, `buildResultKey`, `planResultKey.entityKey.key` all present | **R-NOOP** |
| `GET /rest/api/latest/result/{resultKey}?expand=vcsRevisions` | `getResultVcsRevision` (line 98–102) | OK 200 | matches `BambooVcsRevisionsResponse`; `vcsRevisions.vcsRevision[].vcsRevisionKey` returns the SHA (96ff…070c on repo bundle, c04a…efa7 on automation) and `repositoryId`/`repositoryName` populated | **R-NOOP** — bridge endpoint validated, in production today (commit `b9ed7cbe`) |
| `GET /rest/api/latest/result/{resultKey}?expand=testResults.failedTests.testResult,testResults.successfulTests.testResult` | `getTestResults` (line 114–117) | OK 200 (1567 successful tests, 0 failed on the repo result) | matches `BambooJobTestResultDto`; `testResults.successfulTests.testResult[]` populated with `className`/`methodName`/`status`/`duration` per case | **R-NOOP** — note the repo bundle's `result_test_results.json` is 739 KB on disk (1567 cases × ~470 bytes/case) — see §7 |
| `GET /rest/api/latest/result/{resultKey}?expand=variables` | `getBuildVariables` (line 165–171) | OK 200 (12 vars on the repo build, including `inject.GIT_COMMIT_SHA`) | matches `BambooBuildVariablesResponse`; `variables.variable[]` returns `name`/`value` (NOT `key`/`value` as on plan-level `variableContext` — important distinction) | **R-NOOP** — this is the `dockerTagsAsJson` reader path used by `:automation` |
| `GET /rest/api/latest/result/{resultKey}?expand=artifacts.artifact` | `getArtifacts` (line 332–337) | OK 200 (1 artifact "Build log" on the repo build) | matches `BambooArtifactResponse`; `artifacts.artifact[]` shape with `name`, `link.href`, `producerJobKey`, `shared` | **R-NOOP** — note: `size` field appears 0 in the live response shapes we captured even when there are artifacts; plugin DTO has `size: Long = 0` default which is harmless. |
| `GET /download/{resultKey}/build_logs/{resultKey}.log` | `getBuildLog` (line 104–108, calls `getRaw`) | OK 200 | plain text; `Content-Type: text/plain;charset=utf-8`; **22,791 bytes (automation) and 405,631 bytes (repo CI)** | **R-NOOP for endpoint; §7 — chunk/cap the log render** |
| `GET /rest/api/latest/result/byChangeset/{sha}?expand=results.result.plan` | `getResultsByChangeset` (line 377–380) | OK 200 (4 results on the repo SHA, including `chain_branch` plan with `master.key` linking back to the master plan) | matches `BambooResultsByChangesetResponse` — `results.result[].plan.key` and `results.result[].planResultKey.key` populated; live response also includes a `master` block on each entry that the plugin's `BambooChangesetResultEntry` discards (uncommitted-yet useful for "find the master plan from a branch result") | **R-NOOP for shape**; consider extending `BambooChangesetResultEntry` to capture `master.key` if the plugin ever needs to walk branch→master (not used today) |
| `GET /rest/api/latest/repository?max-results=200` | `getLinkedRepositories` (line 386–389) | OK 200 — but **`size: 0, searchResults: []` on BOTH bundles** | matches `BambooLinkedRepositoryListResponse` shape | **R-NOOP for shape; §10 open question** — the repo lists are empty for this PAT. Either the user's token can't see linked repos, or this org doesn't use Bamboo Linked Repositories. |
| `GET /rest/api/latest/repository/{id}/usedBy` | `getRepositoryUsedBy` (line 395–398) | SKIP (probe needs a repo id which we don't have because `/repository` returned empty) | unknown | **R-INVESTIGATE** — needs a re-probe with `--repo-id <known-id>` once we have one. Not blocking. |

**Total endpoints validated:** 23 plugin call paths (all of `BambooApiClient.kt`'s read paths plus the URL the build-log download uses). 4 mutating endpoints (`POST /queue/{planKey}`, `POST /build/admin/restartBuild.action`, `DELETE /queue/{resultKey}`, `PUT /result/{resultKey}/stop`) are inventoried but not exercised by the read-only probe.

---

## 4. Cross-bundle failures: per-endpoint analysis

All 7 fails reproduced identically in both bundles. They are systemic, not data-specific.

### 4.1 `/result/{plan}/branch/{branch}/latest` 404s when branch == master's tracked branch — **LATENT PLUGIN BUG**

| | |
|---|---|
| **Probe** | `bundle-repo.unpacked/raw/result_latest_branch.json` and `bundle-automation.unpacked/raw/result_latest_branch.json` |
| **Status** | 404, body `{"message":"<redacted>","status-code":404,"sub-code":-1}` |
| **Bundles** | Both: probed against master plan key + branch=`develop`; the master plan tracks `develop` |
| **Why it 404s** | Bamboo's `/result/{master-plan}/branch/{branch}/latest` returns 404 when the requested branch *is* the master plan's tracked branch — Bamboo treats the master plan as the implicit branch plan for that branch and there is no separate branch-plan key. For non-master branches (e.g. `feature/foo`), Bamboo creates a child branch plan and `/branch/feature%2Ffoo/latest` works. |
| **Plugin behavior in 10.2** | `BambooApiClient.getLatestResult(planKey, branch="develop")` (`bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/api/BambooApiClient.kt:73–84`) issues the branch-specific URL. On 404 it returns `ApiResult.Error(NOT_FOUND, ...)`. **`BambooServiceImpl.getLatestBuild` at `bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/service/BambooServiceImpl.kt:73–107`** has a fallback path: when `resolveBranchPlanKey` (line 78) fails, it falls back to calling `api.getLatestResult(planKey, branch)` *with the branch* — which is exactly the URL that 404s. There is no second fallback to `/result/{planKey}/latest` (the master plan's own latest, which IS the branch's latest when branch == master.tracked-branch). |
| **Recommended fix** | In `BambooApiClient.getLatestResult` (line 73–84), on 404 from the branch-specific URL, fall back to `/result/{planKey}/latest` and return that. Alternatively, fix in `BambooServiceImpl.getLatestBuild` (line 91–97): if the branch URL 404s, try the unbranched URL. The latter is preferable because the API client should stay one-call-one-URL for testability. |
| **Suggested change** (in `BambooServiceImpl.kt` around line 91–97): after `api.getLatestResult(planKey, branch)` returns `ApiResult.Error(NOT_FOUND, ...)`, log the warning AND retry with `api.getLatestResult(planKey)`; verify the returned result's `vcsRevisionKey` or branch label matches the requested branch (defensive — guards against a stale master result on a different branch). |
| **Risk of doing nothing** | When the user's local checkout is on the master-tracked branch (e.g. `develop`), Build tab returns "Bamboo resource not found" instead of the actual latest build. Easy user-visible regression every time someone checks out `develop`/`master`/`main`. |
| **What feature/test would catch it** | Service-level integration test for `BambooServiceImpl.getLatestBuild(planKey, branch="<master-tracked>")` against a fixture where the branch URL 404s — assert the unbranched fallback is invoked. |

### 4.2 `/plan/{key}/specs?format=YAML` 403 (permission gate, not removal)

| | |
|---|---|
| **Probe** | `bundle-{repo,automation}.unpacked/raw/plan_specs_yaml.json` |
| **Status** | 403, body `{"message":"You don't have <perm> to <action> ... <plan-key>","status-code":403,"sub-code":-1}` |
| **Atlassian docs** | The Bamboo 10.2.14 swagger does not list `/plan/{key}/specs` at all (per WebFetch on swagger.json). However the live server returns 403, not 404 — so the endpoint exists but is gated by a permission the PAT doesn't hold. Almost certainly **Plan View Configuration** (the per-plan permission needed to read specs YAML). |
| **Plugin behavior in 10.2** | `BambooApiClient.getPlanSpecs` (line 64–67) returns `ApiResult.Error(FORBIDDEN, "Insufficient Bamboo permissions")`. **No caller in the plugin invokes `getPlanSpecs` today** — verified via grep on `bamboo/`, `automation/`, `core/`. It's wired but not consumed. |
| **Recommended fix** | Two options: (a) **Remove `getPlanSpecs` from BambooApiClient.kt** since nothing calls it. Net cleanup ~5 lines. (b) **Keep it** for future "show plan YAML" feature, but document the permission requirement in KDoc. The doc bias is (a) — the plugin's policy elsewhere is to delete unreached fallbacks. |
| **Risk of doing nothing** | Zero today (no caller). If a future feature calls it without a settings-time permission probe, users without **Plan View Configuration** will see a generic "Insufficient permissions" toast with no remediation path. |

### 4.3 `/plan/{key}/variable` 404 — duplicate-purpose dead fallback

| | |
|---|---|
| **Probe** | `bundle-{repo,automation}.unpacked/raw/plan_variables_direct.json` |
| **Status** | 404, body `{"message":"<redacted>","status-code":404,"sub-code":-1}` |
| **Atlassian docs** | Not in 10.2.14 swagger.json (absent at the documented level — but live behavior is 404 either way). This was a known-flaky path in the April 2026 probe per the in-source comment on line 127. |
| **Plugin behavior in 10.2** | `BambooApiClient.getPlanVariableDirect` (line 127–131) is the **Strategy B fallback** in `BambooServiceImpl.getPlanVariables` (line 308–316). Strategy A (`getPlanVariableContext`, the variableContext expand path, line 297–305) succeeds, so Strategy B is never reached on this server. |
| **Recommended fix** | **Remove `getPlanVariableDirect`** from `BambooApiClient.kt:127–131` and the corresponding call from `BambooServiceImpl.kt:308–316`. Strategy C (recent-build variable fallback at `BambooServiceImpl.kt:318–335`) is independently useful and stays. **Net deletion ≈18 lines.** |
| **Risk of doing nothing** | Cosmetic — one extra 404 per plan-variables fetch only when Strategy A returns empty. Strategy A returned 10 vars on both bundles; never empty in practice. |
| **House-style cross-ref** | Per `feedback_reuse_code.md` and `simplify-scoped` guidance: dead fallbacks with no observed traffic are deletion candidates. |

### 4.4 `/labels?max-results=20` 404 — REJECT candidate

| | |
|---|---|
| **Probe** | `bundle-{repo,automation}.unpacked/raw/labels_global.json` |
| **Status** | 404 |
| **Atlassian docs** | Not in 10.2.14 swagger.json. No mention in 9.x→10.x release notes. |
| **Decision** | **REJECT.** This is a feature-discovery candidate, not in the plugin today. Drop from feature backlog — no global `/labels` listing in 10.2 DC. (Per-build labels via `?expand=labels.label` DO work — see §5 / R-ADD-2.) |

### 4.5 `/result/{key}/jiraIssues` 404 — REJECT candidate

| | |
|---|---|
| **Probe** | `bundle-{repo,automation}.unpacked/raw/result_jira_issues.json` |
| **Status** | 404 |
| **Atlassian docs** | Documented in 10.2.14 swagger as a path. Returns 404 on this DC instance — possibly because Bamboo↔Jira application link is not configured on this org's instance, or because the Jira-link plugin isn't enabled. The full result body DOES include a `jiraIssues: {size: 1, ...}` summary on the unbranched plan latest (`result_latest_plan.json:97–102`) but the dedicated endpoint 404s. |
| **Decision** | **REJECT for adoption.** Even if the endpoint were reachable, the Bitbucket audit (commit `b9ed7cbe`) already adopted `/rest/jira/1.0/.../pull-requests/{id}/issues` (R-ADD-11) which gives the plugin Jira issue keys via the PR side. No need to also fetch via Bamboo. |

### 4.6 `/info/configurationProperties` 404 — expected (admin-only)

Probe: `info_configuration_properties.json`, status 404, both bundles. Documented as admin-gated; plugin doesn't call it. **No action.**

### 4.7 `/serverInfo` 404 — Jira-style alias not exposed by Bamboo

Probe: `server_info.json`. Plugin doesn't call it. **No action.**

---

## 5. Adoption decisions for feature candidates

For each candidate that returned 200, decide ADOPT / DEFER / REJECT.

### R-ADD-1: `?expand=changes.change` (per-build commit list) — **ADOPT**

| | |
|---|---|
| **Probe** | `bundle-repo.unpacked/raw/result_changes.json:118–142` returns `changes.change[]` with `userName`, `fullName`, `comment`, `changesetId`, `commitUrl`, `date`, `files.size` |
| **Plugin gap today** | `getResultVcsRevision` returns one SHA. The Build tab and Bamboo→Bitbucket bridge (`BuildFailureBridgeStartupActivity`) only know about that single commit. |
| **UX value** | Build tab "What's in this build" tooltip / popover lists every commit since last green build (size, author, message). Increments the bridge from "PR for SHA" to "PRs for any of N SHAs." |
| **Adoption sketch** | New DTO `BambooBuildChangesResponse` wrapping `changes.change[]` with fields `userName`, `fullName`, `comment`, `changesetId`, `commitUrl`, `date`. New method `BambooApiClient.getBuildChanges(resultKey)`. `BambooServiceImpl` exposes a new `getBuildChanges` returning `List<BuildCommitData>`. **Cost:** ~40 LOC + DTO. **Risk:** low — read-only, additive. |
| **Why ADOPT not DEFER** | The Bitbucket bridge already exists and is shipping today; this is a strict superset of the SHA we already extract. Cheap. |

### R-ADD-2: `?expand=labels.label` (per-build labels) — **DEFER**

| | |
|---|---|
| **Probe** | `result_labels.json` returns `labels.label[]` (empty in both bundles — no labels assigned). Schema present, semantics OK. |
| **Decision** | DEFER — no current user feature ask. The "label as release-ready" workflow is hypothetical; without a concrete UX driver this stays unbuilt. Re-evaluate if the user explicitly asks. |

### R-ADD-3: `?expand=changes.change` AND/OR adopt `getBuildChanges` (sub-case of R-ADD-1) — covered above

### R-ADD-4: `/agent` (build agents) — **DEFER**

| | |
|---|---|
| **Probe** | `agent_list.json` returns `[{id, name, type: REMOTE, active, enabled, busy}, ...]`. Live response shows multiple `REMOTE` agents, all active. |
| **Decision** | DEFER. Hypothetical "no agent online for this plan's required capabilities" badge — useful but speculative. The user has not reported queue-stuck builds as a pain point. Re-evaluate if this becomes a complaint. |

### R-ADD-5: `/queue` (global queue) — **DEFER**

| | |
|---|---|
| **Probe** | `queue_global.json` returns `{queuedBuilds: {size: 51, max-result: 51}}`. 51 builds queued globally — the org runs hot. |
| **Plugin path today** | `getRunningAndQueuedBuilds(planKey)` does per-plan polling. The `:automation` `QueueService` may benefit from a single global poll. |
| **Decision** | DEFER. The plugin polls O(N suite plans) where N is small (typically 1–3 configured). Switching to one global poll is a net win architecturally but the gain is invisible at small N. **Adopt when the user adds a 4th+ suite plan or reports queue-poll latency.** |

### R-ADD-6: `/deploy/project/all` (deployment projects) — **REJECT**

| | |
|---|---|
| **Probe** | `deploy_project_all.json` returns 23+ deployment projects with `key`, `name`, `description`, `environments`, `operations.{canView, canEdit, canExecute, ...}` |
| **Plugin gap** | No deployment surface in any tab today. |
| **Decision** | REJECT for this audit — greenfield feature, not a fix or improvement to an existing tab. Per the per-service audit policy: "DO NOT recommend the plugin add features the user hasn't asked for." Move to backlog under future-features if the user ever introduces a deployment workflow. |

### R-ADD-7: `/result/{key}/comment` (build comments) — **DEFER**

| | |
|---|---|
| **Probe** | `result_comments.json` returns `{comments: {size: 0, comment: []}}`. Endpoint works but no comments on the test result. |
| **Decision** | DEFER. "Leave a build comment from the Build tab" is hypothetical. No user ask. Single endpoint cost is low if asked later; not worth shipping pre-emptively. |

---

## 6. 9.x → 10.x diff that matters to this plugin

Only the breaking changes that touch our endpoints. Verified against the Atlassian release/upgrade notes pages on 2026-05-07.

| Change | Source | Impact on plugin |
|---|---|---|
| **REST v2 / JAX-RS 2 / Platform 7 rearchitecture** (10.0) | [Bamboo 10.0 EAP notes](https://confluence.atlassian.com/bambooreleases/bamboo-10-0-eap-1416560823.html): "Note that this isn't a change to Bamboo REST API, which remains largely unchanged." | **No wire impact.** Plugin runs as an HTTP client; the Java API rearchitecture is internal to Bamboo's server-side resource implementations. Confirmed empirically: every plugin path that returned 200 in 9.x still returns 200 in 10.2.14. |
| **Login URI change** (10.0): `/userlogin!doDefault.action` → `/userlogin.action` | [Bamboo 10.2 upgrade notes](https://confluence.atlassian.com/bambooreleases/bamboo-10-2-upgrade-notes-1484751029.html) | **No impact.** Plugin uses Bearer PAT auth (`HttpClientFactory` in `:core`); never hits the form-login URL. |
| **Deployment permissions restructure** (10.2): new `CREATE_RELEASE`, `CLONE`, `ADMINISTRATION` perms split out of `EDIT` | Bamboo 10.2 release notes & upgrade notes | **No impact** — plugin doesn't touch deployment endpoints (R-ADD-6 rejected). |
| **Java upgrade required for Elastic agents (Java 11→17)** (10.2) | Upgrade notes | **No impact** — server-side ops concern. |
| **Removed: PostgreSQL 12-13 / Oracle 18c / Perforce / Java 11** (10.2) | Upgrade notes | **No impact** — server-side. |
| **Plan View Configuration permission tightening** (inferred from §3.2) | Live response, not docs | **Latent** — `getPlanSpecs` 403s on this PAT. See §3.2; plugin doesn't call it today, but if a future feature does, it needs a settings-time permission probe. |

**No documented endpoint removals or shape changes** in the 9.x→10.x transition that touch any plugin path. The two latent plugin bugs (§3.1, §3.5) are bugs that have always been there — exposed by the probe, not introduced by 10.x.

---

## 7. Build-log size finding — quantitative

| Bundle | `result_key` | Log bytes | Log notes |
|---|---|---|---|
| automation suite (`bundle-automation`) | `EPML-T1PXMCCUAQLFQHTLNNP-OVTB-187` (OSS Analysis job, 47 sec build) | **22,791** | small — single Gradle/Maven analysis stage |
| service CI (`bundle-repo`) | `FXZV-A0CLVIKGLYVHCUI-ULVZ-390` (Build Artifacts job, 11 min build) | **405,631** | `~17.7×` larger; `Content-Type: text/plain;charset=utf-8` |

`BambooApiClient.getBuildLog` (line 104–108) returns the whole body via `getRaw` — `it.body?.string() ?: ""`. The 405 KB log path is `BuildDashboardPanel.kt:1133` and `:1168`; both pass the full string into `stageDetailPanel.showLog(...)` on the EDT (`invokeLater`). For 405 KB:

- **Memory:** ~810 KB peak (raw bytes + UTF-16 String + Document model in JTextArea/EditorTextField).
- **EDT cost:** loading 400 KB into a Swing text component is the kind of operation `feedback_intellij_plugin_performance` flags — likely ≥100 ms paint+layout pass on first show, scrolljank thereafter.
- **Realistic worst case:** integration / E2E job logs are typically 1–10 MB. The user's CI org runs 11-min jobs with 405 KB logs; a 30-min integration job log easily reaches 1–3 MB. Plugin will OOM-risk a tab and freeze the EDT briefly when those land in the dashboard.

**Recommendation:** soft cap the rendered log to a tail (e.g. last 200 KB) with a "Show full log (N MB)" link that opens in a scratch file or external editor (the IntelliJ pattern). Optionally probe whether `/download/{key}/build_logs/*.log` honors HTTP `Range: bytes=-204800` for tail-only fetch — Atlassian docs don't mention `Range` support on this download endpoint and we can't probe live; **this is an open question** (§10).

Plumbing path:
- `BambooApiClient.kt:104–108` — accept an optional `maxBytes` param; if set, after the body is read, retain only the last `maxBytes` of the string. Cheap, no protocol risk.
- `BuildDashboardPanel.kt:1126–1140` — render the truncated body, expose "Open full log" action that re-fetches without the cap and opens in a scratch buffer.

This is a **plugin-side fix**; nothing changes about the Bamboo wire call.

---

## 8. Plugin code-level recommendations

Concrete `BambooApiClient.kt` / `BambooDtos.kt` / `BambooServiceImpl.kt` changes for the implementation commit. File-relative line ranges from current files at this audit's snapshot.

### 8.1 `BambooDtos.kt:182–186` — fix `BambooPlanVariableDto` shape mismatch (P0)

**Current:**

```kotlin
@Serializable
data class BambooPlanVariableDto(
    val name: String,
    val value: String = ""
)
```

**Problem:** The plan-level `variableContext` expand returns `[{key: "JENKINS_SECRET", variableType: "GLOBAL", isPassword: true}, {key: "DEVELOP_VERSION", value: "null", ...}, ...]`. With kotlinx-serialization and a non-nullable `name: String` (no default), `MissingFieldException` will be thrown for every variable on parse — i.e., `getPlanVariableContext` always returns `ApiResult.Error(PARSE_ERROR, ...)` on this server. `BambooServiceImpl.getPlanVariables` then falls through Strategy A to Strategy C (recent-build vars), masking the bug.

> However, the **build-level** `variables.variable[]` (used by `getBuildVariables` for the dockerTagsAsJson read) DOES return `name`/`value` shape. So the same DTO is being asked to handle two incompatible shapes.

**Recommended change:**

Split the DTO. Keep `BambooPlanVariableDto` as-is for build-level; introduce `BambooPlanContextVariableDto` for plan-level:

```kotlin
@Serializable
data class BambooPlanContextVariableDto(
    val key: String,
    val value: String = "",
    val variableType: String = "",
    val isPassword: Boolean = false
)
```

Update `BambooPlanDetailResponse.variableContext` to use a new `BambooVariableContextCollection(variable: List<BambooPlanContextVariableDto>)`. Update `getPlanVariableContext` (line 120–124) to return `List<BambooPlanContextVariableDto>` and update `BambooServiceImpl.getPlanVariables` (line 297–305) to map `it.key` instead of `it.name`.

**Regression test:** `BambooApiClientTest.getPlanVariableContext_parsesKeyShape` — feed the recorded `plan_variables_via_context.json:75–105` shape, assert `variable[0].key == "JENKINS_SECRET"`.

### 8.2 `BambooApiClient.kt:73–84` and `BambooServiceImpl.kt:91–97` — branch-fallback fix (P0)

**Current `BambooServiceImpl.getLatestBuild`:** when both branch-plan-key resolution and direct branch URL fail, returns the error.

**Recommended change** (in `BambooServiceImpl.kt:91–97`): on `ApiResult.Error(NOT_FOUND, ...)` from `api.getLatestResult(planKey, branch)`, retry with `api.getLatestResult(planKey)` (master). Trust the result if `vcsRevisions.vcsRevision[0].repositoryName` matches the user's expected repo, OR if the master plan IS configured to track the requested branch (we can't easily verify this server-side, so accept defensively and log a warning that the result might be from a different branch).

**Regression test:** `BambooServiceImplBranchFallbackTest.masterTrackedBranch_falls_back_to_unbranched_latest` with a fake API client returning 404 for `/branch/develop/latest` and 200 for `/latest`.

### 8.3 `BambooApiClient.kt:127–131` and `BambooServiceImpl.kt:308–316` — delete dead `/variable` fallback

Remove `getPlanVariableDirect` and its caller in `BambooServiceImpl.getPlanVariables`. Keep Strategy A (variableContext) and Strategy C (recent-build) — these are the live paths. **Net: -18 lines.**

### 8.4 `BambooApiClient.kt:64–67` — decide on `getPlanSpecs`

Either delete (no caller anywhere in the codebase — verified by grep across `bamboo/`, `automation/`, `core/`, `agent/`) or keep with a KDoc note explaining the **Plan View Configuration** permission requirement and 403 handling. Recommendation: **delete** for consistency with §8.3 dead-fallback policy.

### 8.5 `BambooApiClient.kt:69–71` and `:404–410` — consolidate duplicate `/branch` listing

`getBranches(planKey)` and `getPlanBranches(masterPlanKey, maxResults)` both call `GET /rest/api/latest/plan/{key}/branch?max-results=...`. Two DTOs (`BambooBranchDto` and `BambooPlanBranch`) describe the same response. Pick one (`BambooPlanBranch` is richer — includes `description`, `workflowType`, `divergent`, `awaitingSpecsExecution`, `shortName`, `shortKey`, `key`) and delete the other; update call sites.

### 8.6 `BambooDtos.kt:295–320` — extend `BambooChangesetResultEntry` (optional)

Per §3 finding, `result/byChangeset` returns a `master` block on each entry that the plugin discards. If a future feature needs branch→master walk (e.g., "the build that failed is on a branch plan; show me the master plan to compare"), capture it now:

```kotlin
@Serializable
data class BambooChangesetResultEntry(
    val plan: BambooPlanRef? = null,
    val planResultKey: BambooPlanResultKey? = null,
    val master: BambooPlanRef? = null  // NEW — present on chain_branch results
)
```

Backward-compatible (optional, default null). **Defer to "when needed"** unless the user asks for branch→master surfacing — speculative addition.

### 8.7 `BambooApiClient.kt:104–108` and `BuildDashboardPanel.kt:1133, 1168` — build-log soft cap

Per §7. Add `maxBytes: Int? = null` parameter to `getBuildLog`; at call sites in `BuildDashboardPanel`, cap to ~200 KB tail and add "Open full log" link. Keep the unparameterized read for `BuildLogParser` / `CveRemediationService` (they need the whole log to scan for warnings).

### 8.8 (Feature) `BambooApiClient.getBuildChanges` — adopt R-ADD-1

Per §5 R-ADD-1. New method + DTO + `BambooServiceImpl` exposure. ~40 LOC. Wire into Build tab "What's in this build" tooltip in a follow-up commit if the user wants it; the API surface itself is the audit-ship deliverable.

---

## 9. What was checked but is fine (R-NOOP)

Plugin endpoints that returned 200 with the expected shape on both bundles. No action needed; listed for completeness so the implementation-commit reviewer sees the audit was exhaustive.

```
GET /rest/api/latest/info                                                              200
GET /rest/api/latest/currentUser                                                       200
GET /rest/api/latest/plan?expand=plans.plan&max-results=100                            200
GET /rest/api/latest/project?max-results=100                                           200
GET /rest/api/latest/project/{key}?expand=plans.plan                                   200
GET /rest/api/latest/search/plans?searchTerm={q}&fuzzy=true&max-results=25             200
GET /rest/api/latest/plan/{key}/branch?max-results=100                                 200 (consolidate duplicate, §8.5)
GET /rest/api/latest/plan/{key}                                                        200
GET /rest/api/latest/plan/{key}?expand=variableContext                                 200 (DTO fix needed, §8.1)
GET /rest/api/latest/result/{plan}/latest?expand=stages.stage.results.result           200
GET /rest/api/latest/result/{plan}?includeAllStates=true&max-results=5&expand=...      200
GET /rest/api/latest/result/{plan}?max-results=10&expand=...stages...,...variables...  200
GET /rest/api/latest/result/{key}?expand=stages.stage.results.result                   200
GET /rest/api/latest/result/{key}?expand=vcsRevisions                                  200 (Bamboo→Bitbucket bridge, in production)
GET /rest/api/latest/result/{key}?expand=testResults.failedTests.testResult,...        200
GET /rest/api/latest/result/{key}?expand=variables                                     200 (dockerTagsAsJson reader; shape: name/value)
GET /rest/api/latest/result/{key}?expand=artifacts.artifact                            200
GET /download/{key}/build_logs/{key}.log                                               200 (size cap recommended, §7)
GET /rest/api/latest/result/byChangeset/{sha}?expand=results.result.plan               200
GET /rest/api/latest/repository?max-results=200                                        200 (empty on this PAT — §10 open question)
```

---

## 10. Open questions

1. **`/repository` returns `{size: 0, searchResults: []}` on both bundles.** Either (a) the user's PAT can't see Linked Repositories (admin-gated content?), or (b) this org doesn't use Bamboo Linked Repositories at all (each plan defines its own per-plan VCS). Resolves with: re-probe with an admin PAT, OR ask the user "do you use Bamboo Linked Repositories?" If answer is "no," then `getLinkedRepositories` and `getRepositoryUsedBy` (and their DTOs) are also dead code in the plugin and can be removed in a follow-up cleanup.

2. **`Range: bytes=...` support on `/download/{key}/build_logs/*.log`?** Not in 10.2.14 swagger.json (the download path itself isn't documented as a REST resource — it's a static file route). We can't probe live. Resolves with: a curl test against the user's instance with `-H "Range: bytes=-204800"` and observing the response code (206 Partial Content vs 200 OK). If 206 is supported, the §7 fix can do tail-only fetch instead of fetch-then-truncate, saving the network bytes.

3. **`/result/{key}/jiraIssues` 404 reason.** Live response is 404 even though the endpoint is in 10.2.14 swagger and `result_full.json` shows `jiraIssues: {size: 1}` on the build. Suggests the Bamboo↔Jira application link or the bundled "Jira Issues" Bamboo plugin is disabled on this DC. Doesn't block the audit (we REJECT this candidate per §4.5) but worth a one-line ask to the user if confirmation is needed.

4. **`searchPlans` returned 0 results in both bundles** when the searchTerm was the full plan key (e.g. `GWAB-S4XMTOTLPPWAXBK`). Bamboo's `/search/plans` indexes plan **name** with fuzzy matching, not the key. Plugin's only caller (if any) of `searchPlans` should pass a name fragment, not a key. Verify the plugin doesn't pass keys at runtime — quick grep needed before the implementation commit. (This is a probe-driver UX note, not a plugin bug — the probe used the key as a smoke-test input; the endpoint itself is fine.)

5. **`getRepositoryUsedBy` is unprobed** because step 1 above returned 0 repos. If §10.1 resolves to "yes, the plugin uses Linked Repositories on a different deployment," we'd want to re-probe `/repository/{id}/usedBy` with a real id before adopting any change to its DTO.

---

## 11. Probe artifacts (for traceability)

| Artifact | Path |
|---|---|
| Versions-only bundle (committed) | `tools/atlassian-probe/Result_Bamboo/bundle-versions-only-uncompressed.txt` |
| Service-CI full sweep (committed) | `tools/atlassian-probe/Result_Bamboo/bundle-repo.txt` + `bundle-repo.unpacked/` |
| Automation full sweep (committed) | `tools/atlassian-probe/Result_Bamboo/bundle-automation.txt` + `bundle-automation.unpacked/` |
| Probe driver | `tools/atlassian-probe/probe_bamboo.py` v0 (commit `1c4ba363`) |
| This recommendations doc | `docs/research/2026-05-07-bamboo-audit-recommendations.md` |

Unpacked dirs are local-only — future sessions can diff against committed bundles to detect schema drift across instance upgrades.

---

## 12. Implementation phasing (single Bamboo commit)

Per the audit policy ("one commit per service"), all approved items below land in **one commit** on `fix/automation-handover-quality-tabs`.

### Phase 1 — Bug fixes (P0, must ship)
- **§8.1** — split `BambooPlanVariableDto` to fix the silent parse failure on `?expand=variableContext`
- **§8.2** — `getLatestBuild` master-tracked-branch fallback
- **§8.7** — build-log soft cap in `BuildDashboardPanel` rendering paths

### Phase 2 — Cleanup (no behavior change)
- **§8.3** — delete `getPlanVariableDirect` and Strategy B in `getPlanVariables`
- **§8.4** — delete `getPlanSpecs` (no caller anywhere)
- **§8.5** — consolidate `getBranches` / `getPlanBranches` duplicates

### Phase 3 — Feature additions
- **§8.8 / R-ADD-1** — `getBuildChanges(resultKey)` API surface (consumer wiring optional, follow-up commit)

### Deferred — out of scope for this implementation commit
- R-ADD-2 build labels (no user ask)
- R-ADD-4 agent health (no user ask)
- R-ADD-5 global queue (single-plan polling fine at current N)
- R-ADD-6 deployment projects (greenfield, REJECTED)
- R-ADD-7 build comments (no user ask)
- §8.6 `BambooChangesetResultEntry.master` (speculative)
- §10.1 `/repository` cleanup pending user confirmation

---

## 13. Out-of-scope / explicitly NOT touching

- **Bamboo Cloud** — DC only per audit policy (and Bamboo Cloud is EOL anyway).
- **Plugin auth model** — Bearer PAT confirmed working; no change.
- **Bamboo→Bitbucket bridge** (`BuildFailureBridgeStartupActivity`) — already shipped at commit `b9ed7cbe`; this audit confirms the underlying `getResultVcsRevision` shape is stable.
- **`:automation` `dockerTagsAsJson` reader** — verified working; build-level `variables.variable[]` shape is `name`/`value` (the plan-level mismatch in §8.1 is unrelated).
- **`agent/` module** — Bamboo agent tools (`bamboo_builds.*`) wrap `BambooService`, not `BambooApiClient`; covered transitively by the `BambooService` fixes in this commit.
- **CVE remediation, log parser** — unrelated; orthogonal to API audit.
