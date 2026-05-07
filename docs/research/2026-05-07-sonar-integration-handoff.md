# Sonar integration commit — handoff for next session

**You're picking this up cold. This file tells you what to do, in what order, with which files.** The audit is done; the integration is the deliverable.

---

## TL;DR

- The Sonar audit is complete. Recs doc lives at `docs/research/2026-05-07-sonar-audit-recommendations.md`.
- Your job is **one integration commit** on `fix/automation-handover-quality-tabs` that lands the approved items from the recs doc.
- All the data you need (probe results, validated payloads, plugin DTOs) is on disk. Don't re-run the probe — it's been validated three times.
- Server pinned: **SonarQube Community Build 25.9.0.112764**, edition `community`, non-admin token. Server is 5 weeks past versionEOL (2026-04-01) — not blocking.

---

## Required reading, in order

1. **`docs/research/2026-05-07-sonar-audit-recommendations.md`** (333 lines) — the recs doc. Read end-to-end. §1 TL;DR + §3 findings matrix + §10 implementation sequencing are the load-bearing sections.
2. **`~/.claude/projects/-Users-subhankarhalder-Desktop-Programs-scripts-IntelijPlugin/memory/project_sonar_audit_in_progress.md`** (auto-loaded via `MEMORY.md`) — distilled status of the audit. Same content as the recs doc but compressed.
3. **`sonar/CLAUDE.md`** — module overview. Auth scheme, endpoint inventory, architecture (api → service → ui).
4. **Plugin clients (read these before writing code):**
   - `sonar/src/main/kotlin/com/workflow/orchestrator/sonar/api/SonarApiClient.kt` (320 lines)
   - `sonar/src/main/kotlin/com/workflow/orchestrator/sonar/api/dto/SonarDtos.kt` (343 lines — single file, all DTOs)
   - `sonar/src/main/kotlin/com/workflow/orchestrator/sonar/service/SonarServiceImpl.kt` — line 722 is where R-FIX-1's `mdDesc ?: htmlDesc ?: ""` chain lives
   - `sonar/src/main/kotlin/com/workflow/orchestrator/sonar/ui/IssueDetailPanel.kt` — where R-FIX-1's empty rule description renders
   - `sonar/src/main/kotlin/com/workflow/orchestrator/sonar/ui/QualityDashboardPanel.kt` — R-EVOLVE-2's tab toggle target
   - `sonar/src/main/kotlin/com/workflow/orchestrator/sonar/ui/GateStatusBanner.kt` — R-EVOLVE-2 follow-on (show failed conditions)
5. **Validated payloads to round-trip in tests** (already on disk under `tools/atlassian-probe/Result_Sonar/bundle-sweep-compresses-v3.unpacked/raw/`):
   - `rules_show.json` — for R-FIX-1's new `descriptionSections` parsing test
   - `quality_gate_status.json` — for R-EVOLVE-1's `caycStatus` round-trip + R-FIX-3's `period` reuse
   - `hotspots_show.json` — for R-ADD-AGENT-1's new `SonarHotspotDetailDto` round-trip
   - `issues_search_facets_new_code.json` — for R-ADD-AGENT-3's `facets` field on the search result
   - `users_current.json`, `qualitygates_list.json` — for R-ADD-FEATURE-1/2

**You should NOT re-run the probe.** v0.3 is on disk, three sweeps already validated everything. Re-running burns minutes for no information gain.

---

## Implementation checklist

### Phase 1 — bug-class fixes (R-FIX, ship-blocking)

