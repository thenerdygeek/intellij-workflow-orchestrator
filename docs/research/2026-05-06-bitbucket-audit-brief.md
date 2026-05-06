# Bitbucket API Audit — Session Brief

**Branch:** `fix/automation-handover-quality-tabs` (same branch as Jira; do not create new branches)
**Status:** Not started. This is **session 2** of a 3-step audit (Jira → **Bitbucket** → Nexus).
**Predecessors:** Jira session must commit first; the toolkit it built (probe / redact / bundle) is reusable.

---

## 1. Mission

Replicate the Jira audit pattern for Bitbucket Data Center: inventory call sites, probe each with a read-only script, redact + bundle the results for the user, compile a keep/swap/add recommendations doc, get user approval, implement everything in **one Bitbucket-scoped commit**.

---

## 2. Toolkit you'll reuse

All three scripts in `tools/atlassian-probe/` are general-purpose; you only need to write the Bitbucket-specific probe driver. The redact + bundle scripts are product-agnostic.

| Tool | Path | Reuse as-is? |
|---|---|---|
| `redact.py` | `tools/atlassian-probe/redact.py` | Yes. Already handles emails, free-text, custom-word substring markers, gzip-friendly output, SystemRandom-backed random replacements. |
| `bundle.py` | `tools/atlassian-probe/bundle.py` | Yes. `pack` / `unpack` modes; `--compress` for clipboard-too-big bundles. |
| Probe driver | needs to be written: `tools/atlassian-probe/probe_bitbucket.py` | **Author this.** Mirror `probe_jira.py`'s structure: `--versions-only`, `--discover`, full sweep with categories `version` / `existing` / `internal` / `candidate`. |

The redactor's `CUSTOM_REDACT_WORDS` list and the JSON-shape redaction work without changes for Bitbucket payloads.

---

## 3. Bitbucket call-site inventory (already grepped)

The plugin's Bitbucket surface is **all in `core/src/main/kotlin/com/workflow/orchestrator/core/bitbucket/BitbucketBranchClient.kt`** — that's the single funnel (good — no architectural gap to fix here). Auth is `Authorization: Bearer <PAT>` against Bitbucket Data Center 8.x+.

### Endpoints currently used (29 distinct paths)

**Repo / branch reads:**
```
GET /rest/api/1.0/projects?limit=100
GET /rest/api/1.0/projects/{projectKey}/repos/{repoSlug}
GET /rest/api/1.0/projects/{projectKey}/repos/{repoSlug}/branches?limit=100&orderBy=MODIFICATION
GET /rest/api/1.0/projects/{projectKey}/repos/{repoSlug}/default-branch
GET /rest/api/1.0/projects/{projectKey}/repos/{repoSlug}/browse/{filePath}?at={ref}&raw
```

