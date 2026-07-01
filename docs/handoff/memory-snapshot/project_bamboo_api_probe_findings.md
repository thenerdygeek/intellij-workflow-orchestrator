---
name: Bamboo API probe findings (2026-04-14)
description: Real API response analysis from api-probe script — CI plan vs suite plan structure, log URLs, variable locations, branch behavior
type: project
originSessionId: c7bc748a-f201-48a2-9f3e-1413c9353bb6
---
API probe run against real Bamboo server with two plan types.

**CI Plan (PROJECT-PROJECTREPO) — builds the service, produces docker tag:**
- Plan-level variables → 404 (no plan variables)
- Branch-level variables → 404
- Direct `/branch/{name}/latest` → 404 (server-side branch resolution broken for this plan)
- Plan-level log (`/download/PROJECT-PROJECTREPO-571/...`) → 200 but only **101 bytes** (useless wrapper)
- Job-level log (`/download/PROJECT-PROJECTREPO-OSSAN-571/...`) → 200, **29,709 bytes** (real log with docker tags)
- Job key format: `{PLAN_KEY}-{JOB_SHORT_KEY}-{BUILD_NUMBER}` (e.g. `PROJECT-PROJECTREPO-OSSAN-571`)
- `JOB_SHORT_KEY` is `shortKey` from `plan` inside `stages.stage[].results.result[]`
- CI plan has 2 stages with 3+ jobs
- Build variables on result: 12 variables

**Suite Plan (PROJECT-AUTOMATIONTESTS) — automation tests, has dockerTagsAsJson:**
- Plan-level variables → 404 (variables only exist on triggered build results)
- Branch-level variables → 404
- Plan-level log → 404 (no log at plan level)
- Job-level log → 200, **22,792 bytes**
- Build variables on result: 26 variables (includes dockerTagsAsJson set at trigger time)
- Suite has 3 stages with 3+ jobs
- `recent_results` with `expand=results.result.stages.stage,results.result.variables` → 200 with full data including variable array
- Branch results include `master` field pointing to parent plan

**Critical fix needed for BuildLogReady:**
`BuildMonitorService` currently constructs `resultKey = "${planKey}-${buildNumber}"` which is plan-level.
Plan-level logs are either 404 or tiny (101 bytes). Must use job-level result key from `stages.stage[0].results.result[0].buildResultKey` to get the real log (29KB) containing the docker tag.

**Why:** The `BuildState` already has `stages: List<StageState>` with `resultKey` per job. The first job's `resultKey` is the correct key for log fetching.

**How to apply:** Update `BuildMonitorService` to extract the first job result key from the build response, use that for log fetching instead of constructing plan-level key. The `BambooResultDto.stages.stage[].results.result[].buildResultKey` field is already parsed in `mapToBuildState()`.
