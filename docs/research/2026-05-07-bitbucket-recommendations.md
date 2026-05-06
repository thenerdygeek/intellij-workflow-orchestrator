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

## 2. Bamboo ↔ Bitbucket bridge — final scope after audit-followup (2026-05-07)

The user explicitly asked for "related Bamboo builds etc etc" as a feature direction. After the audit-followup re-probe, the bridge ships with the **v1 build-status surface** + reverse lookup. Rich/deployment endpoints are organically deferred (this org's Bamboo agents don't publish rich data, and no provider has registered deployments).

| # | Endpoint | Status | Decision |
|---|---|---|---|
| B1 | `GET /commits/{sha}/pull-requests` (reverse lookup) | ✅ 200 — schema validated (size: 0 on test SHA, but the shape is right) | **R-ADD-5: ADOPT.** Phase 1 of the bridge: Bamboo build fails → reverse-lookup to PRs → notify authors. |
| B2 | `GET /rest/api/latest/.../commits/{cid}/builds` (rich build status) | ❌ 400 even on a SHA with 2 registered Bamboo builds (audit-followup confirmed) | **DEFER.** Bamboo agents on this org publish only basic v1 schema — no `testResults`/`parent`/`ref` to return. Adoptable once Bamboo is upgraded; out of scope for this audit. |
| B3 | `GET /rest/api/latest/.../commits/{cid}/deployments` | ❌ 400 (capability advertised but unused) | **DEFER.** No provider has published deployments. Re-evaluate when one comes online. |
| B4 | `POST /rest/build-status/1.0/commits/stats` (bulk POST) | ❌ 400 with both body shapes tried | **DEFER.** Single-commit GET (B6) is sufficient for our use; bulk lookup not UX-critical. |
| B5 | `GET /rest/build-status/1.0/commits/{sha}` (current plugin path) | ✅ 200 | **R-NOOP — keep as the long-term build-status fetch.** Returns the basic schema Bamboo publishes (state/key/name/url/description/dateAdded), which is what the PR build-status badge needs anyway. |
| B6 | `GET /rest/build-status/1.0/commits/stats/{sha}` (single-commit aggregate) | ✅ 200 | **R-ADD-12: ADOPT** for cheap `{successful, failed, inProgress}` dashboard counters. |

**End-to-end bridge that ships:** Bamboo build event → Bitbucket v1 commit-status table → R-ADD-5 reverse lookup → PR authors notified, with R-ADD-12 powering aggregate counters. Rich-format and deployments wait on Bamboo-side upgrades.

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
| R-ADD-16 | `GET /pull-requests/{id}.patch` (`Accept: text/plain`) | ✅ 200 (validated post-followup) | "Apply this PR locally" agent flows; offline review | PR tab |

**Highest-value adds for Phase 1:** R-ADD-5 (Bamboo bridge), R-ADD-11 (Jira link replaces regex), R-ADD-1/2 (inbox), R-ADD-15 (required-builds gating).

---

## 5. R-INVESTIGATE — Failures, post-followup-re-probe (2026-05-07)

Audit-followup re-probe ran 2026-05-07 against a commit with 2 registered Bamboo builds (auto-discovered by the scanner). Results below resolve the open items.

