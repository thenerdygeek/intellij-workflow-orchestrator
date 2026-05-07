# SonarQube Community Build 25.9 — recommendations from full-sweep audit

**Date:** 2026-05-07
**Target:** SonarQube **Community Build 25.9.0.112764** (`edition: community`, versionEOL `2026-04-01` — already 5 weeks past EOL as of 2026-05-07)
**Branch:** `fix/automation-handover-quality-tabs` (audit branch — Jira → Bitbucket → Bamboo → **Sonar**, one integration commit per service)
**Auth:** Bearer (user token), non-admin scope (`permissions.global: []` on `/api/users/current`)
**Sources:**
- Probe driver — `tools/sonar-probe/probe_sonar.py` v0 → v0.3 (commits `89411137`, `acf72425`, `c35f3848`, `fb12aa36`)
- Probe results — three sweeps committed under `tools/atlassian-probe/Result_Sonar/`:
  - `bundle-versions-only.txt` (4 endpoints, version + edition pinned)
  - `bundle-sweep-compressed.txt` (24 endpoints, 18 OK / 3 FAIL / 3 SKIP — caught two probe bugs + the 403s)
  - `bundle-sweep-compresses-v3.txt` (29 endpoints, 24 OK / 4 FAIL / 1 SKIP — every plugin path + every adoption candidate validated)
- Plugin clients audited:
  - `sonar/src/main/kotlin/com/workflow/orchestrator/sonar/api/SonarApiClient.kt`
  - `sonar/src/main/kotlin/com/workflow/orchestrator/sonar/api/dto/SonarDtos.kt`
  - `sonar/src/main/kotlin/com/workflow/orchestrator/sonar/service/SonarServiceImpl.kt`
  - `sonar/src/main/kotlin/com/workflow/orchestrator/sonar/ui/{IssueDetailPanel,QualityDashboardPanel,GateStatusBanner,CoverageLineMarkerProvider}.kt`
- Sonar docs cross-checked via response payloads + Sonar's own 400-error enumeration of valid facets.
- Reference style template — `docs/research/2026-05-07-bamboo-audit-recommendations.md` (committed during the parallel Bamboo audit).

> **Read this first if you're picking up the Sonar audit.** Findings are categorized R-FIX (must do before ship) / R-EVOLVE (DTO + UX upgrades for 25.x) / R-ADD-AGENT (autonomous-fix workflow surfaces) / R-ADD-FEATURE (other useful adoptions) / R-DEFER (lower priority) / R-DROP (dead code from pre-25.x assumptions). Per the per-service commit policy, all approved items land in **one Sonar implementation commit** on `fix/automation-handover-quality-tabs`.

---

## 1. TL;DR

**Wire-correctness is fine. The plugin's HTTP shapes work on Community Build 25.9 — every plugin call returns the expected status codes and JSON shape.** The audit surfaces issues that sit *above* the wire: DTO-to-response staleness from Sonar 25.x schema migrations, two admin-gated endpoints that silently break for non-admin tokens, and a pile of dead degradation code that targets a Sonar tier that no longer exists.

**Top findings:**

1. **Rule descriptions render EMPTY on Sonar 25.x** — `SonarServiceImpl.kt:722` reads `dto.mdDesc ?: dto.htmlDesc ?: ""`. Both are null on 25.x because Sonar replaced them with structured `descriptionSections[{key, content}]`. **R-FIX-1.** Bug-class user-visible.
2. **`/api/ce/activity` and `/api/new_code_periods/show` return 403** for non-admin tokens on Community Build 25.9 (both endpoints have admin-only Sonar permissions). Plugin's `getAnalysisTasks()` and `getNewCodePeriod()` propagate the error as `ApiResult.Error(FORBIDDEN, "Insufficient SonarQube permissions")` but the UI presents it as "no analysis history" with no remediation hint. **R-FIX-2 / R-FIX-3.**
3. **Multi-branch is no longer Developer+** — Sonar 25.x rebrand renamed Community Edition → "Community Build" *and* moved multi-branch analysis to the free tier. Plugin's pre-25.x assumptions (`branch=` silently ignored on Community, `/api/hotspots/search` returns 404, `/api/project_branches/list` returns only main) are all dead code on 25.x. The current branch picker, hotspots panel, and per-branch gate display all work as designed against this server. **R-DROP.**
4. **Quality tab Issue list defaults to "Overall Code"** — `SonarApiClient.getIssues()` defaults `inNewCodePeriod=false`. On a feature branch, that returns the full snapshot (~720 issues for the user's audit branch — includes everyone else's pre-existing tech debt). The Sonar Web UI has Overall vs New Code tabs; Sonar's gate uses `new_*` metrics for "Clean as You Code"; users on a feature branch want to see *what they introduced*, not what was already there. **R-EVOLVE-2.** UX gap.
5. **AI Code Fix is absent on Community Build** — `/api/v2/ai-codefix/feature` and `/availability` both 404. The user's quality gates show `isAiCodeSupported: false` consistently. Agent's autonomous-fix workflow must use its own LLM path; not a Sonar-side option on this tier. **R-ADD-AGENT-4.**

**Top 5 recommended actions (ordered by user impact × effort):**

