# Bitbucket DC 9.4 — recommendations from full-sweep audit

**Date:** 2026-05-07
**Target:** Bitbucket Data Center 9.4.16 (build 9004016, LTS)
**Branch:** `fix/automation-handover-quality-tabs` (audit branch — Jira → **Bitbucket** → Nexus, one commit per service)
**Auth:** PAT with non-admin scope
**Sources:**
- Probe driver — `tools/atlassian-probe/probe_bitbucket.py` v1
- Probe results — `tools/atlassian-probe/Result_Bitbucket/bundle-full-sweep-compressed.txt` (74 endpoints, 50 OK / 24 fail; 76-file unpacked dir)
- DC 9.4 API research — `docs/research/2026-05-07-bitbucket-9.4-api-surface.md`
- Plugin client under audit — `core/src/main/kotlin/com/workflow/orchestrator/core/bitbucket/BitbucketBranchClient.kt`

> **Read this first if you're picking up the Bitbucket audit.** This is the keep/swap/add doc; once approved, all approved items land in **one Bitbucket implementation commit** on this branch (per the audit policy in `project_api_audit_in_progress.md`).

---

## 0. Headline findings

- **No architectural funnel gap** — every Bitbucket call goes through `BitbucketBranchClient.kt`. Unlike Jira, there's no `JiraTaskRepository`-style leak to fix here.
- **2 current plugin calls are broken on DC 9.4** — see §1, must-fix regardless of swap decisions.
- **5 swap candidates validated** — collapse per-repo iteration into single dashboard calls; richer branch/comment data in fewer round-trips.
- **14 new feature surfaces validated** — including the user's stated direction (commit→PR reverse lookup for Bamboo bridges) and Code Insights for the Quality tab.
- **3 swap/feature endpoints returned 400 on this run** — likely because no Bamboo build/deployment has been registered against the test commit; need to re-validate against a built commit before adoption.
- **Server-vs-DC heuristic fix shipped in v1 probe** is now confirmed working — `edition: Data Center` in summary, no admin token needed.

---

## 1. CRITICAL — plugin bugs surfaced by the probe (P0)

These two calls are made today by `BitbucketBranchClient.kt` and return error codes on DC 9.4. Fix in the implementation commit regardless of any swap decisions.

### 1.1 `getPullRequestCommentsThreads()` returns 400 on DC 9.4

| | |
|---|---|
| **Plugin call** | `GET /rest/api/1.0/projects/{p}/repos/{r}/pull-requests/{prId}/comments?limit=100&start=0` |
| **Plugin sites** | `BitbucketBranchClient.kt` lines 1741–1752 + line 1777 (`getPullRequestCommentsThreads`) |
| **Probe result** | `pr_comments` → **400** with `errors[0].message` (text redacted) |
| **Root cause** | DC 9.4's `/comments` listing requires either `path` (for path-anchored comments) or `count=true` — listing "all root threads in a PR" without those is rejected. (The same call worked on older DC versions, which is why the plugin code looks fine.) |
| **Recommended fix** | Drop the direct `/comments` call. The plugin already calls `/activities` (which returned 200 in this sweep) and the activities timeline already contains every COMMENTED action with full comment bodies. Filter activities for `action=COMMENTED` instead. |
| **Implementation note** | `BitbucketBranchClient.kt`'s comments-listing function should be deprecated in favour of the activities-derived path that `pullrequest/.../CommentsTabPanel.kt` likely already uses for at least part of its data. Confirm in implementation. |

### 1.2 `getPullRequestMergeStrategies()` 404 — needs project-level fallback

| | |
|---|---|
| **Plugin call** | `GET /rest/api/1.0/projects/{p}/repos/{r}/settings/pull-requests/git` |
| **Plugin sites** | `BitbucketBranchClient.kt` lines 1542–1553 |
| **Probe result** | `repo_settings_pull_requests_git` → **404** with `{message, status-code: 404, sub-code: -1}` |
| **Root cause** | The repo doesn't have a per-repo override of merge-strategy settings; in DC 9.4 the absence-of-override is a 404, not an empty 200. The repo inherits the project-level setting. |
| **Recommended fix** | On 404, fall back to `GET /rest/api/1.0/projects/{p}/settings/pull-requests/git`. Cache the resolution per-repo per-session. |
| **Implementation note** | Same DTO at both URLs — only the path differs. |