| ID | Endpoint | Pre-followup | Post-followup | Resolution |
|---|---|---|---|---|
| R-INV-1 | `GET /rest/api/latest/.../commits/{cid}/builds` (rich build status) | 400 (suspected: no builds) | **400** even on a SHA with 2 registered Bamboo builds | **DEFER.** This org's Bamboo agents publish only the basic v1 schema (no `testResults`/`parent`/`ref`/`duration`); the rich endpoint rejects when there's nothing rich to return. Adoption requires Bamboo agents to be upgraded first — out of scope for this audit. **Keep `/rest/build-status/1.0/commits/{sha}` (v1) as the long-term build-status fetch.** |
| R-INV-2 | `GET /rest/api/latest/.../commits/{cid}/deployments` | 400 (suspected: no deployments) | **400** | **DEFER.** Capability advertised but no provider (Bamboo or otherwise) has registered deployment status anywhere. Adoption is unblocked the day a deployment-publisher comes online; until then the endpoint surface is meaningless. Not on the implementation commit. |
| R-INV-3 | `POST /rest/build-status/1.0/commits/stats` (bulk stats) | 400 `MismatchedInputException` (`{commits:[sha]}`) | **400** still `MismatchedInputException` even with `[{commitId:"sha"}]` | **DEFER.** Body shape still undetermined. Not blocking — the single-commit GET (`/commits/stats/{sha}`, R-ADD-12) is validated and sufficient for dashboard counters. Re-probe in a future audit-followup if bulk lookup becomes UX-critical. |
| R-INV-4 | `GET /pull-requests/{id}/comments` (current plugin call) | 400 | n/a | **Covered as P0 fix — see §1.1.** |
| R-INV-5 | `GET /repos/{r}/settings/pull-requests/git` (current plugin call) | 404 | n/a | **Covered as P0 fix — see §1.2.** |
| R-INV-6 | `GET /commits/{sha}/jira-issues` | 404 | n/a | **Resolved** — drop attempt; use R-ADD-11 (PR-scoped Jira link) instead. |
| R-INV-7 | `GET /pull-requests/{id}.patch` | 406 Not Acceptable | **✅ 200** with `Accept: text/plain` | **PROMOTE TO ADOPT.** Probe driver fixed (commit `a2584f62` adds per-request `accept=` override). Endpoint usable for "apply this PR locally" agent flows. Track as **R-ADD-16**. |
| R-INV-8 | `GET /repos/{r}/last-modified/{path}` | 400 `ArgumentValidationException` (no `at=`) | **404** `NoSuchPathException` on README.md (a path that doesn't exist) — endpoint validates input correctly with `at=` | **DEFER for empirical validation.** The endpoint and probe are fine; this specific repo just doesn't have README.md. Needs a re-probe with `--file-path <known-existing-path>` if we want a 200 confirmation. Not blocking. |
| R-INV-9 | `POST /rest/search/1.0/search` | TIMEOUT (30s) | n/a | **DEFER.** Search backend appears disabled or absent on this DC; not adopting code-search via this endpoint. |

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

### Phase 3 — Bamboo ↔ Bitbucket bridge (final scope, post-audit-followup)
- **R-ADD-5** — reverse `commits/{sha}/pull-requests` lookup wired into `:bamboo` build-failure flows
- **R-ADD-12** — single-commit aggregate stats `commits/stats/{sha}` for build badges
- **R-NOOP B5** — keep v1 `/rest/build-status/1.0/commits/{sha}` as the long-term build-status fetch (NOT a fallback — this is the permanent solution on this org until Bamboo agents publish rich format)
- _Rich build status (R-INV-1) and deployments (R-INV-2) are deferred — see §5; not in this commit_

### Phase 4 — Feature additions
- **R-ADD-11** — `/rest/jira/1.0/.../pull-requests/{id}/issues` replaces manual regex
- **R-ADD-1 / R-ADD-2** — inbox count + list (PR tab tool-window badge)
- **R-ADD-15** — required-builds conditions (with corrected path)
- **R-ADD-13** — markup preview endpoint wired into commit-msg + PR-desc editors
- **R-ADD-14** — file-scoped diff for the PR review tab
- **R-ADD-16** — `pull-requests/{id}.patch` with `Accept: text/plain` (validated post-followup; useful for "apply this PR locally" flows)

### Phase 5 — Quality tab Code Insights (separate commit, can defer)
- **R-ADD-9 + R-ADD-10** — Code Insights reports + annotations on the Quality tab
- _Note: this overlaps the existing Sonar integration; needs a Quality-tab design choice. Defer to a separate `:sonar` or `:quality` commit if scope creeps._

### Deferred — out of scope for this implementation commit
- **R-INV-1** — rich build-status (Bamboo-side upgrade required first)
- **R-INV-2** — deployments (no provider publishing on this DC)
- **R-INV-3** — bulk-stats POST (body shape undetermined; not blocking)
- **R-INV-8** — last-modified (works; needs a re-probe with a path that exists in the repo)
- **R-INV-9** — code search (backend appears disabled)

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
| Versions-only bundle (committed) | `tools/atlassian-probe/Result_Bitbucket/bundle-versions-only-uncompressed.txt` |
| Full-sweep bundle (committed) | `tools/atlassian-probe/Result_Bitbucket/bundle-full-sweep-compressed.txt` |
| **Audit-followup bundle (committed)** | `tools/atlassian-probe/Result_Bitbucket/bundle-audit-followup-compressed.txt` |
| API surface research | `docs/research/2026-05-07-bitbucket-9.4-api-surface.md` |
| Probe driver | `tools/atlassian-probe/probe_bitbucket.py` (commits `71048d16` v1, `a2584f62` v1.1 — adds `--audit-followup` mode + `accept=` per-request header override) |

Unpacked dirs are local-only — future sessions can diff against committed bundles to detect schema drift across instance upgrades.
