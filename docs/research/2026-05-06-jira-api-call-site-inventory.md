# Jira API call-site inventory & audit

**Date:** 2026-05-06
**Branch:** fix/automation-handover-quality-tabs
**Scope:** Step 1 of 3 in the Jira → Bitbucket → Nexus API audit & probe sequence
**Server flavour:** Atlassian Data Center / Server only (Cloud is out of scope)

---

## 1. Architecture today

```
                   ┌──────────────────────────────────┐
                   │  JiraApiClient (single OkHttp)   │
                   │  baseUrl + Bearer-PAT auth       │
                   │  ApiResult<T> + status mapping   │
                   └──────────────┬───────────────────┘
                                  │
        ┌─────────────────────────┼──────────────────────────────┐
        ▼                         ▼                              ▼
JiraServiceImpl           JiraSearchServiceImpl          TicketTransitionServiceImpl
(implements                (raw-JSON wrappers for         (transitions cache
 :core JiraService)         user/label/component/        + emits TicketTransitioned)
                            version/group/autocomplete)
        ▼                         ▼                              ▼
   Sprint tab,            Transition dialog                Quick-transition flows
   Detail panel,          field widgets                    (TicketDetailPanel, agent)
   Branching,
   Active-ticket,
   Commit-message,
   Agent JiraTool,
   Mention search,
   PR module
```

**Funnel principle:** every Jira HTTP call **should** go through `JiraApiClient`. Two known deviations:

| File | Status | Reason |
|---|---|---|
| `core/auth/AuthTestService.kt` | **Keep isolated** | Documented in memory `feedback_auth_test_isolation.md` — needs `followRedirects(false)`, 10s timeouts, user-typed token-as-data; factory semantics incompatible. Do not migrate. |
| `jira/tasks/JiraTaskRepository.kt` | **Architectural gap** | Builds its own `OkHttpClient` (line 32) and calls `/rest/api/2/issue/{id}`, `/rest/api/2/search`, `/rest/api/2/myself` directly. Should funnel through `JiraApiClient`. **Action: migrate in implementation commit.** |

---

## 2. UI surface → API endpoint → user requirement

### Sprint tab (`SprintDashboardPanel`, `SprintService`, `MentionSearchProvider`)

| User action / feature | Endpoint(s) | Notes |
|---|---|---|
| Show "active sprint + my tickets" | `GET /rest/agile/1.0/board/{boardId}/sprint?state=active` then `GET /rest/agile/1.0/sprint/{sprintId}/issue?jql=assignee=currentUser()` | Two-call cascade. Agile API. |
| Kanban mode (no active sprint) | `GET /rest/agile/1.0/board/{boardId}/issue?jql=resolution=Unresolved AND assignee=currentUser()` | |
| List boards (board-picker) | `GET /rest/agile/1.0/board?type={scrum\|kanban}&name={filter}&maxResults=200` | |
| Past sprints pagination | `GET /rest/agile/1.0/board/{boardId}/sprint?state=closed&startAt&maxResults` | |
| Search Everywhere ticket search | `GET /rest/api/2/search?jql={text~text}&fields=summary,status,issuetype,priority,assignee` | |
| @-mention ticket search (agent) | Same as above, plus per-board scoped variants | `MentionSearchProvider:413+` |

### Ticket detail panel (`TicketDetailPanel`, `DevStatusSection`, `WorklogSection`, `QuickCommentPanel`)

| User action / feature | Endpoint(s) |
|---|---|
| Open a ticket | `GET /rest/api/2/issue/{key}?expand=issuelinks` |
| Detail panel rich fetch (LLM context) | `GET /rest/api/2/issue/{key}?fields=summary,description,status,priority,issuetype,assignee,reporter,labels,components,fixVersions,comment&expand=renderedFields` |
| Custom field (acceptance criteria) | `GET /rest/api/2/issue/{key}?fields={customFieldId}` |
| List comments | `GET /rest/api/2/issue/{key}/comment?maxResults=50&orderBy=-created` |
| Add comment (Quick Comment) | `POST /rest/api/2/issue/{key}/comment` |
| Worklog list | `GET /rest/api/2/issue/{key}/worklog?maxResults=20` |
| Log time | `POST /rest/api/2/issue/{key}/worklog` |
| Dev-status (branches, PRs, commits, builds, deploys, reviews) | `GET /rest/dev-status/1.0/issue/detail?issueId={numericId}&applicationType=stash&dataType={branch\|pullrequest\|repository\|build\|deployment\|review}` ⚠ INTERNAL |

### Transition dialog (`TicketTransitionDialog`, `JiraSearchService`, `TicketTransitionService`)