---

## 2. Bamboo ↔ Bitbucket bridge (the user's stated direction)

The user explicitly asked for "related Bamboo builds etc etc" as a feature direction. The probe surfaced four touchpoints, three of which need follow-up before we can ship.

| # | Endpoint | Probe status | What it unlocks | Decision |
|---|---|---|---|---|
| B1 | `GET /commits/{sha}/pull-requests` (reverse lookup) | ✅ 200 (size: 0 for our test SHA — schema validated) | Bamboo build fails for commit X → look up which PRs touched X → notify those authors. **The most valuable bridge, ready to adopt.** | **R-ADD-5: ADOPT** |
| B2 | `GET /rest/api/latest/.../commits/{cid}/builds` (rich build status) | ⚠️ 400 (`ArgumentValidationException`) — likely "no builds registered for this commit"; v1 GET still works as fallback | Replaces v1 `/rest/build-status/1.0/commits/{sha}` for builds that DO have rich data (testResults, parent SHA, ref). | **R-INV-1: REVALIDATE** before adopting — see §5 |
| B3 | `GET /rest/api/latest/.../commits/{cid}/deployments` (deployment-by-commit) | ⚠️ 400 (`ArgumentValidationException`) — capability advertised on this DC, but no provider has published deployments yet | "This commit shipped to staging at 14:32" inline indicators. | **R-INV-2: REVALIDATE** after Bamboo publishes a deployment |
| B4 | `POST /rest/build-status/1.0/commits/stats` (bulk-stats POST) | ❌ 400 (`MismatchedInputException`) — probe sent wrong body shape | Cheap "color a commit list red/green" bulk lookup | **R-INV-3: PROBE BUG**, fix body shape and re-run |
| B5 | `GET /rest/build-status/1.0/commits/{sha}` (current plugin path) | ✅ 200 | Today's PR build-status badge | **R-NOOP**: keep as fallback when rich endpoint 400s |
| B6 | `GET /rest/build-status/1.0/commits/stats/{sha}` (single-commit aggregate) | ✅ 200 | Cheap counter `{successful, failed, inProgress}` per-commit | **R-ADD-12: ADOPT** for dashboard counters |

The end-to-end story we're enabling: **Bamboo build event → Bitbucket commit-status table → reverse-lookup → PR authors notified**. Phase 1 of the Bamboo bridge ships immediately via R-ADD-5 + R-ADD-12 + R-NOOP B5. Phase 2 (rich build + deployments) waits for a re-probe against a built commit.

---

## 3. R-SWAP — Validated swaps (better paths for existing functions)

Each row is paired with the exact `BitbucketBranchClient.kt` function to update.

| ID | Plugin function | Current path | Recommended swap | Probe |
|---|---|---|---|---|
| R-SWAP-1 | `getOpenPullRequestsAuthoredByMe()` (line 1062) | per-repo `/role.1=AUTHOR` loop (N calls / N repos) | `GET /rest/api/1.0/dashboard/pull-requests?role=AUTHOR&state=OPEN&limit=10` (1 call) | ✅ 200, returned 6 PRs across all repos |
| R-SWAP-2 | `getOpenPullRequestsForReviewByMe()` (line 1103) | per-repo `/role.1=REVIEWER` loop | `GET /rest/api/1.0/dashboard/pull-requests?role=REVIEWER&state=OPEN&limit=10` | ✅ 200 |
| R-SWAP-3 | `listBranches()` (line 641) | `?orderBy=MODIFICATION` then N follow-up calls for `aheadBehind` | add `&details=true` to same call | ✅ 200 — payload contains `metadata` per branch |
| R-SWAP-4 | (PR blocker filter — currently done client-side over `/comments`) | filter all comments where `severity=BLOCKER` | `GET /pull-requests/{id}/blocker-comments?count=true` | ✅ 200 |
| R-SWAP-5 | reviewer-list parsing in `parsePullRequestReviewers()` (callers of `getPullRequest`) | parses `pr_get.reviewers[]` | `GET /pull-requests/{id}/participants` (explicit endpoint with `lastReviewedCommit` per reviewer) | ✅ 200 |