- [ ] **R-FIX-1** — Rule descriptions render empty on Sonar 25.x.
  - Add new DTO `SonarRuleDescriptionSectionDto(val key: String = "", val content: String = "")` to `SonarDtos.kt`.
  - Extend `SonarRuleDto` (`SonarDtos.kt:319`) with `val descriptionSections: List<SonarRuleDescriptionSectionDto> = emptyList()` plus `cleanCodeAttribute`, `cleanCodeAttributeCategory`, `impacts: List<SonarImpactDto>`, `educationPrinciples: List<String>`. All optional with defaults — older Sonar parses cleanly.
  - Change `SonarServiceImpl.kt:722` from `description = dto.mdDesc ?: dto.htmlDesc ?: ""` to:
    ```kotlin
    description = dto.mdDesc
        ?: dto.htmlDesc
        ?: dto.descriptionSections
            .filter { it.content.isNotBlank() }
            .joinToString("\n\n") { it.content }
            .ifBlank { null }
        ?: ""
    ```
  - **Test**: round-trip `rules_show.json` raw payload through `SonarRuleDto` deserialization; assert `descriptionSections` is non-empty + at least one section's `key` is `"root_cause"`.
  - **UI test**: open `IssueDetailPanel` on a Sonar 25.x server (or load fixture), assert rule description is non-empty.

- [ ] **R-FIX-2** — `/api/ce/activity` 403 silently blanks the "Last analysis" indicator.
  - In `SonarServiceImpl` (or wherever `getAnalysisTasks` is consumed), detect `ApiResult.Error(ErrorType.FORBIDDEN, _)` specifically.
  - Surface a one-time inline empty state in the Quality tab: "Analysis history hidden — your token doesn't have **Administer Project** permission. [Open Sonar permissions]" where the link opens `<sonarBaseUrl>/project_roles?id=<projectKey>`.
  - **Test**: feed `getAnalysisTasks` a 403 response, assert the UI surfaces the permission hint, not silent empty.

- [ ] **R-FIX-3** — `/api/new_code_periods/show` 403 has a free workaround.
  - `SonarQualityGateDto.period: SonarGatePeriodDto?` (`SonarDtos.kt:46`) already carries `mode` + `parameter` — same fields a non-403'd `getNewCodePeriod()` would return.
  - Option A (preferred): drop `getNewCodePeriod()` entirely. Update callers to read `cachedGateStatus.period`.
  - Option B (conservative): keep `getNewCodePeriod()` but suppress 403 — return `Success(SonarNewCodePeriodDto(...))` synthesized from the gate's `period` block.
  - **Test**: round-trip `quality_gate_status.json` and assert `period.mode == "REFERENCE_BRANCH"` + `period.parameter == "release"` are extractable.

### Phase 2 — DTO + UX upgrades (R-EVOLVE)

- [ ] **R-EVOLVE-1** — Add `val caycStatus: String = ""` to `SonarQualityGateDto` (`SonarDtos.kt:43`). Optionally surface as a badge in `GateStatusBanner` (`compliant`/`over-compliant` → green check, `non-compliant` → "this gate doesn't enforce new-code conditions" hint).

- [ ] **R-EVOLVE-2** — Overall Code / New Code toggle on `QualityDashboardPanel`.
  - Add a `JBSegmentedButton` or `JBTabbedPane` with two modes.
  - Default heuristic: `if (activeBranch?.isMain == true) Overall else NewCode`. Persist last choice per project.
  - Wire `IssueListPanel` to call `getIssues(..., inNewCodePeriod = (mode == NewCode))` and `CoverageTablePanel` to use the matching metric set (`new_*` metrics for New Code mode).
  - Reuse the existing `DEFAULT_METRIC_KEYS` (already includes `new_*`).
  - **Acceptance**: switching modes on a feature branch updates issue count from ~720 → ~7 (the new-code delta).

- [ ] **R-EVOLVE-2 follow-on** — `GateStatusBanner` should list failed conditions inline. The data is in `SonarQualityGateDto.conditions` already — filter `status == "ERROR"` and render as "Failed: 7 new code smells (threshold 0); …".

