# Write-Ops UI-Parity Audit — Jira + Bitbucket + Bamboo

**Date:** 2026-05-07
**Branch:** `fix/automation-handover-quality-tabs`
**Scope:** Every write operation the plugin issues against Atlassian-stack services, audited for (a) wire-shape correctness vs. probe data, and (b) parity with the native web UI's preflight intelligence (autocomplete, required-field enforcement, conflict detection, permission gates).

This is a **read-only audit**. No code was changed.

## Per-service deep-dive docs

- [Jira write ops audit](./2026-05-07-jira-write-ops-audit.md) — DC 10.3.16
- [Bitbucket PR write ops audit](./2026-05-07-bitbucket-pr-write-ops-audit.md) — DC 9.4.16
- [Bamboo write ops audit](./2026-05-07-bamboo-write-ops-audit.md) — DC 10.2.14

---

## Executive verdict

| Service | Wire-shape correctness | UI-parity verdict | Worst finding |
|---|---|---|---|
| **Jira** | All POST bodies match server | Inconsistent — dialog path is gold, shortcut paths bypass preflight | `startWork` skips required-fields check (silent rule violation) |
| **Bitbucket** | Mostly correct; 3 ops have API issues | Reviewer autocomplete works on create; falls apart on PrDetail and post-creation | GET→PUT version race with no 409 retry on 4 mutating ops |
| **Bamboo** | **Build trigger sends variables in a body the server ignores** | Form is fetched + rendered, but submission shape is wrong, no permission preflight, no stage-order enforcement | `triggerBuild` returns 200 while silently dropping every variable the user typed |

## Top findings ranked by severity

### P0 — Broken or silently wrong (fix first)

| # | Service | Finding | Code |
|---|---|---|---|
| 1 | Bamboo | `triggerBuild` sends variables as JSON body; server expects `?bamboo.variable.<name>=value` query params. Returns 200, drops user input. Affects manual trigger, automation queue auto-trigger, every Docker-tag-driven build. | `bamboo/.../api/BambooApiClient.kt:142-162` |
| 2 | Bitbucket | `addReviewer` / `removeReviewer` / `updateTitle` / merge: GET PR → PUT with `version` field, no retry on 409. Stale `version` silently fails any write if anyone touched the PR between GET and PUT. | `pullrequest/.../service/PrActionService.kt:204,323,368` |

### P1 — Silent rule violation or material UX gap

| # | Service | Finding | Code |
|---|---|---|---|
| 3 | Jira | `JiraServiceImpl.startWork` posts `/transitions` with `fields={}`, bypassing the MissingFields preflight that `TicketTransitionService.executeTransition` runs correctly. If an admin marks any field required on the In Progress transition, this silently 400s or violates workflow rules. | `jira/.../service/JiraServiceImpl.kt:650` |
| 4 | Jira | `QuickCommentPanel` and `handover/.../JiraCommentPanel` post unrestricted `{body}` only — no role/group `visibility`. Closure comments meant to be role-restricted leak to all viewers. | (see Jira doc, sec. Comments) |
| 5 | Jira | `TimeLogPanel` collects a date from the user, computes an ISO `started`, then `JiraService.logWork(key, timeSpent, comment)` drops it. Server defaults to "now". | `jira/.../ui/TimeLogPanel.kt` (see Jira doc) |
| 6 | Bitbucket | Default-reviewers DTO at `BitbucketBranchClient.kt:107` is missing `sourceRefMatcher` / `targetRefMatcher`. Plugin unions all conditions instead of scoping per branch (e.g., `target=develop`). Probe sample shows real conditions are scoped. | `core/.../BitbucketBranchClient.kt:107,1075` |
| 7 | Bitbucket | AI inline comments don't pin `diffType` / `fromHash` / `toHash`. EFFECTIVE-anchored comments float when the PR receives new commits between AI-review-time and push-time. | (see Bitbucket doc, AI inline comments section) |
| 8 | Bitbucket | `PrDetailPanel.showAddReviewerPopup` (line 1648) calls `client.getUsers(query)` without the `projectKey/repoSlug` permission filter. Suggests users with no repo access. | `pullrequest/.../ui/PrDetailPanel.kt:1648` |
| 9 | Bamboo | `ManualStageDialog` discards `isPassword` from variable DTOs — secret values rendered/submitted as plain text. Plus no `variableType` (PLAN/GLOBAL/MANUAL), no description, no branch picker, no executeAllStages, no custom-revision field. | `bamboo/.../model/PlanVariableData.kt` (see Bamboo doc F-2) |
| 10 | Bamboo | `StageListPanel:45-57,161-163` enables `[Run]` for any `manual && !IN_PROGRESS` stage. Native Bamboo greys non-next-runnable stages. | `bamboo/.../ui/StageListPanel.kt:45-57,161-163` |
| 11 | Bamboo | "Rerun Failed Jobs" has no confirmation dialog, no `X-Atlassian-Token: no-check` header on the Struts admin action, and the response check `200..399` lets an HTML auth-redirect masquerade as success. | (see Bamboo doc F-6) |

