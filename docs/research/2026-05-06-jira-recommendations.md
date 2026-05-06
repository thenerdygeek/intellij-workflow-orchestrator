# Jira API recommendations — keep / swap / add

**Date:** 2026-05-06
**Branch:** `fix/automation-handover-quality-tabs`
**Scope:** Step 1 of 3 in the Jira → Bitbucket → Nexus audit. Server-only (Jira 10.3.16 Data Center).
**Inputs:**
- `docs/research/2026-05-06-jira-api-call-site-inventory.md` — every UI/agent feature mapped to its endpoint.
- `tools/atlassian-probe/Result_Jira/bundle.unpacked/summary.md` — 43 probes; 39 ✅, 4 ❌.
- `tools/atlassian-probe/Result_Jira/bundle.unpacked/raw/*.json` — full bodies for shape verification.

This doc decides what to **adopt** in the implementation commit. Each row is *Keep / Swap-for-X / Add-Y / Risk*. Items are independently selectable — the user can pick a subset before we touch code.

---

## 0a. Verification status (post-followup probe)

The 5 follow-up probes A–E (commit `e2870234` mode + bundle `bundle-audit-followup.txt`) all returned **200**. Implementation shapes confirmed:

| Probe | R-* | Verified shape | Implementation note |
|---|---|---|---|
| A | R-SWAP-1 | `POST /rest/api/2/search` returns `{startAt, maxResults, total, issues:[{key, fields:{summary, status}}], warningMessages, names, schema}` — **same shape as GET** | Existing `JiraIssueSearchResult` parser works unchanged. Pure verb swap. |
| B | R-SWAP-2 | `GET /rest/api/2/issue/picker?query=PROJ3-` returned 1 section: `{label:"History Search", id:"hs", sub:"Showing 19 of 24 matching issues", issues:[{key, keyHtml, img, summary, summaryText}, …]}`. **`currentSearch` section did NOT appear for prefix queries** — only `History Search`. | Render whatever sections come back; don't index on a specific section id. Use `summaryText` (plain) for the suggestion label. `keyHtml` contains `<b>` markup — only useful if we add HTML rendering. |
| C | R-ADD-1 | `GET /rest/api/2/mypermissions?projectKey=…` — same `{permissions:{KEY:{…, havePermission, deprecatedKey?}}}` shape as the global call, with permissions reflecting that project's ACLs. | Cache 5 min per project. Read modern keys (`EDIT_ISSUES`); ignore entries with `deprecatedKey:true` to avoid double-counting. |
| D | R-ADD-7 | `GET /rest/api/2/filter/{id}` returns `{id, name, description, owner, jql, viewUrl, searchUrl, favourite, sharePermissions, editable, sharedUsers, subscriptions}` | The **`jql` field** is the migration target — feed it into the existing `searchIssues(jql)` after the user clicks a favourite. |
| E | R-ADD-3 polish | `GET /rest/api/2/issue/{key}?fields=summary,description,status,priority,issuetype,assignee,reporter,labels,components,fixVersions,comment&expand=renderedFields,changelog` returned **fields + comments + changelog in one call** | Detail panel can drop two of the three current calls (rich fetch + comments + changelog → single fetch). Comments live at `fields.comment.comments[]`. |

All FIX / ARCH / SWAP / RISK rows are now green. ADD rows are unblocked.

## 0b. Redactor follow-ups (non-blocking)

The follow-up bundle exposed three more leak patterns — flagged here so they can be fixed in `tools/atlassian-probe/redact.py` before the Bitbucket / Nexus bundles are produced:

1. **Issue-picker `summaryText`** — ticket descriptions pass through un-redacted ("[Atomx] expired event pass …"); the adjacent `summary` field at the same level *was* redacted. Field-name heuristic mismatch.
2. **Filter `name`** — saved-filter names containing personal identifiers ("Gangadhar_M2GO_Sprint_…") pass through un-redacted in both `/filter/favourite` and `/filter/{id}` responses.
3. **JQL keyword over-redaction** — inside `filter.jql`, the words `AND` and `Sprint` were swapped with random placeholders ("XUY Zyghqv"). Custom-word matcher treats JQL keywords as redactable. Inverse problem to (1)–(2).