- [ ] **R-EVOLVE-3 / R-DROP-1** — Delete dead Community-edition fallback code.
  - Grep `:sonar` for `if (edition == "community")` or similar guards. Anything that silently degrades to single-branch / no-hotspots / project-scoped-period on Community is dead on 25.x. Delete it.
  - Update `getSecurityHotspots` KDoc — drop the "Developer Edition+" note, replace with "Sonar 25.x+ at all editions; pre-25.x Community returned 404."

### Phase 3 — Agent-targeted adoption (R-ADD-AGENT)

> **Service architecture rule (CLAUDE.md)**: every new agent-callable surface must be `core/services/Xxx.kt` interface returning `ToolResult<T>` where `T` is in `core/model/`, with a meaningful `.summary`, then a feature-module impl. Don't expose plugin UI panel methods directly to the agent — they're inaccessible.

- [ ] **R-ADD-AGENT-1** — `/api/hotspots/show` adoption.
  - New DTOs in `SonarDtos.kt`: `SonarHotspotDetailDto(key, component:SonarComponentDto, project:..., rule:SonarHotspotRuleDto, status, line, message, assignee?, author?, creationDate?, updateDate?, textRange?, changelog: List<SonarHotspotChangelogDto>, comment: List<...>, users: List<SonarHotspotUserDto>, canChangeStatus: Boolean = false)`. The `rule` block is `SonarHotspotRuleDto(key, name, vulnerabilityProbability, securityCategory, riskDescription, vulnerabilityDescription, fixRecommendations)` — the three description fields are HTML strings; pass through verbatim.
  - New method `SonarApiClient.getHotspotDetail(hotspotKey: String): ApiResult<SonarHotspotDetailDto>` calling `/api/hotspots/show?hotspot={key}`.
  - Core service interface: extend `SonarService` with `suspend fun getHotspotDetail(key: String): ToolResult<HotspotDetail>`.
  - Agent tool wrapper in `:agent`: register `sonar_hotspot_detail` so the LLM can fetch fix guidance per hotspot.
  - **CRITICAL CAVEAT** to document in `:agent` system prompt: `canChangeStatus: false` for non-admin tokens. Agent cannot directly mark hotspots as fixed/safe — has to edit code, push, wait for re-analysis to re-evaluate. **Don't promise the agent it can close hotspots autonomously.**
  - **Test**: round-trip `hotspots_show.json` raw payload; assert `rule.fixRecommendations` contains `"SecureRandom"` (the literal compliant-solution example).

- [ ] **R-ADD-AGENT-2** — Document agent usage of `sources/lines` (no code change).
  - Add a paragraph to `agent/CLAUDE.md`: how the agent uses `SonarSourceLineDto` fields (`isNew`, `lineHits`, `conditions`, `coveredConditions`) for line-level decisions. The DTO already has all four; only documentation is missing.

