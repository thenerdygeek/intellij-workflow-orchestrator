# Quality tab arc — handoff for v0.83.69-alpha (2026-05-06)

Branch: `fix/automation-handover-quality-tabs`
Last commit on this branch: `32305659` (v0.83.68-alpha shipped)
`gradle.properties` already bumped to **0.83.69-alpha** for the next release.

This handoff is self-contained — you should not need to read prior session
context. Files referenced are paths into this repo.

---

## Background — what this arc fixed

The Quality tab in NEW CODE mode wasn't showing the files that contributed
to a failing new-code coverage gate. Three layers were broken; v0.83.66
through v0.83.68 fixed them in order:

| Version | Layer | What it fixed |
|---|---|---|
| v0.83.66 | Request | Removed the `sonarMetricKeys` settings override (it was a strict subset of `SonarApiClient.DEFAULT_METRIC_KEYS` and silently overrode it). Removed the `ifEmpty { fileCoverage }` silent fallback that masked empty new-code data. Cross-tab navigation from gate banner now flips `newCodeMode` for coverage conditions, not just issue conditions. |
| v0.83.67 | Response parser | `CoverageMapper.mapMeasures` now reads `it.effectiveValue()` instead of `it.value`. Modern SonarQube returns `new_*` metric values inside `period.value`, not the top-level `value`, when `additionalFields=period` is in the request — and `SonarApiClient.getMeasures` always sends that for any `new_*` request. The DTO already had `effectiveValue()` for this purpose; `SonarServiceImpl` used it correctly in three sites; `CoverageMapper` was the one consumer that didn't. |
| v0.83.68 | Pagination + sort | Probe data captured against a real 531-file project surfaced two more issues. (a) `getMeasures` was hitting `ps=500` with no sort, so Sonar's default alphabetical sort silently dropped the 31-file tail; if the new-code files happened to sort late they were invisible. Now paginates up to `MAX_MEASURES_PAGES=10 × MEASURES_PAGE_SIZE=500 = 5000` components, and adds `s=metric&metricSort=new_lines_to_cover&asc=false` when `new_lines_to_cover` is in the metric set. (b) `/api/new_code_periods/show` requires admin and returns 403 for most tokens, so the "New code: compared to release" line was permanently missing. Now derives `NewCodePeriod` from the embedded `period: { mode, parameter }` field on the gate response, which is fetched with project Browse permission. |

Detailed root-cause and empirical-confirmation docs at
- `docs/research/2026-05-06-quality-tab-newcode-debug-handoff.md`
  (v0.83.66/67 — DTO `effectiveValue()` story)
- `docs/research/2026-05-06-quality-tab-pagination-and-agent-followups.md`
  (v0.83.68 — pagination/sort/gate-period story plus full agent-tool audit)

---

## What's left on the plan

### Verification (user-side, not work)

Install **v0.83.68-alpha** and open the Quality tab on the branch where the
gate is failing. Switch to NEW CODE, hit Refresh.

- Coverage table should list files with `new_lines_to_cover > 0`, sorted by
  new-code volume.
- Branch info bar should show "New code: compared to release".

If still empty after v0.83.68, run the Sonar probe with `--raw` and search
for files with non-zero `new_lines_to_cover`:

```bash
python3 scripts/api-probe.py \
    --config scripts/api-probe-config.json \
    --services sonar --endpoints measures_tree --raw

python3 -c "
import json
d = json.load(open('scripts/api-probe-results/sonar.raw.json'))
m = [e for e in d if e['endpoint']=='measures_tree'][0]
nz = [c['path'] for c in m['body']['components']
      if any(x.get('metric')=='new_lines_to_cover'
             and x.get('period',{}).get('value','0')!='0'
             for x in c['measures'])]
print(f'{len(nz)} of {len(m[\"body\"][\"components\"])} files have new code')
for p in nz[:20]: print(f' - {p}')"
```

If the count is non-zero but the Coverage tab is still empty in v0.83.68,
the bug is downstream of pagination/sort — open a new investigation.

### TASK 1 — SonarQube 9.6+ Clean Code taxonomy (highest priority)

**Why it's worth doing**: The user's probe (`scripts/api-probe-results/sonar.raw.json`)
shows every issue carrying these fields, and the plugin ignores them all:

```jsonc
"cleanCodeAttribute": "CONVENTIONAL",        // CLEAR | CONVENTIONAL | FORMATTED | EFFICIENT | LAWFUL | RESPECTFUL | TRUSTWORTHY | DISTINCT | LOGICAL | COMPLETE | IDENTIFIABLE
"cleanCodeAttributeCategory": "CONSISTENT",  // CONSISTENT | INTENTIONAL | ADAPTABLE | RESPONSIBLE
"impacts": [
  {"softwareQuality": "RELIABILITY", "severity": "MEDIUM"},
  {"softwareQuality": "MAINTAINABILITY", "severity": "LOW"}
],
"issueStatus": "OPEN"                        // separate from legacy `status`
```