1. **R-FIX-1**: Add `descriptionSections: List<SonarRuleDescriptionSectionDto>` to `SonarRuleDto`; in `SonarServiceImpl` fall back through `mdDesc ?: htmlDesc ?: descriptionSections.joinToString { it.content } ?: ""` (preferred order: Markdown > legacy HTML > 25.x sections).
2. **R-FIX-2**: Detect 403 on `getAnalysisTasks()` and surface as "Last analysis: requires admin permission" with a settings-page hint, not as silent empty.
3. **R-FIX-3**: When `getNewCodePeriod()` returns 403, fall back to `SonarQualityGateDto.period` (already in the DTO, already populated by `getQualityGateStatus()`) — same `mode` + `parameter` shape, no extra HTTP call. Removes a 403 the user is guaranteed to hit.
4. **R-EVOLVE-2**: Add Overall Code / New Code toggle to `QualityDashboardPanel` (parity with Sonar Web UI). On non-main branches default to New Code; on main branch default to Overall.
5. **R-ADD-AGENT-1**: Adopt `/api/hotspots/show` for the hotspot detail panel — `rule.{riskDescription, vulnerabilityDescription, fixRecommendations}` is what the agent feeds the LLM for autonomous remediation.

Six total R-FIX/R-EVOLVE items (cumulative effort ≈ 1 day), four R-ADD-AGENT items (≈ 1 day), seven R-ADD-FEATURE / R-DEFER items, three R-DROP items.

---

## 2. Server snapshot