Bonus: the `R-*` IDs the script emits in `notes` text (e.g. `R-SWAP-1`) get rewritten to `R-PROJ2-035` etc. for the same reason. Cosmetic.

These are deferred to a separate tooling commit; they don't change the Jira recommendations.

## 0. Headline findings

1. **Latent bug confirmed.** `JiraSearchServiceImpl.searchUsers` (`/rest/api/2/user/search?query=…`) returns 400 on Server because DC requires `username=`, not `query=`. The transition dialog's generic user-picker has been broken on Server. Fix is one parameter rename. (See R-FIX-1.)
2. **Architectural gap confirmed.** `jira/tasks/JiraTaskRepository.kt:32` builds its own `OkHttpClient`. Three rogue endpoints. Unification is straightforward — the rich fetch and `/myself` already live on `JiraApiClient`. (See R-ARCH-1.)
3. **No v3 search migration.** `POST /rest/api/3/search/jql` returns 302 → `/login.jsp?permissionViolation=true` on this DC. Not backported. **Stay on v2.** Don't chase Cloud forward-compat.
4. **Long-JQL fix available.** `POST /rest/api/2/search` works (39ms, returned 3.6M-issue index instantly). Migrate `validateTicketKeys` POST to drop the `chunked(100)` URL-length workaround. (See R-SWAP-1.)
5. **Mention-search rewrite available.** `GET /rest/api/2/issue/picker` works. Replaces the agent's manual board+sprint walk. Smaller code, better suggestions. (See R-SWAP-2.)
6. **5 cheap new features unlocked**, all 200-OK on this DC: `mypermissions`, `field`-discovery, `changelog`-expand, `remotelink`, `watchers`, `filter/favourite`, expanded `myself`. (See R-ADD-* below.)
7. **Redactor leak — non-blocking, flag for future runs.** The bundle's `filter_favourite.json` ships filter `name` values un-redacted (e.g. `"Subhankar_M2GO_Sprint_…"`). Free-text values inside JSON arrays-of-objects bypass the current redactor heuristic. Doesn't change the recommendations but worth a follow-up commit on `tools/atlassian-probe/redact.py` before Bitbucket/Nexus runs.

---

## 1. Per-call-site decision table

Format: `R-<KIND>-<N>` IDs let us approve subsets. KIND ∈ KEEP / FIX / SWAP / ADD / ARCH / RISK.