### P2 — UX papercuts

| # | Service | Finding |
|---|---|---|
| 12 | Bitbucket | Merge dialog doesn't refresh PR `version` between button-click and POST submit. |
| 13 | Bitbucket | No "PR already exists for branch" preflight on create — relies on server 409. |
| 14 | Bitbucket | No `severity: BLOCKER` on comments; cannot create Bitbucket-DC-style blocking PR tasks. |
| 15 | Bitbucket | Markdown preview rendered locally, not via DC's `/markup/preview`. Drift from server rendering. |
| 16 | Bamboo | Cancel/Stop have no permission preflight (rely on 403). |
| 17 | Jira | `parseJiraErrorMessage` only invoked from `transitionIssue`. Lifting it to the shared `post()` would give `addComment` / `postWorklog` the same actionable error mapping. |

## Cross-cutting patterns

1. **Two-path drift.** The same logical write op exists in two implementations: a "rich" dialog path that does the right preflight (Sprint-tab `TicketTransitionDialog`, Bitbucket `CreatePrDialog`, Bamboo `ManualStageDialog`) and a "shortcut" path (`JiraServiceImpl.startWork`, `PrActionService` field-update calls) that bypasses preflight. The shortcut paths are the silent-bug surface.

2. **GET→PUT without 409 retry.** Bitbucket DC requires the latest `version` int on any PR mutation. The plugin reads it once at panel-open and reuses it across every action button click. Every long-lived PR detail panel is a 409 trap.

3. **Body vs. query-param mismatch on legacy Bamboo endpoints.** Bamboo Server's `/queue/{planKey}` is one of the few Atlassian endpoints that expects variables as URL-encoded query params, not JSON. Easy mistake; the audit caught it because the probe response showed `params:` shape.

4. **Server-rendered vs. client-rendered.** Bitbucket markdown is rendered locally; Jira comment visibility is unconfigured; Bamboo plan variables don't carry their server-side type/description. In each case the plugin reimplements something the server already exposes via API.

5. **Probe coverage gap.** Bamboo's `--versions-only` probe correctly avoided exercising mutating endpoints, so all wire-shape claims for Bamboo writes are sourced from Atlassian docs + convention rather than live responses. Recommend a follow-up `--writes-allowlist` probe in a non-prod Bamboo to confirm finding #1 before fixing it (see "Recommended next probe" below).

## Gold-standard surfaces (do not regress)

- **Sprint-tab `TicketTransitionDialog`** — fetches `expand=transitions.fields`, renders required fields per-widget, validates client-side before POST.
- **Agent `jira(action="transition")`** — surfaces typed `MissingFields` payload back to the LLM, so the agent can re-ask the user for the missing field instead of failing opaquely.
- **`CreatePrDialog.searchUsers`** — real autocomplete with the `REPO_READ` permission filter, 300 ms debounce, and rich display-name + username + email rendering.
- **`ManualStageDialog.init`** preflight call to `bambooService.getPlanVariables` — the fetch is correct; only the submission shape is broken.

## Recommended fix order

If you choose to act on this audit, the priority is roughly:

1. **Bamboo `triggerBuild` shape fix** (P0, single-file, high user impact) — switch JSON body to query params, confirm with a one-shot manual probe against your Bamboo before merging.
2. **Bitbucket version-race retry** (P0, affects 4 ops) — wrap `addReviewer` / `removeReviewer` / `updateTitle` / merge in a "fetch latest version on 409 and retry once" helper.
3. **Jira `startWork` preflight** (P1, single-method redirect) — route `JiraServiceImpl.startWork` through `TicketTransitionService.executeTransition` so it picks up the MissingFields check.
4. **Default-reviewers per-branch matcher** (P1, structural) — extend the DTO at `BitbucketBranchClient.kt:107` and filter by ref-matcher in `getDefaultReviewersForBranch`.
5. **Stage runnability + plan-variable secret masking** (P1, Bamboo) — port the Bamboo web UI's "next-runnable-only" rule and surface `isPassword`.
6. **Lift `parseJiraErrorMessage` to shared `post()` helper** (P2, low-risk cleanup) — consistent error UX across Jira writes.
7. Everything else as a UX polish pass.

## Recommended next probe

Before implementing fix #1, run a one-shot Bamboo trigger against a sandbox plan with a known plan variable, capturing both the request and the resulting build's variable values:

```
POST /rest/api/latest/queue/{key}?bamboo.variable.foo=bar&executeAllStages=true
```

Then `GET /rest/api/latest/result/{resultKey}?expand=variables` and confirm `foo=bar` came through. This validates the proposed fix against the actual server before code changes ship.

## Out of scope / not audited

- **Sonar** — already shipped (`ac0e070e`). User excluded from this audit.
- **Nexus** — deferred per memory `project_nexus_version_probe_findings.md`.
- **Sourcegraph** — separate auth/transport stack; isolated per memory.
- **Attachment upload** (Jira), **artifact upload** (Bamboo), **plan branch creation**, **Docker registry tag/promote** — no plugin code path exists. Documented in respective per-service docs.
