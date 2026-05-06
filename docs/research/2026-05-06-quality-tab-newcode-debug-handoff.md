# Quality tab NEW CODE mode — debug handoff (2026-05-06)

Branch: `fix/automation-handover-quality-tabs`
Status: **shipped in v0.83.67-alpha** — empirically confirmed against the
public `next.sonarqube.com` API; one-line `effectiveValue()` fix in
`CoverageMapper`; period-shape regression test pinning the contract.

## Symptom (post-v0.83.66-alpha)

After the v0.83.66-alpha release (which removed the `sonarMetricKeys` setting
and the silent `ifEmpty { fileCoverage }` fallback), the Coverage tab in NEW
CODE mode now shows **"No files in the new-code period."** while the gate
banner still correctly reports `Branch Coverage: 50% (threshold: 80%)` on new
code. Both readings can't be true — if the gate fails on new-code branch
coverage, the project necessarily contains new-code files contributing to
that 50%.

## Root cause (hypothesis, strong)

`SonarMeasureDto` already encodes the SonarQube quirk: new-code metrics live
under `period.value`, not the top-level `value`. The DTO exposes
`effectiveValue()` exactly for this:

```kotlin
// sonar/.../api/dto/SonarDtos.kt
data class SonarMeasureDto(
    val metric: String,
    val value: String = "",
    val period: SonarMeasurePeriodDto? = null
) {
    fun effectiveValue(): String =
        if (metric.startsWith("new_") && period != null) period.value else value
}
```

Three call sites use it correctly: `SonarServiceImpl.kt:189`, `:875`, `:905`.

`CoverageMapper.mapMeasures` does **not** — it reads `it.value` directly:

```kotlin
// sonar/.../service/CoverageMapper.kt:18
val measures = comp.measures.associate { it.metric to it.value }
```

Combined with `SonarApiClient.getMeasures` always sending
`additionalFields=period` whenever `metricKeys` contains `new_`, the actual
SonarQube response moves new-code values into `period.value` and leaves
top-level `value` empty. The mapper reads `value` → empty → `toIntOrNull()` →
`null`. `newLinesToCover` is null for every file. The new-code filter
(`SonarDataService.refreshWith:349-350`) drops everything. Empty state shown.

## Why existing tests didn't catch this

Both the existing `maps new_coverage and new_branch_coverage when present`
test and the v0.83.66 `maps new_lines_to_cover and new_uncovered_lines and
complexity fields` test construct `SonarMeasureDto(metric, value, period=null)`
— putting the value in `value` instead of `period.value`. The fixture
`measures-newcode-component-tree.json` has the same shape mismatch. The test
data does not match what real SonarQube returns when `additionalFields=period`
is in the request.

The v0.83.66 regression test (`getMeasures default URL includes every metric
the Quality tab renders`) verified the **request**, not the **response
parser**. It pinned that the keys go out, not that the response gets read
correctly.

## Why the silent fallback hid this for so long

`QualityDashboardPanel` previously did:

```kotlin
val coverageData = currentState.activeFileCoverage.ifEmpty { currentState.fileCoverage }
coverageTablePanel.update(
    coverageData,
    currentState.newCodeMode && currentState.activeFileCoverage.isNotEmpty(),
    currentState.totalCoverageFileCount
)
```

When `newCodeFileCoverage` was empty, the panel silently fell back to overall
files in overall columns. Users saw "wrong files in NEW CODE mode" — a
wrong-data symptom that made the diagnosis look like a request-shape problem
instead of a parse problem. v0.83.66 removed the fallback, surfacing the real
empty-state.

## Empirical confirmation (2026-05-06)

Hit the public `next.sonarqube.com` API with the same shape the plugin sends
(file qualifier, additionalFields=period, mixed new_* + overall metric keys):

```
GET /api/measures/component_tree
  ?component=SonarSource_echoes-react
  &qualifiers=FIL
  &additionalFields=period
  &metricKeys=new_coverage,new_uncovered_lines,new_lines_to_cover,coverage,line_coverage,complexity
  &ps=3
```

Response (verbatim, trimmed):

```json
// new_* metrics — value lives in period.value
{"metric":"new_lines_to_cover","period":{"index":1,"value":"0"}}
{"metric":"new_uncovered_lines","period":{"index":1,"value":"0","bestValue":true}}

// overall metrics — value at top level
{"metric":"complexity","value":"5"}
{"metric":"coverage","value":"100.0","bestValue":true}
```

This is shape (B). The fix landed on `effectiveValue()` which already handles
both shapes (returns `period.value` when present, falls back to `value`).
Forward-compatible — works for both modern (single `period`) and pre-Sonar 8
deployments don't apply because the plugin's DTO doesn't model the legacy
`periods` array anyway. If a deployment still on Sonar ≤7.x ever surfaces,
add `periods: List<...>` to `SonarMeasurePeriodDto`'s parent and extend
`effectiveValue()` to also try `periods?.firstOrNull()?.value`.