| ID | Plugin call site | Today's endpoint | Probe | Decision | Effort |
|---|---|---|---|---|---|
| **R-KEEP-1** | `JiraTaskRepository.getTask` (after R-ARCH-1) | `GET /rest/api/2/issue/{id}` | ✅ 200, 279ms | Keep endpoint | — |
| **R-KEEP-2** | `JiraServiceImpl.getTicket` (rich fetch) | `GET /rest/api/2/issue/{key}?fields=…&expand=renderedFields` | ✅ 200, 209ms | Keep | — |
| **R-KEEP-3** | `JiraApiClient.getTransitions` | `GET /rest/api/2/issue/{key}/transitions?expand=transitions.fields` | ✅ 200, 238ms | Keep — capture `autoCompleteUrl` per field as live | — |
| **R-KEEP-4** | `JiraApiClient.getComments` | `GET /rest/api/2/issue/{key}/comment?orderBy=-created` | ✅ 200, 255ms | Keep | — |
| **R-KEEP-5** | `JiraApiClient.getWorklogs` | `GET /rest/api/2/issue/{key}/worklog` | ✅ 200, 201ms | Keep | — |
| **R-KEEP-6** | `JiraSearchService.searchAssignableUsers` | `GET /rest/api/2/user/assignable/search?issueKey={k}&query=` | ✅ 200, 301ms | Keep | — |
| **R-KEEP-7** | `JiraSearchService.searchGroups` | `GET /rest/api/2/groups/picker` | ✅ 200, 206ms | Keep | — |
| **R-KEEP-8** | Project versions/components (5min cached) | `GET /rest/api/2/project/{k}/{versions\|components}` | ✅ 200, 203/321ms | Keep | — |
| **R-KEEP-9** | Boards / sprints / sprint-issues / kanban-issues | `GET /rest/agile/1.0/…` | ✅ all 200 | Keep | — |
| **R-KEEP-10** | Labels suggest (with 404→empty fallback) | `GET /rest/api/1.0/labels/suggest?query=` | ✅ 200, 556ms (today) — fallback still required | Keep with existing fallback | — |
| **R-KEEP-11** | DevStatus all 6 dataTypes (branch, pr, repository, build, deployment, review) | `GET /rest/dev-status/1.0/issue/detail?…` | ✅ all 200, 214–435ms | Keep — risk register unchanged | — |
| **R-FIX-1** | `JiraSearchService.searchUsers` (generic user picker) | `GET /rest/api/2/user/search?query=…` ❌ 400 on Server | ❌ 400 — Server requires `username=` | **FIX**: rename param `query=` → `username=` in `JiraSearchServiceImpl.searchUsers` line 122. Verify via existing `JiraSearchServiceImplTest.searchUsers_*` (test asserts path startsWith `/rest/api/2/user/search` — needs an assertion update on the param too). | S |
| **R-ARCH-1** | `JiraTaskRepository` (3 calls bypass funnel) | self-built `OkHttpClient` | n/a — architecture | **MIGRATE**: take a `JiraServiceImpl` (or expose `JiraApiClient.testMyself()`). Replace `/issue/{id}`, `/search`, `/myself` with `JiraService.getTicket`, `JiraService.searchIssues`, `JiraService.testConnection`. Keeps auth interceptor + retry + content-type guard. Tests: `JiraTaskRepositoryTest` already exists per repo conventions — wire the same fake. | M |
| **R-SWAP-1** | `JiraApiClient.validateTicketKeys` (BranchChangeTicketDetector + TicketKeyCache) | `GET /rest/api/2/search?jql=key in (…)` chunked(100) | ✅ POST 200, 482ms (initial probe) / 287ms (followup with realistic body) — same shape as GET | **SWAP** to `POST /rest/api/2/search` with body `{"jql":"key in (…)","fields":["summary","status"],"maxResults":N}`. Drop the `chunked(100)` loop. Single round-trip for any batch size. Existing `JiraIssueSearchResult` parser unchanged. | S |
| **R-SWAP-2** | `MentionSearchProvider.searchTickets` (manual board+sprint walk) | various agile/board endpoints + filter | ✅ `GET /rest/api/2/issue/picker?query=…&showSubTasks=true` 200, 413ms (key-prefix). Followup confirmed: 24 matches in `History Search` section. **Only `History Search` returned for key-prefix queries — `currentSearch` did NOT appear**. | **SWAP** to issue-picker: render whichever sections the response contains (don't index on `id == "hs"` / `"cs"`). Use `summaryText` for the dropdown label. Keep the per-board cache only as a *prewarm* hint. | M |
| **R-ADD-1** | New: `JiraService.getMyPermissions(projectKey?)` | `GET /rest/api/2/mypermissions[?projectKey=…]` ✅ 200, 308ms | **ADD** | Gate `Transition` button on `TRANSITION_ISSUES`, `Comment` on `ADD_COMMENTS`, `Log work` on `WORK_ON_ISSUES`, `Watch` on `VIEW_VOTERS_AND_WATCHERS`. Cache 5min per project. Note: payload returns both deprecated (`EDIT_ISSUE`) and modern (`EDIT_ISSUES`) keys — read modern, ignore `deprecatedKey:true` entries. | M |
| **R-ADD-2** | New: `JiraService.discoverFields()` for acceptance-criteria custom field | `GET /rest/api/2/field` ✅ 200, 229ms | **ADD** | Replace the manual `customfield_10001` setting with a "Pick acceptance-criteria field" dropdown that lists all `custom:true` fields by name. Onboarding win — the plugin works on any Jira instance without per-customer config. | M |
| **R-ADD-3** | New: changelog feed in `TicketDetailPanel` | `GET /rest/api/2/issue/{key}?fields=…,comment&expand=renderedFields,changelog` ✅ 200, 605ms (combined) | **ADD** | Combine into the existing rich-fetch call (one round trip instead of three). Render last N status/assignee/priority changes from `changelog.histories[].items[]` as chips. Comments come back inline at `fields.comment.comments[]`. | M |
| **R-ADD-4** | New: `LinkedDocsSection` in `TicketDetailPanel` | `GET /rest/api/2/issue/{key}/remotelink` ✅ 200, 277ms | **ADD** | Render Confluence backlinks (`application.type == com.atlassian.confluence`) + generic external links. Click to open in browser. The probe ticket already has 2 Confluence backlinks — concrete value. | M |
| **R-ADD-5** | New: "Watch this ticket" toggle | `GET/POST/DELETE /rest/api/2/issue/{key}/watchers` ✅ GET 200, 205ms | **ADD** | Toggle button in `TicketDetailPanel` header. POST `"<username>"` (string body) to add, DELETE with `?username=…` to remove. Use `isWatching:false` flag from GET to render initial state. | S |
| **R-ADD-6** | New: extended self info on the onboarding banner | `GET /rest/api/2/myself?expand=groups,applicationRoles` ✅ 200, 199ms | **ADD** | Show user's group memberships + application roles in the Setup dialog confirmation step ("Connected as <name> · Member of: dev, jira-users, …"). Helps the user verify permissions without leaving the IDE. | S |
| **R-ADD-7** | New: favourite filters in Sprint tab | `GET /rest/api/2/filter/favourite` ✅ 200, 220ms; `GET /rest/api/2/filter/{id}` ✅ 200, 270ms (returns full `jql` + `name` + `viewUrl`) | **ADD** | New "Saved filters" section above the active-sprint list. Click a filter → fetch detail → feed `jql` into existing `searchIssues`. **Note:** `/filter/search` returned 404 on this DC, so we cannot offer arbitrary filter search — favourites only. | M |
| **R-RISK-1** | `HttpClientFactory.followRedirects=true` masks 302→login as success | n/a — config | n/a | **REVIEW**: when an endpoint disappears or auth expires, Jira responds 302 to `/login.jsp?permissionViolation=true`. With `followRedirects=true` the plugin would fetch the login page (HTML) and likely crash on JSON parse — but for endpoints that return `Content-Type: text/html` for the login page, this could surface as a confusing parse error rather than an auth error. Probe confirmed the 302 path. *Decision*: add a check in `JiraApiClient` — if response `Content-Type` starts with `text/html`, treat as `ApiResult.Error("not authenticated")`. Sidecar to the architectural commit. | S |

**Skipped — not viable on this DC:**
- ❌ `POST /rest/api/3/search/jql` (302 → not backported)
- ❌ `POST /rest/api/2/search/approximate-count` (404 → not backported) — no Sprint badge counter feature
- ❌ `GET /rest/api/2/filter/search` (404 → not backported) — only `/filter/favourite` available

---

## 2. Proposed implementation slice (after user approval)

Single commit on `fix/automation-handover-quality-tabs`:

1. **R-FIX-1** — one-line param rename + one test assertion. (Smallest blast radius, biggest value.)
2. **R-ARCH-1** — `JiraTaskRepository` rewritten on top of `JiraServiceImpl`. Existing `JiraTaskRepositoryTest` updated.
3. **R-SWAP-1** — `validateTicketKeys` switched to POST + `chunked(100)` removed; existing `JiraApiClientTest` for validateTicketKeys updated.
4. **R-SWAP-2** — `MentionSearchProvider.searchTickets` rewritten on top of issue-picker; old board-walk path deleted.
5. **R-ADD-1..7** — each behind its own service method + UI hook. Each independently revertable.
6. **R-RISK-1** — `JiraApiClient` HTML-content-type guard.
7. **`:jira/CLAUDE.md` + `:core/CLAUDE.md`** — endpoint table refreshed.
8. **Build:** `./gradlew :jira:test :core:test verifyPlugin`.

Commit message:
```
feat(jira): unify HTTP funnel and adopt validated endpoints (Server-only)

R-FIX-1   user-search param rename query=→username= (Server bug)
R-ARCH-1  JiraTaskRepository → JiraApiClient
R-SWAP-1  validateTicketKeys → POST /rest/api/2/search (drop chunking)
R-SWAP-2  MentionSearchProvider → /rest/api/2/issue/picker
R-ADD-1   permissions-aware UI gating
R-ADD-2   custom-field discovery for acceptance criteria
R-ADD-3   changelog feed in detail panel
R-ADD-4   remote-link section in detail panel
R-ADD-5   watch-this-ticket toggle
R-ADD-6   onboarding banner: groups + application roles
R-ADD-7   favourite-filters in Sprint tab
R-RISK-1  HTML-response auth-error mapping in JiraApiClient
```

(Final R-* set is whatever the user approves.)

---

## 3. What's NOT in this commit

- Bitbucket / Nexus / Sonar / Bamboo / Automation modules (separate sessions).
- `core/auth/AuthTestService.kt` — kept isolated (memory: `feedback_auth_test_isolation.md`).
- Probe tooling changes (`tools/atlassian-probe/`) — already shipped on this branch.
- Redactor filter-name leak fix — flagged here, deferred to a tooling commit before the Bitbucket run.
- Dirty working-tree files (`agent/**`) — unrelated WIP.

---

## 4. Awaiting user decision

Please confirm which R-* IDs to adopt. Defaults I'd recommend:
- **All of R-FIX-1, R-ARCH-1, R-SWAP-1, R-RISK-1** — bug, architecture, perf, hardening.
- **R-SWAP-2, R-ADD-3, R-ADD-4, R-ADD-5** — high value, low risk.
- **R-ADD-1, R-ADD-2** — onboarding/UX wins, slightly larger because they touch settings UI.
- **R-ADD-6, R-ADD-7** — nice-to-haves; happy to defer if scope feels large.

Per memory `feedback_architecture_autonomy.md`: I'll proceed autonomously on architecture (FIX/ARCH/SWAP/RISK) once you say "go." For the new features (ADD-*) which add UI surfaces, please tell me which to include before I start.

---

## 5. Audit follow-up probes — how to run

Five gaps in the existing bundle don't block the recommendations but **do** block writing the migration code with confidence. They've been wired into `tools/atlassian-probe/probe_jira.py` as a new `--audit-followup` mode. Read-only.

| Probe | Verifies | Affects |
|---|---|---|
| A — `POST /rest/api/2/search` with `key in (…)` body + `fields` whitelist | Exact body shape `validateTicketKeys` would post | R-SWAP-1 |
| B — `GET /rest/api/2/issue/picker?query={proj}-` (key-prefix, not `query=test`) | Mention search with realistic input | R-SWAP-2 |
| C — `GET /rest/api/2/mypermissions?projectKey=…` | Project-scoped permission shape | R-ADD-1 |
| D — `GET /rest/api/2/filter/favourite` → pick first id → `GET /rest/api/2/filter/{id}` | Saved-filter detail incl. JQL | R-ADD-7 |
| E — `GET /rest/api/2/issue/{key}?fields=…&expand=renderedFields,changelog` | Combined-expand for single-call detail panel | R-ADD-3 polish |

```bat
:: Windows cmd
python probe_jira.py --url <YOUR_JIRA_URL> --token <YOUR_PAT> ^
    --audit-followup ^
    --issue-key PROJ2-030 ^
    --project-key PROJ
```

```bash
# PowerShell / Unix
python probe_jira.py --url <YOUR_JIRA_URL> --token <YOUR_PAT> \
    --audit-followup \
    --issue-key PROJ2-030 \
    --project-key PROJ
```

Optional: pass `--filter-id <N>` to target a specific saved filter for probe D. Without it, the script fetches `/filter/favourite` and uses the first hit.

After running:
1. `python redact.py --in Result_<N>/ --out Result_Jira_followup/` (same custom-word list as before; SystemRandom mapping is per-run so it won't collide with the prior bundle's placeholders).
2. `python bundle.py pack --in Result_Jira_followup/ --out Result_Jira/bundle_followup.txt --compress`.
3. Paste the bundle path back — I update §1 with the verified payload shapes, then we proceed with the implementation commit.
