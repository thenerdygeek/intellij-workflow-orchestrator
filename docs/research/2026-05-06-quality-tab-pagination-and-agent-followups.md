# Quality tab pagination + agent-tool follow-ups (2026-05-06)

Branch: `fix/automation-handover-quality-tabs`
Origin: probe data captured against the user's real Sonar surfaced
secondary issues beyond v0.83.67's `effectiveValue()` fix.

## Problem confirmed

The user's project has 531 files. The plugin's `getMeasures` request used
`ps=500` with no sort, so Sonar's default alphabetical sort returned the
first 500 files by name and silently dropped 31. If the files with new
code happened to fall in those 31, the new-code Coverage tab showed
"No files in the new-code period" while the gate (which sees the whole
project) correctly reported a failing `new_branch_coverage`.

Empirically: in the user's `--raw` probe, the first 10 of 531 components
all showed `new_lines_to_cover.period.value = "0"`. Plausible by chance,
but the gate failure proves at least *some* of the 521 unsampled files
have `new_lines_to_cover > 0`. Truncation hid them.

A second, smaller issue: `/api/new_code_periods/show` returned 403 because
the user's token lacks admin. The plugin had try/catch protection so it
didn't crash, but the "New code: compared to release" line in the branch
info bar was permanently missing for non-admin tokens.

## v0.83.68-alpha fixes (shipped)

1. **Pagination loop in `SonarApiClient.getMeasures`** — loop on `p=N`
   until `paging.total` is reached or the page returns empty.
   `MAX_MEASURES_PAGES = 10` × `MEASURES_PAGE_SIZE = 500` covers up to
   5000 files; past that we log and return partial data rather than
   stall the IDE.
2. **Sort by `new_lines_to_cover` desc** — when `new_lines_to_cover`
   is in the metric set (i.e. always for the Coverage tab path), add
   `&s=metric&metricSort=new_lines_to_cover&asc=false`. Files with the
   most new code surface first; pagination then guarantees full
   coverage. Conditional so callers requesting only overall metrics
   don't trigger a 400 from Sonar.
3. **Gate-period fallback for `NewCodePeriod`** — `SonarQualityGateDto`
   now models the embedded `period: { mode, parameter }` field.
   `SonarDataService.refreshWith` derives `NewCodePeriod` from the gate
   response when `/api/new_code_periods/show` returns 403 (or any other
   non-success). The gate is fetched with project Browse permission, so
   it works on every token that can read quality data at all.

Tests pinned all three:
- `getMeasures sorts by new_lines_to_cover desc when that metric is in the request`
- `getMeasures does not request metricSort when new_lines_to_cover is absent from keys`
- `getMeasures paginates until paging total is reached and merges components`
- `getMeasures stops paginating when a page returns empty components`
- `deserialize quality gate failed` extended to assert the period fields.

## Agent-tool impact: mostly free

`agent/.../integration/SonarTool.kt` exposes 13 actions that all flow
through `SonarServiceImpl`, which delegates to the same `SonarApiClient`.
The v0.83.67 (`effectiveValue()`) and v0.83.68 (pagination + sort + gate
fallback) fixes are purely lower in the stack, so every agent action
benefits with **zero agent-side code changes**:

| Agent action | Underlying call | Benefits from v0.83.67 + v0.83.68 |
|---|---|---|
| `coverage` | `SonarServiceImpl.getCoverage` → `api.getMeasures` | Yes — pagination + sort, period parsing |
| `branch_quality_report` | direct `api.getMeasures` calls | Yes — same |
| `project_measures` | `api.getProjectMeasures` | Yes — DTO `effectiveValue()` |
| `quality_gate` | `api.getQualityGateStatus` | Yes — period now exposed |
| `issues` / `issues_paged` | `api.getIssues(WithPaging)` | No new wins (uses different API) |
| `source_lines`, `duplications`, `branches`, `search_projects`, `analysis_tasks`, `local_analysis`, `security_hotspots` | their respective `api.*` | No relevant wins |

## Agent-tool follow-ups worth planning (NOT shipped)

### 1. SonarQube 9.6+ Clean Code taxonomy

Current Sonar instances return four richer per-issue fields the plugin
ignores entirely:

```jsonc
"cleanCodeAttribute": "CONVENTIONAL",            // CLEAR | CONVENTIONAL | FORMATTED | …
"cleanCodeAttributeCategory": "CONSISTENT",      // CONSISTENT | INTENTIONAL | ADAPTABLE | RESPONSIBLE
"impacts": [
  {"softwareQuality": "RELIABILITY", "severity": "MEDIUM"},
  {"softwareQuality": "MAINTAINABILITY", "severity": "LOW"}
],
"issueStatus": "OPEN"
```

For an agent fixing code, knowing the impact is `RELIABILITY/HIGH` rather
than the legacy `severity=MAJOR, type=BUG` is materially more actionable.
Currently the agent's `issues` action returns only `severity` + `type` —
the agent can't prioritize a "reliability blocker" over a "maintainability
nit" with the same legacy MAJOR severity.

**Plan (separate from v0.83.68):**

- DTO: extend `SonarIssueDto` with `cleanCodeAttribute`, `cleanCodeAttributeCategory`, `impacts: List<SonarImpactDto>`, `issueStatus`. All optional defaults so older Sonar (pre-9.6) still parses cleanly.
- Model: extend `MappedIssue` with `cleanCodeAttribute`, `cleanCodeAttributeCategory`, `impacts: List<Impact>` where `Impact = (SoftwareQuality, Severity)`.
- IssueMapper: copy the new fields through.
- Agent tool output: `issues` and `issues_paged` actions include `impacts` and `cleanCodeAttribute` in the JSON payload. Update the action description so the agent knows to use these for prioritization.
- UI: `IssueListPanel` cell renderer adds a small impacts badge ("REL/HIGH"); `IssueDetailPanel` shows the full breakdown.

Estimated scope: 1 new DTO, 4 model field additions, 3 surface updates (mapper, agent tool output, UI). Half-day with tests.

### 2. `getIssues` (un-paginated) — silent truncation at ps=500

The agent's `issues` action calls `api.getIssues(...)` which sets `ps=500`
with no pagination. Projects with > 500 open issues silently lose the
tail. The action description doesn't warn the agent.

**Plan:** Either default the `issues` action to pagination (delegate to
the same code path as `issues_paged`), or update the description to
explicitly say "returns up to 500 issues; use `issues_paged` for full
coverage." Lower priority than (1) because `issues_paged` already exists.

### 3. Document admin-only endpoints

`analysis_tasks` (`/api/ce/activity`) and the now-fallback-ed
`new_code_period` (`/api/new_code_periods/show`) both return 403 for
non-admin tokens. The agent's tool descriptions don't mention this. An
agent that retries on 403 wastes turns. Update descriptions to flag the
admin requirement.

## Out of scope for this branch

- `bitbucket` audit (the existing `reference_bitbucket_tools_audit_result.md`
  flags 6 F-HIGH + 3 F-MED + 15 UNTESTED; that's a separate workstream).
- `bamboo` / `jira` / `nexus` correctness sweeps (probe is ready; run
  when bandwidth allows).

## How the user verifies v0.83.68 fixed the original symptom

1. Install v0.83.68-alpha.
2. Open Quality tab → switch to NEW CODE → Refresh on the same branch
   that was showing the empty state.
3. Coverage table should now list files with `new_lines_to_cover > 0`,
   sorted by new-code volume.
4. Branch info bar should show "New code: compared to release" instead
   of being absent.