### Sonar API additionalFields — canonical values

Per the `MeasuresService.java` SDK source, valid `additionalFields` values
on `/api/measures/component_tree` are exactly `metrics, period` (singular).
Sending `additionalFields=periods` (plural) returns `400 Value of parameter
'additionalFields' (periods) must be one of: [metrics, period]`. The plugin
already sends the correct singular form.

## Probe steps (still useful for verifying the user's specific Sonar)

The plugin already has a comprehensive API probe at
`scripts/api-probe.py` (commit `f0d6d06a`, 2026-04-19). v0.83.66 enhanced it:

- `--raw` flag bypasses censoring so actual response values are visible.
- Sonar `measures_tree` now sends `additionalFields=period` and the full
  `DEFAULT_METRIC_KEYS` set (matches the plugin's actual request).
- `project_measures` also sends `additionalFields=period`.

To confirm:

```bash
# 1. Generate config template (one-time)
python3 scripts/api-probe.py --init scripts/api-probe-config.json

# 2. Edit scripts/api-probe-config.json with your Sonar URL, token,
#    project_key, and the branch where the gate failed.

# 3. Run the Sonar probe in raw mode
python3 scripts/api-probe.py \
    --config scripts/api-probe-config.json \
    --services sonar \
    --endpoints measures_tree \
    --raw

# 4. Inspect scripts/api-probe-results/sonar.raw.json. For any component with
#    a `new_*` metric, look at where the value lives:
#      Shape A: { "metric": "new_lines_to_cover", "value": "42",
#                 "period": null }   → DTO.value path works
#      Shape B: { "metric": "new_lines_to_cover", "value": "",
#                 "period": { "value": "42", "index": 1 } }  → period.value path
```

## Targeted fix (pending probe confirmation)

If shape (B) is confirmed:

```kotlin
// sonar/.../service/CoverageMapper.kt:18
val measures = comp.measures.associate { it.metric to it.effectiveValue() }
```

…and a fixture file shaped like (B) plus a test:

```kotlin
@Test
fun `maps new_* metrics from period.value when additionalFields=period was requested`() {
    val components = listOf(
        SonarMeasureComponentDto(
            key = "k", path = "src/F.kt",
            measures = listOf(
                SonarMeasureDto("new_lines_to_cover", value = "",
                    period = SonarMeasurePeriodDto(value = "42")),
                SonarMeasureDto("new_branch_coverage", value = "",
                    period = SonarMeasurePeriodDto(value = "50.0"))
            )
        )
    )
    val file = CoverageMapper.mapMeasures(components, "test")["src/F.kt"]!!
    assertEquals(42, file.newLinesToCover)
    assertEquals(50.0, file.newBranchCoverage!!, 0.01)
}
```

This would have failed before the one-line fix. A passing test on shape-B
data is what would actually pin the regression.

## Other services — probe coverage status

The probe already covers 5 services with reasonable endpoint coverage. With
the `--raw` flag, each can now be used to verify any DTO shape mismatch
similar to the SonarQube one. Quick map:

| Service | Endpoints probed | Plugin DTO at risk |
|---|---|---|
| Bamboo | currentUser, plans, projects, plan_branches, plan_variables, latest_result, recent_results, branch_latest_result, build_result, build_log, build_artifacts, job_result, job_log, job_test_results, job_artifacts | `BambooBuildResultDto`, `BambooJobResultDto`, `BambooBranchDto` |
| Jira | myself, boards, sprints, sprint_issues, issue_detail, issue_transitions, issue_comments, issue_worklogs, search, dev_status_branches, dev_status_prs | `JiraIssueDto`, `JiraTransitionDto`, `JiraSprintDto` |
| Bitbucket | whoami, projects, branches, default_branch, prs_open, prs_merged, users, pr_detail, pr_activities, pr_merge_status, pr_changes, pr_commits, pr_diff, merge_strategies, build_status | `BitbucketPullRequestDto`, `BitbucketActivityDto`, `BitbucketDiffDto` |
| Sonar | (above) | `SonarMeasureDto`, `SonarQualityGateDto`, `SonarIssueDto` |
| Docker (Nexus) | v2_check, tags_list, manifest_check | `DockerTagListDto` |

Pre-existing audit notes reference Bitbucket gaps
(`reference_bitbucket_tools_audit_result.md`: 6 F-HIGH + 3 F-MED + 15 UNTESTED;
25.0% broken-or-missing rate). Worth a follow-up pass once the Coverage tab
is fixed.

## Scope guard

This handoff is about the Quality tab NEW CODE bug. Other findings
(Bitbucket coverage gaps, etc.) are flagged here but not in scope for the
v0.83.67 fix. They can be picked up independently using the same probe.