- [ ] **R-ADD-AGENT-3** — Facet-based triage.
  - Extend `SonarIssueSearchResult` (`SonarDtos.kt:78`) with `val facets: List<SonarFacetDto> = emptyList()`. Add `SonarFacetDto(property: String = "", values: List<SonarFacetValueDto> = emptyList())` and `SonarFacetValueDto(@SerialName("val") val value: String = "", val count: Int = 0)`. (The Kotlin keyword `val` clashes with Sonar's field name `val`, hence `@SerialName`.)
  - New method `SonarApiClient.getIssueFacets(projectKey, branch, inNewCodePeriod, facets)` calling `/api/issues/search?componentKeys={k}&resolved=false&ps=1&inNewCodePeriod={true|false}&facets={comma-list}&branch={b}`.
  - Valid 25.x facet names (from Sonar's own 400-error enumeration): `severities, types, tags, impactSoftwareQualities, impactSeverities, cleanCodeAttributeCategories, assignees, files, rules, statuses, resolutions, author, directories, scopes, languages, codeVariants, issueStatuses, prioritizedRule, createdAt, sonarsourceSecurity` + security compliance facets (`pciDss-3.2/4.0`, `owaspAsvs-4.0`, `owaspMobileTop10-2024`, `stig-ASD_V5R3`, `casa`, `sansTop25`, `cwe`). **Note `files`, NOT `fileUuids`** (that was a v2 probe bug fixed in v0.3).
  - Agent tool wrapper: `sonar_issue_facets` so the agent can prioritize before walking the issue list.
  - **Test**: round-trip `issues_search_facets_new_code.json` raw payload; assert `facets` is non-empty + at least one facet has `property == "impactSoftwareQualities"`.

- [ ] **R-ADD-AGENT-4** — AI Code Fix is unavailable. **No code change.** Document in `:agent` system prompt: "SonarQube AI Code Fix is not available on Community Build. Use the agent's own LLM path for autonomous fixes."

### Phase 4 — Settings page enhancements (R-ADD-FEATURE)

- [ ] **R-ADD-FEATURE-1** — `/api/users/current`.
  - New DTO `SonarCurrentUserDto(login, name, email?, groups: List<String>, permissions: SonarPermissionsDto?, externalProvider?, scmAccounts: List<String> = emptyList())` and `SonarPermissionsDto(global: List<String> = emptyList())`.
  - New method `SonarApiClient.getCurrentUser(): ApiResult<SonarCurrentUserDto>`.
  - Settings page: show "Connected as ${name} (${login})" + a perms badge ("Admin: yes/no" based on `permissions.global` containing relevant scopes).
  - Used to gate the R-FIX-2 "open Sonar permissions" hint conditionally — admins don't need to be told they need admin perms.

- [ ] **R-ADD-FEATURE-2** — `/api/qualitygates/list`.
  - New DTO `SonarQualityGateListResponse(qualitygates: List<SonarQualityGateListEntryDto>)` with each entry carrying `name, isDefault, isBuiltIn, caycStatus, hasStandardConditions, hasMQRConditions, isAiCodeSupported`.
  - Settings page or Quality tab header: "Project's gate: ${gate.name} (${gate.caycStatus})".

### Phase 5 — out of scope for this commit (intentional)

Do NOT include in this commit (defer to follow-ups):
- `/api/project_analyses/search` adoption (R-DEFER)
- `/api/measures/search_history` sparkline (R-DEFER)
- `/api/issues/tags` filter chip (R-DEFER)
- `/api/languages/list` scanner chip (R-DEFER)
- `/api/sources/scm` separate adoption — `sources/lines` is the superset, no need (covered in §6.2 of recs doc)

---

## Architectural constraints

- **Service architecture (project CLAUDE.md)**: agent-callable surfaces must be `core/services/Xxx.kt` → `ToolResult<T>` → feature impl → agent tool wrapper. Methods on UI panels are inaccessible to the agent. R-ADD-AGENT-1 + R-ADD-AGENT-3 each need this full stack.
- **Threading (project CLAUDE.md)**: `Dispatchers.IO` for API calls, `invokeLater`/`Dispatchers.EDT` for UI, `withTimeoutOrNull` per agent tool.
- **No `runBlocking` in Swing.**
- **Auth (project CLAUDE.md)**: Sonar uses `Authorization: Bearer <token>` via `HttpClientFactory(ServiceType.SONARQUBE)`. Don't introduce a new HTTP client.
- **DTO defaults**: every new DTO field gets a default so older Sonar servers (pre-25.x) still parse cleanly. `kotlinx.serialization` with `ignoreUnknownKeys = true` (already configured at `SonarApiClient.kt:51`) handles unknown fields silently — but missing required fields will fail. Defaults everywhere.
- **No Co-Authored-By in commit message** (user feedback `feedback_no_coauthor.md`).
- **Single integration commit** on `fix/automation-handover-quality-tabs` per the per-service commit policy that landed Jira / Bitbucket / Bamboo.
- **Update `sonar/CLAUDE.md` in the same commit** if new endpoints are added (per `feedback_update_docs_immediately.md`).

---

## Verification protocol

Before claiming done:

1. **Compile + tests**: `./gradlew :sonar:test :core:test` (the `:agent` tests too if you wire R-ADD-AGENT tool wrappers).
2. **Plugin verifier**: `./gradlew verifyPlugin` (catches API misuse the plugin loader will reject).
3. **Round-trip the validated payloads**: each new DTO should have a unit test that loads the matching `tools/atlassian-probe/Result_Sonar/bundle-sweep-compresses-v3.unpacked/raw/<file>.json` payload and asserts shape + the load-bearing fields. This is real-server data — if your DTO can't parse it, the plugin can't either.
4. **Manual smoke test on the user's server (optional but recommended)**: `./gradlew runIde`, point Settings → Tools → Workflow Orchestrator → Sonar at the user's URL + token, open the Quality tab on a feature branch, switch the Overall / New Code toggle, click into an issue (rule description should be non-empty), click into a hotspot (the new detail panel should render `fixRecommendations`).

---

## Commit shape

Single commit, message style mirrors the bamboo / bitbucket integration commits on the audit branch:

```
feat(sonar): adopt validated 25.x endpoints + 3 R-FIX bug fixes

R-FIX-1: SonarRuleDto.descriptionSections + fallback chain in
SonarServiceImpl.kt:722. Fixes empty rule descriptions on Sonar 25.x.

R-FIX-2: 403 detection on getAnalysisTasks → "Administer Project
permission required" empty state on the Quality tab.

R-FIX-3: getNewCodePeriod() reads from cached gate's period block instead
of calling the admin-gated /api/new_code_periods/show. Removes a
guaranteed 403 for non-admin users.

R-EVOLVE-1: caycStatus on SonarQualityGateDto.

R-EVOLVE-2: Overall Code / New Code tab toggle on QualityDashboardPanel.
Defaults to New Code on non-main branches.

R-DROP: removed pre-25.x Community-edition guards (multi-branch is at
the free tier as of Sonar 25.x rebrand to Community Build).

R-ADD-AGENT-1: /api/hotspots/show adoption — new SonarHotspotDetailDto +
getHotspotDetail() + sonar_hotspot_detail agent tool. canChangeStatus:
false documented as agent constraint.

R-ADD-AGENT-3: /api/issues/search?facets= adoption — facets field on
SonarIssueSearchResult + getIssueFacets() + sonar_issue_facets agent tool.

R-ADD-FEATURE-1+2: /api/users/current + /api/qualitygates/list for
settings page identity + gate name display.

Plugin makes ZERO writes against Sonar — confirmed.

Verified against the user's server (Community Build 25.9.0.112764) via
three sweeps captured in tools/atlassian-probe/Result_Sonar/. Recs doc:
docs/research/2026-05-07-sonar-audit-recommendations.md.
```

After landing, **delete the memory file** `~/.claude/projects/.../memory/project_sonar_audit_in_progress.md` and the `MEMORY.md` index entry — the audit is closed.

---

## Quick-reference: the three bug-class items

If time-pressured, these three R-FIX items are the only must-haves; everything else is enhancement:

| ID | What | Where | Effort |
|---|---|---|---|
| R-FIX-1 | Rule descriptions render empty | `SonarDtos.kt:319`, `SonarServiceImpl.kt:722` | 30 min |
| R-FIX-2 | "Last analysis" silently blank for non-admin | wherever `getAnalysisTasks` is consumed | 30 min |
| R-FIX-3 | `getNewCodePeriod()` 403 with free workaround | `SonarApiClient.kt:213` + caller | 15 min |

A R-FIX-only commit ships the bug fixes in <1.5 hours. R-EVOLVE + R-ADD-AGENT take the full day.

Good luck. The data is on disk; trust the recs doc; don't re-run the probe.