**Compounding effect:** R-SWAP-1 and R-SWAP-2 are the biggest architectural wins. The plugin currently iterates over every repo to build the PR list — for users with 50+ repos that's 50 HTTP round-trips on each refresh. The dashboard variant is one call. Combined with the existing connection pool, this collapses a noticeable UI lag on large instances.

---

## 4. R-ADD — New feature surfaces validated

| ID | Endpoint | Probe | UX value | Plugin tab |
|---|---|---|---|---|
| R-ADD-1 | `GET /rest/api/1.0/inbox/pull-requests/count` | ✅ 200 (`{count: 0}`) | Tool-window unread badge | PR tab |
| R-ADD-2 | `GET /rest/api/1.0/inbox/pull-requests` | ✅ 200 | "PRs needing your action" — Atlassian's algorithmic list | PR tab |
| R-ADD-3 | `GET /rest/api/1.0/dashboard/pull-request-suggestions` | ✅ 200 | "You should review these" suggestions | PR tab |
| R-ADD-4 | `GET /rest/api/1.0/profile/recent/repos` | ✅ 200 | Repo picker ranks by recency-for-me, not alphabetical | Sprint + PR tabs |
| R-ADD-5 | `GET /commits/{sha}/pull-requests` | ✅ 200 (size: 0 — schema OK) | **Reverse lookup — Bamboo build → PR notifier** | Build tab + PR tab |
| R-ADD-6 | `GET /commits?limit=10` | ✅ 200 | Recent-activity feed widget | Sprint tab |
| R-ADD-7 | `GET /tags?limit=20` | ✅ 200 | "Compare with tag X" / release-notes feature | future |
| R-ADD-8 | `GET /labels?limit=20` | ✅ 200 | Filter PR list by label | PR tab |
| R-ADD-9 | `GET /rest/insights/1.0/.../reports` | ✅ 200 (size: 0 — capability validated) | Sonar/SCA findings on a commit | Quality tab |
| R-ADD-10 | `GET /rest/insights/1.0/.../annotations?limit=50` | ✅ 200 | Inline-on-diff overlays for findings | Quality tab |
| R-ADD-11 | `GET /rest/jira/1.0/.../pull-requests/{id}/issues` | ✅ 200 (returned `[{key: PROJ-002, url}]`) | **Replace plugin's manual regex over PR title/branch/commits** | PR tab + commit-msg gen |
| R-ADD-12 | `GET /rest/build-status/1.0/commits/stats/{sha}` | ✅ 200 | Cheap per-commit aggregate counter `{successful, failed, inProgress}` | Build tab + PR tab badges |
| R-ADD-13 | `POST /rest/api/1.0/markup/preview` | ✅ 200 | Editor that renders Markdown exactly as Bitbucket will | Commit-msg + PR-desc editors |
| R-ADD-14 | `GET /pull-requests/{id}/diff/{path}?contextLines=10` | ✅ 200 | Per-file diff fetch — smaller payload for huge PRs | PR review tab |
| R-ADD-15 | `GET /rest/required-builds/latest/.../conditions` | ✅ 200 (corrected path; v0 used wrong base) | "Required builds before merge" indicator | PR tab |

**Highest-value adds for Phase 1:** R-ADD-5 (Bamboo bridge), R-ADD-11 (Jira link replaces regex), R-ADD-1/2 (inbox), R-ADD-15 (required-builds gating).

---

## 5. R-INVESTIGATE — Failures needing follow-up

Each row is a `400` / `404` / probe-bug that needs resolution before the implementation commit can decide on it.

