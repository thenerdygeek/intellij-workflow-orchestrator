---
name: ACTIVE 2026-05-07 — Bamboo audit & probe sequence
description: Bamboo 10.2.14 (DC) confirmed via probe; deep API research + integration next on `fix/automation-handover-quality-tabs`
type: project
originSessionId: dfc5110f-b4ee-412c-9bcd-d57ca9411e4f
---
**STATUS: 2026-05-07 — Bamboo audit SHIPPED end-to-end. Memory file ready for deletion next session.**

Final commit: `59c9ea8d feat(bamboo): adopt validated 10.2 endpoints + 2 P0 fixes` on `fix/automation-handover-quality-tabs` (+587/-56 LOC, 10 files, +16 tests, all 123 :bamboo tests green).

Implemented from `docs/research/2026-05-07-bamboo-audit-recommendations.md`:
- §8.1 P0 — `BambooPlanVariableDto` shape fix (key/value, not name/value); split into `BambooPlanContextVariableDto` for the variableContext expand
- §8.2 P0 — `BambooServiceImpl.getLatestBuild` master-tracked-branch fallback (404 on branch path → retry plain `/latest`)
- §8.3 cleanup — delete `getPlanVariableDirect` + Strategy B + `BambooVariableListResponse` DTO
- §8.4 — KEPT `getPlanSpecs` (live caller at `PlanDetectionService.kt:278`, recommendations doc was wrong; KDoc added documenting 403 behavior on non-admin PATs)
- §8.5 — branch-listing methods consolidated
- §8.7 P1 — `BuildDashboardPanel.LOG_RENDER_CAP_BYTES` + `capLogForDisplay()` pure helper, applied at both log-render sites (1133, 1168)
- §8.8 R-ADD-1 ADOPT — new `getBuildChanges` on BambooApiClient + BambooService, `BuildChangeData` model in :core (no UI wiring yet)

Pattern complete — matches Jira `56d0dd0` + Bitbucket `b9ed7cbe`.

---

(Below is preserved for reference; safe to remove this file in a future cleanup session.)

**Bundles produced (both Bamboo 10.2.14, 26/34 success each):**
- `tools/atlassian-probe/Result_Bamboo/bundle-automation.txt` — Automation tab suite plan
- `tools/atlassian-probe/Result_Bamboo/bundle-repo.txt` — Build tab service-CI plan tracking develop

