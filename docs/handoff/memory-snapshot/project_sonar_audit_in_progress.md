---
name: ACTIVE 2026-05-07 — SonarQube audit (recs doc shipped, integration commit pending)
description: probe v0.3 + recs doc 8fae7b90 shipped on fix/automation-handover-quality-tabs; next step is the single Sonar integration commit (R-FIX 1-3 + R-EVOLVE + R-ADD-AGENT)
type: project
originSessionId: 6632c6fc-f3d9-46f4-b2b0-3078aef9ac0b
---
**SERVER PINNED**: SonarQube **Community Build 25.9.0.112764**, edition `community`, status UP, versionEOL `2026-04-01` (5w past EOL as of 2026-05-07). Token valid, `permissions.global:[]` (non-admin). Reference branch is `release` (REFERENCE_BRANCH new-code mode).

**Probe + recs commits on `fix/automation-handover-quality-tabs`**:
- `89411137` v0 — initial probe + README
- `acf72425` v0.1 — param fixes + 4 agent-targeted endpoints + corrected 25.x predictions + file-key auto-discovery
- `c35f3848` v0.2 — discover prefers failing-gate non-main branch (was suggesting main)
- `fb12aa36` v0.3 — branch-aware file walk + correct `files` facet (was `fileUuids`)
- `8fae7b90` recs doc — `docs/research/2026-05-07-sonar-audit-recommendations.md` (333 lines)

**Recs doc structure**: TL;DR · server snapshot · findings matrix (every plugin endpoint) · §4 real failures · §5 plugin DTO gaps · §6 R-ADD-AGENT (4 items) · §7 UX gaps · §8 R-DROP (dead pre-25.x code) · §9 other feature candidates · §10 implementation sequencing · §11 companion files · §12 quick-ref bug-class summary.

**Three bug-class findings (R-FIX-1/2/3)**:
1. **R-FIX-1**: `SonarRuleDto` has only `htmlDesc + mdDesc`, both null on Sonar 25.x. Sonar replaced them with `descriptionSections:[{key,content}]`. `SonarServiceImpl.kt:722` chain `mdDesc ?: htmlDesc ?: ""` → empty rule description in `IssueDetailPanel`. Real bug. Fix: add `descriptionSections: List<SonarRuleDescriptionSectionDto>` to DTO + extend the fallback chain.
2. **R-FIX-2**: `/api/ce/activity` admin-gated on 25.x → 403 for non-admin → "Last analysis" indicator silently blank. Fix: detect FORBIDDEN + surface "permission required" hint with link to Sonar permissions page.
3. **R-FIX-3**: `/api/new_code_periods/show` also admin-gated. **Same data is already populated on `SonarQualityGateDto.period`** (mode + parameter) which the plugin already fetches every Quality tab refresh. Fix: drop or suppress `getNewCodePeriod()`, read from cached gate. Removes a guaranteed 403.

**Earlier WRONG claims I corrected during the audit**:
- "Plugin DTOs missing CCT fields" → FALSE. `SonarIssueDto` already has `cleanCodeAttribute`, `cleanCodeAttributeCategory`, `impacts: List<SonarImpactDto>`, `issueStatus`. Verified via grep.
- "Plugin missing per-line coverage primitives" → FALSE. `SonarSourceLineDto` already has `lineHits`, `conditions`, `coveredConditions`, `isNew`. Agent has everything needed for line-level fix targeting.
- "Community ignores branch=, hotspots/search 404s, branches/list returns only main" → FALSE on Sonar 25.x. Pre-25.x Community Edition behaved this way; Sonar's 25.x rebrand to "Community Build" moved multi-branch to the free tier. **R-DROP** dead code that assumes the old gates.

**R-ADD-AGENT (autonomous-fix workflow)**:
- R-ADD-AGENT-1: `/api/hotspots/show` — full risk + fix recommendations per hotspot. The `rule.fixRecommendations` HTML contains a literal "Compliant Solution" code example (e.g. `SecureRandom random = new SecureRandom();` for `java:S2245`) that the agent feeds the LLM. New DTO + client method + agent tool wrapper needed. **CAVEAT**: `canChangeStatus: false` for non-admin — agent fixes are detected via re-analysis, not direct status mutation.
- R-ADD-AGENT-2: `/api/sources/lines.isNew + .lineHits + .conditions + .coveredConditions` — already wired in plugin. Document for `:agent`. No code change.
- R-ADD-AGENT-3: `/api/issues/search?facets=...&inNewCodePeriod=true` — facet counts for triage. Sonar 25.x valid facets: `severities, types, tags, impactSoftwareQualities, impactSeverities, cleanCodeAttributeCategories, assignees, files, rules, ...` (NOT `fileUuids`). Extend `SonarIssueSearchResult` with `facets` field, add agent tool wrapper.
- R-ADD-AGENT-4: AI Code Fix (`/api/v2/ai-codefix/feature` + `/availability`) → 404 on Community Build. `isAiCodeSupported: false` on every gate. Document in `:agent` system prompt; agent must use own LLM. No integration.

**R-EVOLVE (UX upgrades for 25.x)**:
- R-EVOLVE-1: Add `caycStatus` to `SonarQualityGateDto` (compliant / non-compliant / over-compliant). Surface as a positive badge or non-compliance hint.
- R-EVOLVE-2: **Overall Code vs New Code tab parity with Sonar Web UI** — `QualityDashboardPanel` should toggle between full snapshot and new-code delta. Default to New Code on non-main branches, Overall on main. Plugin already has the primitives (`getIssues(inNewCodePeriod=true)`, `new_*` metric keys) — purely UI wiring.
- R-EVOLVE-3 (covered in R-DROP-1): delete pre-25.x Community-edition guards.

**R-ADD-FEATURE (lower priority but viable)**:
- `/api/users/current` — settings page identity + permissions detection (gate the R-FIX-2 admin hint conditionally).
- `/api/qualitygates/list` — settings page "this project's gate is X".

**R-DEFER**: `/api/project_analyses/search`, `/api/measures/search_history`, `/api/issues/tags`, `/api/languages/list`. All work but cosmetic.

**Plugin makes zero writes against Sonar** — confirmed by grep across `:sonar` and `:core`.

**Estimated effort for the integration commit**: 1 day for R-FIX 1-3 + R-EVOLVE 1-3 + R-DROP, 1 day for R-ADD-AGENT 1+3 (DTOs + client + agent tool wrappers) + R-ADD-FEATURE 1+2. One PR.

**versionEOL note**: server is 5w past EOL. Recommend upgrade at next maintenance window — not blocking.

**Branch:** `fix/automation-handover-quality-tabs`. Other untracked work-in-progress on this branch from user's separate workstreams: `docs/research/2026-05-07-automation-handover-audit.md` and large automation-module refactor (DockerRegistryClient deletion + several settings/test changes).

Remove this memory file when the Sonar integration commit ships.