| ID | Endpoint | Status | Likely cause | Action before adoption decision |
|---|---|---|---|---|
| R-INV-1 | `GET /rest/api/latest/.../commits/{cid}/builds` (rich build status) | 400 `ArgumentValidationException` | Test commit has no builds registered; endpoint validates that builds exist before returning | Re-probe against a commit that has Bamboo builds. If still 400, fall back to v1 path permanently. |
| R-INV-2 | `GET /rest/api/latest/.../commits/{cid}/deployments` | 400 `ArgumentValidationException` | Capability advertised but no Bamboo deployment-publishing has happened yet | Re-probe after a deployment is registered (or accept this as a "future feature when Bamboo wires deployment-publishing"). |
| R-INV-3 | `POST /rest/build-status/1.0/commits/stats` | 400 `MismatchedInputException` | **Probe bug** — wrong body shape. Probe sends `{commits: ["sha"]}`; Atlassian likely wants `[{commitId: "sha"}]` | Fix probe body shape and re-run; cheap to validate. |
| R-INV-4 | `GET /pull-requests/{id}/comments` (current plugin call) | 400 | DC 9.4 requires `path` or `count=true` | **Already covered as P0 fix — see §1.1** |
| R-INV-5 | `GET /repos/{r}/settings/pull-requests/git` (current plugin call) | 404 | No per-repo override exists | **Already covered as P0 fix — see §1.2** |
| R-INV-6 | `GET /commits/{sha}/jira-issues` | 404 | Endpoint doesn't exist at this path; Jira link plugin exposes the variant **at PR scope** instead | Drop commit-scoped attempt; use R-ADD-11 (PR-scoped) for the Jira-key extraction. |
| R-INV-7 | `GET /pull-requests/{id}.patch` | 406 Not Acceptable | Probe sends `Accept: application/json`; endpoint serves `text/plain` only | Probe header bug — adjust probe to set `Accept: text/plain`. Endpoint itself is fine; recommend in v2 of probe. |
| R-INV-8 | `GET /repos/{r}/last-modified/{path}` | 400 `ArgumentValidationException` | Likely needs `at={ref}` query param | Re-probe with `?at=refs/heads/develop` and validate. Then decide. |
| R-INV-9 | `POST /rest/search/1.0/search` | TIMEOUT (30s) | Search backend either disabled or slow; no actual response | Either skip (search not enabled on this DC) or re-test with longer timeout. |

---

## 6. R-NOOP — Validated current calls (no change recommended)

Every plugin call below returned 200 and matches the documented shape. Keep as-is.

```
GET /rest/api/1.0/projects                                     ✅ 200
GET /rest/api/1.0/projects/{p}/repos/{r}                       ✅ 200
GET /rest/api/1.0/projects/{p}/repos/{r}/default-branch        ✅ 200
GET /rest/api/1.0/projects/{p}/repos/{r}/branches              ✅ 200
GET /rest/api/1.0/projects/{p}/repos/{r}/branches?filterText=  ✅ 200
GET /rest/api/1.0/projects/{p}/repos/{r}/pull-requests         ✅ 200 (with state, role, direction variants)
GET /rest/api/1.0/projects/{p}/repos/{r}/pull-requests/{id}    ✅ 200
GET /pull-requests/{id}/activities                             ✅ 200
GET /pull-requests/{id}/diff                                   ✅ 200
GET /pull-requests/{id}/changes                                ✅ 200
GET /pull-requests/{id}/commits                                ✅ 200
GET /pull-requests/{id}/comments/{commentId}                   ✅ 200 (single comment by id)
GET /pull-requests/{id}/merge                                  ✅ 200 (mergeability check)
GET /default-reviewers/1.0/projects/{p}/repos/{r}/conditions   ✅ 200
GET /rest/api/1.0/users?filter=                                ✅ 200
GET /rest/build-status/1.0/commits/{sha}                       ✅ 200 (keep as fallback even after rich endpoint adopted)
GET /rest/capabilities                                         ✅ 200
```

---

## 7. Architecture instruction (from user, 2026-05-06)

> "Make changes in all the appropriate places (sprint tab, commit message generation, agent tools, etc.) and unify the architecture if not already."

For Bitbucket this is **already satisfied** — every call funnels through `core/bitbucket/BitbucketBranchClient.kt`, which goes through `:core` interface returning `ToolResult<T>`. The agent's `BitbucketPrTool` / `BitbucketReviewTool` / `BitbucketRepoTool` all delegate to the same client. **No architectural fix needed in the implementation commit** — only endpoint changes per this doc.