For an agent fixing code, knowing an issue's impact is `RELIABILITY/HIGH`
vs `MAINTAINABILITY/LOW` is materially more actionable than the legacy
`severity=MAJOR, type=BUG` (both can be MAJOR with very different urgency).

**Implementation**

1. **DTO** — `sonar/src/main/kotlin/com/workflow/orchestrator/sonar/api/dto/SonarDtos.kt`
   - Extend `SonarIssueDto` with new optional fields:
     ```kotlin
     val cleanCodeAttribute: String? = null,
     val cleanCodeAttributeCategory: String? = null,
     val impacts: List<SonarImpactDto> = emptyList(),
     val issueStatus: String? = null,
     ```
   - Add new DTO:
     ```kotlin
     @Serializable
     data class SonarImpactDto(
         val softwareQuality: String = "",   // RELIABILITY | SECURITY | MAINTAINABILITY
         val severity: String = ""           // INFO | LOW | MEDIUM | HIGH | BLOCKER
     )
     ```
   - All defaults set so older Sonar (pre-9.6) without these fields still
     parses cleanly. Json client already uses `ignoreUnknownKeys = true`.

2. **Model** — `sonar/src/main/kotlin/com/workflow/orchestrator/sonar/model/SonarModels.kt`
   - Find `data class MappedIssue(...)`. Add:
     ```kotlin
     val cleanCodeAttribute: String? = null,
     val cleanCodeAttributeCategory: String? = null,
     val impacts: List<Impact> = emptyList(),
     ```
   - Add to the same file:
     ```kotlin
     data class Impact(
         val softwareQuality: SoftwareQuality,
         val severity: ImpactSeverity
     )
     enum class SoftwareQuality { RELIABILITY, SECURITY, MAINTAINABILITY, UNKNOWN }
     enum class ImpactSeverity { INFO, LOW, MEDIUM, HIGH, BLOCKER, UNKNOWN }
     ```
     `UNKNOWN` keeps deserialization robust against future Sonar values.

3. **Mapper** — `sonar/src/main/kotlin/com/workflow/orchestrator/sonar/service/IssueMapper.kt`
   - In `mapIssues` (or wherever individual `SonarIssueDto` becomes `MappedIssue`),
     copy the four new fields. Map `impacts` element-wise:
     ```kotlin
     impacts = dto.impacts.map { i ->
         Impact(
             softwareQuality = runCatching { SoftwareQuality.valueOf(i.softwareQuality) }
                 .getOrDefault(SoftwareQuality.UNKNOWN),
             severity = runCatching { ImpactSeverity.valueOf(i.severity) }
                 .getOrDefault(ImpactSeverity.UNKNOWN)
         )
     }
     ```

4. **Agent JSON** — `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/integration/SonarTool.kt`
   - The `issues` and `issues_paged` actions return JSON via the
     `toAgentToolResult()` path. Find the response builder for issues
     (probably in `SonarServiceImpl` or a serializer near it) and add
     the new fields to the JSON output:
     ```jsonc
     {
       "key": "...", "severity": "MAJOR", "type": "BUG",
       "cleanCodeAttribute": "CONVENTIONAL",
       "cleanCodeAttributeCategory": "CONSISTENT",
       "impacts": [{"softwareQuality": "RELIABILITY", "severity": "MEDIUM"}],
       …
     }
     ```
   - **Update the action description in `SonarTool.kt`** so the agent
     knows to use `impacts` for prioritization. Current description
     (around line 47):
     ```
     - issues(project_key, file?, branch?, new_code_only?) → Code issues …
     ```
     Add a sentence like: "Each issue includes `impacts[]` (per-software-quality
     severity in `RELIABILITY`/`SECURITY`/`MAINTAINABILITY`) and `cleanCodeAttribute`
     for prioritization beyond legacy `severity`/`type`."

5. **UI — issue list cell renderer** — `sonar/src/main/kotlin/com/workflow/orchestrator/sonar/ui/IssueListPanel.kt`
   - The renderer at the bottom of the file builds an HTML label.
     Add a small impacts badge before the file:line, e.g.
     `<font color='${StatusColors.htmlColor(impactColor)}'>[REL/HIGH]</font>`
     where `impactColor` derives from the highest-severity impact:
     - `BLOCKER` / `HIGH` → `StatusColors.ERROR`
     - `MEDIUM` → `StatusColors.WARNING`
     - `LOW` / `INFO` → `StatusColors.INFO`
   - Format: 3-letter quality abbreviation (REL/SEC/MNT) + slash + severity.
   - Skip the badge entirely when `impacts` is empty (older Sonar).

6. **UI — issue detail panel** — `sonar/src/main/kotlin/com/workflow/orchestrator/sonar/ui/IssueDetailPanel.kt`
   - Add a "Clean Code" section between rule info and the legacy severity
     badge. Show:
     - `cleanCodeAttribute` and `cleanCodeAttributeCategory` as a
       breadcrumb: `CONSISTENT → CONVENTIONAL`
     - Each impact as a colored chip: `RELIABILITY · MEDIUM`
   - Hide the section entirely when no taxonomy data (graceful degradation
     for Sonar < 9.6).

