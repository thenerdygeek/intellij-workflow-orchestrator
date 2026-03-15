# Automation Tab — Full Wiring Spec

## Problem

The Automation tab has UI panels (TagStagingPanel, QueueStatusPanel, SuiteConfigPanel) but they are not wired to backend services. Buttons do nothing, tables are empty, no suites are selectable.

## Solution

Wire the Automation tab end-to-end: Settings for suite config, baseline fetching from Bamboo, docker tag extraction from build logs, tag staging with editing, variable overrides, trigger/queue, and unified monitor view.

## Settings (one-time setup)

### Per-project settings (PluginSettings)

```
Docker tag key for this repo: [order-service]
```

Maps the current IntelliJ project to its key in `dockerTagsAsJson`. Project-level setting.

```
Service CI plan key:    [PROJ-MYSERVICE]
```

The Bamboo plan key for this repo's own CI build (NOT the automation suite). Used to find the latest build and extract the docker tag from the build log.

### App-level settings (AutomationSettingsService)

Suite configurations persisted at IDE level (across all projects):

```
Automation Suites:
  [+ Add Suite]
  ┌─────────────────────────────────────────────┐
  │ Display Name: [Regression Suite           ] │
  │ Plan Key:     [PROJ-REGRESSION            ] │
  │ [Remove]                                    │
  ├─────────────────────────────────────────────┤
  │ Display Name: [E2E Payments               ] │
  │ Plan Key:     [PROJ-E2E-PAYMENTS          ] │
  │ [Remove]                                    │
  └─────────────────────────────────────────────┘
```

Each suite stores: planKey, displayName, last-used variables, last-used stages.

## Layout

Two sub-tabs: **Configure** and **Monitor**.

Header bar: Suite dropdown + status indicator + action buttons (Validate, Update to Latest, Queue, Trigger).

### Configure tab

Left (65%): Tag staging table — all services with editable docker tags.
Right (35%): Plan variables (editable, with defaults) + suite info + running on suite.

### Monitor tab

Unified view of all runs across all suites.
Left: compact run list (your runs + other runs).
Right: selected run detail — stages, failed tests, actions.

## Data Flow

### On suite selection (Configure tab)

```
User selects suite from dropdown
  → Fetch last 10 builds: GET /rest/api/latest/result/{suitePlanKey}?max-results=10&expand=variables
  → Score each run: count release tags (semver pattern) vs docker tags
  → Pick best baseline (most release tags)
  → Extract dockerTagsAsJson from baseline's build variables
  → Parse JSON into tag entries → populate table
  → Auto-replace current repo's tag:
    1. Get docker tag key from project settings
    2. Get current branch's docker tag from build log:
       - Fetch build log for latest PR build
       - Regex: Unique Docker Tag\s*:\s*(.+)
    3. Replace the matching key's value in the table
    4. Highlight row green with "Your branch" badge
  → Fetch plan variables: GET /rest/api/latest/plan/{suitePlanKey}/variable
  → Populate variables panel with defaults
```

### On trigger

```
User clicks "Trigger Now"
  → Build dockerTagsAsJson from table entries
  → Merge with variable overrides
  → POST /rest/api/latest/queue/{suitePlanKey}
    body: { variables: { dockerTagsAsJson: "...", environment: "staging", ... } }
  → Get buildResultKey from response
  → Switch to Monitor tab
  → Start polling build status
  → Emit AutomationTriggered event
```

### On queue

```
User clicks "Queue Run"
  → Same as trigger but:
    1. Check if suite is currently running
    2. If running → add to local queue (QueueService)
    3. Poll until suite becomes idle → auto-trigger
    4. Show queue position + wait estimate in Monitor tab
```

## Baseline Scoring

Uses a weighted formula (matches existing `TagBuilderService.scoreAndRankRuns()`):

```kotlin
fun scoreRun(dockerTagsJson: String, successfulStages: Int, failedStages: Int): Int {
    val tags = Json.parseToJsonElement(dockerTagsJson).jsonObject
    var releaseCount = 0
    for ((_, value) in tags) {
        val tag = value.jsonPrimitive.content
        if (tag.matches(Regex("^\\d+\\.\\d+\\.\\d+.*"))) {
            releaseCount++
        }
    }
    return (releaseCount * 10) + (successfulStages * 5) - (failedStages * 20)
}
```

Pick the run with highest score. Tie-break: most recent (use actual `buildStartedTime` from API, not `Instant.now()`).

## Docker Tag Extraction

The current repo's docker tag is extracted from its **CI build log** (the Artifacts build triggered by the PR), NOT from the automation suite.

### Additional settings needed

```
Settings > Tools > Workflow Orchestrator > Advanced
  Service CI plan key: [PROJ-MYSERVICE]    ← the Bamboo plan that builds THIS repo
```

This is different from the automation suite plan keys. This is the repo's own build plan.

### Extraction flow

```
1. Use the service CI plan key + current branch to get latest build:
   GET /rest/api/latest/result/{serviceCiPlanKey}/branch/{branchName}/latest
   (or use the branch plan key from build status, same as Build tab auto-detect)
2. Fetch build log: GET /download/{jobResultKey}/build_logs/{jobResultKey}.log
3. Extract: Regex("Unique Docker Tag\\s*:\\s*(.+)")
4. Trim result, strip ANSI escape codes
5. If not found → show warning, let user enter manually
```