The only architectural note: when adopting the dashboard PRs (R-SWAP-1 / R-SWAP-2), the per-repo iteration helpers can be **deleted** rather than kept-and-deprecated, since the new function returns the same shape. This is a positive simplification (≈40 lines deleted vs ≈10 added).

---

## 8. Implementation phasing (single Bitbucket commit)

Per the audit policy ("one commit per service"), all approved items below land in **one commit** on `fix/automation-handover-quality-tabs`.

### Phase 1 — Bug fixes (P0, must ship)
- **§1.1** — `getPullRequestCommentsThreads()`: switch to activities-derived path
- **§1.2** — repo-level pull-request settings: project-level fallback on 404

### Phase 2 — High-value swaps (architectural improvements)
- **R-SWAP-1 + R-SWAP-2** — dashboard PRs replace per-repo iteration (collapse N round-trips → 1)
- **R-SWAP-3** — branches with `details=true`
- **R-SWAP-4** — `blocker-comments?count=true` for blocker badges
- **R-SWAP-5** — `/participants` for reviewer status

### Phase 3 — Bamboo ↔ Bitbucket bridge (Phase 1 — what works today)
- **R-ADD-5** — reverse `commits/{sha}/pull-requests` lookup wired into `:bamboo` build-failure flows
- **R-ADD-12** — single-commit aggregate stats `commits/stats/{sha}` for build badges
- **R-NOOP B5** — keep v1 build-status as the per-commit fallback

### Phase 4 — Feature additions
- **R-ADD-11** — `/rest/jira/1.0/.../pull-requests/{id}/issues` replaces manual regex
- **R-ADD-1 / R-ADD-2** — inbox count + list (PR tab tool-window badge)
- **R-ADD-15** — required-builds conditions (with corrected path)
- **R-ADD-13** — markup preview endpoint wired into commit-msg + PR-desc editors
- **R-ADD-14** — file-scoped diff for the PR review tab

### Phase 5 — Quality tab Code Insights (separate commit, can defer)
- **R-ADD-9 + R-ADD-10** — Code Insights reports + annotations on the Quality tab
- _Note: this overlaps the existing Sonar integration; needs a Quality-tab design choice. Defer to a separate `:sonar` or `:quality` commit if scope creeps._

### Deferred to follow-up probe (waits on R-INVESTIGATE resolution)
- **R-INV-1** — rich build-status (revalidate against built commit)
- **R-INV-2** — deployments (revalidate after publishing)
- **R-INV-3** — bulk-stats POST (probe bug fix, then validate)
- **R-INV-8** — last-modified (add `at=` and re-validate)

---

## 9. Out-of-scope / explicitly NOT touching

- **Bitbucket Cloud** — Server/DC only per audit policy
- **Webhook management UI** — even though `repo_webhooks` 401'd as expected, building a webhook UI is a feature ask, not an audit fix
- **Smart-mirroring read endpoints** — DC-specific; useful for replica diagnostics but out of plugin scope
- **Personal access tokens API** (`/rest/access-tokens/1.0/users/{slug}`) — opt-in via `--my-username`; not run this sweep. Re-probe + adopt if the user asks for token-expiry warnings.
- **Audit / migration / admin endpoints** — admin-token-only and not relevant to the IDE plugin's surface

---

## 10. Probe artifacts (for traceability)

| Artifact | Path |
|---|---|
| Compressed bundle (committed) | `tools/atlassian-probe/Result_Bitbucket/bundle-full-sweep-compressed.txt` |
| Unpacked bundle (local-only, not committed) | `tools/atlassian-probe/Result_Bitbucket/bundle-full-sweep.unpacked/` |
| API surface research | `docs/research/2026-05-07-bitbucket-9.4-api-surface.md` |
| Probe driver | `tools/atlassian-probe/probe_bitbucket.py` v1 (commit `71048d16`) |

The unpacked dir contains 74 raw `<probe-name>.json` files — each holds the full request/response so future sessions can diff against later probe runs to detect schema drift.