**Cross-bundle failures (same on both):**
- `/plan/{key}/specs?format=YAML` → 403 (PAT permission, not API removal)
- `/result/{plan}/branch/develop/latest` → 404 (master plan tracks develop; no separate branch plan; plugin's `getLatestResult(plan, branch)` has a latent bug here — needs fallback to `/result/{plan}/latest` when branch matches master-tracked branch)
- `/plan/{key}/variable` → 404 (known; fallback already handled)
- `/labels`, `/result/{key}/jiraIssues` → 404 (feature candidates confirmed unavailable in 10.2)

**Cross-bundle adoptable candidates (200 on both):** `/agent`, `/queue`, `/deploy/project/all`, `/result/{key}/comment`, `/result/{key}?expand=changes.change`, `/result/{key}?expand=labels.label`.

**Build log size delta:** automation 22 KB / repo CI 405 KB — relevant for plugin's log buffer + truncation policy.

**Research SHIPPED 2026-05-07** → `docs/research/2026-05-07-bamboo-audit-recommendations.md` (427 lines). Two P0 latent bugs surfaced:
- `BambooDtos.kt:182-186` — `BambooPlanVariableDto` declares `name`/`value` but `?expand=variableContext` returns `key`/`value`. Silent parse-fail; getPlanVariables falls through to Strategy C (recent-build variables) which only succeeds when a recent build exists. Automation-tab variable picker likely empty for users on this server when no recent build.
- `BambooApiClient.kt:73-84` + `BambooServiceImpl.kt:91-97` — `getLatestResult(plan, branch)` 404s on `/result/{plan}/branch/{branch}/latest` when `branch` matches the master plan's tracked Git branch (Bamboo treats master plan as the implicit branch plan; no separate branch plan exists). No `/result/{plan}/latest` fallback.

P1 perf: 405 KB log → EDT push in `BuildDashboardPanel.kt:1133` — soft cap recommended.

Adoption: 1 ADOPT (R-ADD-1 `?expand=changes.change`), 4 DEFER, 2 REJECT (404 in 10.2). Cleanup: dead `/variable` fallback, unused `getPlanSpecs`, duplicate `getBranches`/`getPlanBranches`.

**Confirmed server facts (from `Result_Bamboo/bundle-versions-only-uncompressed.txt`):**
- `version: 10.2.14`, `buildNumber: 100220`, `buildDate: 2026-01-14`, `state: RUNNING`
- `edition: ""` — Bamboo `/info` doesn't surface edition. Treat as **Data Center** (Atlassian retired Server SKU 2024)
- Auth: PAT bearer works (200 on `/currentUser` as `kyw21409`)
- First PAT was rejected (401) — required Bamboo-host-specific PAT, not the Jira/Bitbucket one

Commits on `fix/automation-handover-quality-tabs`:
- `1c4ba363 feat(bamboo-audit): read-only probe_bamboo.py (v0)` — 808-line Python probe + README updates
- `9661e770 feat(bamboo-probe): --discover mode for self-bootstrapping plan/result IDs` — adds 3rd mode (versions-only / discover / full sweep). Walks /project -> /project/{k}?expand=plans.plan -> /plan/{k}/branch -> /result/{plan}?expand=...&variables.variable -> /result/{k}?expand=vcsRevisions and writes Result_N/discover.md with copy-paste commands. Flags plans surfacing `dockerTagsAsJson` (the same variable name in `automation/service/ConflictDetectorService.kt` and `TagBuilderService.kt`) as Automation-tab suite candidates vs Build-tab service-CI plans. Plugin coverage audit confirmed all 24 read endpoints + 4 writes already inventoried.

**Why over How:** Bamboo 10.2 is a major version bump from the 9.x docs the plugin's `BambooApiClient` was built against. Atlassian removed `/rest/api/latest/clone` and adjusted `result/{planKey}` pagination defaults in 10.x; admin form actions (`restartBuild.action`) historically break across major versions. We therefore validate every plugin-called endpoint against 10.2 docs *before* writing the integration commit.

**After version is known, future session does:**
1. Deep API research on the reported Bamboo version (per `feedback_audit_research_after_version.md`)
2. Write `docs/research/2026-05-07-bamboo-audit-recommendations.md` — covers correctness check on 24 existing endpoints + feature-discovery decisions on the 8 candidates (deploy projects, agent list, queue, jiraIssues, comments, changes, labels)
3. Single integration commit `feat(bamboo): adopt validated endpoints + ...` on the same branch — extending the API-audit branch one more service

**Probe coverage (all read-only):**
- 4 version probes (`/info`, `/serverInfo` alias, `/currentUser`, `/info/configurationProperties`)
- 24 endpoints from `BambooApiClient.kt` (correctness baseline)
- 8 feature-discovery candidates we don't currently call

**Mutating endpoints (NOT in probe, inventoried in summary.md):** `triggerBuild`, `restartBuild`, `cancelBuild`, `stopBuild`. User-Agent ends in `(read-only)` for Bamboo audit-log proof.

**Sequencing relative to Sonar:** user wants Sonar probe in a future session (per 2026-05-07 message). Sonar will need its own `tools/sonar-probe/` dir (non-Atlassian product family) — NOT co-located in `tools/atlassian-probe/`.

**Convention notes for next session:**
- Probe matches `probe_bitbucket.py` JSON shape so `redact.py` + `bundle.py` work unchanged
- Skipped endpoints get `SKIP` rows in summary.md (small divergence from Bitbucket probe which silently omits)
- Per memory `project_bamboo_api_probe_findings.md` (April 2026): user's CI plan returns 404 on both `plan/{key}?expand=variableContext` AND `plan/{key}/variable` — probe captures both for the recommendations doc to confirm

Remove this memory file when the integration commit ships.