**Branch + PR creation (write — verify but don't run):**
```
POST /rest/api/1.0/projects/{projectKey}/repos/{repoSlug}/branches
POST /rest/api/1.0/projects/{projectKey}/repos/{repoSlug}/pull-requests
```

**PR list / get / update:**
```
GET /rest/api/1.0/projects/.../pull-requests?state={state}&limit={limit}
GET /rest/api/1.0/projects/.../pull-requests?direction=OUTGOING&at={branchRef}&state=OPEN
GET /rest/api/1.0/projects/.../pull-requests?state={state}&role.1=AUTHOR&...
GET /rest/api/1.0/projects/.../pull-requests?state={state}&role.1=REVIEWER&...
GET /rest/api/1.0/projects/.../pull-requests/{prId}
PUT /rest/api/1.0/projects/.../pull-requests/{prId}                  # update title/description
GET /rest/api/1.0/projects/.../pull-requests/{prId}/activities       # for reviewer status, comments timeline
GET /rest/api/1.0/projects/.../pull-requests/{prId}/diff
GET /rest/api/1.0/projects/.../pull-requests/{prId}/changes
GET /rest/api/1.0/projects/.../pull-requests/{prId}/commits
GET /rest/api/1.0/projects/.../pull-requests/{prId}/comments
GET /rest/api/1.0/projects/.../pull-requests/{prId}/comments/{commentId}
PUT /rest/api/1.0/projects/.../pull-requests/{prId}/comments/{commentId}?version={version}
POST /rest/api/1.0/projects/.../pull-requests/{prId}/comments
```

**PR merge lifecycle (writes):**
```
GET  /rest/api/1.0/projects/.../pull-requests/{prId}/merge          # check mergeability
POST /rest/api/1.0/projects/.../pull-requests/{prId}/merge?version={v}
POST /rest/api/1.0/projects/.../pull-requests/{prId}/decline?version={v}
POST /rest/api/1.0/projects/.../pull-requests/{prId}/approve
DELETE /rest/api/1.0/projects/.../pull-requests/{prId}/approve
PUT /rest/api/1.0/projects/.../pull-requests/{prId}/participants/{username}
```

**Other surfaces:**
```
GET /rest/api/1.0/projects/.../settings/pull-requests/git           # repo-level merge strategies
GET /rest/api/1.0/users?filter={query}&limit=10                     # user search
GET /rest/build-status/1.0/commits/{commitId}                       # build status integration
GET /rest/default-reviewers/1.0/projects/.../repos/.../conditions   # default-reviewers plugin
```

### Files that consume Bitbucket data

`pullrequest/src/main/kotlin/com/workflow/orchestrator/pullrequest/...`:
- `service/BitbucketServiceImpl.kt` — main service implementing `:core` PR interfaces
- `service/PrDetailService.kt`, `service/PrListService.kt`, `service/PrActionService.kt`
- `ui/PrDashboardPanel.kt`, `ui/PrDetailPanel.kt`, `ui/CommentsTabPanel.kt`, `ui/AiReviewTabPanel.kt`, `ui/CreatePrDialog.kt`
- `action/CreatePrPrefetch.kt` — pre-fetch PR context on Create PR action
- `workflow/OpenPrListerImpl.kt` — registered EP for cross-module PR listing

`core/src/main/kotlin/com/workflow/orchestrator/core/bitbucket/`:
- `BitbucketBranchClient.kt` — the funnel (~1900 lines, all 29 paths above)
- `PrService.kt`, `CreatePrLauncher.kt` — interfaces
- `RemoteUrlParser.kt` — parses git remote URLs to extract project/slug

Agent integration: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/integration/` has `BitbucketPrTool`, `BitbucketReviewTool`, `BitbucketRepoTool` — all delegate to the same client.

### No funnel gap

Unlike Jira (where `JiraTaskRepository` rolls its own OkHttp), every Bitbucket call goes through `BitbucketBranchClient`. **No architectural fix needed in this audit** — just endpoint validation + recommendations.

---

## 4. Candidate endpoints to probe (recommendations)

Things the plugin doesn't currently use but might benefit from:

| Endpoint | Why probe |
|---|---|
| `GET /rest/api/1.0/application-properties` | Server version detection (mirrors Jira's `/serverInfo`). Tells us DC version + `displayName`. |
| `GET /rest/api/1.0/admin/license` | License info — useful for "is this Bitbucket Server or Data Center?" gating |
| `GET /rest/api/1.0/projects/.../repos/.../pull-requests-count` (if it exists) | Cheap counter for dashboards |
| `GET /rest/api/1.0/projects/.../repos/.../tags` | Tag list for "compare with branch X" features |
| `GET /rest/api/1.0/projects/.../repos/.../files/.../{path}?at=` | File-content alternative to `/browse?raw` (different output shape) |
| `GET /rest/api/1.0/projects/.../repos/.../webhooks` | Discover existing webhooks (could help debug "why aren't builds triggering?") |
| `GET /rest/api/1.0/projects/.../repos/.../labels` | Labels per repo (newer Bitbucket DC) |
| `GET /rest/build-status/2.0/commits/{commitId}` | Bitbucket DC 8 added build-status v2 — richer schema with `parent`, `testResults`. Plugin uses v1 today. |
| `GET /rest/api/1.0/projects/.../repos/.../pull-requests/{prId}/blocker-comments` | Blocking comments only — would let the plugin show "this PR has 2 blockers" without scanning all comments |
| `GET /rest/insights/1.0/projects/.../repos/.../commits/{sha}/reports` | Code Insights API (DC 7+) — surfaces SonarQube findings on PRs |

### Internal endpoint to verify

`/rest/build-status/1.0/commits/{commitId}` is documented but used in fewer places than the v2 form. Probe both.

---

## 5. Step-by-step plan for this session

```bash
# 1. Pull latest (Jira commit should already be on this branch)
# 2. Inventory + write probe driver
#    Mirror probe_jira.py:
#      • run_versions_only() — /application-properties + admin/license
#      • run_discover() — derive project/repo from user's git remotes via /users + /projects
#      • run_full() — exercise every endpoint in §3 + every candidate in §4

# 3. Hand the user the discover command:
#    python probe_bitbucket.py --url https://bb.company --token YOUR_PAT --discover

# 4. User picks values, runs full sweep, redacts + bundles, pastes summary back

# 5. Compile docs/research/2026-05-06-bitbucket-recommendations.md (same shape as Jira's)

# 6. Get user approval

# 7. Implement in ONE commit:
#    - Apply approved endpoint swaps in BitbucketBranchClient
#    - Wire any approved new-feature paths through the existing service interfaces
#    - Update :core/CLAUDE.md and :pullrequest/CLAUDE.md if the surface changes
#    - ./gradlew :core:test :pullrequest:test verifyPlugin before commit

# 8. Push. Done.
```

Commit message: `feat(bitbucket): adopt validated endpoints (DC-only)` — no Co-Authored-By trailer (memory `feedback_no_coauthor.md`).

---

## 6. User constraints — already in memory

Same as Jira: Server / Data Center only, read-only probes, one commit per service, autonomous on architecture / consult on UI mockups, work on **this branch** (`fix/automation-handover-quality-tabs`). The user's Windows laptop will run the probe with their personal Bitbucket PAT.

---

## 7. Things NOT to touch in this session

- Jira / Nexus modules
- The `:agent` module's Bitbucket tool implementations (they delegate to the client; if recommendations cascade into them, that's part of the implementation commit, but no proactive changes)
- `AuthTestService` — its Bitbucket branch (`/rest/api/1.0/users`) is intentionally isolated like the Jira one
- The `agent/src/...` dirty WIP files in the user's working tree

---

## 8. Out-of-scope for this audit (defer or document)

- **Bitbucket Cloud** — the user explicitly said Server-only for now. If something is Cloud-only (e.g. `/rest/api/2.0/`), document it but do not adopt.
- **Webhook management UI** — even if `/webhooks` probes 200, building a webhook UI is a feature, not an audit fix. Note the capability and stop.
- **Code Insights** if it returns 200 — adopting it requires Sonar integration choices that belong to the Quality tab, not the PR module. Note + defer.