| | |
|---|---|
| **Version** | `25.9.0.112764` (`/api/server/version` — plain text body) |
| **System status** | `UP` (id `0B1824E7-AYGz2L6shWioIVeAW7Ej`, `/api/system/status`) |
| **Edition** | `community` — renamed "Community Build" in 25.x. Multi-branch + hotspots are at this tier as of 25.x. |
| **versionEOL** | `2026-04-01` (`/api/navigation/global`). 5 weeks past EOL on audit date. SonarQube ships monthly Community Build releases; minor versions stop receiving updates after ~6 months. Past-EOL means no security patches on this minor — recommend the user upgrade to latest 25.x at next maintenance window. **Not blocking for this audit.** |
| **Token** | Valid (`/api/authentication/validate.valid: true`). |
| **User** | `Subhankar Halder` (`emj96875`), groups: `[ql-vjfzbk4zw-jms-oned, sonar-users]`, `permissions.global: []` (non-admin). `usingSonarLintConnectedMode: true`. |
| **`canAdmin`** | `false` (from `/api/navigation/global`). |
| **CI integration** | `detectedCI: Bamboo` (from `/api/project_analyses/search`'s analysis records). |
| **Audit project** | One project (key redacted): 23 branches total, `develop` is main with `qualityGateStatus: OK`, `release`/`hotfix` excluded from purge. Two non-main branches were `qualityGateStatus: ERROR` at v1 sweep time. |
| **Audit branch (v3)** | `develop-PROJ-001` (most recent non-main, picked by v0.2's failing-gate heuristic). Reference branch is `release` per `/api/qualitygates/project_status.period`. |

> **The user's reference-branch new-code period is the canonical "Clean as You Code" setup**: PR/feature branches are gated against `release`, not against the previous version. This means the new-code metrics the gate enforces (`new_violations`, `new_coverage`, `new_maintainability_rating`) are **per-PR delta** vs the about-to-ship release branch. The plugin should align its UX to this mental model — the user's "have I introduced regressions?" question maps to `inNewCodePeriod=true` queries on a feature branch.

---

## 3. Findings matrix — every plugin endpoint validated

Columns: endpoint | plugin caller (file:line) | v3 status | response shape vs DTO | recommendation

| Endpoint | Plugin caller | v3 status | Shape vs DTO | Recommendation |
|---|---|---|---|---|
| `GET /api/server/version` | (probe only — plugin uses `/authentication/validate` for liveness) | OK 200 (text) | n/a | **R-NOOP** — note for `:core` health check: this is the cheapest version detect, no auth required. |
| `GET /api/system/status` | (none) | OK 200 | n/a | **R-NOOP** — feature candidate for a "Sonar UP" indicator if needed. |
| `GET /api/navigation/global` | (none) | OK 200 | n/a | **R-EVOLVE-3** uses this — plugin should detect `edition` once at startup + drop pre-25.x degrade paths. |
| `GET /api/authentication/validate` | `validateConnection()` (`SonarApiClient.kt:62`) | OK 200 (`{valid: true}`) | matches `SonarValidationDto` | **R-NOOP** |
| `GET /api/components/search?qualifiers=TRK&q={q}&ps=25` | `searchProjects(query)` (line 67) | OK 200 | matches `SonarComponentSearchResult.components` (note: `SonarProjectDto.qualifier` defaults to `"TRK"` which is correct for this query) | **R-NOOP** |
| `GET /api/project_branches/list?project={k}` | `getBranches(projectKey)` (line 74) | OK 200, **23 branches** | `SonarBranchDto` shape matches; missing `branchId` (uuid) and `excludedFromPurge` (bool). Neither is used by the plugin today; cosmetic gap. | **R-NOOP** for behavior; **R-DEFER** to add `branchId` if the plugin ever needs to deeplink to Sonar UI per branch. |
| `GET /api/qualitygates/project_status?projectKey={k}&branch={b}` | `getQualityGateStatus(projectKey, branch)` (line 81) | OK 200, status `ERROR` (5 of 21 conditions failing) | matches `SonarQualityGateDto` and `SonarConditionDto`. **Missing**: `caycStatus` ∈ {compliant, non-compliant, over-compliant} (Clean as You Code rating, new in 25.x — present in response, dropped by DTO). | **R-EVOLVE-1** — small DTO addition. |
| `GET /api/issues/search?componentKeys={k}&resolved=false&ps=500&branch={b}[&inNewCodePeriod=true]` | `getIssues(...)` and `getIssuesWithPaging(...)` (lines 90, 109) | OK 200 (720 overall issues, 7 new-code issues on the audit branch in v1; 0 new-code in v3 — branch state varies between sweeps as the team works) | matches `SonarIssueSearchResult` and `SonarIssueDto`. **`SonarIssueDto` already has CCT fields** (`cleanCodeAttribute`, `cleanCodeAttributeCategory`, `impacts: List<SonarImpactDto>`, `issueStatus`) — congrats, this part is already correct. | **R-NOOP** for shape. **R-EVOLVE-2** for the "default to new-code on feature branches" UX. |
| `GET /api/measures/component_tree?component={k}&metricKeys=...&qualifiers=FIL&ps=500&p={n}&additionalFields=period&branch={b}` | `getMeasures(projectKey, branch, metricKeys)` (line 130) | OK 200, 532 files in baseComponent's snapshot of audit branch (paginates) | matches `SonarMeasureSearchResult`, `SonarMeasureComponentDto`, `SonarMeasureDto`. `additionalFields=period` is included by the plugin and works correctly — `new_*` metrics arrive in `period.value` not `value`, and `SonarMeasureDto.effectiveValue()` correctly disambiguates. The `s=metric&metricSort=new_lines_to_cover&asc=false` sort the plugin sends is honored. | **R-NOOP** — plugin's pagination contract (10 pages × 500 ps cap) is right-sized for a 532-file project. |
| `GET /api/measures/component?component={k}&metricKeys=...&additionalFields=period&branch={b}` | `getProjectMeasures(projectKey, branch, metricKeys)` (line 184) | OK 200 | matches `SonarComponentMeasureResponse.component.measures` | **R-NOOP** |
| `GET /api/ce/activity?component={k}&ps=10` | `getAnalysisTasks(projectKey)` (line 199) | **FAIL 403** — `{errors:[{msg:"Insufficient privileges"}]}` | n/a — error envelope only | **R-FIX-2** — see §4.1. Plugin shows "no analysis history" silently; user has no idea it's a permission issue. |
| `GET /api/ce/task?id={id}&additionalFields=...` | `getCeTask(taskId)` (line 207) | SKIP (no task id available — `ce/activity` 403'd, no fallback) | n/a | **R-NOOP** — used only after a successful CE poll, which non-admin users can't reach anyway. Acceptable to keep. |
| `GET /api/new_code_periods/show?project={k}[&branch={b}]` | `getNewCodePeriod(projectKey, branch)` (line 213) | **FAIL 403** | n/a | **R-FIX-3** — see §4.2. Fall back to `SonarQualityGateDto.period` which is already populated by `getQualityGateStatus()`. |
| `GET /api/hotspots/search?project={k}&ps=500&branch={b}` | `getSecurityHotspots(projectKey, branch)` (line 225) | OK 200, 1 hotspot returned (`java:S2245`) | matches `SonarHotspotSearchResult`, `SonarHotspotDto`. Pre-25.x Community returned 404 — plugin's "Developer Edition+" comment is now stale (was true in 9.x/10.x; multi-branch + hotspots moved to Community Build in 25.x). | **R-NOOP** for shape; **R-DROP** the "Developer Edition+" comment in the KDoc + any guarded callsites. |
| `GET /api/duplications/show?key={fileKey}[&branch={b}]` | `getDuplications(componentKey, branch)` (line 238) | OK 200 (empty `duplications: []` on the sample file — file has no duplicates; shape verified) | matches `SonarDuplicationsResponse`, `SonarDuplicationDto`, `SonarDuplicationBlockDto`, `SonarDuplicationFileDto` | **R-NOOP** — note: `SonarDuplicationFileDto.projectName` is the field name in the plugin's DTO; Sonar's response uses `project` (no `Name`). Currently parses but as `""` default. **Cosmetic gap** — fix or accept. |
| `GET /api/rules/show?key={ruleKey}` | `getRule(ruleKey)` (line 250) | OK 200 | **DTO MISMATCH (R-FIX-1)**. `SonarRuleDto` has only `htmlDesc`, `mdDesc`, `tags`, `remFnBaseEffort`. **Sonar 25.x sends `descriptionSections: [{key:"root_cause", content:"<p>..."}, {key:"resources", content:"..."}]` and OMITS `htmlDesc`/`mdDesc` entirely.** Plugin's `SonarServiceImpl.kt:722` does `dto.mdDesc ?: dto.htmlDesc ?: ""` → empty string → IssueDetailPanel renders empty rule description. **Real bug, user-visible.** Also missing: `cleanCodeAttribute`, `cleanCodeAttributeCategory`, `impacts: List<SonarImpactDto>`, `educationPrinciples`, `langName`, `descriptionSections`. | **R-FIX-1** — see §5.1. |
| `GET /api/sources/lines?key={k}&from={n}&to={m}&branch={b}` | `getSourceLines(componentKey, from, to, branch)` (line 257) | OK 200, 50 lines returned with `{line, code, scmRevision, scmAuthor, scmDate, duplicated, isNew}` | matches `SonarSourceLineDto` (which has `lineHits, conditions, coveredConditions, isNew` — all populated when the file has coverage; absent for files without). The DTO has `code: String = ""` default but the response always includes `code` — match is fine. | **R-NOOP** for shape. **R-ADD-AGENT-2** documents how the agent uses `isNew + lineHits + conditions + coveredConditions` to target precise fixes. |

**Total plugin endpoints validated: 14** (every read path in `SonarApiClient.kt`). **Zero writes** (the plugin is a pure read consumer of Sonar; no `POST`/`PUT`/`DELETE` exists in the module — confirmed by grep across `:sonar` and `:core`).

---

## 4. Real failures: per-endpoint analysis

### 4.1 `/api/ce/activity` returns 403 for non-admin tokens — **R-FIX-2**

| | |
|---|---|
| **Probe** | `bundle-sweep-compresses-v3.unpacked/raw/ce_activity.json` |
| **Status** | 403, body `{"errors":[{"msg":"Insufficient privileges"}]}` |
| **Why it 403s** | Sonar 25.x admin-gates `/api/ce/activity` for non-component-admin tokens. Per Sonar's documented permission model: this endpoint requires `Administer System` or `Administer` permission on the component. The user's PAT has `permissions.global: []` (per `/api/users/current`) — non-admin. |
| **Plugin behavior today** | `SonarApiClient.getAnalysisTasks` (`SonarApiClient.kt:197–202`) returns `ApiResult.Error(ErrorType.FORBIDDEN, "Insufficient SonarQube permissions")`. UI consumers (Quality tab's "Last analysis" indicator + the Coverage tab's analysis-staleness chip — both are reading this) silently fall through to "no analysis" empty state with no error surfaced to the user. |
| **User-visible symptom** | "Last analysis" chip is blank on the Quality tab even though analyses are running. User has no idea it's a permission issue — looks like a plugin bug. |
| **Recommended fix** | Detect `ErrorType.FORBIDDEN` specifically in `SonarDataService` (where `getAnalysisTasks` is consumed) and surface a one-time toast: "Last analysis history requires Sonar 'Administer Project' permission. Open SonarQube, go to Project Settings → Permissions to grant." Or: an inline empty-state on the Quality tab: "Analysis history hidden — your Sonar token doesn't have Administer Project permission. [Open Sonar permissions]". |
| **Token-level workaround for the user** | Generate a Sonar token from an account that has `Administer System` or project-admin scope. The plugin can't work around the permission gate, but a clear error message lets the user fix it. |
| **Test** | Service-level test: feed `getAnalysisTasks` a 403 response, assert the UI surfaces the "permission required" hint, not silent empty. |

### 4.2 `/api/new_code_periods/show` returns 403 — fallback already in DTO **R-FIX-3**

| | |
|---|---|
| **Probe** | `bundle-sweep-compresses-v3.unpacked/raw/new_code_period_show.json` |
| **Status** | 403, same `Insufficient privileges` envelope |
| **Why it 403s** | Same admin-gating in Sonar 25.x: `/api/new_code_periods/show` requires `Administer Project`. Non-admin tokens get 403. |
| **Plugin behavior today** | `SonarApiClient.getNewCodePeriod` (line 211) returns `ApiResult.Error(FORBIDDEN, ...)`. The Quality tab's "New Code Period" chip (showing things like "since release branch" or "last 30 days") goes blank. |
| **The free fix** | **The same data is already populated on `/api/qualitygates/project_status`'s `period` field** — fetched on every Quality tab refresh. `SonarQualityGateDto.period: SonarGatePeriodDto?` carries `mode` (e.g. `REFERENCE_BRANCH`) and `parameter` (e.g. `release`) — the **exact** fields a non-403'd `/api/new_code_periods/show` would return. Plugin already calls this endpoint on every gate refresh. |
| **Recommended fix** | In `SonarServiceImpl`, when `getNewCodePeriod()` returns 403, **don't** retry — read `latestGateStatus.period` from the cached state instead. Drop the `getNewCodePeriod()` call entirely if it never works for non-admin users, OR keep it and just suppress the 403 (it'll be a no-op when the gate response carries the same data). |
| **Why this matters for the agent** | The agent's "is this line in the new-code period?" decision needs the period definition. Today: 403, no data, agent has to fall back to git heuristics. After R-FIX-3: free, no extra HTTP call, agent gets the period definition from data the plugin already has. |
| **Test** | Verify `SonarQualityGateDto.period.mode == "REFERENCE_BRANCH"` and `parameter == "release"` round-trip through the existing gate-status path (the v3 raw payload confirms both). |

### 4.3 `/api/v2/ai-codefix/feature` and `/availability` return 404 — feature absent on Community Build — **R-ADD-AGENT-4**

| | |
|---|---|
| **Probes** | `ai_codefix_feature.json`, `ai_codefix_availability.json` |
| **Status** | Both 404 |
| **Why** | Sonar's AI Code Fix is part of "AI Code Assurance" — gated to Enterprise + a paid AI subscription. The user's `/api/qualitygates/list` confirms `isAiCodeSupported: false` on every quality gate, including the default. AI Code Fix endpoints are absent on this server. |
| **Implication for the agent** | Agent's "fix to green" workflow must use its **own** LLM path (Cline-ported ReAct loop already in `:agent` module). Sonar will not provide pre-baked code fixes. |
| **What the agent gets instead** | `/api/hotspots/show.rule.fixRecommendations` — which is an HTML block from Sonar's rule definition with a literal "Compliant Solution" code example (e.g. for `java:S2245`: `SecureRandom random = new SecureRandom();`). That's **not** AI-generated, it's curated by Sonar's rules team — and it's exactly what the agent needs as the LLM prompt's "good pattern" example. |
| **R-ADD-AGENT-4 recommendation** | Don't probe AI Code Fix at runtime (it'll always 404 here). When the user upgrades to a Sonar tier that includes AI Code Fix, they can re-run the probe and we add the integration in a follow-up PR. For now, document in `:agent` module that Sonar-side AI fixes are not available + the agent has to use its own LLM. |

---

## 5. Plugin DTO gaps for Sonar 25.x

### 5.1 `SonarRuleDto` is missing `descriptionSections` — **R-FIX-1**

| | |
|---|---|
| **Plugin DTO today** (`SonarDtos.kt:319–326`) | `data class SonarRuleDto(key, name, htmlDesc?, mdDesc?, remFnBaseEffort?, tags)` |
| **Sonar 25.x response** | `{key, repo, name, severity, status, isTemplate, tags, sysTags, lang, langName, params, defaultDebtRemFnType, debtRemFnType, type, defaultRemFnType, defaultRemFnBaseEffort, remFnType, remFnBaseEffort, remFnOverloaded, scope, isExternal, descriptionSections:[{key:"root_cause"\|"resources"\|"how_to_fix"\|"introduction", content:"<HTML>"}], educationPrinciples:[], updatedAt, cleanCodeAttribute, cleanCodeAttributeCategory, impacts:[{softwareQuality,severity}]}`. **`htmlDesc` and `mdDesc` are NOT present.** They were deprecated in Sonar 9.6 and removed by 25.x. |
| **Plugin consumer** | `SonarServiceImpl.kt:722` — `description = dto.mdDesc ?: dto.htmlDesc ?: ""`. Both are null in 25.x → `description = ""`. **`IssueDetailPanel`'s rule description block renders empty.** Bug-class. |
| **Recommended fix** | Add `descriptionSections: List<SonarRuleDescriptionSectionDto> = emptyList()` to `SonarRuleDto`, plus a new `SonarRuleDescriptionSectionDto(key: String, content: String)` data class. In `SonarServiceImpl`, fall back through `mdDesc ?: htmlDesc ?: descriptionSections.joinToString("\n\n") { it.content } ?: ""`. Order matters — older Sonar (9.x) servers still ship `htmlDesc` and not the sections; newer ones ship sections only. |
| **Bonus while we're there** | Also add `cleanCodeAttribute`, `cleanCodeAttributeCategory`, `impacts: List<SonarImpactDto>` to `SonarRuleDto` — these appear on every rule in 25.x and let the agent rank rules by software quality without re-fetching the issue list. Plus `educationPrinciples: List<String>` if rule remediation tutorials are ever surfaced (currently empty for `java:S1135`, populated for security rules). |
| **Test** | Round-trip `tools/atlassian-probe/Result_Sonar/bundle-sweep-compresses-v3.unpacked/raw/rules_show.json` through `SonarRuleDto` deserialization and assert `descriptionSections` is non-empty + at least one section has a `key` of `"root_cause"`. |

### 5.2 `SonarQualityGateDto` is missing `caycStatus` — **R-EVOLVE-1**

| | |
|---|---|
| **Plugin DTO today** (`SonarDtos.kt:42–47`) | `data class SonarQualityGateDto(status, conditions, period?)` |
| **Sonar 25.x response** | Adds `caycStatus: "compliant"\|"non-compliant"\|"over-compliant"`. Verified in `quality_gate_status.json` raw. |
| **What it means** | "Clean as You Code" compliance — whether the gate enforces enough new-code-only conditions to actually catch regressions. `over-compliant` on the user's gate is good news (it has more new-code conditions than the bare minimum CaYC standard). `non-compliant` would mean the gate is still using legacy overall-code-only thresholds (treadmill-of-tech-debt pattern). |
| **Why surface it** | Two scenarios. (a) User's gate is `non-compliant` — show a one-time hint in `GateStatusBanner`: "This gate doesn't enforce new-code conditions. Sonar Web UI's 'Quality Gates' settings page can help you adopt Clean as You Code." (b) User's gate is `compliant`/`over-compliant` — surface that as a positive badge: "✓ CaYC compliant". |
| **Recommended fix** | Add `caycStatus: String = ""` to `SonarQualityGateDto`. Optional UI surfacing; not bug-class. |

### 5.3 `SonarIssueSearchResult` has no `facets` field — **R-ADD-AGENT-3** (see §6.3)

`SonarIssueSearchResult` today is `{paging, issues}`. To adopt the facet-based triage probe (§6.3), it needs `facets: List<SonarFacetDto> = emptyList()` plus a `SonarFacetDto(property: String, values: List<SonarFacetValueDto>)` and `SonarFacetValueDto(val: String, count: Int)`. Strict no-op for existing callers (default empty list); only matters when `facets=...` is passed in the URL.

### 5.4 `SonarBranchDto` is missing `branchId` and `excludedFromPurge` — **cosmetic**

`SonarBranchDto` today has `(name, isMain, type, status?, analysisDate?)`. The 25.x response also includes `branchId: <uuid>` and `excludedFromPurge: bool`. Neither is consumed today; `branchId` would be useful for deep-linking to Sonar Web UI per branch (`<sonar>/dashboard?id=<key>&branch=<name>` works without the UUID, but `excludedFromPurge` could power a "release branch" badge in the picker). **R-DEFER**.

### 5.5 `SonarDuplicationFileDto.projectName` vs response's `project` — **cosmetic**

Plugin DTO has `projectName: String`. Sonar's response field is `project: String`. Currently parses as `""` default — silently wrong, but no UI uses it today. **R-DEFER** (rename and add `@SerialName("project")` if the field is ever consumed).

---

## 6. Agent-targeted endpoint adoption — **R-ADD-AGENT**

The agent's "fix to green Sonar" workflow needs targeted, structured data per issue / per file / per project. The current plugin surfaces enough for a UI dashboard but not for autonomous remediation. These four adoptions close the gap.

### 6.1 R-ADD-AGENT-1: `/api/hotspots/show` for full hotspot detail

| | |
|---|---|
| **Why** | Plugin today calls `/api/hotspots/search` only — returns the LIST of hotspots with location + severity. **The agent needs `rule.riskDescription`, `rule.vulnerabilityDescription`, `rule.fixRecommendations`** to construct an LLM prompt for autonomous remediation. Those three fields are only on `/api/hotspots/show?hotspot={key}`. |
| **Sample payload** | For `java:S2245` (PRNG security-sensitive), `fixRecommendations` is HTML with: `<h2>Recommended Secure Coding Practices</h2><ul><li>Use SecureRandom...</li></ul><h2>Compliant Solution</h2><pre>SecureRandom random = new SecureRandom(); ...</pre>`. The `<pre>` block is the literal example fix the agent shows the LLM as the "good pattern". |
| **Critical caveat: `canChangeStatus: false`** | The user's PAT can NOT mark hotspots as fixed/safe/acknowledged — that requires `Administer Security Hotspots` perm. The agent's autonomous-fix flow has to be: (1) edit code, (2) push, (3) wait for re-analysis to re-evaluate the hotspot from the new code. **The agent cannot directly close a hotspot through the Sonar API on this server.** Worth documenting in `:agent` system prompt as a constraint. |
| **What to add** | New method `SonarApiClient.getHotspotDetail(hotspotKey: String): ApiResult<SonarHotspotDetailDto>` calling `/api/hotspots/show?hotspot={key}`. New DTO `SonarHotspotDetailDto(key, component, project, rule: SonarHotspotRuleDto, status, line, message, ...)` and `SonarHotspotRuleDto(key, name, vulnerabilityProbability, securityCategory, riskDescription, vulnerabilityDescription, fixRecommendations)`. Plus `changelog: List<SonarHotspotChangelogDto>` to surface the analysis-branch lineage (which is gold for "this hotspot is pre-existing, not introduced by this PR"). |
| **UI integration** | Extend `IssueDetailPanel` (or create a sibling `HotspotDetailPanel`) to render the three rule HTML blocks. Plugin's existing HTML rendering (used elsewhere) handles `<pre>` and `<ul>` cleanly. |
| **Agent integration** | New core service method `SonarService.getHotspotDetail()` returning `ToolResult<HotspotDetail>` per the §"Service Architecture" rule in the project CLAUDE.md. Agent tool wrapper in `:agent` exposes it as e.g. `sonar_hotspot_detail` so the LLM can fetch fix guidance per hotspot before editing. |

### 6.2 R-ADD-AGENT-2: Document agent usage of `/api/sources/lines` (already wired)

| | |
|---|---|
| **Why no DTO change is needed** | `SonarSourceLineDto` already has `line, code, scmRevision, scmAuthor, scmDate, duplicated, isNew, lineHits, conditions, coveredConditions`. **All the data the agent needs for line-level decisions is already in the plugin.** |
| **What the agent does with each field** | `isNew == true` → "this line falls in the new-code period; targeting fixes here keeps the gate from regressing." `lineHits == 0 && conditions == null` → "uncovered statement — write a test that executes this line." `lineHits > 0 && conditions != null && coveredConditions < conditions` → "compound predicate — write a test that exercises the missing branch (Sonar tracks per-condition coverage)." `duplicated == true` → "this line is part of a duplication; pair with `/api/duplications/show` to find the other copies." |
| **What's missing today** | Nothing data-side. UX-side: the gutter line marker in `CoverageLineMarkerProvider` doesn't surface conditions vs coveredConditions distinction (plugin shows red for blocker/critical, yellow for major, grey for minor — based on issue severity, not on coverage state). For the agent's prompt, the data is fine; the LLM just needs the agent to format it right. |
| **Recommended action** | Add a paragraph to `:agent/CLAUDE.md` documenting how the agent should use these fields. No DTO change. **Effort: 10 lines of prose.** |

### 6.3 R-ADD-AGENT-3: `/api/issues/search?facets=...&inNewCodePeriod=true` for triage prioritization

| | |
|---|---|
| **Why** | Without facets, the agent has to walk all N new-code issues to compute "how many are RELIABILITY/HIGH vs MAINTAINABILITY/LOW". With facets, one HTTP call returns the count breakdown. For a 720-issue file that's a 720× speedup on the triage path. |
| **Sample response** | `{paging, issues:[1], facets:[{property:"impactSoftwareQualities", values:[{val:"MAINTAINABILITY", count:7}, {val:"RELIABILITY", count:3}, {val:"SECURITY", count:0}]}, {property:"impactSeverities", values:[...]}, {property:"files", values:[...]}, ...]}`. |
| **Valid facet names on Sonar 25.x** | (per Sonar's own 400-error enumeration when sent an invalid one): `projects, files, assigned_to_me, severities, statuses, resolutions, rules, assignees, author, directories, scopes, languages, tags, types, pciDss-3.2, pciDss-4.0, owaspAsvs-4.0, owaspMobileTop10-2024, stig-ASD_V5R3, casa, sansTop25, cwe, createdAt, sonarsourceSecurity, codeVariants, cleanCodeAttributeCategories, impactSoftwareQualities, impactSeverities, issueStatuses, prioritizedRule`. **Note `files`, NOT `fileUuids`** (the latter caused a 400 in our v2 sweep — bug fixed in probe v0.3). |
| **Recommended adoption** | New method `SonarApiClient.getIssueFacets(projectKey, branch, inNewCodePeriod, facets)` returning a new `SonarIssueFacetsResponse` DTO. Plugin uses it to render a "summary chip" on the Quality tab when on a feature branch: "7 new code smells, 0 new bugs, 0 new vulnerabilities" — the same triage view the Sonar Web UI's New Code tab shows at the top of its issue list. |
| **Agent integration** | Same pattern: core `SonarService.getIssueFacets()` → `ToolResult<IssueFacets>` → `:agent` tool wrapper. The agent calls this BEFORE walking the issue list to decide priority order. |

### 6.4 R-ADD-AGENT-4: AI Code Fix is unavailable — agent uses its own LLM (no integration needed)

Already covered in §4.3. No DTO, no client, no UI change. Documentation-only: note in `:agent` system prompt that Sonar AI Code Fix is not available on Community Build, agent must use its own ReAct loop for remediation.

---

## 7. UX gaps surfaced by the audit

### 7.1 R-EVOLVE-2: Overall Code vs New Code parity with Sonar Web UI

The Sonar Web UI's project page has two top-level tabs: **Overall Code** and **New Code**. Users instinctively switch to **New Code** on a feature branch because that's what their PR introduces. The plugin's `QualityDashboardPanel` has no equivalent toggle — it shows everything from `getIssues(..., inNewCodePeriod=false)` always.

**Recommended fix:**
- Add a tab toggle (or a `JBSegmentedButton`) at the top of `QualityDashboardPanel`: "Overall Code | New Code".
- When the active branch is the project's main branch (`isMain: true` per `/api/project_branches/list`), **default to Overall Code** — the new-code period on main is empty/self-comparison.
- When the active branch is non-main, **default to New Code**.
- Persist the user's last choice per project.
- Wire both `IssueListPanel` and `CoverageTablePanel` to listen to the toggle: when New Code is active, both call `getIssues(inNewCodePeriod=true)` + `getMeasures(metricKeys=new_*)` respectively.

The data is there — `getIssues()` already accepts `inNewCodePeriod`, the `new_*` metric keys are already in `DEFAULT_METRIC_KEYS`. This is purely a UI wiring change + a default-mode heuristic.

**Why this is high-value:** on a feature branch with 720 overall-code issues vs 7 new-code issues, the Quality tab today shows 720 — most of which are someone else's tech debt. Users learn to ignore the tab. With New Code default, users see 7 — actionable. The agent's fix-to-green workflow operates on the same 7. UX and agent both align around new-code-first.

### 7.2 R-EVOLVE-2 follow-on: `GateStatusBanner` should explain WHICH conditions failed

Today, when the gate is `ERROR`, `GateStatusBanner` shows "Quality gate failed" without detail. The conditions are right there in the response (`SonarConditionDto` carries `metricKey`, `actualValue`, `errorThreshold`, `comparator`). Show them: "Failed: 7 new code smells (threshold: 0); Maintainability rating D (threshold: A); …".

The agent prompt would benefit from this too — instead of "the gate is failing, here are the issues, figure it out", the agent gets "the gate is failing because new_code_smells=7 vs threshold=0 and new_maintainability_rating=D vs A — fix issues that improve those two specifically".

### 7.3 R-FIX-1 follow-on: rule description rendering

Already in §5.1. Once `descriptionSections` is in the DTO, `IssueDetailPanel` should iterate sections by key:
- `root_cause` → render first as the "what" + "why"
- `how_to_fix_it` → render second as the "how" (when present)
- `resources` → render last as a collapsible "Learn more" footer

Different rules ship different section sets — security rules tend to have all four; code-smell rules often have only `root_cause` + `resources`. Render whatever's present.

### 7.4 R-FIX-2 follow-on: Permission-aware empty states

Already in §4.1. The pattern generalizes: any time a Sonar endpoint returns `ErrorType.FORBIDDEN`, the empty-state should say "X requires Sonar permission Y" rather than "no data". A small reusable composable (`SonarPermissionEmptyState(missingPerm: String)`) covers `getAnalysisTasks`, `getNewCodePeriod`, and any future admin-gated endpoints.

---

## 8. Multi-branch on Community Build — **R-DROP** dead code

Pre-25.x assumptions baked into the plugin and the audit's own probe v0:

- "Community ignores `branch=` on `/api/issues/search`, `/api/measures/component_tree`, `/api/qualitygates/project_status`."
- "`/api/hotspots/search` returns 404 on Community."
- "`/api/project_branches/list` returns only main on Community."
- "`/api/new_code_periods/show` is project-scoped only on Community."

**All of these are false on Sonar 25.x Community Build.** Verified across the v3 sweep:
- `branch=` honored on all four endpoints.
- `/api/hotspots/search` returns the full hotspot list (1 hotspot for the audit branch).
- `/api/project_branches/list` returns 23 branches with full metadata including `isMain`, `qualityGateStatus`, `analysisDate`, `branchId`.
- `/api/new_code_periods/show` *does* 403 — but on permission scope, not on edition.

**Action items:**

- **R-DROP-1**: Search the `:sonar` module for `if (edition == "community")` guards. Any code that silently degrades to single-branch / no-hotspots / project-scoped-period on Community is dead on 25.x. Delete it.
- **R-DROP-2**: Update `SonarApiClient` KDoc on `getSecurityHotspots` — currently says "(Developer Edition+)". On 25.x: available at all paid + Community Build tiers. Update to "(SonarQube 25.x+ at all editions; pre-25.x Community returns 404)".
- **R-DROP-3**: Probe `tools/sonar-probe/probe_sonar.py` was originally written with the same stale predictions. v0.1 commit `acf72425` corrected the per-endpoint notes; the `_edition_capability_note` function in v0.3 has the correct description. Memory file `project_sonar_audit_in_progress.md` is also updated.

---

## 9. Other feature candidates

| Endpoint | v3 status | Recommendation |
|---|---|---|
| `/api/users/current` | OK 200 | **R-ADD-FEATURE-1**. Add to settings page: "Connected as ${name} (${login})". Plus the response's `permissions.global` array tells the plugin whether the user has Sonar admin perms — useful to gate the "Settings → Permissions" hint from R-FIX-2 conditionally. |
| `/api/qualitygates/list` | OK 200 (17 gates on this server) | **R-ADD-FEATURE-2**. Settings page: "Project's gate: ${gate.name} (${gate.caycStatus})". Useful pre-analysis context. |
| `/api/project_analyses/search` | OK 200 (8 analyses on the audit branch) | **R-DEFER**. Could replace `getAnalysisTasks` (which 403s) for non-admin users — `/api/project_analyses/search` returns analysis history without admin gating, with `{key, date, projectVersion, events:[{category:"VERSION","name":"1.79.0-SNAPSHOT"}], revision, detectedCI:"Bamboo", manualNewCodePeriodBaseline}`. The `revision` field is the git SHA — agent could use this to identify which commits are reflected in the latest analysis. **Why DEFER**: requires a new client method + DTO + service-layer integration. R-FIX-2's "permission-required hint" handles the user-visible gap with less code. Pick this up later if the agent needs analysis-trail data. |
| `/api/measures/search_history` | OK 200 | **R-DEFER**. Returns metric history (`coverage` over time). Could power a "trend" sparkline on the Overview card. Cosmetic. |
| `/api/issues/tags?project={k}` | OK 200 (project-scoped now that `?project=` is correct) | **R-DEFER**. Could power a tag filter chip on `IssueListPanel`. Cosmetic. |
| `/api/languages/list` | OK 200 (27 languages installed on this server) | **R-DEFER**. Could power a "this Sonar covers X, Y, Z" chip. Cosmetic. |

---

## 10. Implementation sequencing

Recommended order for the single Sonar integration commit:

1. **R-FIX-1**: `SonarRuleDto.descriptionSections` + `SonarServiceImpl` fallback chain. (Bug-class — fixes empty rule descriptions.)
2. **R-FIX-2**: Detect 403 on `getAnalysisTasks`; surface "permission required" hint in the Quality tab's last-analysis empty state.
3. **R-FIX-3**: Drop or suppress `getNewCodePeriod()`; consume `SonarQualityGateDto.period` instead. (Removes a guaranteed 403 for non-admin users.)
4. **R-EVOLVE-1**: `caycStatus` on `SonarQualityGateDto`.
5. **R-EVOLVE-2**: Overall Code / New Code toggle on `QualityDashboardPanel`. Default-mode heuristic per branch.
6. **R-EVOLVE-3** (covered in R-DROP-1/2): delete dead Community-edition guards + KDoc.
7. **R-ADD-AGENT-1**: `/api/hotspots/show` adoption — new client method, DTO, core service method, agent tool wrapper.
8. **R-ADD-AGENT-3**: `/api/issues/search?facets=...` adoption — extend search result DTO, new triage chip, agent tool wrapper.
9. **R-ADD-FEATURE-1, R-ADD-FEATURE-2**: settings page enhancements (`/api/users/current` + `/api/qualitygates/list`).

Items 1–3 are bug fixes and should land first. Items 4–9 are enhancements; can ship together or split if time-pressured.

`:agent`-side changes (system prompt note re AI Code Fix unavailability + new `sonar_hotspot_detail` + `sonar_issue_facets` tool wrappers) land in the same commit since the audit is for the agent's workflow.

**Estimated effort:** 1 day for items 1–6, 1 day for items 7–9 (+ corresponding `:agent` plumbing). One PR.

---

## 11. Companion files

Probe driver and bundles, all on `fix/automation-handover-quality-tabs`:

- `tools/sonar-probe/probe_sonar.py` (commits `89411137` v0 → `fb12aa36` v0.3)
- `tools/sonar-probe/README.md`
- `tools/atlassian-probe/Result_Sonar/bundle-versions-only.txt` (4 endpoints, version + edition pinned)
- `tools/atlassian-probe/Result_Sonar/bundle-sweep-compressed.txt` (24 endpoints, v0 sweep)
- `tools/atlassian-probe/Result_Sonar/bundle-sweeep-compresesd-v2.txt` (29 endpoints, v0.1 sweep — caught probe bugs in `project_analyses_search`, `issues_tags`, `issues_search_facets_new_code`)
- `tools/atlassian-probe/Result_Sonar/bundle-sweep-compresses-v3.txt` (29 endpoints, v0.3 sweep — clean data, basis of this doc)

Memory file: `~/.claude/projects/-Users-subhankarhalder-Desktop-Programs-scripts-IntelijPlugin/memory/project_sonar_audit_in_progress.md` (will be deleted when this implementation commit lands).

---

## 12. Quick-reference: bug-class items only

For triage by anyone who skims this doc:

- **Rule descriptions render empty on Sonar 25.x.** Fix: add `descriptionSections` to `SonarRuleDto` + chain in `SonarServiceImpl.kt:722`. (R-FIX-1)
- **"Last analysis" indicator is silently blank for non-admin users.** Fix: detect 403 + show "permission required" empty state. (R-FIX-2)
- **`getNewCodePeriod()` is guaranteed-403 for non-admin and the same data is in `SonarQualityGateDto.period`.** Fix: drop the call; read from gate. (R-FIX-3)

Everything else is enhancement.
