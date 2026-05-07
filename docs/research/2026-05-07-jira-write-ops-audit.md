# Jira Write Ops Audit — 2026-05-07

Read-only audit of every Jira-mutating call across `:jira`, `:handover`, `:agent`. Cross-references the on-disk probe at `tools/atlassian-probe/Result_Jira/bundle.unpacked/` (Jira DC 10.3.16, Bearer auth).

---

## 1. Executive summary

**Counts**
- CORRECT: 5 (`addWatcher`, `removeWatcher`, `addComment`, `postWorklog`, `transitionIssue`-shape)
- WRONG-SHAPE: 0 — every mutating body decoded by the probe maps to what the server documented.
- MISSING-PREFLIGHT: 1 — `transitionIssue` execute path does not always re-fetch with `expand=transitions.fields` before sending.
- MISSING-UX: 4 — `JiraCommentPanel` (handover), `QuickCommentPanel`, `TimeLogPanel` (handover), and `start_work` auto-transition all skip the per-action metadata fetch the native Jira UI does.
- NOT-EXPOSED-AS-UI: 2 — `addWatcher` / `removeWatcher` are implemented in `JiraApiClient` and `JiraServiceImpl` but no UI dialog drives them today (they're a future toggle).

**Top 3 P0/P1 findings**

1. **P1 — `start_work` auto-transition silently violates workflow rules.** `JiraServiceImpl.startWork` (`jira/.../service/JiraServiceImpl.kt:650`) finds the first transition whose `toStatus.name == "In Progress"` and POSTs it with `fieldValues = emptyMap()`. The probe (`raw/issue_transitions.json`, transition id `211 → "In Progress"`) shows that transition's screen carries `assignee` (autoCompleteUrl) and `timetracking` — both currently optional, but if a project admin marks `assignee.required = true`, the silent path 400s without surfacing the field. The branch is created, the ticket is not transitioned, and the user is left in an inconsistent state. The `TicketTransitionService` MissingFields preflight is **bypassed**: `startWork` calls `api.transitionIssue` directly, not `TicketTransitionService.executeTransition`.
2. **P1 — Handover `JiraCommentPanel` and `:jira` `QuickCommentPanel` post wiki-markup blindly with no visibility/role picker.** Native Jira comment dialog renders a "Restrict comment to" combo populated by `/rest/api/2/issue/{key}/comment/properties` + project role list. Both panels (`handover/.../JiraCommentPanel.kt:64`, `jira/.../QuickCommentPanel.kt:42`) wire a single `JBTextField`/`JBTextArea` straight to `JiraService.addComment(key, body)` which calls `JiraApiClient.addComment` (`jira/.../api/JiraApiClient.kt:199`) emitting `{"body": <text>}`. Result: every comment is *unrestricted*, even when a team policy expects "Developers only" comments on a closure. Customer-facing tickets that should be developer-private leak.
3. **P1 — `TimeLogPanel` (handover) drops the user-picked `started` date on the floor.** The panel collects a date field (`TimeLogPanel.kt:48,213`) and computes a `started` ISO string, but the comment at `TimeLogPanel.kt:229-233` confirms the call site only forwards `(timeSpent, comment)` — never the date. `JiraApiClient.postWorklog` (`api/JiraApiClient.kt:186`) writes `{"timeSpent":..., "comment":...}`; the server defaults `started` to **now**, silently overriding the user's intent. Native Jira "Log Work" honors the date picker.

The shape correctness is good (every body decoded by the probe matches what the server documented). The gaps are at the **UX preflight** layer: we send valid JSON, but we don't pull the metadata the native UI uses to populate dropdowns / enforce required fields / honor visibility.

---

## 2. Server context

| Property | Value | Source |
|---|---|---|
| Version | `10.3.16` | `Result_Jira/bundle.unpacked/raw/serverInfo.json` |
| Deployment | `Server` (Data Center) | same |
| Build | `10030016`, 2026-01-07 | same |
| Auth | `Authorization: Bearer <PAT>` | confirmed via `HttpClientFactory.clientFor(ServiceType.JIRA)` + 200s on every probed read |
| `POST /rest/api/2/issue/{key}/transitions` | 200 with `{transition.fields}` schema | `raw/issue_transitions.json` |
| Per-transition required fields | YES — workflow has them (e.g. transition `231 → Done` requires `resolution`, `261 → Analyzed` requires `description`, `issuetype`, `components`) | `raw/issue_transitions.json:725-805` etc. |
| `GET /rest/api/2/user/assignable/search?issueKey=&query=` | 200 | `raw/issue_assignable.json` |
| `GET /rest/api/2/user/search?username=` | 200 (query=`username` not `query` on Server) | `raw/user_search.json` + summary L38 (failed `?query=` form) |
| `GET /rest/api/2/groups/picker?query=` | 200 | `raw/groups_picker.json` |
| `GET /rest/api/2/project/{key}/versions` / `/components` | 200 | `raw/project_versions.json`, `raw/project_components.json` |
| `GET /rest/api/1.0/labels/suggest?query=` | 200 (internal endpoint, 404 fallback wired) | `raw/labels_suggest_internal.json` |
| `POST /rest/api/2/issue/{key}/attachments` | **NOT PROBED** | — |

Notably, the probe never exercised attachment upload; this audit therefore cannot pin server semantics for the multipart form.

---

## 3. Findings

### 3.1 `transitionIssue` — POST `/rest/api/2/issue/{key}/transitions` — CORRECT-with-MISSING-PREFLIGHT

**Code:** `jira/.../api/JiraApiClient.kt:343-386` (HTTP), `jira/.../api/TransitionInputSerializer.kt:9-25` (body), `jira/.../service/TicketTransitionServiceImpl.kt:188-256` (orchestration), `jira/.../ui/TicketTransitionDialog.kt:96-167` (dialog), `jira/.../ui/TransitionDialogOpenerImpl.kt:9-12` (entry)
**HTTP:** `POST /rest/api/2/issue/{key}/transitions` body `{transition:{id}, fields:{...}, update:{comment:[{add:{body:"..."}}]}}`
**Probe evidence:** `raw/issue_transitions.json` documents transitions and their `fields` schema; lookups `transition.fields.<id>.required` are present (e.g. transition 231 `resolution` required, transition 261 `description`+`issuetype`+`components` required). Server 10.3.16 supports the `update.comment.add.body` shape (Jira DC docs).

**Correctness check:**
- [pass] Method+path match server.
- [pass] Body shape matches: `{transition:{id}, fields, update}` is exactly what Atlassian DC docs document; `TransitionInputSerializer.buildBody` emits it cleanly.
- [pass] Auth header (Bearer via `HttpClientFactory`).
- [pass] Error handling — 400 body parsed via `parseJiraErrorMessage` (`api/JiraApiClient.kt:388-394`) into `errorMessages` + `errors{...}`, mapped to `VALIDATION_ERROR`.

**UI parity check:**
- [pass] Preflight via `TicketTransitionDialog.loadTransitions()` calls `TicketTransitionService.getAvailableTransitions(ticketKey)` which calls `JiraApiClient.getTransitions(issueKey, expandFields=true)` — i.e. uses `expand=transitions.fields` (`api/JiraApiClient.kt:141-184`). Cache TTL 60 s in `TicketTransitionServiceImpl.cache` (`service/TicketTransitionServiceImpl.kt:80-83`). 
- [pass] Required-field enforcement in dialog: `TicketTransitionDialog.doValidate()` (`ui/TicketTransitionDialog.kt:175-181`) iterates widgets; each widget's `validate()` returns the per-field error. `TicketTransitionServiceImpl.executeTransition` also re-checks server-side at `service/TicketTransitionServiceImpl.kt:206-222` and emits `MissingFields` payload.
- [pass] Autocomplete on user/picker fields: `FieldWidgetFactory` (`ui/widgets/FieldWidgetFactory.kt:20-56`) dispatches to `UserPickerWidget` etc.; `UserPickerWidget` calls `JiraSearchService.searchAssignableUsers` per keystroke (`ui/widgets/EntityPickerWidgets.kt:31`).
- [pass] Validation matches server constraints — schema parsed via `JiraTransitionResponseParser.parseField` (`api/JiraTransitionResponseParser.kt:45-57`) including `autoCompleteUrl` and `allowedValues`.
- [fail/sub-finding] **Stale-cache risk:** the 60 s cache means a workflow rule change made by the admin between the dialog open and submit is invisible to the dialog. `executeTransition` re-validates against the same cached `meta`, not a fresh fetch (`service/TicketTransitionServiceImpl.kt:193`). For human dialogs this is fine; for the **agent path** (next finding) it is the only preflight.

**Verdict:** CORRECT for the dialog-driven path. **MISSING-PREFLIGHT** for non-dialog callers — see 3.2 and 3.10.
**Severity:** P2 (cache staleness) — dialog itself is solid.
**Proposed fix:** Add an "expand+force-refresh" branch in `TicketTransitionService.executeTransition` that bypasses the cache when the prior cached entry was older than e.g. 5 s.

---

### 3.2 `BranchingService.startWork` auto-transition — POST `/rest/api/2/issue/{key}/transitions` — MISSING-PREFLIGHT

**Code:** `jira/.../service/JiraServiceImpl.kt:650-701` (`startWork` impl), `jira/.../api/JiraApiClient.kt:343` (POST)
**HTTP:** `POST /rest/api/2/issue/{key}/transitions` body `{"transition":{"id":"<inProgressId>"}}` — `fields={}` always.
**Probe evidence:** `raw/issue_transitions.json:612-657` shows transition `211 → In Progress` carries `timetracking` and `assignee` (with `autoCompleteUrl`). Both currently optional on the probed instance, but admins can flip `required=true` per Jira DC workflow editor.

**Correctness check:**
- [pass] Method+path match server.
- [pass] Body shape (`{transition:{id}}`) is valid for transitions with no required fields.
- [pass] Auth header.
- [fail] Error handling — `JiraServiceImpl.startWork` checks `ApiResult.Success/Error` and only logs on failure (`service/JiraServiceImpl.kt:670-678`); the user sees a top-level "Started work" success even when the transition silently failed (`return ToolResult.success(...)` at line 693, with `transitioned=false` field that no one reads).

**UI parity check:**
- [missing] Preflight metadata fetched: `startWork` calls `api.getTransitions(issueKey)` *without* `expandFields=true` (`service/JiraServiceImpl.kt:664` → `JiraApiClient.getTransitions` defaults `expandFields=true`, so this is actually OK; but the `meta.fields` are then ignored).
- [missing] Required-field enforcement: `transitionIssue(..., TransitionInput(inProgressTransition.id, emptyMap(), null))` at `service/JiraServiceImpl.kt:670` always sends `fields={}`. If the admin makes any field required on the In Progress transition, this 400s and `transitioned=false` is silently set to false.
- [missing] Autocomplete: N/A (no UI).
- [missing] Validation matches server constraints: no client-side check.

**Verdict:** MISSING-PREFLIGHT
**Severity:** P1
**Proposed fix:** Route `BranchingService.startWork` (and the agent's `start_work` action — see 3.10) through `TicketTransitionService.executeTransition`, which already does the MissingFields preflight. When a required field surfaces, surface a UI prompt in the Sprint tab dialog rather than silently giving up.

---

### 3.3 `addComment` (Sprint tab `QuickCommentPanel`) — POST `/rest/api/2/issue/{key}/comment` — MISSING-UX

**Code:** `jira/.../ui/QuickCommentPanel.kt:31-117` (UI), `jira/.../service/JiraServiceImpl.kt:268-299` (service), `jira/.../api/JiraApiClient.kt:199-205` (HTTP)
**HTTP:** `POST /rest/api/2/issue/{key}/comment` body `{"body": "<wiki-markup-text>"}`
**Probe evidence:** `raw/issue_comments.json` shows the GET shape — confirms server returns `visibility` keys when restricted; reverse-engineering tells us the POST accepts `{body, visibility:{type:"role"|"group", value:"..."}}` (Jira DC v8+).

**Correctness check:**
- [pass] Method+path match.
- [pass] Body — minimal `{"body": ...}` is the documented baseline for unrestricted comments.
- [pass] Auth header.
- [pass] Error handling — `addComment` maps 401/403/etc. via shared `post` helper (`api/JiraApiClient.kt:551-583`); UI surfaces the message via `WorkflowNotificationService` in `QuickCommentPanel.kt:99-106`.

**UI parity check:**
- [missing] Preflight metadata fetched — none. We do not call `/rest/api/2/issue/{key}/comment/properties` or fetch the project role list.
- [missing] Required-field enforcement — N/A for comments, but…
- [missing] Visibility (role/group) picker — native Jira UI shows a "Restrict to" combo. Our panel only exposes a one-line input. Posting a closure note that should be developer-only goes public.
- [missing] @mention support — Jira DC accepts `[~username]` (and Cloud accepts `[~accountId:...]`); we leave the user to type the syntax manually with no autocomplete (no `/rest/api/2/user/search` integration on the comment field).
- [missing] Markdown / wiki preview — `QuickCommentPanel` is a `JBTextField` (single line!) with no wiki rendering preview. Even multi-line wiki markup (`{code}`, `h4.`) is sent unrendered.

**Verdict:** MISSING-UX
**Severity:** P1 (silent visibility violation) + P2 (UX papercut for mention syntax / preview)
**Proposed fix:** Replace `JBTextField` with a multi-line `JBTextArea` + a `Restrict to` combo populated from `/rest/api/2/myself?expand=groups` + project roles (the latter needs a `GET /rest/api/2/project/{key}/role` call, currently not in `JiraApiClient`). Add `[~username]` autocomplete by reusing `JiraSearchService.searchUsers`.

---

### 3.4 `addComment` (handover `JiraCommentPanel`) — POST `/rest/api/2/issue/{key}/comment` — MISSING-UX

**Code:** `handover/.../ui/panels/JiraCommentPanel.kt:64-170` (UI), routes through the same `JiraService.addComment(key, body)` (`handover/.../JiraCommentPanel.kt:150`), same `JiraApiClient.addComment` HTTP path.
**HTTP:** identical to 3.3 — `{"body": <wiki-markup>}`.
**Probe evidence:** same as 3.3.

**Correctness check:** identical to 3.3 (pass on shape, auth, error handling).

**UI parity check:**
- [missing] Visibility picker. The closure comment is *exactly* the kind of comment teams want restricted to "Developers" or "QA" roles — but the panel hardcodes unrestricted.
- [missing] Markdown/wiki preview. `commentPreview` is editable on toggle (`ui/panels/JiraCommentPanel.kt:60`) but the underlying widget is a plain `JBTextArea`; the `{code:json}` and table syntax built by `JiraClosureService.buildClosureComment` (`handover/.../service/JiraClosureService.kt:36-58`) renders as plain text in the preview.
- [missing] @mention support — same as 3.3.
- [pass] Pre-flight: ticket existence check is implicit (no key → button disabled in `refreshPostButtonState`).

**Verdict:** MISSING-UX
**Severity:** P1 (closure comments are precisely the case where role-restricted visibility matters most)
**Proposed fix:** Same as 3.3 — extend `JiraService.addComment` to accept an optional `visibility: CommentVisibility?` param; surface combo populated from project roles. Keep handover's auto-generated wiki markup; just add the visibility selector.

---

### 3.5 `postWorklog` (handover `TimeLogPanel`) — POST `/rest/api/2/issue/{key}/worklog` — WRONG-SHAPE-LITE

**Code:** `handover/.../ui/panels/TimeLogPanel.kt:198-247` (UI), `jira/.../service/JiraServiceImpl.kt:301-326` (service), `jira/.../api/JiraApiClient.kt:186-193` (HTTP)
**HTTP:** `POST /rest/api/2/issue/{key}/worklog` body `{"timeSpent":"<hh mm>", "comment":<text-or-omitted>}`
**Probe evidence:** `raw/issue_worklogs.json` (GET) shows the response carries `started`, `timeSpent`, `comment`, `author`. Documented Jira DC POST body accepts `started` (ISO 8601 with `+0000` offset), `timeSpent`, `comment`, plus `?adjustEstimate=new|leave|manual|auto&newEstimate=..&reduceBy=..` query params.

**Correctness check:**
- [pass] Method+path match.
- [partial-fail] Body shape — server accepts `{timeSpent, comment}`, but is **missing** `started`. Default behavior is "now," so logging "Friday's 2 hours" on Monday silently lands as Monday.
- [pass] Auth header.
- [partial-fail] Error handling — 400 errors from the worklog endpoint (e.g. invalid `timeSpent` like `"2hrs"`) are swallowed by the shared `post` helper and bubble up as `SERVER_ERROR` rather than being surfaced as the parsed `errorMessages`. The shared `post` helper (`api/JiraApiClient.kt:551`) has no `parseJiraErrorMessage` branch unlike `transitionIssue`.

**UI parity check:**
- [pass] Preflight metadata fetched — N/A; worklog endpoint doesn't expose a metadata endpoint.
- [pass] Required-field enforcement — `TimeLogPanel.refreshLogButtonState` (`ui/panels/TimeLogPanel.kt:173-190`) validates hours in `[0, maxHours]` and disallows future dates.
- [missing] **Date is collected but discarded.** `TimeLogPanel.dateField` (`ui/panels/TimeLogPanel.kt:48`) accepts a date; `onLogClicked` builds an ISO date via `timeService.formatStartedDate` (`ui/panels/TimeLogPanel.kt:213`) and then **does not send it** — the call at `TimeLogPanel.kt:233` is `jiraService.logWork(ticketKey, timeSpent, comment)`, no third arg. The inline comment at `TimeLogPanel.kt:229-232` admits this. Server falls back to "now."
- [missing] Existing worklog editing — no UI for `PUT /rest/api/2/issue/{key}/worklog/{id}` or `DELETE`.
- [missing] Remaining-estimate adjustment options — no `adjustEstimate` query-param plumbing. Native Jira "Log Work" dialog has a radio: "Adjust automatically / Use existing estimate / Set new estimate / Reduce manually." Our panel hardcodes the server default ("auto").

**Verdict:** WRONG-SHAPE (date-drop) + MISSING-UX (no estimate adjust, no edit/delete worklog).
**Severity:** P1 (date-drop produces silently wrong audit trails) + P2 (estimate adjust)
**Proposed fix:** Extend `JiraService.logWork` and `JiraApiClient.postWorklog` to accept `started: String?` and `adjustEstimate: AdjustEstimate?` (sealed). Wire `TimeLogPanel.dateField` through. Add a `?adjustEstimate=` query-string when non-null (`auto` is the server default; `manual` requires `reduceBy`; `new` requires `newEstimate`).

---

### 3.6 `postWorklog` (Sprint tab post-commit checkin) — POST `/rest/api/2/issue/{key}/worklog` — CORRECT (within the same WRONG-SHAPE-LITE family)

**Code:** `jira/.../vcs/TimeTrackingCheckinHandlerFactory.kt:91-119` (post-commit hook), same `JiraService.logWork` → `JiraApiClient.postWorklog`.
**HTTP:** identical to 3.5.

**Correctness check:**
- [pass] Method+path, auth, error handling — same as 3.5.
- [pass] **Auto-derived `timeSpent`** via `TimeTrackingLogic.toJiraTimeSpent` (`vcs/TimeTrackingCheckinHandlerFactory.kt:160-168`) which produces the canonical `"Nh Mm"` form.

**UI parity check:**
- [pass] Preflight — N/A (no dialog; this is a fire-and-forget post-commit).
- [pass] Required-field enforcement — handled by the checkin checkbox + spinner.
- [missing] Same date/estimate-adjust gaps as 3.5 — but here the semantic is "log time spent on the commit at commit time," so `started=now` is actually correct for this surface.

**Verdict:** CORRECT for this surface (post-commit "log time *now*"), inherits the date-drop limit only if/when we wanted backdating.
**Severity:** P2
**Proposed fix:** None for this surface; extend the API in 3.5's fix and let this caller continue to omit `started`.

---

### 3.7 `addWatcher` / `removeWatcher` — POST/DELETE `/rest/api/2/issue/{key}/watchers` — CORRECT (NOT-EXPOSED-AS-UI)

**Code:** `jira/.../api/JiraApiClient.kt:736-749` (HTTP), `jira/.../service/JiraServiceImpl.kt:1341-` (service)
**HTTP:** `POST` body is the bare JSON string `"username"` (a JSON-quoted scalar) — that's the Jira DC contract. `DELETE` uses query string `?username=`.
**Probe evidence:** `raw/issue_watchers.json` (GET) confirms the endpoint exists; the POST/DELETE pair is the documented v2 server contract.

**Correctness check:**
- [pass] All four (method, path, body, auth, error handling).

**UI parity check:**
- N/A — the methods exist on `JiraService` but no panel wires a "Watch this ticket" toggle today. `TicketDetailPanel` is documented as having a watch toggle in `:jira` `CLAUDE.md` ("Watch toggle in the header row") but I did not find a Watch button code path that calls `addWatcher`/`removeWatcher`. The widget is render-only against `getWatchers`.

**Verdict:** NOT-EXPOSED-AS-UI (correct API stub, no caller).
**Severity:** P2
**Proposed fix:** Wire the rendered watcher row to call `JiraService.addWatcher(key, currentUserName)` on toggle.

---

### 3.8 `validateTicketKeys` — POST `/rest/api/2/search` — CORRECT

**Code:** `jira/.../api/JiraApiClient.kt:441-470`
**HTTP:** `POST /rest/api/2/search` body `{"jql":"key in (...)", "fields":["summary","status"], "maxResults":N}`
**Probe evidence:** `bundle-audit-followup.unpacked/raw/search_v2_post_keys.json` confirms `200 OK` for this exact body shape on the user's instance.

**Correctness check:** all pass.
**UI parity check:** N/A — read-only validation, no dialog.
**Verdict:** CORRECT (formally a write — POST with body — but functionally a read; included only for completeness).
**Severity:** N/A.

---

### 3.9 Agent `jira(action="comment")` — POST `/rest/api/2/issue/{key}/comment` — MISSING-UX

**Code:** `agent/.../tools/integration/JiraTool.kt:361-380`, routes through `JiraService.addComment` → `JiraApiClient.addComment`.
**HTTP:** `{"body": <text>}` — same as 3.3.
**Probe evidence:** same as 3.3.

**Correctness check:** all pass (inherits 3.3).

**UI parity check:**
- [pass] Pre-flight: agent does call `service.getTicket(key)` first to verify existence (`tools/integration/JiraTool.kt:370`).
- [missing] Visibility — no `visibility` param exposed on the tool. The tool description explicitly does not mention visibility (`tools/integration/JiraTool.kt:54`: `comment(key, body)`), so the LLM cannot opt in even when asked.
- [missing] @mention guidance — the tool description doesn't tell the LLM that DC takes `[~username]`. The LLM may emit `@username` (Slack-style) which renders as plain text on Jira.

**Verdict:** MISSING-UX (at the tool-description level; the API is correct).
**Severity:** P2
**Proposed fix:** Extend the tool param schema with optional `visibility_role` / `visibility_group`, plumb through `JiraService.addComment` once 3.3 lands. Update tool description to mention `[~username]` for DC.

---

### 3.10 Agent `jira(action="transition")` — POST `/rest/api/2/issue/{key}/transitions` — CORRECT (good preflight via service)

**Code:** `agent/.../tools/integration/JiraTool.kt:256-359`, routes through `TicketTransitionService.executeTransition` (`tools/integration/JiraTool.kt:286`).
**HTTP:** `{transition:{id}, fields:{...}, update:{comment:[...]}}` — same as 3.1.
**Probe evidence:** same as 3.1.

**Correctness check:** all pass.

**UI parity check:**
- [pass] Preflight — `executeTransition` calls `prepareTransition` → `getAvailableTransitions` (`service/TicketTransitionServiceImpl.kt:151,193`), which uses `expand=transitions.fields`.
- [pass] Required-field enforcement — `MissingFieldsError` is surfaced as a typed `payload` and rendered to the LLM as `payload_type: missing_required_fields` with per-field id/name/schema (`tools/integration/JiraTool.kt:290-302`). The LLM is then nudged to call `ask_followup_question` and retry.
- [pass] Autocomplete — N/A for a tool surface; the LLM sees `schema=User|Group|Version|...` in the missing-fields payload and can match against `JiraSearchService` results.
- [pass] Validation matches server constraints — same path as the dialog.

**Verdict:** CORRECT — this is the gold-standard surface for transitions.
**Severity:** N/A.
**Proposed fix:** None. (Pin this as a regression target: changes to `TicketTransitionServiceImpl.executeTransition` must keep the `MissingFields` payload route for the agent.)

---

### 3.11 Agent `jira(action="start_work")` — POST `/rest/api/2/issue/{key}/transitions` — MISSING-PREFLIGHT (inherits 3.2)

**Code:** `agent/.../tools/integration/JiraTool.kt:477-490`, routes through `JiraService.startWork` → `JiraServiceImpl.startWork` (`jira/.../service/JiraServiceImpl.kt:650`).
**HTTP:** same as 3.2 — silent transition with `fields={}`.

**Correctness check / UI parity check:** identical to 3.2.

**Verdict:** MISSING-PREFLIGHT.
**Severity:** P1.
**Proposed fix:** Route `JiraServiceImpl.startWork`'s transition step through `TicketTransitionService.executeTransition` so both the dialog flow and the agent flow get the MissingFields preflight.

---

### 3.12 Agent `jira(action="log_work")` — POST `/rest/api/2/issue/{key}/worklog` — MISSING-UX (inherits 3.5)

**Code:** `agent/.../tools/integration/JiraTool.kt:389-409`, routes through `JiraService.logWork`.
**HTTP:** `{"timeSpent": ..., "comment": ...}` — same as 3.5.

**Correctness check:**
- [pass] All shape checks.
- [missing] No `started` parameter on the tool — the LLM cannot backdate a worklog. (Note: 3.6 documents that the post-commit checkin surface is fine without it; the agent surface is not — agents are explicitly asked to "log time for yesterday's review session" use cases.)

**UI parity check:**
- [pass] Pre-flight — agent calls `service.getTicket(key)` first (`tools/integration/JiraTool.kt:399`).
- [missing] No `adjustEstimate` parameter.
- [missing] No `started` parameter.

**Verdict:** MISSING-UX (parameter surface; HTTP shape is fine).
**Severity:** P2
**Proposed fix:** Add `started` and `adjust_estimate` optional params on the `log_work` action, plumb through `JiraService.logWork(key, timeSpent, comment, started, adjustEstimate)`.

---

### 3.13 Attachment upload — `POST /rest/api/2/issue/{key}/attachments` — NOT-FOUND

**Searched:** ripgrep for `attachment.*upload`, `multipart`, `X-Atlassian-Token` across `:jira`, `:handover`, `:agent`. Zero hits.
**Code:** None — only `downloadAttachment` exists (`jira/.../service/JiraServiceImpl.kt:703`).

**Verdict:** NOT-FOUND. The plugin has no attachment-upload write path.
**Severity:** P2 (only matters once an upload feature is requested; documenting the gap so a future implementer knows the contract).
**Proposed fix (forward-looking, not requested today):** When implemented, mandatory headers are `X-Atlassian-Token: no-check` (CSRF defense bypass for API clients) plus `Content-Type: multipart/form-data`. Body is one or more `file` parts. The `JiraApiClient.post()` helper currently hardcodes `application/json`; the implementer needs a sibling `postMultipart` helper.

---

## 4. Cross-cutting issues

### 4.1 Two separate orchestration paths for the same POST

Three call sites POST to `/rest/api/2/issue/{key}/transitions`:

| Caller | Preflight? | Required-field check? |
|---|---|---|
| `TicketTransitionDialog` (Sprint tab) | YES — `TicketTransitionService.getAvailableTransitions` with `expand=transitions.fields`, 60 s cache | YES — dialog widgets + `executeTransition` redundant check |
| Agent `jira(action="transition")` | YES — same service path | YES — surfaces `MissingFields` payload |
| `BranchingService.startWork` / agent `start_work` | NO — calls `JiraApiClient.transitionIssue` directly with `fields={}` | NO |

**Pattern:** the silent-transition path was supposedly "removed in the unified-transition-UX redesign" per the comment in `TicketTransitionServiceImpl.kt:31-33`. It survives in `JiraServiceImpl.startWork`. Fix is one-line: replace the direct `api.transitionIssue` call (`service/JiraServiceImpl.kt:670`) with `project.service<TicketTransitionService>().executeTransition(...)`.

### 4.2 No project-role discovery anywhere

Native Jira UI populates the comment "Restrict to" combo via the project role list. The plugin has no `GET /rest/api/2/project/{key}/role` call (verified: ripgrep for `/role` in `JiraApiClient.kt` — zero hits). This blocks the comment-visibility fix in 3.3, 3.4, 3.9.

### 4.3 No hardcoded `customfield_*` IDs in write paths

I greped for `customfield_` across the modules; the only hits in write code are:
- `JiraTransitionResponseParser.parseField` reads them dynamically from the response (correct).
- `TransitionInputSerializer.writeValue` uses the field id as the JSON key (correct — agnostic to which custom field).
- Settings code in `:jira` (advanced UI) lets the user *configure* a customfield id; settings reads only.

**Pass:** The plugin's writes do not bake instance-specific custom-field IDs.

### 4.4 Date format

`TimeTrackingService.formatStartedDate` produces `"yyyy-MM-dd'T'HH:mm:ss.SSSZ"` (the documented Jira DC worklog `started` format). When the field is wired through (per 3.5), no format mismatch is expected. Pinned by Jira DC docs.

### 4.5 Consistent Bearer auth, no XML token storage

All write paths share `JiraApiClient`'s injected `tokenProvider` which the audit baseline confirms reads from `CredentialStore` (PasswordSafe). No XML / `state` storage of tokens anywhere in the read.

### 4.6 No `parseJiraErrorMessage` for non-transition POSTs

`api/JiraApiClient.kt:388-394` is only called from `transitionIssue`. The shared `post` helper (`api/JiraApiClient.kt:551-583`) does not parse `errorMessages` / `errors{...}` — it returns the raw status code summary. So `addComment`/`postWorklog`/`addWatcher` errors give a less-specific message than `transitionIssue` errors. Low-effort fix: pull the parser into the shared `post` and call it on every 4xx.

---

## 5. Out of scope / not found

- **Attachment upload** — looked for, not present (3.13).
- **Comment edit / delete** — `PUT /rest/api/2/issue/{key}/comment/{id}` and `DELETE` not implemented; `JiraApiClient` has no callers. Native Jira UI exposes both. Out of scope today.
- **Worklog edit / delete** — same as above.
- **Issue create** — no `POST /rest/api/2/issue` call site anywhere. The plugin is read-mostly + transition-on-existing-issues. Confirmed by ripgrep for `"/issue"` body POSTs in `JiraApiClient.kt`.
- **Issue link create** — `transitions[].fields.issuelinks.autoCompleteUrl` is exposed in the probe (`raw/issue_transitions.json:481`) but no UI surfaces a "link issue" action; the dialog accepts it generically through `FieldWidgetFactory.Unknown → TextFieldWidget` fallback. Not assessed here.
- **Cloud-style `accountId` mention syntax** — the probe pinned the server as DC; `[~username]` is the right form for this instance. If/when a Cloud instance is in scope, mention syntax fix is needed.
- **`comment.properties` endpoint for visibility** — not probed (the audit follow-up bundle covers candidates, not visibility). Recommend a probe-only follow-up before implementing 4.2.

---

## Appendix — file:line citations index

- POST builders: `jira/.../api/JiraApiClient.kt:186, 199, 343, 441, 551, 736`
- Transition body serializer: `jira/.../api/TransitionInputSerializer.kt:9-25`
- Transition response parser: `jira/.../api/JiraTransitionResponseParser.kt:21-67`
- Dialog: `jira/.../ui/TicketTransitionDialog.kt:96-217`
- Dialog opener: `jira/.../ui/TransitionDialogOpenerImpl.kt:9-12`
- Service-layer transition: `jira/.../service/TicketTransitionServiceImpl.kt:105-256`
- `startWork` silent transition: `jira/.../service/JiraServiceImpl.kt:650-701`
- Sprint comment panel: `jira/.../ui/QuickCommentPanel.kt:31-117`
- Handover comment panel: `handover/.../ui/panels/JiraCommentPanel.kt:64-170`
- Handover time-log panel: `handover/.../ui/panels/TimeLogPanel.kt:198-247`
- Post-commit time tracker: `jira/.../vcs/TimeTrackingCheckinHandlerFactory.kt:91-119`
- Agent meta-tool: `agent/.../tools/integration/JiraTool.kt:256-490`
- Watcher API stubs: `jira/.../api/JiraApiClient.kt:736-749`
- Probe ground truth: `tools/atlassian-probe/Result_Jira/bundle.unpacked/raw/issue_transitions.json`, `.../raw/serverInfo.json`, `.../raw/issue_assignable.json`, `.../raw/groups_picker.json`, `.../raw/project_versions.json`, `.../raw/project_components.json`, `.../bundle-audit-followup.unpacked/raw/search_v2_post_keys.json`