7. **Tests**
   - `sonar/src/test/kotlin/com/workflow/orchestrator/sonar/api/dto/SonarDtoSerializationTest.kt`
     — extend the existing `deserialize issues search` to assert
     `cleanCodeAttribute`, `impacts.size`, `impacts[0].softwareQuality`.
     Update `sonar/src/test/resources/fixtures/issues-search.json` with
     the new fields (capture from
     `scripts/api-probe-results/sonar.raw.json`'s `issues` endpoint).
   - `sonar/src/test/kotlin/com/workflow/orchestrator/sonar/service/IssueMapperTest.kt`
     — add a test that maps a `SonarIssueDto` with `impacts` to a
     `MappedIssue` and verifies the enum mapping (including the
     `UNKNOWN` fallback for an unrecognized severity).
   - `sonar/src/test/kotlin/com/workflow/orchestrator/sonar/ui/QualityListItemTest.kt`
     (if it exists) or add one — render a list item with impacts and
     assert the badge text format.

**Acceptance**
- IssueListPanel rows on a Sonar 9.6+ project show the impacts badge.
- IssueDetailPanel shows the Clean Code section with the breadcrumb and
  impact chips.
- Agent's `issues` action JSON output includes the new fields.
- Sonar < 9.6 still renders cleanly (no badge, no section).

### TASK 2 — Two small description tweaks

Both in `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/integration/SonarTool.kt`:

1. **`issues` action silently caps at `ps=500`** — the description (~line 47)
   says "Code issues" with no cap mention. Two options:
   - **A (preferred)**: default the action to paginate via the same code
     path as `issues_paged`. Cleanest contract, no agent education needed.
   - **B (smaller)**: just update the description to say "returns up to 500
     issues; use `issues_paged` for full coverage on large projects."

2. **`analysis_tasks` 403s on non-admin tokens** — description doesn't say so.
   An agent retrying on 403 wastes turns. Update the action description
   (`analysis_tasks` ~line 56-ish in the description block) to say
   "requires admin permission; returns 403 for non-admin tokens."

### TASK 3 — Out of scope, but worth tracking

These came up in the audit but are separate workstreams:

- **Bitbucket DTO/tools audit.** Pre-existing reference at
  `reference_bitbucket_tools_audit_result.md`: 6 F-HIGH + 3 F-MED + 15 UNTESTED.
  Use the `--raw` probe (`--services bitbucket`) to capture real responses
  and audit each endpoint against its DTO.
- **Bamboo / Jira / Nexus correctness sweeps** using the same probe.

---

## Tooling pointers

- **Probe script**: `scripts/api-probe.py`. Header docstring covers
  every detail. Five services (bamboo, jira, sonar, bitbucket, docker),
  `--raw` mode for actual values (with PII redaction), default mode is
  fully-censored output safe to share.
- **Probe config**: `scripts/api-probe-config.json` (gitignored). Run
  `python3 scripts/api-probe.py --init scripts/api-probe-config.json`
  to generate a template.
- **Probe results**: `scripts/api-probe-results/` (gitignored).
- **Plugin DTO that handles Sonar version drift**: `SonarMeasureDto.effectiveValue()`
  in `sonar/.../api/dto/SonarDtos.kt:124`. Anywhere you read measure values,
  call this — never read `it.value` directly.

## Release process for v0.83.69 (when you're ready)

```bash
# Version is already 0.83.69-alpha in gradle.properties.
./gradlew :sonar:test :agent:test
./gradlew clean buildPlugin
git add -p   # stage only the v0.83.69 changes
git commit -m "fix(sonar): <what changed>"
git push origin fix/automation-handover-quality-tabs
gh release create v0.83.69-alpha \
    --prerelease \
    --target fix/automation-handover-quality-tabs \
    --title "v0.83.69-alpha — <one-line summary>" \
    --notes "..." \
    build/distributions/intellij-workflow-orchestrator-0.83.69-alpha.zip
```

## Discipline reminders (from prior sessions)

- Never read `it.value` for a `SonarMeasureDto`. Always `effectiveValue()`.
- Never hand-roll `ifEmpty` fallbacks that swap in different-shape data
  silently. The Coverage tab arc lost ~3 sessions of debugging time to
  exactly this pattern.
- When updating fixtures, capture from `scripts/api-probe-results/sonar.raw.json`
  (after PII redaction) so test data matches real responses, not
  fictional shapes.
- Tests that exercise wrong-shape data are worse than no test (they
  pass against fictional input while production stays broken). Treat
  fixtures as production contracts.
- For UI changes, run `./gradlew runIde` and sanity-check the visible
  affordance — the test suite verifies code correctness, not feature
  correctness.