## Architecture Note

`:automation` depends on `:bamboo` (`implementation(project(":bamboo"))` in build.gradle.kts). This is a pre-existing architecture violation. Accepted as tech debt — the correct fix would be extracting `BambooApiClient` interface + DTOs to `:core`. Not addressed in this spec.

## Cancel vs Stop

Different Bamboo endpoints for different build states:

| Build state | Action | Endpoint |
|---|---|---|
| Queued (not yet running) | Cancel | `DELETE /rest/api/latest/queue/{resultKey}` |
| Running | Stop | `PUT /rest/api/latest/result/{resultKey}/stop` |

The Monitor tab's "Cancel" button must check the build state and use the correct endpoint.

## Tag Table Columns

| Column | Editable | Source |
|---|---|---|
| Service | No | Key from dockerTagsAsJson |
| Docker Tag | **Yes** | Value from baseline, or auto-replaced for your repo |
| Latest Release | No | From baseline scoring (highest semver among last 10 runs) |
| Status | No | "Your branch" / "Release" / "Custom" / "⚠ Modified" |

## Variables Panel

- `dockerTagsAsJson`: auto-generated from table (read-only, shown as collapsed JSON)
- Other plan variables: fetched from `GET /rest/api/latest/plan/{planKey}/variable`, shown with defaults, editable
- `+ Add variable` button for additional overrides

## Monitor Tab

### Your Runs section

Each run card shows:
- Suite name + build number + status badge (Running/Queued/Completed)
- Stage progress as colored chips (✓ Setup ✓ Smoke ⚠ Integration)
- For completed: test summary (1278 passed, 3 failed) + failed test names with error messages
- Actions: Copy Results, Re-trigger, Open in Bamboo ↗
- Left border color: green (passed), yellow (some failed), blue (running), gray (queued), red (all failed)

### Other Runs section

Shows running/queued builds from other users on the same suites.
Fetched via `GET /rest/api/latest/result/{suitePlanKey}?includeAllStates=true&max-results=5`.

### Click to expand

Clicking a run in the left list shows detail in right panel:
- Test summary bar
- Stage chips with duration
- Failed test list with class.method + error message (clickable to source)

## New API Methods (BambooApiClient)

```kotlin
// Already exists or needs adding:
suspend fun getRecentResults(planKey: String, maxResults: Int = 10): ApiResult<List<BambooResultDto>>
suspend fun getBuildVariables(resultKey: String): ApiResult<Map<String, String>>
suspend fun triggerBuild(planKey: String, variables: Map<String, String>): ApiResult<BambooQueueResponse>
suspend fun getRunningAndQueuedBuilds(planKey: String): ApiResult<List<BambooResultDto>>
suspend fun cancelBuild(resultKey: String): ApiResult<Unit>
```

## Files to Modify/Create

### Settings
| File | Change |
|---|---|
| `core/settings/PluginSettings.kt` | Add `dockerTagKey` per-project field |
| `core/settings/AdvancedConfigurable.kt` | Add "Docker tag key" field |
| `automation/service/AutomationSettingsService.kt` | Wire suite CRUD to real UI (already has persistence) |

### Automation tab
| File | Change |
|---|---|
| `automation/ui/AutomationPanel.kt` | Rewrite: sub-tabs, wire services, suite selector |
| `automation/ui/TagStagingPanel.kt` | Wire to TagBuilderService, editable table |
| `automation/ui/SuiteConfigPanel.kt` | Wire to plan variables API, editable fields |
| `automation/ui/QueueStatusPanel.kt` | Wire callbacks, show real status |
| `automation/ui/MonitorPanel.kt` | NEW: unified run monitor |
| `automation/ui/RunDetailPanel.kt` | NEW: expanded run detail with test results |

### Services
| File | Change |
|---|---|
| `automation/service/TagBuilderService.kt` | Wire baseline fetching, scoring, tag extraction |
| `automation/service/QueueService.kt` | Wire trigger, polling, auto-trigger |
| `bamboo/api/BambooApiClient.kt` | Add getRecentResults, getBuildVariables, triggerBuild, cancelBuild |

## Edge Cases

- **No suites configured**: Show "Configure automation suites in Settings" message
- **Bamboo plan not found**: Show error, allow manual plan key correction
- **No baseline runs**: Show empty table with "No previous runs found — enter tags manually"
- **Build log doesn't contain docker tag**: Show warning, let user enter manually
- **All tests failed**: Red border, prominent failure message, Re-trigger button
- **Intermittent failures (common)**: Show failed test count prominently, Copy Results still works
- **Suite busy**: Queue button available, shows position + wait estimate
- **IDE restart**: Recover queue state from SQLite, reconcile with Bamboo

## Monitor Polling Strategy

- Poll interval: 15 seconds for running builds, 60 seconds for queued
- Max poll duration: none (polls until build completes or user cancels)
- Multiple suites: single polling loop iterates all active/queued entries
- On build complete: fetch test results, emit AutomationFinished event, show notification