| User action / feature | Endpoint(s) |
|---|---|
| List available transitions w/ field schema | `GET /rest/api/2/issue/{key}/transitions?expand=transitions.fields` |
| Execute transition (with optional fields + comment) | `POST /rest/api/2/issue/{key}/transitions` |
| Assignee picker | `GET /rest/api/2/user/assignable/search?issueKey={key}&query={q}` |
| Generic user picker | `GET /rest/api/2/user/search?query={q}&maxResults` |
| Label suggestions | `GET /rest/api/1.0/labels/suggest?query={q}` ⚠ INTERNAL — code already has 404→empty fallback |
| Group picker | `GET /rest/api/2/groups/picker?query={q}&maxResults` |
| Versions picker (5min cached) | `GET /rest/api/2/project/{key}/versions` |
| Components picker (5min cached) | `GET /rest/api/2/project/{key}/components` |
| Field-level autocomplete (whatever the field's `autoCompleteUrl` says) | Whatever URL Jira returns in transition field metadata |

### Start Work flow (`BranchingService`, `StartWorkDialog`)

| User action / feature | Endpoint(s) |
|---|---|
| Validate ticket exists | `GET /rest/api/2/issue/{key}?expand=issuelinks` |
| (Combined with Bitbucket branch-create — Bitbucket side covered in step 2) |  |
| Optional: transition ticket on branch-create | `POST /rest/api/2/issue/{key}/transitions` |

### Branch-detection popup (`BranchChangeTicketDetector`, `TicketKeyCache`)

| User action / feature | Endpoint(s) |
|---|---|
| Validate one or many ticket keys (batch) | `GET /rest/api/2/search?jql=key in (KEY1,KEY2,...)&maxResults={n}&fields=summary,status` (chunks of 100) |

### Commit message generation (`core/vcs/GenerateCommitMessageAction`, `JiraTicketProvider`)

| User action / feature | Endpoint(s) |
|---|---|
| Fetch context for "Generate commit message" action | `getTicketContext(key)` → `getIssueWithContext(key)` (rich fetch above) — runs in parallel for each candidate ticket |

### Handover (`handover/ui/HandoverTabProvider`)

| User action / feature | Endpoint(s) |
|---|---|
| Post Jira closure comment | `POST /rest/api/2/issue/{key}/comment` |
| Read ticket for closure context | `GET /rest/api/2/issue/{key}?expand=issuelinks` |

### IntelliJ Tasks integration (`jira/tasks/JiraTaskRepository`) — **bypasses JiraApiClient**

| User action / feature | Endpoint(s) | Notes |
|---|---|---|
| Resolve task by id | `GET /rest/api/2/issue/{id}` | Direct OkHttp |
| List tasks (`jql=assignee=currentUser() AND resolution=Unresolved`) | `GET /rest/api/2/search?jql&startAt&maxResults` | Direct OkHttp |
| Test connection | `GET /rest/api/2/myself` | Direct OkHttp |

### Pull-request module (`pullrequest/action/CreatePrPrefetch`, `pullrequest/ui/TicketChipInput`)

| User action / feature | Endpoint(s) |
|---|---|
| Pre-fetch ticket info for PR-create dialog | `getIssueWithContext(key)` (rich fetch) |
| Ticket chip search/validation | `searchIssues(text)` + `getTicket(key)` |

### Auto-detect / Onboarding (`core/onboarding/SetupDialog`, `core/autodetect/AutoDetectOrchestrator`)

| User action / feature | Endpoint(s) |
|---|---|
| Auth-test on URL+token form | `GET /rest/api/2/myself` (via `AuthTestService` — kept isolated) |

### Agent — Jira meta-tool (`agent/tools/integration/JiraTool`)

17 actions, all of which delegate to `JiraServiceImpl` → `JiraApiClient`. The agent has the broadest API surface coverage of any consumer. New agent actions should NOT do their own HTTP — extend `JiraService`/`JiraApiClient` first.

```
get_ticket | get_transitions | transition | comment | get_comments
log_work | get_worklogs | get_sprints | get_linked_prs | get_boards
get_sprint_issues | get_board_issues | search_issues | search_tickets
get_dev_branches | start_work | download_attachment
```

### Agent — `@`-mention (`agent/ui/MentionSearchProvider`, `MentionContextBuilder`)

| User action / feature | Endpoint(s) |
|---|---|
| `@` ticket search in chat input | `getBoards`, `getAvailableSprints`, `getSprintIssues`/`getBoardIssues` (cached); `getTicket(key)` to validate; `getComments(key)` for ticket-with-context |

---

## 3. Risk register (endpoints we depend on)

| Endpoint | Risk | Why |
|---|---|---|
| `/rest/dev-status/1.0/issue/detail` | **HIGH — INTERNAL** | Atlassian-internal API; powers Jira's own Dev Panel; stable since 7.x but unsupported. If it breaks, the Dev Status section + agent's `get_linked_prs`/`get_dev_branches`/etc all break silently. Probe must explicitly test all 6 `dataType` values. |
| `/rest/api/1.0/labels/suggest` | **MEDIUM — INTERNAL** | Code already swallows 404 and returns empty list. If this disappears, label-typeahead in transitions degrades to free-form input. Safe degradation. |
| Field `autoCompleteUrl` (returned in transition metadata) | LOW | Jira itself tells us the URL — nothing to verify pre-emptively. Probe should *capture and report* the URLs returned for a real transition response. |
| `/rest/api/2/search` (JQL v1) | **LOW today / MEDIUM future** | Cloud deprecated this May-2024 in favour of `/rest/api/3/search/jql` (POST + cursor pagination + `nextPageToken`). DC has not migrated, but worth probing whether the v3 endpoint exists on the user's instance — would inform a migration plan. |

---

## 4. Architectural unification gap

**Single concrete gap:** `jira/tasks/JiraTaskRepository.kt` builds its own `OkHttpClient` and bypasses `JiraApiClient`. Three rogue calls.

**Implementation-commit action:** rewrite `JiraTaskRepository` to take a `JiraServiceImpl` (or `JiraApiClient`-equivalent helper) so:
- The 3 paths (`/rest/api/2/issue/{id}`, `/rest/api/2/search`, `/rest/api/2/myself`) share the funnel's auth interceptor, retry, content-type guard, and error mapping.
- Future cross-cutting changes (e.g. swap to `/rest/api/3/search/jql`, add `Accept-Language`, etc.) propagate.

`AuthTestService` stays isolated by design — already memorialized.

---

## 5. Candidate recommendations to validate via probe

Endpoints **not** currently used that the probe should test for availability + payload shape:

| Endpoint | Why probe / Potential feature |
|---|---|
| `GET /rest/api/2/serverInfo` | **Version detection mode**. Returns `version`, `versionNumbers[]`, `buildNumber`, `deploymentType`, `buildDate`. |
| `GET /rest/api/2/mypermissions` | Surface whether the user can transition/edit/comment a given ticket → gate UI buttons proactively. |
| `GET /rest/api/2/myself?expand=groups,applicationRoles` | Show user's groups/roles in onboarding banner, debug "why can't I see X". |
| `GET /rest/api/2/issue/{key}?expand=changelog` | Ticket history feed in detail panel ("last status change 3 days ago"). |
| `GET /rest/api/2/field` | Discover all custom fields once → drop the manual `customfield_10001` config for acceptance criteria; let user pick from a list. |
| `GET /rest/api/2/filter/search?filterName=` (or `/filter/favourite`) | Let user pick a saved JQL filter in Sprint tab instead of board-only. |
| `GET /rest/api/2/issue/{key}/watchers` + `POST /watchers` | "Watch this ticket" toggle in detail panel (low effort, real value). |
| `GET /rest/api/3/search/jql` (POST + token) | Forward-compatibility check: does the user's DC have the new JQL endpoint? If yes, plan a migration. |
| `POST /rest/api/2/search` (POST body instead of URL JQL) | Avoid URL-length issues with batch validation when keys list is huge. |
| `GET /rest/api/2/issue/createmeta?projectKeys={k}&issuetypeNames={t}&expand=projects.issuetypes.fields` | Power a richer Start Work / "create subtask" flow without manual field discovery. |
| `GET /rest/api/2/issue/{key}/remotelink` | Show external links (Confluence pages, build URLs) in detail panel. |
| `GET /rest/api/2/serverInfo` `/healthcheck` if available | Connectivity dashboard. |

---

## 6. Probe deliverables (tools/atlassian-probe/probe_jira.py)

The probe must be **read-only**. Modes:

- `--versions-only` → just `/serverInfo` + `/myself` + `/mypermissions`. Quick output to determine the user's Jira version before deciding which endpoint set to recommend.
- Default (full read sweep) → exercises every endpoint in §2 with realistic params, plus every candidate in §5.
- Pure-GET only. **No** transition execution, **no** comment posting, **no** worklog posting, **no** branch creation, **no** field mutations — even if the user provides `--issue-key`.

Output: `tools/atlassian-probe/Result_N/{summary.md, raw/<endpoint>.json}` (auto-incremented N), mirroring `tools/sourcegraph-probe/`.

---

## 7. Next-step checklist (for implementation commit, after probe results)

1. Migrate `JiraTaskRepository` to `JiraApiClient`.
2. Apply approved endpoint swaps from probe results (e.g., v2 → v3 search if available).
3. Wire approved new features (e.g., `mypermissions` gating, `field` discovery for acceptance-criteria custom field, `changelog` in detail panel).
4. Update `:jira` and `:core` `CLAUDE.md` to reflect any new endpoints.
5. Build + verifyPlugin.
6. Single commit: `feat(jira): unify HTTP funnel and adopt validated endpoints (Server-only)`.
