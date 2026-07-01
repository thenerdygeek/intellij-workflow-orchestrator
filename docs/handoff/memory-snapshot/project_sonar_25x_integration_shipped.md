---
name: SHIPPED 2026-05-07 — Sonar 25.x integration commit
description: Sonar audit recommendations landed in single commit ac0e070e on fix/automation-handover-quality-tabs; R-FIX 1-3 + R-EVOLVE 1-2 + R-DROP + R-ADD-AGENT 1+3 + R-ADD-FEATURE 1+2 implemented (settings UI for identity/gate-name deferred)
type: project
originSessionId: 128ccdb3-e0e2-4b63-a4eb-2718a06c5630
---
**SERVER PINNED**: SonarQube **Community Build 25.9.0.112764**, edition `community`, non-admin token, reference branch `release`.

**Commit**: `ac0e070e feat(sonar): adopt validated 25.x endpoints + 3 R-FIX bug fixes` on `fix/automation-handover-quality-tabs` (lands recs from `docs/research/2026-05-07-sonar-audit-recommendations.md`).

**Implemented**:
- R-FIX-1: SonarRuleDto.descriptionSections + cleanCode/impacts; SonarServiceImpl.kt fallback chain mdDesc → htmlDesc → descriptionSections.joinToString
- R-FIX-2: SonarState.analysisHistoryForbidden + QualityDashboardPanel "Open Sonar permissions" hint when /api/ce/activity 403s; auto-hides for admins (no 403, no hint)
- R-FIX-3: dropped getNewCodePeriod() + SonarNewCodePeriodDto; data sourced from SonarQualityGateDto.period (always available with Browse permission)
- R-EVOLVE-1: caycStatus on SonarQualityGateDto + QualityGateState; GateStatusBanner appends "Gate is non-Clean-as-You-Code" suffix when non-compliant
- R-EVOLVE-2: PluginSettings.sonarPreferredCodeMode (Int 0=unset, 1=Overall, 2=NewCode); persists toggle across IDE restarts; default heuristic isMain → Overall else NewCode (NewCode is the Sonar Web UI default on PR branches)
- R-DROP: removed "Developer Edition+" Hotspots KDoc references — multi-branch + hotspots are at Community Build tier on Sonar 25.x
- R-ADD-AGENT-1: /api/hotspots/show — SonarHotspotDetailDto + getHotspotDetail() + core HotspotDetailData + SonarService.getHotspotDetail() + sonar agent action `hotspot_detail`. canChangeStatus:false documented in SonarTool description as agent constraint
- R-ADD-AGENT-3: /api/issues/search?facets= — facets field on SonarIssueSearchResult + SonarFacetDto + SonarFacetValueDto (with @SerialName("val") for keyword clash) + getIssueFacets() + sonar agent action `issue_facets`
- R-ADD-AGENT-2 + 4: SonarTool description documents source_lines fields (isNew/lineHits/conditions/coveredConditions) + AI Code Fix unavailability on Community Build
- R-ADD-FEATURE-1: /api/users/current — DTO + getCurrentUser() + core SonarCurrentUserData (with isAdmin) + sonar agent action `current_user`
- R-ADD-FEATURE-2: /api/qualitygates/list — SonarQualityGateListResponse + listQualityGates() + core SonarQualityGateListData + sonar agent action `quality_gates_list`

**Deferred** (per bitbucket commit precedent of scoping Phase 4 to specific items):
- Settings page UI for the user-identity badge and project-gate name display. Data is fully wired through the agent surface; agent can call `current_user` and `quality_gates_list` for the same info.

**Tests added**: 6 round-trip tests in `SonarDtoSerializationTest.kt` against the v3 raw probe payloads (rules-show-25x, qualitygate-status-25x, hotspots-show-25x, issues-search-facets-25x, users-current, qualitygates-list). All green. Plugin verifier green.

**Architectural note**: PluginSettings.sonarPreferredCodeMode landed as part of commit `bc50576c` (parallel Nexus L3 session pulled in my uncommitted edit during their refactor). Field is correctly defined and used by my SonarDataService implementation in commit `ac0e070e`.

**Build-cache trap (informational)**: kotlinx.serialization with ignoreUnknownKeys=true silently drops unknown fields, but missing required fields throw at decode time. Defaulting `SonarTextRangeDto.startLine`/`endLine` to 0 was needed because the probe redacted some field names — production Sonar always sends them.
